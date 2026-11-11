# x-fix2 玩家侧验收问题收口

分支 `launch/questo-20261110`。来源：`ACCEPT-ISSUES-20261111.md` 的玩家侧行。前一轮（用量上限中断前）已推 c878fbb7 / 590289d7 / 24be9978 / 4f4f66d9 / 131acc46 / b6a99a28 / c49e4976 / 8d3896e8 / cb0eb6bb；本轮续做 L-2、L-6、P-14、P-17 并收口门禁。
统一单测：`tests/unit/x-fix2-player-20261111.test.js`（另注明的除外）。

## 一、已修

| 编号 | 原因 | 修法 | 主要文件 | 测试 |
|---|---|---|---|---|
| P-1 | `buildHeroCta` 不看截止，已结束主题仍给「立即报名」 | 已截止只显示「已截止」，不给报名/解锁入口 | pages/topic/index/index.js、index.wxml、utils/topic-motion.js | x-fix2 P-1 |
| P-2 | 自玩票标题硬写「自玩通行证」，与「通行证＝我的会员平台通行证」冲突 | 改「自玩门票」；无票种不再落到自玩结账 | pages/topic/utils/checkout-view.js、components/cy/pass-reveal、topic/index | x-fix2 P-2；r2-store-checkout-view、l-pass-platform-pass、v13-proto-helpers 同步 |
| P-3 | 权益行用节点 `total` 而非商家数 | 改按 `merchantCount` 计数 | pages/play/index.wxml、index.js | x-fix2 P-3 |
| P-4 | 「各站奖励」行无图标无箭头 | 补图标与箭头 | pages/play/index.wxml、index.wxss | x-fix2 P-4 |
| P-5 | 站点孤立圆点；「到店三步」被固定 CTA 盖住 | 去孤立圆点，步骤区避让 | pages/play/index.wxml | x-fix2 P-5 |
| P-6 | 卡包首屏与故事开场无返回 | 两处补返回键（预览态保持原返回） | pages/play/index.wxml | x-fix2 P-6 |
| P-7 | 章节名压在「未到店」角标下 | 章节名避让角标 | pages/play/index.wxss、utils/play-stage-pops.js | x-fix2 P-7 |
| P-8 | 「开放」大数格塞整句 | 该格只放时段 | pages/play/index.js、subpackageP3 | x-fix2 P-8 |
| P-9 | 旅程手记左格塞长节点名且有说明句 | 去说明句，左格不放节点名 | pages/play/index.wxml、index.wxss | x-fix2 P-9 |
| P-10 | 结算里程碑文案不通顺 | 改写 | pages/play/utils/play-milestone.js | x-fix2 P-10 |
| P-11 | 首页定位确认是长疑问句 | 改陈述句（首页与隐私页同步） | pages/index/index.js、pages/privacy/index.js | x-fix2 P-11 |
| P-12 | 开放时间 `break-all` 把「10.13」劈开 | 只在连字符后折行（零宽空格）；日期联测断言去掉零宽位 | pages/topic/index/index.wxss、utils/topic-detail-facts.js | x-fix2 P-12；topic-detail-facts；sc-ui-date-count-cross-page |
| P-13 | 通行证未开放仍摆筛选条 | `state` 为 off/merchant 时不渲染 | subpackageMember/market/library/index.wxml | x-fix2 P-13 |
| P-14 / L-1 | 收集册（勋章/邮票/藏品）是浅色，玩家侧须深色 | 三页与相册光效/开场组件改回玩家深色；藏品页 ✦ 钮改浅色线条 | subpackageP3/pages/{badge-wall,stamp-album,object-cards}、components/album-aura、album-intro | x-fix2 L-1；前后截图 shots/L-1-* |
| M-2 | 勋章墙接口失败整页空白、单枚图失败拖垮整页 | 失败保留页头；单枚图失败只降级该枚（根因是测试后端缺 OSS 配置，页面侧已兜底） | badge-wall/index.js、index.wxml | x-fix2 M-2；shots/M-2-* |
| P-15 | 投诉建议与 AI 对话记录同一图标 | 新增 megaphone 图标 | pages/shezhi/shezhi.wxml、images/icon_lc_megaphone.svg | x-fix2 P-15 |
| P-16 | 选中胶囊在地图上无落影 | 加落影 | subpackageRoam/nearby/index.wxss | x-fix2 P-16 |
| P-18 | 无封面票卡下半空白 | 放插画 | subpackageMember/signup/index.{wxml,wxss,json} | x-fix2 P-18 |
| ME-4（底栏，玩家端同） | 自定义底栏玻璃不透明度不足，卡片文字透出 | 提高不透明度 | components/tabBar/index.wxss | x-fix2 ME-4 |
| L-3 | 离屏画布取样拿不到点（DevTools/旧基础库）时章节号只剩一圈糊橙点 | 取样失败退回内置点阵数字与「章」字点阵 | pages/play/components/chapter-flip/index.js | x-fix2 L-3 |
| **L-2（本轮）** | 先查是页面缺陷还是测试数据：开机黑场（`st-boot`，约 2 秒后自清，定时器链 line→open→fade→空）属设计过渡帧；真问题是加载遮罩（z-index 200、纯黑底、骨架 #191919 在黑底近乎不可见）压住外层返回键，整屏像黑屏且无处可退 | 加载遮罩内单放一颗返回键（z 201，预览态不出），不加说明文字 | pages/play/index.wxml、index.wxss | x-fix2 L-2 |

