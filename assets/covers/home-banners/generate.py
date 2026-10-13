#!/usr/bin/env python3
"""商家 / 报名活动 Banner 动图的母版生成器。

俱乐部那张是 Apple Fitness 录屏直转(见 from_video.py / sources/README.md)。
这两张要跟它是一套,所以**材质同源**:中心的 Memoji 圆片是从同一段视频里抠出来的
(memoji/*.png),配件沿用它那套「深色圆片 + 霓虹剪影」,只是换了语义:
  商家 → 店铺 / 一杯热的 / 票 / 内容(讲的是开店,不是跑步)
  报名 → 日程 / 地点 / 同行 / 彩带
中心也各不相同:商家是单人轮播,报名是三个人组队在浅椭圆上轮转 —— 正好说「和朋友」。

配件是**画出来的、会动的**,不是静态图标:参考稿量下来,那边配件的位置基本钉死,
活的是图形本身(跑步循环、冒热气)。这一点是上一版和参考稿差距最大的地方。

用法:
    python3 assets/covers/home-banners/generate.py            # 重渲 + 重写 manifest
    python3 assets/covers/home-banners/generate.py --check    # 只重渲到临时目录比对,不落盘
"""
import argparse
import math
import os
import shutil
import tempfile

from PIL import Image, ImageChops, ImageDraw

import _banner_io as io

HERE = os.path.dirname(os.path.abspath(__file__))
MEMOJI_DIR = os.path.join(HERE, 'memoji')

# 画幅与俱乐部那张对齐(320x228),不然两张在同一个轮播里高度会跳
GIF_W, GIF_H, FRAMES, FPS = 320, 228, 46, 10
SS = 2

_cache = {}      # 霓虹图标
_memoji = {}     # 人像


def _lerp(a, b, k):
    return tuple(round(a[i] + (b[i] - a[i]) * k) for i in range(3))


def _fill(mask, top, bottom):
    """把剪影用竖向渐变填色 —— Apple 那套霓虹图标都是渐变,纯色一眼就塑料。"""
    S = mask.size[0]
    grad = Image.new('RGB', (1, S))
    for y in range(S):
        grad.putpixel((0, y), _lerp(top, bottom, y / max(1, S - 1)))
    out = Image.new('RGBA', mask.size, (0, 0, 0, 0))
    out.paste(grad.resize(mask.size), (0, 0), mask)
    return out


