package com.qk.app.core

import android.content.Context
import java.io.File

/**
 * 环境安装器：把 APK assets 里的 termux bootstrap + frpc 二进制铺到应用私有目录。
 *
 * 流程：
 *  1. assets/bootstrap.zip → $PREFIX (usr/)  ← 运行时做前缀等长替换
 *  2. assets/frpc.bin     → $PREFIX/bin/frpc
 *  3. 写入 $PREFIX/etc/... 基础配置
 *  4. 建立符号链接（bootstrap 自带 SYMLINKS.txt）
 */
object EnvInstaller {

    private const val BOOTSTRAP_ASSET = "bootstrap.zip"
    private const val FRPC_ASSET = "frpc.bin"

    data class Progress(val phase: String, val percent: Int, val detail: String = "")

    /**
     * 安装环境。幂等：已安装则直接返回 true。
     */
    fun install(ctx: Context, onProgress: (Progress) -> Unit = {}): Boolean {
        if (Paths.isInstalled(ctx)) {
            onProgress(Progress("已完成", 100, "环境已就绪"))
            return true
        }
        return try {
            installInternal(ctx, onProgress)
        } catch (e: Exception) {
            Paths.log("EnvInstaller", "安装失败: ${e.message}")
            onProgress(Progress("失败", 0, e.message ?: "未知错误"))
            false
        }
    }

    private fun installInternal(ctx: Context, onProgress: (Progress) -> Unit): Boolean {
        val usr = Paths.usr(ctx)
        val tmp = File(ctx.cacheDir, "bootstrap.zip")
        val tmpOut = File(ctx.cacheDir, "bootstrap_extracted")

        // ---- 1. 复制 assets 到缓存 ----
        onProgress(Progress("准备文件", 5))
        copyAsset(ctx, BOOTSTRAP_ASSET, tmp)

        // ---- 2. 解包 + 前缀替换 ----
        onProgress(Progress("解包运行环境", 15))
        tmpOut.deleteRecursively()
        var lastPct = 15
        PrefixPatcher.unzipAndPatch(tmp, tmpOut) { msg ->
            onProgress(Progress("解包运行环境", lastPct, msg))
        }

        // ---- 3. 移动到 usr/ ----
        onProgress(Progress("安装运行环境", 70))
        usr.mkdirs()
        copyTree(tmpOut, usr)
        tmpOut.deleteRecursively()
        tmp.delete()

        // ---- 4. 符号链接 ----
        onProgress(Progress("建立命令链接", 85))
        createSymlinks(ctx)

        // ---- 5. frpc ----
        onProgress(Progress("安装 frpc", 92))
        installFrpc(ctx)

        // ---- 6. 基础配置 ----
        onProgress(Progress("写入默认配置", 97))
        setupDefaults(ctx)

        onProgress(Progress("完成", 100))
        Paths.stamp(ctx).writeText("v1")
        return true
    }

    private fun copyAsset(ctx: Context, name: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(name).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output, 128 * 1024)
            }
        }
    }

    private fun copyTree(src: File, dest: File) {
        src.walkTopDown().forEach { f ->
            val target = File(dest, f.relativeTo(src).path)
            if (f.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
            }
        }
    }

    /**
     * termux bootstrap 用 SYMLINKS.txt 记录了 bin 下的软链接（如 coreutils -> /bin/true）。
     * 格式为 `target<TAB>linkpath`（实际用 UTF-8 箭头字符）。
     */
    private fun createSymlinks(ctx: Context) {
        val linksFile = File(Paths.usr(ctx), "SYMLINKS.txt")
        if (!linksFile.exists()) return
        val usr = Paths.usr(ctx).absolutePath
        for (line in linksFile.readLines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            // 格式：`linkName←./bin/target`（U+2190 分隔）
            val arrow = t.indexOf('\u2190')
            if (arrow <= 0) continue
            val linkName = t.substring(0, arrow).trim()
            var target = t.substring(arrow + 1).trim()
            if (linkName.isEmpty() || target.isEmpty()) continue

            val linkFile = File(usr, "bin/$linkName")
            if (linkFile.exists()) continue

            target = target.removePrefix("./")
            val absTarget = when {
                target.startsWith("/bin/") -> "$usr$target"
                target.startsWith("/") -> target
                else -> "$usr/$target"
            }
            try {
                OsSymlink.symlink(absTarget, linkFile.absolutePath)
            } catch (_: Exception) {
                // 符号链接失败不致命：bash 仍可工作，只是少了部分别名
            }
        }
        linksFile.delete()
    }

    private fun installFrpc(ctx: Context) {
        val out = File(Paths.usr(ctx), "bin/frpc")
        out.parentFile?.mkdirs()
        try {
            ctx.assets.open(FRPC_ASSET).use { input ->
                out.outputStream().use { input.copyTo(it, 128 * 1024) }
            }
            out.setExecutable(true, false)
        } catch (e: Exception) {
            Paths.log("EnvInstaller", "frpc 安装失败: ${e.message}")
        }
    }

    private fun setupDefaults(ctx: Context) {
        val usr = Paths.usr(ctx)

        // nginx 主配置
        val nginxConf = File(usr, "etc/nginx/nginx.conf")
        if (!nginxConf.exists()) {
            nginxConf.parentFile?.mkdirs()
            nginxConf.writeText(NginxConf.defaultMainConf(usr.absolutePath))
        }
        File(usr, "etc/nginx/sites-available").mkdirs()
        File(usr, "etc/nginx/sites-enabled").mkdirs()
        File(usr, "var/log/nginx").mkdirs()
        File(usr, "var/run/nginx").mkdirs()

        // bashrc
        val bashrc = File(usr, "etc/bash.bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                listOf(
                    "export PREFIX=${usr.absolutePath}",
                    "export HOME=${Paths.home(ctx).absolutePath}",
                    "export PATH=\"\$PREFIX/bin:\$PATH\"",
                    "export TMPDIR=\"\$PREFIX/tmp\"",
                    "export LS_COLORS='di=01;34:ln=01;36:so=01;35'",
                    "alias ll='ls -l'",
                    "alias la='ls -a'"
                ).joinToString("\n") + "\n"
            )
        }
        // profile
        val profile = File(usr, "etc/profile")
        if (!profile.exists()) {
            profile.writeText(
                listOf(
                    "[ -f \"${usr.absolutePath}/etc/bash.bashrc\" ] && . \"${usr.absolutePath}/etc/bash.bashrc\"",
                    "export PATH=\"\$PREFIX/bin:\$PATH\""
                ).joinToString("\n") + "\n"
            )
        }
        // hosts
        val hosts = File(usr, "etc/hosts")
        if (!hosts.exists()) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        // home 目录
        Paths.home(ctx).mkdirs()
        File(Paths.home(ctx), ".bashrc").writeText(
            "[ -f \"${usr.absolutePath}/etc/bash.bashrc\" ] && . \"${usr.absolutePath}/etc/bash.bashrc\"\n"
        )
    }
}

/** android.system.Os.symlink 的安全封装 */
object OsSymlink {
    fun symlink(target: String, linkPath: String) {
        val osClass = Class.forName("android.system.Os")
        val m = osClass.getMethod("symlink", String::class.java, String::class.java)
        m.invoke(null, target, linkPath)
    }
}
