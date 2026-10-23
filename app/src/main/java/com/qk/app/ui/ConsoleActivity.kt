package com.qk.app.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qk.app.R
import com.qk.app.core.Paths
import com.qk.app.core.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内置命令行：把预置的 termux 环境开放给用户。
 *
 * 有了它，`apt install python` / `npm i -g xxx` / 排查问题都能自己来，
 * 不必等应用作者改代码。
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private lateinit var shell: Shell

    private val history = mutableListOf<String>()
    private var historyIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        shell = Shell(this)
        output = findViewById(R.id.tvConsole)
        input = findViewById(R.id.etConsole)
        scroll = findViewById(R.id.consoleScroll)
        output.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnRun).setOnClickListener { runCommand(input.text.toString()) }
        findViewById<Button>(R.id.btnHistoryUp).setOnClickListener { browseHistory(-1) }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            output.text = ""
            print(
                "QuickSite 控制台\n" +
                    "PREFIX = ${Paths.usr(this).absolutePath}\n" +
                    "提示：apt update && apt install <包名> 可安装 termux 仓库里的包\n"
            )
        }

        if (!Paths.isInstalled(this)) {
            print("运行环境尚未安装，请先回首页点「安装运行环境」。")
            input.isEnabled = false
        } else {
            print("QuickSite 控制台 — 输入命令后按「运行」\nPREFIX = ${Paths.usr(this).absolutePath}\n")
        }
    }

    private fun browseHistory(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(-1, history.size - 1)
        input.setText(if (historyIndex < 0) "" else history[historyIndex])
        input.setSelection(input.text.length)
    }

    private fun runCommand(cmdRaw: String) {
        val cmd = cmdRaw.trim()
        if (cmd.isEmpty()) return
        history.add(cmd)
        historyIndex = history.size
        input.setText("")

        print("\n$ $cmd")
        val btn = findViewById<Button>(R.id.btnRun)
        btn.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // sudo 前缀只是习惯写法，这里没有 root，直接去掉
                val clean = cmd.removePrefix("sudo ").trim()
                shell.run(clean, timeoutSec = 120)
            }
            val body = result.output.trimEnd()
            if (body.isNotEmpty()) print(body)
            if (result.timedOut) {
                print("[超时 120s，已终止]")
            } else if (result.exitCode != 0) {
                print("[退出码 ${result.exitCode}]")
            }
            btn.isEnabled = true
        }
    }

    private fun print(text: String) {
        output.append(text + "\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
