// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.service.project.wizard.groovy

import com.intellij.ide.projectWizard.NewProjectWizardCollector.BuildSystem.logAddSampleCodeChanged
import com.intellij.ide.projectWizard.NewProjectWizardConstants.BuildSystem.GRADLE
import com.intellij.ide.projectWizard.generators.AssetsNewProjectWizardStep
import com.intellij.ide.starters.local.StandardAssetsProvider
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.name
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.path
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardStep.Companion.ADD_SAMPLE_CODE_PROPERTY_NAME
import com.intellij.ide.wizard.chain
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.observable.util.bindBooleanStorage
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.distribution.LocalDistributionInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.UIBundle
import com.intellij.ui.dsl.builder.*
import org.jetbrains.plugins.gradle.service.project.wizard.GradleNewProjectWizardData.GradleDsl
import org.jetbrains.plugins.gradle.service.project.wizard.GradleNewProjectWizardStep
import org.jetbrains.plugins.gradle.service.project.wizard.generateModuleBuilder
import org.jetbrains.plugins.groovy.GroovyBundle
import org.jetbrains.plugins.groovy.config.GroovyHomeKind
import org.jetbrains.plugins.groovy.config.wizard.*

class GradleGroovyNewProjectWizard : BuildSystemGroovyNewProjectWizard {

  override val name = GRADLE

  override val ordinal = 200

  override fun createStep(parent: GroovyNewProjectWizard.Step): NewProjectWizardStep = Step(parent).chain(::AssetsStep)

  class Step(parent: GroovyNewProjectWizard.Step) :
    GradleNewProjectWizardStep<GroovyNewProjectWizard.Step>(parent),
    BuildSystemGroovyNewProjectWizardData by parent {

    private val addSampleCodeProperty = propertyGraph.property(true)
      .bindBooleanStorage(ADD_SAMPLE_CODE_PROPERTY_NAME)

    private var addSampleCode by addSampleCodeProperty

    init {
      gradleDsl = GradleDsl.GROOVY
    }

    override fun setupSettingsUI(builder: Panel) {
      super.setupSettingsUI(builder)
      with(builder) {
        row(GroovyBundle.message("label.groovy.sdk")) {
          groovySdkComboBox(context, groovySdkProperty)
        }.bottomGap(BottomGap.SMALL)
        row {
          checkBox(UIBundle.message("label.project.wizard.new.project.add.sample.code"))
            .bindSelected(addSampleCodeProperty)
            .whenStateChangedFromUi { logAddSampleCodeChanged(it) }
        }.topGap(TopGap.SMALL)
      }
    }

    override fun setupProject(project: Project) {
      super.setupProject(project)

      val builder = generateModuleBuilder()
      builder.gradleVersion = suggestGradleVersion()

      builder.configureBuildScript {
        when (val groovySdk = groovySdk) {
          null -> it.withPlugin("groovy")
          is FrameworkLibraryDistributionInfo -> it.withGroovyPlugin(groovySdk.version.versionString)
          is LocalDistributionInfo -> {
            it.withPlugin("groovy")
            it.withMavenCentral()
            when (val groovySdkKind = GroovyHomeKind.fromString(groovySdk.path)) {
              null -> it.addImplementationDependency(it.call("files", groovySdk.path))
              else -> it.addImplementationDependency(it.call("fileTree", groovySdkKind.jarsPath) {
                for (subdir in groovySdkKind.subPaths) {
                  call("include", subdir)
                }
              })
            }
          }
        }
        it.withJUnit()
      }

      ExternalProjectsManagerImpl.setupCreatedProject(project)
      builder.commit(project)
      if (addSampleCode) {
        runWriteAction {
          val groovySourcesDirectory = builder.contentEntryPath + "/src/main/groovy"
          val directory = VfsUtil.createDirectoryIfMissing(groovySourcesDirectory)
          if (directory != null) {
            builder.createSampleGroovyCodeFile(project, directory)
          }
        }
      }
    }
  }

  private class AssetsStep(parent: NewProjectWizardStep) : AssetsNewProjectWizardStep(parent) {
    override fun setupAssets(project: Project) {
      outputDirectory = "$path/$name"
      addAssets(StandardAssetsProvider().getGradleIgnoreAssets())
    }
  }
}