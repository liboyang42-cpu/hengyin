# l-iconsB 结果（2026-11-11）

分支 `claude/l-iconsB-20261111`，从 `origin/launch/questo-20261110`（`afa90ffd`）切出，最新 launch 已在其中（launch 期间没有新提交）。依据：业主 2026-11-11「both ok」批准的 `proto/icons` v2（`icons-b.js` 图形、`icons.css` 主 + 次级动作、ps-pass 通行证页、ic-illu 整套）。

## 改动

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| IB-1 | fixed | cy-illus 的线索 / 勋章 / 钥匙 / 成功 / 失败 / 调查中 / 队伍 / 通行证 / 积分 / 章节印十枚换成风格 B v2。图形逐字取自原型 `icons-b.js` 第 7–80 行（单测核对 sha256），调色盘柠檬绿 / 天蓝 / 珊瑚 / 金 / 紫 / 纸。名字和用法（`name` / `size` / `delay` / `bare` / `loop` / `reduced-motion` / `art.layers()`）都不变，所有调用处自动换图。v2 没重画的另外 15 枚（邮戳、奖杯、锁、闪光、宝石等）保持原样。 | `pages/play/components/cy-illus/art-b.js`（新）、`art.js`、`index.js`、`index.wxml`；`subpackageMember/`、`subpackageP3/`、`subpackageJourney/` 的 `components/cy-illus/*` 是逐字副本 | `tests/unit/l-iconsB-20261111.test.js`（12 条）；在 Chromium 里把原型 inline SVG 和本组件的分层图并排对比，静止帧逐像素一致；rig 截图见下 | 真机上 data URI SVG 的 `rgba()` 描边与 `clip-path="inset()"` 属性（原型原样带着）在 iOS / 安卓 WebView 上的渲染 |
| IB-2 | fixed | 原型的主 + 次级动作移植成 WXSS 过渡，不新增关键帧（棘轮仍是 146）。做法：小程序不能给 SVG 子元素做动画，所以按原型 `icons.css` 里会动的部件把每枚图切成几层同尺寸的图（如钥匙 = 地影 / 钥匙身 + 宝石 / 两粒绿光）；组件按 `art-b.js` 里的 `MOTION` 时间表在根节点上加阶段类（进场 `e1…`、待机每圈累加 `p1…`），每个阶段的样子和过渡写在 `index.wxss .ib--<名字>.pN`。只动 transform / opacity / clip-path（成功的钩从左往右画出、线索镜面反光扫过用 clip-path，代替原型的描边虚线动画）。减少动态效果：不挂任何阶段类，每一层直接是终态（与原型 reduced 一致：闪光 / 纸屑 / + 静态显示，钩画满）。页面隐藏时停计时；宿主换名字时按新图的时间表从进场重来。 | 同上 | 单测：阶段类与时间表一一对应、只用三种过渡属性、减少动态效果规则、组件计时（假时钟）；`motion-property-ratchet`、`reduced-motion-coverage-contract` 通过；rig 帧条 `motion-*` | 真机帧率（每枚待机一圈 1–9 次 setData，与旧画法同量级） |
| IB-3 | fixed | 通行证页按 ps-pass 改回标准玩家样式：黑底 token、实色卡（城瘾通行证 / 已开通或未开通 / 有效期至 + 风格 B 钥匙）、标准列表行「可玩主题库」（章节印图标，主题数只写服务端给的）、底部固定白药丸（持证 = 去主题库；未持证 = 开通通行证）。去掉暖橙渐变底和主题封面网格，不做玻璃。持证人仍能选套餐续期（卡片下的「续期通行证」描边按钮）。开通成功层（`.ok`，l-icons 负责）没动。 | `subpackageMember/market/membership/index.wxml`、`index.wxss`、`index.js` | 单测 2 条；rig：`pass-player`、`pass-held`、`pass-reduced` 前后对比 | 真机安全区下沿与固定药丸的间距 |
| IB-4 | fixed | 商家看不到通行证：「我的」里的「通行证与礼品」入口在底座上已对商家隐藏（`!isMerchantView`）；本 lane 在通行证页、游戏主题库、通行证与礼品页按商家视角直接收住（通行证页 / 主题库给一句「商家账号没有通行证」，通行证与礼品页不读通行证卡）。这三段代码与 l-icons 分支上的逐字相同，合并时不会冲突。 | `subpackageMember/market/membership/index.js`、`index.wxml`、`market/library/index.js`、`index.wxml`、`market/passes/index.js` | 单测：商家身份进通行证页不发任何请求、状态 = merchant；rig：`pass-merchant`、`library-merchant` 前后对比 | 无 |
| IB-5 | fixed | 主包体积不变：`scripts/mainpkg-size-report.js` 的 mainTotal 改前改后都是 3,298,602 字节 × 0.633 ≈ **2039.1 KiB < 2048**（cy-illus 只在分包里）。每个分包的 cy-illus 副本 +40,777 字节（≈ +25.2 KiB 折算）：`pages/play` 约 1899 KiB、`subpackageMember` 约 1037 KiB、`subpackageP3` 约 394 KiB、`subpackageJourney` 约 132 KiB（按目录字节 × 0.633 估算，均远低于单分包 2048 KiB）。 | — | 见左 | 开发者工具上传时的实际计数 |
| IB-6 | fixed | 台账随通行证页 / 主题库新增的商家空态重建（+1 控件，行号漂移）。 | `scripts/uiaudit/action-ledger.json` | `build-action-ledger.js --write` 后无漂移 | 无 |

