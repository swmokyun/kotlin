// !LANGUAGE: +NewInference
// !DIAGNOSTICS: -UNUSED_PARAMETER

fun test(numbers: List<Number>) {
    shortComparable { getShort() ?: 1 }
    shortExpected { getShort() ?: 1 }
}

fun <R> shortExpected(f: () -> R) {}
fun <R : Comparable<R>> shortComparable(f: () -> R) {}

fun getShort(): Short? = 1