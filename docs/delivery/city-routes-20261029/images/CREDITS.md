# 图片与字体署名

> **状态：站点照片还没有下载，署名也还没有最终核验。** 本次运行的网络策略挡住了 `commons.wikimedia.org` 和 `upload.wikimedia.org`，所以下表只是候选清单：
> 文件名来自搜索结果里的真实 Commons 页面，作者、许可和尺寸是搜索摘要里能看到的信息，标“待核”的项目还没有核实。
> 在能访问 Commons 的机器上运行 `python3 tools/fetch_images.py images/manifest.json images`，脚本会在下载时通过 Commons API 读取每个文件的作者和许可，
> 只保留允许商用的许可（CC0、公有领域、CC BY、CC BY-SA），并自动用核验后的内容**覆盖**本文件。

## 封面（已完成，可直接使用）

- `covers/*/cover-3x4.jpg`、`cover-16x9.jpg`、`share-1x1.jpg`：本仓库原创生成（`tools/cover.py`）。底纹是程序画的铅字阵列和邮戳线条，**没有使用任何第三方图片**，也没有其他品牌的标志或水印。
- 字体：Noto Serif SC（Black、Regular）、Noto Sans SC（Medium、Regular），**SIL Open Font License 1.1**（许可原文见 noto-cjk 仓库 Serif/LICENSE），来源 https://github.com/notofonts/noto-cjk 。
  按 OFL 的规定，字体可以用来制作和商用图片，生成的图片不受 OFL 约束；本仓库没有分发字体文件本身。

## 站点照片候选（待下载核验）

