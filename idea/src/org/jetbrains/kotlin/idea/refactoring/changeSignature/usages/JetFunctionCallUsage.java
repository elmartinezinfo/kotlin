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

package org.jetbrains.kotlin.idea.refactoring.changeSignature.usages;

import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.TIntArrayList;
import gnu.trove.TIntProcedure;
import kotlin.KotlinPackage;
import kotlin.Pair;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.*;
import org.jetbrains.kotlin.idea.caches.resolve.ResolvePackage;
import org.jetbrains.kotlin.idea.core.CorePackage;
import org.jetbrains.kotlin.idea.refactoring.changeSignature.JetChangeInfo;
import org.jetbrains.kotlin.idea.refactoring.changeSignature.JetParameterInfo;
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.ExtractionEnginePackage;
import org.jetbrains.kotlin.idea.refactoring.introduce.introduceVariable.KotlinIntroduceVariableHandler;
import org.jetbrains.kotlin.idea.util.ShortenReferences;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.calls.callUtil.CallUtilPackage;
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall;
import org.jetbrains.kotlin.resolve.calls.model.ResolvedValueArgument;
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.kotlin.resolve.scopes.receivers.ThisReceiver;
import org.jetbrains.kotlin.types.JetType;
import org.jetbrains.kotlin.types.checker.JetTypeChecker;
import org.jetbrains.kotlin.types.expressions.OperatorConventions;

import java.util.*;

import static org.jetbrains.kotlin.psi.PsiPackage.JetPsiFactory;

public class JetFunctionCallUsage extends JetUsageInfo<JetCallElement> {
    private static final Comparator<Pair<JetElement, JetElement>>
            REVERSED_TEXT_OFFSET_COMPARATOR = new Comparator<Pair<JetElement, JetElement>>() {
        @Override
        public int compare(
                @NotNull Pair<JetElement, JetElement> p1,
                @NotNull Pair<JetElement, JetElement> p2
        ) {
            int offset1 = p1.getFirst().getTextRange().getStartOffset();
            int offset2 = p2.getFirst().getTextRange().getStartOffset();
            return offset1 < offset2 ? 1
                                     : offset1 > offset2 ? -1
                                                         : 0;
        }
    };

    private static final Function1<JetElement, ShortenReferences.Options>
            SHORTEN_ARGUMENTS_OPTIONS = new Function1<JetElement, ShortenReferences.Options>() {
        @Override
        public ShortenReferences.Options invoke(JetElement element) {
            return new ShortenReferences.Options(true, true);
        }
    };

    private final JetFunctionDefinitionUsage<?> callee;
    private final BindingContext context;
    private final ResolvedCall<? extends CallableDescriptor> resolvedCall;

    public JetFunctionCallUsage(@NotNull JetCallElement element, JetFunctionDefinitionUsage callee) {
        super(element);
        this.callee = callee;
        this.context = ResolvePackage.analyze(element, BodyResolveMode.FULL);
        this.resolvedCall = CallUtilPackage.getResolvedCall(element, context);
    }

    @Override
    public boolean processUsage(JetChangeInfo changeInfo, JetCallElement element) {
        if (changeInfo.isNameChanged()) {
            JetExpression callee = element.getCalleeExpression();

            if (callee instanceof JetSimpleNameExpression) {
                callee.replace(JetPsiFactory(getProject()).createSimpleName(changeInfo.getNewName()));
            }
        }

        if (element.getValueArgumentList() != null) {
            if (changeInfo.isParameterSetOrOrderChanged()) {
                updateArgumentsAndReceiver(changeInfo, element);
            }
            else {
                changeArgumentNames(changeInfo, element);
            }
        }

        if (changeInfo.getNewParametersCount() == 0
            && element instanceof JetDelegatorToSuperCall) {
            JetEnumEntry enumEntry = PsiTreeUtil.getParentOfType(element, JetEnumEntry.class, true);
            if (enumEntry != null && enumEntry.getInitializerList() == element.getParent()) {
                PsiElement colon = enumEntry.getColon();
                JetInitializerList initializerList = enumEntry.getInitializerList();
                enumEntry.deleteChildRange(colon != null ? colon : initializerList, initializerList);
            }
        }

        return true;
    }

