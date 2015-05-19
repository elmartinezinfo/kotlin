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

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.psi.JetNamedFunction
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.lazy.DeclarationScopeProvider
import org.jetbrains.kotlin.resolve.lazy.ForceResolveUtil
import org.jetbrains.kotlin.resolve.lazy.LazyDeclarationResolver
import org.jetbrains.kotlin.storage.MemoizedFunctionToNotNull
import org.jetbrains.kotlin.storage.StorageManager
import javax.annotation.PostConstruct
import javax.inject.Inject

class BodyResolveTaskManager {
    public var declarationScopeProvider: DeclarationScopeProvider? = null @Inject set
    public var lazyDeclarationResolver: LazyDeclarationResolver? = null @Inject set
    public var bodyResolver: BodyResolver? = null @Inject set
    public var storageManager: StorageManager? = null @Inject set
    public var trace: BindingTrace? = null @Inject set

    private var resolveBodyCache: MemoizedFunctionToNotNull<JetNamedFunction, BindingTrace>? = null

    @PostConstruct
    public fun init() {
        resolveBodyCache = storageManager!!.createMemoizedFunction { namedFunction: JetNamedFunction ->
            val safeTrace = DelegatingBindingTrace(trace!!.getBindingContext(), "trace to resolve a member scope of expression", namedFunction)

            val scope = declarationScopeProvider!!.getResolutionScopeForDeclaration(namedFunction)
            val functionDescriptor = lazyDeclarationResolver!!.resolveToDescriptor(namedFunction) as FunctionDescriptor
            ForceResolveUtil.forceResolveAllContents(functionDescriptor)

            bodyResolver!!.resolveFunctionBody(DataFlowInfo.EMPTY, safeTrace, namedFunction, functionDescriptor, scope)

            safeTrace
        }
    }

    public fun resolveBody(namedFunction: JetNamedFunction): BindingTrace =
             resolveBodyCache!!.invoke(namedFunction)
}
