/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.initializer;

import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.js.translate.callTranslator.CallTranslator;
import org.jetbrains.kotlin.js.translate.context.Namer;
import org.jetbrains.kotlin.js.translate.context.TranslationContext;
import org.jetbrains.kotlin.js.translate.declaration.DelegationTranslator;
import org.jetbrains.kotlin.js.translate.general.AbstractTranslator;
import org.jetbrains.kotlin.js.translate.reference.CallArgumentTranslator;
import org.jetbrains.kotlin.lexer.JetTokens;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilPackage;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.types.JetType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.kotlin.js.translate.utils.BindingUtils.*;
import static org.jetbrains.kotlin.js.translate.utils.FunctionBodyTranslator.setDefaultValueForArguments;
import static org.jetbrains.kotlin.js.translate.utils.PsiUtils.getPrimaryConstructorParameters;
import static org.jetbrains.kotlin.js.translate.utils.jsAstUtils.JsAstUtilsPackage.toInvocationWith;
import static org.jetbrains.kotlin.resolve.DescriptorUtils.getClassDescriptorForType;

public final class ClassInitializerTranslator extends AbstractTranslator {
    @NotNull
    private final JetClassOrObject classDeclaration;
    @NotNull
    private final List<JsStatement> initializerStatements = new SmartList<JsStatement>();

    public ClassInitializerTranslator(
            @NotNull JetClassOrObject classDeclaration,
            @NotNull TranslationContext context
    ) {
        super(context.newDeclarationWithScope(
                getClassDescriptor(context.bindingContext(), classDeclaration),
                new JsFunctionScope(context.scope(), "scope for primary/default constructor")));
        this.classDeclaration = classDeclaration;
    }

    @NotNull
    public JsFunction generateInitializeMethod(DelegationTranslator delegationTranslator) {
        //TODO: it's inconsistent that we have scope for class and function for constructor, currently have problems implementing better way
        ClassDescriptor classDescriptor = getClassDescriptor(bindingContext(), classDeclaration);
        ConstructorDescriptor primaryConstructor = classDescriptor.getUnsubstitutedPrimaryConstructor();

        JsFunction result;
        if (primaryConstructor != null) {
            result = context().getFunctionObject(primaryConstructor);

            result.getBody().getStatements().addAll(setDefaultValueForArguments(primaryConstructor, context()));

            //NOTE: while we translate constructor parameters we also add property initializer statements
            // for properties declared as constructor parameters
            result.getParameters().addAll(translatePrimaryConstructorParameters());

            mayBeAddCallToSuperMethod(result);
        }
        else {
            result = new JsFunction(context().scope(), new JsBlock(), "fake constructor for " + classDescriptor.getName().asString());
        }

        delegationTranslator.addInitCode(initializerStatements);
        new InitializerVisitor(initializerStatements).traverseContainer(classDeclaration, context());

        List<JsStatement> statements = result.getBody().getStatements();

        for (JsStatement statement : initializerStatements) {
            if (statement instanceof JsBlock) {
                statements.addAll(((JsBlock) statement).getStatements());
            }
            else {
                statements.add(statement);
            }
        }

        return result;
    }

    @NotNull
    public JsExpression generateEnumEntryInstanceCreation(@NotNull JetType enumClassType) {
        ResolvedCall<FunctionDescriptor> superCall = getSuperCall();

        if (superCall == null) {
            ClassDescriptor classDescriptor = getClassDescriptorForType(enumClassType);
            JsNameRef reference = context().getQualifiedReference(classDescriptor);
            return new JsNew(reference);
        }

        return CallTranslator.translate(context(), superCall);
    }

    private void mayBeAddCallToSuperMethod(JsFunction initializer) {
        if (classDeclaration.hasModifier(JetTokens.ENUM_KEYWORD)) {
            addCallToSuperMethod(Collections.<JsExpression>emptyList(), initializer);
            return;
        }
        if (hasAncestorClass(bindingContext(), classDeclaration)) {
            ResolvedCall<FunctionDescriptor> superCall = getSuperCall();
            if (superCall == null) return;

            if (classDeclaration instanceof JetEnumEntry) {
                JsExpression expression = CallTranslator.translate(context(), superCall, null);
                JsExpression fixedInvocation = toInvocationWith(expression, JsLiteral.THIS);
                initializerStatements.add(0, fixedInvocation.makeStmt());
            }
            else {
                List<JsExpression> arguments = CallArgumentTranslator.translate(superCall, null, context()).getTranslateArguments();
                addCallToSuperMethod(arguments, initializer);
            }
        }
    }

    private void addCallToSuperMethod(@NotNull List<JsExpression> arguments, JsFunction initializer) {
        JsName ref = context().scope().declareName(Namer.CALLEE_NAME);
        initializer.setName(ref);
        JsInvocation call = new JsInvocation(Namer.getFunctionCallRef(Namer.superMethodNameRef(ref)));
        call.getArguments().add(JsLiteral.THIS);
        call.getArguments().addAll(arguments);
        initializerStatements.add(0, call.makeStmt());
    }

    @Nullable
    private ResolvedCall<FunctionDescriptor> getSuperCall() {
        for (JetDelegationSpecifier specifier : classDeclaration.getDelegationSpecifiers()) {
            if (specifier instanceof JetDelegatorToSuperCall) {
                JetDelegatorToSuperCall superCall = (JetDelegatorToSuperCall) specifier;
                //noinspection unchecked
                return (ResolvedCall<FunctionDescriptor>) CallUtilPackage.getResolvedCallWithAssert(superCall, bindingContext());
            }
        }
        return null;
    }

    @NotNull
    List<JsParameter> translatePrimaryConstructorParameters() {
        List<JetParameter> parameterList = getPrimaryConstructorParameters(classDeclaration);
        List<JsParameter> result = new ArrayList<JsParameter>();
        for (JetParameter jetParameter : parameterList) {
            result.add(translateParameter(jetParameter));
        }
        return result;
    }

    @NotNull
    private JsParameter translateParameter(@NotNull JetParameter jetParameter) {
        DeclarationDescriptor parameterDescriptor =
                getDescriptorForElement(bindingContext(), jetParameter);
        JsName parameterName = context().getNameForDescriptor(parameterDescriptor);
        JsParameter jsParameter = new JsParameter(parameterName);
        mayBeAddInitializerStatementForProperty(jsParameter, jetParameter);
        return jsParameter;
    }

    private void mayBeAddInitializerStatementForProperty(@NotNull JsParameter jsParameter,
            @NotNull JetParameter jetParameter) {
        PropertyDescriptor propertyDescriptor =
                getPropertyDescriptorForConstructorParameter(bindingContext(), jetParameter);
        if (propertyDescriptor == null) {
            return;
        }
        JsNameRef initialValueForProperty = jsParameter.getName().makeRef();
        addInitializerOrPropertyDefinition(initialValueForProperty, propertyDescriptor);
    }

    private void addInitializerOrPropertyDefinition(@NotNull JsNameRef initialValue, @NotNull PropertyDescriptor propertyDescriptor) {
        initializerStatements.add(InitializerUtils.generateInitializerForProperty(context(), propertyDescriptor, initialValue));
    }
}
