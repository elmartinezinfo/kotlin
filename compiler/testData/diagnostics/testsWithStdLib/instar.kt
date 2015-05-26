// Reproduces exception in TypeResolver.kt: EA-66870

import java.util.ArrayList

abstract class J {
    public abstract fun <T : Collection<S>, S : List<<!UNSUPPORTED_STAR_PROJECTION!>in<!> *>> foo(x: T)
    fun bar() {
        val s = ArrayList<ArrayList<Int>>()
        foo(s)
    }
}