// PSI_ELEMENT: com.intellij.psi.PsiClass
// OPTIONS: usages
// FIND_BY_REF
// FIND_BY_NAVIGATION_ELEMENT
package usages

import library.Foo

fun test() {
    val foo: <caret>Foo
}