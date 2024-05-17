package org.example


import edu.ucr.cs.riple.core.Annotator
import edu.ucr.cs.riple.core.Config

import org.apache.maven.artifact.DependencyResolutionRequiredException
import org.apache.maven.model.Plugin
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.codehaus.plexus.util.xml.Xpp3DomBuilder
import org.codehaus.plexus.util.xml.pull.MXSerializer
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.*


@Mojo(name = "add-annotation-processor")
class AddAnnotationProcessorMojo : AbstractMojo() {
    companion object {

        private const val MAVEN_COMPILER_PLUGIN = "maven-compiler-plugin"
        private const val CONFIGURATION = "configuration"
        private const val ANNOTATION_PROCESSOR_PATHS = "annotationProcessorPaths"
        private const val COMPILER_ARGS = "compilerArgs"
        private const val PATH = "path"
        private const val GROUP_ID = "groupId"
        private const val ARTIFACT_ID = "artifactId"
        private const val VERSION = "version"
        private val OUT_DIR = Files.createTempDirectory("annotator_temp")
        private val ANNOTATOR_DIR = OUT_DIR.resolve("annotator")

        private val PATHS_TSV = ANNOTATOR_DIR.resolve("paths.tsv")

        private val initializerClass = "com.uber.nullaway.annotations.Initializer"

        //        create OUT_DIR/annotator
        init {
            ANNOTATOR_DIR.createDirectory()
        }

    }

    @Parameter(defaultValue = "\${project.build.directory}", readonly = true)
    private val projectBuildDir: File? = null

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private val project: MavenProject? = null


    @Parameter(defaultValue = "edu.ucr.cs.riple.annotator", required = true)
    private val groupId: String? = null

    @Parameter(defaultValue = "annotator-scanner", required = true)
    private val artifactId: String? = null

    @Parameter(defaultValue = "1.3.8", required = true)
    private val version: String? = null

    //    needed for File.deleteRecursively()
    @OptIn(ExperimentalPathApi::class)
    @Throws(MojoExecutionException::class)
    override fun execute() {
        try {
//            find the compiler plugin to make changes to it and add the annotation processor path
            backUpTargetsPom()
            val compilerPlugin = findCompilerPlugin()
            if (compilerPlugin != null) {
                modifyAnnotationProcessorPath(compilerPlugin)
                addEditedConfigToCompilerPlugin(compilerPlugin)
//                printModifiedProjectPOM()
            }else{
                throw MojoExecutionException("Maven Compiler Plugin is not added to the target project. Nullaway and Errorprone need to be added to the project!")
            }
            updateTargetPomWithModifiedCompilerPlugin()

//            updateTargetPomWithModifiedCompilerPlugin()
            writePathsToTsv()
            callAnnotator()
            restoreOriginalPom()

            OUT_DIR.deleteRecursively()
        } catch (e: DependencyResolutionRequiredException) {
            throw MojoExecutionException("Failed to modify annotation processor path", e)
        }
    }

    private fun callAnnotator() {
        val mvnCommand = "cd ${project!!.basedir} && mvn compile -DskipTests"

        val buildCommand = listOf(
            "-d", ANNOTATOR_DIR.toString(),
            "-cp", PATHS_TSV.toString(),
            "-i", initializerClass,
            "--build-command", mvnCommand,
            "-cn", "NULLAWAY",
            "-rboserr"
        )

        val config = Config(buildCommand.toTypedArray())
        val annotator = Annotator(config)
        annotator.start()
    }

    private fun printModifiedProjectPOM() {
        val compilerPlugin = findCompilerPlugin()
        val config = compilerPlugin.configuration as? Xpp3Dom
        val compilerArgs = config?.getChild(COMPILER_ARGS)
        val annotationProcessorPaths = config?.getChild(ANNOTATION_PROCESSOR_PATHS)
        val path = annotationProcessorPaths?.getChild(PATH)
        val groupId = path?.getChild(GROUP_ID)
        val artifactId = path?.getChild(ARTIFACT_ID)
        val version = path?.getChild(VERSION)
        println("Modified project POM:")
        println(compilerPlugin)
        println(config)
    }


