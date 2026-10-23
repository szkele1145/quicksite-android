# QuickSite 🚀

**安卓秒开网站**——把手机变成一台 Web 服务器。内置 nginx、pm2、frpc，装完即用，不用连电脑、不用敲命令、不用联网装依赖。

> 目标平台：Android 7.0 ~ Android 16（arm64-v8a）

---

## 一分钟上手

1. **装 APK**（允许「安装未知来源应用」）
2. 打开 App → 点 **「安装运行环境」**（只需一次，约 1 分钟）
3. 点 **「一键启动」** → nginx 起来了
4. 进 **「网站」** → 新建站点 → 浏览器访问 `http://127.0.0.1:8080`

内网穿透：**「frpc 内网穿透」** → 选配置文件 或 粘贴内容 → 启动。

---

## 功能

| 模块 | 说明 |
|------|------|
| **一键启动** | nginx / frpc 启停，后台保活服务（前台通知 + WakeLock） |
| **网站** | 静态站点 / 反向代理两种类型，端口自动分配，保存即热重载 |
| **frpc** | 一键导入 `toml` / 旧版 `ini` 配置，自动转换 + 规范化 + 冲突检查 |
| **Node 应用** | 用 pm2 托管 node 项目（启动 / 停止 / 看日志） |
| **日志** | nginx access/error + frpc 日志汇总，可一键清空 |

---

## 技术要点

### 1. 等长包名字节替换

termux bootstrap 里的二进制把 `/data/data/com.termux/files/usr` 硬编码进了 ELF 字符串表和 RUNPATH。本项目用 **`com.qk.app`** 作为包名——

```
/data/data/com.termux/files/usr   →  31 字符
/data/data/com.qk.app/files/usr   →  31 字符   ✅ 等长
```

于是可以在安装期对 338 个 ELF、130 处绝对路径做**等长字节替换**，无需重链接、无需改 ELF 结构。`Paths.kt` 里有 `require` 做等长断言，长度一旦不一致立刻崩溃。

### 2. targetSdk = 28 的执行豁免

Android 10+ 对应用私有目录有 W^X 限制（数据目录默认不可执行）。但 AOSP 的 `seapp_contexts` 里存在低 targetSdk 档位：

```
user=_app minTargetSdkVersion=28 domain=untrusted_app_27 type=app_data_file levelFrom=all
```

而 `untrusted_app_all.te`（android14/15/16 三个分支内容一致）含：

```
allow untrusted_app_all app_data_file:file { r_file_perms execute };
```

即 **targetSdk ≤ 28 的应用仍可从 app data dir 执行二进制**。同时 Android 15/16 要求最低安装 targetSdk 为 23/24，我们取 28 正好落在区间内 → **覆盖 Android 7~16**。

> 这是本项目能在手机上跑 nginx 的根本原因，已通过 AOSP 源码逐字节核实。

### 3. 全内置，零联网安装

- `assets/bootstrap.zip`（19.7 MB）：精简后的 termux bootstrap + **预置好的 nginx**（构建期解 deb 注入，不依赖运行时 `dpkg`）
- `assets/frpc.bin`（16 MB）：官方静态编译的 frp android_arm64

首次启动解包时做前缀替换，之后全程离线可用。

### 4. 自研 deb 解包器

`DebExtractor.kt` 纯 Kotlin 实现 ar → tar 解析，不依赖 `dpkg`/`ar`/`tar` 外部命令；符号链接走 `android.system.Os.symlink` 反射创建。

---

## 项目结构

```
app/src/main/java/com/qk/app/
├── core/
│   ├── Paths.kt            目录布局 + 前缀等长断言
│   ├── PrefixPatcher.kt    等长字节替换（zip 目录穿越防护）
│   ├── DebExtractor.kt     纯 Kotlin deb 解包
│   ├── EnvInstaller.kt     环境安装（解包+替换+符号链接+默认配置）
│   ├── Shell.kt            bash 执行器 + 环境变量注入
│   ├── ServiceManager.kt   nginx / frpc / pm2 启停
│   ├── NginxConf.kt        nginx 配置生成
│   ├── FrpcConf.kt         frp 配置解析（toml / ini）
│   ├── AppConfig.kt        配置持久化
│   └── KeepAliveService.kt 前台保活服务
└── ui/                     MainActivity / Sites / Frpc / Apps / Log

scripts/
├── build-assets.ps1        Windows 版 assets 构建
├── build-assets.sh         CI 版 assets 构建
├── deb_extract.py          deb 解包
├── tar_extract.py          tar.gz 提取
├── zip_tree.py             目录打包
└── gen-icon.py             纯 Python 生成图标 PNG
```

---

## 本地构建

```powershell
# 需要 JDK 17 + Android SDK 34
.\gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

assets 重建：

```powershell
.\scripts\build-assets.ps1
python scripts\gen-icon.py app\src\main\res
```

## 云端构建

推送 `v*` 标签触发 GitHub Actions：

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow 会自装 JDK 17 + SDK 34、拉取 bootstrap/frp/nginx、构建并发布 Release。

---

## frpc 配置导入

支持两种格式，导入时自动解析并规范化：

**TOML（frp 官方）**
```toml
serverAddr = "frps.example.com"
serverPort = 7000

[auth]
method = "token"
token = "your-token"

[[proxies]]
name = "web"
type = "tcp"
localIP = "127.0.0.1"
localPort = 8080
remotePort = 8080
```

**INI（frp v0.4x 老格式）** — 自动转成 TOML：
```ini
[common]
server_addr = frps.example.com
server_port = 7000
token = your-token

[web]
type = tcp
local_port = 8080
remote_port = 8080
```

---

## 签名

仓库内自带 keystore（`keystore/quicksite.keystore`）：

- alias：`quicksite`
- storepass / keypass：`quicksite2026`

> 仅用于让 CI 能直接产出可安装包。正式发布请换成自己的 keystore 并从仓库移除。

---

## 已知限制

- 仅 **arm64-v8a**（现代安卓手机通用；老设备需另出 armeabi-v7a 包）
- 未预置 pm2 / nodejs 运行时，需要 Node 功能时目前只走 termux 官方源安装
- frpc 为官方静态编译版，无 `com.termux` 前缀依赖，可直接执行