## 二、核查后不改

- **L-6 首页首城位置卡压 feed**：静态核查首页只有三处与首城定位相关——顶栏「· 城市」行（绝对定位在 hero 内，不进 feed 流）、`cy-privacy-gate` 半屏 sheet、`modal.show` 的「首页城市定位」弹窗（`cy-modal` 固定定位、`--cy-z-modal` 档、带遮罩）。三者都是覆盖层而非流内卡片，没有流内位置卡可与 feed 重叠；无设备截图复现，未臆改布局。需要复现截图（含机型）才能继续。
- **P-17 漫游 tab 中段黑带**：`mapReady` 仅在定位成功、进入开场切场时置 true，落地页本就不挂地图；`mapLoad` 看门狗 5 秒内无 updated 才出重试提示。属无定位/DevTools 下的落地页留白，测试环境现象，未改。
- 结算页 P-10 用 MOCK 数据，真实结算需完整跑完城市线，走查未覆盖。

## 三、门禁（本轮收尾）

- 动作台账：前几次 x-fix2 提交增删返回键/行造成 6022→6027（tap 3027→3030、事件合同 2995→2997），按流程 `--write` 重建并同步 `action-ledger.test.js` 中的分母与 `onPickChapter` 行号（540→541、序号 77→78，均为位移/新增）；`--check` PASS 6027。
- 全量单测（仓库根 `node --test "chengyinhub-xcx/tests/unit/**/*.test.js"`）：17323 项，0 失败。（中途 4 失败：台账三项＋日期联测，已按上修正。）
- ui-integrity：`UI-GATE-0:PASS`，无新增基线条目。
- route-matrix：PASS，171 路由 / 27 场景，无 diff。
- wxss 选择器门禁通过；keyframes 相关断言随全量单测通过。
- 主包：源码字节约 3.2 MB（未压缩，与走查口径一致）；压缩后体积需 CLI preview/upload，`wechat-release-contract` 报 WAIT-EXTERNAL，本环境未取得，不宣称 <2048 KiB 已验证。
- 未触碰商家/俱乐部/作者页面与 `utils/wxs/*`、`ci/architecture/*`、`ci.yml`；未跑 `ci/xcx-check.sh`。

STATUS: PARTIAL — L-2 已修并推送，台账与全量单测通过；L-6、P-17 经核查无可复现的页面缺陷未改，压缩后主包体积待 CLI 实测。
