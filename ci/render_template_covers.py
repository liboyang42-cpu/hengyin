#!/usr/bin/env python3
"""模板/主题封面渲染器 —— 现成的 CC0 像素城市场景 + 现成的游戏图标,本仓不自绘。

为什么不是自绘:上一版 56 张全是「天际线 + 火柴人剪影」一个配方,既不像游戏,也看不出
哪张是什么内容(「今晚的暗号」和「街角谜题」长一个样)。这版换成 ansimuz 的 CC0 像素
城市成品图层做底,game-icons.net 的游戏图标做内容标记,一图一条内容。素材来源、授权与
sha256 见 docs/assets/template-covers/SOURCES.md。

画布 240x135 逻辑像素,最近邻放大 4 倍到 960x540 —— 像素图必须整数倍放大,插值会糊掉。

取景 = 配方 + 横向位移 + 纵向位移。五个配方共 69 个互不重样的取景,56 张各占一个;
「取景不得复用」是 build() 里的硬断言,不是注释里的口头承诺。

渲染依赖 macOS 的 qlmanage(把图标 SVG 光栅化成像素)与 Pillow,因此重跑只在 Mac 上
可复现;CI 侧不重渲,只校验已提交产物与 manifest 一致(见 ci/test_template_cover_contract.py)。

用法:
    python3 ci/render_template_covers.py            # 渲染 + 覆盖 final/ + 重写 manifest
    python3 ci/render_template_covers.py --check    # 只重渲到临时目录,比对 sha256 是否漂移
"""

import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[1]
ASSET_ROOT = REPO / "docs/assets/template-covers"
FINAL_ROOT = ASSET_ROOT / "final"
PIXEL_ROOT = ASSET_ROOT / "sources-pixel"
ICON_ROOT = ASSET_ROOT / "sources-icons"
MANIFEST = ASSET_ROOT / "manifest.json"
PRODUCTION_BASE = "https://www.chengyinhub.com/prod-api/profile/template-covers/"

PW, PH, SCALE = 240, 135, 4          # 逻辑像素 -> 960x540
W, H = PW * SCALE, PH * SCALE
MAX_BYTES = 180_000

STYLE = ("ready-made CC0 pixel-art city scenes (ansimuz) + CC BY 3.0 game icons "
         "(game-icons.net); composited by ci/render_template_covers.py; "
         "no photographic or generative image assets")

_layers = {}


def layer(rel):
    if rel not in _layers:
        _layers[rel] = Image.open(PIXEL_ROOT / rel).convert("RGBA")
    return _layers[rel]


def band(img, ox, top):
    """把一层按横向位移平铺进 PW x PH 的带子。层比画布窄时循环续上,不留缝。"""
    out = Image.new("RGBA", (PW, PH), (0, 0, 0, 0))
    x = -(ox % img.width)
    while x < PW:
        out.alpha_composite(img, (x, top))
        x += img.width
    return out


# 配方:(图层, 视差系数, 该层相对基线的额外 y)。系数越大越靠前、位移越大。
RECIPES = {
    "street": [("synth/v2-back.png", .20, 0), ("synth/v2-middle.png", .50, 0),
               ("synth/v2-foreground.png", 1.0, 0)],
    "shops":  [("synth/v1-back-buildings.png", .30, 0), ("synth/v1-far-buildings.png", .60, 0),
               ("synth/v1-foreground.png", 1.0, 0)],
    "dusk":   [("synth/sky-back.png", .25, 0), ("synth/sky-buildings.png", .55, 0),
               ("synth/sky-front.png", 1.0, 0)],
    "miami":  [("miami/sun.png", .15, 0), ("miami/back.png", .30, 0), ("miami/buildings.png", .50, 0),
               ("miami/palms.png", .80, 0), ("miami/highway.png", 1.0, 0)],
    "towers": [("warped/skyline-a.png", .20, 0), ("warped/buildings-bg.png", .50, 70),
               ("warped/near-buildings-bg.png", 1.0, 0)],
}
# 每个配方的取景基线:把图层里「有内容」的那一段对准画布,别露出大片空路面/空天。
BASE_Y = {"street": -137, "shops": -57, "dusk": -57, "miami": -105, "towers": -74}

NEON = {"cyan": (94, 240, 255), "magenta": (255, 92, 190), "amber": (255, 186, 74),
        "lime": (150, 255, 140), "violet": (186, 150, 255)}

