"""拼接审阅用缩略图总览。

  python3 contact_sheet.py <out.png> <fonts_dir> <图片或目录>...
目录会展开为其中的 .jpg/.png（按文件名排序）。每格下方写相对路径与像素尺寸。
"""
import os, sys
from PIL import Image, ImageDraw, ImageFont, ImageOps

CELL, PAD, LABEL = 420, 24, 64
BG, FG, FG2 = (12, 12, 14), (248, 248, 248), (150, 150, 150)


def collect(args):
    out = []
    for a in args:
        if os.path.isdir(a):
            out += [os.path.join(a, f) for f in sorted(os.listdir(a)) if f.lower().endswith((".jpg", ".jpeg", ".png"))]
        else:
            out.append(a)
    return out


def main(out, fonts, *paths):
    files = collect(paths)
    cols = min(3, len(files)) or 1
    rows = (len(files) + cols - 1) // cols
    W = cols * (CELL + PAD) + PAD
    H = rows * (CELL + LABEL + PAD) + PAD
    sheet = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(sheet)
    f1 = ImageFont.truetype(os.path.join(fonts, "NotoSansSC-Medium.otf"), 20)
    f2 = ImageFont.truetype(os.path.join(fonts, "NotoSansSC-Regular.otf"), 16)
    for i, p in enumerate(files):
        im = Image.open(p)
        size = im.size
        th = ImageOps.contain(im.convert("RGB"), (CELL, CELL))
        x = PAD + (i % cols) * (CELL + PAD)
        y = PAD + (i // cols) * (CELL + LABEL + PAD)
        sheet.paste(th, (x + (CELL - th.width) // 2, y + (CELL - th.height) // 2))
        d.text((x, y + CELL + 8), os.path.basename(os.path.dirname(p)) + "/" + os.path.basename(p), font=f1, fill=FG)
        d.text((x, y + CELL + 36), f"{size[0]}×{size[1]}", font=f2, fill=FG2)
    sheet.save(out)
    print("wrote", out, len(files), "images")


if __name__ == "__main__":
    main(*sys.argv[1:])
