package com.qk.app.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务管理器：统一管理 nginx / pm2 / frpc 三个后台进程的启停与状态。
 */
class ServiceManager(private val ctx: Context) {

    private val shell = Shell(ctx)
    private val procs = ConcurrentHashMap<String, Process>()

    companion object {
        const val SVC_NGINX = "nginx"
        const val SVC_FRPC = "frpc"
        const val SVC_PM2 = "pm2"
    }

    // ---------------- 状态查询 ----------------

    fun nginxRunning(): Boolean {
        val pidFile = File(Paths.nginxRun(ctx), "nginx.pid")
        if (!pidFile.exists()) return false
        val pid = pidFile.readText().trim().toIntOrNull() ?: return false
        return pidAlive(pid)
    }

    fun frpcRunning(): Boolean = procs[SVC_FRPC]?.let { it.isAlive } ?: false

    fun pm2Running(): Boolean = shell.run("command -v pm2 >/dev/null 2>&1 && pm2 ls 2>/dev/null | grep -q 'online' ", 15).ok

    fun pidAlive(pid: Int): Boolean =
        try {
            ProcessBuilder("/system/bin/kill", "-0", pid.toString())
                .redirectErrorStream(true).start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }

    // ---------------- nginx ----------------

    /**
     * 启动 nginx。
     */
    fun startNginx(): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        val nginxBin = File(Paths.usr(ctx), "bin/nginx")
        if (!nginxBin.exists()) {
            return Shell.Result(-1, "nginx 未安装")
        }
        // 检查配置
        val test = shell.run("\"$prefix/bin/nginx\" -t -p \"$prefix\" -c \"$prefix/etc/nginx/nginx.conf\"", 30)
        if (!test.ok) {
            return Shell.Result(-2, "nginx 配置检查失败：\n${test.output}")
        }
        val r = shell.run("\"$prefix/bin/nginx\" -p \"$prefix\" -c \"$prefix/etc/nginx/nginx.conf\"", 30)
        return if (r.ok) Shell.Result(0, "nginx 已启动") else Shell.Result(-1, "nginx 启动失败：\n${r.output}")
    }

    fun stopNginx(): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        val r = shell.run("\"$prefix/bin/nginx\" -s stop -p \"$prefix\" -c \"$prefix/etc/nginx/nginx.conf\" 2>&1 || true", 20)
        return Shell.Result(0, "nginx 已停止\n${r.output}")
    }

    fun reloadNginx(): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        val r = shell.run(
            "\"$prefix/bin/nginx\" -s reload -p \"$prefix\" -c \"$prefix/etc/nginx/nginx.conf\" 2>&1 || true",
            20
        )
        return Shell.Result(0, r.output)
    }

    fun nginxPorts(): List<Int> {
        val dir = Paths.nginxSites(ctx)
        return dir.listFiles { f -> f.name.endsWith(".conf") }?.mapNotNull { f ->
            Regex("listen\\s+(\\d+)").find(f.readText())?.groupValues?.get(1)?.toIntOrNull()
        } ?: emptyList()
    }

    // ---------------- frpc ----------------

    /**
     * 启动 frpc（后台守护，日志写入文件）。
     */
    fun startFrpc(): Shell.Result {
        if (frpcRunning()) return Shell.Result(0, "frpc 已在运行")
        val conf = Paths.frpcConf(ctx)
        if (!conf.exists()) {
            return Shell.Result(-1, "未找到 frpc 配置文件，请先导入")
        }
        val frpc = File(Paths.usr(ctx), "bin/frpc")
        if (!frpc.exists()) {
            return Shell.Result(-1, "frpc 未安装")
        }
        val log = File(Paths.logDir(ctx), "frpc.log")
        log.delete()
        val prefix = Paths.usr(ctx).absolutePath
        val p = shell.startDetached(
            "\"$prefix/bin/frpc\" -c \"${conf.absolutePath}\" -l \"${log.absolutePath}\"",
            logFile = log
        )
        if (p == null) return Shell.Result(-1, "frpc 启动失败")
        procs[SVC_FRPC] = p
        // 稍等检查是否存活
        Thread.sleep(1200)
        return if (p.isAlive) Shell.Result(0, "frpc 已启动") else {
            val err = if (log.exists()) log.readText() else "(无日志)"
            Shell.Result(-1, "frpc 启动失败：\n$err")
        }
    }

    fun stopFrpc(): Shell.Result {
        procs[SVC_FRPC]?.let {
            it.destroy()
            if (it.isAlive) {
                Thread.sleep(300)
                it.destroyForcibly()
            }
        }
        procs.remove(SVC_FRPC)
        // 兜底：杀掉残留进程
        shell.run("pkill -f 'frpc -c' 2>/dev/null || true", 10)
        return Shell.Result(0, "frpc 已停止")
    }

    fun frpcLog(): String {
        val log = File(Paths.logDir(ctx), "frpc.log")
        return if (log.exists()) log.readText() else ""
    }

    // ---------------- pm2 ----------------

    /**
     * pm2 启动一个 node 项目。
     * @param script 入口脚本（相对 projects 目录或绝对路径）
     */
    fun pm2Start(name: String, script: String, args: String = ""): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        val cmd = buildString {
            append("export PATH=\"$prefix/bin:${'$'}PATH\"; ")
            append("cd \"${Paths.nodeHome(ctx).absolutePath}\"; ")
            append("pm2 delete $name >/dev/null 2>&1; ")
            append("pm2 start \"$script\" --name $name")
            if (args.isNotBlank()) append(" -- $args")
            append("; pm2 save >/dev/null 2>&1")
        }
        return shell.run(cmd, 120)
    }

    fun pm2Stop(name: String): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        return shell.run("export PATH=\"$prefix/bin:${'$'}PATH\"; pm2 delete $name 2>&1; pm2 save >/dev/null 2>&1", 60)
    }

    fun pm2List(): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        return shell.run("export PATH=\"$prefix/bin:${'$'}PATH\"; pm2 jlist 2>/dev/null || pm2 ls 2>&1", 30)
    }

    fun pm2Logs(name: String, lines: Int = 100): Shell.Result {
        val prefix = Paths.usr(ctx).absolutePath
        return shell.run("export PATH=\"$prefix/bin:${'$'}PATH\"; pm2 logs $name --lines $lines --nostream 2>&1", 30)
    }

    /** 停止所有服务（应用退出时） */
    fun stopAll() {
        try { stopNginx() } catch (_: Exception) {}
        try { stopFrpc() } catch (_: Exception) {}
    }
}