    private fun addEditedConfigToCompilerPlugin(compilerPlugin: Plugin) {
        val config = compilerPlugin.configuration as? Xpp3Dom
            ?: Xpp3Dom(CONFIGURATION).also { compilerPlugin.configuration = it }

        var compilerArgs = config.getChild(COMPILER_ARGS)
            ?: Xpp3Dom(COMPILER_ARGS).also {
                config.addChild(it)
                compilerPlugin.configuration = config // Write back the changes
            }

        // Update the child in compilerArgs that has the word "nullaway" in it
        compilerArgs.children
            .filter { it.value.contains("-XepOpt:NullAway") }
            .forEach { child ->
                child.value += editScannerAndNullAwayCompilerFlags()
            }
    }


    private fun findCompilerPlugin(): Plugin {
        return project!!.buildPlugins.stream()
            .filter { plugin: Plugin -> MAVEN_COMPILER_PLUGIN == plugin.artifactId }
            .findFirst()
            .orElse(null)
    }

    private fun updateTargetPomWithModifiedCompilerPlugin() {
        val compilerPlugin = findCompilerPlugin()
        val config = compilerPlugin.configuration as? Xpp3Dom
            ?: throw MojoExecutionException("Compiler plugin configuration not found")

        val targetPom = project!!.file
        val pomReader = MavenXpp3Reader()
        val pomWriter = MavenXpp3Writer()

        // Read the existing pom.xml into a model
        val model = FileReader(targetPom).use { pomReader.read(it) }

        // Find the compiler plugin in the model and update its configuration
        model.build.plugins.find { it.artifactId == MAVEN_COMPILER_PLUGIN }?.configuration = config

        // Write the modified model back to pom.xml
        FileWriter(targetPom).use { writer -> pomWriter.write(writer, model) }
    }


    private fun modifyAnnotationProcessorPath(compilerPlugin: Plugin) {
        if (compilerPlugin.configuration == null) {
            throw NotImplementedError("Maven Compiler Plugin is not added to the target project!")
        }
        var config = compilerPlugin.configuration as Xpp3Dom
        if (config == null) {
            config = Xpp3Dom(CONFIGURATION)
            compilerPlugin.configuration = config
        }
        var annotationProcessorPaths = config.getChild(ANNOTATION_PROCESSOR_PATHS)
        if (annotationProcessorPaths == null) {
            annotationProcessorPaths = Xpp3Dom(ANNOTATION_PROCESSOR_PATHS)
            config.addChild(annotationProcessorPaths)
        }
        val path = Xpp3Dom(PATH)
        annotationProcessorPaths.addChild(path)
//        adds scanner here
        addNode(path, GROUP_ID, groupId)
        addNode(path, ARTIFACT_ID, artifactId)
        addNode(path, VERSION, version)

    }

    private fun editScannerAndNullAwayCompilerFlags(): String {
        val scannerConfigPath =
            "-XepOpt:AnnotatorScanner:ConfigPath=" + ANNOTATOR_DIR.resolve("scanner.xml")
        val nullawayConfigPath =
            "-XepOpt:NullAway:FixSerializationConfigPath=" +ANNOTATOR_DIR.resolve("nullaway.xml")
        val nullawaySerializer = "-XepOpt:NullAway:SerializeFixMetadata=true"

        val scannerCheck = "-Xep:AnnotatorScanner:ERROR"
        return (" $scannerCheck $scannerConfigPath $nullawayConfigPath $nullawaySerializer")
    }

//    this function is supposed to back up the targets POM.xml to the annotator directory
    private fun backUpTargetsPom(){
        val targetPom = project!!.file
        val targetPomBackup = ANNOTATOR_DIR.resolve("pom.xml")
        Files.copy(targetPom.toPath(), targetPomBackup)
    }
    private fun restoreOriginalPom() {
        val targetPom = project!!.file
        val targetPomBackup = ANNOTATOR_DIR.resolve("pom.xml")
        Files.copy(targetPomBackup, targetPom.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writePathsToTsv() {
        try {
            val scannerConfigPath = ANNOTATOR_DIR.resolve("scanner.xml")
            val nullawayConfigPath = ANNOTATOR_DIR.resolve("nullaway.xml")
//            create paths.tsv in AnnotatorDir
            val pathsTsv = PATHS_TSV.createFile()
            pathsTsv.bufferedWriter().use { writer ->
                writer.write("$nullawayConfigPath\t$scannerConfigPath")
                writer.close()
            }
        } catch (e: FileSystemException) {
            println("FS Error: $e")
        }
    }
    private fun addNode(parent: Xpp3Dom, name: String, value: String?) {
        val node = Xpp3Dom(name)
        node.value = value
        parent.addChild(node)
    }
}