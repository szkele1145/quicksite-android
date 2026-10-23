package com.qk.app.core
import android.content.Context

import java.io.File

/**
 * frpc 配置文件导入与解析。
 *
 * 支持两种输入格式：
 *  - TOML（frp 官方格式）
 *  - INI / 老版本 ini 格式（frp v0.4x 时代）→ 自动转换为 TOML
 *
 * 导入后做「智能修正」：
 *  - 补全 serverAddr / serverPort 默认值
 *  - 把本机 bindPort 与 nginx 端口冲突时提示
 *  - 自动识别 auth.method = "token" / "tls" 等
 */
object FrpcConf {

    data class Proxy(
        val name: String,
        val type: String,          // tcp / udp / http / https / stcp / xtcp / tcpmux
        val localIp: String,
        val localPort: Int,
        val remotePort: Int? = null,
        val customDomains: List<String> = emptyList(),
        val secretKey: String? = null,
        val group: String? = null,
        val extra: Map<String, String> = emptyMap()
    )

    data class Imported(
        val serverAddr: String,
        val serverPort: Int,
        val authMethod: String,
        val authToken: String?,
        val proxies: List<Proxy>,
        val toml: String,
        val warnings: List<String> = emptyList(),
        val sourceFormat: String = "toml"
    )

    // ---------------- 解析 ----------------

    /**
     * 从文本解析（自动识别 toml / ini）。
     */
    fun parse(text: String): Imported {
        val trimmed = text.trim()
        return if (looksLikeIni(trimmed)) parseIni(trimmed) else parseToml(trimmed)
    }

    private fun looksLikeIni(text: String): Boolean {
        // frp ini 格式特征：有 [common] 或 [xxx] 且含 server_addr = xxx
        val hasSection = Regex("^\\s*\\[[^\\]]+\\]\\s*$", RegexOption.MULTILINE).containsMatchIn(text)
        val hasIniKey = Regex("^\\s*server_addr\\s*=", RegexOption.MULTILINE).containsMatchIn(text)
        val hasTomlTable = Regex("^\\s*\\[[^\\]]+\\]\\s*$", RegexOption.MULTILINE).containsMatchIn(text)
        return hasSection && hasIniKey && !hasTomlTable
    }

    /** 极简 TOML 解析：只处理 [table]、key = value、字符串/数字/布尔/数组 */
    private fun parseToml(text: String): Imported {
        val warnings = mutableListOf<String>()
        var serverAddr = ""
        var serverPort = 7777
        var authMethod = "token"
        var authToken: String? = null
        val proxies = mutableListOf<Proxy>()

        var currentSection: String? = null
        var currentTable: MutableMap<String, String> = linkedMapOf()

        fun flush() {
            val sec = currentSection ?: return
            if (sec.startsWith("proxies.")) {
                val pname = sec.removePrefix("proxies.")
                // frp 支持 [[proxies]] 数组写法，这里按 table 处理
                val type = currentTable["type"] ?: "tcp"
                val localPort = currentTable["localPort"]?.toIntOrNull()
                    ?: currentTable["local_port"]?.toIntOrNull() ?: 0
                if (localPort == 0) {
                    warnings += "代理 [$pname] 缺少 localPort，已跳过"
                } else {
                    val remote = currentTable["remotePort"]?.toIntOrNull()
                        ?: currentTable["remote_port"]?.toIntOrNull()
                    val domains = currentTable["customDomains"] ?: currentTable["custom_domains"] ?: ""
                    proxies += Proxy(
                        name = pname,
                        type = type,
                        localIp = currentTable["localIP"] ?: currentTable["local_ip"] ?: "127.0.0.1",
                        localPort = localPort,
                        remotePort = remote,
                        customDomains = domains.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        secretKey = currentTable["secretKey"] ?: currentTable["secret_key"],
                        group = currentTable["group"],
                        extra = currentTable.filterKeys {
                            it !in setOf(
                                "type", "localIP", "local_ip", "localPort", "local_port",
                                "remotePort", "remote_port", "customDomains", "custom_domains",
                                "secretKey", "secret_key", "group"
                            )
                        }
                    )
                }
            }
        }

        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            // [[proxies]] 数组形式
            if (line.startsWith("[[")) {
                flush()
                currentSection = line.trim('[', ']')
                currentTable = linkedMapOf()
                return@forEach
            }
            if (line.startsWith("[")) {
                flush()
                currentSection = line.trim('[', ']')
                currentTable = linkedMapOf()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            // 去尾逗号（TOML 数组/内联表）
            value = value.removeSuffix(",")
            // 拆引号
            value = trimQuotes(value)

            when {
                currentSection == null || currentSection == "server" -> {
                    when (key) {
                        "serverAddr", "server_addr" -> serverAddr = value
                        "serverPort", "server_port" -> serverPort = value.toIntOrNull() ?: 7777
                        "auth.method", "method" -> authMethod = value
                        "auth.token", "token" -> authToken = value
                    }
                }
                currentSection == "auth" -> {
                    when (key) {
                        "method" -> authMethod = value
                        "token" -> authToken = value
                    }
                }
                else -> {
                    currentTable[key] = value
                }
            }
        }
        flush()

        if (serverAddr.isBlank()) warnings += "未找到 serverAddr，请手动填写 frps 服务器地址"
        if (proxies.isEmpty()) warnings += "未解析到任何代理（proxies）"

        return Imported(
            serverAddr = serverAddr,
            serverPort = serverPort,
            authMethod = authMethod,
            authToken = authToken,
            proxies = proxies,
            toml = normalizeToml(serverAddr, serverPort, authMethod, authToken, proxies),
            warnings = warnings,
            sourceFormat = "toml"
        )
    }

