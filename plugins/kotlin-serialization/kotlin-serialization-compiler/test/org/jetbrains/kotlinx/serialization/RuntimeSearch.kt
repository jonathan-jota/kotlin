/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization

import junit.framework.TestCase
import org.jetbrains.kotlin.utils.PathUtil
import org.jetbrains.kotlinx.serialization.compiler.diagnostic.VersionReader
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class RuntimeLibraryInClasspathTest {
    private val coreLibraryPath = getSerializationCoreLibraryJar()

    @Test
    fun testRuntimeLibraryExists() {
        TestCase.assertNotNull(
            "kotlinx-serialization runtime library is not found. Make sure it is present in test classpath",
            coreLibraryPath
        )
    }

    @Test
    fun testRuntimeHasSufficientVersion() {
        val version = VersionReader.getVersionsFromManifest(coreLibraryPath!!)
        assertTrue(version.currentCompilerMatchRequired(), "Runtime version too high")
        assertTrue(version.implementationVersionMatchSupported(), "Runtime version too low")
    }
}

internal fun getSerializationCoreLibraryJar(): File? = getSerializationLibraryJar("kotlinx.serialization.KSerializer")

internal fun getSerializationLibraryJar(classToDetect: String): File? = try {
    PathUtil.getResourcePathForClass(Class.forName(classToDetect))
} catch (e: ClassNotFoundException) {
    null
}
