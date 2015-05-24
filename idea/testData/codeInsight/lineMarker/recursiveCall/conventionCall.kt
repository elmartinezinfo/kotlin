fun Any.get(a: Int) {
    if (a > 0) {
        <lineMarker>this[a - 1]</lineMarker>
    }
}

class A {
    override fun <lineMarker descr="Overrides function in 'Any'"></lineMarker>equals(other: Any?): Boolean {
        this <lineMarker descr="Recursive call">==</lineMarker> other
        return true
    }

    fun inc(): A {
        return this<lineMarker descr="Recursive call">++</lineMarker>
    }
}