/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.expressions

import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol

abstract class IrConstantValue : IrExpression() {
    abstract fun contentEquals(other: IrConstantValue) : Boolean
    abstract fun contentHashCode(): Int
}

abstract class IrConstantPrimitive : IrConstantValue() {
    abstract var value: IrConst<*>
}

abstract class IrConstantObject : IrConstantValue() {
    abstract val constructor: IrConstructorSymbol
    abstract val properties: Map<IrPropertySymbol, IrConstantValue>
    abstract fun putProperty(property: IrPropertySymbol, value: IrConstantValue)
    abstract fun toConstructorCall(): IrConstructorCall
}

abstract class IrConstantArray : IrConstantValue() {
    abstract val elements: List<IrConstantValue>
    abstract fun putElement(index: Int, value: IrConstantValue)
}

abstract class IrConstantIntrinsic : IrConstantValue() {
    abstract var expression: IrExpression
}