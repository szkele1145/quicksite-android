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
import com.qk.app.core.SelfTest
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
        findViewById<View>(R.id.cardConsole).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }

        // 长按状态卡片 → 跑一次环境自检（真机上最直接的诊断入口）
        findViewById<View>(R.id.statusCard).setOnLongClickListener {
            runSelfTest()
            true
        }

        refreshState()
    }

    /** 跑环境自检并弹窗展示结果。 */
    private fun runSelfTest() {
        if (!Paths.isInstalled(this)) {
            statusText.text = "环境未安装，先点上面的「安装运行环境」"
            return
        }
        statusText.text = "自检中…"
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { SelfTest.run(this@MainActivity) }
            statusText.text = "自检：${report.summary()}"
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("环境自检")
                .setMessage(SelfTest.format(report))
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("QuickSite 自检", SelfTest.format(report))
                    )
                    toast("已复制，可直接粘贴反馈")
                }
                .show()
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

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
                append("手机内访问：http://127.0.0.1:${ports.first()}")
                val lan = localIpv4()
                if (lan != null) {
                    append("\n局域网访问：http://$lan:${ports.first()}")
                }
            } else {
                append("尚未创建站点，点下方「网站」一键创建默认站点。")
            }
            if (cfg.frpcEnabled && cfg.frpServerAddr.isNotBlank()) {
                append("\n穿透服务器：${cfg.frpServerAddr}:${cfg.frpServerPort}")
            }
        }

        btnStart.isEnabled = !nginxUp
        btnStop.isEnabled = nginxUp || frpcUp
    }

    /** 取本机局域网 IPv4（wlan0 优先），用于展示可被同网设备访问的地址。 */
    private fun localIpv4(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
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
                statusText.text = "环境安装完成，正在自检…"
                refreshState()
                runSelfTest()
            } else {
                statusText.text = "环境安装失败，请查看日志（长按状态卡片可重试自检）"
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
            // 起来之后开启前台保活，避免系统回收
            if (svc.nginxRunning() || svc.frpcRunning()) {
                com.qk.app.core.KeepAliveService.start(this@MainActivity)
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
            com.qk.app.core.KeepAliveService.stop(this@MainActivity)
            refreshState()
        }
    }
}
