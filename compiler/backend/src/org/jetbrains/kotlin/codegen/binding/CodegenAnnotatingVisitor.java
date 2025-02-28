/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.binding;

import com.intellij.psi.PsiElement;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.SmartList;
import com.intellij.util.containers.Stack;
import kotlin.Pair;
import kotlin.collections.CollectionsKt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.backend.common.SamTypeApproximator;
import org.jetbrains.kotlin.builtins.FunctionTypesKt;
import org.jetbrains.kotlin.builtins.KotlinBuiltIns;
import org.jetbrains.kotlin.builtins.ReflectionTypes;
import org.jetbrains.kotlin.cfg.WhenChecker;
import org.jetbrains.kotlin.codegen.*;
import org.jetbrains.kotlin.codegen.coroutines.CoroutineCodegenUtilKt;
import org.jetbrains.kotlin.codegen.state.GenerationState;
import org.jetbrains.kotlin.codegen.when.SwitchCodegenProvider;
import org.jetbrains.kotlin.codegen.when.WhenByEnumsMapping;
import org.jetbrains.kotlin.config.JvmDefaultMode;
import org.jetbrains.kotlin.config.LanguageFeature;
import org.jetbrains.kotlin.config.LanguageVersionSettings;
import org.jetbrains.kotlin.coroutines.CoroutineUtilKt;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.descriptors.annotations.Annotations;
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor;
import org.jetbrains.kotlin.descriptors.impl.LocalVariableDescriptor;
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil;
import org.jetbrains.kotlin.load.java.JvmAbi;
import org.jetbrains.kotlin.load.java.sam.JavaSingleAbstractMethodUtils;
import org.jetbrains.kotlin.name.ClassId;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.name.SpecialNames;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.psi.stubs.KotlinFileStub;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.BindingTrace;
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils;
import org.jetbrains.kotlin.resolve.DescriptorUtils;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilKt;
import org.jetbrains.kotlin.resolve.calls.model.*;
import org.jetbrains.kotlin.resolve.calls.tower.NewResolvedCallImpl;
import org.jetbrains.kotlin.resolve.calls.tower.NewVariableAsFunctionResolvedCallImpl;
import org.jetbrains.kotlin.resolve.constants.ConstantValue;
import org.jetbrains.kotlin.resolve.constants.EnumValue;
import org.jetbrains.kotlin.resolve.constants.NullValue;
import org.jetbrains.kotlin.resolve.descriptorUtil.DescriptorUtilsKt;
import org.jetbrains.kotlin.resolve.jvm.RuntimeAssertionsOnDeclarationBodyChecker;
import org.jetbrains.kotlin.resolve.sam.SamConstructorDescriptor;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.kotlin.resolve.scopes.receivers.TransientReceiver;
import org.jetbrains.kotlin.types.KotlinType;
import org.jetbrains.kotlin.backend.common.SamType;
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker;
import org.jetbrains.org.objectweb.asm.Type;

import java.util.*;

import static org.jetbrains.kotlin.codegen.binding.CodegenBinding.*;
import static org.jetbrains.kotlin.codegen.inline.InlineCodegenUtilsKt.NUMBERED_FUNCTION_PREFIX;
import static org.jetbrains.kotlin.lexer.KtTokens.*;
import static org.jetbrains.kotlin.name.SpecialNames.safeIdentifier;
import static org.jetbrains.kotlin.resolve.BindingContext.*;

class CodegenAnnotatingVisitor extends KtVisitorVoid {
    private static final TokenSet BINARY_OPERATIONS = TokenSet.orSet(
            AUGMENTED_ASSIGNMENTS,
            TokenSet.create(PLUS, MINUS, MUL, DIV, PERC, RANGE, LT, GT, LTEQ, GTEQ, IDENTIFIER)
    );

    private final Map<String, Integer> anonymousSubclassesCount = new HashMap<>();

    private final Stack<ClassDescriptor> classStack = new Stack<>();
    private final Stack<String> nameStack = new Stack<>();
    private final Stack<FunctionDescriptor> functionsStack = new Stack<>();
    private final Set<ClassDescriptor> uninitializedClasses = new HashSet<>();

    private final BindingTrace bindingTrace;
    private final BindingContext bindingContext;
    private final GenerationState.GenerateClassFilter filter;
    private final JvmRuntimeTypes runtimeTypes;
    private final SwitchCodegenProvider switchCodegenProvider;
    private final LanguageVersionSettings languageVersionSettings;
    private final ClassBuilderMode classBuilderMode;
    private final DelegatedPropertiesCodegenHelper delegatedPropertiesCodegenHelper;
    private final JvmDefaultMode jvmDefaultMode;
    private final SamTypeApproximator samTypeApproximator;

    public CodegenAnnotatingVisitor(@NotNull GenerationState state) {
        this.bindingTrace = state.getBindingTrace();
        this.bindingContext = state.getBindingContext();
        this.filter = state.getGenerateDeclaredClassFilter();
        this.runtimeTypes = state.getJvmRuntimeTypes();
        this.switchCodegenProvider = new SwitchCodegenProvider(state);
        this.languageVersionSettings = state.getLanguageVersionSettings();
        this.classBuilderMode = state.getClassBuilderMode();
        this.delegatedPropertiesCodegenHelper = new DelegatedPropertiesCodegenHelper(state);
        this.jvmDefaultMode = state.getJvmDefaultMode();
        this.samTypeApproximator = new SamTypeApproximator(state.getModule().getBuiltIns(), state.getLanguageVersionSettings());
    }

    @NotNull
    private ClassDescriptor recordClassForCallable(
            @NotNull KtElement element,
            @NotNull CallableDescriptor callableDescriptor,
            @NotNull Collection<KotlinType> supertypes,
            @NotNull String name
    ) {
        return recordClassForCallable(element, callableDescriptor, supertypes, name, null);
    }

