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

package org.jetbrains.kotlin.psi.stubs.impl

import com.intellij.psi.stubs.StubElement
import com.intellij.util.io.StringRef
import org.jetbrains.kotlin.psi.JetParameter
import org.jetbrains.kotlin.psi.stubs.KotlinParameterStub
import org.jetbrains.kotlin.psi.stubs.elements.JetStubElementTypes
import org.jetbrains.kotlin.name.FqName
import com.intellij.psi.PsiElement

public class KotlinParameterStubImpl(
        parent: StubElement<out PsiElement>?,
        private val fqName: StringRef?,
        private val name: StringRef?,
        private val isMutable: Boolean,
        private val hasValOrVarNode: Boolean,
        private val hasDefaultValue: Boolean
) : KotlinStubBaseImpl<JetParameter>(parent, JetStubElementTypes.VALUE_PARAMETER), KotlinParameterStub {
    override fun getName(): String? {
        return StringRef.toString(name)
    }

    override fun getFqName(): FqName? {
        return if (fqName != null) FqName(fqName.getString()) else null
    }

    override fun isMutable() = isMutable
    override fun hasValOrVar() = hasValOrVarNode
    override fun hasDefaultValue() = hasDefaultValue
}
