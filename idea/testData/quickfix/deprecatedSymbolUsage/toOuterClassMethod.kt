// "Replace with 'newFun(this)'" "true"
// ERROR: Unresolved reference: newFun

class Outer {
    inner class Inner {
        @deprecated("", ReplaceWith("newFun(this)"))
        fun oldFun() {}
    }

    fun newFun(inner: Inner) {}
}

fun foo(inner: Outer.Inner) {
    inner.<caret>oldFun()
}