    @NotNull
    private ClassDescriptor recordClassForFunction(
            @NotNull KtElement element,
            @NotNull FunctionDescriptor functionDescriptor,
            @NotNull String name,
            @Nullable DeclarationDescriptor customContainer
    ) {
        return recordClassForCallable(
                element, functionDescriptor,
                runtimeTypes.getSupertypesForClosure(functionDescriptor),
                name, customContainer
        );
    }

    @NotNull
    private ClassDescriptor recordClassForCallable(
            @NotNull KtElement element,
            @NotNull CallableDescriptor callableDescriptor,
            @NotNull Collection<KotlinType> supertypes,
            @NotNull String name,
            @Nullable DeclarationDescriptor customContainer
    ) {
        String simpleName = name.substring(name.lastIndexOf('/') + 1);
        ClassDescriptor classDescriptor = new SyntheticClassDescriptorForLambda(
                customContainer != null ? customContainer : correctContainerForLambda(callableDescriptor),
                Name.special("<closure-" + simpleName + ">"),
                supertypes,
                element
        );

        bindingTrace.record(CLASS_FOR_CALLABLE, callableDescriptor, classDescriptor);
        return classDescriptor;
    }

    @NotNull
    private DeclarationDescriptor correctContainerForLambda(@NotNull CallableDescriptor descriptor) {
        DeclarationDescriptor container = descriptor.getContainingDeclaration();

        // In almost all cases the function's direct container is the correct container to consider in JVM back-end
        // (and subsequently to write to EnclosingMethod and InnerClasses attributes).
        // The only exceptional case is when a lambda is declared in the super call of an anonymous object:
        // in this case it's constructed in the outer code, despite being located under the object PSI- and descriptor-wise
        // TODO: consider the possibility of fixing this in the compiler front-end

        while (container instanceof ConstructorDescriptor) {
            ClassDescriptor classDescriptor = ((ConstructorDescriptor) container).getConstructedClass();
            if (!DescriptorUtils.isAnonymousObject(classDescriptor)) break;
            if (!uninitializedClasses.contains(classDescriptor)) break;
            container = classDescriptor.getContainingDeclaration();
        }

        return container;
    }

    @NotNull
    private String inventAnonymousClassName() {
        String top = peekFromStack(nameStack);
        Integer cnt = anonymousSubclassesCount.get(top);
        if (cnt == null) {
            cnt = 0;
        }
        anonymousSubclassesCount.put(top, cnt + 1);

        return top + "$" + (cnt + 1);
    }

    @Override
    public void visitKtElement(@NotNull KtElement element) {
        super.visitKtElement(element);

        if (!classBuilderMode.generateBodies) {
            if (element instanceof KtFile) {
                KotlinFileStub stub = ((KtFile) element).getStub();
                if (stub != null) {
                    for (StubElement stubElement : stub.getChildrenStubs()) {
                        stubElement.getPsi().accept(this);
                    }
                    return;
                }
            }

            if (element instanceof KtElementImplStub<?> && ((KtElementImplStub) element).getStub() != null) {
                StubElement<?> stub = ((KtElementImplStub<?>) element).getStub();
                if (stub != null) {
                    for (StubElement stubElement : stub.getChildrenStubs()) {
                        stubElement.getPsi().accept(this);
                    }
                    return;
                }
            }
        }

        element.acceptChildren(this);
    }

