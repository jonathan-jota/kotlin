/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.jvm.diagnostics

import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDefaultErrorMessages
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.RENDER_TYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnosticRenderers.TO_STRING
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.CONFLICTING_JVM_DECLARATIONS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.DELEGATION_BY_IN_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.DEPRECATED_JAVA_ANNOTATION
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.ENUM_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.FIELD_IN_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.ILLEGAL_JAVA_LANG_RECORD_SUPERTYPE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.INNER_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JAVA_TYPE_MISMATCH
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_PACKAGE_NAME_CANNOT_BE_EMPTY
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_PACKAGE_NAME_MUST_BE_VALID_NAME
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_PACKAGE_NAME_NOT_SUPPORTED_IN_FILES_WITH_CLASSES
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_RECORD_EXTENDS_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_RECORD_NOT_LAST_VARARG_PARAMETER
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_RECORD_NOT_VAL_PARAMETER
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.JVM_RECORD_WITHOUT_PRIMARY_CONSTRUCTOR_PARAMETERS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.LOCAL_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.NON_DATA_CLASS_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.NON_FINAL_JVM_RECORD
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_ABSTRACT
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_ANNOTATION_CLASS_CONSTRUCTOR
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_LOCAL
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_PRIVATE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.STRICTFP_ON_CLASS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.SUPER_CALL_WITH_DEFAULT_PARAMETERS
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.SYNCHRONIZED_IN_INTERFACE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.SYNCHRONIZED_ON_ABSTRACT
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.SYNCHRONIZED_ON_INLINE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.UPPER_BOUND_CANNOT_BE_ARRAY
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.VOLATILE_ON_DELEGATE
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors.VOLATILE_ON_VALUE

object FirJvmDefaultErrorMessages {
    fun installJvmErrorMessages() {
        FirDefaultErrorMessages.Companion.MAP.also { map ->
            map.put(CONFLICTING_JVM_DECLARATIONS, "Platform declaration clash")
            map.put(JAVA_TYPE_MISMATCH, "Java type mismatch expected {0} but found {1}. Use explicit cast", RENDER_TYPE, RENDER_TYPE)
            map.put(UPPER_BOUND_CANNOT_BE_ARRAY, "Upper bound of a type parameter cannot be an array")
            map.put(STRICTFP_ON_CLASS, "'@Strictfp' annotation on classes is unsupported yet")
            map.put(VOLATILE_ON_VALUE, "'@Volatile' annotation cannot be used on immutable properties")
            map.put(VOLATILE_ON_DELEGATE, "'@Volatile' annotation cannot be used on delegated properties")
            map.put(SYNCHRONIZED_ON_ABSTRACT, "'@Synchronized' annotation cannot be used on abstract functions")
            map.put(SYNCHRONIZED_ON_INLINE, "'@Synchronized' annotation has no effect on inline functions")
            map.put(SYNCHRONIZED_IN_INTERFACE, "'@Synchronized' annotation cannot be used on interface members")
            map.put(OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS, "'@JvmOverloads' annotation has no effect for methods without default arguments")
            map.put(OVERLOADS_ABSTRACT, "'@JvmOverloads' annotation cannot be used on abstract methods")
            map.put(OVERLOADS_INTERFACE, "'@JvmOverloads' annotation cannot be used on interface methods")
            map.put(OVERLOADS_PRIVATE, "'@JvmOverloads' annotation has no effect on private declarations")
            map.put(OVERLOADS_LOCAL, "'@JvmOverloads' annotation cannot be used on local declarations")
            map.put(
                OVERLOADS_ANNOTATION_CLASS_CONSTRUCTOR,
                "'@JvmOverloads' annotation cannot be used on constructors of annotation classes"
            )
            map.put(DEPRECATED_JAVA_ANNOTATION, "This annotation is deprecated in Kotlin. Use ''@{0}'' instead", TO_STRING)
            map.put(JVM_PACKAGE_NAME_CANNOT_BE_EMPTY, "''@JvmPackageName'' annotation value cannot be empty")
            map.put(
                JVM_PACKAGE_NAME_MUST_BE_VALID_NAME,
                "''@JvmPackageName'' annotation value must be a valid dot-qualified name of a package"
            )
            map.put(
                JVM_PACKAGE_NAME_NOT_SUPPORTED_IN_FILES_WITH_CLASSES,
                "''@JvmPackageName'' annotation is not supported for files with class declarations"
            )
            map.put(
                SUPER_CALL_WITH_DEFAULT_PARAMETERS,
                "Super-calls with default arguments are not allowed. Please specify all arguments of ''super.{0}'' explicitly",
                TO_STRING
            )

            map.put(LOCAL_JVM_RECORD, "Local @JvmRecord classes are not allowed")
            map.put(NON_FINAL_JVM_RECORD, "@JvmRecord class should be final")
            map.put(ENUM_JVM_RECORD, "@JvmRecord class should not be an enum")
            map.put(
                JVM_RECORD_WITHOUT_PRIMARY_CONSTRUCTOR_PARAMETERS,
                "Primary constructor with parameters is required for @JvmRecord class"
            )
            map.put(JVM_RECORD_NOT_VAL_PARAMETER, "Constructor parameter of @JvmRecord class should be a val")
            map.put(JVM_RECORD_NOT_LAST_VARARG_PARAMETER, "Only the last constructor parameter of @JvmRecord may be a vararg")
            map.put(JVM_RECORD_EXTENDS_CLASS, "Record cannot inherit a class", RENDER_TYPE)
            map.put(INNER_JVM_RECORD, "@JvmRecord class should not be inner")
            map.put(FIELD_IN_JVM_RECORD, "It's not allowed to have non-constructor properties with backing filed in @JvmRecord class")
            map.put(DELEGATION_BY_IN_JVM_RECORD, "Delegation is not allowed for @JvmRecord classes")
            map.put(NON_DATA_CLASS_JVM_RECORD, "Only data classes are allowed to be marked as @JvmRecord")
            map.put(ILLEGAL_JAVA_LANG_RECORD_SUPERTYPE, "Classes cannot have explicit 'java.lang.Record' supertype")
        }
    }
}