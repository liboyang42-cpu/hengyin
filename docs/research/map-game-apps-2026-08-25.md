# 地图游戏 App 竞品研究（2026-08-25）

## 结论

当前仍可见官方运营或在架的地图游戏，主要不是一种形态，而是四类：

1. **地图即世界**：现实道路被重绘为游戏世界，代表为 Pokemon GO、Pikmin Bloom、Monster Hunter Now、Jurassic World Alive、Orna。
2. **地图即战场**：地点、连线和区域本身就是胜负，代表为 Ingress。
3. **地图即任务板**：真实地图保留工具属性，地图点位承载寻宝或签到，代表为 Geocaching、Munzee。
4. **地图即故事导航**：玩家按路线到达真实地标，在节点解谜或推进剧情，代表为 Adventure Lab、Questo。

对城瘾最有价值的方向不是复制某一款，而是组合 Pikmin Bloom 的行走痕迹、Ingress 的区域状态、Adventure Lab/Questo 的城市故事节点。

> “地图视觉样式”是基于官方页面、帮助中心和官方应用商店截图做的设计归纳，不是厂商对其视觉风格的官方命名。

## 竞品清单

| App | 地图视觉样式（归纳） | 地图上的核心操作 | 核心循环 | 对城瘾的参考价值 |
|---|---|---|---|---|
| Pokemon GO | 明亮、低细节、轻 3D 卡通城市；角色居中；兴趣点高塔化；有交互半径与天气反馈 | 找 Pokemon、转 PokéStop、打 Gym/Raid、发现和创建 Route | 出门移动 → 发现 → 收集/战斗 → 图鉴与养成 | 最成熟的“现实城市游戏化”范式；可学可读半径、远近层级和地标放大，不宜照搬密集图标与 IP 收集逻辑 |
| Ingress | 深色霓虹扫描仪；黑底路网；蓝绿 Portal、连线和三角控制区非常醒目 | 占领 Portal、连接 Portal、围合区域、阵营协作 | 到点占领 → 连线 → 控区 → 阵营攻防 | 适合参考“区域被玩家改变”的强反馈；不适合城瘾整体使用其高压军事 HUD |
| Pikmin Bloom | 柔和浅绿、花园感、留白较多；花朵、蘑菇和行走花路构成地图记忆 | 走路、种花、访问大花/蘑菇、查看远处地点 | 日常步行 → 留下花路 → 收集 Pikmin/明信片 → 回顾一天 | 最适合参考“走过以后地图变了”和低压力漫游氛围 |
| Monster Hunter Now | 现实路网之上覆盖森林、沙漠、沼泽等大色块；大型怪物直接成为地图主角 | 找怪、采集、进入限时战斗、标记稍后狩猎 | 移动找目标 → 75 秒战斗 → 材料 → 装备升级 | 可学“区域主题皮肤 + 大目标”，但高密度怪物和强战斗节奏不适合城瘾日常探索主界面 |
| Jurassic World Alive | 偏自然探索/监测仪器感；恐龙、补给点和玩家探测半径覆盖真实地图 | 找恐龙、放无人机收集 DNA、补给、活动与战斗 | 地图发现 → 无人机采样 → 融合养成 → PvP | 可参考“地图发现后进入独立互动场景”；GPS-less 模式也说明位置游戏需要无障碍替代入口 |
| Orna | OpenStreetMap 路网底图叠加 2D 像素怪物、NPC、建筑和地牢，像复古 RPG 世界地图 | 遭遇怪物、进地牢、做任务、占地、建设 Origin Town | 现实移动 → RPG 遭遇 → 战斗与掉落 → 职业/城镇成长 | 很适合研究低成本美术覆盖真实地图；问题是图标密度高、道路层级容易不清晰 |
| Geocaching | 接近户外导航工具；真实地图、多类型针点、筛选和详情优先 | 找附近藏宝点、导航、查看线索、线下寻找并记录 | 选点 → 到达附近 → 实物寻找 → Log/收藏 | 可学可信位置、筛选和“真实世界验证”，但视觉游戏感较弱 |
| Munzee | 工具地图上叠加大量不同类型的实体/虚拟点位，偏高信息密度 | 地图找点、GPS 导航、扫描 QR、部署自己的点 | 搜索 → 到点 → 扫码 Capture → 积分/徽章/排行 | 可学“玩家共建点位 + 到店扫码验真”；不应照搬复杂图标体系和地图拥挤度 |
| Adventure Lab | 清爽真实地图 + Adventure 起点/任务节点；地图负责到达，故事卡负责体验 | 找附近 Adventure、逐点到达、答题、解锁故事 | 选故事 → 按顺序到点 → 解谜 → 完成路线 | 与城瘾城市探索非常接近，适合参考“地图和剧情分层” |
| Questo | 城市选择、路线与地标导航为主，体验更像户外密室/自助步行故事 | 选择城市故事、走到地标、解线索、推进剧情 | 购买/选择 Quest → 步行 → 地标谜题 → 故事结局 | 最接近城瘾的商业化城市故事产品，可重点研究内容上架、路线时长、离线与节点叙事 |

