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

package org.jetbrains.kotlin.js.resolve.diagnostics;

import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0;
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory1;
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory2;
import org.jetbrains.kotlin.diagnostics.Errors;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.types.JetType;

import static org.jetbrains.kotlin.diagnostics.PositioningStrategies.*;
import static org.jetbrains.kotlin.diagnostics.Severity.ERROR;
import static org.jetbrains.kotlin.diagnostics.Severity.WARNING;

public interface ErrorsJs {
    DiagnosticFactory1<JetElement, JetType> NATIVE_ANNOTATIONS_ALLOWED_ONLY_ON_MEMBER_OR_EXTENSION_FUN = DiagnosticFactory1.create(ERROR, DECLARATION_SIGNATURE_OR_DEFAULT);
    DiagnosticFactory1<JetElement, String> NATIVE_INDEXER_KEY_SHOULD_BE_STRING_OR_NUMBER = DiagnosticFactory1.create(ERROR, DECLARATION_SIGNATURE_OR_DEFAULT);
    DiagnosticFactory1<JetElement, String> NATIVE_INDEXER_CAN_NOT_HAVE_DEFAULT_ARGUMENTS = DiagnosticFactory1.create(ERROR, DECLARATION_SIGNATURE_OR_DEFAULT);
    DiagnosticFactory0<JetDeclaration> NATIVE_GETTER_RETURN_TYPE_SHOULD_BE_NULLABLE = DiagnosticFactory0.create(ERROR, DECLARATION_RETURN_TYPE);
    DiagnosticFactory0<JetDeclaration> NATIVE_SETTER_WRONG_RETURN_TYPE = DiagnosticFactory0.create(ERROR, DECLARATION_RETURN_TYPE);
    DiagnosticFactory2<JetElement, Integer, String> NATIVE_INDEXER_WRONG_PARAMETER_COUNT = DiagnosticFactory2.create(ERROR, DECLARATION_SIGNATURE_OR_DEFAULT);
    DiagnosticFactory1<JetExpression, JsCallData> JSCODE_ERROR = DiagnosticFactory1.create(ERROR, JsCodePositioningStrategy.INSTANCE$);
    DiagnosticFactory1<JetExpression, JsCallData> JSCODE_WARNING = DiagnosticFactory1.create(WARNING, JsCodePositioningStrategy.INSTANCE$);
    DiagnosticFactory0<JetExpression> JSCODE_ARGUMENT_SHOULD_BE_CONSTANT = DiagnosticFactory0.create(ERROR, DEFAULT);
    DiagnosticFactory1<JetElement, JetElement> NOT_SUPPORTED = DiagnosticFactory1.create(ERROR, DEFAULT);
    DiagnosticFactory1<JetElement, JetElement> REFERENCE_TO_BUILTIN_MEMBERS_NOT_SUPPORTED = DiagnosticFactory1.create(ERROR, DEFAULT);
    DiagnosticFactory1<JetNamedDeclaration, String> NON_TOPLEVEL_CLASS_DECLARATION = DiagnosticFactory1.create(ERROR, DECLARATION_NAME);
    DiagnosticFactory0<JetNamedDeclaration> SECONDARY_CONSTRUCTOR = DiagnosticFactory0.create(ERROR, DECLARATION_SIGNATURE_OR_DEFAULT);
    DiagnosticFactory0<JetExpression> JSCODE_NO_JAVASCRIPT_PRODUCED = DiagnosticFactory0.create(ERROR, DEFAULT);
    DiagnosticFactory0<JetCallExpression> COULD_NOT_INLINE_FROM_LIBRARY = DiagnosticFactory0.create(WARNING, DEFAULT);

    @SuppressWarnings("UnusedDeclaration")
    Object _initializer = new Object() {
        {
            Errors.Initializer.initializeFactoryNames(ErrorsJs.class);
        }
    };
}
