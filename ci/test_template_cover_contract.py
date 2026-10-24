#!/usr/bin/env python3
"""模板/主题封面产出契约门禁。

2026-08-25 二次换轨:自绘平涂场景 → 现成的 CC0 像素城市场景(ansimuz)+ 现成的
游戏图标(game-icons.net)。自绘那版被推翻有两条原因:①不像游戏;②56 张全是
「天际线 + 火柴人」一个配方,看不出哪张是什么内容。
(更早一版是 Pexels 实拍照,因为 24 个图源要覆盖 43 个位置必然复用而退役。)
★ 「内容不许重复」这条硬闸从上一版继承下来:56 个 sha256 必须互不相同;
   渲染脚本里另有「取景 / 图标不得复用」的断言,两层各管一段。
★ 图标层是 CC BY 3.0,**署名是许可条件**:小程序设置页内必须有署名区块,这里也钉住。

本门禁只校验已提交产物与 manifest 一致,不重渲(渲染依赖 macOS qlmanage 与
Pillow,CI 上没有)。「重跑是否产出同样的 sha256」由本机执行:
    python3 ci/render_template_covers.py --check
"""

import hashlib
import json
import re
import struct
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[1]
ASSET_ROOT = REPO / "docs/assets/template-covers"
FINAL_ROOT = ASSET_ROOT / "final"
MANIFEST = ASSET_ROOT / "manifest.json"
RENDERER = REPO / "ci/render_template_covers.py"
MIGRATION = REPO / "chengyinhub-admin/sql/migration_20260822_template_cover_images.sql"
PRODUCTION_BASE = "https://www.chengyinhub.com/prod-api/profile/template-covers/"
MAX_BYTES = 180_000

# 换轨后不允许再出现的实拍来源字段;混进来说明有人把照片管线接回来了
RETIRED_PHOTO_FIELDS = ("sourceProvider", "sourceId", "sourcePage", "photographer",
                        "licenseUrl", "sourceSha256")

# game-icons 的目录名 → 上游 license.txt 里的署名写法。新增作者必须在这里登记,
# 否则上一条断言直接判红(而不是悄悄放行一个没署名的作者)。
ARTIST_DISPLAY_NAME = {
    "delapouite": "Delapouite", "lorc": "Lorc", "skoll": "Skoll", "sbed": "Sbed",
    "quoting": "Quoting", "lord-berandas": "Lord Berandas", "faithtoken": "Faithtoken",
    "willdabeast": "Willdabeast", "john-colburn": "John Colburn", "felbrigg": "Felbrigg",
    "carl-olsen": "Carl Olsen", "priorblue": "PriorBlue", "irongamer": "Irongamer",
    "heavenly-dog": "HeavenlyDog", "caro-asercion": "Caro Asercion", "cathelineau": "Cathelineau",
    "darkzaitzev": "DarkZaitzev", "zeromancer": "Zeromancer", "viscious-speed": "Viscious Speed",
    "andymeneely": "Andy Meneely", "aussiesim": "Aussiesim", "sparker": "Sparker",
    "rihlsul": "Rihlsul", "guard13007": "Guard13007", "spencerdub": "SpencerDub",
    "generalace135": "GeneralAce135", "zajkonur": "Zajkonur", "catsu": "Catsu",
    "starseeker": "Starseeker", "pepijn-poolman": "Pepijn Poolman", "john-redman": "John Redman",
    "kier-heyl": "Kier Heyl", "lucasms": "Lucas", "pierre-leducq": "Pierre Leducq",
    "seregacthtuf": "SeregaCthtuf", "various-artists": "Various artists", "badges": "Badges",
}

SOURCES_DOC = ASSET_ROOT / "SOURCES.md"
ATTRIBUTION_VIEW = REPO / "chengyinhub-xcx/pages/shezhi/about/index.wxml"
APP_JSON = REPO / "chengyinhub-xcx/app.json"
PIXEL_ROOT = ASSET_ROOT / "sources-pixel"
ICON_ROOT = ASSET_ROOT / "sources-icons"

EXPECTED_LIBRARY_TITLES = {
    "今晚的暗号", "老板的秘密题", "拍下这一刻", "收集这枚风味印章",
    "新品盲测局", "今日搭配任务", "集合点亮", "小队竞速点亮", "街角谜题",
    "城市取景任务", "城市接力棒", "今日角色任务", "G0｜30秒生活掉线测试",
    "G1｜现在出状况,你先选什么", "G2｜我的底线三选一", "G3｜营业时间拼图",
    "G4｜去之前准备什么", "G5｜值不值得现在处理", "G6｜首选失效怎么办",
    # 今天值得庆祝 G0–G7:2026-08-25 补封面。此前只有图标没有封面,
    # 而 bannerList/latestList 读 item.imgUrl,缺封面会渲染成「封面暂不可用」错误卡。
    "G0｜30秒今天值得什么", "G1｜每人一个不能", "G2｜气氛卡碰撞", "G3｜三小时时间方块",
    "G4｜每人预算筹码", "G5｜十分钟步行上限", "G6｜主选突然掉线", "G7｜60秒共同落款",
}

