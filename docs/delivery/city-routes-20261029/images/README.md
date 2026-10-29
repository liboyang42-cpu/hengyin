# images/

- `covers/<线路>/`：已生成的主题封面（竖版 3:4 1500×2000、横版 16:9 1920×1080、分享图 1:1 1080×1080）。
- `contact-sheet-covers.png`：封面审阅总览。
- `manifest.json`：每站要下载的 Commons 文件（线路、站、文件代号、文件名）。
- `CREDITS.md`：署名。当前是候选清单，下载后由脚本覆盖为核验结果。

## 下载站点照片（需要能访问 Wikimedia）

```sh
python3 -m venv .venv && .venv/bin/pip install pillow
.venv/bin/python tools/fetch_images.py images/manifest.json images
# 生成 images/<线路>/orig/*.jpg（原图重新编码，去掉 EXIF 和 GPS）、images/<线路>/web/*.jpg（长边 2048 px，JPEG 质量 85）、
# 以及 images/CREDITS.md、credits.json、skipped.json；原图总量超过 60 MB 时不再保存原图。
.venv/bin/python tools/contact_sheet.py images/contact-sheet-stations.png <字体目录> images/commercial-press/web images/post/web
```

如果要做照片版封面：`tools/cover.py <封面配置> <输出目录> <字体目录> <照片.jpg>`。照片会被转成黑白，再叠上深色渐变。
