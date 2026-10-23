package com.qk.app.ui

import com.qk.app.R

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qk.app.core.Paths
import com.qk.app.core.ServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Node 应用管理：选择项目目录 + 入口脚本，用 pm2 拉起。
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var svc: ServiceManager
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        svc = ServiceManager(this)

        val list: RecyclerView = findViewById(R.id.appList)
        adapter = AppAdapter(loadApps()) { app, action ->
            when (action) {
                "start" -> doPm2(app, "start")
                "stop" -> doPm2(app, "stop")
                "logs" -> showLogs(app)
                "delete" -> confirmDelete(app)
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnAddApp).setOnClickListener { showAddDialog() }
    }

    private fun loadApps(): List<AppEntry> {
        val f = java.io.File(Paths.confDir(this), "apps.json")
        if (!f.exists()) return emptyList()
        return try {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AppEntry(o.getString("name"), o.getString("script"), o.optString("args"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveApps(apps: List<AppEntry>) {
        val arr = org.json.JSONArray()
        apps.forEach { a ->
            arr.put(org.json.JSONObject().apply {
                put("name", a.name); put("script", a.script); put("args", a.args)
            })
        }
        java.io.File(Paths.confDir(this), "apps.json").writeText(arr.toString())
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_app, null)
        val etName: android.widget.EditText = view.findViewById(R.id.etAppName)
        val etScript: android.widget.EditText = view.findViewById(R.id.etAppScript)
        val etArgs: android.widget.EditText = view.findViewById(R.id.etAppArgs)
        etName.hint = "如 my-api"
        etScript.hint = "如 server.js（相对 projects 目录）"

        AlertDialog.Builder(this)
            .setTitle("添加 Node 应用")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val script = etScript.text.toString().trim()
                if (name.isBlank() || script.isBlank()) return@setPositiveButton
                val apps = loadApps().toMutableList()
                apps.removeAll { it.name == name }
                apps.add(AppEntry(name, script, etArgs.text.toString().trim()))
                saveApps(apps)
                adapter.reload(apps)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doPm2(app: AppEntry, action: String) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                if (action == "start") svc.pm2Start(app.name, app.script, app.args)
                else svc.pm2Stop(app.name)
            }
            android.widget.Toast.makeText(this@AppsActivity, r.output.trim().take(200), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun showLogs(app: AppEntry) {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { svc.pm2Logs(app.name, 80) }
            AlertDialog.Builder(this@AppsActivity)
                .setTitle("${app.name} 日志")
                .setMessage(r.output.ifBlank { "（暂无日志）" })
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun confirmDelete(app: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle("删除应用")
            .setMessage("从列表移除「${app.name}」？pm2 进程也会停止。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { svc.pm2Stop(app.name) }
                    val apps = loadApps().toMutableList()
                    apps.removeAll { it.name == app.name }
                    saveApps(apps)
                    adapter.reload(apps)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    data class AppEntry(val name: String, val script: String, val args: String)

    inner class AppAdapter(
        private var apps: List<AppEntry>,
        private val onAction: (AppEntry, String) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.Holder>() {

        fun reload(newApps: List<AppEntry>) {
            apps = newApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = apps.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val a = apps[position]
            val v = holder.itemView
            v.findViewById<TextView>(R.id.tvAppName).text = a.name
            v.findViewById<TextView>(R.id.tvAppInfo).text = "${a.script}${if (a.args.isNotBlank()) " $a.args" else ""}"
            v.findViewById<View>(R.id.btnStart).setOnClickListener { onAction(a, "start") }
            v.findViewById<View>(R.id.btnStop).setOnClickListener { onAction(a, "stop") }
            v.findViewById<View>(R.id.btnLogs).setOnClickListener { onAction(a, "logs") }
            v.findViewById<View>(R.id.btnDelete).setOnClickListener { onAction(a, "delete") }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v)
    }
}