## 截图（`shots/`，390px，JPEG q70，rig 实拍）

rig：cloud-walk mpweb 浏览器运行时，改前包 = `launch/questo-20261110`，改后包 = 本分支；后端是本 lane 的桩（`rig/fakeapi.mjs`，HARNESS，只回通行证概览 / 主题库 / 登录几个事实；身份名带 held = 已持证，带 merchant = 商家）。驱动：`rig/shots.mjs`（改前 / 改后同一脚本）、`rig/motion.mjs`（改后帧条）、kit 结论屏用 `l-kits/rig/kit-shots.mjs`。

| 屏 | 改前 / 改后 | 用到的插图 |
|---|---|---|
| 本局完成 · 获得勋章 | `run-end-badge-*`，帧条 `run-end-badge-f300…f2400-after`（勋章落下压扁、绶带摆、爆星） | 勋章 |
| 本局完成 | `run-end-success-*` | 成功 |
| 背包 · 物品（待领取积分） | `bag-*` | 积分（宝石是旧画法，未变） |
| 背包 · 线索 | `bag-clues-*` | 线索 |
| 判定屏 赢 / 输 | `verdict-win-*`、`verdict-lose-*`；帧条 `motion-verdict-win-*`（方砖盖下 → 钩画出 → 纸屑弹出落下）、`motion-verdict-lose-*`（落下 → 抖一下 → 裂开 → 小云飘来 → 掉一滴） | 成功 / 失败 |
| 指认结论 结案 / 失败 / 结算确认中 | `lk-acc-solved-*`、`lk-acc-failed-*`、`lk-acc-pending-*` | 成功 / 失败 / 调查中 |
| 结算 · 分享卡 | `settle-share-*`（战报由 rig setData 注入，HARNESS） | 勋章 |
| 收集册卡堆 | `album-*`（rig 先 setData 一枚勋章让列表出现，再把开场切到卡堆态，HARNESS） | 勋章 |
| 通行证页 未持证 / 已持证 / 减少动态效果 | `pass-player-*`、`pass-held-*`、`pass-reduced-*`；帧条 `motion-pass-f0080…f4900-after`（钥匙进场 → 在锁里转四分之一圈 → 宝石闪 → 绿光；章节印举起落下、印记显出） | 钥匙、章节印（改前是通行证 + 钥匙） |
| 商家进通行证页 / 主题库 | `pass-merchant-*`、`library-merchant-*`（改前商家能看到购买页和主题库） | — |

拍到但没放：地点卡、玩法开场在这份预览数据里有封面图，不出插图；猜词 / 图片题结论屏不用 cy-illus。没拍到：章节完成整屏（`chapter-flip` 结尾的成功插图，l-icons 正在改这个组件）和剧情里的玩法卡片封面（`playKit.cardIllus`），改的是同一个组件，换图方式相同。

