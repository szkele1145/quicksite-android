#!/usr/bin/env python3
"""把目录树打包成 zip（保留可执行位信息到 unix mode）。"""
import sys, os, zipfile


def main(src, out):
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, dirs, files in os.walk(src):
            for name in sorted(files):
                p = os.path.join(root, name)
                arc = os.path.relpath(p, src).replace(os.sep, '/')
                z.write(p, arc)
    print(f'已打包 -> {out} ({os.path.getsize(out)} bytes)')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