    /** 解析老式 ini 格式并转成 TOML */
    private fun parseIni(text: String): Imported {
        val warnings = mutableListOf<String>()
        var serverAddr = ""
        var serverPort = 7777
        var token: String? = null
        val proxies = mutableListOf<Proxy>()

        var current: String? = null
        var map = linkedMapOf<String, String>()

        fun flush() {
            val sec = current ?: return
            if (sec.lowercase() == "common") {
                serverAddr = map["server_addr"] ?: serverAddr
                serverPort = map["server_port"]?.toIntOrNull() ?: serverPort
                token = map["token"] ?: token
                return
            }
            val localPort = map["local_port"]?.toIntOrNull() ?: 0
            if (localPort == 0) {
                warnings += "[$sec] 缺少 local_port，已跳过"
                return
            }
            proxies += Proxy(
                name = sec,
                type = map["type"] ?: "tcp",
                localIp = map["local_ip"] ?: "127.0.0.1",
                localPort = localPort,
                remotePort = map["remote_port"]?.toIntOrNull(),
                customDomains = map["custom_domains"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                secretKey = map["sk"] ?: map["secret_key"],
                group = map["group"],
                extra = map.filterKeys { it !in setOf("local_port", "remote_port", "local_ip", "custom_domains", "sk", "secret_key") }
            )
        }

        text.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                current = line.substring(1, line.length - 1).trim()
                map = linkedMapOf()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            map[line.substring(0, eq).trim()] = trimQuotes(line.substring(eq + 1).trim())
        }
        flush()

        if (serverAddr.isBlank()) warnings += "未找到 server_addr，请手动填写 frps 服务器地址"
        if (proxies.isEmpty()) warnings += "未解析到任何代理段落"

        return Imported(
            serverAddr = serverAddr,
            serverPort = serverPort,
            authMethod = "token",
            authToken = token,
            proxies = proxies,
            toml = normalizeToml(serverAddr, serverPort, "token", token, proxies),
            warnings = warnings,
            sourceFormat = "ini"
        )
    }

    // ---------------- 输出 ----------------

    /** 生成规范 TOML */
    fun normalizeToml(
        serverAddr: String,
        serverPort: Int,
        authMethod: String,
        authToken: String?,
        proxies: List<Proxy>
    ): String = buildString {
        appendLine("# 由 QuickSite 导入/生成")
        appendLine("serverAddr = \"$serverAddr\"")
        appendLine("serverPort = $serverPort")
        appendLine()
        appendLine("[auth]")
        appendLine("method = \"$authMethod\"")
        if (!authToken.isNullOrBlank()) {
            appendLine("token = \"$authToken\"")
        }
        proxies.forEach { p ->
            appendLine()
            appendLine("[[proxies]]")
            appendLine("name = \"${p.name}\"")
            appendLine("type = \"${p.type}\"")
            appendLine("localIP = \"${p.localIp}\"")
            appendLine("localPort = ${p.localPort}")
            p.remotePort?.let { appendLine("remotePort = $it") }
            if (p.customDomains.isNotEmpty()) {
                appendLine("customDomains = [${p.customDomains.joinToString(", ") { "\"$it\"" }}]")
            }
            p.secretKey?.let { appendLine("secretKey = \"$it\"") }
            p.group?.let { appendLine("group = \"$it\"") }
            p.extra.forEach { (k, v) ->
                appendLine("$k = \"$v\"")
            }
        }
    }

    /** 写入 frpc 配置到应用目录 */
    fun write(ctx: Context, imported: Imported): File {
        val f = Paths.frpcConf(ctx)
        f.parentFile?.mkdirs()
        f.writeText(imported.toml)
        return f
    }

    /** 从文件导入 */
    fun importFile(ctx: Context, src: File): Imported {
        val text = src.readText()
        val imported = parse(text)
        write(ctx, imported)
        return imported
    }

    private fun trimQuotes(s: String): String {
        var v = s.trim()
        if (v.length >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length - 1)
        }
        return v
    }

    /** 检查端口冲突 */
    fun checkConflicts(imported: Imported, usedPorts: Set<Int>): List<String> {
        val out = mutableListOf<String>()
        imported.proxies.forEach { p ->
            p.remotePort?.let { rp ->
                if (rp in usedPorts) {
                    out += "代理「${p.name}」的远程端口 $rp 与本机已用端口冲突"
                }
            }
        }
        return out
    }
}