    @Nullable
    private JetExpression getReceiverExpressionIfMatched(
            @NotNull ReceiverValue receiverValue,
            @NotNull DeclarationDescriptor originalDescriptor,
            @NotNull JetPsiFactory psiFactory
    ) {
        if (!receiverValue.exists()) return null;

        // Replace descriptor of extension function/property with descriptor of its receiver
        // to simplify checking against receiver value in the corresponding resolved call
        if (originalDescriptor instanceof CallableDescriptor && !(originalDescriptor instanceof ReceiverParameterDescriptor)) {
            ReceiverParameterDescriptor receiverParameter = ((CallableDescriptor) originalDescriptor).getExtensionReceiverParameter();
            if (receiverParameter == null) return null;
            originalDescriptor = receiverParameter;
        }

        boolean currentIsExtension = resolvedCall.getExtensionReceiver() == receiverValue;
        boolean originalIsExtension =
                originalDescriptor instanceof ReceiverParameterDescriptor &&
                ((ReceiverParameterDescriptor) originalDescriptor).getValue() instanceof ExtensionReceiver;
        if (currentIsExtension != originalIsExtension) return null;

        JetType originalType = originalDescriptor instanceof ReceiverParameterDescriptor
                               ? ((ReceiverParameterDescriptor) originalDescriptor).getType()
                               : originalDescriptor instanceof ClassDescriptor
                                 ? ((ClassDescriptor) originalDescriptor).getDefaultType()
                                 : null;
        if (originalType == null || !JetTypeChecker.DEFAULT.isSubtypeOf(receiverValue.getType(), originalType)) return null;

        return getReceiverExpression(receiverValue, psiFactory);
    }

    private boolean needSeparateVariable(@NotNull PsiElement element) {
        if (element instanceof JetConstantExpression) return false;
        if (element instanceof JetThisExpression) return false;
        if (element instanceof JetSimpleNameExpression) return false;

        //noinspection SuspiciousMethodCalls
        if (element instanceof JetBinaryExpression
            && OperatorConventions.ASSIGNMENT_OPERATIONS.containsKey(((JetBinaryExpression) element).getOperationToken())) return true;

        //noinspection SuspiciousMethodCalls
        if (element instanceof JetUnaryExpression
            && OperatorConventions.INCREMENT_OPERATIONS.contains(((JetUnaryExpression) element).getOperationToken())) return true;

        if (element instanceof JetCallExpression) {
            ResolvedCall<? extends CallableDescriptor> resolvedCall =
                    CallUtilPackage.getResolvedCall((JetCallExpression) element, context);
            return resolvedCall != null && resolvedCall.getResultingDescriptor() instanceof ConstructorDescriptor;
        }

        for (PsiElement child : element.getChildren()) {
            if (needSeparateVariable(child)) return true;
        }
        return false;
    }

