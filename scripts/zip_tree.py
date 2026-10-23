#!/usr/bin/env python3
"""把目录树打包成 zip；跳过无法读取的条目（如 Windows 上的悬空符号链接）。"""
import os
import sys
import time
import zipfile


def main(src, out):
    skipped = []
    count = 0
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, dirs, files in os.walk(src):
            for name in sorted(files):
                p = os.path.join(root, name)
                arc = os.path.relpath(p, src).replace(os.sep, '/')
                try:
                    st = os.stat(p)
                except OSError:
                    skipped.append(arc)
                    continue
                if not os.path.isfile(p):
                    skipped.append(arc)
                    continue
                info = zipfile.ZipInfo(arc, time.localtime(st.st_mtime)[:6])
                info.compress_type = zipfile.ZIP_DEFLATED
                mode = 0o755 if (st.st_mode & 0o111) else 0o644
                info.external_attr = (0o100000 | mode) << 16
                try:
                    with open(p, 'rb') as f:
                        data = f.read()
                except OSError:
                    skipped.append(arc)
                    continue
                z.writestr(info, data)
                count += 1
    print(f'已打包 {count} 个文件 -> {out} ({os.path.getsize(out)} bytes)')
    if skipped:
        print(f'跳过 {len(skipped)} 个无法读取的条目（悬空链接等）:')
        for s in skipped[:10]:
            print('   -', s)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
