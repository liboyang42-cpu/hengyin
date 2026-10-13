# 模板/主题封面素材台账

56 张封面(27 个公共玩法 + 29 个完整主题)由 `ci/render_template_covers.py` 生成:
**现成的 CC0 像素城市场景做底图,现成的游戏图标做内容标记**,本仓不再自绘任何图形。

换轨历史:实拍照(2026-08-22)→ 自绘平涂场景(2026-08-25 上午)→ 现成像素素材(2026-08-25 下午)。
前两版被推翻的原因一致 —— **不像游戏**;自绘那版还有第二个毛病:56 张全是「天际线 + 火柴人」
一个配方,看不出哪张是什么内容。

---

## 一、底图:ansimuz 像素城市场景(全部 CC0,免署名)

作者 Luis Zuno (ansimuz),三个包的授权声明均随包分发(`public-license.pdf` / `.txt`),
原文:「All assets included in this package are licensed under the Creative Commons Zero (CC0)
license, which means you can use them freely in any project, whether personal or commercial,
without the need for attribution.」

| 包 | 下载页 | 下载文件 | 字节 | SHA-256 |
|---|---|---|---|---|
| Synth Cities Environment | https://ansimuz.itch.io/cyberpunk-street-environment | `cyberpunk-street-files.zip` | 16,736,056 | `9d6a1325d9603ba7cff285ba9301d5bda8e75ff47e1401e37d8496cd1f3973b8` |
| Warped City | https://ansimuz.itch.io/warped-city | `Warped City Assets Files` | 2,743,743 | `a8800fa847f16e1a15834ead8119f36438e9a6772d862e680dc2336b6e96bd4a` |
| Warped Miami Synth | https://ansimuz.itch.io/warped-miami-synth | `Miami-synth-files.zip` | 2,403,452 | `74a091e037f447d59b7602cb49fb65597bfee0fb9843b95c5a2c76d2a56bdf67` |

仓库只留**实际用到的 17 个视差图层**(`sources-pixel/`,共 192KB),整包不入库。

同批评估后**弃用**的两个包(也是 CC0,但题材不符):
`warped-ocean-view`(水下穹顶)、`warped-zone-202`(抽象科幻走廊)。

### 五个场景配方

| 配方 | 图层 | 画面 |
|---|---|---|
| `street` | synth v2 back / middle / foreground | 霓虹街道,车流与店招 |
| `shops`  | synth v1 back-buildings / far-buildings / foreground | 商店街,RESTAURANT/COFFEE/BAR 招牌 |
| `dusk`   | synth city-skyline back / buildings / front | 紫橙调黄昏天际线 |
| `miami`  | miami sun / back / buildings / palms / highway | synthwave 落日公路,棕榈与跑车 |
| `towers` | warped skyline-a / buildings-bg / near-buildings-bg | 赛博高楼与霓虹管线 |

视差图层可横向、纵向独立位移,穷举得 69 个互不重样的取景(street 15 / shops 12 /
dusk 12 / miami 18 / towers 12),56 张各占一个,**无一复用**。这条由
`ci/test_template_cover_contract.py` 的 sha256 互异断言 + 渲染脚本内的取景唯一性断言双闸把住。

## 二、内容标记:game-icons.net 游戏图标(CC BY 3.0,**要署名**)

56 枚图标一图一条内容(暗号=钥匙、拍照=相机、盲盒=宝箱、角色任务=面具……),
在 42px 小尺寸光栅化,出来是真像素,与底图同一套画法。

- 来源:https://game-icons.net/ · 仓库 https://github.com/game-icons/icons
- 授权:Creative Commons BY 3.0(`sources-icons/UPSTREAM-license.txt` 是上游原文)
- 本批用到的作者:Delapouite(30)、Lorc(18)、Skoll(4)、Sbed(2)、Quoting(1)、Lord Berandas(1)
  —— 这份数字由 `manifest.json` 的 icon 字段实算,契约按同一份真源逐个核对署名页,
  换图标后两处都要跟着改(2026-08-25 换 t08/t09 两枚时就漏改过一次)

⚠️ **CC BY 要求署名**,小程序内的署名区块在
`chengyinhub-xcx/pages/shezhi/shezhi`(设置页底部「素材署名」),由 `docs/assets/template-covers/SOURCES.md`
与该区块共同构成履约证据。图标换人换包时**两处都要改**。

## 三、生产投放

渲染产物在 `final/`,文件名与生产 URL 一一对应:
`https://www.chengyinhub.com/prod-api/profile/template-covers/<file>`。
⚠️ 该迁移还会**改写生产文案**:上海 5 主题的 description 末尾原有「封面摄影:…」署名,
封面换成 CC0/自绘素材后这句是假署名,迁移里用 `SUBSTRING_INDEX` 截掉。文件尾部的回滚段只覆盖
`img_url`,description 没有自动还原路径 —— **原文逐字保存在
`chengyinhub-admin/sql/migration_seed_shanghai_solo_topics_20260822.sql` 的 INSERT 里**,
要还原就从那份种子取回对应的 5 条 description。

DB 侧映射由 `chengyinhub-admin/sql/migration_20260822_template_cover_images.sql` 负责
(文件名未变,所以换图不需要改迁移;但**换图后必须重新上传这 56 个文件并 sha256 回读**,
否则线上还是旧图)。
