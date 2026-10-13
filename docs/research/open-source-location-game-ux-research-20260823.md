# 城瘾位置游戏开源源码与 UI/UX 研究

- 日期：2026-08-23
- 范围：微信原生小程序的城市定向、主题自由探索、开放世界漫游
- 方法：只采用官方 GitHub 仓库/README/LICENSE、官方产品页和官方帮助/设计页面；GitHub stars 与默认分支最新 commit 于 2026-08-23 回读，stars 会波动。

## 结论

没有找到一套同时满足“成熟、许可清楚、持续维护、可直接运行于微信原生小程序、覆盖地图＋谜题＋分支＋多人”的完整开源游戏。最稳的方案是组合：

> StreetComplete 地图微任务 + Adventure Lab 到点/进度 UX + inkjs 分支剧情 + GooseChase 团队任务流 + Pikmin Bloom 日常探索。

而且模组不能只面向玩家：

- **商家**是关主和现场承接者：提供稳定谜面、开放时间、兜底与权益。
- **俱乐部**是组局者和导演：选点、排线、分角色、广播、控场、复盘。
- **玩家**是探索者和协作者：观察、选择、交流、收集、影响结局。

一份模组配置应呈现三种权限视图，不为每个玩法另建一套编辑器。

## Top 5

| 排名 | 参考 | 当前证据 | 最值得借鉴 | 微信小程序边界 |
|---|---|---|---|---|
| 1 | [StreetComplete](https://github.com/streetcomplete/StreetComplete) | 4,763★；2026-08-21 commit；GPL-3.0；Kotlin/Android | 附近任务 pin → 一次一个现场问题 → 完成后地图即时变更 | 代码移植高风险，GPL 源码不直接搬；独立重写任务状态机 |
| 2 | [Adventure Lab](https://www.geocaching.com/sites/adventure-lab/en/) | 官方产品持续运营；闭源 | 固定可见 Start、顺序/自由路线、编号 pin、进度条、接近提示、Answer 到点解锁、完赛明信片 | 只借鉴 UX；定位要有容差与现场答案/扫码兜底 |
| 3 | [ink](https://github.com/inkle/ink) + [inkjs](https://github.com/y-lohse/inkjs) | 4,909★ + 647★；MIT；2026-05-05 / 2026-08-01 commit | 条件、变量、分支汇流、存档；内容和 UI 解耦 | 最值得做代码 spike；隐藏答案和奖励仍由服务端裁决 |
| 4 | [GooseChase](https://goosechase.com/features) | 2026 官方功能页/live demo 持续更新；闭源 | 照片/视频、文字、GPS 任务；队伍动态流、点赞、奖励分、广播、榜单隐藏/揭晓、角色任务 | 只借鉴交互；UGC 要审核、去 EXIF/精确位置，文本/GPS 默认不进公开流 |
| 5 | [Pikmin Bloom](https://pikminbloom.com/) | 官方 2026-07 仍有月度活动；闭源 | 走路产生可见痕迹、异步周目标、Expedition/明信片、月度事件 | 不抄随机箱和付费加速；不要求持续后台定位 |

### 为什么是这五个

- StreetComplete 最能解决“地图上现在能玩什么”。官方 README 明确是自动寻找附近任务、用 marker 展示、现场回答一个简单问题并上传结果，还支持离线。[官方 README](https://github.com/streetcomplete/StreetComplete#readme)
- Adventure Lab 最能解决“到点后怎么自然推进”。官方 UX 复盘把 Start 做成滚动时仍可见、加顶部进度和编号点、让接近提示持续可见，并把两屏庆祝压成一屏。[官方改版对照](https://www.geocaching.com/blog/2021/12/adventure-lab-app-before-and-after-edition/)
- inkjs 最能解决“前面线索与后面结局没关系”。它是零依赖 JavaScript 运行时，可加载编译后的 story JSON，暴露当前内容与 choices。[inkjs README](https://github.com/y-lohse/inkjs#readme)
- GooseChase 最能把“组队管理”变成“团队在一起玩”。官方支持任务级动态流可见性、实时排行榜、奖励分、定时解锁/广播；官方模板还明确示例 Strategist、Explorer、Documenter 三种角色。[官方模板](https://blog.goosechase.com/goosechase-experience-blueprints-interactive-templates/)
- Pikmin Bloom 最适合漫游复玩：步行、种花轨迹、异步周挑战、明信片和周期事件让玩家不用一直盯屏。[官方玩法页](https://pikminbloom.com/)

## 三个三方闭环模组

### 1. 商圈谜案季（主题自由探索，P0）

组合：Adventure Lab 到点 UX + inkjs 剧情变量 + GooseChase 角色协作。

1. **商家关主**：每店选一个长期稳定的现场物作为谜面，配置可观察答案、营业时段、异常兜底和现有权益；只编辑自己的节点。
2. **俱乐部导演**：选 4–6 家店，串成案件；给侦察员、解码员、记录员不同信息，配置顺序/自由路线、三级提示和终局。
3. **玩家探索**：到点后找、拍、答得到关键词/图案/数字；线索写入剧情变量，最终选择和结局随本局结果变化。
4. **闭环结果**：商家得到可核验到访和授权内容；俱乐部得到完成率/卡点做下一期；玩家获得记忆卡、隐藏结局和既有碎片/徽章，不新增货币。

门禁：现场物变化时必须有替代答案；剧情完成与权益核销分账；客户端不能自行发奖励。

### 2. 周末城市异象（开放世界漫游，P0）

组合：StreetComplete 任务 pin + Pikmin Bloom 周期目标 + GooseChase 动态流/广播。

1. **商家关主**：申报周末可用时间和一个短任务，例如找店内符号、拍指定构图、回答现场问题；闭店可暂停。
2. **俱乐部导演**：从已确认节点编成当周 3–5 个异象，设置难度、时段、任务可见性、中途线索广播和排行榜是否隐藏。
3. **玩家探索**：地图只显示附近活跃异象；进入范围后完成，照片进入队伍流，异步累计个人与俱乐部共同进度。
4. **闭环结果**：商家获得周末到访/UGC，俱乐部获得组局活跃，玩家地图留下发现格和城市明信片，下周异象轮换形成复玩。

门禁：按“日期 + 城市格子”稳定生成，刷新不换点；照片去 EXIF/精确位置；排名不能只按速度。

### 3. 非对称角色城市定向（俱乐部组局，P0）

组合：GooseChase Team Roles + Adventure Lab 编号节点 + ink 条件分支。

1. **商家关主**：作为情报站、补给站或终局见证人，只看到要准备的道具/口令/核验动作，不接触整局隐藏答案。
2. **俱乐部导演**：创建临时队伍，分配侦察员、解码员、记录员、队长；分别发布不同信息，控制解锁与广播。
3. **玩家探索**：侦察员看区域，解码员看符号表，记录员看拍摄模板，队长做一次风险路线选择；必须交流才能拼全答案。
4. **闭环结果**：商家完成现场承接；俱乐部沉淀可复制局模板与复盘；玩家得到团队总结卡、角色表现和不同结局，而非只有个人名次。

门禁：成员掉线时允许队长付出扣分代价接管，不能卡死整队；精确队友位置只在局内、短时、主动同意后可见。

## 其他高质量开源/产品参考

| 候选 | Stars / 维护 / License / 栈 | 核心价值 | 复用难度与风险 |
|---|---|---|---|
| [c:geo](https://github.com/cgeo/cgeo) | 1,535★；2026-08-21；Apache-2.0；Java/Android | 地图/列表同源、紧凑 marker、方向线、多点路线、历史状态、离线日志。[官方 Live Map](https://manual.cgeo.org/en/mainmenu/livemap) | 高；Android UI 重写。只借鉴自己的任务数据 UX，不碰第三方平台非官方数据 |
| [PraxisMapper](https://github.com/PraxisMapper/PraxisMapper) | 76★；2026-03-06；Apache-2.0；C#/.NET 8 | Place/Area/Global 三层状态、地图叠层、按地理格子保存探索、尽量少存位置 | 高；城瘾已有 Spring 后端，不建议引入平行 .NET 地图服务，只借鉴模型 |
| [Nakama](https://github.com/heroiclabs/nakama) | 13,199★；2026-08-05；Apache-2.0；Go/Postgres-compatible | party/group、异步/实时多人、通知、周期榜和 tournament | 高；现有登录/队伍/排行已存在，借鉴 API 与生命周期，不整体换后端 |
| [MapLibre GL JS](https://github.com/maplibre/maplibre-gl-js) | 11,408★；2026-08-21；BSD-3-Clause；TypeScript/WebGL | GeoJSON/feature state、地图图层和动画，可原型化迷雾、发现、事件状态 | 高；DOM/WebGL 不能直接替代微信 `<map>`，只做 Web 原型后用原生 overlay 重写 |
| [vincentchalamon/scavenger-hunt](https://github.com/vincentchalamon/scavenger-hunt) | 1★；2026-05-28；CC BY-NC-SA 4.0；Next 16/React 19/Leaflet/Three.js | keyword、hotspot、scratch、magnifier、card/page flip、3D box；线索拼最终短语；有配置校验和 Playwright | 高且**禁止商业直接复用**；只研究交互/schema，原创重写 |
| [Questo](https://questoapp.com/) | 闭源；官方 ©2026 页面显示 1M+ 玩家、1,072 城、3K+ quests | 真实地点 + 虚构故事、逐点解谜、提示代价、完赛回忆、创作者审核 | 只借鉴；完整内部一手研究见 [既有报告](./questo-app-research-20260820.md) |
| [Ingress](https://ingress.com/en) | 闭源；官方 2026-02 Scanner 文档、2026-08 活动 | 40m Action Range、少量颜色表达节点状态、图层、节点合成区域目标 | 只提取行动半径/区域共同状态；不抄高密 HUD、阵营战争与危险移动激励 |

## 技术与许可边界

- **GPL**：StreetComplete 代码直接进入非 GPL 客户端会带来强 copyleft 风险；只研究机制并独立实现。
- **NonCommercial**：`vincentchalamon/scavenger-hunt` 是 CC BY-NC-SA 4.0，不用于城瘾商业代码、素材或派生界面。
- **闭源产品**：Adventure Lab、GooseChase、Pikmin Bloom、Questo、Ingress 只借鉴抽象机制与信息层级，不复制代码、素材、故事、文案、图标或品牌皮肤。
- **微信运行时**：“浏览器可用”不等于“小程序可用”；DOM、WebGL、Worker、npm 转译、包体、冷启动与真机触摸都要 spike。
- **地图许可**：引擎开源不代表瓦片、字体、sprite、POI 数据和在线服务可商用；每层单独核许可/服务条款。
- **GPS 与防作弊**：geofence 不是绝对到场证明；要有容差、异常点过滤和现场答案/扫码兜底。答案、关键进度、分数、权益与奖励必须服务端权威且幂等。
- **隐私与安全**：不默认持续后台定位；动态流去 EXIF/精确位置；任务点需有开放时段、可达性、退出和安全提示。

## 建议下一步

只做一个 3–5 点真实样板，不换后端、不换地图、不新建编辑器：

1. 在现有模板里增加 `线索产出 / 剧情变量 / 角色可见性 / 三级提示 / 任务流可见性` 配置。
2. 做 inkjs spike：3 节点、2 变量、2 结局；验证包体、冷启动、存档、升级和篡改负控。
3. 用 Adventure Lab 的 UX 跑通发现 → 接近提示 → 到点解锁 → 协作解谜 → 单屏结算。
4. 用三方验收：商家能独立准备并暂停节点；俱乐部能开局/广播/复盘；5 名首次玩家无需口头指导能完成。

## 主要一手来源

- [StreetComplete repo / GPL-3.0](https://github.com/streetcomplete/StreetComplete)
- [c:geo repo / Apache-2.0](https://github.com/cgeo/cgeo)
- [PraxisMapper repo / Apache-2.0](https://github.com/PraxisMapper/PraxisMapper)
- [ink repo / MIT](https://github.com/inkle/ink)、[inkjs repo / MIT](https://github.com/y-lohse/inkjs)
- [Nakama repo / Apache-2.0](https://github.com/heroiclabs/nakama)
- [MapLibre GL JS repo / BSD-3-Clause](https://github.com/maplibre/maplibre-gl-js)
- [Scavenger Hunt repo / CC BY-NC-SA 4.0](https://github.com/vincentchalamon/scavenger-hunt)
- [Adventure Lab 官方使用指南](https://www.geocaching.com/blog/2024/10/top-5-adventure-lab-app-tips/)
- [GooseChase 官方功能页](https://goosechase.com/features)
- [Pikmin Bloom 官方玩法页](https://pikminbloom.com/)
- [Questo 官方产品页](https://questoapp.com/)
- [Ingress 官方 Scanner 指南](https://support.ingress.com/hc/en-us/articles/41140372539035-Understanding-the-Scanner)

## 置信度

- 高：仓库 stars、commit、license、官方明示技术栈与功能；均从一手来源当前回读。
- 高：闭源产品公开的玩法、信息层级和 Adventure Lab 官方 UX 改版原则。
- 中：这些组合迁移到城瘾后的效果；必须由真实路线、真机和首次玩家测试证明。
- 未验证：第三方 JavaScript 在城瘾当前构建链/微信基础库的包体与性能；线上微信版本是否已具备对应配置消费能力。
