package foo

// CHECK_NOT_CALLED: test

class A

inline fun test<reified T>(): String {
    val a: Any = A()

    return if (a is T) "A" else "Unknown"
}

fun box(): String {
    assertEquals("A", test<A>())
    assertEquals("Unknown", test<String>())

    return "OK"
}