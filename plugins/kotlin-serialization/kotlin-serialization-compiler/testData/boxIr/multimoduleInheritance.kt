// IGNORE_BACKEND_FIR: JVM_IR
// TARGET_BACKEND: JVM_IR
// IGNORE_DEXING

// WITH_RUNTIME

// MODULE: lib
// FILE: lib.kt

package a

import kotlinx.serialization.*

@Serializable
open class Demo {
    var optional: String? = null
}


// MODULE: app(lib)
// FILE: app.kt

package test

import a.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
//
//@Serializable
//sealed class Project {
//    abstract val name: String
//    var status = "open"
//}
//
//@Serializable
//@SerialName("owned")
//class OwnedProject(override val name: String, val owner: String) : Project()

@Serializable
class Demo1: Demo()

fun main() {
    val string = Json.encodeToString(Demo1.serializer(), Demo1())
    println(string)
    val reconstructed = Json.decodeFromString(Demo1.serializer(),string)
}

//fun main() {
//    val json = Json { encodeDefaults = false } // "status" will be skipped otherwise
//    val data: Project = OwnedProject("kotlinx.coroutines", "kotlin")
//    println(json.encodeToString(data))
//}

fun box(): String {
    main()
    return "OK"
}