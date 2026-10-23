package com.qk.app.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 统一的环境变量。termux 的 bash 会自动读取 `$PREFIX/etc/profile` 等，
 * 我们在运行任何命令前显式注入同样的变量，保证行为一致。
 */
object Env {

    fun base(ctx: Context): MutableMap<String, String> {
        val usr = Paths.usr(ctx).absolutePath
        val home = Paths.home(ctx).absolutePath
        val m = mutableMapOf(
            "PREFIX" to usr,
            "HOME" to home,
            "PATH" to "$usr/bin:$usr/bin:/system/bin",
            "TMPDIR" to "$usr/tmp",
            "SHELL" to "$usr/bin/bash",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
            "EXTERNAL_STORAGE" to "/sdcard",
            "PREFIX" to usr,
            // dpkg 前缀改写后，让 apt/dpkg 认得我们的根目录
            "DPKG_ROOT" to usr,
            "QS_UNINSTALL" to "0",
            "QS_ARCH" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
        )
        // 让 termux-am 等工具不会去弹权限框
        m["TERMUX_APP_PACKAGE_MANAGER"] = "apt"
        return m
    }

    fun termuxStyle(ctx: Context): Map<String, String> = base(ctx)
}

/**
 * 进程执行器：在 termux 环境里跑命令。
 *
 * 统一走 `$PREFIX/bin/bash -lc "<cmd>"`，并把 stdout/stderr 合并到一个可读流。
 */
class Shell(private val ctx: Context) {

    data class Result(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * 执行命令并等待完成。
     * @param timeoutSec 超时秒数
     * @param cwd 工作目录（null = HOME）
     */
    fun run(
        command: String,
        timeoutSec: Long = 120,
        cwd: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        val bash = File(Paths.usr(ctx), "bin/bash")
        if (!bash.exists()) {
            return Result(-1, "环境未安装：$bash 不存在")
        }
        val cmd = ProcessBuilder(bash.absolutePath, "-lc", command)
        cmd.environment().putAll(Env.base(ctx))
        cmd.environment().putAll(extraEnv)
        cmd.directory(cwd ?: Paths.home(ctx))
        cmd.redirectErrorStream(true)
        return try {
            val p = cmd.start()
            val out = StringBuilder()
            p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                Result(-9, out.toString(), timedOut = true)
            } else {
                Result(p.exitValue(), out.toString())
            }
        } catch (e: IOException) {
            Result(-1, "执行失败: ${e.message}")
        }
    }

    /**
     * 后台启动（守护进程），返回 Process 以便后续停止。
     */
    fun startDetached(
        command: String,
        cwd: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
        logFile: File? = null
    ): Process? {
        val bash = File(Paths.usr(ctx), "bin/bash")
        if (!bash.exists()) return null
        val cmd = ProcessBuilder(bash.absolutePath, "-lc", command)
        cmd.environment().putAll(Env.base(ctx))
        cmd.environment().putAll(extraEnv)
        cmd.directory(cwd ?: Paths.home(ctx))
        cmd.redirectErrorStream(true)
        return try {
            val p = cmd.start()
            if (logFile != null) {
                logFile.parentFile?.mkdirs()
                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine {
                            synchronized(logFile) { logFile.appendText(it + "\n") }
                        }
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true }.start()
            }
            p
        } catch (e: IOException) {
            Paths.log("Shell", "启动失败: ${e.message}")
            null
        }
    }
}
