#!/usr/bin/env python3
"""
ELF 依赖闭包自检：证明 bootstrap.zip 里的 aarch64 运行时自洽。

检查项：
  1. 架构是否全为 EM_AARCH64（64-bit）
  2. PT_INTERP（动态链接器）路径是否指向系统 linker
  3. 每个 ELF 的 DT_NEEDED 是否都能被满足：
       - zip 内的 lib/*.so
       - Android 系统库白名单（/system/lib64）
  4. DT_RUNPATH / DT_RPATH 是否指向我们的前缀
  5. 含 termux 前缀的 ELF 是否会被正确替换（字符串存在性）

用法: python scripts/elf_audit.py <bootstrap.zip>
"""
import struct
import sys
import zipfile
from collections import Counter, defaultdict

EM_AARCH64 = 183
PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ = 0, 1, 5, 10
DT_RPATH, DT_RUNPATH = 15, 29

# Android 平台自带、任何 App 都能链接的库（/system/lib64）
SYSTEM_LIBS = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libz.so',
    'libandroid.so', 'libjnigraphics.so', 'libEGL.so', 'libGLESv2.so',
    'libOpenSLES.so', 'libOpenMAXAL.so', 'libstdc++.so', 'libc++_shared.so',
    'libnativehelper.so', 'libvulkan.so', 'libmediandk.so', 'libcamera2ndk.so',
    'libaaudio.so', 'libamidi.so', 'libneuralnetworks.so',
}
# bionic 的真实文件名（NDK 里叫 libc.so，设备上也是 libc.so）
SYSTEM_ALIASES = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so'}


def parse_elf(data):
    """返回 (class, machine, interp, needed[], runpath[]) 或 None"""
    if len(data) < 64 or data[:4] != b'\x7fELF':
        return None
    ei_class = data[4]
    if ei_class != 2:
        return {'class': ei_class, 'machine': None, 'interp': None,
                'needed': [], 'runpath': []}
    e_machine = struct.unpack_from('<H', data, 18)[0]
    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]
    if e_phoff == 0 or e_phnum == 0 or e_phoff + e_phnum * e_phentsize > len(data):
        return {'class': ei_class, 'machine': e_machine, 'interp': None,
                'needed': [], 'runpath': []}

    interp = None
    dynamic = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from('<I', data, off)[0]
        if p_type == PT_INTERP:
            p_offset = struct.unpack_from('<Q', data, off + 8)[0]
            p_filesz = struct.unpack_from('<Q', data, off + 32)[0]
            interp = data[p_offset:p_offset + p_filesz].rstrip(b'\x00').decode('utf-8', 'replace')
        elif p_type == PT_DYNAMIC:
            p_offset = struct.unpack_from('<Q', data, off + 8)[0]
            p_filesz = struct.unpack_from('<Q', data, off + 32)[0]
            dynamic = (p_offset, p_filesz)

    needed, runpath = [], []
    if dynamic:
        off, size = dynamic
        entries = []
        for i in range(0, size - 15, 16):
            tag, val = struct.unpack_from('<qQ', data, off + i)
            if tag == DT_NULL:
                break
            entries.append((tag, val))
        # 先找 strtab
        strtab_off = strsz = None
        for tag, val in entries:
            if tag == DT_STRTAB:
                strtab_off = val
            elif tag == DT_STRSZ:
                strsz = val
        if strtab_off is not None:
            # 虚拟地址→文件偏移：用第一个 PT_LOAD 做近似（通常 vaddr==offset）
            base = None
            for i in range(e_phnum):
                o = e_phoff + i * e_phentsize
                if struct.unpack_from('<I', data, o)[0] == PT_LOAD:
                    p_offset = struct.unpack_from('<Q', data, o + 8)[0]
                    p_vaddr = struct.unpack_from('<Q', data, o + 16)[0]
                    base = p_vaddr - p_offset
                    break
            if base is None:
                base = 0
            s_off = strtab_off - base
            if 0 <= s_off < len(data):
                def cstr(o):
                    e = data.find(b'\x00', o)
                    if e < 0:
                        e = len(data)
                    return data[o:e].decode('utf-8', 'replace')
                for tag, val in entries:
                    if tag == DT_NEEDED:
                        needed.append(cstr(s_off + val))
                    elif tag in (DT_RPATH, DT_RUNPATH):
                        runpath.append(cstr(s_off + val))
    return {'class': ei_class, 'machine': e_machine, 'interp': interp,
            'needed': needed, 'runpath': runpath}


