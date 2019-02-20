/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.tasks

import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.*
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.process.ProcessForkOptions
import org.gradle.process.internal.DefaultProcessForkOptions
import org.gradle.process.internal.ExecHandleFactory
import org.jetbrains.kotlin.gradle.internal.testing.TCServiceMessagesClientSettings
import org.jetbrains.kotlin.gradle.internal.testing.TCServiceMessagesTestExecutionSpec
import org.jetbrains.kotlin.gradle.internal.testing.TCServiceMessagesTestExecutor
import org.jetbrains.kotlin.gradle.testing.IgnoredTestSuites
import org.jetbrains.kotlin.gradle.testing.TestsGrouping
import org.jetbrains.kotlin.gradle.utils.injected
import java.io.File
import javax.inject.Inject

open class KotlinNodeJsTestTask : AbstractTestTask() {
    @Input
    var ignoredTestSuites: IgnoredTestSuites =
            IgnoredTestSuites.showWithContents

    @Input
    var testsGrouping: TestsGrouping =
            TestsGrouping.root

    @Input
    @Optional
    var targetName: String? = null

    @Input
    var excludes = mutableSetOf<String>()

    @InputDirectory
    var nodeModulesDir: File? = null

    @Input
    @SkipWhenEmpty
    var nodeModulesToLoad: Set<String> = setOf()

    @InputFile
    @Optional
    var testRuntimeNodeModule: File? = null

    @Suppress("UnstableApiUsage")
    private val filterExt: DefaultTestFilter
        get() = filter as DefaultTestFilter

    init {
        filterExt.isFailOnNoMatchingTests = false
    }

    @get:Inject
    open val fileResolver: FileResolver
        get() = injected

    @Suppress("LeakingThis")
    @Internal
    val nodeJsProcessOptions: ProcessForkOptions = DefaultProcessForkOptions(fileResolver)

    fun nodeJs(options: ProcessForkOptions.() -> Unit) {
        options(nodeJsProcessOptions)
    }

    override fun createTestExecutionSpec(): TCServiceMessagesTestExecutionSpec {
        val extendedForkOptions = DefaultProcessForkOptions(fileResolver)
        nodeJsProcessOptions.copyTo(extendedForkOptions)

        extendedForkOptions.environment.addPath("NODE_PATH", nodeModulesDir!!.canonicalPath)

        val cliArgs = KotlinNodeJsTestRunnerCliArgs(
                nodeModulesToLoad.toList(),
                filterExt.includePatterns + filterExt.commandLineIncludePatterns,
                excludes,
                ignoredTestSuites.cli
        )

        val clientSettings = when (testsGrouping) {
            TestsGrouping.none -> TCServiceMessagesClientSettings(rootNodeName = name, skipRoots = true)
            TestsGrouping.root -> TCServiceMessagesClientSettings(rootNodeName = name, nameOfRootSuiteToReplace = targetName)
            TestsGrouping.leaf -> TCServiceMessagesClientSettings(rootNodeName = name, skipRoots = true, nameOfLeafTestToAppend = targetName)
        }

        return TCServiceMessagesTestExecutionSpec(
                extendedForkOptions,
                listOf(finalTestRuntimeNodeModule.absolutePath) + cliArgs.toList(),
                clientSettings
        )
    }

    @get:Inject
    open val execHandleFactory: ExecHandleFactory
        get() = injected

    private val finalTestRuntimeNodeModule: File
        get() = testRuntimeNodeModule
                ?: nodeModulesDir!!.resolve(".bin").resolve(kotlinNodeJsTestRuntimeBin)

    override fun createTestExecuter() = TCServiceMessagesTestExecutor(
            execHandleFactory,
            buildOperationExecutor
    )
}

private fun MutableMap<String, Any>.addPath(key: String, path: String) {
    val prev = get(key)
    if (prev == null) set(key, path)
    else set(key, prev as String + File.pathSeparator + path)
}