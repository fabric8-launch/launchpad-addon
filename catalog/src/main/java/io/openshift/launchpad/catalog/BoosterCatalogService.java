/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.openshift.launchpad.catalog;

import static io.openshift.launchpad.catalog.util.Files.removeFileExtension;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import io.openshift.launchpad.catalog.util.CopyFileVisitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.yaml.snakeyaml.Yaml;

/**
 * This service reads from the Booster catalog Github repository in https://github.com/openshiftio/booster-catalog and
 * marshalls into {@link Booster} objects.
 * 
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
@Singleton
public class BoosterCatalogService
{
   private static final String GITHUB_URL = "https://github.com/";
   private static final String CATALOG_INDEX_PERIOD_PROPERTY_NAME = "LAUNCHPAD_BACKEND_CATALOG_INDEX_PERIOD";
   private static final String CATALOG_GIT_REF_PROPERTY_NAME = "LAUNCHPAD_BACKEND_CATALOG_GIT_REF";
   private static final String CATALOG_GIT_REPOSITORY_PROPERTY_NAME = "LAUNCHPAD_BACKEND_CATALOG_GIT_REPOSITORY";

   private static final String DEFAULT_INDEX_PERIOD = "0";
   private static final String DEFAULT_GIT_REF = "master";
   private static final String DEFAULT_GIT_REPOSITORY_URL = "https://github.com/openshiftio/booster-catalog.git";

   private static final String CLONED_BOOSTERS_DIR = ".boosters";
   private static final String METADATA_FILE = "metadata.json";

   private static final Yaml yaml = new Yaml();
   /**
    * Files to be excluded from project creation
    */
   private static final List<String> EXCLUDED_PROJECT_FILES = Arrays.asList(".git", ".travis", ".travis.yml",
            ".ds_store",
            ".obsidian", ".gitmodules");

   private static final Logger logger = Logger.getLogger(BoosterCatalogService.class.getName());

   private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();

   private volatile List<Booster> boosters = Collections.emptyList();

   private ScheduledExecutorService executorService;

   /**
    * Clones the catalog git repository and reads the obsidian metadata on each quickstart repository
    */
   private void index()
   {
      WriteLock lock = reentrantLock.writeLock();
      try
      {
         lock.lock();
         String catalogRepositoryURI = getEnvVarOrSysProp(CATALOG_GIT_REPOSITORY_PROPERTY_NAME,
                  DEFAULT_GIT_REPOSITORY_URL);
         String catalogRef = getEnvVarOrSysProp(CATALOG_GIT_REF_PROPERTY_NAME, DEFAULT_GIT_REF);
         logger.log(Level.INFO, "Indexing contents from {0} using {1} ref",
                  new Object[] { catalogRepositoryURI, catalogRef });
         Path catalogPath = Files.createTempDirectory("booster-catalog");
         // Remove this directory on JVM termination
         catalogPath.toFile().deleteOnExit();
         logger.info(() -> "Created " + catalogPath);
         // Clone repository
         Git.cloneRepository()
                  .setURI(catalogRepositoryURI)
                  .setBranch(catalogRef)
                  .setCloneSubmodules(true)
                  .setDirectory(catalogPath.toFile())
                  .call().close();
         this.boosters = indexBoosters(catalogPath);
      }
      catch (GitAPIException e)
      {
         logger.log(Level.SEVERE, "Error while performing Git operation", e);
      }
      catch (Exception e)
      {
         logger.log(Level.SEVERE, "Error while indexing", e);
      }
      finally
      {
         logger.info(() -> "Finished content indexing");
         lock.unlock();
      }
   }

   /**
    * @param moduleRoot
    * @return
    * @throws IOException
    */
   private List<Booster> indexBoosters(Path catalogPath) throws IOException
   {
      Path moduleRoot = catalogPath.resolve(CLONED_BOOSTERS_DIR);
      Path metadataFile = catalogPath.resolve(METADATA_FILE);
      List<Booster> boosters = new ArrayList<>();
      Map<String, Mission> missions = new HashMap<>();
      Map<String, Runtime> runtimes = new HashMap<>();
      if (Files.exists(metadataFile))
      {
         processMetadata(metadataFile, missions, runtimes);
      }
      Files.walkFileTree(catalogPath, new SimpleFileVisitor<Path>()
      {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
         {
            File ioFile = file.toFile();
            String fileName = ioFile.getName().toLowerCase();
            // Skip any file that starts with .
            if (!fileName.startsWith(".") && (fileName.endsWith(".yaml") || fileName.endsWith(".yml")))
            {
               String id = removeFileExtension(fileName);
               Path modulePath = moduleRoot.resolve(id);
               indexBooster(id, file, modulePath, missions, runtimes).ifPresent(boosters::add);
            }
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
         {
            return dir.startsWith(moduleRoot) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
         }
      });
      boosters.sort(Comparator.comparing(Booster::getName));
      return Collections.unmodifiableList(boosters);
   }

   /**
    * Process the metadataFile and adds to the specified missions and runtimes maps
    * 
    * @param metadataFile
    * @param missions
    * @param runtimes
    */
   void processMetadata(Path metadataFile, Map<String, Mission> missions, Map<String, Runtime> runtimes)
   {
      logger.info(() -> "Reading metadata at " + metadataFile + " ...");

      try (BufferedReader reader = Files.newBufferedReader(metadataFile);
               JsonReader jsonReader = Json.createReader(reader))
      {
         JsonObject index = jsonReader.readObject();
         index.getJsonArray("missions")
                  .stream()
                  .map(JsonObject.class::cast)
                  .map(e -> new Mission(e.getString("id"), e.getString("name")))
                  .forEach(m -> missions.put(m.getId(), m));

         index.getJsonArray("runtimes")
                  .stream()
                  .map(JsonObject.class::cast)
                  .map(e -> new Runtime(e.getString("id"), e.getString("name")))
                  .forEach(r -> runtimes.put(r.getId(), r));
      }
      catch (IOException e)
      {
         logger.log(Level.SEVERE, "Error while processing metadata " + metadataFile, e);
      }
   }

   /**
    * Takes a YAML file from the repository and indexes it
    * 
    * @param file A YAML file from the booster-catalog repository
    * @return an {@link Optional} containing a {@link Booster}
    */
   @SuppressWarnings("unchecked")
   private Optional<Booster> indexBooster(String id, Path file, Path moduleDir, Map<String, Mission> missions,
            Map<String, Runtime> runtimes)
   {
      logger.info(() -> "Indexing " + file + " ...");

      Booster booster = null;
      try (BufferedReader reader = Files.newBufferedReader(file))
      {
         // Read YAML entry
         booster = yaml.loadAs(reader, Booster.class);
      }
      catch (IOException e)
      {
         logger.log(Level.SEVERE, "Error while reading " + file, e);
      }
      if (booster != null)
      {
         try
         {
            // Booster ID = filename without extension
            booster.setId(id);

            String runtimeId = file.getParent().toFile().getName();
            String missionId = file.getParent().getParent().toFile().getName();

            booster.setMission(missions.computeIfAbsent(missionId, Mission::new));
            booster.setRuntime(runtimes.computeIfAbsent(runtimeId, Runtime::new));

            booster.setContentPath(moduleDir);
            // Module does not exist. Clone it
            if (Files.notExists(moduleDir))
            {
               try (Git git = Git.cloneRepository()
                        .setDirectory(moduleDir.toFile())
                        .setURI(GITHUB_URL + booster.getGithubRepo())
                        .setCloneSubmodules(true)
                        .setBranch(booster.getGitRef())
                        .call())
               {
                  // Checkout on specified start point
                  git.checkout()
                           .setName(booster.getGitRef())
                           .setStartPoint(booster.getGitRef())
                           .call();
               }
            }
            Path metadataPath = moduleDir.resolve(booster.getBoosterDescriptorPath());
            try (BufferedReader metadataReader = Files.newBufferedReader(metadataPath))
            {
               Map<String, Object> metadata = yaml.loadAs(metadataReader, Map.class);
               booster.setMetadata(metadata);
            }

            Path descriptionPath = moduleDir.resolve(booster.getBoosterDescriptionPath());
            if (Files.exists(descriptionPath))
            {
               byte[] descriptionContent = Files.readAllBytes(descriptionPath);
               booster.setDescription(new String(descriptionContent));
            }
         }
         catch (GitAPIException gitException)
         {
            logger.log(Level.SEVERE, "Error while reading git repository", gitException);
         }
         catch (Exception e)
         {
            logger.log(Level.SEVERE, "Error while reading metadata from " + file, e);
         }
      }
      return Optional.ofNullable(booster);
   }

   @PostConstruct
   void init()
   {
      long indexPeriod = Long.parseLong(getEnvVarOrSysProp(CATALOG_INDEX_PERIOD_PROPERTY_NAME, DEFAULT_INDEX_PERIOD));
      if (indexPeriod > 0L)
      {
         executorService = Executors.newScheduledThreadPool(1);
         logger.info(() -> "Indexing every " + indexPeriod + " minutes");
         executorService.scheduleAtFixedRate(this::index, 0, indexPeriod, TimeUnit.MINUTES);
      }
      else
      {
         index();
      }
   }

   @PreDestroy
   void destroy()
   {
      if (executorService != null)
      {
         executorService.shutdown();
      }
   }

   private static String getEnvVarOrSysProp(String name, String defaultValue)
   {
      return System.getProperty(name, System.getenv().getOrDefault(name, defaultValue));
   }

   /**
    * Copies the {@link Booster} contents to the specified {@link Path}
    */
   public Path copy(Booster booster, Path projectRoot) throws IOException
   {
      Path modulePath = booster.getContentPath();
      return Files.walkFileTree(modulePath,
               new CopyFileVisitor(projectRoot,
                                   (p) -> !EXCLUDED_PROJECT_FILES.contains(p.toFile().getName().toLowerCase())));
   }

   public Set<Mission> getMissions()
   {
      return boosters.stream()
               .map(Booster::getMission)
               .collect(Collectors.toCollection(TreeSet::new));
   }

   public Set<Runtime> getRuntimes(Mission mission)
   {
      if (mission == null)
      {
         return Collections.emptySet();
      }
      return boosters.stream()
               .filter(b -> mission.equals(b.getMission()))
               .map(Booster::getRuntime)
               .collect(Collectors.toCollection(TreeSet::new));
   }

   public Optional<Booster> getBooster(Mission mission, Runtime runtime)
   {
      Objects.requireNonNull(mission, "Mission should not be null");
      Objects.requireNonNull(runtime, "Runtime should not be null");
      return boosters.stream()
               .filter(b -> mission.equals(b.getMission()))
               .filter(b -> runtime.equals(b.getRuntime()))
               .findFirst();
   }

   public List<Booster> getBoosters()
   {
      Lock readLock = reentrantLock.readLock();
      try
      {
         readLock.lock();
         return boosters;
      }
      finally
      {
         readLock.unlock();
      }
   }
}
