package com.qk.app.ui

import com.qk.app.R

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.qk.app.core.AppConfig
import com.qk.app.core.NginxConf
import com.qk.app.core.Paths
import com.qk.app.core.ServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 站点管理：增删改 nginx 站点（静态 / 反向代理），保存即生成配置并热重载。
 */
class SitesActivity : AppCompatActivity() {

    private lateinit var cfg: AppConfig
    private lateinit var svc: ServiceManager
    private lateinit var adapter: SiteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sites)

        cfg = AppConfig(this)
        svc = ServiceManager(this)

        val list: RecyclerView = findViewById(R.id.siteList)
        adapter = SiteAdapter(cfg.sites()) { position, action ->
            when (action) {
                "edit" -> showEditDialog(position)
                "delete" -> confirmDelete(position)
                "open" -> openSite(position)
                "index" -> createIndexFor(position)
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnAddSite).setOnClickListener { showEditDialog(-1) }
        findViewById<View>(R.id.btnStartNginx).setOnClickListener {
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { svc.startNginx() }
                Toast.makeText(this@SitesActivity, r.output.trim(), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.reload(cfg.sites())
    }

    /** 新建（position<0）或编辑站点。 */
    private fun showEditDialog(position: Int) {
        val isNew = position < 0
        val sites = cfg.sites()
        val existing = if (isNew) null else sites.getOrNull(position)

        val view = layoutInflater.inflate(R.layout.dialog_site, null)
        val etName: EditText = view.findViewById(R.id.etSiteName)
        val etPort: EditText = view.findViewById(R.id.etSitePort)
        val tgType: MaterialButtonToggleGroup = view.findViewById(R.id.tgSiteType)
        val etRoot: EditText = view.findViewById(R.id.etSiteRoot)
        val etProxy: EditText = view.findViewById(R.id.etSiteProxy)
        val etServerName: EditText = view.findViewById(R.id.etServerName)

        // 默认值：懒人配置——端口自动往后排
        etPort.setText(
            (existing?.port ?: suggestPort(sites)).toString()
        )

        existing?.let {
            etName.setText(it.name)
            if (it.type == NginxConf.Site.Type.PROXY) {
                tgType.check(R.id.btnTypeProxy)
                etProxy.setText(it.proxyPass ?: "")
                etRoot.visibility = View.GONE
            } else {
                tgType.check(R.id.btnTypeStatic)
                etRoot.setText(it.root ?: "")
                etProxy.visibility = View.GONE
            }
            etServerName.setText(it.serverName)
        }

        tgType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val proxy = checkedId == R.id.btnTypeProxy
                etRoot.visibility = if (proxy) View.GONE else View.VISIBLE
                etProxy.visibility = if (proxy) View.VISIBLE else View.GONE
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (isNew) "新建站点" else "编辑站点")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim().replace(Regex("[^a-zA-Z0-9_-]"), "-")
                if (name.isEmpty()) {
                    toast("站点名不能为空"); return@setPositiveButton
                }
                val port = etPort.text.toString().toIntOrNull()
                if (port == null || port !in 1..65535) {
                    toast("端口无效（1-65535）"); return@setPositiveButton
                }
                val isProxy = tgType.checkedButtonId == R.id.btnTypeProxy
                val site = NginxConf.Site(
                    name = name,
                    type = if (isProxy) NginxConf.Site.Type.PROXY else NginxConf.Site.Type.STATIC,
                    port = port,
                    root = if (!isProxy) {
                        etRoot.text.toString().ifBlank { "var/www/$name" }
                    } else null,
                    proxyPass = if (isProxy) {
                        etProxy.text.toString().ifBlank { "http://127.0.0.1:3000" }
                    } else null,
                    serverName = etServerName.text.toString().ifBlank { "localhost" }
                )

                val updated = cfg.sites().toMutableList()
                if (isNew) {
                    if (updated.any { it.name == name }) {
                        toast("站点名已存在"); return@setPositiveButton
                    }
                    updated.add(site)
                } else {
                    if (updated.indexOfFirst { it.name == name } !in listOf(-1, position)) {
                        toast("站点名已存在"); return@setPositiveButton
                    }
                    updated[position] = site
                }
                cfg.saveSites(updated)
                NginxConf.writeSite(this, site)
                if (site.type == NginxConf.Site.Type.STATIC) {
                    NginxConf.writeDefaultIndex(this, name)
                }
                adapter.reload(cfg.sites())
                lifecycleScope.launch {
                    val r = withContext(Dispatchers.IO) { svc.reloadNginx() }
                    toast(if (r.output.contains("error", true)) "已保存（nginx 重载有告警）" else "已保存并生效")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun suggestPort(sites: List<NginxConf.Site>): Int {
        var p = 8080
        val used = sites.map { it.port }.toSet()
        while (p in used) p++
        return p
    }

    private fun confirmDelete(position: Int) {
        val site = cfg.sites().getOrNull(position) ?: return
        AlertDialog.Builder(this)
            .setTitle("删除站点")
            .setMessage("确定删除「${site.name}」？对应 nginx 配置会被移除。")
            .setPositiveButton("删除") { _, _ ->
                val updated = cfg.sites().toMutableList()
                updated.removeAt(position)
                cfg.saveSites(updated)
                NginxConf.removeSite(this, site.name)
                adapter.reload(cfg.sites())
                lifecycleScope.launch { withContext(Dispatchers.IO) { svc.reloadNginx() } }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSite(position: Int) {
        val site = cfg.sites().getOrNull(position) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${site.port}/")))
        } catch (e: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun createIndexFor(position: Int) {
        val site = cfg.sites().getOrNull(position) ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                NginxConf.writeDefaultIndex(this@SitesActivity, site.name, if (site.type == NginxConf.Site.Type.STATIC) site.root else null)
            }
            toast("已生成默认首页")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- 列表 ----------------

    inner class SiteAdapter(
        private var sites: List<NginxConf.Site>,
        private val onAction: (Int, String) -> Unit
    ) : RecyclerView.Adapter<SiteAdapter.Holder>() {

        fun reload(newSites: List<NginxConf.Site>) {
            sites = newSites
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_site, parent, false))

        override fun getItemCount(): Int = sites.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val s = sites[position]
            val v = holder.itemView
            v.findViewById<TextView>(R.id.tvSiteName).text = s.name
            v.findViewById<TextView>(R.id.tvSiteInfo).text = buildString {
                append(if (s.type == NginxConf.Site.Type.STATIC) "静态" else "反向代理")
                append(" · 端口 ")
                append(s.port)
                if (s.type == NginxConf.Site.Type.PROXY && !s.proxyPass.isNullOrBlank()) {
                    append(" → ")
                    append(s.proxyPass)
                }
            }
            v.findViewById<View>(R.id.btnEdit).setOnClickListener { onAction(position, "edit") }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener { onAction(position, "delete") }
            v.findViewById<View>(R.id.btnOpen).setOnClickListener { onAction(position, "open") }
            v.findViewById<View>(R.id.btnIndex).setOnClickListener { onAction(position, "index") }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v)
    }
}
