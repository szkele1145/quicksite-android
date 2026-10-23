package com.qk.app.core
import android.content.Context

import java.io.File

/**
 * nginx 配置生成。
 *
 * 支持两种站点类型：
 *  - 静态站点：把本地目录发布出去
 *  - 反向代理：把请求转发到本机/远程的某个端口
 */
object NginxConf {

    data class Site(
        val name: String,
        val type: Type,
        val port: Int,
        val root: String? = null,        // 静态站点根目录
        val proxyPass: String? = null,   // 反代目标 http://host:port
        val serverName: String = "localhost",
        val enabled: Boolean = true,
        val extraHeaders: Map<String, String> = emptyMap()
    ) {
        enum class Type { STATIC, PROXY }
    }

    fun defaultMainConf(prefix: String): String = """
        # 注意：不能写 user 指令——以应用 uid 运行时 nginx 无法切用户，只会报 warning
        worker_processes 1;
        error_log $prefix/var/log/nginx/error.log warn;
        pid $prefix/var/run/nginx/nginx.pid;

        events {
            worker_connections 512;
        }

        http {
            include $prefix/etc/nginx/mime.types;
            default_type application/octet-stream;
            access_log $prefix/var/log/nginx/access.log;

            sendfile on;
            keepalive_timeout 65;
            client_max_body_size 32m;

            gzip on;
            gzip_types text/plain text/css application/json application/javascript text/xml application/xml;

            include $prefix/etc/nginx/sites-enabled/*;
        }
    """.trimIndent()

    /** 生成单个站点的配置文件内容 */
    fun siteConf(site: Site, prefix: String): String {
        val sb = StringBuilder()
        sb.appendLine("# 由 QuickSite 生成：${site.name}")
        sb.appendLine("server {")
        sb.appendLine("    listen ${site.port};")
        if (site.serverName.isNotBlank() && site.serverName != "localhost") {
            sb.appendLine("    server_name ${site.serverName};")
        }
        sb.appendLine("    root $prefix/var/www/${site.name};")
        sb.appendLine("    index index.html index.htm;")

        if (site.extraHeaders.isNotEmpty()) {
            sb.appendLine()
            site.extraHeaders.forEach { (k, v) ->
                sb.appendLine("    add_header $k \"$v\";")
            }
        }

        sb.appendLine()
        sb.appendLine("    location / {")
        when (site.type) {
            Site.Type.STATIC -> {
                sb.appendLine("        try_files ${'$'}uri ${'$'}uri/ /index.html;")
            }
            Site.Type.PROXY -> {
                val target = site.proxyPass ?: "http://127.0.0.1:3000"
                sb.appendLine("        proxy_pass $target;")
                sb.appendLine("        proxy_http_version 1.1;")
                sb.appendLine("        proxy_set_header Host ${'$'}host;")
                sb.appendLine("        proxy_set_header X-Real-IP ${'$'}remote_addr;")
                sb.appendLine("        proxy_set_header X-Forwarded-For ${'$'}proxy_add_x_forwarded_for;")
                sb.appendLine("        proxy_set_header X-Forwarded-Proto ${'$'}scheme;")
                sb.appendLine("        proxy_set_header Upgrade ${'$'}http_upgrade;")
                sb.appendLine("        proxy_set_header Connection \"upgrade\";")
                sb.appendLine("        proxy_read_timeout 300s;")
            }
        }
        sb.appendLine("    }")
        sb.appendLine("}")
        return sb.toString()
    }

    /** 写入站点配置到 sites-enabled */
    fun writeSite(ctx: Context, site: Site): File {
        val dir = Paths.nginxSites(ctx)
        dir.mkdirs()
        val prefix = Paths.usr(ctx).absolutePath
        val f = File(dir, "${site.name}.conf")
        f.writeText(siteConf(site, prefix))
        return f
    }

    fun removeSite(ctx: Context, name: String) {
        File(Paths.nginxSites(ctx), "$name.conf").delete()
    }

    fun listSites(ctx: Context): List<String> =
        Paths.nginxSites(ctx).listFiles { f -> f.name.endsWith(".conf") }
            ?.map { it.name.removeSuffix(".conf") }?.sorted() ?: emptyList()

    /** 生成默认欢迎页（懒人配置：装完就有东西看） */
    fun writeDefaultIndex(ctx: Context, siteName: String) {
        val dir = File(Paths.usr(ctx), "var/www/$siteName")
        dir.mkdirs()
        val index = File(dir, "index.html")
        if (!index.exists()) {
            index.writeText("""
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>QuickSite</title>
                    <style>
                        body{font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#0f172a;color:#e2e8f0;
                             display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
                        .c{text-align:center;max-width:32em;padding:2em}
                        h1{font-size:2em;margin:0 0 .4em}
                        p{color:#94a3b8;line-height:1.7}
                        code{background:#1e293b;padding:.2em .5em;border-radius:4px;color:#38bdf8}
                    </style>
                </head>
                <body>
                    <div class="c">
                        <h1>🚀 QuickSite 已就绪</h1>
                        <p>你的手机现在就是一台 Web 服务器。</p>
                        <p>站点目录：<code>var/www/${siteName}</code></p>
                        <p>访问本机：<code>http://127.0.0.1:${'$'}端口</code></p>
                    </div>
                </body>
                </html>
            """.trimIndent())
        }
    }
}