# 内容牌固定居中。列表小图按 4:3 裁,左右各切掉 (PW - PH*4/3)/2,牌子必须落在剩下的安全区里。
# ★ 这是模块级的一次性不变量,不是每张封面重判一遍 —— 它只在有人改动 PW/PLATE 常量时变红。
#   写在 cover() 里逐张判等于恒真断言:bx 由常量算出,56 张的取值完全一样。
PLATE = 62
PLATE_X = (PW - PLATE) // 2
_SAFE_L = (PW - PH * 4 // 3) // 2
_SAFE_R = PW - _SAFE_L
if not (_SAFE_L <= PLATE_X and PLATE_X + PLATE <= _SAFE_R):
    raise SystemExit("内容牌越界:x=%d..%d(4:3 裁切后安全区 %d..%d)"
                     % (PLATE_X, PLATE_X + PLATE, _SAFE_L, _SAFE_R))


def pixel_icon(rel, size=42):
    """图标在小尺寸光栅化 —— 出来就是真像素,而不是缩小的矢量。

    game-icons 的 SVG 自带一条 512x512 的黑底方块 + 白色图形,所以「亮 = 图形」。
    刻意不去掉黑底:去掉后 qlmanage 会把透明底压成白色,整块变成实心白。
    """
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "i.svg").write_bytes((ICON_ROOT / rel).read_bytes())
        subprocess.run(["qlmanage", "-t", "-s", str(size), "-o", str(tmp), str(tmp / "i.svg")],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        im = Image.open(tmp / "i.svg.png").convert("RGB").resize((size, size), Image.NEAREST)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px, op = im.load(), out.load()
    for y in range(size):
        for x in range(size):
            if sum(px[x, y]) > 330:
                op[x, y] = (255, 255, 255, 255)
    return out


def cover(icon_rel, recipe, shift, yoff, neon):
    c = Image.new("RGBA", (PW, PH), (8, 6, 14, 255))
    for rel, factor, extra in RECIPES[recipe]:
        c.alpha_composite(band(layer(rel), int(shift * factor), BASE_Y[recipe] + yoff + extra))

    col = NEON[neon]
    bx, by = PLATE_X, 11
    plate = Image.new("RGBA", (PLATE, PLATE), (0, 0, 0, 0))
    d = ImageDraw.Draw(plate)
    d.rectangle((0, 0, PLATE - 1, PLATE - 1), fill=(10, 8, 18, 215), outline=col + (255,), width=2)
    d.rectangle((3, 3, PLATE - 4, PLATE - 4), outline=col + (90,), width=1)
    plate.alpha_composite(pixel_icon(icon_rel), (10, 10))

    glow = Image.new("RGBA", (PW, PH), (0, 0, 0, 0))
    ImageDraw.Draw(glow).rectangle((bx - 3, by - 3, bx + PLATE + 2, by + PLATE + 2), fill=col + (38,))
    c.alpha_composite(glow)
    c.alpha_composite(plate, (bx, by))
    ImageDraw.Draw(c).line((PW // 2, by + PLATE, PW // 2, by + PLATE + 9), fill=col + (200,), width=1)
    return c.convert("RGB").resize((W, H), Image.NEAREST)


# ─── 56 条配方:文件名 -> (类别, 标题, 图标, 场景, 横移, 纵移, 霓虹色)
def build():
    S = {
        # 商家 / 俱乐部官方玩法
        "p01-tonights-passcode":        ("library", "今晚的暗号", "lorc/key.svg", "street", 140, 120, "amber"),
        "p02-boss-secret-question":     ("library", "老板的秘密题", "delapouite/secret-book.svg", "shops", 0, 30, "magenta"),
        "p03-capture-this-moment":      ("library", "拍下这一刻", "delapouite/photo-camera.svg", "shops", 180, 30, "cyan"),
        "p04-flavor-stamp":             ("library", "收集这枚风味印章", "delapouite/stamper.svg", "miami", 420, 105, "amber"),
        "p05-new-product-blind-test":   ("library", "新品盲测局", "delapouite/blindfold.svg", "street", 280, 60, "lime"),
        "p06-todays-styling-task":      ("library", "今日搭配任务", "delapouite/clothes.svg", "dusk", 0, 28, "magenta"),
        "p07-rally-light-up":           ("library", "集合点亮", "lorc/rally-the-troops.svg", "towers", 0, 37, "cyan"),
        "p08-team-race-light-up":       ("library", "小队竞速点亮", "lorc/stopwatch.svg", "miami", 140, 105, "magenta"),
        "p09-street-corner-riddle":     ("library", "街角谜题", "delapouite/puzzle.svg", "towers", 120, 37, "violet"),
        "p10-city-framing-task":        ("library", "城市取景任务", "delapouite/binoculars.svg", "towers", 360, 74, "lime"),
        "p11-city-relay":               ("library", "城市接力棒", "skoll/baton.svg", "miami", 0, 105, "magenta"),
        "p12-todays-role-task":         ("library", "今日角色任务", "lorc/drama-masks.svg", "dusk", 70, 28, "lime"),
        # 生活备份 G0-G6
        "p13-life-outage-test":         ("library", "G0｜30秒生活掉线测试", "sbed/unplugged.svg", "street", 420, 60, "cyan"),
        "p14-what-to-choose-first":     ("library", "G1｜现在出状况,你先选什么", "delapouite/choice.svg", "shops", 90, 57, "amber"),
        "p15-bottom-line-three-choices": ("library", "G2｜我的底线三选一", "delapouite/weight-scale.svg", "dusk", 140, 57, "violet"),
        "p16-business-hours-puzzle":    ("library", "G3｜营业时间拼图", "delapouite/alarm-clock.svg", "towers", 240, 0, "amber"),
        "p17-before-you-go-checklist":  ("library", "G4｜去之前准备什么", "delapouite/checklist.svg", "shops", 270, 0, "lime"),
        "p18-worth-doing-now":          ("library", "G5｜值不值得现在处理", "lorc/hourglass.svg", "street", 560, 60, "violet"),
        "p19-first-choice-offline":     ("library", "G6｜首选失效怎么办", "delapouite/crossroad.svg", "dusk", 210, 0, "cyan"),
        # 今天值得庆祝 G0-G7
        "p20-worth-today-30s":          ("library", "G0｜30秒今天值得什么", "delapouite/party-popper.svg", "miami", 700, 105, "amber"),
        "p21-one-cannot-each":          ("library", "G1｜每人一个不能", "sbed/cancel.svg", "street", 0, 120, "magenta"),
        "p22-mood-card-clash":          ("library", "G2｜气氛卡碰撞", "quoting/card-play.svg", "shops", 180, 0, "violet"),
        "p23-three-hour-blocks":        ("library", "G3｜三小时时间方块", "lorc/cubes.svg", "towers", 0, 74, "cyan"),
        "p24-budget-chips":             ("library", "G4｜每人预算筹码", "lorc/poker-hand.svg", "miami", 280, 50, "amber"),
        "p25-ten-minute-walk":          ("library", "G5｜十分钟步行上限", "lorc/walking-boot.svg", "dusk", 70, 57, "lime"),
        "p26-main-choice-offline":      ("library", "G6｜主选突然掉线", "lord-berandas/power-button.svg", "street", 560, 0, "magenta"),
        "p27-cosign-60s":               ("library", "G7｜60秒共同落款", "lorc/quill-ink.svg", "shops", 0, 0, "cyan"),
        # 完整主题
        "t01-hidden-menu":              ("topic", "隐藏菜单", "lorc/scroll-unfurled.svg", "shops", 270, 30, "amber"),
        "t02-in-store-treasure-hunt":   ("topic", "店内寻宝", "lorc/treasure-map.svg", "street", 280, 120, "lime"),
        "t03-photo-challenge":          ("topic", "拍照挑战", "delapouite/video-camera.svg", "miami", 560, 105, "cyan"),
        "t04-mysterious-clerk-passcode": ("topic", "神秘店员的暗号", "lorc/padlock.svg", "street", 0, 60, "violet"),
        "t05-three-step-puzzle":        ("topic", "三步解谜", "delapouite/stairs.svg", "towers", 120, 0, "lime"),
        "t06-listen-and-identify":      ("topic", "听音辨物", "skoll/sound-waves.svg", "dusk", 140, 28, "cyan"),
        "t07-three-quick-answers":      ("topic", "三连快答", "delapouite/whistle.svg", "shops", 90, 0, "magenta"),
        "t08-stamp-tour":               ("topic", "集章巡店", "delapouite/passport.svg", "miami", 140, 50, "amber"),
        "t09-fork-in-the-road":         ("topic", "岔路口的选择", "delapouite/direction-signs.svg", "towers", 240, 37, "cyan"),
        "t10-three-blind-boxes":        ("topic", "三个盲盒", "skoll/open-treasure-chest.svg", "street", 420, 120, "magenta"),
        "t11-blind-taste-master":       ("topic", "闭眼味觉师", "delapouite/tongue.svg", "dusk", 0, 57, "magenta"),
        "t12-menu-tongue-twister":      ("topic", "菜单顺口溜", "lorc/conversation.svg", "shops", 180, 57, "lime"),
        "t13-silent-ordering":          ("topic", "沉默点单", "delapouite/silenced.svg", "street", 140, 60, "violet"),
        "t14-convenience-store-mixologist": ("topic", "便利店调酒师", "lorc/wine-glass.svg", "miami", 420, 50, "cyan"),
        "t15-reverse-npc":              ("topic", "逆向NPC", "delapouite/arrow-cursor.svg", "towers", 360, 0, "magenta"),
        "t16-pawprint-street-tour":     ("topic", "爪印巡街", "lorc/paw.svg", "dusk", 210, 28, "lime"),
        "t17-healing-music-corner":     ("topic", "治愈音乐角", "delapouite/musical-notes.svg", "shops", 0, 57, "cyan"),
        "t18-blind-style-mix":          ("topic", "穿搭盲配", "skoll/dress.svg", "miami", 0, 50, "violet"),
        "t19-life-backup":              ("topic", "生活不掉线", "delapouite/wifi-router.svg", "street", 0, 0, "lime"),
        "t20-after-work-switch":        ("topic", "下班后切换频道", "delapouite/tv.svg", "towers", 0, 0, "amber"),
        "t21-solo-departure":           ("topic", "一个人也出发", "delapouite/walking-scout.svg", "miami", 700, 50, "cyan"),
        "t22-parent-child-happy":       ("topic", "亲子各自开心", "delapouite/family-house.svg", "dusk", 70, 0, "amber"),
        "t23-photo-day":                ("topic", "今天拍点好看的", "lorc/sunrise.svg", "shops", 270, 57, "amber"),
        "t24-night-owl-city":           ("topic", "夜猫子城市生活", "lorc/owl.svg", "street", 560, 120, "violet"),
        # 上海单人自由探索 5 主题
        "t25-shanghai-flavor-tour":     ("topic", "风味巡游", "delapouite/hot-meal.svg", "miami", 280, 105, "amber"),
        "t26-shanghai-midlife-day":     ("topic", "不惑之年", "lorc/empty-hourglass.svg", "dusk", 140, 0, "violet"),
        "t27-shanghai-prebuilt-life":   ("topic", "预制人生", "delapouite/factory.svg", "towers", 120, 74, "cyan"),
        "t28-shanghai-heat-escape":     ("topic", "高温末日逃生", "delapouite/thermometer-hot.svg", "miami", 560, 50, "magenta"),
        "t29-shanghai-old-movie":       ("topic", "上海像一部旧电影", "delapouite/film-projector.svg", "street", 280, 0, "violet"),
    }

    # 取景与图标都不许复用 —— 上一版就是「一个配方套 56 张」栽的,这里用断言钉死,
    # 而不是靠注释里的自觉。
    framings = [(v[3], v[4], v[5]) for v in S.values()]
    dup = sorted({f for f in framings if framings.count(f) > 1})
    if dup:
        raise SystemExit("取景复用:%s" % (dup,))
    icons = [v[2] for v in S.values()]
    dup = sorted({i for i in icons if icons.count(i) > 1})
    if dup:
        raise SystemExit("图标复用:%s" % (dup,))
    return S


# ─── 导出
def render(scenes, out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, (_, _, icon, recipe, shift, yoff, neon) in scenes.items():
        cover(icon, recipe, shift, yoff, neon).save(
            out_dir / ("%s.webp" % name), "WEBP", quality=88, method=6)


def sha(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true",
                    help="只重渲到临时目录并比对 sha256,不落盘")
    args = ap.parse_args()
    scenes = build()
    if len(scenes) != 56:
        raise SystemExit("expected 56 scenes, built %d" % len(scenes))

    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            render(scenes, Path(tmp))
            drift = [n for n in scenes
                     if sha(Path(tmp) / ("%s.webp" % n)) != sha(FINAL_ROOT / ("%s.webp" % n))]
        if drift:
            raise SystemExit("sha256 漂移(%d 张):%s ..." % (len(drift), ", ".join(sorted(drift)[:5])))
        print("复现校验通过:%d 张 sha256 与仓库一致" % len(scenes))
        return

    render(scenes, FINAL_ROOT)
    assets = []
    for name, (kind, title, icon, recipe, shift, yoff, neon) in scenes.items():
        path = FINAL_ROOT / ("%s.webp" % name)
        size = path.stat().st_size
        if size > MAX_BYTES:
            raise SystemExit("%s 超过体积上限:%d > %d" % (name, size, MAX_BYTES))
        assets.append({
            "kind": kind, "title": title, "file": path.name,
            "width": W, "height": H, "bytes": size, "sha256": sha(path),
            "scene": recipe, "framing": "x%dy%d" % (shift, yoff), "neon": neon,
            "icon": icon,
            "productionUrl": PRODUCTION_BASE + path.name,
        })
    MANIFEST.write_text(json.dumps({
        "schemaVersion": 3,
        "generatedBy": "ci/render_template_covers.py",
        "style": STYLE,
        "sourcesDoc": "docs/assets/template-covers/SOURCES.md",
        "pixelLicense": "CC0-1.0 (ansimuz) — no attribution required",
        "iconLicense": "CC-BY-3.0 (game-icons.net) — attribution required",
        "productionBaseUrl": PRODUCTION_BASE,
        "rendererSha256": sha(Path(__file__)),
        "assets": assets,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("导出 %d 张,manifest 已更新" % len(assets))


if __name__ == "__main__":
    main()
