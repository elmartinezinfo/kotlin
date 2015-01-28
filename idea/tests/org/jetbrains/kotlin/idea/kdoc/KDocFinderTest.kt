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

package org.jetbrains.kotlin.idea.kdoc

import org.jetbrains.kotlin.idea.PluginTestCaseBase
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.junit.Assert
import org.jetbrains.kotlin.descriptors.ClassDescriptor

public class KDocFinderTest() : LightPlatformCodeInsightFixtureTestCase() {
    override fun getTestDataPath(): String {
        return PluginTestCaseBase.getTestDataPathBase() + "/kdoc/finder/"
    }

    public fun testConstructor() {
        myFixture.configureByFile(getTestName(false) + ".kt")
        val declaration = (myFixture.getFile() as JetFile).getDeclarations()[0]
        val descriptor = declaration.resolveToDescriptor() as ClassDescriptor
        val constructorDescriptor = descriptor.getUnsubstitutedPrimaryConstructor()
        val doc = KDocFinder.findKDoc(constructorDescriptor)
        Assert.assertEquals("Doc for constructor of class C.", doc!!.getContent())
    }
}