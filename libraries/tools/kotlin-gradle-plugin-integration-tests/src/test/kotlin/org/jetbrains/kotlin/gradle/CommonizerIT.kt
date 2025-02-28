/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.api.logging.LogLevel.INFO
import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.commonizer.CommonizerTarget
import org.jetbrains.kotlin.gradle.internals.DISABLED_NATIVE_TARGETS_REPORTER_WARNING_PREFIX
import org.jetbrains.kotlin.gradle.util.reportSourceSetCommonizerDependencies
import org.jetbrains.kotlin.incremental.testingUtils.assertEqualDirectories
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CommonizerIT : BaseGradleIT() {
    override val defaultGradleVersion: GradleVersionRequired = GradleVersionRequired.FOR_MPP_SUPPORT

    companion object {
        private const val commonizerOutput = "Preparing commonized Kotlin/Native libraries"
    }

    @Test
    fun `test commonizeNativeDistributionWithIosLinuxWindows`() {
        with(Project("commonizeNativeDistributionWithIosLinuxWindows")) {
            build(":cleanNativeDistributionCommonization") {
                assertSuccessful()
            }

            build("commonize", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=false") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertContains(DISABLED_NATIVE_TARGETS_REPORTER_WARNING_PREFIX)
                assertContains(commonizerOutput)
                assertSuccessful()
            }

            build("commonize", "--rerun-tasks", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=true") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertContains("Native Distribution Commonization: Cache hit")
                assertContains("Native Distribution Commonization: All available targets are commonized already")
                assertContains("Native Distribution Commonization: Lock acquired")
                assertContains("Native Distribution Commonization: Lock released")
                assertNotContains(commonizerOutput)
                assertSuccessful()
            }

            build("commonize", "--rerun-tasks", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=false") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertContains("Native Distribution Commonization: Cache disabled")
                assertContains(commonizerOutput)
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test commonizeCurlInterop UP-TO-DATE check`() {
        with(preparedProject("commonizeCurlInterop")) {
            build(":commonize") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
                assertSuccessful()
            }

            build(":commonize") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksUpToDate(":cinteropCurlTargetA")
                assertTasksUpToDate(":cinteropCurlTargetB")
                assertTasksUpToDate(":commonizeCInterop")
                assertSuccessful()
            }

            val buildGradleKts = projectFile("build.gradle.kts")
            val originalBuildGradleKtsContent = buildGradleKts.readText()

            buildGradleKts.writeText(originalBuildGradleKtsContent.replace("curl", "curl2"))
            build(":commonize") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurl2TargetA")
                assertTasksExecuted(":cinteropCurl2TargetB")
                assertTasksExecuted(":commonizeCInterop")
                assertSuccessful()
            }

            buildGradleKts.writeText(originalBuildGradleKtsContent.lineSequence().filter { "curl" !in it }.joinToString("\n"))
            build(":commonize") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksNotExecuted(":cinteropCurlTargetA")
                assertTasksNotExecuted(":cinteropCurlTargetB")
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test commonizeCurlInterop feature flag`() {
        with(preparedProject("commonizeCurlInterop")) {
            setupWorkingDir()
            // Remove feature flag from gradle.properties
            projectFile("gradle.properties").apply {
                writeText(readText().lineSequence().filter { "enableCInteropCommonization" !in it }.joinToString("\n"))
            }

            build(":commonize") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksNotExecuted(":cinteropCurl2TargetA")
                assertTasksNotExecuted(":cinteropCurl2TargetB")
                assertTasksNotExecuted(":commonizeCInterop")
                assertSuccessful()
            }

            build(":commonize", "-Pkotlin.mpp.enableCInteropCommonization=true") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
                assertSuccessful()
            }

            build(":commonize", "-Pkotlin.mpp.enableCInteropCommonization=false") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksNotExecuted(":cinteropCurlTargetA")
                assertTasksNotExecuted(":cinteropCurlTargetB")
                assertTasksNotExecuted(":commonizeCInterop")
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test commonizeCurlInterop copyCommonizeCInteropForIde`() {
        with(preparedProject("commonizeCurlInterop")) {
            setupWorkingDir()
            val expectedOutputDirectoryForIde = projectDir.resolve(".gradle/kotlin/commonizer")
            val expectedOutputDirectoryForBuild = projectDir.resolve("build/classes/kotlin/commonizer")

            build(":copyCommonizeCInteropForIde") {
                assertSuccessful()
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")

                assertTrue(expectedOutputDirectoryForIde.isDirectory, "Missing output directory for IDE")
                assertTrue(expectedOutputDirectoryForBuild.isDirectory, "Missing output directory for build")
                assertEqualDirectories(expectedOutputDirectoryForBuild, expectedOutputDirectoryForIde, false)
            }

            build(":clean") {
                assertSuccessful()
                assertTrue(expectedOutputDirectoryForIde.isDirectory, "Expected ide output directory to survive cleaning")
                assertFalse(expectedOutputDirectoryForBuild.exists(), "Expected output directory for build to be cleaned")
            }
        }
    }

    @Test
    fun `test commonizeCurlInterop compilation`() {
        with(preparedProject("commonizeCurlInterop")) {
            build(":compileNativeMainKotlinMetadata") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
                assertSuccessful()
            }

            if (CommonizableTargets.targetA.isCompilable) {
                // targetA will be macos
                build(":targetABinaries") {
                    assertSuccessful()
                }
            }
            if (CommonizableTargets.targetB.isCompilable) {
                //targetB will be linuxArm64
                build(":targetBBinaries") {
                    assertSuccessful()
                }
            }
        }
    }

    @Test
    fun `test commonizeCurlInterop execution`() {
        with(preparedProject("commonizeCurlInterop")) {
            if (CommonizableTargets.targetA.isExecutable) {
                build(":targetATest") {
                    assertSuccessful()
                }
            }
            if (CommonizableTargets.targetB.isExecutable) {
                build(":targetBTest") {
                    assertSuccessful()
                }
            }
        }
    }

    @Test
    fun `test commonizeSQLiteInterop`() {
        with(preparedProject("commonizeSQLiteInterop")) {
            build(":commonize") {
                assertSuccessful()
                assertTasksExecuted(":cinteropSqliteTargetA")
                assertTasksExecuted(":cinteropSqliteTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }
        }
    }

    @Test
    fun `test commonizeSQLiteAndCurlInterop`() {
        with(preparedProject("commonizeSQLiteAndCurlInterop")) {
            build(":commonize") {
                assertSuccessful()
                assertTasksExecuted(":cinteropSqliteTargetA")
                assertTasksExecuted(":cinteropSqliteTargetB")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata") {
                assertSuccessful()
                assertTasksUpToDate(":cinteropSqliteTargetA")
                assertTasksUpToDate(":cinteropSqliteTargetB")
                assertTasksUpToDate(":cinteropCurlTargetA")
                assertTasksUpToDate(":cinteropCurlTargetB")
                assertTasksUpToDate(":commonizeCInterop")
            }
        }
    }

    @Test
    fun `test commonizeInterop using posix APIs`() {
        with(preparedProject("commonizeInteropUsingPosixApis")) {
            build(":commonizeCInterop") {
                assertSuccessful()
                assertTasksExecuted(":cinteropWithPosixTargetA")
                assertTasksExecuted(":cinteropWithPosixTargetB")
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata") {
                assertSuccessful()
                assertTasksUpToDate(":cinteropWithPosixTargetA")
                assertTasksUpToDate(":cinteropWithPosixTargetB")
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksUpToDate(":commonizeCInterop")
            }
        }
    }

    @Test
    fun `test KT-46234 intermediate source set with only one native target`() {
        `test single native platform`("commonize-kt-46234-singleNativeTarget")
    }

    @Test
    fun `test KT-46142 standalone native source set`() {
        `test single native platform`("commonize-kt-46142-singleNativeTarget")
    }

    private fun `test single native platform`(project: String) {
        val posixInImplementationMetadataConfigurationRegex = Regex(""".*implementationMetadataConfiguration:.*([pP])osix""")
        val posixInIntransitiveMetadataConfigurationRegex = Regex(""".*intransitiveMetadataConfiguration:.*([pP])osix""")

        fun CompiledProject.containsPosixInImplementationMetadataConfiguration(): Boolean =
            output.lineSequence().any { line ->
                line.matches(posixInImplementationMetadataConfigurationRegex)
            }

        fun CompiledProject.containsPosixInIntransitiveMetadataConfiguration(): Boolean =
            output.lineSequence().any { line ->
                line.matches(posixInIntransitiveMetadataConfigurationRegex)
            }

        with(Project(project)) {
            build(":p1:listNativePlatformMainDependencies", "-Pkotlin.mpp.enableIntransitiveMetadataConfiguration=false") {
                assertSuccessful()

                assertTrue(
                    containsPosixInImplementationMetadataConfiguration(),
                    "Expected dependency on posix in implementationMetadataConfiguration"
                )

                assertFalse(
                    containsPosixInIntransitiveMetadataConfiguration(),
                    "Expected **no** dependency on posix in intransitiveMetadataConfiguration"
                )
            }

            build(":p1:listNativePlatformMainDependencies", "-Pkotlin.mpp.enableIntransitiveMetadataConfiguration=true") {
                assertSuccessful()

                assertFalse(
                    containsPosixInImplementationMetadataConfiguration(),
                    "Expected **no** posix dependency in implementationMetadataConfiguration"
                )

                assertTrue(
                    containsPosixInIntransitiveMetadataConfiguration(),
                    "Expected dependency on posix in intransitiveMetadataConfiguration"
                )
            }

            build("assemble") {
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test KT-46248 single supported native target dependency propagation`() {
        fun CompiledProject.containsPosixDependency(): Boolean = output.lineSequence().any { line ->
            line.matches(Regex(""".*Dependency:.*[pP]osix"""))
        }

        fun CompiledProject.containsDummyCInteropDependency(): Boolean = output.lineSequence().any { line ->
            line.matches(Regex(""".*Dependency:.*cinterop-dummy.*"""))
        }

        with(Project("commonize-kt-46248-singleNativeTargetPropagation")) {
            build(":p1:listNativeMainDependencies") {
                assertSuccessful()
                assertTrue(containsPosixDependency(), "Expected dependency on posix in nativeMain")
                assertTrue(containsDummyCInteropDependency(), "Expected dependency on dummy cinterop in nativeMain")
            }

            build(":p1:listNativeMainParentDependencies") {
                assertSuccessful()
                assertTrue(containsPosixDependency(), "Expected dependency on posix in nativeMainParent")
                assertTrue(containsDummyCInteropDependency(), "Expected dependency on dummy cinterop in nativeMain")
            }

            build(":p1:listCommonMainDependencies") {
                assertSuccessful()
                assertFalse(containsPosixDependency(), "Expected **no** dependency on posix in commonMain (because of jvm target)")
                assertFalse(containsDummyCInteropDependency(), "Expected **no** dependency on dummy cinterop in nativeMain")
            }

            build("assemble") {
                assertSuccessful()
                assertTasksExecuted(":p1:compileCommonMainKotlinMetadata")
                assertTasksExecuted(":p1:compileKotlinNativePlatform")
            }
        }
    }

    @Test
    fun `test KT-46248 single supported native target dependency propagation - cinterop`() {
        fun CompiledProject.containsCinteropDependency(): Boolean {
            val nativeMainContainsCInteropDependencyRegex = Regex(""".*Dependency:.*cinterop-dummy.*""")
            return output.lineSequence().any { line ->
                line.matches(nativeMainContainsCInteropDependencyRegex)
            }
        }

        with(Project("commonize-kt-47523-singleNativeTargetPropagation-cinterop")) {
            build("listNativePlatformMainDependencies") {
                assertSuccessful()
                assertFalse(
                    containsCinteropDependency(),
                    "Expected sourceSet 'nativeMain' to list cinterop dependency (not necessary, since included in compilation)"
                )
            }

            build("listNativeMainDependencies") {
                assertSuccessful()
                assertTrue(containsCinteropDependency(), "Expected sourceSet 'nativeMain' to list cinterop dependency")
            }

            build("listCommonMainDependencies") {
                assertSuccessful()
                assertTrue(containsCinteropDependency(), "Expected sourceSet 'commonMain' to list cinterop dependency")
            }

            build("assemble") {
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test KT-46856 filename too long - all native targets configured`() {
        with(Project("commonize-kt-46856-all-targets")) {
            build(":commonize", options = BuildOptions(forceOutputToStdout = true)) {
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test multiple cinterops with test source sets and compilations - test source sets depending on main`() {
        `test multiple cinterops with test source sets and compilations`(true)
    }

    @Test
    fun `test multiple cinterops with test source sets and compilations`() {
        `test multiple cinterops with test source sets and compilations`(false)
    }

    private fun `test multiple cinterops with test source sets and compilations`(testSourceSetsDependingOnMain: Boolean) {
        with(Project("commonizeMultipleCInteropsWithTests", minLogLevel = INFO)) {

            val isUnix = HostManager.hostIsMac || HostManager.hostIsLinux
            val isMac = HostManager.hostIsMac
            val isWindows = HostManager.hostIsMingw

            val testSourceSetsDependingOnMainParameter = "-PtestSourceSetsDependingOnMain=$testSourceSetsDependingOnMain"

            fun CompiledProject.assertTestSourceSetsDependingOnMainParameter() {
                val message = "testSourceSetsDependingOnMain is set"
                if (testSourceSetsDependingOnMain) assertContains(message) else assertNotContains(message)
            }

            reportSourceSetCommonizerDependencies(this, testSourceSetsDependingOnMainParameter) {
                it.assertTestSourceSetsDependingOnMainParameter()

                /* this source sets are also shared with a jvm target */
                getCommonizerDependencies("commonMain").assertEmpty()
                getCommonizerDependencies("commonTest").assertEmpty()

                getCommonizerDependencies("nativeMain").onlyCInterops().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper".takeIf { isUnix })
                    assertTargetOnAllDependencies(
                        CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64, MINGW_X64, MINGW_X86)
                    )
                }

                getCommonizerDependencies("nativeTest").onlyCInterops().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper".takeIf { isUnix }, ".*nativeTestHelper")
                    assertTargetOnAllDependencies(
                        CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64, MINGW_X64, MINGW_X86)
                    )
                }

                if (isUnix) {
                    getCommonizerDependencies("unixMain").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("unixTest").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*nativeTestHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("linuxMain").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
                    }

                    getCommonizerDependencies("linuxTest").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*nativeTestHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
                    }

                    getCommonizerDependencies("linuxX64Main").assertEmpty()
                    getCommonizerDependencies("linuxArm64Main").assertEmpty()
                    getCommonizerDependencies("linuxX64Test").assertEmpty()
                    getCommonizerDependencies("linuxArm64Test").assertEmpty()
                }

                if (isMac) {
                    getCommonizerDependencies("appleMain").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("appleTest").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper", ".*nativeTestHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("iosMain").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64))
                    }

                    getCommonizerDependencies("iosTest").onlyCInterops().apply {
                        assertDependencyFilesMatches(
                            ".*nativeHelper", ".*unixHelper", ".*appleHelper", ".*nativeTestHelper", ".*iosTestHelper"
                        )
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64))
                    }

                    getCommonizerDependencies("macosMain").assertEmpty()
                    getCommonizerDependencies("macosTest").assertEmpty()
                    getCommonizerDependencies("iosX64Main").assertEmpty()
                    getCommonizerDependencies("iosX64Test").assertEmpty()
                    getCommonizerDependencies("iosArm64Main").assertEmpty()
                    getCommonizerDependencies("iosArm64Test").assertEmpty()
                }

                if (isWindows) {
                    getCommonizerDependencies("windowsMain").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*windowsHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(MINGW_X86, MINGW_X64))
                    }

                    getCommonizerDependencies("windowsTest").onlyCInterops().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*windowsHelper", ".*nativeTestHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(MINGW_X86, MINGW_X64))
                    }

                    getCommonizerDependencies("windowsX64Main").assertEmpty()
                    getCommonizerDependencies("windowsX64Test").assertEmpty()
                    getCommonizerDependencies("windowsX86Main").assertEmpty()
                    getCommonizerDependencies("windowsX86Test").assertEmpty()
                }
            }

            build(":assemble", testSourceSetsDependingOnMainParameter) {
                assertTestSourceSetsDependingOnMainParameter()
                assertSuccessful()
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertTasksUpToDate(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                assertTestSourceSetsDependingOnMainParameter()
                assertSuccessful()
            }

            if (isUnix) {
                build(":compileUnixMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                    assertTestSourceSetsDependingOnMainParameter()
                    assertSuccessful()
                }

                build(":compileLinuxMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                    assertTestSourceSetsDependingOnMainParameter()
                    assertSuccessful()
                }
            }

            if (isMac) {
                build(":compileAppleMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                    assertTestSourceSetsDependingOnMainParameter()
                    assertSuccessful()
                }

                build(":compileIosMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                    assertTestSourceSetsDependingOnMainParameter()
                    assertSuccessful()
                }
            }

            if (isWindows) {
                build(":compileWindowsMainKotlinMetadata", testSourceSetsDependingOnMainParameter) {
                    assertTestSourceSetsDependingOnMainParameter()
                    assertSuccessful()
                }
            }
        }
    }

    @Test
    fun `test KT-48118 c-interops available in commonMain`() {
        with(Project("commonize-kt-48118-c-interop-in-common-main")) {
            reportSourceSetCommonizerDependencies(this) {
                val upperMain = getCommonizerDependencies("upperMain")
                upperMain.onlyCInterops().assertDependencyFilesMatches(".*cinterop-dummy")
                upperMain.onlyNativeDistribution().assertNotEmpty()

                val commonMain = getCommonizerDependencies("commonMain")
                commonMain.onlyCInterops().assertDependencyFilesMatches(".*cinterop-dummy")
                commonMain.onlyNativeDistribution().assertNotEmpty()
            }

            build(":compileCommonMainKotlinMetadata") {
                assertSuccessful()
            }

            build(":compileUpperMainKotlinMetadata") {
                assertSuccessful()
            }
        }
    }

    @Test
    fun `test KT-47641 commonizing c-interops does not depend on any source compilation`() {
        with(Project("commonize-kt-47641-cinterops-compilation-dependency")) {
            build("commonizeCInterop", options = BuildOptions(forceOutputToStdout = true)) {
                assertTasksExecuted(":p1:commonizeCInterop")
                assertTasksExecuted(":p2:commonizeCInterop")

                assertTasksExecuted(":p1:cinteropTestWithPosix.*")
                assertTasksExecuted(":p2:cinteropTestWithPosix.*")
                assertTasksExecuted(":p2:cinteropTestWithPosixP2.*")

                /* Make sure that we correctly reference any compile tasks in this test (test is useless otherwise) */
                assertTasksRegisteredRegex(":p1.*compile.*")
                assertTasksRegisteredRegex(":p2.*compile.*")

                /* CInterops *shall not* require any compilation */
                assertTasksNotExecuted(":p0.*compile.*")
                assertTasksNotExecuted(":p1.*compile.*")
                assertTasksNotExecuted(":p2.*compile.*")
            }
        }
    }

    @Test
    fun `test KT-48138 commonizing c-interops when nativeTest and nativeMain have different targets`() {
        with(Project("commonize-kt-48138-nativeMain-nativeTest-different-targets")) {
            reportSourceSetCommonizerDependencies(this) {
                val nativeMain = getCommonizerDependencies("nativeMain")
                nativeMain.onlyCInterops().assertDependencyFilesMatches(".*cinterop-dummy")
                nativeMain.onlyNativeDistribution().assertNotEmpty()
                nativeMain.assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64, LINUX_ARM32_HFP))

                val nativeTest = getCommonizerDependencies("nativeTest")
                nativeTest.onlyNativeDistribution().assertNotEmpty()
                nativeTest.onlyCInterops().assertDependencyFilesMatches(".*cinterop-dummy")
                nativeTest.assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
            }
        }
    }

    private fun preparedProject(name: String): Project {
        return Project(name).apply {
            setupWorkingDir()
            projectDir.walkTopDown().filter { it.name.startsWith("build.gradle") }.forEach { buildFile ->
                val originalText = buildFile.readText()
                val preparedText = originalText
                    .replace("<targetA>", CommonizableTargets.targetA.value)
                    .replace("<targetB>", CommonizableTargets.targetB.value)
                buildFile.writeText(preparedText)
            }
        }
    }
}

private data class TargetSubstitution(val value: String, val isCompilable: Boolean, val isExecutable: Boolean) {
    override fun toString(): String = value
}

private object CommonizableTargets {
    private val os = OperatingSystem.current()

    val targetA = when {
        os.isMacOsX -> TargetSubstitution("macosX64", isCompilable = true, isExecutable = true)
        os.isLinux -> TargetSubstitution("linuxX64", isCompilable = true, isExecutable = true)
        os.isWindows -> TargetSubstitution("mingwX64", isCompilable = true, isExecutable = false)
        else -> fail("Unsupported os: ${os.name}")
    }

    val targetB = when {
        os.isMacOsX -> TargetSubstitution("linuxX64", isCompilable = true, isExecutable = false)
        os.isLinux -> TargetSubstitution("linuxArm64", isCompilable = true, isExecutable = false)
        os.isWindows -> TargetSubstitution("mingwX86", isCompilable = true, isExecutable = false)
        else -> fail("Unsupported os: ${os.name}")
    }
}

