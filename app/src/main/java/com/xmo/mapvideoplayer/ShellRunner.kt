package com.xmo.mapvideoplayer

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Runs shell scripts on the device. The Changan head unit has a vendor-locked
 * adbd that prompts for a password on sensitive commands when called from
 * outside, but in-process `su` from a rooted shell bypasses that — we get a
 * real root shell directly. `am` and `settings` work without prompting here.
 */
class ShellRunner {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Pipes [script] into a single `su` invocation and waits for it to finish.
     * Multiple commands can be separated by newlines; they share a shell so
     * variables and `cd` persist across lines.
     */
    fun runAsRoot(script: String): Result {
        Log.d(TAG, "runAsRoot script:\n$script")
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                if (!script.endsWith("\n")) writer.write("\n")
                writer.write("exit\n")
                writer.flush()
            }

            val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
            val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
            val exitCode = process.waitFor()

            Log.d(TAG, "exit=$exitCode stdout.len=${stdout.length} stderr.len=${stderr.length}")
            if (stderr.isNotBlank()) Log.w(TAG, "stderr: $stderr")

            Result(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "su invocation failed", e)
            Result(exitCode = -1, stdout = "", stderr = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Quick smoke-check: does `su` work and return a real root id? */
    fun isRootAvailable(): Boolean {
        val r = runAsRoot("id")
        return r.success && r.stdout.contains("uid=0")
    }

    companion object {
        private const val TAG = "ShellRunner"
    }
}
