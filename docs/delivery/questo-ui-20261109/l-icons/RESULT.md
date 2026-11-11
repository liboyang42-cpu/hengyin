# l-icons 结果（上线波次 · 图标风格 A / C 与通行证钥匙开场）

分支 `claude/l-icons-20261111`，从 `origin/launch/questo-20261110` 切出；交付前已再合入最新 launch（`afa90ffd`），合并后动作台账按合并树重建。草稿 PR 合入 `launch/questo-20261110`。
依据：业主 2026-11-11 批准的五条（C 圆点只用于章节号与章节印；A 3D 玻璃只用于奖励时刻；通行证钥匙开场；玻璃只给星尘主题内卡片 / 弹窗；商家没有通行证）。B 风格基础图标归 l-iconsB，本线没动；`cy-illus` 的 API 没改。

截图在 `shots/`，单张 390×844、JPEG q70，`strip-*.jpg` 是帧条，每张我都看过。rig 说明见文末。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| 1 圆点章节号 + 章节印（C） | 已完成 | 章节开场 / 完成屏的章节号从金色方块改成原型 `dots.js` 的圆点点阵（格 11 · 点 4.6，带亮波）；开场屏章名下面加圆点章节印：外环 44 点顺时针慢转、内环 28 点逆时针、中间「章」字点阵，圆点从星野飞入落位。颜色读新加的 `utils/look-colors`（`--lk-a` / `--lk-ink` 的 JS 镜像），四种外观 × 五种配色都覆盖，老主题回落原型金。减少动态效果：直接落位、不转、无亮波。章节印等文字块落位后量出空位再放，夹在文字块和按钮中间。通关的银色标志仍是方块。 | `pages/play/components/chapter-flip/index.js`、`pages/play/utils/look-colors.js`、`pages/play/look-tokens.wxss`、`pages/publish/fabu/look-tokens.wxss`（同步副本） | `tests/unit/l-icons-20261111.test.js`（look-colors 与 wxss 镜像一致、圆点章节门、减少动态效果终态）。截图：`strip-ch-start-frames-before/after`（同一时间点前后对比）、`strip-ch-start-looks-after`（四种外观 + 减少动态效果）、`strip-ch-start-palettes-after`、`ch-start-<外观>-<配色>-after` 共 20 张、`ch-end-*`。 | 真机 canvas 帧率；章节印在矮屏（≤667pt）上会按空位缩小，没在真机看过 |
| 2 奖励时刻 3D 玻璃（A） | 已完成 | 从 `proto/icons/glass3d.js` 用无头 Chrome 烤出 24 帧、112px 的 WebP 网格雪碧图：本局完成的勋章 / 成功（`pages/play`）、通行证英雄区的通行证与钥匙（`subpackageMember`），每张 39–55 KB。新加 `cy-frames` 序列帧播放器：`image` + `transform` 切帧，不用 keyframes；同时只挂当前 / 下一张 / 再下一张三张图；`once` 播完抛 `end`；减少动态效果停在静帧且不起计时器；页面隐藏时暂停。规范实现在 `pages/play`，两个分包里是逐字副本（测试钉住一致）。 | `pages/play/components/cy-frames/*`、`pages/play/images/frames/*`、`pages/play/index.{wxml,json,js}`、`subpackageMember/components/cy-frames/*`、`subpackageMember/images/frames/*`、`subpackageMember/market/membership/*` | 单测：cy-frames 切帧 / 挂图上限 / end / 减少动态效果；每个 webp 都有调用者。截图：`strip-run-end-badge-after`（500/900/1400ms）、`strip-reward-station`（勋章 / 成功前后）。烤帧脚本：`bake/bake.mjs`、`bake/bake.html`。 | 真机 WebP 解码与切帧是否掉帧；`glass-key.webp` 55 KB，略超 50 KB 目标 |
| 3 通行证钥匙开场 | 已完成 | 新分包 `subpackagePass/reveal`：原型 `ps-key` 的 3D 钥匙按 satya2 时间轴烤成序列帧（上半区 585×780，开场 83 帧 @18fps、待机 72 帧 @12fps，3 列网格 WebP 共 26 张，分包合计 1.7 MB，在 ≈2 MB 预算内）。底部暖橙渐变、眉题、标题、正文、「开始」按钮是活的 DOM，按时间轴依次进场（只用 transition）。完整版只在第一次进入和开通成功（`mode=paid`，只带服务端给的主题数 / 到期日）时播，之后进来直接待机循环；右上「跳过」只在完整版里出现。减少动态效果直接终态。通行证列表的「通行证」卡先进钥匙开场；通行证页开通成功改跳钥匙开场，旧的锁 / 钥匙 ok 层退场。 | `subpackagePass/reveal/*`、`subpackagePass/frames/*`、`subpackagePass/components/cy-frames/*`、`app.json`、`subpackageMember/market/passes/index.js`、`subpackageMember/market/membership/*` | 单测：分包与帧清单（每张图都在、合计 ≤ 2 MB、83 / 72 帧）、「跳过」只在完整版、减少动态效果终态、商家只给一句原因。截图：`strip-key-intro-frames`、`strip-key-idle-frames`（直接从烤好的雪碧图切帧）、`strip-reveal-first-after`（rig 里首次进入，截到时开场已播完、进入待机）、`reveal-again-after`、`reveal-paid-after` → `reveal-paid-to-library-after`、`reveal-start-to-membership-after`、`reveal-reduced-after`。 | 真机 18fps 下 26 张大图的内存与解码；首帧前的加载空白 |
| 4 星尘玻璃 / 通行证页不做玻璃 | 已完成 | 星尘主题内的卡片 / 弹窗按 `proto/icons gl-stardust` 配方对齐：模糊 22px、饱和 1.7、高光边、斜向反光、3% 噪点。纸本 / 末世 / 夜城和通行证、结算、平台页都不做玻璃；通行证页本身是普通玩家样式。 | `pages/play/look-tokens.wxss`、`pages/publish/fabu/look-tokens.wxss` | 单测钉住通行证页 wxss 里没有 `backdrop-filter` / `filter: blur`。截图：`station-stardust-before/after`、`station-survival-after`（末世不做玻璃）、`membership-before/after`。 | 真机 `backdrop-filter` 性能 |
| 5 商家没有通行证 | 已完成 | 通行证页、游戏主题库、钥匙开场页、通行证列表里的通行证卡和主题库行都按 `roleGuard.isMerchantView` 守卫，商家只看到「商家账号没有通行证」，没有任何通行证入口。合并后发现钥匙开场页的商家空态插图被导航压住（父级不是 flex 列），已修。 | `subpackageMember/market/{passes,membership,library}/*`、`subpackagePass/reveal/*` | 单测：商家守卫（含钥匙开场页商家空态是 flex 列）。截图：`strip-pass-before`（改前商家能看到通行证卡、通行证页和购买按钮）对比 `strip-pass-pages`（改后）、`reveal-merchant-after`。 | 真机商家账号走一遍 |
| 合并收口 | 已完成 | 合入最新 launch 后动作台账重建：6007 → 6009（钥匙开场页「跳过」「开始」tap +2，通行证页两处「游戏主题库」tap −2，开场页 `bind:back` / `bind:end` 事件合同 +2），三处计数断言按重建实测更新。 | `scripts/uiaudit/action-ledger.json`、`tests/unit/action-ledger.test.js` | `build-action-ledger.js --check` PASS（6009）；`action-ledger.test.js` 48/48。 | — |

