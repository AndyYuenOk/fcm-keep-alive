package com.example.fcmkeepalive

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

internal object ShizukuProcessRunner {
    data class Result(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean
    )

    fun run(
        process: Process,
        timeoutMs: Long,
        destroyGraceMs: Long,
        streamReadGraceMs: Long
    ): Result {
        val stdoutCollector = StreamCollector(process.inputStream)
        val stderrCollector = StreamCollector(process.errorStream)
        val exitCode = waitForExit(process, timeoutMs)
        if (exitCode == null) {
            terminateTimedOutProcess(process, destroyGraceMs)
            return Result(
                exitCode = null,
                stdout = stdoutCollector.await(streamReadGraceMs),
                stderr = stderrCollector.await(streamReadGraceMs),
                timedOut = true
            )
        }
        return Result(
            exitCode = exitCode,
            stdout = stdoutCollector.await(streamReadGraceMs),
            stderr = stderrCollector.await(streamReadGraceMs),
            timedOut = false
        )
    }

    private fun terminateTimedOutProcess(process: Process, destroyGraceMs: Long) {
        process.destroy()
        if (waitForExit(process, destroyGraceMs) == null) {
            process.destroyForcibly()
            waitForExit(process, destroyGraceMs)
        }
    }

    private fun waitForExit(process: Process, timeoutMs: Long): Int? {
        val exitCodeRef = AtomicReference<Int?>(null)
        val failureRef = AtomicReference<Throwable?>(null)
        val waiter = Thread({
            try {
                exitCodeRef.set(process.waitFor())
            } catch (t: Throwable) {
                failureRef.set(t)
            }
        }, "ShizukuProcessWaiter").apply {
            isDaemon = true
            start()
        }
        waiter.join(timeoutMs)
        failureRef.get()?.let { throw it }
        return if (waiter.isAlive) null else exitCodeRef.get()
    }

    private class StreamCollector(stream: InputStream?) {
        private val textRef = AtomicReference("")
        private val failureRef = AtomicReference<Throwable?>(null)
        private val thread = Thread({
            try {
                textRef.set(stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (t: Throwable) {
                failureRef.set(t)
            }
        }, "ShizukuStreamCollector").apply {
            isDaemon = true
            start()
        }

        fun await(timeoutMs: Long): String {
            thread.join(timeoutMs)
            failureRef.get()?.let { throw it }
            return textRef.get()
        }
    }
}