## 官方依据

- [Pokemon GO Map View 帮助中心](https://niantic.helpshift.com/hc/en/6-pokemon-go/faq/84-what-is-the-map-view/)：地图反映玩家现实位置，并包含天气、指南针、交互半径等元素。
- [Pokemon GO Routes 官方页](https://pokemongo.com/en/routes?hl=en)：玩家可发现、跟随和创建现实路线。
- [Ingress 官方网站](https://ingress.com/en)：以现实地点、Portal、阵营协作和全球行动为核心；页面仍列出 2026 活动。
- [Pikmin Bloom App Store](https://apps.apple.com/us/app/pikmin-bloom-walking-game/id1556357398)：步行、位置与后台步数是核心能力。
- [Pikmin Bloom Bird's-Eye View 帮助](https://niantic.helpshift.com/hc/en/23-pikmin-bloom/faq/5119-bird-s-eye-view/)：可从步行视图切换到地图鸟瞰，查看远处蘑菇、大花和特殊地点。
- [Monster Hunter Now App Store](https://apps.apple.com/us/app/monster-hunter-now/id6445906110)：官方说明现实狩猎、森林/沙漠/沼泽生态区、75 秒战斗、AR 和后台标记；2026 年仍有版本与活动更新。
- [Jurassic World Alive App Store](https://apps.apple.com/us/app/jurassic-world-alive/id1231085864)：官方说明用位置技术在地图发现恐龙。
- [Jurassic World Alive GPS-less Play](https://jurassicworldalive.com/news/gps-less-play/)：可关闭真实地图，进入虚拟区域继续地图玩法。
- [Orna 官方网站](https://ornarpg.com/gps/?lang=en)：GPS 驱动的多人 RPG，可战斗、探索地牢并占领现实地标。
- [Orna App Store](https://apps.apple.com/us/app/orna-fantasy-rpg-adventure/id1451775309)：确认 OpenStreetMap、像素美术、GPS 探索、城镇建设和持续内容更新。
- [Geocaching App Store](https://apps.apple.com/us/app/geocaching/id329541503)：官方寻宝地图、导航和线下藏宝体验。
- [Munzee 官方玩法页](https://www.munzee.com/game/?mobile-app=true&theme=wiki)：地图搜索、GPS 定位、扫码 Capture 和玩家部署点位。
- [Munzee App Store](https://apps.apple.com/us/app/munzee/id1367282248)：实体 QR 点和虚拟 GPS 点并存，2026 年仍在架。
- [Adventure Lab App Store](https://apps.apple.com/us/app/adventure-lab/id1412140803)：社区创作的户外寻宝、故事、挑战和地图导航。
- [Questo 官方网站](https://questoapp.com/)：将城市步行变为故事游戏，以真实地标、谜题、1.5–3 小时路线和离线体验为核心。

## 城瘾地图方向建议

### 建议组合

- **默认地图**：Pikmin Bloom 式低压力城市底图，减少道路与普通 POI 噪声。
- **玩家记忆层**：走过区域留下个人雾区消散、足迹、花路或色块，强调“这是我的城市记忆”。
- **任务层**：Adventure Lab/Questo 式路线和故事节点，只在选择主题后突出，避免默认地图塞满任务。
- **城市状态层**：借 Ingress 的区域反馈，但改为街区探索度、故事完成度、主题氛围，不做敌我占领。
- **强互动节点**：借 Jurassic World Alive/Monster Hunter Now，从地图进入独立的 NPC、拍照、解谜或收集场景。

### 不建议

- 不把所有商户、任务、NPC、活动同时铺在地图上。
- 不以普通导航地图换一套颜色就当成游戏地图。
- 不让地图只有“点图标 → 看详情”，玩家移动后必须留下持久变化。
- 不完全依赖 GPS 才能继续，应提供路线预览、历史回看、无障碍或弱定位模式。
- Wokamon 属于计步器 + 虚拟宠物，并不是以地图为空间规则的产品，不应列为核心地图竞品。
