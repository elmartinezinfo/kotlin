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

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.analyzer.*
import org.jetbrains.kotlin.context.*
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.di.InjectorForBodyResolve
import org.jetbrains.kotlin.idea.project.ResolveSessionForBodies
import org.jetbrains.kotlin.idea.project.TargetPlatform
import org.jetbrains.kotlin.idea.project.TargetPlatformDetector
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.impl.JavaClassImpl
import org.jetbrains.kotlin.psi.JetElement
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.psi.JetNamedFunction
import org.jetbrains.kotlin.resolve.*
import org.jetbrains.kotlin.resolve.calls.smartcasts.DataFlowInfo
import org.jetbrains.kotlin.resolve.jvm.JvmPlatformParameters
import org.jetbrains.kotlin.resolve.lazy.*
import org.jetbrains.kotlin.storage.ExceptionTracker
import org.jetbrains.kotlin.storage.MemoizedFunctionToNotNull
import org.jetbrains.kotlin.types.expressions.LocalLazyDeclarationResolver
import org.jetbrains.kotlin.utils.Profiler
import org.jetbrains.kotlin.utils.keysToMap
import kotlin.properties.Delegates

fun createModuleResolverProvider(
        project: Project,
        globalContext: GlobalContextImpl,
        analyzerFacade: AnalyzerFacade<out ResolverForModule, JvmPlatformParameters>,
        syntheticFiles: Collection<JetFile>,
        delegateProvider: ModuleResolverProvider,
        moduleFilter: (IdeaModuleInfo) -> Boolean
): ModuleResolverProvider {

    val allModuleInfos = collectAllModuleInfosFromIdeaModel(project).toHashSet()

    val syntheticFilesByModule = syntheticFiles.groupBy { it.getModuleInfo() }
    val syntheticFilesModules = syntheticFilesByModule.keySet()
    allModuleInfos.addAll(syntheticFilesModules)

    val modulesToCreateResolversFor = allModuleInfos.filter(moduleFilter)

    fun createResolverForProject(): ResolverForProject<IdeaModuleInfo, ResolverForModule> {
        val modulesContent = { module: IdeaModuleInfo ->
            ModuleContent(syntheticFilesByModule[module] ?: listOf(), module.contentScope())
        }

        val jvmPlatformParameters = JvmPlatformParameters {
            javaClass: JavaClass ->
            val psiClass = (javaClass as JavaClassImpl).getPsi()
            psiClass.getModuleInfo()
        }

        val resolverForProject = analyzerFacade.setupResolverForProject(
                globalContext.withProject(project), modulesToCreateResolversFor, modulesContent,
                jvmPlatformParameters, delegateProvider.resolverForProject
        )
        return resolverForProject
    }

    val resolverForProject = createResolverForProject()

    val moduleToBodiesResolveSession = modulesToCreateResolversFor.keysToMap { module ->
        val resolveSession = resolverForProject.resolverForModule(module).lazyResolveSession
        ResolveSessionForBodies(project, resolveSession, IDEBodyResolveTaskManager(globalContext, project, resolveSession))
    }
    return ModuleResolverProviderImpl(
            resolverForProject,
            moduleToBodiesResolveSession,
            globalContext,
            delegateProvider
    )
}

class IDEBodyResolveTaskManager(val globalContext: GlobalContext, val project: Project, val resolveSession: ResolveSession): BodyResolveTaskManager() {
    private val bodyResolve = InjectorForBodyResolve(
            globalContext.withProject(project).withModule(resolveSession.getModuleDescriptor()),
            resolveSession.getTrace(), TargetPlatform.JVM.getAdditionalCheckerProvider(), StatementFilter.NONE
    ).getBodyResolver()

    val bodyResolveCache = CachedValuesManager.getManager(project).createCachedValue<MemoizedFunctionToNotNull<JetNamedFunction, BodyResolveResult>>(
            CachedValueProvider {
                val manager = resolveSession.getStorageManager()
                val functionsCacheFunction = manager.createMemoizedFunction<JetNamedFunction, BodyResolveResult> { function ->
                    doResolveFunctionBody(function)
                }

                CachedValueProvider.Result.create<MemoizedFunctionToNotNull<JetNamedFunction, BodyResolveResult>>(
                        functionsCacheFunction, PsiModificationTracker.MODIFICATION_COUNT, resolveSession.getExceptionTracker())
            }, false)

