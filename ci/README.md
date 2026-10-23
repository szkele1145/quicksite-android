# 云端构建（GitHub Actions）

## 为什么在 `ci/` 而不是 `.github/workflows/`

推送 `.github/workflows/*.yml` 需要 GitHub Token 带 **`workflow`** 权限。当前用于初始化的 Token 只有 `repo` 权限，GitHub 会拒绝推送（报 `refusing to allow a Personal Access Token to create or update workflow`）。

所以 workflow 模板放在这里，启用方式二选一：

### 方式 A：网页端启用（推荐，不需要新 Token）

1. 打开 <https://github.com/szkele1145/quicksite-android/new/main/.github/workflows>
2. 文件名填 `build.yml`
3. 把本文仓库 `ci/build.yml` 的内容整段粘进去
4. Commit

### 方式 B：换一个带 `workflow` 权限的 Token

```powershell
# 在 GitHub → Settings → Developer settings → Personal access tokens
# 勾选 repo + workflow，然后：
git remote set-url origin https://<新token>@github.com/szkele1145/quicksite-android.git
Move-Item ci\build.yml .github\workflows\build.yml -Force
git add -A; git commit -m "ci: enable release workflow"; git push
```

### 方式 C：GitHub CLI

```bash
gh auth login          # 勾选 workflow scope
gh workflow enable build.yml
```

---

## 启用后怎么打包

推送 `v*` 标签即自动构建并发 Release：

```bash
git tag v1.0.1
git push origin v1.0.1
```

也可以在 Actions 页面手动触发（`workflow_dispatch`），填版本号。

## Workflow 做了什么

1. 装 JDK 17 + Android SDK 34（带缓存）
2. 下载 termux bootstrap / frp android_arm64 / nginx deb
3. 跑 `scripts/build-assets.sh` 生成 `bootstrap.zip` + `frpc.bin`
4. `scripts/gen-icon.py` 生成图标
5. 生成 keystore 并构建 release APK
6. 上传 APK 到 Release

> 注意：仓库自带 `keystore/quicksite.keystore`（密码 `quicksite2026`），CI 会重新生成一份。正式分发建议改用你自己的 keystore 并通过 Secrets 注入。
