"""从 Wikimedia Commons 下载站点配图，并在下载时用 Commons API 现场核验作者与许可。

  python3 fetch_images.py ../images/manifest.json ../images

需要网络放行 commons.wikimedia.org 与 upload.wikimedia.org。
- 许可不在 ALLOWED 内的文件一律跳过（记录在 skipped.json），不会落盘。
- 原图存 images/<route>/orig/，另出 2048px JPEG q85 网页版到 images/<route>/web/（去除全部 EXIF，含 GPS）。
- 自动生成 images/CREDITS.md 与 images/credits.json；总量超过 60 MB 时停止下载原图。
"""
import hashlib, io, json, os, re, sys, urllib.parse, urllib.request
from PIL import Image

API = "https://commons.wikimedia.org/w/api.php"
UA = "chengyin-city-routes/1.0 (content research; contact via repo owner)"
ALLOWED = ("CC0", "Public domain", "PD", "CC BY 2.0", "CC BY 2.5", "CC BY 3.0", "CC BY 4.0",
           "CC BY-SA 2.0", "CC BY-SA 2.5", "CC BY-SA 3.0", "CC BY-SA 4.0")
BUDGET = 60 * 1024 * 1024


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def strip_html(s):
    return re.sub(r"<[^>]+>", "", s or "").strip()


def info(filename):
    q = urllib.parse.urlencode({"action": "query", "format": "json", "titles": "File:" + filename,
                                "prop": "imageinfo", "iiprop": "url|size|extmetadata|sha1"})
    pages = json.loads(get(API + "?" + q))["query"]["pages"]
    page = next(iter(pages.values()))
    if "imageinfo" not in page:
        return None
    ii = page["imageinfo"][0]
    md = ii.get("extmetadata", {})
    return {
        "url": ii["url"], "page": ii.get("descriptionurl"), "width": ii["width"], "height": ii["height"],
        "license": strip_html(md.get("LicenseShortName", {}).get("value")),
        "author": strip_html(md.get("Artist", {}).get("value")) or "佚名",
        "credit": strip_html(md.get("Credit", {}).get("value")),
        "attribution_required": md.get("AttributionRequired", {}).get("value"),
    }


def main(manifest, out):
    items = json.load(open(manifest, encoding="utf-8"))
    used, credits, skipped = 0, [], []
    for it in items:
        meta = info(it["file"])
        if not meta:
            skipped.append({**it, "reason": "file not found"}); continue
        if not meta["license"].startswith(ALLOWED):
            skipped.append({**it, "reason": "license " + meta["license"]}); continue
        raw = get(meta["url"])
        im = Image.open(io.BytesIO(raw)); im.load()
        rdir = os.path.join(out, it["route"])
        os.makedirs(os.path.join(rdir, "orig"), exist_ok=True)
        os.makedirs(os.path.join(rdir, "web"), exist_ok=True)
        base = it["slug"]
        if used + len(raw) <= BUDGET:
            # 原图重新编码一次以去除 EXIF（GPS），JPEG 保持高质量
            src = im.convert("RGB")
            p = os.path.join(rdir, "orig", base + ".jpg")
            src.save(p, "JPEG", quality=95, subsampling=0)
            used += os.path.getsize(p)
        web = im.convert("RGB")
        web.thumbnail((2048, 2048), Image.LANCZOS)
        web.save(os.path.join(rdir, "web", base + ".jpg"), "JPEG", quality=85, optimize=True, progressive=True)
        credits.append({**it, **meta, "sha256": hashlib.sha256(raw).hexdigest()})
        print("ok", it["file"], meta["license"], meta["width"], "x", meta["height"])
    json.dump(credits, open(os.path.join(out, "credits.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(skipped, open(os.path.join(out, "skipped.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(out, "CREDITS.md"), "w", encoding="utf-8") as f:
        f.write("# 图片署名（下载时由 Commons API 自动核验）\n\n")
        for c in credits:
            f.write(f"- `{c['route']}/web/{c['slug']}.jpg`（{c['station']}）：{c['author']} / Wikimedia Commons / {c['license']} — {c['page']}\n")
        f.write("\n## 主题简介页脚可直接粘贴\n\n")
        for r in sorted({c['route'] for c in credits}):
            f.write(f"### {r}\n图片：" + "；".join(f"{c['author']}（{c['license']}）" for c in credits if c['route'] == r) + "。来源 Wikimedia Commons。\n\n")
    print("bytes(orig)", used, "skipped", len(skipped))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