def neon(kind, phase, px, top, bottom):
    """霓虹商业图标。每个都带一点自己的动作 —— 图形不动,整张就是死的。"""
    key = (kind, round(phase, 3), px, top, bottom)
    if key in _cache:
        return _cache[key]
    S = px * 4
    m = Image.new('L', (S, S), 0)
    d = ImageDraw.Draw(m)
    R = lambda x0, y0, x1, y1, **kw: d.rounded_rectangle(
        [S * x0, S * y0, S * x1, S * y1], radius=S * kw.pop('r', 0.03), **kw)
    o = 2 * math.pi * phase

    if kind == 'shop':
        wob = 0.012 * math.sin(o)                       # 雨棚轻轻起伏
        d.polygon([(S * 0.10, S * (0.30 + wob)), (S * 0.90, S * (0.30 + wob)),
                   (S * 0.82, S * (0.46 + wob)), (S * 0.18, S * (0.46 + wob))], fill=255)
        R(0.20, 0.46, 0.80, 0.84, fill=255, r=0.04)
        d.rounded_rectangle([S * 0.42, S * 0.58, S * 0.58, S * 0.84],
                            radius=S * 0.03, fill=0)     # 门:掏空,露出底片颜色
    elif kind == 'cup':
        R(0.26, 0.44, 0.66, 0.80, fill=255, r=0.06)
        d.ellipse([S * 0.62, S * 0.50, S * 0.82, S * 0.68], outline=255, width=int(S * 0.055))
        R(0.18, 0.82, 0.76, 0.88, fill=255, r=0.03)
        for i in (0, 1):                                 # 两缕热气,循环上升
            p = (phase + i * 0.5) % 1.0
            y0 = 0.40 - 0.16 * p
            pts = [(0.36 + i * 0.16 + 0.035 * math.sin(6 * (y0 + k * 0.04)), y0 - k * 0.04)
                   for k in range(4)]
            d.line([(S * a, S * b) for a, b in pts], fill=int(255 * (1 - p)),
                   width=int(S * 0.045), joint='curve')
    elif kind == 'ticket':
        tilt = 0.02 * math.sin(o)
        R(0.14, 0.34 + tilt, 0.86, 0.70 + tilt, fill=255, r=0.06)
        for cxx in (0.14, 0.86):                         # 两侧撕口
            d.ellipse([S * (cxx - 0.07), S * (0.44 + tilt), S * (cxx + 0.07), S * (0.60 + tilt)],
                      fill=0)
        d.line([(S * 0.50, S * (0.40 + tilt)), (S * 0.50, S * (0.64 + tilt))],
               fill=0, width=int(S * 0.035))
    elif kind == 'calendar':
        R(0.16, 0.30, 0.84, 0.82, fill=255, r=0.07)
        for x in (0.32, 0.68):                           # 挂环
            d.line([(S * x, S * 0.20), (S * x, S * 0.33)], fill=255, width=int(S * 0.06))
        d.line([(S * 0.16, S * 0.45), (S * 0.84, S * 0.45)], fill=0, width=int(S * 0.045))
        col = int((phase * 3) % 3)                       # 高亮格逐日跳
        d.rounded_rectangle([S * (0.26 + col * 0.20), S * 0.56,
                             S * (0.42 + col * 0.20), S * 0.72], radius=S * 0.03, fill=0)
    elif kind == 'pin':
        bob = 0.022 * math.sin(o)
        d.ellipse([S * 0.30, S * (0.18 + bob), S * 0.70, S * (0.58 + bob)], fill=255)
        d.polygon([(S * 0.36, S * (0.50 + bob)), (S * 0.64, S * (0.50 + bob)),
                   (S * 0.50, S * (0.80 + bob))], fill=255)
        d.ellipse([S * 0.43, S * (0.30 + bob), S * 0.57, S * (0.44 + bob)], fill=0)
        rp = (phase * 2) % 1.0                           # 地面涟漪一圈圈推出去
        rr = 0.10 + 0.22 * rp
        d.ellipse([S * (0.5 - rr), S * (0.86 - rr * 0.30), S * (0.5 + rr), S * (0.86 + rr * 0.30)],
                  outline=int(210 * (1 - rp)), width=max(1, int(S * 0.035)))
    elif kind == 'pair':
        for i, (hx, hs) in enumerate(((0.34, 0.115), (0.66, 0.115))):
            bb = 0.018 * math.sin(o + i * math.pi)       # 俩人交替点头
            d.ellipse([S * (hx - hs), S * (0.28 + bb - hs), S * (hx + hs), S * (0.28 + bb + hs)],
                      fill=255)
            d.pieslice([S * (hx - 0.20), S * (0.46 + bb), S * (hx + 0.20), S * (0.92 + bb)],
                       180, 360, fill=255)
    elif kind == 'party':
        # 开口要够宽,窄了就读成一支箭头而不是拉炮
        d.polygon([(S * 0.13, S * 0.87), (S * 0.40, S * 0.24), (S * 0.76, S * 0.47)], fill=255)
        for i in range(5):                               # 彩屑从炮口飞出去再循环
            fp = (phase + i / 5.0) % 1.0
            ang = math.radians(-64 + i * 30)
            dist = 0.10 + 0.30 * fp
            px_, py_ = 0.58 + math.cos(ang) * dist, 0.36 + math.sin(ang) * dist
            rr = S * 0.055 * (1 - fp * 0.4)
            d.ellipse([S * px_ - rr, S * py_ - rr, S * px_ + rr, S * py_ + rr],
                      fill=int(255 * (1 - fp * 0.75)))
    else:  # sparkle:内容,一闪一闪
        # 八根等长射线读成雪花;要的是 iOS 那种四角星 —— 靠内凹的腰做出来
        k = 0.88 + 0.12 * math.sin(o)
        out_r, in_r = 0.46 * k, 0.105
        pts = []
        for i in range(8):
            ang = math.pi / 4 * i - math.pi / 2
            rr = out_r if i % 2 == 0 else in_r
            pts.append((S * (0.5 + math.cos(ang) * rr), S * (0.5 + math.sin(ang) * rr)))
        d.polygon(pts, fill=255)

    _cache[key] = _fill(m, top, bottom).resize((px, px), Image.LANCZOS)
    return _cache[key]


def memoji(name, box):
    if (name, box) not in _memoji:
        src = Image.open(os.path.join(MEMOJI_DIR, name + '.png')).convert('RGBA')
        src = src.crop(src.getbbox())
        k = box / max(src.size)
        _memoji[(name, box)] = src.resize(
            (max(1, round(src.width * k)), max(1, round(src.height * k))), Image.LANCZOS)
    return _memoji[(name, box)]


# ── 动效基元 ────────────────────────────────────────────────────────
def ease(t):
    return t * t * (3 - 2 * t)


def spring(u):
    """0→1 的弹簧:冲过头再收住。匀速进场没有「弹」。"""
    if u <= 0:
        return 0.0
    if u >= 1:
        return 1.0
    return 1 - math.exp(-6.5 * u) * math.cos(6.2 * u)


