/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.plugins.precompiled.tasks

import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.file.FileCollectionFactory
import org.gradle.api.internal.file.FileCollectionInternal
import org.gradle.api.internal.initialization.ScriptHandlerInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.groovy.scripts.TextResourceScriptSource
import org.gradle.initialization.ClassLoaderScopeRegistry
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.internal.concurrent.CompositeStoppable.stoppable
import org.gradle.internal.hash.HashCode
import org.gradle.internal.resource.BasicTextResourceLoader

import org.gradle.kotlin.dsl.accessors.TypedProjectSchema
import org.gradle.kotlin.dsl.accessors.buildAccessorsFor
import org.gradle.kotlin.dsl.accessors.hashCodeFor
import org.gradle.kotlin.dsl.accessors.schemaFor

import org.gradle.kotlin.dsl.concurrent.IO
import org.gradle.kotlin.dsl.concurrent.withAsynchronousIO
import org.gradle.kotlin.dsl.concurrent.writeFile

import org.gradle.kotlin.dsl.plugins.precompiled.PrecompiledScriptPlugin

import org.gradle.kotlin.dsl.precompile.PrecompiledScriptDependenciesResolver

import org.gradle.kotlin.dsl.support.KotlinScriptType
import org.gradle.kotlin.dsl.support.serviceOf

import org.gradle.plugin.management.internal.DefaultPluginRequests
import org.gradle.plugin.management.internal.PluginRequestInternal
import org.gradle.plugin.management.internal.PluginRequests
import org.gradle.plugin.use.PluginDependenciesSpec
import org.gradle.plugin.use.internal.PluginRequestApplicator
import org.gradle.plugin.use.internal.PluginRequestCollector

import org.gradle.testfixtures.ProjectBuilder

import java.io.File


@CacheableTask
open class GeneratePrecompiledScriptPluginAccessors : ClassPathSensitiveCodeGenerationTask() {

    @get:OutputDirectory
    var metadataOutputDir = directoryProperty()

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    var compiledPluginsBlocksDir = directoryProperty()

    @get:Internal
    internal
    lateinit var plugins: List<PrecompiledScriptPlugin>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @Suppress("unused")
    internal
    val scriptFiles: Set<File>
        get() = scriptPluginFilesOf(plugins)

    /**
     *  ## Computation and sharing of type-safe accessors
     * 1. Group precompiled script plugins by the list of plugins applied in their `plugins` block.
     * 2. For each group, compute the project schema implied by the list of plugins.
     * 3. Re-group precompiled script plugins by project schema.
     * 4. For each group, emit the type-safe accessors implied by the schema to a package named after the schema
     * hash code.
     * 5. For each group, for each script plugin in the group, write the generated package name to a file named
     * after the contents of the script plugin file. This is so the file can be easily found by
     * [PrecompiledScriptDependenciesResolver].
     */
    @TaskAction
    fun generate() {
        withAsynchronousIO(project) {

            recreateOutputDir()

            val projectPlugins = selectProjectPlugins()
            if (projectPlugins.isNotEmpty()) {
                generateTypeSafeAccessorsFor(projectPlugins)
            }
        }
    }

    private
    fun IO.recreateOutputDir() {
        // access output dir from main thread, recreate in IO thread
        val metadataOutputDir = metadataOutputDir.get().asFile
        io { recreate(metadataOutputDir) }
    }

    private
    fun IO.generateTypeSafeAccessorsFor(projectPlugins: List<PrecompiledScriptPlugin>) {
        scriptPluginPluginsFor(projectPlugins)
            .groupBy {
                UniquePluginRequests(it.plugins)
            }.let {
                projectSchemaImpliedByPluginGroups(it)
            }.forEach { (projectSchema, scriptPlugins) ->
                writeTypeSafeAccessorsFor(projectSchema)
                for (scriptPlugin in scriptPlugins) {
                    writeContentAddressableImplicitImportFor(
                        scriptPlugin,
                        projectSchema.packageName
                    )
                }
            }
    }

    private
    fun scriptPluginPluginsFor(projectPlugins: List<PrecompiledScriptPlugin>) = sequence {
        val loader = createPluginsClassLoader()
        try {
            for (plugin in projectPlugins) {
                loader.scriptPluginPluginsFor(plugin)?.let {
                    yield(it)
                }
            }
        } finally {
            stoppable(loader).stop()
        }
    }

    private
    fun ClassLoader.scriptPluginPluginsFor(plugin: PrecompiledScriptPlugin): ScriptPluginPlugins? {

        // The compiled script class won't be present for precompiled script plugins
        // which don't include a `plugins` block
        if (getResource(compiledScriptClassFile(plugin)) == null) {
            return null
        }

        return ScriptPluginPlugins(
            plugin,
            collectPluginRequestsOf(plugin)
        )
    }

    private
    fun ClassLoader.collectPluginRequestsOf(plugin: PrecompiledScriptPlugin): PluginRequests =
        PluginRequestCollector(scriptSourceFor(plugin)).run {

            loadClass(plugin.compiledScriptTypeName)
                .getConstructor(PluginDependenciesSpec::class.java)
                .newInstance(createSpec(1))

            pluginRequests
        }

    private
    fun compiledScriptClassFile(plugin: PrecompiledScriptPlugin) =
        plugin.compiledScriptTypeName.replace('.', '/') + ".class"