## 门禁

- 小程序全量单测（`node --test "tests/unit/**/*.test.js"`）：合并 launch 后第一次跑 17236 项，只有动作台账两项计数没跟上（6007/6009、2986/2988），重建台账后 `action-ledger.test.js` 48/48 通过；最终全量结果见下一行。
- 台账重建后再跑全量：17236 项全部通过（0 失败）。之后只给 `l-icons-20261111.test.js` 加了两条商家空态断言，单跑 9/9 通过。
- `ui-integrity.js --warn-as-error`：UI-GATE-0:PASS；动作台账 `--check` PASS。
- 主包：新素材全部在分包（`pages/play`、`subpackageMember`、`subpackagePass`）；`mainpkg-size-report` 改前改后都是 3221 KB 源码（未压缩，与 launch 一致，主包里本线只改了 `app.json` 加一个分包）。真实压缩后体积要用微信 CLI `--info-output` 量（`wechat-release-contract` 报 WAIT-EXTERNAL）。
- 没有新增 `@keyframes`（diff 里出现的只是注释和测试断言）。按约定没跑 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## 待你决定

1. 通行证页英雄区现在用的是 3D 玻璃序列帧（通行证 + 钥匙），我把它算作「通行证」奖励时刻；页面其余部分（价格卡、按钮、主题库）是普通样式，没有玻璃材质。如果「里面不需要是玻璃的」也包括这块插图，改回原来的 `cy-illus pass/key` 只需改 `membership/index.wxml` 两行，并删掉 `subpackageMember/images/frames` 和那份 `cy-frames` 副本。
2. `glass-key.webp` 55 KB，比 20–50 KB 的目标多 5 KB；要压到 50 KB 以内需要降一点质量或减到 20 帧。
3. 钥匙开场的序列帧合计 1.7 MB，在 2 MB 预算内，但 `subpackagePass` 是首次进入才下载；网络差时首帧前会有一小段黑屏，要不要给它加预下载规则（`preloadRule`）由你定。

## Rig

- 用 `claude/cloud-walk-20261101-rig` 的 `mpweb` 浏览器运行时（真实打包本仓库 `chengyinhub-xcx` 的全部页面和分包）。before 是 `origin/launch/questo-20261110` 的工作树，after 是本分支。
- 后端是本线的桩（`rig/fakeapi.mjs`，HARNESS）：只回登录、角色、通行证、主题库、用户信息这几屏要读的事实，其余一律 404。游玩页走 `pages/play?preview=1` + `l-play/rig/gen.js` 的预览数据，外观 / 配色用环境变量切。
- 驱动脚本 `rig/shots.mjs`，帧条 `rig/strip.mjs`。钥匙开场的开场段在 rig 里来不及截（启动等待 2.5 s 时已播完），所以开场帧条直接从烤好的雪碧图切。
- 已知限制：rig 里是 Chrome 渲染，不是微信基础库；WebP 解码、canvas 帧率、`backdrop-filter`、安全区都要真机看。

STATUS: COMPLETE — 五项都已落地并有截图与单测；待你决定 3 条；真机未验。