    @NotNull
    private JetExpression substituteReferences(
            @NotNull JetExpression expression,
            @NotNull Map<PsiReference, DeclarationDescriptor> referenceMap,
            @NotNull final JetPsiFactory psiFactory
    ) {
        if (referenceMap.isEmpty() || resolvedCall == null) return expression;

        JetExpression newExpression = (JetExpression) expression.copy();

        Map<JetSimpleNameExpression, JetSimpleNameExpression> nameCounterpartMap =
                ExtractionEnginePackage.createNameCounterpartMap(expression, newExpression);

        Map<ValueParameterDescriptor, ResolvedValueArgument> valueArguments = resolvedCall.getValueArguments();

        List<Pair<JetElement, JetElement>> replacements = new ArrayList<Pair<JetElement, JetElement>>();
        for (Map.Entry<PsiReference, DeclarationDescriptor> e : referenceMap.entrySet()) {
            DeclarationDescriptor descriptor = e.getValue();

            JetExpression argumentExpression;
            boolean addReceiver = false;
            if (descriptor instanceof ValueParameterDescriptor) { // Ordinary parameter
                // Find corresponding parameter in the current function (may differ from 'descriptor' if original function is part of override hierarchy)
                ValueParameterDescriptor parameterDescriptor =
                        resolvedCall.getResultingDescriptor().getValueParameters().get(((ValueParameterDescriptor) descriptor).getIndex());

                ResolvedValueArgument resolvedValueArgument = valueArguments.get(parameterDescriptor);
                if (!(resolvedValueArgument instanceof ExpressionValueArgument)) continue;

                ValueArgument argument = ((ExpressionValueArgument) resolvedValueArgument).getValueArgument();
                if (argument == null) continue;

                argumentExpression = argument.getArgumentExpression();
            }
            else {
                addReceiver = !(descriptor instanceof ReceiverParameterDescriptor);
                argumentExpression = getReceiverExpressionIfMatched(resolvedCall.getExtensionReceiver(), descriptor, psiFactory);
                if (argumentExpression == null) {
                    argumentExpression = getReceiverExpressionIfMatched(resolvedCall.getDispatchReceiver(), descriptor, psiFactory);
                }
            }
            if (argumentExpression == null) continue;

            //noinspection unchecked
            if (needSeparateVariable(argumentExpression) &&
                PsiTreeUtil.getNonStrictParentOfType(getElement(),
                                                     JetConstructorDelegationCall.class,
                                                     JetDelegationSpecifier.class,
                                                     JetParameter.class) == null) {

                final Ref<JetExpression> newExpressionRef = new Ref<JetExpression>();
                KotlinIntroduceVariableHandler.doRefactoring(
                        getProject(),
                        null,
                        argumentExpression,
                        Collections.singletonList(argumentExpression),
                        new Function1<JetProperty, Unit>() {
                            @Override
                            public Unit invoke(JetProperty property) {
                                //noinspection ConstantConditions
                                newExpressionRef.set(psiFactory.createExpression(property.getName()));
                                return null;
                            }
                        }
                );
                argumentExpression = newExpressionRef.get();
            }

            //noinspection SuspiciousMethodCalls
            JetExpression expressionToReplace = nameCounterpartMap.get(e.getKey().getElement());
            if (expressionToReplace == null) continue;
            PsiElement parent = expressionToReplace.getParent();
            if (parent instanceof JetThisExpression) {
                expressionToReplace = (JetThisExpression) parent;
            }

            if (addReceiver) {
                JetCallExpression callExpression = PsiTreeUtil.getParentOfType(expressionToReplace, JetCallExpression.class, true);
                if (callExpression != null && PsiTreeUtil.isAncestor(callExpression.getCalleeExpression(), expressionToReplace, false)) {
                    expressionToReplace = callExpression;
                } else {
                    // Do not substitute operation references in infix/prefix calls
                    if (parent instanceof JetOperationExpression
                        && ((JetOperationExpression) parent).getOperationReference() == expressionToReplace) {
                        continue;
                    }
                }
                replacements.add(
                        new Pair<JetElement, JetElement>(
                                expressionToReplace,
                                psiFactory.createExpression(argumentExpression.getText() + "." + expressionToReplace.getText())
                        )
                );
            }
            else {
                replacements.add(new Pair<JetElement, JetElement>(expressionToReplace, argumentExpression));
            }
        }

        // Sort by descending offset so that call arguments are replaced before call itself
        ContainerUtil.sort(replacements, REVERSED_TEXT_OFFSET_COMPARATOR);
        for (Pair<JetElement, JetElement> replacement : replacements) {
            replacement.getFirst().replace(replacement.getSecond());
        }

        return newExpression;
    }