    private
    fun scriptSourceFor(plugin: PrecompiledScriptPlugin) =
        TextResourceScriptSource(
            BasicTextResourceLoader().loadFile("Precompiled script plugin", plugin.scriptFile)
        )

    private
    fun selectProjectPlugins() = plugins.filter { it.scriptType == KotlinScriptType.PROJECT }

    private
    fun createPluginsClassLoader(): ClassLoader =
        classLoaderScopeRegistry()
            .coreAndPluginsScope
            .createChild("$path/precompiled-script-plugins").run {
                local(compiledPluginsClassPath())
                lock()
                localClassLoader
            }

    private
    fun classLoaderScopeRegistry() = project.serviceOf<ClassLoaderScopeRegistry>()

    private
    fun compiledPluginsClassPath() =
        DefaultClassPath.of(compiledPluginsBlocksDir.get().asFile) + classPath

    private
    fun projectSchemaImpliedByPluginGroups(
        pluginGroupsPerRequests: Map<UniquePluginRequests, List<ScriptPluginPlugins>>
    ): Map<HashedProjectSchema, List<ScriptPluginPlugins>> {

        val schemaBuilder = SyntheticProjectSchemaBuilder(temporaryDir, classPathFiles.files)
        return pluginGroupsPerRequests.flatMap { (uniquePluginRequests, scriptPlugins) ->
            val schema = schemaBuilder.schemaFor(uniquePluginRequests.plugins)
            val hashedSchema = HashedProjectSchema(schema)
            scriptPlugins.map { hashedSchema to it }
        }.groupBy(
            { (schema, _) -> schema },
            { (_, plugin) -> plugin }
        )
    }

    private
    fun IO.writeTypeSafeAccessorsFor(hashedSchema: HashedProjectSchema) {
        buildAccessorsFor(
            hashedSchema.schema,
            classPath,
            sourceCodeOutputDir.get().asFile,
            temporaryDir.resolve("accessors"),
            hashedSchema.packageName
        )
    }

    private
    fun IO.writeContentAddressableImplicitImportFor(scriptPlugin: ScriptPluginPlugins, packageName: String) {
        io { writeFile(implicitImportFileFor(scriptPlugin), "$packageName.*".toByteArray()) }
    }

    private
    fun implicitImportFileFor(scriptPluginPlugins: ScriptPluginPlugins): File =
        metadataOutputDir.get().asFile.resolve(scriptPluginPlugins.scriptPlugin.hashString)
}


internal
class SyntheticProjectSchemaBuilder(rootProjectDir: File, rootProjectClassPath: Set<File>) {

    private
    val rootProject = buildRootProject(rootProjectDir, rootProjectClassPath)

    fun schemaFor(plugins: PluginRequests): TypedProjectSchema =
        schemaFor(childProjectWith(plugins))

    private
    fun childProjectWith(pluginRequests: PluginRequests): Project {

        val project = ProjectBuilder.builder()
            .withParent(rootProject)
            .withProjectDir(rootProject.projectDir.resolve("schema"))
            .build()

        applyPluginsTo(project, pluginRequests)

        return project
    }

    private
    fun buildRootProject(projectDir: File, rootProjectClassPath: Collection<File>): Project {

        val project = ProjectBuilder.builder()
            .withProjectDir(projectDir)
            .build()

        addScriptClassPathDependencyTo(project, rootProjectClassPath)

        applyPluginsTo(project, DefaultPluginRequests.EMPTY)

        return project
    }

    private
    fun addScriptClassPathDependencyTo(project: Project, rootProjectClassPath: Collection<File>) {
        val scriptHandler = project.buildscript as ScriptHandlerInternal
        scriptHandler.addScriptClassPathDependency(
            DefaultSelfResolvingDependency(
                project
                    .serviceOf<FileCollectionFactory>()
                    .fixed("precompiled-script-plugins-accessors-classpath", rootProjectClassPath) as FileCollectionInternal
            )
        )
    }

    private
    fun applyPluginsTo(project: Project, pluginRequests: PluginRequests) {
        val targetProjectScope = (project as ProjectInternal).classLoaderScope
        project.serviceOf<PluginRequestApplicator>().applyPlugins(
            pluginRequests,
            project.buildscript as ScriptHandlerInternal,
            project.pluginManager,
            targetProjectScope
        )
    }
}


internal
data class HashedProjectSchema(
    val schema: TypedProjectSchema,
    val hash: HashCode = hashCodeFor(schema)
) {
    val packageName by lazy {
        "gradle.kotlin.dsl.accessors._$hash"
    }

    override fun hashCode(): Int = hash.hashCode()

    override fun equals(other: Any?): Boolean = other is HashedProjectSchema && hash == other.hash
}


internal
data class ScriptPluginPlugins(
    val scriptPlugin: PrecompiledScriptPlugin,
    val plugins: PluginRequests
)


private
class UniquePluginRequests(val plugins: PluginRequests) {

    val applications = plugins.map { it.toPluginApplication() }

    override fun equals(other: Any?): Boolean = other is UniquePluginRequests && applications == other.applications

    override fun hashCode(): Int = applications.hashCode()
}


private
fun PluginRequestInternal.toPluginApplication() = PluginApplication(
    id.id, version, isApply
)


internal
data class PluginApplication(
    val id: String,
    val version: String?,
    val apply: Boolean?
)


internal
fun scriptPluginFilesOf(list: List<PrecompiledScriptPlugin>) = list.map { it.scriptFile }.toSet()