    override fun resolveFunctionBody(function: JetNamedFunction): BodyResolveResult {
        return bodyResolveCache.getValue().invoke(function)
    }

    override fun hasElementAdditionalResolveCached(function: JetNamedFunction): Boolean {
        if (!bodyResolveCache.hasUpToDateValue()) return false
        return bodyResolveCache.getValue().isComputed(function)
    }

    private fun doResolveFunctionBody(function: JetNamedFunction): BodyResolveResult {
//        val profiler = Profiler.create("-- IDE -- ${Thread.currentThread().getName()} ${function.getName()} ${function.hashCode()} $this " +
//                                       "${PsiManager.getInstance(function.getProject()).getModificationTracker().getModificationCount()}").start()

        val scope = resolveSession.getScopeProvider().getResolutionScopeForDeclaration(function)
        val functionDescriptor = resolveSession.resolveToDescriptor(function) as FunctionDescriptor
        val dataFlowInfo = DataFlowInfo.EMPTY

        val bodyResolveResult = BodyResolveTaskManager.resolveFunctionBody(
                function, bodyResolve, BodyResolveContext(dataFlowInfo, resolveSession.getTrace(), functionDescriptor, scope))

//        profiler.end()

        return bodyResolveResult
    }
}

private fun collectAllModuleInfosFromIdeaModel(project: Project): List<IdeaModuleInfo> {
    val ideaModules = ModuleManager.getInstance(project).getModules().toList()
    val modulesSourcesInfos = ideaModules.flatMap { listOf(it.productionSourceInfo(), it.testSourceInfo()) }

    //TODO: (module refactoring) include libraries that are not among dependencies of any module
    val ideaLibraries = ideaModules.flatMap {
        ModuleRootManager.getInstance(it).getOrderEntries().filterIsInstance<LibraryOrderEntry>().map {
            it.getLibrary()
        }
    }.filterNotNull().toSet()

    val librariesInfos = ideaLibraries.map { LibraryInfo(project, it) }

    val ideaSdks = ideaModules.flatMap {
        ModuleRootManager.getInstance(it).getOrderEntries().filterIsInstance<JdkOrderEntry>().map {
            it.getJdk()
        }
    }.filterNotNull().toSet()

    val sdksInfos = ideaSdks.map { SdkInfo(project, it) }

    val collectAllModuleInfos = modulesSourcesInfos + librariesInfos + sdksInfos
    return collectAllModuleInfos
}

trait ModuleResolverProvider {
    val exceptionTracker: ExceptionTracker
    fun resolverByModule(module: IdeaModuleInfo): ResolverForModule = resolverForProject.resolverForModule(module)
    fun resolveSessionForBodiesByModule(module: IdeaModuleInfo): ResolveSessionForBodies
    val resolverForProject: ResolverForProject<IdeaModuleInfo, ResolverForModule>
}

object EmptyModuleResolverProvider: ModuleResolverProvider {
    override val exceptionTracker: ExceptionTracker
        get() = throw IllegalStateException("Should not be called")

    override fun resolveSessionForBodiesByModule(module: IdeaModuleInfo): ResolveSessionForBodies
            = throw IllegalStateException("Trying to obtain resolve session for unknown $module")

    override val resolverForProject: ResolverForProject<IdeaModuleInfo, ResolverForModule> = EmptyResolverForProject()

}

class ModuleResolverProviderImpl(
        override val resolverForProject: ResolverForProject<IdeaModuleInfo, ResolverForModule>,
        private val bodiesResolveByModule: Map<IdeaModuleInfo, ResolveSessionForBodies>,
        val globalContext: GlobalContextImpl,
        val delegateProvider: ModuleResolverProvider = EmptyModuleResolverProvider
): ModuleResolverProvider {
    override val exceptionTracker: ExceptionTracker = globalContext.exceptionTracker

    override fun resolveSessionForBodiesByModule(module: IdeaModuleInfo): ResolveSessionForBodies =
            bodiesResolveByModule[module] ?:
            delegateProvider.resolveSessionForBodiesByModule(module)
}
