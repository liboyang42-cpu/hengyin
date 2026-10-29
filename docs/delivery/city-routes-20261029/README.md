# 城市定向新线路：商务印书馆 + 邮政（待发布）

这个目录是两条上海城市定向线路的发布内容包。发布会话照着 `publish-guide.md`，在小程序里走普通作者的发布流程，把内容逐项填进去。

| 线路 | 站数 | 估算步行 | 玩法 |
|---|---|---|---|
| 《铅火暗线》商务印书馆 1897—1932 | 6 站，2 章 | 约 4.8 km（第 4→5 站约 3 km，可骑车或打车） | 排序、拍照打卡、连线、分类、猜数字、输入挑战 |
| 《暗流信筒》邮政与电报 1863—1949 | 7 站，2 章 | 约 2.8 km（按“约 3 公里”对外写） | 排序、猜数字、输入挑战、罗盘指向、选项问答、分类、一句留言 |

价格 0，票数 0。完成方式统一为「定位到达」，到达后开始本站玩法。

## 文件

- `publish-guide.md`：逐屏发布脚本，按顺序列出每个字段填什么、在哪里上传图片，以及可能出现的提示。
- `routes/<线路>/route.json`：机器可读的完整内容；`route.md`：人读版，含坐标、配置和每条【史实】的来源网址。
- `corrections.md`：草稿更正清单，共 91 条，其中史实 74 条、玩法与地点 17 条。
- `validation/`：
  - `configs.json`：13 份玩法配置，含结果反馈。
  - `validation-output.txt`：校验结果，13/13 通过。用的是小程序编辑器自己的“解析 → 校验 → 序列化”代码，以及后端 `AdvancedGameConfigValidator` 的原文。
  - `selfcheck-output.txt`：文字自检结果，62/62 通过。
- `self-check.md`：发布前和发布后的检查表。
- `images/`：
  - 封面：已生成，见 `contact-sheet-covers.png`。
  - 站点照片：清单在 `manifest.json`，署名在 `CREDITS.md`。照片还没有下载。
- `tools/`：生成和校验脚本。改内容只改 `routes_data.py`，然后依次运行 `python3 build.py && python3 make_guide.py && python3 selfcheck.py`，再运行 `node validator/validate.js ../validation/configs.json --java`。
- `source/`：原始素材，保持原样。

## 这次没做完的部分与原因

1. **站点高清照片没有下载**。本次运行的网络策略挡住了 `commons.wikimedia.org` 和 `upload.wikimedia.org`，所以只完成了这些：
   - 列出候选文件清单。
   - 写好下载脚本 `tools/fetch_images.py`。脚本下载时会核验许可，自动生成署名，去掉 EXIF 和 GPS，并导出 2048 px 的网页版。
   - 把封面改为不依赖照片的原创设计。
   站点照片的总览图因此也还没有生成。
2. **坐标是估值**。挡住的还有 OSM、Nominatim、Overpass 和 OSRM，所以：
   - 坐标来自仓库里已有的种子数据，或者按地址推估。
   - 步行距离按“直线 × 1.3”估算。
   - 编辑器只能用地图选点，填进去的坐标本来就来自腾讯地图（GCJ-02）。发布脚本里写了每站的选点搜索词和偏差容忍范围。
3. **史实核对只能借助搜索引擎**，没能直接打开原网页。`corrections.md` 里标 ★ 的条目，建议能访问时再抽查一遍。

上面要用到的域名放行后，补齐第 1、2 项不需要改任何内容文件：运行 `fetch_images.py` 和 `contact_sheet.py` 即可。

## 不在本包范围

本包没有改动 App 源码和迁移文件，没有连接任何生产库或主机，也没有运行 `ci/xcx-check.sh`、`ds-hardcode-gate-selftest.sh`。
后端校验器是用 `javac` 单独编译后运行的，只用来校验 JSON，没有跑 Maven，也没有起服务。
