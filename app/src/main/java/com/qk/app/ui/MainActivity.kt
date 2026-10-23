package com.qk.app.ui

import com.qk.app.R

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qk.app.core.AppConfig
import com.qk.app.core.EnvInstaller
import com.qk.app.core.Paths
import com.qk.app.core.ServiceManager
import com.qk.app.core.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：懒人配置的核心 —— 一键启动。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var svc: ServiceManager
    private lateinit var cfg: AppConfig
    private lateinit var shell: Shell

    private lateinit var installCard: View
    private lateinit var progressBar: View
    private lateinit var statusText: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var infoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        svc = ServiceManager(this)
        cfg = AppConfig(this)
        shell = Shell(this)

        installCard = findViewById(R.id.installCard)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        btnInstall = findViewById(R.id.btnInstall)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        infoText = findViewById(R.id.infoText)

        btnInstall.setOnClickListener { doInstall() }
        btnStart.setOnClickListener { doStart() }
        btnStop.setOnClickListener { doStop() }

        findViewById<View>(R.id.cardSites).setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
        }
        findViewById<View>(R.id.cardFrpc).setOnClickListener {
            startActivity(Intent(this, FrpcActivity::class.java))
        }
        findViewById<View>(R.id.cardApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<View>(R.id.cardLogs).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        val installed = Paths.isInstalled(this)
        installCard.visibility = if (installed) View.GONE else View.VISIBLE
        btnInstall.visibility = if (installed) View.GONE else View.VISIBLE

        if (!installed) {
            statusText.text = "运行环境未安装"
            btnStart.isEnabled = false
            btnStop.isEnabled = false
            return
        }

        val nginxUp = svc.nginxRunning()
        val frpcUp = svc.frpcRunning()
        val ports = svc.nginxPorts()

        statusText.text = buildString {
            append("nginx：")
            append(if (nginxUp) "运行中" else "已停止")
            append("    frpc：")
            append(if (frpcUp) "运行中" else "已停止")
        }

        infoText.text = buildString {
            if (ports.isNotEmpty()) {
                append("本机站点端口：")
                append(ports.joinToString(", "))
                append("\n")
                append("手机内访问：http://127.0.0.1:${ports.first()}\n")
                append("局域网访问：http://${android.net.Uri.encode("")}" )
            } else {
                append("尚未创建站点，点下方「网站」一键创建默认站点。")
            }
        }

        btnStart.isEnabled = !nginxUp
        btnStop.isEnabled = nginxUp || frpcUp
    }

    private fun doInstall() {
        btnInstall.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.text = "正在安装运行环境…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                EnvInstaller.install(this@MainActivity) { p ->
                    runOnUiThread {
                        statusText.text = "${p.phase}${if (p.detail.isNotBlank()) "：${p.detail}" else ""}"
                    }
                }
            }
            progressBar.visibility = View.GONE
            btnInstall.isEnabled = true
            if (ok) {
                statusText.text = "环境安装完成"
                refreshState()
            } else {
                statusText.text = "环境安装失败，请查看日志"
            }
        }
    }

    private fun doStart() {
        statusText.text = "启动中…"
        btnStart.isEnabled = false
        lifecycleScope.launch {
            val msg = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                val n = svc.startNginx()
                sb.appendLine(n.output)
                if (cfg.frpcEnabled && !svc.frpcRunning()) {
                    val f = svc.startFrpc()
                    sb.appendLine(f.output)
                }
                sb.toString()
            }
            statusText.text = msg.trim().ifBlank { "已启动" }
            refreshState()
        }
    }

    private fun doStop() {
        statusText.text = "停止中…"
        btnStop.isEnabled = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                svc.stopNginx()
                svc.stopFrpc()
            }
            refreshState()
        }
    }
}
