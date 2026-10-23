#!/usr/bin/env python3
"""解包 deb（ar + tar.xz/gz）到指定目录，剥掉 termux 前缀。"""
import sys, tarfile, io, os

PREFIXES = (
    './data/data/com.termux/files/usr/',
    './data/data/com.termux/files/usr',
    '/data/data/com.termux/files/usr/',
    '/data/data/com.termux/files/usr',
    'data/data/com.termux/files/usr/',
    'data/data/com.termux/files/usr',
)


def norm(name):
    """把 deb 内的各种前缀写法归一成「相对 usr 的路径」。"""
    n = name
    for p in PREFIXES:
        if n.startswith(p):
            n = n[len(p):]
            break
    return n.lstrip('/')


def main(deb_path, out_root):
    with open(deb_path, 'rb') as f:
        if f.read(8) != b'!<arch>\n':
            raise SystemExit('不是有效的 deb（缺少 ar magic）')
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
        raise SystemExit('deb 中找不到 data.tar')

    tf = tarfile.open(fileobj=io.BytesIO(data))
    count = 0
    extra_links = []
    members = tf.getmembers()

    # 先建立「相对 usr 的路径 -> 内容」映射，供符号链接降级为实体副本时使用。
    # 原因：deb 里的链接常指向 /data/data/com.termux/files/usr/... 这种绝对路径，
    # 而运行时我们的前缀已经换成 com.qk.app，绝对路径会失效，所以能复制就复制。
    files = {}
    for m in members:
        if not m.isfile():
            continue
        name = norm(m.name)
        if name:
            files[name] = tf.extractfile(m).read()

    for m in members:
        name = norm(m.name)
        if not name:
            continue
        dest = os.path.join(out_root, name)
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym():
            target = norm(m.linkname)
            if target in files:
                os.makedirs(os.path.dirname(dest) or '.', exist_ok=True)
                if os.path.lexists(dest):
                    os.remove(dest)
                with open(dest, 'wb') as w:
                    w.write(files[target])
                count += 1
                continue
            try:
                os.symlink(m.linkname, dest)
            except (OSError, NotImplementedError):
                extra_links.append((m.linkname, name))
        elif m.isfile():
            os.makedirs(os.path.dirname(dest) or '.', exist_ok=True)
            with open(dest, 'wb') as w:
                w.write(files[name])
            count += 1
    if extra_links:
        with open(os.path.join(out_root, 'SYMLINKS.extra.txt'), 'a') as f:
            for link, path in extra_links:
                f.write(f'{link}\t{path}\n')
    print(f'deb 解包完成: {count} 个文件, {len(extra_links)} 个符号链接待运行时创建')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
