fun main() {
  for (i <caret>in 1..2) {}
}

// MULTIRESOLVE
// REF: (in kotlin.IntIterator).next()
// REF: (in kotlin.IntRange).iterator()
// REF: (in kotlin.Iterator).hasNext()
