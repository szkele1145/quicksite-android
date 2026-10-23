package com.qk.app.ui

import com.qk.app.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.qk.app.core.AppConfig
import com.qk.app.core.FrpcConf
import com.qk.app.core.Paths
import com.qk.app.core.ServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * frpc 模块：一键导入配置文件。
 *
 * 支持从文件管理器选 .toml/.ini，或在输入框里直接粘贴配置内容。
 * 导入后自动解析、转成标准 TOML、写入应用目录，并展示解析结果。
 */
class FrpcActivity : AppCompatActivity() {

    private lateinit var cfg: AppConfig
    private lateinit var svc: ServiceManager

    /** 文件选择：*.toml / *.ini / *.* */
    private val pickConfig = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { importFromUri(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frpc)

        cfg = AppConfig(this)
        svc = ServiceManager(this)

        findViewById<View>(R.id.btnPickFile).setOnClickListener {
            pickConfig.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.btnPaste).setOnClickListener { showPasteDialog() }
        findViewById<View>(R.id.btnSaveServer).setOnClickListener { saveServerManual() }
        findViewById<View>(R.id.btnStartFrpc).setOnClickListener { toggleFrpc() }
        findViewById<View>(R.id.btnViewConf).setOnClickListener { showConfDialog() }

        loadState()
    }

    override fun onResume() {
        super.onResume()
        loadState()
    }

    private fun loadState() {
        val etAddr: EditText = findViewById(R.id.etServerAddr)
        val etPort: EditText = findViewById(R.id.etServerPort)
        val etToken: EditText = findViewById(R.id.etAuthToken)

        if (etAddr.text.isNullOrBlank()) etAddr.setText(cfg.frpServerAddr)
        if (etPort.text.isNullOrBlank()) etPort.setText(cfg.frpServerPort.toString())
        if (etToken.text.isNullOrBlank()) etToken.setText(cfg.frpAuthToken)

        val tvStatus: TextView = findViewById(R.id.tvFrpcStatus)
        val running = svc.frpcRunning()
        tvStatus.text = if (running) "frpc 运行中" else "frpc 已停止"
        findViewById<View>(R.id.btnStartFrpc).let {
            (it as android.widget.Button).text = if (running) "停止 frpc" else "启动 frpc"
        }

        renderProxies()
    }

    private fun renderProxies() {
        val tv: TextView = findViewById(R.id.tvProxyList)
        val proxies = cfg.frpcProxies()
        tv.text = if (proxies.isEmpty()) {
            "尚未导入任何代理。点「选择配置文件」一键导入，或「粘贴配置内容」。"
        } else {
            buildString {
                append("已导入 ${proxies.size} 个代理：\n")
                proxies.forEach { p ->
                    append("• ${p.name}  [${p.type}]  ")
                    append("${p.localIp}:${p.localPort}")
                    p.remotePort?.let { append("  →  远程 $it") }
                    if (p.customDomains.isNotEmpty()) {
                        append("  →  ${p.customDomains.joinToString(",")}")
                    }
                    append("\n")
                }
            }
        }
    }

    // ---------------- 导入 ----------------

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // 先把文件拷进应用私有目录，避免权限/临时文件问题
                    val cache = File(cacheDir, "import_frpc.conf")
                    contentResolver.openInputStream(uri)?.use { input ->
                        cache.outputStream().use { input.copyTo(it) }
                    } ?: return@withContext null

