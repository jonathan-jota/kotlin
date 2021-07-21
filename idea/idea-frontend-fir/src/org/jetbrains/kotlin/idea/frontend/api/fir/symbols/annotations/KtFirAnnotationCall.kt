/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.symbols.annotations

import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.expressions.FirAnnotationCall
import org.jetbrains.kotlin.idea.fir.findPsi
import org.jetbrains.kotlin.idea.fir.low.level.api.lazy.resolve.ResolveType
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtNamedConstantValue
import org.jetbrains.kotlin.idea.frontend.api.tokens.ValidityToken
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtCallElement

internal class KtFirAnnotationCall(
    private val containingDeclaration: FirRefWithValidityCheck<FirDeclaration>,
    annotationCall: FirAnnotationCall
) : KtAnnotationCall() {

    private val annotationCallRef by weakRef(annotationCall)

    override val token: ValidityToken get() = containingDeclaration.token

    override val psi: KtCallElement? by containingDeclaration.withFirAndCache { fir ->
        fir.findPsi(fir.moduleData.session) as? KtCallElement
    }

    override val classId: ClassId? by cached {
        containingDeclaration.withFirByTypeWithPossibleResolveInside(ResolveType.AnnotationType) { fir ->
            annotationCallRef.getClassId(fir.moduleData.session)
        }
    }

    override val useSiteTarget: AnnotationUseSiteTarget? get() = annotationCallRef.useSiteTarget

    override val arguments: List<KtNamedConstantValue> by containingDeclaration.withFirAndCache(ResolveType.AnnotationsArguments) { fir ->
        mapAnnotationParameters(annotationCallRef, fir.moduleData.session).map { (name, expression) ->
            KtNamedConstantValue(name, expression.convertConstantExpression())
        }
    }
}
