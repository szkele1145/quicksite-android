package com.qk.app.core

import android.content.Context
import java.io.File

/**
 * 环境自检：装完之后用一组真实命令验证运行环境是否可用。
 *
 * 这一组检查是「能不能跑起来」的最短证据链：
 *   bash 可执行 → 基础命令齐全 → nginx / node / pm2 / frpc 版本可读
 * 任何一项失败都能直接定位问题层（权限 / 符号链接 / 动态库 / 前缀替换）。
 */
object SelfTest {

    data class Item(val name: String, val ok: Boolean, val detail: String)

    data class Report(val items: List<Item>, val passed: Int, val total: Int) {
        val allOk: Boolean get() = passed == total
        fun summary(): String = when {
            allOk -> "全部通过（$passed/$total）"
            passed == 0 -> "全部失败（0/$total），环境可能未安装"
            else -> "部分失败（$passed/$total）"
        }
    }

    fun run(ctx: Context): Report {
        val shell = Shell(ctx)
        val items = mutableListOf<Item>()

        if (!Paths.isInstalled(ctx)) {
            return Report(
                listOf(Item("环境安装", false, "未安装，请先点「安装运行环境」")),
                0, 1
            )
        }

        // 1. bash 是否能执行
        val bash = File(Paths.usr(ctx), "bin/bash")
        items += if (!bash.exists()) {
            Item("bash 存在", false, "缺少 ${bash.absolutePath}")
        } else if (!bash.canExecute()) {
            Item("bash 可执行", false, "权限不足（应 0755，实际不可执行）")
        } else {
            val r = shell.run("echo quicksite-ok", 20)
            Item(
                "bash 可执行",
                r.ok && r.output.contains("quicksite-ok"),
                if (r.ok) "OK" else "退出码 ${r.exitCode}：${r.output.take(160)}"
            )
        }

        // 2. 基础命令（coreutils 多调用链接是否建好）
        val missing = listOf("ls", "cp", "mv", "rm", "cat", "sed", "grep", "tar", "env", "sh")
            .filter { !File(Paths.usr(ctx), "bin/$it").exists() }
        items += Item(
            "基础命令",
            missing.isEmpty(),
            if (missing.isEmpty()) "10/10 就绪" else "缺少：${missing.joinToString(", ")}"
        )

        // 3. nginx
        items += version(shell, "nginx", "\"\${'$'}PREFIX/bin/nginx\" -v", 20)

        // 4. node
        items += version(shell, "node", "\"\${'$'}PREFIX/bin/node\" -v", 25)

        // 5. npm / pm2（依赖 node，失败不算致命）
        items += version(shell, "npm", "\"\${'$'}PREFIX/bin/npm\" -v", 40)
        items += version(shell, "pm2", "\"\${'$'}PREFIX/bin/pm2\" -v", 60)

        // 6. frpc
        items += version(shell, "frpc", "\"\${'$'}PREFIX/bin/frpc\" -v", 20)

        // 7. 站点配置文件是否可生成
        val sites = Paths.nginxSites(ctx).listFiles { f -> f.name.endsWith(".conf") }?.size ?: 0
        items += Item("站点配置", sites > 0, if (sites > 0) "$sites 个站点" else "还没有站点（可到「网站」新建）")

        val passed = items.count { it.ok }
        return Report(items, passed, items.size)
    }

    private fun version(shell: Shell, name: String, cmd: String, timeout: Long): Item {
        val r = shell.run(cmd, timeout)
        val firstLine = r.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return if (r.ok && firstLine.isNotEmpty()) {
            Item(name, true, firstLine.take(70))
        } else {
            Item(
                name, false,
                "退出码 ${r.exitCode}" + (if (r.timedOut) "（超时）" else "") +
                    (if (firstLine.isNotEmpty()) "：${firstLine.take(90)}" else "")
            )
        }
    }

    fun format(report: Report): String = buildString {
        appendLine(report.summary())
        appendLine()
        report.items.forEach { i ->
            append(if (i.ok) "  ✓ " else "  ✗ ")
            append(i.name)
            if (i.detail.isNotBlank()) {
                append("  ")
                append(i.detail)
            }
            appendLine()
        }
    }
}
