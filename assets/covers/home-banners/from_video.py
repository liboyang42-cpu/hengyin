#!/usr/bin/env python3
"""把一段 Banner 动效录屏转成首页用的 GIF + 海报。

录屏里图形区下面通常还压着标题/副标题,那两行归页面渲染,**不能烤进图里**。
所以裁切区不是手量的:先跨帧求非黑内容的并集,再找横向空白带把图形区和文案区切开,
只取最上面那一段。手量一次就得记一次,换一段视频又要重量 —— 那正是会忘的地方。

用法:python3 assets/covers/home-banners/from_video.py sources/club.mp4 home-banner-club
"""
import os
import shutil
import sys
import tempfile

import numpy as np
from PIL import Image

import _banner_io as io

HERE = os.path.dirname(os.path.abspath(__file__))
FPS, WIDTH = 10, 320


def artwork_box(video, tmp):
    """跨帧求内容并集,再按横向空白带切出最上面的图形区。"""
    io.run(['ffmpeg', '-y', '-v', 'error', '-i', video,
            '-vf', "select='not(mod(n\\,3))'", '-vsync', '0',
            os.path.join(tmp, 's%04d.png')])
    shots = sorted(f for f in os.listdir(tmp) if f.startswith('s'))
    acc = None
    for f in shots:
        a = np.array(Image.open(os.path.join(tmp, f)).convert('L')) > 16
        acc = a if acc is None else (acc | a)
    rows = acc.sum(axis=1)
    on = np.nonzero(rows)[0]
    if not len(on):
        raise SystemExit('整段都是黑的,没有可裁的内容')
    top = int(on.min())
    # 从内容顶端往下走,遇到第一段够长的空白就断开 —— 下面是文案,不要
    bottom, gap = int(on.max()) + 1, 0
    for y in range(top, int(on.max()) + 1):
        gap = gap + 1 if rows[y] == 0 else 0
        if gap >= 24:
            bottom = y - gap + 1
            break
    cols = np.nonzero(acc[top:bottom].sum(axis=0))[0]
    left, right = int(cols.min()), int(cols.max()) + 1
    # 留一点安全边:抽帧再密也可能错过最舒展的那一帧,宁可多带几像素黑边也别切到内容
    pad = max(4, int(acc.shape[1] * 0.006))
    W, H = acc.shape[1], acc.shape[0]
    left, right = max(0, left - pad), min(W, right + pad)
    top, bottom = max(0, top - pad), min(H, bottom + pad)
    # 宽高都取偶数:yuv/缩放链路对奇数尺寸不友好
    return (right - left) & ~1, (bottom - top) & ~1, left, top


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    video = sys.argv[1] if os.path.isabs(sys.argv[1]) else os.path.join(HERE, sys.argv[1])
    name = sys.argv[2]
    tmp = tempfile.mkdtemp()
    try:
        w, h, x, y = artwork_box(video, tmp)
        crop = 'crop=%d:%d:%d:%d' % (w, h, x, y)
        print('图形区 %dx%d @ (%d,%d) —— 下方文案已排除' % (w, h, x, y))
        # 先把裁好的帧铺到临时目录,再交给共享编码器 —— 两个脚本必须用同一套参数
        chain = "%s,fps=%d,scale=%d:-2:flags=lanczos" % (crop, FPS, WIDTH)
        io.run(['ffmpeg', '-y', '-v', 'error', '-i', video, '-vf', chain,
                '-vsync', '0', os.path.join(tmp, 'g%04d.png')])
        gif = io.report(io.encode_gif(os.path.join(tmp, 'g%04d.png'),
                                      os.path.join(HERE, name + '.gif'), FPS, tmp, io.VIDEO_COLORS))
        poster = os.path.join(io.POSTER_DIR, name + '.jpg')
        io.run(['ffmpeg', '-y', '-v', 'error', '-i', video, '-vf',
                "%s,select='eq(n\\,0)',scale=%d:%d:flags=lanczos" % (crop, io.POSTER_W, io.POSTER_H),
                '-vsync', '0', '-frames:v', '1', '-q:v', '3', poster])
        io.report(poster)
        if os.path.getsize(gif) > 900 * 1024:
            print('⚠️  超过契约里单张 900KB 的线,要么降 fps 要么裁短')
    finally:
        shutil.rmtree(tmp)


if __name__ == '__main__':
    main()
    io.write_manifest()