                    val text = cache.readText()
                    applyImported(FrpcConf.parse(text), text, cache.length())
                } catch (e: Exception) {
                    runOnUiThread { toast("导入失败：${e.message}") }
                    null
                }
            }
            result?.let { showImportResult(it) }
        }
    }

    private fun applyImported(imported: FrpcConf.Imported, text: String, byteSize: Long): FrpcConf.Imported {
        // 写入配置
        FrpcConf.write(this, imported)
        // 回填服务器信息
        cfg.frpServerAddr = imported.serverAddr
        cfg.frpServerPort = imported.serverPort
        cfg.frpAuthToken = imported.authToken ?: ""
        cfg.saveFrpcProxies(imported.proxies)
        cfg.frpcEnabled = true
        // 备份原始文本，便于排查
        File(Paths.confDir(this), "frpc.imported.raw").writeText(text)
        return imported
    }

    private fun showImportResult(imported: FrpcConf.Imported) {
        runOnUiThread {
            loadState()
            val sb = StringBuilder()
            sb.appendLine("来源格式：${if (imported.sourceFormat == "ini") "INI（已自动转 TOML）" else "TOML"}")
            sb.appendLine("服务器：${imported.serverAddr}:${imported.serverPort}")
            sb.appendLine("认证：${imported.authMethod}${if (!imported.authToken.isNullOrBlank()) "（已带 token）" else ""}")
            sb.appendLine("代理数：${imported.proxies.size}")
            if (imported.warnings.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("提示：")
                imported.warnings.forEach { sb.appendLine("• $it") }
            }
            sb.appendLine()
            sb.append("配置已写入，可直接启动 frpc。")

            AlertDialog.Builder(this)
                .setTitle("导入成功")
                .setMessage(sb.toString())
                .setPositiveButton("启动 frpc") { _, _ -> startFrpc() }
                .setNegativeButton("稍后", null)
                .show()
        }
    }

    /** 粘贴配置文本导入 */
    private fun showPasteDialog() {
        val et = EditText(this).apply {
            hint = "在此粘贴 frpc.toml 或 frpc.ini 内容"
            minLines = 8
            maxLines = 16
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("粘贴 frpc 配置")
            .setView(et)
            .setPositiveButton("导入") { _, _ ->
                val text = et.text.toString()
                if (text.isBlank()) {
                    toast("内容为空")
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val imported = withContext(Dispatchers.IO) {
                        runCatching { applyImported(FrpcConf.parse(text), text, text.length.toLong()) }.getOrNull()
                    }
                    if (imported == null) toast("解析失败，请检查配置格式") else showImportResult(imported)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 手动服务器信息 ----------------

    private fun saveServerManual() {
        val etAddr: EditText = findViewById(R.id.etServerAddr)
        val etPort: EditText = findViewById(R.id.etServerPort)
        val etToken: EditText = findViewById(R.id.etAuthToken)

        val addr = etAddr.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 7777
        if (addr.isBlank()) {
            toast("请填写服务器地址")
            return
        }
        cfg.frpServerAddr = addr
        cfg.frpServerPort = port
        cfg.frpAuthToken = etToken.text.toString().trim()

        // 若已有代理，按新服务器信息重写 TOML
        val proxies = cfg.frpcProxies()
        val toml = FrpcConf.normalizeToml(addr, port, "token", cfg.frpAuthToken, proxies)
        Paths.frpcConf(this).writeText(toml)

        toast("已保存服务器信息")
    }

    // ---------------- 启停 ----------------

    private fun toggleFrpc() {
        if (svc.frpcRunning()) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { svc.stopFrpc() }
                loadState()
            }
        } else {
            startFrpc()
        }
    }

    private fun startFrpc() {
        // 启动前用最新输入框内容刷新配置，避免用户改完没保存
        val etAddr: EditText = findViewById(R.id.etServerAddr)
        val etPort: EditText = findViewById(R.id.etServerPort)
        val etToken: EditText = findViewById(R.id.etAuthToken)
        val addr = etAddr.text.toString().trim()
        if (addr.isNotBlank()) {
            saveServerManual()
        }
        cfg.frpcEnabled = true

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { svc.startFrpc() }
            toast(r.output.trim().take(300))
            loadState()
        }
    }

    private fun showConfDialog() {
        val f = Paths.frpcConf(this)
        val content = if (f.exists()) f.readText() else "（还没有配置文件）"
        AlertDialog.Builder(this)
            .setTitle("当前 frpc.toml")
            .setMessage(content)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
