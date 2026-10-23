package com.qk.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用配置持久化（SharedPreferences + JSON）。
 */
class AppConfig(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("quicksite", Context.MODE_PRIVATE)

    var autoStartNginx: Boolean
        get() = sp.getBoolean("auto_start_nginx", true)
        set(v) = sp.edit().putBoolean("auto_start_nginx", v).apply()

    var autoStartFrpc: Boolean
        get() = sp.getBoolean("auto_start_frpc", true)
        set(v) = sp.edit().putBoolean("auto_start_frpc", v).apply()

    var frpcEnabled: Boolean
        get() = sp.getBoolean("frpc_enabled", false)
        set(v) = sp.edit().putBoolean("frpc_enabled", v).apply()

    /** 已导入的 frp 代理列表（JSON 缓存，便于首页展示） */
    var frpcProxiesJson: String
        get() = sp.getString("frpc_proxies", "[]") ?: "[]"
        set(v) = sp.edit().putString("frpc_proxies", v).apply()

    fun frpcProxies(): List<FrpcConf.Proxy> {
        return try {
            val arr = JSONArray(frpcProxiesJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FrpcConf.Proxy(
                    name = o.optString("name"),
                    type = o.optString("type", "tcp"),
                    localIp = o.optString("localIp", "127.0.0.1"),
                    localPort = o.optInt("localPort"),
                    remotePort = if (o.has("remotePort")) o.optInt("remotePort") else null,
                    customDomains = o.optJSONArray("customDomains")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                    secretKey = if (o.has("secretKey")) o.optString("secretKey") else null,
                    group = if (o.has("group")) o.optString("group") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFrpcProxies(list: List<FrpcConf.Proxy>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("type", p.type)
                put("localIp", p.localIp)
                put("localPort", p.localPort)
                p.remotePort?.let { put("remotePort", it) }
                put("customDomains", JSONArray(p.customDomains))
                p.secretKey?.let { put("secretKey", it) }
                p.group?.let { put("group", it) }
            })
        }
        frpcProxiesJson = arr.toString()
    }

    /** 站点列表（JSON） */
    var sitesJson: String
        get() = sp.getString("sites", "[]") ?: "[]"
        set(v) = sp.edit().putString("sites", v).apply()

    fun sites(): List<NginxConf.Site> {
        return try {
            val arr = JSONArray(sitesJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NginxConf.Site(
                    name = o.optString("name"),
                    type = if (o.optString("type", "static") == "proxy") NginxConf.Site.Type.PROXY
                    else NginxConf.Site.Type.STATIC,
                    port = o.optInt("port"),
                    root = if (o.has("root")) o.optString("root") else null,
                    proxyPass = if (o.has("proxyPass")) o.optString("proxyPass") else null,
                    serverName = o.optString("serverName", "localhost"),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSites(list: List<NginxConf.Site>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("type", if (s.type == NginxConf.Site.Type.PROXY) "proxy" else "static")
                put("port", s.port)
                s.root?.let { put("root", it) }
                s.proxyPass?.let { put("proxyPass", it) }
                put("serverName", s.serverName)
                put("enabled", s.enabled)
            })
        }
        sitesJson = arr.toString()
    }

    var frpServerAddr: String
        get() = sp.getString("frp_server_addr", "") ?: ""
        set(v) = sp.edit().putString("frp_server_addr", v).apply()

    var frpServerPort: Int
        get() = sp.getInt("frp_server_port", 7777)
        set(v) = sp.edit().putInt("frp_server_port", v).apply()

    var frpAuthToken: String
        get() = sp.getString("frp_auth_token", "") ?: ""
        set(v) = sp.edit().putString("frp_auth_token", v).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run_done", false)
        set(v) = sp.edit().putBoolean("first_run_done", v).apply()
}
