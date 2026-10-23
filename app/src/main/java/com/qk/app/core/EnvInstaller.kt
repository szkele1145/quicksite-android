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

        // ---- 1. 直接从 assets 流式解包到 usr/（不落中间 zip，省磁盘与 IO）----
        onProgress(Progress("解包运行环境", 10))
        usr.mkdirs()
        var files = 0
        val destPath = usr.canonicalPath
        ctx.assets.open(BOOTSTRAP_ASSET).use { raw ->
            java.util.zip.ZipInputStream(raw.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(usr, entry.name)
                    if (!out.canonicalPath.startsWith(destPath)) {
                        throw SecurityException("非法 zip 条目: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        PrefixPatcher.writeEntriesPatched(zis, out)
                        files++
                        if (files % 300 == 0) {
                            onProgress(Progress("解包运行环境", 10 + (files / 60).coerceAtMost(60), "已解包 $files 个文件"))
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Paths.log("EnvInstaller", "解包完成，共 $files 个文件")

        // ---- 1.5 权限修复（关键）----
        // Java 的 ZipInputStream 解包不会还原 unix 权限位，所有文件默认是 rw-------，
        // 直接执行 bin/bash 会 EACCES。这里统一按目录语义补权限。
        onProgress(Progress("设置执行权限", 75))
        fixPermissions(usr)

        // ---- 2. 符号链接 ----
        onProgress(Progress("建立命令链接", 80))
        createSymlinks(ctx)

        // ---- 3. frpc ----
        onProgress(Progress("安装 frpc", 88))
        installFrpc(ctx)

        // ---- 4. 基础配置 ----
        onProgress(Progress("写入默认配置", 95))
        setupDefaults(ctx)

        onProgress(Progress("完成", 100))
        Paths.stamp(ctx).writeText("v1")
        return true
    }

    /**
     * 修复解包后文件的权限位。
     *
     * Java 解 zip 不会还原权限，全部是 0600；而 termux 环境里 bin/ 与 libexec/ 下
     * 的每个文件都必须可执行，否则 `bash -lc` 直接 EACCES，整套运行环境不可用。
     */
    private fun fixPermissions(usr: File) {
        val usrPath = usr.absolutePath
        val execDirs = listOf("bin", "libexec", "libexec/termux", "libexec/termux-am")
        var exec = 0
        var plain = 0
        usr.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val rel = f.absolutePath.removePrefix(usrPath).trimStart(File.separatorChar)
            val inExecDir = execDirs.any { rel.startsWith("$it${File.separatorChar}") } ||
                !rel.contains(File.separatorChar) // usr/ 根下的脚本
            try {
                if (inExecDir) {
                    OsChmod.chmod(f.absolutePath, 0x1ED)   // 0755
                    exec++
                } else {
                    OsChmod.chmod(f.absolutePath, 0x1A4)   // 0644
                    plain++
                }
            } catch (_: Exception) {
                // 单个文件失败不影响整体
            }
        }
        Paths.log("EnvInstaller", "权限设置完成：可执行 $exec 个，普通 $plain 个")
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
     * 按 SYMLINKS.txt 重建符号链接。
     *
     * 文件格式是 **`<目标>←<链接路径>`**（U+2190 左箭头分隔），例如：
     * ```
     * coreutils←./bin/true          → bin/true 是指向 bin/coreutils 的链接
     * xz←./bin/xzcat                → bin/xzcat 指向 bin/xz
     * xzgrep.1.gz←./share/man/man1/xzegrep.1.gz
     * ```
     * 左侧不含 `/` 时是「相对链接所在目录」的名字，右侧带 `./` 前缀。
     *
     * 这些链接必须建：`ls`/`cp`/`true`/`printf` 等基础命令在 termux 里都是
     * coreutils 多调用二进制的链接，缺了它 bash 脚本基本跑不起来。
     */
    private fun createSymlinks(ctx: Context) {
        val linksFile = File(Paths.usr(ctx), "SYMLINKS.txt")
        if (!linksFile.exists()) return
        val usr = Paths.usr(ctx)
        val usrPath = usr.absolutePath
        var created = 0
        var skipped = 0

        for (line in linksFile.readLines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val arrow = t.indexOf('\u2190')
            if (arrow <= 0) continue

            val targetName = t.substring(0, arrow).trim().removePrefix("./")
            var linkPath = t.substring(arrow + 1).trim().removePrefix("./")
            if (targetName.isEmpty() || linkPath.isEmpty()) continue

            val linkFile = File(usr, linkPath)
            // 链接所在目录
            val linkDir = linkFile.parentFile ?: continue

            // 目标：含 `/` 视为相对 usr 根；否则相对链接所在目录
            val absTarget = if (targetName.contains('/')) {
                File(usr, targetName).absolutePath
            } else {
                File(linkDir, targetName).absolutePath
            }

            // 目标不存在就不建（多半是已裁掉的 man/doc）
            if (!File(absTarget).exists()) {
                skipped++
                continue
            }
            // 已存在同名实体文件（非链接）就保留，不覆盖
            if (linkFile.exists() && !isSymlink(linkFile)) {
                skipped++
                continue
            }
            try {
                linkFile.parentFile?.mkdirs()
                if (isSymlink(linkFile)) linkFile.delete()
                OsSymlink.symlink(absTarget, linkFile.absolutePath)
                created++
            } catch (_: Exception) {
                skipped++
            }
        }
        Paths.log("EnvInstaller", "符号链接：创建 $created 个，跳过 $skipped 个")
        linksFile.delete()
    }

    /** 判断是否符号链接（Android 上 File.isSymlink 不可靠，用 canonical 对比）。 */
    private fun isSymlink(f: File): Boolean = try {
        f.canonicalPath != f.absolutePath
    } catch (_: Exception) {
        true
    }

    private fun installFrpc(ctx: Context) {
        val out = File(Paths.usr(ctx), "bin/frpc")
        out.parentFile?.mkdirs()
        try {
            ctx.assets.open(FRPC_ASSET).use { input ->
                out.outputStream().use { input.copyTo(it, 128 * 1024) }
            }
            OsChmod.chmod(out.absolutePath, 0x1ED) // 0755
        } catch (e: Exception) {
            Paths.log("EnvInstaller", "frpc 安装失败: ${e.message}")
        }
    }

    /** 首次安装时种一个默认站点（8080，静态），让用户开箱就有东西可看。 */
    private fun seedDefaultSite(ctx: Context) {
        try {
            val cfg = AppConfig(ctx)
            if (cfg.sites().isNotEmpty()) return

            val site = NginxConf.Site(
                name = "default",
                type = NginxConf.Site.Type.STATIC,
                port = 8080,
                root = "var/www/default",
                serverName = "localhost"
            )
            NginxConf.writeSite(ctx, site)
            NginxConf.writeDefaultIndex(ctx, "default")
            cfg.saveSites(listOf(site))
            Paths.log("EnvInstaller", "已创建默认站点：8080")
        } catch (e: Exception) {
            Paths.log("EnvInstaller", "创建默认站点失败: ${e.message}")
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
        // nginx 的临时目录（编译期写死在这些路径下，必须提前建好且可写）
        listOf("client-body", "proxy", "fastcgi", "scgi", "uwsgi").forEach {
            File(usr, "var/lib/nginx/$it").mkdirs()
        }
        // apt 的可写目录（控制台里 apt update / install 依赖它们）
        listOf("var/lib/apt/lists/partial", "var/cache/apt/archives/partial", "var/log/apt").forEach {
            File(usr, it).mkdirs()
        }

        // 首次安装自动建一个默认站点，保证「一键启动」后立刻有页面可看
        seedDefaultSite(ctx)

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

/** android.system.Os.chmod 的安全封装（失败时退回 File.setExecutable） */
object OsChmod {
    fun chmod(path: String, mode: Int) {
        try {
            val osClass = Class.forName("android.system.Os")
            val m = osClass.getMethod("chmod", String::class.java, Int::class.javaPrimitiveType)
            m.invoke(null, path, mode)
        } catch (_: Exception) {
            val f = java.io.File(path)
            val executable = (mode and 0x49) != 0
            f.setReadable(true, false)
            f.setWritable(true, true)
            f.setExecutable(executable, false)
        }
    }
}
