package org.example

import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import java.util.function.Consumer

@Mojo(name = "detect-nullaway")
class DependencyDetectionMojo : AbstractMojo() {

    companion object {
        private const val MAVEN_COMPILER_PLUGIN = "maven-compiler-plugin"
        private const val ANNOTATION_PROCESSOR_PATHS = "annotationProcessorPaths"
        private const val GROUP_ID = "groupId"
        private const val ARTIFACT_ID = "artifactId"
        private const val VERSION = "version"
        private const val ERROR_PRONE_GROUP_ID = "com.google.errorprone"
        private const val ERROR_PRONE_ARTIFACT_ID = "error_prone_core"
        private const val NULLAWAY_GROUP_ID = "com.uber.nullaway"
        private const val NULLAWAY_ARTIFACT_ID = "nullaway"
    }

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private val project: MavenProject? = null
    @Throws(MojoExecutionException::class)
    override fun execute() {
        val plugins = project!!.buildPlugins
        if (plugins.isEmpty()) {
            log.info("No plugins found in the processor path.")
        } else {
            plugins.forEach(Consumer { plugin: Plugin ->
                // Check if this is the maven-compiler-plugin
                if (MAVEN_COMPILER_PLUGIN == plugin.artifactId) {
                    log.info(plugin.toString());
                    checkForErrorProneAndNullAway(plugin)
                }
            })
        }
    }

    private fun checkForErrorProneAndNullAway(plugin: Plugin) {
        plugin.executions.forEach(Consumer { execution: PluginExecution ->
            val configuration = execution.configuration as Xpp3Dom
            if (configuration != null) {
                val annotationProcessorPaths = configuration.getChild(ANNOTATION_PROCESSOR_PATHS)
                annotationProcessorPaths?.let { checkAnnotationProcessorPaths(it) }
            }
        })
    }

    private fun checkAnnotationProcessorPaths(annotationProcessorPaths: Xpp3Dom) {
        for (i in 0 until annotationProcessorPaths.childCount) {
            val child = annotationProcessorPaths.getChild(i)
            val groupId = child.getChild(GROUP_ID)
            val artifactId = child.getChild(ARTIFACT_ID)
            val version = child.getChild(VERSION)
            if (groupId != null && artifactId != null && version != null) {
                if (ERROR_PRONE_GROUP_ID == groupId.value && ERROR_PRONE_ARTIFACT_ID == artifactId.value) {
                    log.info("Found Error Prone: " + version.value)
                } else if (NULLAWAY_GROUP_ID == groupId.value && NULLAWAY_ARTIFACT_ID == artifactId.value) {
                    log.info("Found NullAway: " + version.value)
                }
            }
        }
    }
}