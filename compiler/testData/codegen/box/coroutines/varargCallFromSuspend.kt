// WITH_RUNTIME

import kotlin.coroutines.*

var failure = false

suspend fun foo() {
    if ((baz(
            bar()
        ))[0] != 5) failure = true
}

fun <T> baz(vararg elements: T): Array<out T> = elements

suspend fun bar() = 5

fun box(): String {
    ::foo.startCoroutine(Continuation(EmptyCoroutineContext) { it.getOrThrow() })

    return if (!failure) "OK" else "FAILURE"
}