| 站 | 文件代号 | Commons 文件名 | 作者（摘要） | 许可（摘要） | 尺寸 | 页面 |
|---|---|---|---|---|---|---|
| A1 | `a1-movable-type` | Lead-made Chinese Movable Types, Hangzhou National Archives 112.jpg | unknown | unverified - re-verify on file page | ?×? | https://commons.wikimedia.org/wiki/File:Lead-made_Chinese_Movable_Types,_Hangzhou_National_Archives_112.jpg |
| A1 | `a1-metal-type` | Metal movable type.jpg | unknown | unverified - re-verify on file page | ?×? | https://commons.wikimedia.org/wiki/File:Metal_movable_type.jpg |
| A3 | `a3-foochow-road-1927` | Shanghai of Today, Plate 22 - Foochow (Fuzhou) Road.jpg | unknown; publ. A. S. Watson & Co., Shanghai of To-day (1927) | PD-US (pub. before 1931) + PD-China (50-yr org. work) per snippet | 3040×1860 | https://commons.wikimedia.org/wiki/File:Shanghai_of_Today,_Plate_22_-_Foochow_(Fuzhou)_Road.jpg |
| A3 | `a3-henan-road` | Middle Henan Rd. Shanghai.JPG | unknown | unverified - re-verify on file page | ?×? | https://commons.wikimedia.org/wiki/File:Middle_Henan_Rd._Shanghai.JPG |
| A3 | `a3-xia-ruifang` | 夏瑞芳.jpg | unknown | unverified - re-verify on file page (likely PD-old, d.1914) | 501×670 | https://commons.wikimedia.org/wiki/File:%E5%A4%8F%E7%91%9E%E8%8A%B3.jpg |
| A4 | `a4-shun-pao` | Former Shun Pao Building Shanghai.JPG | unknown | unverified - re-verify on file page | ?×? | https://commons.wikimedia.org/wiki/File:Former_Shun_Pao_Building_Shanghai.JPG |
| A5 | `a5-ruins-1932` | Commercial Press Ruins Shanghai, 1932, from Japanese postcard.jpg | unknown (Japanese postcard, 1932) | unverified - re-verify on file page (likely PD-Japan/PD-China historic) | 1024×667 | https://commons.wikimedia.org/wiki/File:Commercial_Press_Ruins_Shanghai,_1932,_from_Japanese_postcard.jpg |
| A6 | `a6-press-1922` | Commercial Press Shanghai 1922.jpg | unknown | unverified - re-verify on file page | 2848×2134 | https://commons.wikimedia.org/wiki/File:Commercial_Press_Shanghai_1922.jpg |
| A6 | `a6-wang-yunwu` | 王雲五2.jpg | unknown | unverified - re-verify on file page | 1288×1950 | https://commons.wikimedia.org/wiki/File:%E7%8E%8B%E9%9B%B2%E4%BA%942.jpg |
| B1 | `b1-customs-house` | Shanghai Customs House.JPG | Livelikerw | CC BY-SA 3.0 per snippet | 4912×3264 | https://commons.wikimedia.org/wiki/File:Shanghai_Customs_House.JPG |
| B1 | `b1-bund-customs-historic` | The Bund with Chinese Custom House and tower (NYPL Hades-2359396-4043752).jpg | NYPL collection | unverified - re-verify on file page (NYPL historic, likely PD) | 4682×2947 | https://commons.wikimedia.org/wiki/File:The_Bund_with_Chinese_Custom_House_and_tower_(NYPL_Hades-2359396-4043752).jpg |
| B1 | `b1-robert-hart` | Sir Robert Hart, seated with book LCCN2014683527.jpg | unknown (Library of Congress) | unverified - re-verify on file page (LoC, pre-1911, likely PD) | 5772×4228 | https://commons.wikimedia.org/wiki/File:Sir_Robert_Hart,_seated_with_book_LCCN2014683527.jpg |
| B2 | `b2-bund-7` | Bangkok Bank Building in Shanghai (Former Great Northern Telegraph Building) (2024) - img 01.jpg | unknown | unverified - re-verify on file page | 4032×3024 | https://commons.wikimedia.org/wiki/File:Bangkok_Bank_Building_in_Shanghai_(Former_Great_Northern_Telegraph_Building)_(2024)_-_img_01.jpg |
| B2 | `b2-telegraph-1905` | Telegraph Co in Shanghai (1905).jpg | unknown | unverified - re-verify on file page (1905, PD) | ?×? | https://commons.wikimedia.org/wiki/File:Telegraph_Co_in_Shanghai_(1905).jpg |
| B4 | `b4-gpo-from-creek` | Shanghai General Post Office Building from Suzhou Creek.jpg | unknown | unverified - re-verify on file page | 4000×3000 | https://commons.wikimedia.org/wiki/File:Shanghai_General_Post_Office_Building_from_Suzhou_Creek.jpg |
| B5 | `b5-gpo-2019` | Former Shanghai General Post Office Building, 2019-10-18 01.jpg | unknown | unverified - re-verify on file page | 5472×3648 | https://commons.wikimedia.org/wiki/File:Former_Shanghai_General_Post_Office_Building,_2019-10-18_01.jpg |
| B5 | `b5-gpo-2019-b` | Former Shanghai General Post Office Building, 2019-10-18 02.jpg | unknown | unverified - re-verify on file page | 5472×3648 | https://commons.wikimedia.org/wiki/File:Former_Shanghai_General_Post_Office_Building,_2019-10-18_02.jpg |
| B6 | `b6-red-revenue` | Stamp China 1897 overprint 1 dollar on 3 cents.jpg | unknown | unverified - re-verify on file page (1897 stamp, PD) | ?×? | https://commons.wikimedia.org/wiki/File:Stamp_China_1897_overprint_1_dollar_on_3_cents.jpg |
| B6 | `b6-red-revenue-b` | 紅印花小一元.jpg | unknown | unverified - re-verify on file page (1897 stamp, PD) | ?×? | https://commons.wikimedia.org/wiki/File:%E7%B4%85%E5%8D%B0%E8%8A%B1%E5%B0%8F%E4%B8%80%E5%85%83.jpg |
| B7 | `b7-suzhou-creek-1987` | Shanghai Suzhou Creek 1987.jpg | Till Niermann | unverified - re-verify on file page | 2500×1655 | https://commons.wikimedia.org/wiki/File:Shanghai_Suzhou_Creek_1987.jpg |

## 主题简介页脚（核验后由脚本生成，在此之前用下面这句）

图片：见各站署名，均来自维基共享资源，按其许可使用。

## 缺口

- 金城银行大楼（A2）、延安东路 34 号（B3）：没有找到可用的 Commons 照片，建议发布者在现场自己拍一张外立面。
- 大龙邮票：没有找到干净的高清扫描（只找到 797 px 的展柜照片）。
- 1932 年商务印书馆废墟：Commons 上的版本最大只有 1024 px。HPC Bristol 有更清晰的照片，但版权属于 Ephgrave 家族，不是开放许可，**不得使用**。
