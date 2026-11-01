package dev.admcp.core.process

import java.io.File
import kotlin.system.exitProcess

object ProcessTestHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "args" -> args.drop(1).forEach(::println)
            "both" -> {
                System.out.print("OUT-")
                System.err.print("ERR-")
                System.out.flush()
                System.err.flush()
            }
            "flood" -> flood(300)
            "floodmax" -> flood(5000)
            "utf8" -> {
                val text = "hello-\u4e16\u754c-\u20ac-\ud83d\ude80"
                val bytes = text.toByteArray(Charsets.UTF_8)
                System.out.write(bytes.copyOfRange(0, 5))
                System.out.flush()
                Thread.sleep(20)
                System.out.write(bytes.copyOfRange(5, bytes.size))
                System.out.flush()
            }
            "stdin" -> System.`in`.copyTo(System.out)
            "sleep" -> Thread.sleep(60_000)
            "fail" -> exitProcess(7)
            else -> exitProcess(2)
        }
    }

    private fun flood(repetitions: Int) {
        val block = "0123456789abcdef".repeat(512)
        repeat(repetitions) {
            System.out.print(block)
            System.err.print(block)
        }
        System.out.flush()
        System.err.flush()
    }

    fun classpath(): String = System.getProperty("java.class.path")
    fun javaExecutable(): File {
        val home = System.getProperty("java.home")
        return File(home, "bin/java" + if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else "")
    }
}
