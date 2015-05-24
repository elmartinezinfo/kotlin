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

package org.jetbrains.kotlin.idea.highlighter

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.SharedPsiElementImplUtil
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.formatter.JetBlock
import org.jetbrains.kotlin.idea.kdoc.KDocReference
import org.jetbrains.kotlin.idea.references.*
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.lexer.JetTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.ThisReceiver
import org.jetbrains.kotlin.types.expressions.OperatorConventions
import org.jetbrains.kotlin.utils.Profiler
import java.util.HashSet

public class KotlinRecursiveCallLineMarkerProvider() : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement) = null

    override fun collectSlowLineMarkers(elements: MutableList<PsiElement>, result: MutableCollection<LineMarkerInfo<*>>) {
        val markedLineNumbers = HashSet<Int>()

        val profiler = Profiler.create("Recursive calls")
        profiler.start()

        profiler.printEntering()

        for (element in elements) {
            ProgressManager.checkCanceled()
            if (element is JetElement) {
                val lineNumber = element.getLineNumber()
                if (lineNumber !in markedLineNumbers && isRecursiveCall(element)) {
                    markedLineNumbers.add(lineNumber)
                    result.add(RecursiveMethodCallMarkerInfo(element))
                }
            }
        }

        profiler.end()
    }

    private fun getEnclosingFunction(element: JetElement, stopOnNonInlinedLambdas: Boolean): JetNamedFunction? {
        for (parent in element.parents(false)) {
            when (parent) {
                is JetFunctionLiteral -> if (stopOnNonInlinedLambdas && !InlineUtil.isInlinedArgument(parent, parent.analyze(), false)) return null
                is JetNamedFunction ->
                    if (stopOnNonInlinedLambdas) {
                        if (!InlineUtil.isInlinedArgument(parent, parent.analyze(), false)) return parent
                    }
                    else {
                        when (parent.getParent()) {
                            is JetBlockExpression -> return parent
                            is JetClassBody -> return parent
                            is JetFile -> return parent
                            is JetScript -> return parent
                        }
                    }
                is JetClassOrObject -> return null
            }
        }
        return null
    }

    public fun <T: JetElement> AbstractJetReference<T>.expectedResolveName(): String? {
        return when (this) {
            is JetSimpleNameReference -> {
                val element = getElement()
                    val referenceText = element.getText()
                    when (referenceText) {
                        "==" -> "equals"
                        "*" -> "times"
                        "+" -> "plus"
                        "-" -> "minus"
                        "/" -> "div"
                        "%" -> "mod"
                        ".." -> "rangeTo"
                        "++" -> "inc"
                        "--" -> "dec"

                        else -> referenceText
                    }
            }
            is JetArrayAccessReference -> "get"
            is JetForLoopInReference -> "iterator"
            else -> null
        }
    }

    private fun isRecursiveCall(element: JetElement): Boolean {
        val elementParent = element.getParent()
        val resolveName = if (element !is JetArrayAccessExpression) {
            when (elementParent) {
                is JetCallExpression, is JetBinaryExpression,
                is JetPostfixExpression, is JetPrefixExpression -> {
                    val psiReference = element.findReferenceAt(0) as? AbstractJetReference<*> ?: return false
                    if (psiReference !is JetSimpleNameReference) return false

                    psiReference.expectedResolveName()
                }

                else -> null
            }
        }
        else {
            "get"
        }

        if (resolveName == null) return false

        val enclosingFunction = getEnclosingFunction(element, false) ?: return false

        if (enclosingFunction.getName() != resolveName) return false

        // Check that there were no not-inlined lambda on the way to enclosing function
        if (enclosingFunction != getEnclosingFunction(element, true)) return false

        val bindingContext = element.analyze()
        val enclosingFunctionDescriptor = bindingContext[BindingContext.FUNCTION, enclosingFunction] ?: return false

        val call = bindingContext[BindingContext.CALL, element] ?: return false
        val resolvedCall = bindingContext[BindingContext.RESOLVED_CALL, call] ?: return false

        if (resolvedCall.getCandidateDescriptor().getOriginal() != enclosingFunctionDescriptor) return false

        fun isDifferentReceiver(receiver: ReceiverValue): Boolean {
            val receiverOwner =
                    when (receiver) {
                        is ExpressionReceiver -> {
                            val thisRef = (JetPsiUtil.deparenthesize(receiver.getExpression()) as?
                                    JetThisExpression)?.getInstanceReference() ?: return true
                            bindingContext[BindingContext.REFERENCE_TARGET, thisRef] ?: return true
                        }

                        is ThisReceiver -> receiver.getDeclarationDescriptor()
                        else -> return false
                    }

            return when (receiverOwner) {
                is SimpleFunctionDescriptor -> receiverOwner != enclosingFunctionDescriptor
                is ClassDescriptor -> receiverOwner != enclosingFunctionDescriptor.getContainingDeclaration()
                else -> throw IllegalStateException("Unexpected receiver owner: $receiverOwner")
            }
        }

        if (isDifferentReceiver(resolvedCall.getExtensionReceiver())) return false
        if (isDifferentReceiver(resolvedCall.getDispatchReceiver())) return false
        return true
    }

    private class RecursiveMethodCallMarkerInfo(callElement: JetElement)
            : LineMarkerInfo<JetElement>(
                    callElement,
                    callElement.getTextRange(),
                    AllIcons.Gutter.RecursiveMethod,
                    Pass.UPDATE_OVERRIDEN_MARKERS,
                    { "Recursive call" },
                    null,
                    GutterIconRenderer.Alignment.RIGHT
    ) {

        override fun createGutterRenderer(): GutterIconRenderer? {
            return object : LineMarkerInfo.LineMarkerGutterIconRenderer<JetElement>(this) {
                override fun getClickAction() = null // to place breakpoint on mouse click
            }
        }
    }

}

private fun PsiElement.getLineNumber(): Int {
    return PsiDocumentManager.getInstance(getProject()).getDocument(getContainingFile())!!.getLineNumber(getTextOffset())
}
