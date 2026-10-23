#!/usr/bin/env python3
# 生成 QuickSite 图标：纯 Python（无 Pillow），手写 PNG 编码
# 一个圆角方块 + "Q" 字样风格标记 + 渐变背景
import struct, zlib, os, math, sys

def make_png(size):
    W = H = size
    px = [[None]*W for _ in range(H)]

    def blend(x, y, cr, cg, cb, ca):
        old = px[y][x]
        if old is None:
            px[y][x] = [cr, cg, cb, ca]
            return
        na = ca + old[3] * (1 - ca)
        if na == 0:
            px[y][x] = [0,0,0,0]
            return
        r = (cr*ca + old[0]*old[3]*(1-ca))/na
        g = (cg*ca + old[1]*old[3]*(1-ca))/na
        b = (cb*ca + old[2]*old[3]*(1-ca))/na
        px[y][x] = [r,g,b,na]

    radius = int(size*0.22)
    # 背景渐变：从 #38BDF8 (56,189,248) 到 #2563EB (37,99,235)
    c1, c2 = (56,189,248), (37,99,235)
    for y in range(H):
        for x in range(W):
            t = (x + y) / (W + H)
            r = c1[0]*(1-t) + c2[0]*t
            g = c1[1]*(1-t) + c2[1]*t
            b = c1[2]*(1-t) + c2[2]*t
            px[y][x] = [r,g,b,255]

    # 圆角裁剪（把圆角外变透明）
    for y in range(H):
        for x in range(W):
            # 判断是否在圆角外
            dx = min(x, W-1-x)
            dy = min(y, H-1-y)
            if dx < radius and dy < radius:
                ddx, ddy = radius-dx, radius-dy
                if math.sqrt(ddx*ddx + ddy*ddy) > radius:
                    px[y][x] = [0,0,0,0]

    # 白色 "Q"：圆环 + 斜杠尾巴
    cx, cy = W*0.44, H*0.44
    ring_r_out = size*0.26
    ring_r_in  = size*0.175
    for y in range(H):
        for x in range(W):
            d = math.sqrt((x-cx)**2 + (y-cy)**2)
            if ring_r_in <= d <= ring_r_out:
                blend(x, y, 255, 255, 255, 255)
            # 尾巴：从环右下往外的粗线
            tx, ty = x - cx, y - cy
            # 线段 (0.18,0.18)*size 到 (0.34,0.34)*size，宽 0.045*size
            p1 = (size*0.18, size*0.18)
            p2 = (size*0.34, size*0.34)
            vx, vy = p2[0]-p1[0], p2[1]-p1[1]
            wx, wy = tx-p1[0], ty-p1[1]
            L2 = vx*vx + vy*vy
            tt = max(0, min(1, (wx*vx + wy*vy)/L2 if L2 else 0))
            pxn, pyn = p1[0]+tt*vx, p1[1]+tt*vy
            dist = math.sqrt((tx-pxn)**2 + (ty-pyn)**2)
            if dist <= size*0.042:
                blend(x, y, 255, 255, 255, 255)

    # 底部小箭头装饰（象征"秒开"）：三条渐短的白色横线，形如 »
    for i, frac in enumerate([0.62, 0.72, 0.82]):
        yy = int(H*frac)
        length = size*0.10
        alpha = 90
        for x in range(int(W*0.30), int(W*0.30+length)):
            if 0 <= x < W and 0 <= yy < H:
                blend(x, yy, 255, 255, 255, alpha)
                blend(x, yy+1, 255, 255, 255, alpha//2)

    # 编码 PNG
    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))

    def clamp(v):
        return max(0, min(255, int(v)))

    raw = b''
    for y in range(H):
        raw += b'\x00'
        for x in range(W):
            r,g,b,a = px[y][x]
            raw += struct.pack('4B', clamp(r), clamp(g), clamp(b), clamp(a))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    return png

def make_fg(size):
    """adaptive icon 前景：透明底，图形缩在中心 66% 安全区内。"""
    W = H = size
    px = [[None]*W for _ in range(H)]

    def blend(x, y, cr, cg, cb, ca):
        old = px[y][x]
        if old is None:
            px[y][x] = [cr, cg, cb, ca]
            return
        na = ca + old[3] * (1 - ca)
        if na == 0:
            px[y][x] = [0, 0, 0, 0]
            return
        r = (cr*ca + old[0]*old[3]*(1-ca))/na
        g = (cg*ca + old[1]*old[3]*(1-ca))/na
        b = (cb*ca + old[2]*old[3]*(1-ca))/na
        px[y][x] = [r, g, b, na]

    # 图形整体缩到 60% 画布，居中
    inner = size * 0.60
    off = (size - inner) / 2
    cx, cy = off + inner*0.44, off + inner*0.44
    ring_out = inner * 0.26
    ring_in = inner * 0.175
    tail_w = inner * 0.042
    p1 = (off + inner*0.62, off + inner*0.62)
    p2 = (off + inner*0.78, off + inner*0.78)
    vx, vy = p2[0]-p1[0], p2[1]-p1[1]
    L2 = vx*vx + vy*vy

    for y in range(H):
        for x in range(W):
            d = math.sqrt((x-cx)**2 + (y-cy)**2)
            if ring_in <= d <= ring_out:
                blend(x, y, 255, 255, 255, 255)
                continue
            wx, wy = x-p1[0], y-p1[1]
            tt = max(0, min(1, (wx*vx + wy*vy)/L2 if L2 else 0))
            pxn, pyn = p1[0]+tt*vx, p1[1]+tt*vy
            if math.sqrt((x-pxn)**2 + (y-pyn)**2) <= tail_w:
                blend(x, y, 255, 255, 255, 255)

    def clamp(v):
        return max(0, min(255, int(v)))

    raw = b''
    for y in range(H):
        raw += b'\x00'
        for x in range(W):
            r, g, b, a = px[y][x] or [0, 0, 0, 0]
            raw += struct.pack('4B', clamp(r), clamp(g), clamp(b), clamp(a))

    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    return png


out_root = sys.argv[1]
sizes = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
    'mipmap-anydpi-v26': None,
}
for d, s in sizes.items():
    path = os.path.join(out_root, d)
    os.makedirs(path, exist_ok=True)
    if s:
        with open(os.path.join(path, 'ic_launcher.png'), 'wb') as f:
            f.write(make_png(s))
        with open(os.path.join(path, 'ic_launcher_round.png'), 'wb') as f:
            f.write(make_png(s))
        # adaptive icon 前景：透明底 + 居中图形（安全区内）
        fg = make_fg(int(s * 108 / 48))
        with open(os.path.join(path, 'ic_launcher_fg.png'), 'wb') as f:
            f.write(fg)
        print(f'{d}: {s}px')

# adaptive icon XML（Android 8+）
xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <foreground android:drawable="@mipmap/ic_launcher_fg" />
    <background android:drawable="@color/accent" />
</adaptive-icon>
'''
os.makedirs(os.path.join(out_root, 'mipmap-anydpi-v26'), exist_ok=True)
with open(os.path.join(out_root, 'mipmap-anydpi-v26', 'ic_launcher.xml'), 'w') as f:
    f.write(xml)
with open(os.path.join(out_root, 'mipmap-anydpi-v26', 'ic_launcher_round.xml'), 'w') as f:
    f.write(xml)
print('done')