EXPECTED_TOPIC_TITLES = {
    "隐藏菜单", "店内寻宝", "拍照挑战", "神秘店员的暗号", "三步解谜", "听音辨物",
    "三连快答", "集章巡店", "岔路口的选择", "三个盲盒", "闭眼味觉师",
    "菜单顺口溜", "沉默点单", "便利店调酒师", "逆向NPC", "爪印巡街",
    "治愈音乐角", "穿搭盲配", "生活不掉线", "下班后切换频道", "一个人也出发",
    "亲子各自开心", "今天拍点好看的", "夜猫子城市生活",
    # 上海单人自由探索 5 主题:2026-08-25 从 Wikimedia 实拍照换成自绘场景 key art,
    # 与其余 51 张同一套画法,描述里的「封面摄影：…」署名同批撤销。
    "风味巡游", "不惑之年", "预制人生", "高温末日逃生", "上海像一部旧电影",
}


def read_webp_size(path: Path):
    """从 VP8X / VP8L / 有损 VP8 头里读尺寸,不依赖任何第三方库。"""
    data = path.read_bytes()
    if len(data) < 30 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise AssertionError(f"not a WebP file: {path.name}")
    pos = 12
    while pos + 8 <= len(data):
        kind = data[pos:pos + 4]
        size = struct.unpack_from("<I", data, pos + 4)[0]
        payload = data[pos + 8:pos + 8 + size]
        if kind == b"VP8X" and len(payload) >= 10:
            return 1 + int.from_bytes(payload[4:7], "little"), 1 + int.from_bytes(payload[7:10], "little")
        if kind == b"VP8L" and len(payload) >= 5 and payload[0] == 0x2F:
            bits = int.from_bytes(payload[1:5], "little")
            return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
        if kind == b"VP8 " and len(payload) >= 10 and payload[3:6] == b"\x9d\x01\x2a":
            return struct.unpack_from("<H", payload, 6)[0] & 0x3FFF, struct.unpack_from("<H", payload, 8)[0] & 0x3FFF
        pos += 8 + size + (size & 1)
    raise AssertionError(f"WebP dimensions unavailable: {path.name}")


class TemplateCoverContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.assets = self.manifest["assets"]

    # ── 产出来源:manifest 必须钉死渲染脚本本身,改了脚本不重渲就红
    def test_provenance_is_ready_made_licensed_art(self):
        self.assertEqual(3, self.manifest["schemaVersion"])
        self.assertEqual(PRODUCTION_BASE, self.manifest["productionBaseUrl"])
        self.assertIn("ready-made CC0 pixel-art city scenes", self.manifest["style"])
        self.assertIn("no photographic or generative image assets", self.manifest["style"])
        self.assertIn("ci/render_template_covers.py", self.manifest["generatedBy"])
        self.assertEqual("docs/assets/template-covers/SOURCES.md", self.manifest["sourcesDoc"])
        self.assertTrue(SOURCES_DOC.is_file(), "素材台账缺失")
        self.assertIn("pages/shezhi/shezhi", SOURCES_DOC.read_text(encoding="utf-8"),
                      "台账里的署名区块路径必须与真实页面一致")
        self.assertEqual(
            hashlib.sha256(RENDERER.read_bytes()).hexdigest(),
            self.manifest["rendererSha256"],
            "渲染脚本改过但没重跑:manifest.rendererSha256 与 ci/render_template_covers.py 不符",
        )

    # ── CC BY 是有条件的许可:署名页缺了,授权就不成立
    def test_cc_by_icons_have_an_in_app_attribution_section(self):
        self.assertIn("CC-BY-3.0", self.manifest["iconLicense"])
        self.assertIn("CC0-1.0", self.manifest["pixelLicense"])
        self.assertTrue(ATTRIBUTION_VIEW.is_file(), "CC BY 图标必须有小程序内署名区块")
        page = ATTRIBUTION_VIEW.read_text(encoding="utf-8")
        self.assertIn("game-icons.net", page)
        self.assertIn("Creative Commons BY 3.0", page)
        # ★ 作者名单从 manifest 实际用到的图标反推,不写死 —— 写死等于「第七位作者的图标
        #   零署名混进来,门禁照样绿」,那是假保证不是门禁。
        for folder in sorted({row["icon"].split("/")[0] for row in self.assets}):
            display = ARTIST_DISPLAY_NAME.get(folder)
            self.assertIsNotNone(display, f"新作者 {folder} 未登记显示名,署名页无法逐字核对")
            self.assertIn(display, page, f"署名页缺少作者 {display}(来自 {folder}/)")
        app_config = json.loads(APP_JSON.read_text(encoding="utf-8"))
        settings_registered = any(
            package.get("root") == "pages/shezhi" and "about/index" in package.get("pages", [])
            for package in app_config.get("subPackages", [])
        )
        self.assertTrue(settings_registered,
                        "承载署名的关于页没在 app.json 注册 = 用户根本打不开")

    # ── 素材必须真的在仓库里:渲染脚本引用的每个图层/图标都要能找到
    def test_every_referenced_source_asset_is_vendored(self):
        renderer = RENDERER.read_text(encoding="utf-8")
        layers = set(re.findall(r'\("((?:synth|miami|warped)/[a-z0-9-]+\.png)"', renderer))
        self.assertTrue(layers, "渲染脚本里没解析到任何图层引用")
        for rel in sorted(layers):
            self.assertTrue((PIXEL_ROOT / rel).is_file(), f"图层未入库:{rel}")
        icons = {row["icon"] for row in self.assets}
        self.assertEqual(56, len(icons), "56 张封面必须用 56 枚不同图标")
        for rel in sorted(icons):
            self.assertTrue((ICON_ROOT / rel).is_file(), f"图标未入库:{rel}")

    # ── 取景不得复用:上一版就是「一个配方套 56 张」栽的
    def test_every_cover_has_its_own_framing(self):
        framings = [(r["scene"], r["framing"]) for r in self.assets]
        self.assertEqual(56, len(set(framings)), "有封面共用同一个取景")

    # ── 核心新闸:内容不许重复。旧门禁只查文件名,放行了「一张图用 5 次」
    def test_every_cover_is_visually_distinct(self):
        by_digest = {}
        for row in self.assets:
            by_digest.setdefault(row["sha256"], []).append(row["file"])
        dupes = {d: f for d, f in by_digest.items() if len(f) > 1}
        self.assertEqual({}, dupes, f"封面内容重复:{dupes}")
        self.assertEqual(56, len(by_digest))

    def test_manifest_covers_every_template_exactly_once(self):
        self.assertEqual(56, len(self.assets))
        self.assertEqual(EXPECTED_LIBRARY_TITLES,
                         {r["title"] for r in self.assets if r["kind"] == "library"})
        self.assertEqual(EXPECTED_TOPIC_TITLES,
                         {r["title"] for r in self.assets if r["kind"] == "topic"})
        files = [r["file"] for r in self.assets]
        self.assertEqual(len(files), len(set(files)), "cover filenames must be unique")
        self.assertEqual(set(files), {p.name for p in FINAL_ROOT.glob("*.webp")},
                         "final/ 目录与 manifest 不一致(有多余或缺失文件)")

    def test_each_file_matches_its_manifest_row(self):
        for row in self.assets:
            path = FINAL_ROOT / row["file"]
            with self.subTest(cover=row["file"]):
                self.assertTrue(path.is_file(), path)
                self.assertEqual((960, 540), read_webp_size(path))
                self.assertEqual(960, row["width"])
                self.assertEqual(540, row["height"])
                self.assertEqual(path.stat().st_size, row["bytes"])
                self.assertLessEqual(path.stat().st_size, MAX_BYTES)
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), row["sha256"])
                self.assertEqual(PRODUCTION_BASE + row["file"], row["productionUrl"])
                for dead in RETIRED_PHOTO_FIELDS:
                    self.assertNotIn(dead, row, f"实拍来源字段已退役,不该再出现:{dead}")

    # ── 落库映射:小程序读的是 imgUrl,SQL 少一条就有模板没封面
    def test_migration_maps_every_cover(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("-- runner: requires=transaction", sql)
        rows = re.findall(
            r"\('(?P<kind>library|topic)',\s*'(?P<title>[^']+)',\s*"
            r"'(?P<url>https://www\.chengyinhub\.com/prod-api/profile/template-covers/[^']+\.webp)'\)",
            sql,
        )
        self.assertEqual(56, len(rows), "migration must contain one source-map row per cover")
        self.assertEqual({(r["kind"], r["title"]): r["productionUrl"] for r in self.assets},
                         {(k, t): u for k, t, u in rows})


if __name__ == "__main__":
    unittest.main()
