package com.cmdtweaks.core

import com.cmdtweaks.App
import com.cmdtweaks.BuildConfig
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

data class ShellResult(
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>
) {
    val stdoutText: String get() = stdout.joinToString("\n")
    val stderrText: String get() = stderr.joinToString("\n")
}

object ShellEngine {
    private const val MAX_POOL_SIZE = 4
    private val rootPool = LinkedBlockingQueue<Shell>(MAX_POOL_SIZE)
    private val shizukuPool = LinkedBlockingQueue<ShizukuSession>(MAX_POOL_SIZE)

    fun rewritePathForBackend(input: String, activeModuleDir: String?): String {
        val targetDir = activeModuleDir?.takeIf { it.isNotEmpty() } ?: return input
        return if (targetDir != PrivilegeManager.PRIMARY_MODULE_DIR && input.contains(PrivilegeManager.PRIMARY_MODULE_DIR)) {
            input.replace(PrivilegeManager.PRIMARY_MODULE_DIR, targetDir)
        } else {
            input
        }
    }

    fun newDedicatedRootShell(): Shell {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        return Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .build()
    }

    private fun acquireRootShell(): Shell {
        while (true) {
            val candidate = rootPool.poll() ?: break
            if (candidate.isAlive && candidate.isRoot) {
                return candidate
            }
            runCatching { candidate.close() }
        }
        return newDedicatedRootShell()
    }

    private fun releaseRootShell(shell: Shell) {
        if (!shell.isAlive || !rootPool.offer(shell)) {
            runCatching { shell.close() }
        }
    }

    fun createShizukuProcess(command: String? = null): Process {
        val cmdArgs = if (command == null) arrayOf("sh") else arrayOf("sh", "-c", command)
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmdArgs, null, null) as Process
        } catch (_: Throwable) {
            ProcessBuilder(*cmdArgs).start()
        }
    }

    fun prewarmShizukuPool() {
        App.executor.submit {
            repeat(2) {
                val session = ShizukuSession()
                session.execute("true")
                if (!shizukuPool.offer(session)) {
                    session.close()
                }
            }
        }
    }

    fun executeFast(rawCommand: String, activeModuleDir: String? = null): String {
        val adaptedCommand = rewritePathForBackend(rawCommand, activeModuleDir)
        return if (!PrivilegeManager.isShizukuActive) {
            val shell = acquireRootShell()
            try {
                ShellUtils.fastCmd(shell, adaptedCommand)
            } finally {
                releaseRootShell(shell)
            }
        } else {
            executeSync(adaptedCommand, activeModuleDir).stdoutText
        }
    }

    fun executeSync(rawCommand: String, activeModuleDir: String? = null): ShellResult {
        val adaptedCommand = rewritePathForBackend(rawCommand, activeModuleDir)
        return if (!PrivilegeManager.isShizukuActive) {
            val shell = acquireRootShell()
            try {
                val res = shell.newJob().add(adaptedCommand).to(ArrayList(), ArrayList()).exec()
                ShellResult(res.code, res.out, res.err)
            } finally {
                releaseRootShell(shell)
            }
        } else {
            val session = shizukuPool.poll() ?: ShizukuSession()
            val result = session.execute(adaptedCommand)
            if (!shizukuPool.offer(session)) {
                session.close()
            }
            result
        }
    }

    private class ShizukuSession {
        private var process: Process? = null
        private var writer: OutputStreamWriter? = null
        private var reader: BufferedReader? = null

        fun execute(command: String): ShellResult {
            return try {
                var proc = process
                if (proc == null || !proc.isAlive) {
                    proc = createShizukuProcess(null)
                    process = proc
                    writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
                    reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                    val errStream = proc.errorStream
                    Thread {
                        runCatching {
                            val buffer = ByteArray(4096)
                            while (errStream.read(buffer) != -1) {
                                // Drain stderr asynchronously
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }

                val marker = "__CMDT_END_${UUID.randomUUID().toString().replace("-", "")}__"
                val w = writer!!
                val r = reader!!

                val wrappedScript = buildString {
                    append("(\n")
                    append(command)
                    append("\n)\n")
                    append("__CMDT_EC=$?\n")
                    append("printf '\\n%s:%d\\n' '$marker' \"${"$"}__CMDT_EC\"\n")
                }

                w.write(wrappedScript)
                w.flush()

                val lines = ArrayList<String>()
                var exitCode = 0
                while (true) {
                    val line = r.readLine() ?: throw IllegalStateException("Unexpected EOF")
                    val idx = line.indexOf("$marker:")
                    if (idx >= 0) {
                        val prefix = line.substring(0, idx)
                        if (prefix.isNotEmpty()) {
                            lines.add(prefix)
                        }
                        exitCode = line.substring(idx + marker.length + 1).trim().toIntOrNull() ?: 0
                        break
                    }
                    lines.add(line)
                }

                if (lines.isNotEmpty() && lines.last().isEmpty()) {
                    lines.removeAt(lines.lastIndex)
                }

                ShellResult(exitCode, lines, emptyList())
            } catch (_: Throwable) {
                close()
                executeOneShot(command)
            }
        }

        private fun executeOneShot(command: String): ShellResult {
            return try {
                val proc = createShizukuProcess(command)
                val outLines = ArrayList<String>()
                val errLines = ArrayList<String>()
                val outReader = Thread {
                    proc.inputStream.bufferedReader().useLines { seq -> seq.forEach { outLines.add(it) } }
                }
                val errReader = Thread {
                    proc.errorStream.bufferedReader().useLines { seq -> seq.forEach { errLines.add(it) } }
                }
                outReader.start()
                errReader.start()
                val exit = proc.waitFor()
                outReader.join()
                errReader.join()
                ShellResult(exit, outLines, errLines)
            } catch (e: Throwable) {
                ShellResult(1, emptyList(), listOf(e.message ?: "Command execution failed"))
            }
        }

        fun close() {
            runCatching { process?.destroy() }
            process = null
            writer = null
            reader = null
        }
    }
}
