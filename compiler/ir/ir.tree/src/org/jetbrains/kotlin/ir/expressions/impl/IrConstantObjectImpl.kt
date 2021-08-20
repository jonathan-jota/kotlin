/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.expressions.impl

import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructedClassType
import org.jetbrains.kotlin.ir.util.transformInPlace
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.utils.SmartList

class IrConstantPrimitiveImpl(
    override val startOffset: Int,
    override val endOffset: Int,
    override var value: IrConst<*>,
) : IrConstantPrimitive() {
    override fun contentEquals(other: IrConstantValue) =
        other is IrConstantPrimitiveImpl &&
                value.kind == other.value.kind &&
                value.value == other.value

    override fun contentHashCode() =
        value.kind.hashCode() * 31 + value.value.hashCode()

    override var type = value.type

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        value.accept(visitor, data)
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        value = value.transform(transformer, data) as IrConst<*>
    }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitConstantPrimitive(this, data)
    }
}

class IrConstantObjectImpl constructor(
    override val startOffset: Int,
    override val endOffset: Int,
    override val constructor: IrConstructorSymbol,
    properties_: Map<IrPropertySymbol, IrConstantValue>,
    override var type: IrType = constructor.owner.constructedClassType,
) : IrConstantObject() {
    override val properties = properties_.toMutableMap()

    init {
        require(properties.all { it.key.owner.backingField != null }) { "Properties of constant object must have backing field"}
        require(constructor.owner.valueParameters.all { it.correspondingPropertySymbol != null }) {
            "All constructor of constant object parameters must be val"
        }
        require(constructor.owner.valueParameters.all { it.correspondingPropertySymbol!! in properties }) {
            "All constructor of constant object parameters values must be given"
        }
    }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitConstantObject(this, data)
    }

    override fun putProperty(property: IrPropertySymbol, value: IrConstantValue) {
        properties[property] = value
    }

    override fun toConstructorCall(): IrConstructorCall {
        return IrConstructorCallImpl.fromSymbolOwner(startOffset, endOffset, type, constructor).apply {
            constructor.owner.valueParameters.forEach {
                putValueArgument(it.index, properties[it.correspondingPropertySymbol]!!)
            }
        }
    }

    override fun contentEquals(other: IrConstantValue): Boolean =
        other is IrConstantObjectImpl &&
                other.type == type &&
                other.constructor == constructor &&
                properties.size == other.properties.size &&
                properties.all { (field, value) -> other.properties[field]?.contentEquals(value) == true }

    override fun contentHashCode(): Int {
        var res = type.hashCode() * 31 + constructor.hashCode()
        for ((field, value) in properties) {
            res += field.hashCode() xor value.contentHashCode()
        }
        return res
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        properties.forEach { (_, value) -> value.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        for ((property, value) in properties) {
            properties[property] = value.transform(transformer, data) as IrConstantValue
        }
    }
}

class IrConstantArrayImpl(
    override val startOffset: Int,
    override val endOffset: Int,
    override var type: IrType,
    initElements: List<IrConstantValue>,
) : IrConstantArray() {
    override val elements = SmartList(initElements)
    override fun putElement(index: Int, value: IrConstantValue) {
        elements[index] = value
    }

    override fun contentEquals(other: IrConstantValue): Boolean =
        other is IrConstantArrayImpl &&
                other.type == type &&
                elements.size == other.elements.size &&
                elements.indices.all { elements[it].contentEquals(other.elements[it]) }

    override fun contentHashCode(): Int {
        var res = type.hashCode()
        for (value in elements) {
            res = res * 31 + value.contentHashCode()
        }
        return res
    }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitConstantArray(this, data)
    }

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        elements.forEach { value -> value.accept(visitor, data) }
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        elements.transformInPlace { value -> value.transform(transformer, data) }
    }
}

/**
 * This ir node represents either some custom constants, which can't be represented in IR,
 * (e.g. TypeInfo pointer in Native backend) and handled directly in code generator,
 * or any expression, which would be lowered to constant later.
 *
 * In second case, when it's lowered, this node would be automatically replaced by its child
 * to simplify matching patterns in IR for further lowerings and code generator.
 * @see transform method for details.
 */
class IrConstantIntrinsicImpl(
    override val startOffset: Int,
    override val endOffset: Int,
    override var expression: IrExpression,
) : IrConstantIntrinsic() {
    override fun contentEquals(other: IrConstantValue): Boolean {
        if (other == this) return true
        if (other !is IrConstantIntrinsicImpl) return false
        val expr = expression
        val otherExpr = other.expression
        if (expr !is IrCall || otherExpr !is IrCall) return false
        if (expr.valueArgumentsCount != 0 || otherExpr.valueArgumentsCount != 0) return false
        if (expr.typeArgumentsCount != otherExpr.typeArgumentsCount) return false
        return expr.symbol == otherExpr.symbol &&
                (0 until expr.typeArgumentsCount).all { expr.getTypeArgument(it) == otherExpr.getTypeArgument(it) }
    }

    override fun contentHashCode(): Int {
        val expr = expression
        if (expr !is IrCall) return hashCode()
        if (expr.valueArgumentsCount != 0) return hashCode()
        var res = expr.symbol.hashCode()
        for (i in 0 until expr.typeArgumentsCount) {
            res = res * 31 + expr.getTypeArgument(i).hashCode()
        }
        return res
    }

    override var type = expression.type

    override fun <D> acceptChildren(visitor: IrElementVisitor<Unit, D>, data: D) {
        expression.accept(visitor, data)
    }

    override fun <D> transform(transformer: IrElementTransformer<D>, data: D): IrExpression {
        return super.transform(transformer, data).let {
            if (it is IrConstantIntrinsicImpl && it.expression is IrConstantValue) {
                it.expression
            } else {
                it
            }
        }
    }

    override fun <D> transformChildren(transformer: IrElementTransformer<D>, data: D) {
        expression = expression.transform(transformer, data)
    }

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        return visitor.visitConstantIntrinsic(this, data)
    }
}