def paste(base, layer, cx, cy, alpha=1.0):
    if alpha <= 0.004 or layer.width < 1 or layer.height < 1:
        return
    if alpha < 1:
        layer = layer.copy()
        layer.putalpha(layer.getchannel('A').point(lambda v: int(v * alpha)))
    base.alpha_composite(layer, (round(cx - layer.width / 2), round(cy - layer.height / 2)))


def plate(base, cx, cy, r, color, alpha=1.0):
    r = max(1.0, r)
    side = int(r * 2) + 2
    chip = Image.new('RGBA', (side, side), (0, 0, 0, 0))
    ImageDraw.Draw(chip).ellipse([1, 1, side - 2, side - 2], fill=color + (255,))
    paste(base, chip, cx, cy, alpha)


# ── 构图 ────────────────────────────────────────────────────────────
# 配件位:(角度°, 半径倍率, 大小/宽)。不对称、不等大 —— 参考稿就是这样,
# 等大均分立刻掉档次。位置钉死不飘,和参考稿一致:活的是图形本身。
SLOTS = [(-138, 1.00, 0.086), (-46, 0.96, 0.062), (34, 1.02, 0.078), (132, 0.94, 0.058)]
ORBIT_RX, ORBIT_RY = 0.350, 0.330

CYAN = ((0, 224, 255), (60, 140, 255))
AMBER = ((255, 214, 80), (255, 150, 40))
LIME = ((166, 255, 0), (70, 220, 90))
PINK = ((255, 120, 175), (255, 45, 111))
INK_C, INK_A, INK_L, INK_P = (12, 32, 48), (40, 31, 12), (24, 40, 18), (44, 16, 32)

VIOLET = ((165, 125, 255), (95, 150, 255))
INK_V = (34, 26, 62)

MERCHANT_PROPS = [('shop', CYAN, INK_C), ('cup', AMBER, INK_A),
                  ('ticket', LIME, INK_L), ('sparkle', PINK, INK_P)]
FRIENDS_PROPS = [('calendar', LIME, INK_L), ('pin', CYAN, INK_C),
                 ('pair', VIOLET, INK_V), ('party', PINK, INK_P)]
SCENES = ['curly', 'lead', 'swim']      # 主理人轮播;圆片配色是素材自带的
# 每一幕多留的尾巴。三幕若严丝合缝平铺,交界处「上一幕退完、下一幕还没起」会露出整帧
# 全黑 —— 实测每循环闪三次。尾巴让相邻两幕交叠,任何时刻画面上至少有一位主角。
SCENE_TAIL = 0.26


def satellites(img, t, W, H, props, cx, cy, k=1.0, a=1.0, u=1.0):
    """四枚配件:位置钉死,活的是图形本身 —— 参考稿量下来就是这样。"""
    for j, (kind, (c1, c2), ink) in enumerate(props):
        ang, rad, pr = SLOTS[j]
        pu = (u - 0.09 - j * 0.05) / 0.24
        pk = spring(pu) if pu < 1 else 1.0
        pa = a * min(1.0, max(0.0, pk) * 1.3)
        if pa <= 0.01:
            continue
        r = W * pr * k * (0.55 + 0.45 * max(0.0, pk))
        th = math.radians(ang)
        px = cx + math.cos(th) * W * ORBIT_RX * rad
        py = cy + math.sin(th) * H * ORBIT_RY * rad \
            + math.sin(2 * math.pi * (t * 2 + j * 0.27)) * H * 0.013
        plate(img, px, py, r, ink, pa)
        # 图形自己的动作相位:每循环整数个周期,接缝处才不跳
        paste(img, neon(kind, (t * 3 + j * 0.29) % 1.0, max(4, int(r * 1.72)), c1, c2),
              px, py, pa)


def friends(img, t, W, H):
    """报名:三个人在一条压扁的椭圆上整整转一圈,前后遮挡把「一起」立起来。
    转满 360° 才收尾,每个人都回到自己的出发点,循环处没有接缝。"""
    cx, cy = W * 0.50, H * 0.50
    cr = W * 0.205
    satellites(img, t, W, H, FRIENDS_PROPS, cx, cy)   # 报名这张没有幕次,配件常驻
    crowd = []
    for i, who in enumerate(SCENES):
        th = 2 * math.pi * (t + i / len(SCENES))
        depth = math.cos(th)                      # +1 最前,-1 最后
        crowd.append((depth,
                      cx + math.sin(th) * W * 0.150,
                      cy + depth * H * 0.026,
                      cr * (0.70 + 0.30 * (depth + 1) / 2) * 1.48,
                      who))
    for depth, x, y, box, who in sorted(crowd, key=lambda c: c[0]):
        paste(img, memoji(who, max(6, int(box))), x, y, 0.88 + 0.12 * (depth + 1) / 2)


