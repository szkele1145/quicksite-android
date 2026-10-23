#!/usr/bin/env python3
"""
把 node / npm / pm2 与其依赖装进 bootstrap 树（bs/）。

用法: python scripts/install_node.py <dl_dir> <bs_dir>

- 解包 nodejs / npm / libicu / libsqlite / c-ares 的 deb 到 bs/
- 解包 pm2 npm tarball 到 bs/lib/node_modules/pm2
- 生成 bin/pm2 包装脚本（bash shebang，Android 可直接 exec）
- 补 bin/env 与 bin/sh（bootstrap 未带）
- 把所有 bin 下脚本中 `#!/usr/bin/env x` 改写成 $PREFIX/bin/x 形式
"""
import io
import os
import sys
import tarfile

TERMUX_PREFIX = '/data/data/com.termux/files/usr'
PKG_PREFIXES = (
    './data/data/com.termux/files/usr/',
    './data/data/com.termux/files/usr',
)


def extract_deb(deb_path, out_root):
    with open(deb_path, 'rb') as f:
        if f.read(8) != b'!<arch>\n':
            raise SystemExit(f'不是 deb: {deb_path}')
        data = None
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode('ascii', 'replace').strip()
            size = int(hdr[48:58].decode('ascii', 'replace').strip() or 0)
            content = f.read(size)
            if size % 2 == 1:
                f.read(1)
            if name.startswith('data.tar'):
                data = content
                break
    if not data:
        raise SystemExit(f'deb 无 data.tar: {deb_path}')

    tf = tarfile.open(fileobj=io.BytesIO(data))
    count = 0
    for m in tf.getmembers():
        name = m.name
        for p in PKG_PREFIXES:
            if name.startswith(p):
                name = name[len(p):]
                break
        name = name.lstrip('/')
        if not name:
            continue
        dest = os.path.join(out_root, name.replace('/', os.sep))
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym():
            try:
                if os.path.lexists(dest):
                    os.remove(dest)
                os.symlink(m.linkname, dest)
            except (OSError, NotImplementedError):
                pass
        elif m.isfile():
            os.makedirs(os.path.dirname(dest) or '.', exist_ok=True)
            with open(dest, 'wb') as w:
                w.write(tf.extractfile(m).read())
            count += 1
    print(f'  {os.path.basename(deb_path)}: {count} 个文件')


def extract_pm2(tgz, bs, dl):
    """优先使用 npm 完整安装的 pm2（含依赖树），退回 tarball 单包。"""
    import shutil
    node_modules = os.path.join(bs, 'lib', 'node_modules')
    os.makedirs(node_modules, exist_ok=True)

    full = os.path.join(dl, 'pm2-install', 'node_modules')
    if os.path.isdir(full):
        moved = 0
        for entry in os.listdir(full):
            src = os.path.join(full, entry)
            dst = os.path.join(node_modules, entry)
            if not os.path.isdir(src):
                continue
            if os.path.isdir(dst):
                shutil.rmtree(dst, ignore_errors=True)
            elif os.path.lexists(dst):
                os.remove(dst)
            shutil.copytree(src, dst, symlinks=False, dirs_exist_ok=True)
            moved += 1
        print(f'  pm2(完整依赖): {moved} 个包 -> lib/node_modules/')
        return

    # 回退：仅 pm2 自身（会缺依赖，仅作占位）
    target = os.path.join(node_modules, 'pm2')
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.makedirs(target, exist_ok=True)
    tf = tarfile.open(tgz, 'r:gz')
    n = 0
    for m in tf.getmembers():
        rel = m.name.split('/', 1)
        if len(rel) < 2 or not rel[1]:
            continue
        dest = os.path.join(target, rel[1].replace('/', os.sep))
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.isfile():
            os.makedirs(os.path.dirname(dest) or '.', exist_ok=True)
            with open(dest, 'wb') as w:
                w.write(tf.extractfile(m).read())
            n += 1
    print(f'  pm2(仅本体，可能缺依赖): {n} 个文件 -> lib/node_modules/pm2')


