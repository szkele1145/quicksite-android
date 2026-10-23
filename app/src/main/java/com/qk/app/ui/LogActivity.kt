package com.qk.app.ui

import com.qk.app.R

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qk.app.core.Paths
import com.qk.app.core.ServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志中心：nginx access/error、frpc 日志，刷新查看。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var svc: ServiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        svc = ServiceManager(this)
        tv = findViewById(R.id.tvLog)

        findViewById<View>(R.id.btnRefresh).setOnClickListener { load() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { collectLogs() }
            tv.text = text.ifBlank { "（暂无日志）" }
        }
    }

    private suspend fun collectLogs(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val logDir = Paths.logDir(this@LogActivity)
        val nginxLog = File(Paths.usr(this@LogActivity), "var/log/nginx")
        listOf(
            "frpc" to File(logDir, "frpc.log"),
            "nginx-error" to File(nginxLog, "error.log"),
            "nginx-access" to File(nginxLog, "access.log")
        ).forEach { (name, f) ->
            sb.appendLine("════ $name ════")
            if (f.exists()) {
                val lines = f.readLines()
                val tail = if (lines.size > 120) lines.takeLast(120) else lines
                sb.appendLine(tail.joinToString("\n"))
            } else {
                sb.appendLine("（无）")
            }
            sb.appendLine()
        }
        sb.toString()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空日志")
            .setMessage("清空 frpc 与 nginx 日志文件？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        File(Paths.logDir(this@LogActivity), "frpc.log").delete()
                        val nl = File(Paths.usr(this@LogActivity), "var/log/nginx")
                        File(nl, "error.log").writeText("")
                        File(nl, "access.log").writeText("")
                    }
                    load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