def merchant(img, t, W, H):
    cx, cy = W * 0.50, H * 0.50
    cr = W * 0.205
    n = len(SCENES)

    for i, who in enumerate(SCENES):
        u = ((t - i / n) % 1.0) * n
        if u >= 1.0 + SCENE_TAIL:
            continue
        grow = spring(u / 0.20) if u < 0.20 else 1.0
        leave = ease((u - 0.86) / (0.14 + SCENE_TAIL)) if u > 0.86 else 0.0
        k = grow * (1 - 0.5 * leave)
        a = min(1.0, grow * 1.4) * (1 - leave)
        if a <= 0.01:
            continue
        drift = math.sin(2 * math.pi * (t + i / n)) * H * 0.011

        satellites(img, t, W, H, MERCHANT_PROPS, cx, cy, k, a, u)
        swell = 1 + 0.04 * ease(min(1.0, u / 0.9))
        paste(img, memoji(who, max(6, int(cr * k * swell * 2.06))), cx, cy + drift, a)


BANNERS = [('home-banner-merchant', merchant), ('home-banner-friends', friends)]


def render(scene, t, w, h):
    img = Image.new('RGBA', (w * SS, h * SS), (0, 0, 0, 255))
    scene(img, t, w * SS, h * SS)
    return img.convert('RGB').resize((w, h), Image.LANCZOS)


def _luma(im):
    return sum(im.convert('L').getdata()) / (im.width * im.height)


def _maxdiff(a, b):
    return max(ImageChops.difference(a, b).getextrema())[1]


def assert_animation_sound(name, frames, loop_frame):
    """三条负控,每条都能真变红:

    1) **循环闭合** —— 首帧与「第 n+1 帧」必须一致;哪个相位漏了 mod、翻面圈数取成
       非整数,都会在这里红。
    2) **没有空帧** —— 最暗帧不得低于中位亮度的一半。⚠️ 这条是补的:原先只有第 1 条,
       而商家那张恰好在 t=0 和 t=1 都渲成整帧全黑,两张黑图互比恒等,**断言因此恒真**,
       每循环闪三次全黑它一声不吭。假保证比没有断言更危险,所以专门补一条盯空帧。
    3) **真的在动** —— 相邻帧之间至少要有一处明显差异;参数写死成静态图会在这里红。
    """
    delta = _maxdiff(frames[0], loop_frame)
    if delta > 2:
        raise SystemExit('%s 循环不闭合:t=0 与 t=1 最大像素差 %d' % (name, delta))

    lums = [_luma(f) for f in frames]
    floor = sorted(lums)[len(lums) // 2] * 0.5
    dark = [i for i, v in enumerate(lums) if v < floor]
    if dark:
        raise SystemExit('%s 有空帧(亮度低于中位一半):第 %s 帧' % (name, dark))

    moved = max(_maxdiff(frames[i], frames[(i + 1) % len(frames)]) for i in range(len(frames)))
    if moved < 8:
        raise SystemExit('%s 相邻帧几乎没有差别(最大 %d),这不是动图' % (name, moved))


def main(check=False):
    drift = []
    for name, scene in BANNERS:
        frames = [render(scene, i / FRAMES, GIF_W, GIF_H) for i in range(FRAMES)]
        assert_animation_sound(name, frames, render(scene, 1.0, GIF_W, GIF_H))
        tmp = tempfile.mkdtemp()
        try:
            for i, f in enumerate(frames):
                f.save(os.path.join(tmp, 'f%03d.png' % i))
            target = os.path.join(HERE, name + '.gif')
            out = os.path.join(tmp, 'out.gif') if check else target
            io.encode_gif(os.path.join(tmp, 'f%03d.png'), out, FPS, tmp, io.GEN_COLORS)
            if check:
                if io.sha256(out) != io.sha256(target):
                    drift.append(name + '.gif')
                continue
            io.report(target)
        finally:
            shutil.rmtree(tmp)

        poster = os.path.join(io.POSTER_DIR, name + '.jpg')
        render(scene, 0.14, io.POSTER_W, io.POSTER_H).save(
            poster, 'JPEG', quality=88, optimize=True)
        io.report(poster)
    if check:
        if drift:
            raise SystemExit('sha256 漂移:%s —— 已提交产物与当前脚本对不上' % ', '.join(drift))
        print('复现校验通过:%d 张与仓库一致' % len(BANNERS))


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--check', action='store_true',
                    help='只重渲到临时目录并比对 sha256,不落盘')
    args = ap.parse_args()
    main(args.check)
    if not args.check:
        io.write_manifest()
