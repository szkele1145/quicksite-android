#!/usr/bin/env python3
"""从 tar.gz 中提取指定名字的文件。"""
import sys, tarfile


def main(tgz_path, out_path, want):
    tf = tarfile.open(tgz_path, 'r:gz')
    for m in tf.getmembers():
        if m.name.rstrip('/').split('/')[-1] == want and m.isfile():
            with open(out_path, 'wb') as w:
                w.write(tf.extractfile(m).read())
            print(f'已提取 {m.name} -> {out_path}')
            return
    raise SystemExit(f'压缩包中找不到 {want}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