def write_wrappers(bs):
    bin_dir = os.path.join(bs, 'bin')
    os.makedirs(bin_dir, exist_ok=True)

    # pm2 包装：bash shebang 在 Android 上可直接 exec（已被前缀替换覆盖）
    pm2_wrapper = f'''#!{TERMUX_PREFIX}/bin/bash
# QuickSite: pm2 启动包装（避免 #!/usr/bin/env node 在 Android 上无法解析）
export PREFIX="{TERMUX_PREFIX}"
export PM2_HOME="$HOME/.pm2"
exec "{TERMUX_PREFIX}/bin/node" "{TERMUX_PREFIX}/lib/node_modules/pm2/bin/pm2" "$@"
'''
    with open(os.path.join(bin_dir, 'pm2'), 'w', newline='\n') as f:
        f.write(pm2_wrapper)
    os.chmod(os.path.join(bin_dir, 'pm2'), 0o755)
    print('  写入 bin/pm2 包装脚本')

    # 极简 env（bootstrap 未带；供 #!/usr/bin/env 场景兜底）
    env_script = f'''#!{TERMUX_PREFIX}/bin/dash
# QuickSite: minimal env(1) for shebang compatibility
while [ $# -gt 0 ]; do
  case "$1" in
    -*=*) export "${{1#-}}"; shift ;;
    *=*) export "$1"; shift ;;
    *) break ;;
  esac
done
if [ $# -eq 0 ]; then exec /system/bin/sh -c 'env' 2>/dev/null || true; fi
exec "$@"
'''
    with open(os.path.join(bin_dir, 'env'), 'w', newline='\n') as f:
        f.write(env_script)
    os.chmod(os.path.join(bin_dir, 'env'), 0o755)
    print('  写入 bin/env')

    # npm / npx / corepack 包装（deb 里是符号链接，Windows 建不出来）
    npm_wrappers = {
        'npm': f'''#!{TERMUX_PREFIX}/bin/bash
export PREFIX="{TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
exec "$PREFIX/bin/node" "$PREFIX/lib/node_modules/npm/bin/npm-cli.js" "$@"
''',
        'npx': f'''#!{TERMUX_PREFIX}/bin/bash
export PREFIX="{TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
exec "$PREFIX/bin/node" "$PREFIX/lib/node_modules/npm/bin/npx-cli.js" "$@"
''',
        'corepack': f'''#!{TERMUX_PREFIX}/bin/bash
export PREFIX="{TERMUX_PREFIX}"
export PATH="$PREFIX/bin:$PATH"
exec "$PREFIX/bin/node" "$PREFIX/lib/node_modules/corepack/dist/corepack.js" "$@"
''',
    }
    for name, body in npm_wrappers.items():
        target = os.path.join(bin_dir, name)
        # deb 解出的可能是符号链接；Windows 下不能直接覆盖写入
        if os.path.islink(target):
            os.remove(target)
        elif os.path.isfile(target):
            continue
        with open(target, 'w', newline='\n') as f:
            f.write(body)
        os.chmod(target, 0o755)
        print(f'  写入 bin/{name}')


def fix_shebangs(bs):
    """把 `#!/usr/bin/env X` 改写成 `#!$PREFIX/bin/X`，其余 termux 绝对路径保持不变。"""
    bin_dir = os.path.join(bs, 'bin')
    changed = 0
    for name in os.listdir(bin_dir):
        p = os.path.join(bin_dir, name)
        if not os.path.isfile(p):
            continue
        try:
            with open(p, 'rb') as f:
                head = f.read(200)
        except OSError:
            continue
        if not head.startswith(b'#!'):
            continue
        first_nl = head.find(b'\n')
        if first_nl < 0:
            continue
        line = head[:first_nl].decode('utf-8', 'replace')
        new_line = None
        if '/usr/bin/env ' in line:
            prog = line.split('/usr/bin/env ', 1)[1].strip().split()[0]
            if prog:
                new_line = f'#!{TERMUX_PREFIX}/bin/{prog}'
        elif line.startswith('#!/usr/bin/') or line.startswith('#!/bin/'):
            prog = line.rsplit('/', 1)[-1].strip()
            if prog and prog not in ('sh', 'env'):
                new_line = f'#!{TERMUX_PREFIX}/bin/{prog}'
        if not new_line or new_line == line:
            continue
        with open(p, 'rb') as f:
            body = f.read()
        with open(p, 'wb') as f:
            f.write(new_line.encode() + body[first_nl:])
        changed += 1
    print(f'  修正 shebang: {changed} 个脚本')


def main(dl, bs):
    print('安装 Node 运行时：')
    for name in ('nodejs.deb', 'npm.deb', 'libicu.deb', 'libsqlite.deb', 'libcares.deb'):
        p = os.path.join(dl, name)
        if not os.path.exists(p) or os.path.getsize(p) < 100000:
            print(f'  跳过（缺失或损坏）: {name}')
            continue
        extract_deb(p, bs)

    pm2 = os.path.join(dl, 'pm2.tgz')
    full_pm2 = os.path.join(dl, 'pm2-install', 'node_modules')
    if os.path.isdir(full_pm2) or (os.path.exists(pm2) and os.path.getsize(pm2) > 10000):
        extract_pm2(pm2, bs, dl)
    else:
        print('  跳过 pm2（未下载）')

    write_wrappers(bs)
    fix_shebangs(bs)

    # 校验
    bin_dir = os.path.join(bs, 'bin')
    for b in ('node', 'npm', 'pm2', 'env'):
        p = os.path.join(bin_dir, b)
        print(f'  校验 {b}: {"OK " + str(os.path.getsize(p)) + "B" if os.path.exists(p) else "缺失"}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