    private void updateArgumentsAndReceiver(JetChangeInfo changeInfo, JetCallElement element) {
        JetValueArgumentList arguments = element.getValueArgumentList();
        assert arguments != null : "Argument list is expected: " + element.getText();
        List<? extends ValueArgument> oldArguments = element.getValueArguments();

        boolean isNamedCall = oldArguments.size() > 1 && oldArguments.get(0).getArgumentName() != null;
        StringBuilder parametersBuilder = new StringBuilder("(");
        boolean isFirst = true;

        TIntArrayList indicesOfArgumentsWithDefaultValues = new TIntArrayList();

        JetPsiFactory psiFactory = new JetPsiFactory(element.getProject());

        List<JetParameterInfo> newSignatureParameters = changeInfo.getNonReceiverParameters();
        for (JetParameterInfo parameterInfo : newSignatureParameters) {
            if (isFirst) {
                isFirst = false;
            }
            else {
                parametersBuilder.append(',');
            }

            JetExpression defaultValueForCall = parameterInfo.getDefaultValueForCall();
            String defaultValueText = defaultValueForCall != null
                                      ? substituteReferences(defaultValueForCall,
                                                             parameterInfo.getDefaultValueParameterReferences(),
                                                             psiFactory).getText()
                                      : "";

            if (isNamedCall) {
                String newName = parameterInfo.getInheritedName(callee);
                parametersBuilder.append(newName).append('=');
            }

            parametersBuilder.append(defaultValueText.isEmpty() ? '0' : defaultValueText);
        }

        parametersBuilder.append(')');
        JetValueArgumentList newArgumentList = JetPsiFactory(getProject()).createCallArguments(parametersBuilder.toString());

        Map<Integer, ? extends ValueArgument> argumentMap = getParamIndexToArgumentMap(changeInfo, oldArguments);

        JetParameterInfo newReceiverInfo = changeInfo.getReceiverParameterInfo();
        JetParameterInfo originalReceiverInfo = changeInfo.getMethodDescriptor().getReceiver();

        ReceiverValue extensionReceiver = resolvedCall != null ? resolvedCall.getExtensionReceiver() : ReceiverValue.NO_RECEIVER;
        ReceiverValue dispatchReceiver = resolvedCall != null ? resolvedCall.getDispatchReceiver() : ReceiverValue.NO_RECEIVER;

        PsiElement elementToReplace = element;
        PsiElement parent = element.getParent();
        if (parent instanceof JetQualifiedExpression && ((JetQualifiedExpression) parent).getSelectorExpression() == element) {
            elementToReplace = parent;
        }

        // Do not add extension receiver to calls with explicit dispatch receiver
        if (newReceiverInfo != null
            && elementToReplace instanceof JetQualifiedExpression
            && dispatchReceiver instanceof ExpressionReceiver) return;

        List<JetValueArgument> newArguments = newArgumentList.getArguments();
        int actualIndex = 0;
        for (int i = 0; i < newArguments.size(); i++) {
            JetValueArgument newArgument = newArguments.get(i);
            JetParameterInfo parameterInfo = newSignatureParameters.get(i);
            if (parameterInfo == originalReceiverInfo) {
                JetExpression receiverExpression = getReceiverExpression(extensionReceiver, psiFactory);
                if (receiverExpression != null) {
                    newArgument.replace(receiverExpression);
                }
                actualIndex++;
                continue;
            }

            ValueArgument oldArgument = argumentMap.get(parameterInfo.getOldIndex());

            if (oldArgument != null) {
                JetValueArgumentName argumentName = oldArgument.getArgumentName();
                JetSimpleNameExpression argumentNameExpression = argumentName != null ? argumentName.getReferenceExpression() : null;
                changeArgumentName(argumentNameExpression, parameterInfo);
                //noinspection ConstantConditions
                newArgument.replace(oldArgument instanceof JetFunctionLiteralArgument
                                    ? psiFactory.createArgument(oldArgument.getArgumentExpression(), null, false)
                                    : oldArgument.asElement());
            }
            // TODO: process default arguments in the middle
            else if (parameterInfo.getDefaultValueForCall() == null) {
                if (parameterInfo.getDefaultValueForParameter() != null) {
                    newArgumentList.removeArgument(newArgument);
                }
                else {
                    newArgument.delete(); // keep space between commas
                }
            }
            else {
                indicesOfArgumentsWithDefaultValues.add(actualIndex++);
            }
        }

        List<JetFunctionLiteralArgument> lambdaArguments = element.getFunctionLiteralArguments();
        if (!lambdaArguments.isEmpty()) {
            element.deleteChildRange(KotlinPackage.first(lambdaArguments), KotlinPackage.last(lambdaArguments));
        }

        //TODO: this is not correct!
        JetValueArgument lastArgument = KotlinPackage.lastOrNull(newArgumentList.getArguments());
        boolean hasTrailingLambdaInArgumentListAfter = lastArgument != null && PsiPackage.unpackFunctionLiteral(lastArgument.getArgumentExpression()) != null;

        arguments = (JetValueArgumentList) arguments.replace(newArgumentList);

        final List<JetElement> argumentsToShorten = new ArrayList<JetElement>(indicesOfArgumentsWithDefaultValues.size());
        final List<JetValueArgument> argumentList = arguments.getArguments();
        indicesOfArgumentsWithDefaultValues.forEach(
                new TIntProcedure() {
                    @Override
                    public boolean execute(int i) {
                        argumentsToShorten.add(argumentList.get(i));
                        return true;
                    }
                }
        );
        new ShortenReferences(SHORTEN_ARGUMENTS_OPTIONS).process(argumentsToShorten);

        JetElement newElement = element;
        if (newReceiverInfo != originalReceiverInfo) {
            PsiElement replacingElement = element;
            if (newReceiverInfo != null) {
                ValueArgument receiverArgument = argumentMap.get(newReceiverInfo.getOldIndex());
                JetExpression extensionReceiverExpression = receiverArgument != null ? receiverArgument.getArgumentExpression() : null;
                JetExpression defaultValueForCall = newReceiverInfo.getDefaultValueForCall();
                String receiverText = extensionReceiverExpression != null
                                      ? extensionReceiverExpression.getText()
                                      : defaultValueForCall != null ? defaultValueForCall.getText() : "";
                if (receiverText.isEmpty()) {
                    receiverText = "_";
                }

                replacingElement = psiFactory.createExpression(receiverText + "." + element.getText());
            }

            newElement = (JetElement) elementToReplace.replace(replacingElement);
        }

        if (hasTrailingLambdaInArgumentListAfter) {
            JetCallExpression newCallExpression =
                    (JetCallExpression) (newElement instanceof JetQualifiedExpression
                                      ? ((JetQualifiedExpression) newElement).getSelectorExpression()
                                      : newElement);
            CorePackage.moveFunctionLiteralOutsideParentheses(newCallExpression);
        }
    }

