/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.openshift.launchpad.ui.booster;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.jboss.forge.addon.maven.resources.MavenModelResource;
import org.jboss.forge.addon.parser.json.resource.JsonResource;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UIValidationContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizardStep;
import org.jboss.forge.furnace.util.Strings;

import io.openshift.booster.catalog.Booster;
import io.openshift.booster.catalog.BoosterCatalogService;
import io.openshift.booster.catalog.Mission;
import io.openshift.booster.catalog.Runtime;
import io.openshift.booster.catalog.Version;
import io.openshift.launchpad.ReadmeProcessor;
import io.openshift.launchpad.ui.input.ProjectName;

/**
 *
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
public class ProjectInfoStep implements UIWizardStep
{
   private static final Logger logger = Logger.getLogger(ProjectInfoStep.class.getName());

   @Inject
   private BoosterCatalogService catalogService;

   @Inject
   @WithAttributes(label = "Runtime Version")
   private UISelectOne<Version> runtimeVersion;

   @Inject
   private ProjectName named;

   @Inject
   private MissionControlValidator missionControlValidator;

   /**
    * Used in LaunchpadResource
    */
   @Inject
   @WithAttributes(label = "GitHub Repository", note = "If empty, it will assume the project name")
   private UIInput<String> gitHubRepositoryName;

   @Inject
   @WithAttributes(label = "Group Id", defaultValue = "io.openshift.booster", required = true)
   private UIInput<String> groupId;

   @Inject
   @WithAttributes(label = "Artifact Id", required = true)
   private UIInput<String> artifactId;

   @Inject
   @WithAttributes(label = "Version", required = true, defaultValue = "1.0.0-SNAPSHOT")
   private UIInput<String> version;

   @Inject
   private ReadmeProcessor readmeProcessor;

   @Override
   public void initializeUI(UIBuilder builder) throws Exception
   {
      UIContext context = builder.getUIContext();
      Mission mission = (Mission) context.getAttributeMap().get(Mission.class);
      Runtime runtime = (Runtime) context.getAttributeMap().get(Runtime.class);
      artifactId.setDefaultValue(() -> {

         String missionPrefix = (mission == null) ? "" : "-" + mission.getId();
         String runtimeSuffix = (runtime == null) ? "" : "-" + runtime.getId().replaceAll("\\.", "");

         return "booster" + missionPrefix + runtimeSuffix;
      });
      DeploymentType deploymentType = (DeploymentType) context.getAttributeMap().get(DeploymentType.class);
      if (deploymentType == DeploymentType.CONTINUOUS_DELIVERY)
      {
         if (mission != null && runtime != null) {
            Set<Version> versions = catalogService.getVersions(mission, runtime);
            if (versions != null && !versions.isEmpty()) {
               runtimeVersion.setValueChoices(versions);
               runtimeVersion.setItemLabelConverter(Version::getName);
               runtimeVersion.setDefaultValue(versions.iterator().next());
               builder.add(runtimeVersion);
            }
         }
         builder.add(named).add(gitHubRepositoryName);
      }
      if (isNodeJS(runtime))
      {
         // NodeJS only requires the name and version
         artifactId.setLabel("Name");
         version.setDefaultValue("1.0.0");
         builder.add(artifactId).add(version);
      }
      else
      {
         builder.add(groupId).add(artifactId).add(version);
      }
   }

   @Override
   public void validate(UIValidationContext context)
   {
      UIContext uiContext = context.getUIContext();
      if ("next".equals(uiContext.getAttributeMap().get("action")))
      {
         // Do not validate again if next() was called
         return;
      }
      DeploymentType deploymentType = (DeploymentType) uiContext.getAttributeMap()
               .get(DeploymentType.class);
      if (deploymentType == DeploymentType.CONTINUOUS_DELIVERY
               && System.getenv("LAUNCHPAD_MISSION_CONTROL_VALIDATION_SKIP") == null)
      {
         if (missionControlValidator.validateOpenShiftTokenExists(context))
         {
            missionControlValidator.validateOpenShiftProjectExists(context, named.getValue());
         }
         if (missionControlValidator.validateGitHubTokenExists(context))
         {
            String repository = gitHubRepositoryName.getValue();
            if (Strings.isNullOrEmpty(repository))
            {
               repository = named.getValue();
            }
            missionControlValidator.validateGitHubRepositoryExists(context, repository);
         }
      }
   }

   @Override
   public UICommandMetadata getMetadata(UIContext context)
   {
      return Metadata.forCommand(getClass()).name("Project Info")
               .description("Project Information")
               .category(Categories.create("Openshift.io"));
   }

   @SuppressWarnings("unchecked")
   @Override
   public Result execute(UIExecutionContext context) throws Exception
   {
      Map<Object, Object> attributeMap = context.getUIContext().getAttributeMap();
      Mission mission = (Mission) attributeMap.get(Mission.class);
      Runtime runtime = (Runtime) attributeMap.get(Runtime.class);
      DeploymentType deploymentType = (DeploymentType) attributeMap.get(DeploymentType.class);
      Booster booster;
      if (runtimeVersion.getValue() != null) {
          booster = catalogService.getBooster(mission, runtime, runtimeVersion.getValue()).get();
      } else {
          booster = catalogService.getBooster(mission, runtime).get();
      }
      DirectoryResource initialDir = (DirectoryResource) context.getUIContext().getInitialSelection().get();
      String childDirectory = deploymentType == DeploymentType.CONTINUOUS_DELIVERY ? named.getValue()
               : artifactId.getValue();
      DirectoryResource projectDirectory = initialDir.getChildDirectory(childDirectory);
      projectDirectory.mkdirs();
      Path projectDirectoryPath = projectDirectory.getUnderlyingResourceObject().toPath();
      // Copy contents
      catalogService.copy(booster, projectDirectoryPath);
      // Is it a maven project?
      MavenModelResource modelResource = projectDirectory.getChildOfType(MavenModelResource.class, "pom.xml");

      // Perform model changes
      if (modelResource.exists())
      {
         Model model = modelResource.getCurrentModel();
         model.setGroupId(groupId.getValue());
         model.setArtifactId(artifactId.getValue());
         model.setVersion(version.getValue());
         
         Properties props = model.getProperties();
         props.setProperty("launch.mission", mission.getId());
         props.setProperty("launch.runtime", runtime.getId());
         if (runtimeVersion.getValue() != null) {
             props.setProperty("launch.version", runtimeVersion.getValue().getId());
         }
         if (booster.getBuildProfile() != null) {
             props.setProperty("launch.buildProfile", booster.getBuildProfile());
         }

         // Change child modules
         for (String module : model.getModules())
         {
            DirectoryResource moduleDirResource = projectDirectory.getChildDirectory(module);
            MavenModelResource moduleModelResource = moduleDirResource.getChildOfType(MavenModelResource.class,
                     "pom.xml");
            Model moduleModel = moduleModelResource.getCurrentModel();
            Parent parent = moduleModel.getParent();
            if (parent != null)
            {
               parent.setGroupId(model.getGroupId());
               parent.setArtifactId(model.getArtifactId());
               parent.setVersion(model.getVersion());
               moduleModelResource.setCurrentModel(moduleModel);
            }
         }
         modelResource.setCurrentModel(model);
      }

      // If NodeJS, just change name and version
      if (isNodeJS(runtime))
      {
         JsonResource packageJsonResource = projectDirectory.getChildOfType(JsonResource.class, "package.json");
         if (packageJsonResource.exists())
         {
            JsonObjectBuilder job = Json.createObjectBuilder();
            job.add("name", artifactId.getValue());
            job.add("version", version.getValue());
            for (Entry<String, JsonValue> entry : packageJsonResource.getJsonObject().entrySet())
            {
               String key = entry.getKey();
               // Do not copy name or version
               if (key.equals("name") || key.equals("version"))
               {
                  continue;
               }
               job.add(key, entry.getValue());
            }
            packageJsonResource.setContents(job.build());
         }
      }
      // Create README.adoc file
      try
      {
         String template = readmeProcessor.getReadmeTemplate(mission.getId());
         if (template != null)
         {
            Map<String, String> values = new HashMap<>();
            values.put("missionId", mission.getId());
            values.put("mission", mission.getName());
            values.put("runtimeId", runtime.getId());
            values.put("runtime", runtime.getName());
            if (runtimeVersion.getValue() != null) {
                values.put("runtimeVersion", runtimeVersion.getValue().getKey());
            }
            values.put("openShiftProject", named.getValue());
            values.put("groupId", groupId.getValue());
            values.put("artifactId", artifactId.getValue());
            values.put("version", version.getValue());
            values.put("targetRepository", Objects.toString(gitHubRepositoryName.getValue(), named.getValue()));
            values.putAll(readmeProcessor.getRuntimeProperties(mission.getId(), runtime.getId()));
            String readmeOutput = readmeProcessor.processTemplate(template, values);
            projectDirectory.getChildOfType(FileResource.class, "README.adoc").setContents(readmeOutput);
            // Delete README.md
            projectDirectory.getChildOfType(FileResource.class, "README.md").delete();
         }
      }
      catch (Exception e)
      {
         if (e instanceof FileNotFoundException)
         {
            logger.log(Level.WARNING, "No README.adoc template found for " + mission.getId());
         }
         else
         {
            logger.log(Level.SEVERE, "Error while creating README.adoc", e);
         }
      }

      context.getUIContext().setSelection(projectDirectory);
      return Results.success();
   }

   private boolean isNodeJS(Runtime runtime)
   {
      return runtime != null && "nodejs".equals(runtime.getId());
   }
}
