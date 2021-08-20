/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization

import org.jetbrains.kotlin.ObsoleteTestInfrastructure
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.runners.codegen.AbstractIrBlackBoxCodegenTest
import org.jetbrains.kotlin.test.services.RuntimeClasspathProvider
//import org.jetbrains.kotlin.codegen.ir.AbstractIrBlackBoxCodegenTest
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar
import java.io.File

//@OptIn(ObsoleteTestInfrastructure::class)
open class AbstractSerializationIrBoxTest : AbstractIrBlackBoxCodegenTest() {
    private val coreLibraryPath = getSerializationCoreLibraryJar()
    private val jsonLibraryPath = getSerializationLibraryJar("kotlinx.serialization.json.Json")

//
//    override fun setupEnvironment(environment: KotlinCoreEnvironment) {
//        SerializationComponentRegistrar.registerExtensions(environment.project)
//        additionalDependencies = listOf(jsonLibraryPath!!, coreLibraryPath!!)
//        environment.updateClasspath(listOf(JvmClasspathRoot(jsonLibraryPath), JvmClasspathRoot(coreLibraryPath)))
//    }

    override fun configure(builder: TestConfigurationBuilder) {
        super.configure(builder)

        builder.useCustomRuntimeClasspathProvider {
            object : RuntimeClasspathProvider() {
                override fun runtimeClassPaths(): List<File> = listOf(coreLibraryPath!!)
            }
        }
    }

}