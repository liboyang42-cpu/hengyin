"""主题封面生成（Pillow）。

  python3 -I cover.py <route.json> <out_dir> <fonts_dir> [photo.jpg]

有照片：照片铺底 + 深色渐变；无照片：程序化底纹（不依赖任何第三方图片）。
字体：Noto Serif SC / Noto Sans SC（SIL OFL 1.1，github.com/notofonts/noto-cjk）。
输出 cover-3x4.jpg(1500x2000)、cover-16x9.jpg(1920x1080)、share-1x1.jpg(1080x1080)。
"""
import json, math, os, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

BG = (12, 12, 14)
FG = (248, 248, 248)
FG2 = (183, 183, 183)


def font(d, name, size):
    return ImageFont.truetype(os.path.join(d, name), size)


def texture(w, h, cfg, fonts):
    """程序化底纹：motif=type 铅字阵列 / motif=post 邮戳与电报线。"""
    rnd = random.Random(cfg.get("seed", 7))
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    accent = tuple(cfg["accent"])
    if cfg["motif"] == "type":
        glyphs = cfg["glyphs"]
        step = max(w, h) // 22
        f = font(fonts, "NotoSerifSC-Black.otf", int(step * 0.82))
        for y in range(-step, h + step, step):
            for x in range(-step, w + step, step):
                g = rnd.choice(glyphs)
                # 铅字是反字：水平镜像
                tile = Image.new("L", (step, step), 0)
                ImageDraw.Draw(tile).text((step * 0.09, -step * 0.06), g, font=f, fill=255)
                tile = ImageOps.mirror(tile)
                v = rnd.randint(16, 36)
                img.paste(Image.new("RGB", (step, step), (v, v, v + 2)), (x, y), tile)
                d.rectangle([x, y, x + step - 1, y + step - 1], outline=(26, 26, 28))
        # 火光：右上角暖色辉光
        glow = Image.new("RGB", (w, h), accent)
        mask = Image.new("L", (w, h), 0)
        md = ImageDraw.Draw(mask)
        cx, cy, r = int(w * 0.78), int(h * 0.18), int(max(w, h) * 0.55)
        for i in range(60, 0, -1):
            rr = r * i / 60
            md.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=int(150 * (1 - i / 60) ** 1.6))
        img = Image.composite(glow, img, mask.filter(ImageFilter.GaussianBlur(40)))
    else:
        d.rectangle([0, 0, w, h], fill=BG)
        line = (40, 40, 44)
        for i in range(7):  # 电报线
            y = int(h * (0.08 + i * 0.045))
            d.line([(0, y + rnd.randint(-6, 6)), (w, y + rnd.randint(-30, 30))], fill=line, width=2)
        for _ in range(9):  # 邮戳
            cx, cy = rnd.randint(0, w), rnd.randint(int(h * 0.05), int(h * 0.6))
            r = rnd.randint(int(w * 0.06), int(w * 0.14))
            col = (rnd.randint(46, 66),) * 3
            d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=4)
            d.ellipse([cx - r * 0.72, cy - r * 0.72, cx + r * 0.72, cy + r * 0.72], outline=col, width=2)
            for k in range(-2, 3):
                yy = cy + k * r * 0.22
                d.line([(cx + r * 1.1, yy), (cx + r * 3.2, yy)], fill=col, width=3)
        glow = Image.new("RGB", (w, h), accent)
        mask = Image.new("L", (w, h), 0)
        md = ImageDraw.Draw(mask)
        cx, cy, r = int(w * 0.2), int(h * 0.22), int(max(w, h) * 0.5)
        for i in range(60, 0, -1):
            rr = r * i / 60
            md.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=int(120 * (1 - i / 60) ** 1.6))
        img = Image.composite(glow, img, mask.filter(ImageFilter.GaussianBlur(40)))
    return img


def photo_bg(path, w, h):
    im = Image.open(path).convert("RGB")
    im = ImageOps.fit(im, (w, h), Image.LANCZOS, centering=(0.5, 0.4))
    im = ImageOps.grayscale(im).convert("RGB")  # 统一单色，与深色界面一致
    return Image.blend(im, Image.new("RGB", (w, h), BG), 0.25)


def gradient(img, start=0.35):
    w, h = img.size
    mask = Image.new("L", (1, h))
    for y in range(h):
        t = max(0.0, (y / h - start) / (1 - start))
        mask.putpixel((0, y), int(255 * min(1.0, t ** 0.9 * 1.05)))
    mask = mask.resize((w, h))
    return Image.composite(Image.new("RGB", (w, h), BG), img, mask)


def wrap(draw, text, f, maxw):
    lines, cur = [], ""
    for ch in text:
        if draw.textlength(cur + ch, font=f) > maxw and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    return lines + ([cur] if cur else [])


def render(cfg, w, h, fonts, photo, out):
    img = photo_bg(photo, w, h) if photo else texture(w, h, cfg, fonts)
    img = gradient(img, 0.30 if h > w else 0.15)
    d = ImageDraw.Draw(img)
    m = int(min(w, h) * 0.075)
    unit = min(w, h)
    accent = tuple(cfg["accent"])
    # 标签：城市定向
    tf = font(fonts, "NotoSansSC-Medium.otf", int(unit * 0.03))
    tag = "城市定向"
    tw = d.textlength(tag, font=tf)
    pad = int(unit * 0.014)
    d.rounded_rectangle([m, m, m + tw + pad * 2.4, m + unit * 0.03 + pad * 2], radius=pad * 3, outline=FG, width=2)
    d.text((m + pad * 1.2, m + pad * 0.7), tag, font=tf, fill=FG)
    # 标题与副标题（底部左对齐）
    title = cfg["title"]
    tsz = int(unit * (0.15 if h >= w else 0.13))
    if h < w:
        tsz = int(unit * 0.15)
    f_t = font(fonts, "NotoSerifSC-Black.otf", tsz)
    f_s = font(fonts, "NotoSerifSC-Regular.otf", int(unit * 0.036))
    f_m = font(fonts, "NotoSansSC-Regular.otf", int(unit * 0.026))
    maxw = w - 2 * m if h >= w else int(w * 0.62)
    sub_lines = wrap(d, cfg["subtitle"], f_s, maxw)
    meta = cfg["meta"]
    y = h - m - int(unit * 0.026)
    d.text((m, y), meta, font=f_m, fill=FG2)
    y -= int(unit * 0.05)
    for ln in reversed(sub_lines):
        y -= int(unit * 0.052)
        d.text((m, y), ln, font=f_s, fill=FG)
    bar_h = max(4, int(unit * 0.006))
    y -= int(unit * 0.04) + bar_h
    d.rectangle([m, y, m + int(unit * 0.08), y + bar_h], fill=accent)
    tb = d.textbbox((0, 0), title, font=f_t)
    y -= int(unit * 0.035) + tb[3]
    d.text((m - int(tsz * 0.04), y), title, font=f_t, fill=FG)
    img.save(out, "JPEG", quality=88, optimize=True, progressive=True)


if __name__ == "__main__":
    cfg = json.load(open(sys.argv[1], encoding="utf-8"))["cover"]
    out_dir, fonts = sys.argv[2], sys.argv[3]
    photo = sys.argv[4] if len(sys.argv) > 4 else None
    os.makedirs(out_dir, exist_ok=True)
    for name, (w, h) in {"cover-3x4.jpg": (1500, 2000), "cover-16x9.jpg": (1920, 1080), "share-1x1.jpg": (1080, 1080)}.items():
        render(cfg, w, h, fonts, photo, os.path.join(out_dir, name))
        print("wrote", os.path.join(out_dir, name))
