"""两个 Banner 产出脚本(generate.py / from_video.py)共用的落盘环节。

抽出来的理由不是「少打几行字」,是 **GIF 编码参数不能各写一份**:调色板档位、抖动、
帧间差分这三样如果两边分别维护,迟早一边改了另一边没改,同一个轮播里的三张图
就会有肉眼可见的画质差。海报尺寸同理 —— 三张不一致会在 aspectFit 下高度打架。
"""
import hashlib
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
POSTER_DIR = os.path.normpath(os.path.join(HERE, '../../../chengyinhub-xcx/images'))

# 调色板档位由调用方给:自绘那两张有大片 3D 柔光渐变,压到 64 色会出色阶;
# 视频那张本身带压缩噪点,96 色只是把噪点也编进去,白涨 11% 体积。
# 共享的是**流水线**(选板 → 无抖动映射),不是档位 —— 把档位也焊死反而是错的统一。
GEN_COLORS, VIDEO_COLORS = 96, 64
POSTER_W, POSTER_H = 400, 286


def run(args):
    subprocess.run(args, check=True, capture_output=True)


def encode_gif(png_pattern, out_gif, fps, tmp_dir, colors):
    """定死一套流水线:先按帧间差分选调色板,再无抖动映射。
    抖动对这类大面积纯色 + 柔光的画面只增体积、不增观感。"""
    pal = os.path.join(tmp_dir, 'pal.png')
    run(['ffmpeg', '-y', '-v', 'error', '-i', png_pattern,
         '-vf', 'palettegen=max_colors=%d:stats_mode=diff' % colors, pal])
    run(['ffmpeg', '-y', '-v', 'error', '-framerate', str(fps),
         '-i', png_pattern, '-i', pal,
         '-lavfi', 'paletteuse=dither=none:diff_mode=rectangle', '-loop', '0', out_gif])
    return out_gif


def report(path):
    print('%-26s %6.1f KB' % (os.path.basename(path), os.path.getsize(path) / 1024))
    return path


def sha256(path):
    with open(path, 'rb') as fh:
        return hashlib.sha256(fh.read()).hexdigest()


# ── 产物清单 ────────────────────────────────────────────────────────
# 对齐仓里既有先例 ci/render_template_covers.py:产物入库,靠 manifest 防静默漂移。
# CI 侧**不重渲**(不需要 ffmpeg),只校验「已提交产物 ↔ manifest」以及
# 「生成脚本的 sha256 ↔ manifest」—— 后者专治「改了脚本却忘了重跑」。
BANNERS = ('home-banner-club', 'home-banner-merchant', 'home-banner-friends')
MEMOJI = ('lead', 'curly', 'swim')
PRODUCERS = ('_banner_io.py', 'generate.py', 'from_video.py', 'extract_memoji.py')
MANIFEST = os.path.join(HERE, 'manifest.json')


def write_manifest():
    """扫一遍全部已提交产物 + 生成脚本,重写 manifest。
    两个生成器结尾都调它,写出的内容一样,所以跑哪个都能保持清单正确。"""
    import json
    from PIL import Image

    assets = {}
    for name in BANNERS:
        for path in (os.path.join(HERE, name + '.gif'),
                     os.path.join(POSTER_DIR, name + '.jpg')):
            with Image.open(path) as im:
                size = im.size
            assets[os.path.basename(path)] = {
                'bytes': os.path.getsize(path), 'width': size[0], 'height': size[1],
                'sha256': sha256(path)}
    for name in MEMOJI:
        path = os.path.join(HERE, 'memoji', name + '.png')
        assets['memoji/' + name + '.png'] = {
            'bytes': os.path.getsize(path), 'sha256': sha256(path)}

    with open(MANIFEST, 'w', encoding='utf-8') as fh:
        json.dump({
            'note': '首页行动 Banner 产物清单。改动生成脚本或素材后必须重跑生成器,'
                    '否则契约门禁会因 sha256 不符而红。',
            'producers': {n: sha256(os.path.join(HERE, n)) for n in PRODUCERS},
            'assets': assets,
        }, fh, ensure_ascii=False, indent=2, sort_keys=True)
        fh.write('\n')
    print('%-26s %d 项产物 / %d 个脚本' % ('manifest.json', len(assets), len(PRODUCERS)))
