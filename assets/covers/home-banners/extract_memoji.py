#!/usr/bin/env python3
"""从 sources/club.mp4 里抠出中心那三枚 Memoji 圆片,供 generate.py 复用。

商家和报名两张要和俱乐部是一套,材质就必须同源 —— 所以人不是另找的素材,
是从同一段视频里抠的。这一步原先是手敲的一次性命令,产物 memoji/*.png 因此
成了没人能复现的孤儿;落成脚本就是为了补上这一环。

圆心和半径不写死:按目标色做掩码,再用「圆片所在的行是长连续段、配件所在的行是短段」
把主角圆和周围配件分开。写死的只有帧号 —— 那是挑出来的、表情最好的三帧。

用法:python3 assets/covers/home-banners/extract_memoji.py
"""
import os
import subprocess
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
VIDEO = os.path.join(HERE, 'sources', 'club.mp4')
OUT = os.path.join(HERE, 'memoji')

# (输出名, 帧号, 圆片主色):帧号是人工挑的「表情最好且已站定」的一帧
PICKS = [('lead', 90, (247, 200, 40)),
         ('curly', 134, (150, 95, 240)),
         ('swim', 242, (240, 60, 70))]


def frame_at(n):
    tmp = tempfile.mkdtemp()
    try:
        png = os.path.join(tmp, 'x.png')
        subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', VIDEO,
                        '-vf', "select='eq(n\\,%d)'" % n, '-vsync', '0',
                        '-frames:v', '1', png], check=True)
        im = Image.open(png).convert('RGBA')
        im.load()
        return im
    finally:
        for f in os.listdir(tmp):
            os.remove(os.path.join(tmp, f))
        os.rmdir(tmp)


def locate_disc(im, color):
    """主角圆的行是长连续段,配件的行是短段 —— 用最长游程把两者分开。"""
    a = np.array(im).astype(int)
    m = np.abs(a[:, :, :3] - np.array(color)).sum(axis=2) < 90
    spans = []
    for row in m:
        idx = np.nonzero(row)[0]
        if not len(idx):
            spans.append((0, 0))
            continue
        cuts = np.nonzero(np.diff(idx) > 1)[0]
        runs = np.split(idx, cuts + 1)
        best = max(runs, key=len)
        spans.append((len(best), int(best[0])))
    longest = max(s[0] for s in spans)
    rows = [y for y, (l, _) in enumerate(spans) if l > 0.45 * longest]
    y0, y1 = min(rows), max(rows)
    x0 = min(spans[y][1] for y in rows)
    x1 = max(spans[y][1] + spans[y][0] for y in rows)
    return (x0 + x1) / 2, (y0 + y1) / 2, max(x1 - x0, y1 - y0) / 2


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, n, color in PICKS:
        im = frame_at(n)
        cx, cy, r = locate_disc(im, color)
        # 框只比圆大一点:Memoji 的头盔/胡子会出血到圆外,得带走;
        # 框一大就会把隔壁配件也卷进来,所以圆外只收「紧贴圆边的非黑像素」。
        box = int(r * 1.12)
        crop = np.array(im.crop((int(cx - box), int(cy - box),
                                 int(cx + box), int(cy + box)))).astype(int)
        yy, xx = np.mgrid[0:crop.shape[0], 0:crop.shape[1]]
        d2 = (xx - box) ** 2 + (yy - box) ** 2
        lum = crop[:, :, :3].max(axis=2)
        alpha = np.where((d2 <= (r + 1) ** 2) | ((lum > 26) & (d2 <= (r * 1.10) ** 2)),
                         255, 0).astype(np.uint8)
        out = Image.fromarray(np.dstack([crop[:, :, :3].astype(np.uint8), alpha]))
        out.save(os.path.join(OUT, name + '.png'))
        print('%-8s frame=%3d  圆心(%.0f,%.0f) R=%.0f  → %s.png' % (name, n, cx, cy, r, name))


if __name__ == '__main__':
    main()
