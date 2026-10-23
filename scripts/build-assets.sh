#!/usr/bin/env bash
# 构建 QuickSite assets（Linux/CI 版）：精简 bootstrap + 预置 nginx + frpc
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DL="${QUICKSITE_DL:-$ROOT/dl}"
WORK="$ROOT/assets-work"
OUT="$ROOT/app/src/main/assets"

mkdir -p "$WORK" "$OUT"

# ---------- 1. 复制 bootstrap ----------
BS="$WORK/bs"
rm -rf "$BS"
cp -a "$DL/bs" "$BS"

# ---------- 2. 精简 ----------
prune() { [ -e "$1" ] && rm -rf "$1" || true; }
prune "$BS/share/misc/magic.mgc"
prune "$BS/share/doc"
prune "$BS/share/info"
prune "$BS/share/man"
prune "$BS/libexec/installed-tests"
prune "$BS/libexec/termux-am"
prune "$BS/libexec/termux/command-not-found"
prune "$BS/var/log"
prune "$BS/var/cache/apt"
prune "$BS/etc/apt"
for l in libapt-pkg.so libapt-private.so libgnutls.so libunbound.so libp11-kit.so libunistring.so pkcs11; do
  prune "$BS/lib/$l"
done

echo "bootstrap: $(du -sh "$BS" | cut -f1)"

# ---------- 3. 解包 nginx deb ----------
python3 "$ROOT/scripts/deb_extract.py" "$DL/nginx_1.31.8_aarch64.deb" "$BS"

# ---------- 3.5 node / npm / pm2 ----------
python3 "$ROOT/scripts/install_node.py" "$DL" "$BS" || echo "node install failed"

# ---------- 3.6 sh -> dash ----------
[ -e "$BS/bin/sh" ] || cp "$BS/bin/dash" "$BS/bin/sh"

# ---------- 4. frpc ----------
python3 "$ROOT/scripts/tar_extract.py" "$DL/frp-android-arm64.tar.gz" "$OUT/frpc.bin" frpc

# ---------- 5. 打包 ----------
python3 "$ROOT/scripts/zip_tree.py" "$BS" "$OUT/bootstrap.zip"
ls -la "$OUT"
echo "assets 构建完成"
