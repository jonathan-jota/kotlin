/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.checkers.generator.diagnostics

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.fir.PrivateForInline
import org.jetbrains.kotlin.fir.checkers.generator.diagnostics.model.DiagnosticList
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.fir.checkers.generator.diagnostics.model.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry

@Suppress("UNUSED_VARIABLE", "LocalVariableName", "ClassName", "unused")
@OptIn(PrivateForInline::class)
object JVM_DIAGNOSTICS_LIST : DiagnosticList("FirJvmErrors") {
    val DECLARATIONS by object : DiagnosticGroup("Declarations") {
        val CONFLICTING_JVM_DECLARATIONS by error<PsiElement>()
    }

    val TYPES by object : DiagnosticGroup("Types") {
        val JAVA_TYPE_MISMATCH by error<KtExpression> {
            parameter<ConeKotlinType>("expectedType")
            parameter<ConeKotlinType>("actualType")
        }
    }

    val TYPE_PARAMETERS by object : DiagnosticGroup("Type parameters") {
        val UPPER_BOUND_CANNOT_BE_ARRAY by error<PsiElement>()
    }

    val ANNOTATIONS by object : DiagnosticGroup("annotations") {
        val STRICTFP_ON_CLASS by error<KtAnnotationEntry>()
        val VOLATILE_ON_VALUE by error<KtAnnotationEntry>()
        val VOLATILE_ON_DELEGATE by error<KtAnnotationEntry>()
        val SYNCHRONIZED_ON_ABSTRACT by error<KtAnnotationEntry>()
        val SYNCHRONIZED_IN_INTERFACE by error<KtAnnotationEntry>()
        val SYNCHRONIZED_ON_INLINE by warning<KtAnnotationEntry>()
        val OVERLOADS_WITHOUT_DEFAULT_ARGUMENTS by warning<KtAnnotationEntry>()
        val OVERLOADS_ABSTRACT by error<KtAnnotationEntry>()
        val OVERLOADS_INTERFACE by error<KtAnnotationEntry>()
        val OVERLOADS_LOCAL by error<KtAnnotationEntry>()
        val OVERLOADS_ANNOTATION_CLASS_CONSTRUCTOR by deprecationError<KtAnnotationEntry>(LanguageFeature.ProhibitJvmOverloadsOnConstructorsOfAnnotationClasses)
        val OVERLOADS_PRIVATE by warning<KtAnnotationEntry>()
        val DEPRECATED_JAVA_ANNOTATION by warning<KtAnnotationEntry>() {
            parameter<FqName>("kotlinName")
        }

        val JVM_PACKAGE_NAME_CANNOT_BE_EMPTY by error<KtAnnotationEntry>()
        val JVM_PACKAGE_NAME_MUST_BE_VALID_NAME by error<KtAnnotationEntry>()
        val JVM_PACKAGE_NAME_NOT_SUPPORTED_IN_FILES_WITH_CLASSES by error<KtAnnotationEntry>()
    }

    val SUPER by object : DiagnosticGroup("Super") {
        val SUPER_CALL_WITH_DEFAULT_PARAMETERS by error<PsiElement>() {
            parameter<String>("name")
        }
    }

    val RECORDS by object : DiagnosticGroup("JVM Records") {
        val LOCAL_JVM_RECORD by error<PsiElement>()
        val NON_FINAL_JVM_RECORD by error<PsiElement>(PositioningStrategy.NON_FINAL_MODIFIER_OR_NAME)
        val ENUM_JVM_RECORD by error<PsiElement>(PositioningStrategy.ENUM_MODIFIER)
        val JVM_RECORD_WITHOUT_PRIMARY_CONSTRUCTOR_PARAMETERS by error<PsiElement>()
        val NON_DATA_CLASS_JVM_RECORD by error<PsiElement>()
        val JVM_RECORD_NOT_VAL_PARAMETER by error<PsiElement>()
        val JVM_RECORD_NOT_LAST_VARARG_PARAMETER by error<PsiElement>()
        val INNER_JVM_RECORD by error<PsiElement>(PositioningStrategy.INNER_MODIFIER)
        val FIELD_IN_JVM_RECORD by error<PsiElement>()
        val DELEGATION_BY_IN_JVM_RECORD by error<PsiElement>()
        val JVM_RECORD_EXTENDS_CLASS by error<PsiElement>(PositioningStrategy.ACTUAL_DECLARATION_NAME) {
            parameter<ConeKotlinType>("superType")
        }
        val ILLEGAL_JAVA_LANG_RECORD_SUPERTYPE by error<PsiElement>()
    }

}