    @Override
    public void visitScript(@NotNull KtScript script) {
        ClassDescriptor scriptDescriptor = bindingContext.get(SCRIPT, script);
        // working around a problem with shallow analysis
        if (scriptDescriptor == null) return;

        String scriptInternalName = AsmUtil.internalNameByFqNameWithoutInnerClasses(script.getFqName());
        recordClosure(scriptDescriptor, scriptInternalName);

        classStack.push(scriptDescriptor);
        nameStack.push(scriptInternalName);
        script.acceptChildren(this);
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitKtFile(@NotNull KtFile file) {
        String name;
        if (file instanceof KtCodeFragment) {
            CodeFragmentCodegenInfo info = CodeFragmentCodegen.getCodeFragmentInfo((KtCodeFragment) file);
            name = info.getClassDescriptor().getName().asString() + "$" + info.getMethodDescriptor().getName().asString();
        } else {
            name = AsmUtil.internalNameByFqNameWithoutInnerClasses(file.getPackageFqName());
        }

        nameStack.push(name);
        visitKtElement(file);
        nameStack.pop();
    }

    @Override
    public void visitEnumEntry(@NotNull KtEnumEntry enumEntry) {
        if (enumEntry.getDeclarations().isEmpty()) {
            for (KtSuperTypeListEntry specifier : enumEntry.getSuperTypeListEntries()) {
                specifier.accept(this);
            }
            return;
        }

        ClassDescriptor descriptor = bindingContext.get(CLASS, enumEntry);
        // working around a problem with shallow analysis
        if (descriptor == null) return;

        bindingTrace.record(ENUM_ENTRY_CLASS_NEED_SUBCLASS, descriptor);
        super.visitEnumEntry(enumEntry);
    }

    @Override
    public void visitObjectDeclaration(@NotNull KtObjectDeclaration declaration) {
        if (!filter.shouldAnnotateClass(declaration)) return;

        ClassDescriptor classDescriptor = bindingContext.get(CLASS, declaration);
        // working around a problem with shallow analysis
        if (classDescriptor == null) return;

        String name = getName(classDescriptor);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitObjectDeclaration(declaration);
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitClass(@NotNull KtClass klass) {
        if (!filter.shouldAnnotateClass(klass)) return;

        ClassDescriptor classDescriptor = bindingContext.get(CLASS, klass);
        // working around a problem with shallow analysis
        if (classDescriptor == null) return;

        String name = getName(classDescriptor);
        recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);
        super.visitClass(klass);
        nameStack.pop();
        classStack.pop();
    }

    private String getName(ClassDescriptor classDescriptor) {
        String base = peekFromStack(nameStack);
        Name descriptorName = SpecialNames.safeIdentifier(classDescriptor.getName());
        if (DescriptorUtils.isTopLevelDeclaration(classDescriptor)) {
            return base.isEmpty() ? descriptorName.asString() : base + '/' + descriptorName;
        }
        else {
            return base + "$" + descriptorName.asString();
        }
    }

    @Override
    public void visitObjectLiteralExpression(@NotNull KtObjectLiteralExpression expression) {
        KtObjectDeclaration object = expression.getObjectDeclaration();
        ClassDescriptor classDescriptor = bindingContext.get(CLASS, object);
        if (classDescriptor == null) {
            // working around a problem with shallow analysis
            super.visitObjectLiteralExpression(expression);
            return;
        }

        String name = inventAnonymousClassName();
        recordClosure(classDescriptor, name);

        KtSuperTypeList delegationSpecifierList = object.getSuperTypeList();
        if (delegationSpecifierList != null) {
            delegationSpecifierList.accept(this);
        }

        classStack.push(classDescriptor);
        nameStack.push(CodegenBinding.getAsmType(bindingContext, classDescriptor).getInternalName());
        KtClassBody body = object.getBody();
        if (body != null) {
            super.visitClassBody(body);
        }
        nameStack.pop();
        classStack.pop();
    }

    @Override
    public void visitLambdaExpression(@NotNull KtLambdaExpression lambdaExpression) {
        KtFunctionLiteral functionLiteral = lambdaExpression.getFunctionLiteral();
        FunctionDescriptor functionDescriptor =
                (FunctionDescriptor) bindingContext.get(DECLARATION_TO_DESCRIPTOR, functionLiteral);
        // working around a problem with shallow analysis
        if (functionDescriptor == null) return;

        String name = inventAnonymousClassName();
        Collection<KotlinType> supertypes = runtimeTypes.getSupertypesForClosure(functionDescriptor);
        ClassDescriptor classDescriptor = recordClassForCallable(functionLiteral, functionDescriptor, supertypes, name);
        MutableClosure closure = recordClosure(classDescriptor, name);

        classStack.push(classDescriptor);
        nameStack.push(name);

        if (CoroutineUtilKt.isSuspendLambda(functionDescriptor)) {
            createAndRecordSuspendFunctionView(closure, (SimpleFunctionDescriptor) functionDescriptor, true);
        }
        functionsStack.push(functionDescriptor);

        super.visitLambdaExpression(lambdaExpression);

        functionsStack.pop();
        nameStack.pop();
        classStack.pop();
    }

    private boolean isAdaptedCallableReference(
            @NotNull KtCallableReferenceExpression expression,
            @NotNull ResolvedCall<?> resolvedCall,
            boolean isSuspendConversion
    ) {
        if (isSuspendConversion) return true;

        CallableDescriptor resultingDescriptor = resolvedCall.getResultingDescriptor();
        if (!(resultingDescriptor instanceof FunctionDescriptor)) return false;
        FunctionDescriptor functionDescriptor = (FunctionDescriptor) resultingDescriptor;

        // Callable reference is adapted if:
        // - adapter arguments mapping is present in value arguments of corresponding resolved call;
        // - return type is not Unit, and expected return type is Unit.

        if (!resolvedCall.getValueArguments().isEmpty()) return true;

        KotlinType callableReferenceType = bindingContext.getType(expression);
        if (callableReferenceType != null) {
            KotlinType callableReferenceReturnType = CollectionsKt.last(callableReferenceType.getArguments()).getType();
            KotlinType functionReturnType = functionDescriptor.getReturnType();
            return functionReturnType != null &&
                   KotlinBuiltIns.isUnit(callableReferenceReturnType) && !KotlinBuiltIns.isUnit(functionReturnType);
        }
        return false;
    }

    @Override
    public void visitCallableReferenceExpression(@NotNull KtCallableReferenceExpression expression) {
        ResolvedCall<?> referencedFunction = CallUtilKt.getResolvedCall(expression.getCallableReference(), bindingContext);
        if (referencedFunction == null) return;
        CallableDescriptor target = referencedFunction.getResultingDescriptor();

        ReceiverValue extensionReceiver = referencedFunction.getExtensionReceiver();
        ReceiverValue dispatchReceiver = referencedFunction.getDispatchReceiver();

        // TransientReceiver corresponds to an unbound reference, other receiver values -- to bound references
        KotlinType receiverType =
                dispatchReceiver != null && !(dispatchReceiver instanceof TransientReceiver) ? dispatchReceiver.getType() :
                extensionReceiver != null && !(extensionReceiver instanceof TransientReceiver) ? extensionReceiver.getType() :
                null;

        CallableDescriptor callableDescriptor;
        Collection<KotlinType> supertypes;

        if (target instanceof FunctionDescriptor) {
            FunctionDescriptor targetFunction = (FunctionDescriptor) target;
            callableDescriptor = bindingContext.get(FUNCTION, expression);
            if (callableDescriptor == null) return;

            KotlinType functionReferenceType = bindingContext.getType(expression);
            boolean isSuspendConversion =
                    !targetFunction.isSuspend() &&
                    functionReferenceType != null &&
                    FunctionTypesKt.isKSuspendFunctionType(functionReferenceType);

            supertypes = runtimeTypes.getSupertypesForFunctionReference(
                    targetFunction, (AnonymousFunctionDescriptor) callableDescriptor, receiverType != null,
                    isAdaptedCallableReference(expression, referencedFunction, isSuspendConversion),
                    isSuspendConversion
            );
        }
        else if (target instanceof PropertyDescriptor) {
            callableDescriptor = bindingContext.get(VARIABLE, expression);
            if (callableDescriptor == null) return;

            //noinspection ConstantConditions
            supertypes = Collections.singleton(
                    runtimeTypes.getSupertypeForPropertyReference(
                            (PropertyDescriptor) target,
                            ReflectionTypes.Companion.isNumberedKMutablePropertyType(callableDescriptor.getReturnType()),
                            receiverType != null
                    )
            );
        }
        else {
            return;
        }

        String name = inventAnonymousClassName();
        ClassDescriptor classDescriptor = recordClassForCallable(expression, callableDescriptor, supertypes, name);
        MutableClosure closure = recordClosure(classDescriptor, name);

        if (callableDescriptor instanceof SimpleFunctionDescriptor) {
            SimpleFunctionDescriptor functionDescriptor = (SimpleFunctionDescriptor) callableDescriptor;
            if (functionDescriptor.isSuspend()) {
                createAndRecordSuspendFunctionView(closure, functionDescriptor, false);
                boolean isRecursive = false;
                for (FunctionDescriptor descriptor: functionsStack) {
                    if (descriptor == target) {
                        isRecursive = true;
                        break;
                    }
                }
                bindingTrace.record(RECURSIVE_SUSPEND_CALLABLE_REFERENCE, classDescriptor, isRecursive);
            }
        }

        if (receiverType != null) {
            closure.setCustomCapturedReceiverType(receiverType);
        }

        super.visitCallableReferenceExpression(expression);
    }

    private SimpleFunctionDescriptor createAndRecordSuspendFunctionView(
            MutableClosure closure,
            SimpleFunctionDescriptor functionDescriptor,
            boolean isSuspendLambda
    ) {
        SimpleFunctionDescriptor jvmSuspendFunctionView =
                CoroutineCodegenUtilKt.getOrCreateJvmSuspendFunctionView(
                        functionDescriptor,
                        this.bindingContext
                );

        bindingTrace.record(
                CodegenBinding.SUSPEND_FUNCTION_TO_JVM_VIEW,
                functionDescriptor,
                jvmSuspendFunctionView
        );

        closure.setSuspend(true);
        if (isSuspendLambda) {
            closure.setSuspendLambda();
        }
        return jvmSuspendFunctionView;
    }

    @NotNull
    private MutableClosure recordClosure(@NotNull ClassDescriptor classDescriptor, @NotNull String name) {
        Type type = Type.getObjectType(JvmCodegenUtil.sanitizeNameIfNeeded(name, languageVersionSettings));
        return CodegenBinding.recordClosure(bindingTrace, classDescriptor, getProperEnclosingClass(), type);
    }

    @Nullable
    private ClassDescriptor getProperEnclosingClass() {
        for (int i = classStack.size() - 1; i >= 0; i--) {
            ClassDescriptor fromStack = classStack.get(i);
            if (!uninitializedClasses.contains(fromStack)) {
                return fromStack;
            }
        }
        return null;
    }

    private void recordLocalVariablePropertyMetadata(LocalVariableDescriptor variableDescriptor) {
        KotlinType delegateType = JvmCodegenUtil.getPropertyDelegateType(variableDescriptor, bindingContext);
        if (delegateType == null) return;

        LocalVariableDescriptor metadataVariableDescriptor = new LocalVariableDescriptor(
                variableDescriptor.getContainingDeclaration(),
                Annotations.Companion.getEMPTY(),
                Name.identifier(variableDescriptor.getName().asString() + "$metadata"),
                ReflectionTypes.Companion.createKPropertyStarType(DescriptorUtilsKt.getModule(variableDescriptor)),
                SourceElement.NO_SOURCE
        );
        bindingTrace.record(LOCAL_VARIABLE_PROPERTY_METADATA, variableDescriptor, metadataVariableDescriptor);
    }

    @Override
    public void visitProperty(@NotNull KtProperty property) {
        DeclarationDescriptor descriptor = bindingContext.get(DECLARATION_TO_DESCRIPTOR, property);
        // working around a problem with shallow analysis
        if (descriptor == null) return;

        checkRuntimeAsserionsOnDeclarationBody(property, descriptor);

        if (descriptor instanceof LocalVariableDescriptor) {
            recordLocalVariablePropertyMetadata((LocalVariableDescriptor) descriptor);
        }

        String nameForClassOrPackageMember = getNameForClassOrPackageMember(descriptor);
        if (nameForClassOrPackageMember != null) {
            nameStack.push(nameForClassOrPackageMember);
        }
        else {
            nameStack.push(peekFromStack(nameStack) + '$' + safeIdentifier(property.getNameAsSafeName()).asString());
        }

        KtPropertyDelegate delegate = property.getDelegate();
        if (delegate != null && descriptor instanceof VariableDescriptorWithAccessors) {
            VariableDescriptorWithAccessors variableDescriptor = (VariableDescriptorWithAccessors) descriptor;
            String name = inventAnonymousClassName();
            KotlinType supertype =
                    runtimeTypes.getSupertypeForPropertyReference(variableDescriptor, variableDescriptor.isVar(), /* bound = */ false);
            ClassDescriptor classDescriptor = recordClassForCallable(delegate, variableDescriptor, Collections.singleton(supertype), name);
            recordClosure(classDescriptor, name);

            if (delegatedPropertiesCodegenHelper.isDelegatedPropertyMetadataRequired(variableDescriptor)) {
                Type containerType = getMetadataOwner(property);
                List<VariableDescriptorWithAccessors> descriptors = bindingTrace.get(DELEGATED_PROPERTIES_WITH_METADATA, containerType);
                if (descriptors == null) {
                    descriptors = new ArrayList<>(1);
                    bindingTrace.record(DELEGATED_PROPERTIES_WITH_METADATA, containerType, descriptors);
                }
                descriptors.add(variableDescriptor);

                bindingTrace.record(DELEGATED_PROPERTY_METADATA_OWNER, variableDescriptor, containerType);
            }
            else {
                bindingTrace.record(DELEGATED_PROPERTY_WITH_OPTIMIZED_METADATA, variableDescriptor);
            }
        }

        super.visitProperty(property);
        nameStack.pop();
    }

    private void checkRuntimeAsserionsOnDeclarationBody(@NotNull KtDeclaration declaration, DeclarationDescriptor descriptor) {
        if (classBuilderMode.generateBodies) {
            // NB This is required only for bodies generation.
            // In light class generation can cause recursion in types resolution.
            RuntimeAssertionsOnDeclarationBodyChecker.check(declaration, descriptor, bindingTrace, languageVersionSettings);
        }
    }

    @NotNull
    private Type getMetadataOwner(@NotNull KtProperty property) {
        for (int i = classStack.size() - 1; i >= 0; i--) {
            ClassDescriptor descriptor = classStack.get(i);
            // The first "real" containing class (not a synthetic class for lambda) is the owner of the delegated property metadata
            if (!(descriptor instanceof SyntheticClassDescriptorForLambda)) {
                ClassId classId = DescriptorUtilsKt.getClassId(descriptor);
                if (classId == null) {
                    return CodegenBinding.getAsmType(bindingContext, descriptor);
                }

                return AsmUtil.asmTypeByClassId(
                        DescriptorUtils.isInterface(descriptor) && !jvmDefaultMode.getForAllMethodsWithBody()
                        ? classId.createNestedClassId(Name.identifier(JvmAbi.DEFAULT_IMPLS_CLASS_NAME))
                        : classId
                );
            }
        }

        return Type.getObjectType(JvmFileClassUtil.getFileClassInternalName(property.getContainingKtFile()));
    }

    @Override
    public void visitPropertyAccessor(@NotNull KtPropertyAccessor accessor) {
        PropertyAccessorDescriptor accessorDescriptor = bindingContext.get(PROPERTY_ACCESSOR, accessor);
        if (accessorDescriptor != null) {
            checkRuntimeAsserionsOnDeclarationBody(accessor, accessorDescriptor);
        }

        super.visitPropertyAccessor(accessor);
    }

    @Override
    public void visitNamedFunction(@NotNull KtNamedFunction function) {
        FunctionDescriptor functionDescriptor = (FunctionDescriptor) bindingContext.get(DECLARATION_TO_DESCRIPTOR, function);
        // working around a problem with shallow analysis
        if (functionDescriptor == null) return;

        checkRuntimeAsserionsOnDeclarationBody(function, functionDescriptor);

        String nameForClassOrPackageMember = getNameForClassOrPackageMember(functionDescriptor);

        if (functionDescriptor instanceof SimpleFunctionDescriptor && functionDescriptor.isSuspend() &&
            !functionDescriptor.getVisibility().equals(DescriptorVisibilities.LOCAL)) {

            if (nameForClassOrPackageMember != null) {
                nameStack.push(nameForClassOrPackageMember);
            }

            String name = inventAnonymousClassName();
            ClassDescriptor classDescriptor = recordClassForFunction(function, functionDescriptor, name, functionDescriptor);
            MutableClosure closure = recordClosure(classDescriptor, name);

            SimpleFunctionDescriptor jvmSuspendFunctionView =
                    createAndRecordSuspendFunctionView(closure, (SimpleFunctionDescriptor) functionDescriptor, false);

            // This is a very subtle place (hack).
            // When generating bytecode of some suspend function, we replace the original descriptor
            // with one that reflects how it should look on JVM.
            // But the problem is that the function may contain resolved calls referencing original parameters, that are recreated
            // in jvmSuspendFunctionView.
            // So we remember the relation between the old and the new parameter descriptors and use it when looking for their indices
            // in ExpressionCodegen.
            for (Pair<ValueParameterDescriptor, ValueParameterDescriptor> parameterDescriptorPair : CollectionsKt
                    .zip(functionDescriptor.getValueParameters(), jvmSuspendFunctionView.getValueParameters())) {
                bindingTrace.record(
                        CodegenBinding.PARAMETER_SYNONYM, parameterDescriptorPair.getFirst(), parameterDescriptorPair.getSecond()
                );
            }

            functionsStack.push(functionDescriptor);

            super.visitNamedFunction(function);

            functionsStack.pop();
            if (nameForClassOrPackageMember != null) {
                nameStack.pop();
            }

            return;
        }

        if (nameForClassOrPackageMember != null) {
            nameStack.push(nameForClassOrPackageMember);
            functionsStack.push(functionDescriptor);
            super.visitNamedFunction(function);
            functionsStack.pop();
            nameStack.pop();
        }
        else {
            String name = inventAnonymousClassName();
            ClassDescriptor classDescriptor = recordClassForFunction(function, functionDescriptor, name, null);
            MutableClosure closure = recordClosure(classDescriptor, name);

            classStack.push(classDescriptor);
            nameStack.push(name);

            if (functionDescriptor instanceof SimpleFunctionDescriptor && functionDescriptor.isSuspend()) {
                createAndRecordSuspendFunctionView(closure, (SimpleFunctionDescriptor) functionDescriptor, true);
            }

            functionsStack.push(functionDescriptor);
            super.visitNamedFunction(function);
            functionsStack.pop();
            nameStack.pop();
            classStack.pop();
        }
    }

    @Nullable
    private String getNameForClassOrPackageMember(@NotNull DeclarationDescriptor descriptor) {
        DeclarationDescriptor containingDeclaration = descriptor.getContainingDeclaration();

        String peek = peekFromStack(nameStack);
        String name = safeIdentifier(descriptor.getName()).asString();
        if (containingDeclaration instanceof ClassDescriptor) {
            return peek + '$' + name;
        }
        else if (containingDeclaration instanceof PackageFragmentDescriptor) {
            KtFile containingFile = DescriptorToSourceUtils.getContainingFile(descriptor);
            assert containingFile != null : "File not found for " + descriptor;
            return JvmFileClassUtil.getFileClassInternalName(containingFile) + '$' + name;
        }

        return null;
    }

    @Override
    public void visitCallExpression(@NotNull KtCallExpression expression) {
        super.visitCallExpression(expression);
        checkSamCall(expression);
        checkCrossinlineCall(expression);
    }

    private void checkCrossinlineCall(@NotNull KtCallExpression expression) {
        KtExpression callee = expression.getCalleeExpression();
        Call call = bindingContext.get(CALL, callee);
        ResolvedCall<?> resolvedCall = bindingContext.get(RESOLVED_CALL, call);

        if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
            VariableAsFunctionResolvedCall variableAsFunction = (VariableAsFunctionResolvedCall) resolvedCall;
            VariableDescriptor variableDescriptor = variableAsFunction.getVariableCall().getResultingDescriptor();

            if (variableDescriptor instanceof ValueParameterDescriptor &&
                ((ValueParameterDescriptor) variableDescriptor).isCrossinline()) {
                DeclarationDescriptor functionWithCrossinlineParameter = variableDescriptor.getContainingDeclaration();
                if (functionsStack.peek().isSuspend()) {
                    bindingTrace.record(CALL_SITE_IS_SUSPEND_FOR_CROSSINLINE_LAMBDA, (ValueParameterDescriptor) variableDescriptor, true);
                }
                for (int i = functionsStack.size() - 1; i >= 0; i--) {
                    Boolean alreadyPutValue = bindingTrace.getBindingContext()
                            .get(CodegenBinding.CAPTURES_CROSSINLINE_LAMBDA, functionsStack.get(i));
                    if (alreadyPutValue != null && alreadyPutValue) {
                        return;
                    }
                    bindingTrace.record(
                            CodegenBinding.CAPTURES_CROSSINLINE_LAMBDA,
                            functionsStack.get(i),
                            true
                    );
                    if (functionsStack.get(i) == functionWithCrossinlineParameter) {
                        return;
                    }
                }
            }
        }
    }