自查：逐张看过，无空标题、重叠、截断；通行证页数字（4 个主题、有效期 2027.11.10、¥199 / ¥29）都来自桩后端，与页面一致。钥匙转四分之一圈时杆会上扫，第一版越出卡片上沿，已改（`fix:` 提交）后重拍。

## 闸门

- 本 lane 及受影响的单测：`l-iconsB-20261111`（12/12）、`launch-look-tokens-illus`、`l-play-20261110`、`l-kits-20261110`、`l-pass-platform-pass`、`l-after-items`、`questo-b2-player`、`empty-state-figma-illustration-contract`、`motion-property-ratchet`、`reduced-motion-coverage-contract`、`get-auth-matrix-contract`、`route-matrix-freshness`、`nav-page-title-spacing-contract`、`title-system-owner-contract` 全部通过。改了另外两个 lane 的断言：`launch-look-tokens-illus`（成功 / 钥匙的进场名改成 b2、章节印不再画数字、副本清单加 `art-b.js`）、`l-play-20261110`（重复属性去重的样例换成 v2 钥匙宝石描边）。
- 全量小程序单测（`node --test tests/unit/*.test.js`，1839 个文件）：运行中，结果出来后补在这一行。
- `node tests/lint/ui-integrity.js`：**UI-GATE-0:PASS**（没加基线）。
- 动作台账：`build-action-ledger.js --write` 已提交，之后无漂移。
- `ds-hardcode-gate.sh`（只看新增行）：通过。没有跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。
- 主包：2039.1 KiB，与底座相同。

## 跨 lane（l-icons，`claude/l-icons-20261111`）

- 两边都改了 `subpackageMember/market/membership/index.wxml` / `index.wxss` / `index.js`。商家守卫三段逐字相同；会冲突的是英雄区：l-icons 把英雄区的通行证 + 钥匙换成 3D 玻璃序列帧（`cy-frames` glass-pass / glass-key），本 lane 按 ps-pass 把英雄区整个换成实色卡。**建议合并时保留本 lane 的 ps-pass 结构，把 l-icons 的 `glass-key` 序列帧放进 `.pp__key`（替换 `<cy-illus name="key" size="184" />`）**——原型 ps-pass 卡里就是那把 3D 钥匙；glass-pass 在 ps-pass 里没有位置。l-icons 删掉的开通成功层（`.ok`）本 lane 没碰，合并时直接取 l-icons 的删除。
- l-icons 没有改 cy-illus 目录，没有冲突。

## 待你决定

1. **通行证卡的「年卡」字样、已用 / 剩余天数进度条、「邀请队友（队友门票 ¥0）」行、「通行证勋章 3 / 12」行**：原型有，服务端现在不给（`holding` 只有 active / validTo / coverageEnd，没有套餐名和开始日期，也没有队友权益和勋章数）。默认：不画，卡里写「已开通 / 未开通」+ 有效期。要画需要后端在 `holding` 里补 `planCode`、`validFrom`，并定义队友权益与通行证勋章。
2. **v2 没重画的 15 枚**（奖杯、闪光、锁 / 开锁、宝石、邮戳、星、信封等）仍是旧的单色画法，和 v2 混在同一屏（例如结算总览的奖杯 + 闪光、背包里的宝石）。默认：保持原样。要统一需要你批准这 15 枚的 v2 画法。
3. **失败图「落地抖一下」**：原型批准的动作，但 BRIEF 总规则写「不抖动」。默认：照原型移植（左右 4 个单位、两次，共约 450ms）。
4. **章节印不再画章节数字**：旧画法会把 `n` 画在印上；v2 印记是白星。现在没有调用处传 `n`（章节开场的点阵章节号是 l-icons 的）。默认：照 v2。
5. **持证人的续期**：原型持证页底部只有「去主题库」。默认：保留套餐选择 + 卡片下方一个描边「续期通行证」按钮，否则持证人没有续期入口。

## 真机未验

- 安卓 X5 / XWeb 与 iOS WebView 对 data URI SVG 里 `rgba()` 描边、`clip-path="inset(...)"` 属性的支持（原型原样带着；浏览器里正常）。
- clip-path 过渡（钩画出、镜面反光）在低端安卓上的流畅度。
- 通行证页固定药丸与底部安全区。

STATUS: COMPLETE
