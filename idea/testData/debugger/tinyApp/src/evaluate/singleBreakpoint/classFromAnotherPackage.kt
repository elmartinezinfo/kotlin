package classFromAnotherPackage

import forTests.MyJavaClass

fun main(args: Array<String>) {
    //Breakpoint!
    args.size
}

// EXPRESSION: MyJavaClass()
// RESULT: instance of forTests.MyJavaClass(id=ID): LforTests/MyJavaClass;

// EXPRESSION: stepping.MyJavaClass()
// RESULT: instance of forTests.MyJavaClass(id=ID): LforTests/MyJavaClass;