    private void checkSamCall(@NotNull KtCallElement expression) {
        ResolvedCall<?> call = CallUtilKt.getResolvedCall(expression, bindingContext);
        if (call == null) return;

        CallableDescriptor descriptor = call.getResultingDescriptor();
        if (!(descriptor instanceof FunctionDescriptor)) return;

        recordSamValuesForNewInference(call);
        recordSamConstructorIfNeeded(expression, call);
        recordSamValuesForOldInference(call, descriptor);
    }

    private void recordSamValuesForOldInference(ResolvedCall<?> call, CallableDescriptor descriptor) {
        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) descriptor);
        if (original == null) return;

        // TODO we can just record SAM_VALUE on relevant value arguments as we do in recordSamValuesForNewInference
        List<ValueParameterDescriptor> valueParametersWithSAMConversion = new SmartList<>();
        for (ValueParameterDescriptor valueParameter : original.getValueParameters()) {
            ValueParameterDescriptor adaptedParameter = descriptor.getValueParameters().get(valueParameter.getIndex());
            if (KotlinTypeChecker.DEFAULT.equalTypes(adaptedParameter.getType(), valueParameter.getType())) continue;
            valueParametersWithSAMConversion.add(valueParameter);
        }
        writeSamValueForValueParameters(valueParametersWithSAMConversion, call.getValueArgumentsByIndex());
    }

    private void recordSamValuesForNewInference(@NotNull ResolvedCall<?> call) {
        NewResolvedCallImpl<?> newResolvedCall = getNewResolvedCallForCallWithPossibleSamConversions(call);
        if (newResolvedCall == null) return;

        Map<ValueParameterDescriptor, ResolvedValueArgument> arguments = newResolvedCall.getValueArguments();
        for (ValueParameterDescriptor valueParameter : arguments.keySet()) {
            ResolvedValueArgument argument = arguments.get(valueParameter);
            if (argument instanceof ExpressionValueArgument) {
                ValueArgument valueArgument = ((ExpressionValueArgument) argument).getValueArgument();
                if (valueArgument != null && newResolvedCall.getExpectedTypeForSamConvertedArgument(valueArgument) != null) {
                    recordSamTypeOnArgumentExpression(valueParameter, valueArgument);
                }
            } else if (argument instanceof VarargValueArgument) {
                VarargValueArgument varargValueArgument = (VarargValueArgument) argument;
                for (ValueArgument valueArgument : varargValueArgument.getArguments()) {
                    if (valueArgument != null && newResolvedCall.getExpectedTypeForSamConvertedArgument(valueArgument) != null) {
                        recordSamTypeOnArgumentExpression(valueParameter, valueArgument);
                    }
                }
            }
        }
    }

    @Nullable
    private static NewResolvedCallImpl<?> getNewResolvedCallForCallWithPossibleSamConversions(@NotNull ResolvedCall<?> call) {
        if (call instanceof NewVariableAsFunctionResolvedCallImpl) {
            return ((NewVariableAsFunctionResolvedCallImpl) call).getFunctionCall();
        }
        else if (call instanceof NewResolvedCallImpl) {
            return (NewResolvedCallImpl<?>) call;
        }
        else {
            return null;
        }
    }

    @Nullable
    private SamType createSamType(KotlinType kotlinType) {
        if (!JavaSingleAbstractMethodUtils.isSamType(kotlinType)) return null;
        return new SamType(kotlinType);
    }

    @Nullable
    private SamType createSamTypeByValueParameter(ValueParameterDescriptor valueParameterDescriptor) {
        KotlinType kotlinSamType = samTypeApproximator.getSamTypeForValueParameter(valueParameterDescriptor);
        if (kotlinSamType == null) return null;
        if (!JavaSingleAbstractMethodUtils.isSamType(kotlinSamType)) return null;
        return new SamType(kotlinSamType);
    }

    private void writeSamValueForValueParameters(
            @NotNull Collection<ValueParameterDescriptor> valueParametersWithSAMConversion,
            @Nullable List<ResolvedValueArgument> valueArguments
    ) {
        if (valueArguments == null) return;

        for (ValueParameterDescriptor valueParameter : valueParametersWithSAMConversion) {
            SamType samType = createSamTypeByValueParameter(valueParameter);
            if (samType == null) continue;

            ResolvedValueArgument resolvedValueArgument = valueArguments.get(valueParameter.getIndex());
            assert resolvedValueArgument instanceof ExpressionValueArgument : resolvedValueArgument;
            ValueArgument valueArgument = ((ExpressionValueArgument) resolvedValueArgument).getValueArgument();
            assert valueArgument != null;
            recordSamTypeOnArgumentExpression(samType, valueArgument);
        }
    }

    private void recordSamTypeOnArgumentExpression(ValueParameterDescriptor valueParameter, ValueArgument valueArgument) {
        SamType samType = createSamTypeByValueParameter(valueParameter);
        if (samType == null) return;

        recordSamTypeOnArgumentExpression(samType, valueArgument);
    }

    private void recordSamTypeOnArgumentExpression(SamType samType, ValueArgument valueArgument) {
        KtExpression argumentExpression = valueArgument.getArgumentExpression();
        assert argumentExpression != null : valueArgument.asElement().getText();

        bindingTrace.record(CodegenBinding.SAM_VALUE, argumentExpression, samType);
    }

    @Override
    public void visitSuperTypeCallEntry(@NotNull KtSuperTypeCallEntry call) {
        // Closures in super type constructor calls for anonymous objects are created in outer context
        if (!isSuperTypeCallForAnonymousObject(call)) {
            withinUninitializedClass(call, () -> super.visitSuperTypeCallEntry(call));
        }
        else {
            super.visitSuperTypeCallEntry(call);
        }

        checkSamCall(call);
    }

    private static boolean isSuperTypeCallForAnonymousObject(@NotNull KtSuperTypeCallEntry call) {
        PsiElement parent = call.getParent();
        if (!(parent instanceof KtSuperTypeList)) return false;
        parent = parent.getParent();
        if (!(parent instanceof KtObjectDeclaration)) return false;
        parent = parent.getParent();
        if (!(parent instanceof KtObjectLiteralExpression)) return false;
        return true;
    }

    @Override
    public void visitConstructorDelegationCall(@NotNull KtConstructorDelegationCall call) {
        withinUninitializedClass(call, () -> super.visitConstructorDelegationCall(call));

        checkSamCall(call);
    }

    @Override
    public void visitParameter(@NotNull KtParameter parameter) {
        PsiElement parent = parameter.getParent(); // KtParameterList
        if (parent != null && parent.getParent() instanceof KtConstructor<?>) {
            KtExpression defaultValue = parameter.getDefaultValue();
            if (defaultValue != null) {
                withinUninitializedClass(defaultValue, () -> defaultValue.accept(this));
            }
        } else {
            super.visitParameter(parameter);
        }
    }

    private void withinUninitializedClass(@NotNull KtElement element, @NotNull Runnable operation) {
        ClassDescriptor currentClass = peekFromStack(classStack);
        assert currentClass != null : element.getClass().getSimpleName() + " should be inside a class: " + element.getText();
        assert !uninitializedClasses.contains(currentClass) : "Class entered twice: " + currentClass;
        uninitializedClasses.add(currentClass);
        operation.run();
        boolean removed = uninitializedClasses.remove(currentClass);
        assert removed : "Inconsistent uninitialized class stack: " + currentClass;
    }

    private void recordSamConstructorIfNeeded(@NotNull KtCallElement expression, @NotNull ResolvedCall<?> call) {
        CallableDescriptor callableDescriptor = call.getResultingDescriptor();
        if (!(callableDescriptor.getOriginal() instanceof SamConstructorDescriptor)) return;

        List<ResolvedValueArgument> valueArguments = call.getValueArgumentsByIndex();
        if (valueArguments == null || valueArguments.size() != 1) return;

        ResolvedValueArgument valueArgument = valueArguments.get(0);
        if (!(valueArgument instanceof ExpressionValueArgument)) return;
        ValueArgument argument = ((ExpressionValueArgument) valueArgument).getValueArgument();
        if (argument == null) return;

        KtExpression argumentExpression = argument.getArgumentExpression();
        bindingTrace.record(SAM_CONSTRUCTOR_TO_ARGUMENT, expression, argumentExpression);

        SamType samType = createSamType(callableDescriptor.getReturnType());
        bindingTrace.record(SAM_VALUE, argumentExpression, samType);
    }

    @Override
    public void visitBinaryExpression(@NotNull KtBinaryExpression expression) {
        super.visitBinaryExpression(expression);

        DeclarationDescriptor operationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression.getOperationReference());
        if (!(operationDescriptor instanceof FunctionDescriptor)) return;

        ResolvedCall<?> resolvedCall = CallUtilKt.getResolvedCall(expression, bindingContext);
        if (resolvedCall != null) recordSamValuesForNewInference(resolvedCall);

        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) operationDescriptor);
        if (original == null) return;

        SamType samType = createSamTypeByValueParameter(original.getValueParameters().get(0));
        if (samType == null) return;

        IElementType token = expression.getOperationToken();
        if (BINARY_OPERATIONS.contains(token)) {
            bindingTrace.record(CodegenBinding.SAM_VALUE, expression.getRight(), samType);
        }
        else if (token == IN_KEYWORD || token == NOT_IN) {
            bindingTrace.record(CodegenBinding.SAM_VALUE, expression.getLeft(), samType);
        }
    }

    @Override
    public void visitArrayAccessExpression(@NotNull KtArrayAccessExpression expression) {
        super.visitArrayAccessExpression(expression);

        DeclarationDescriptor operationDescriptor = bindingContext.get(BindingContext.REFERENCE_TARGET, expression);
        if (!(operationDescriptor instanceof FunctionDescriptor)) return;

        ResolvedCall<?> resolvedCall = CallUtilKt.getResolvedCall(expression, bindingContext);
        if (resolvedCall != null) recordSamValuesForNewInference(resolvedCall);

        boolean isSetter = operationDescriptor.getName().asString().equals("set");
        FunctionDescriptor original = SamCodegenUtil.getOriginalIfSamAdapter((FunctionDescriptor) operationDescriptor);
        if (original == null) return;

        List<KtExpression> indexExpressions = expression.getIndexExpressions();
        List<ValueParameterDescriptor> parameters = original.getValueParameters();
        for (ValueParameterDescriptor valueParameter : parameters) {
            SamType samType = createSamTypeByValueParameter(valueParameter);
            if (samType == null) continue;

            if (isSetter && valueParameter.getIndex() == parameters.size() - 1) {
                PsiElement parent = expression.getParent();
                if (parent instanceof KtBinaryExpression && ((KtBinaryExpression) parent).getOperationToken() == EQ) {
                    KtExpression right = ((KtBinaryExpression) parent).getRight();
                    bindingTrace.record(CodegenBinding.SAM_VALUE, right, samType);
                }
            }
            else {
                KtExpression indexExpression = indexExpressions.get(valueParameter.getIndex());
                bindingTrace.record(CodegenBinding.SAM_VALUE, indexExpression, samType);
            }
        }
    }

    @Override
    public void visitWhenExpression(@NotNull KtWhenExpression expression) {
        super.visitWhenExpression(expression);
        if (!isWhenWithEnums(expression)) return;

        String currentClassName = getCurrentTopLevelClassOrPackagePartInternalName(expression.getContainingKtFile());

        if (bindingContext.get(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName) == null) {
            bindingTrace.record(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName, new ArrayList<>(1));
        }

        List<WhenByEnumsMapping> mappings = bindingContext.get(MAPPINGS_FOR_WHENS_BY_ENUM_IN_CLASS_FILE, currentClassName);
        assert mappings != null : "guaranteed by contract";

        int fieldNumber = mappings.size();

        assert expression.getSubjectExpression() != null : "subject expression should be not null in a valid when by enums";

        KotlinType type = WhenChecker.whenSubjectType(expression, bindingContext);
        assert type != null : "should not be null in a valid when by enums";

        ClassDescriptor classDescriptor = (ClassDescriptor) type.getConstructor().getDeclarationDescriptor();
        assert classDescriptor != null : "because it's enum";

        WhenByEnumsMapping mapping = new WhenByEnumsMapping(classDescriptor, currentClassName, fieldNumber);

        for (ConstantValue<?> constant : switchCodegenProvider.getAllConstants(expression)) {
            if (constant instanceof NullValue) continue;

            assert constant instanceof EnumValue : "expression in when should be EnumValue";
            mapping.putFirstTime((EnumValue) constant, mapping.size() + 1);
        }

        mappings.add(mapping);

        bindingTrace.record(MAPPING_FOR_WHEN_BY_ENUM, expression, mapping);
    }

    private boolean isWhenWithEnums(@NotNull KtWhenExpression expression) {
        ClassId enumClassId = WhenChecker.getClassIdForEnumSubject(expression, bindingContext);
        if (enumClassId == null) return false;

        return switchCodegenProvider.checkAllItemsAreConstantsSatisfying(
                expression,
                constant -> isEnumEntryOrNull(enumClassId, constant)
        );
    }

    private static boolean isEnumEntryOrNull(ClassId enumClassId, ConstantValue<?> constant) {
        return (constant instanceof EnumValue && ((EnumValue) constant).getEnumClassId().equals(enumClassId)) ||
               constant instanceof NullValue;
    }

    @NotNull
    private String getCurrentTopLevelClassOrPackagePartInternalName(@NotNull KtFile file) {
        ListIterator<ClassDescriptor> iterator = classStack.listIterator(classStack.size());
        while (iterator.hasPrevious()) {
            ClassDescriptor previous = iterator.previous();
            if (DescriptorUtils.isTopLevelOrInnerClass(previous)) {
                return CodegenBinding.getAsmType(bindingContext, previous).getInternalName();
            }
        }

        return JvmFileClassUtil.getFacadeClassInternalName(file);
    }

    private static <T> T peekFromStack(@NotNull Stack<T> stack) {
        return stack.empty() ? null : stack.peek();
    }
}
