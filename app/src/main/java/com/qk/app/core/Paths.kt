package com.qk.app.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 应用私有目录布局。
 *
 * 关键约束：termux bootstrap 里的二进制把 `/data/data/com.termux/files/usr` 硬编码进了
 * ELF 字符串表与 RUNPATH。我们用**字节等长**的包名 `com.qk.app`，
 * 使得 `/data/data/com.qk.app/files/usr` 与原路径长度完全一致，
 * 因此可以在安装期对二进制做等长字节替换，无需改动 ELF 结构。
 */
object Paths {

    const val PKG = "com.qk.app"

    /** 原始 termux 前缀（bootstrap 内硬编码） */
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** 我们的前缀（与 TERMUX_PREFIX 等长：31 字符） */
    const val OUR_PREFIX = "/data/data/com.qk.app/files/usr"

    init {
        require(OUR_PREFIX.length == TERMUX_PREFIX.length) {
            "前缀必须等长才能做字节级替换：${OUR_PREFIX.length} != ${TERMUX_PREFIX.length}"
        }
    }

    fun appRoot(ctx: Context): File = File(files(ctx), "usr").apply { mkdirs() }

    /** termux 用户目录（usr/），内含 bin/lib/share 等 */
    fun usr(ctx: Context): File = File(files(ctx), "usr")

    /** HOME 目录 */
    fun home(ctx: Context): File = File(files(ctx), "home").apply { mkdirs() }

    /** 前缀目录，如 /data/data/com.qk.app/files */
    fun files(ctx: Context): File = ctx.filesDir.parentFile
        ?: throw IllegalStateException("filesDir 没有父目录：${ctx.filesDir}")

    fun prefix(ctx: Context): String = files(ctx).absolutePath + "/usr"

    fun homePath(ctx: Context): String = home(ctx).absolutePath

    /** 安装标记：环境是否已就绪 */
    fun stamp(ctx: Context): File = File(ctx.filesDir, "env.stamp")

    fun isInstalled(ctx: Context): Boolean =
        stamp(ctx).exists() && File(usr(ctx), "bin/bash").exists()

    /** 日志目录 */
    fun logDir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    /** 生成的配置目录 */
    fun confDir(ctx: Context): File = File(ctx.filesDir, "conf").apply { mkdirs() }

    /** nginx 配置目录 */
    fun nginxConf(ctx: Context): File = File(usr(ctx), "etc/nginx").apply { mkdirs() }

    /** nginx 站点目录 */
    fun nginxSites(ctx: Context): File = File(nginxConf(ctx), "sites-enabled").apply { mkdirs() }

    /** nginx 运行目录（pid / 临时文件） */
    fun nginxRun(ctx: Context): File = File(usr(ctx), "var/run/nginx").apply { mkdirs() }

    /** pm2 / node 项目目录 */
    fun nodeHome(ctx: Context): File = File(home(ctx), "projects").apply { mkdirs() }

    /** frpc 配置 */
    fun frpcConf(ctx: Context): File = File(confDir(ctx), "frpc.toml")

    fun log(tag: String, msg: String) = Log.i("QuickSite", "[$tag] $msg")
}