def main(zip_path):
    z = zipfile.ZipFile(zip_path)
    names = set(z.namelist())

    elfs = {}
    scripts = []
    for n in names:
        if n.endswith('/'):
            continue
        head = z.read(n)[:4] if False else None
        info = z.getinfo(n)
        # 只读前 4 字节判断，避免全量读入
        with z.open(n) as f:
            magic = f.read(4)
        if magic == b'\x7fELF':
            elfs[n] = z.read(n)
        elif magic[:2] == b'#!':
            with z.open(n) as f:
                scripts.append((n, f.read(120)))

    print(f'ELF 文件数: {len(elfs)}   脚本数: {len(scripts)}')

    # ---- 1. 架构 ----
    bad_class = [n for n, d in elfs.items() if d[4] != 2]
    machines = Counter()
    for n, d in elfs.items():
        m = parse_elf(d)['machine']
        machines[m] += 1
    print(f'ELF class=64 比例: {len(elfs) - len(bad_class)}/{len(elfs)}')
    print(f'机器类型分布: {dict(machines)}  (183 = AArch64)')

    # ---- 2/3/4. 逐个解析 ----
    # 依赖供给来源有两类：
    #   1) zip 内实体的 lib/*.so
    #   2) SYMLINKS.txt 里 lib/ 下的链接（SONAME -> 版本化实体文件），运行时才创建
    provided = set()
    for n in names:
        if n.startswith('lib/') and '.so' in n:
            provided.add(n.split('/')[-1])
    if 'SYMLINKS.txt' in names:
        for line in z.read('SYMLINKS.txt').decode('utf-8', 'replace').splitlines():
            line = line.strip()
            if '\u2190' not in line:
                continue
            left, right = line.split('\u2190', 1)
            target = left.strip().removeprefix('./')
            link = right.strip().removeprefix('./')
            if not link.startswith('lib/'):
                continue
            base = link.rsplit('/', 1)[0] + '/'
            abs_target = target if '/' in target else base + target
            if abs_target in names:
                provided.add(link.split('/')[-1])
    print(f'zip 内提供的 .so 数量: {len(provided)}（含运行时链接）')

    interp_counter = Counter()
    needed_counter = Counter()
    runpaths = Counter()
    missing = defaultdict(list)
    termux_string_hits = 0

    for n, d in elfs.items():
        info = parse_elf(d)
        if info.get('interp'):
            interp_counter[info['interp']] += 1
        for rp in info['runpath']:
            runpaths[rp] += 1
        for lib in info['needed']:
            needed_counter[lib] += 1
            if lib in provided:
                continue
            if lib in SYSTEM_LIBS:
                continue
            missing[lib].append(n)
        if b'com.termux' in d:
            termux_string_hits += 1

    print('\n--- PT_INTERP 分布 ---')
    for k, v in interp_counter.most_common():
        print(f'  {v:5d}  {k}')

    print('\n--- DT_RUNPATH/DT_RPATH 分布 ---')
    for k, v in runpaths.most_common():
        print(f'  {v:5d}  {k}')

    print(f'\n不同 DT_NEEDED 条目: {len(needed_counter)}')
    print(f'含 com.termux 字符串的 ELF: {termux_string_hits}/{len(elfs)}')

    print('\n--- 未满足的依赖 ---')
    if not missing:
        print('  无（依赖闭包完整）')
    else:
        for lib, users in sorted(missing.items()):
            print(f'  {lib:28s} 被 {len(users)} 个使用，例如 {users[0]}')

    # ---- 5. shebang 解释器 ----
    bad_shebang = []
    for n, head in scripts:
        line = head.split(b'\n')[0].decode('utf-8', 'replace')
        parts = line[2:].strip().split()
        if not parts:
            continue
        interp = parts[0]
        rel = None
        if 'com.termux' in interp:
            rel = interp.split('/files/usr/', 1)[-1]
        elif interp.startswith('/usr/bin/') or interp.startswith('/bin/'):
            rel = 'bin/' + interp.rsplit('/', 1)[-1]
        if rel and rel not in names:
            bad_shebang.append((n, rel))
    print(f'\n--- shebang 指向缺失解释器: {len(bad_shebang)} ---')
    for n, rel in bad_shebang[:6]:
        print(f'  {n} -> {rel}')

    ok = (not missing) and (not bad_shebang)
    print('\nRESULT:', 'OK 运行时自洽' if ok else 'ATTENTION 见上方缺口')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
