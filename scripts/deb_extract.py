#!/usr/bin/env python3
"""解包 deb（ar + tar.xz/gz）到指定目录，剥掉 termux 前缀。"""
import sys, tarfile, io, os

PREFIXES = (
    './data/data/com.termux/files/usr/',
    './data/data/com.termux/files/usr',
)


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
    for m in tf.getmembers():
        name = m.name
        for p in PREFIXES:
            if name.startswith(p):
                name = name[len(p):]
                break
        name = name.lstrip('/')
        if not name:
            continue
        dest = os.path.join(out_root, name)
        if m.isdir():
            os.makedirs(dest, exist_ok=True)
        elif m.issym():
            try:
                os.symlink(m.linkname, dest)
            except (OSError, NotImplementedError):
                extra_links.append((m.linkname, name))
        elif m.isfile():
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with open(dest, 'wb') as w:
                w.write(tf.extractfile(m).read())
            count += 1
    if extra_links:
        with open(os.path.join(out_root, 'SYMLINKS.extra.txt'), 'a') as f:
            for link, path in extra_links:
                f.write(f'{link}\t{path}\n')
    print(f'deb 解包完成: {count} 个文件, {len(extra_links)} 个符号链接待运行时创建')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