    @Nullable
    private static JetExpression getReceiverExpression(@NotNull ReceiverValue receiver, @NotNull JetPsiFactory psiFactory) {
        if (receiver instanceof ExpressionReceiver) {
            return ((ExpressionReceiver) receiver).getExpression();
        }
        else if (receiver instanceof ThisReceiver) {
            DeclarationDescriptor descriptor = ((ThisReceiver) receiver).getDeclarationDescriptor();
            String thisText = descriptor instanceof ClassDescriptor ? "this@" + descriptor.getName().asString() : "this";
            return psiFactory.createExpression(thisText);
        }
        return null;
    }

    private static Map<Integer, ? extends ValueArgument> getParamIndexToArgumentMap(JetChangeInfo changeInfo, List<? extends ValueArgument> oldArguments) {
        Map<Integer, ValueArgument> argumentMap = new HashMap<Integer, ValueArgument>();

        for (int i = 0; i < oldArguments.size(); i++) {
            ValueArgument argument = oldArguments.get(i);
            JetValueArgumentName argumentName = argument.getArgumentName();
            JetSimpleNameExpression argumentNameExpression = argumentName != null ? argumentName.getReferenceExpression() : null;
            String oldParameterName = argumentNameExpression != null ? argumentNameExpression.getReferencedName() : null;

            if (oldParameterName != null) {
                Integer oldParameterIndex = changeInfo.getOldParameterIndex(oldParameterName);

                if (oldParameterIndex != null)
                    argumentMap.put(oldParameterIndex, argument);
            }
            else
                argumentMap.put(i, argument);
        }

        return argumentMap;
    }

    private void changeArgumentNames(JetChangeInfo changeInfo, JetCallElement element) {
        for (ValueArgument argument : element.getValueArguments()) {
            JetValueArgumentName argumentName = argument.getArgumentName();
            JetSimpleNameExpression argumentNameExpression = argumentName != null ? argumentName.getReferenceExpression() : null;

            if (argumentNameExpression != null) {
                Integer oldParameterIndex = changeInfo.getOldParameterIndex(argumentNameExpression.getReferencedName());

                if (oldParameterIndex != null) {
                    JetParameterInfo parameterInfo = changeInfo.getNewParameters()[oldParameterIndex];
                    changeArgumentName(argumentNameExpression, parameterInfo);
                }
            }
        }
    }

    private void changeArgumentName(JetSimpleNameExpression argumentNameExpression, JetParameterInfo parameterInfo) {
        PsiElement identifier = argumentNameExpression != null ? argumentNameExpression.getIdentifier() : null;

        if (identifier != null) {
            String newName = parameterInfo.getInheritedName(callee);
            identifier.replace(JetPsiFactory(getProject()).createIdentifier(newName));
        }
    }
}
