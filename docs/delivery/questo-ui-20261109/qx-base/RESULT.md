# qx-base · launch 底座 — RESULT（2026-11-10）

分支 `launch/questo-20261110`，从 `origin/staging/questo-review-20261110`（`941898f5`）切出，已推送。只推了这一个分支；没有推 `master`、`integrate/*`、`staging/*` 或任何 lane 分支；没有合并 PR；没有碰生产、VPS 或私有仓；没有新增或修改 migration；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交密钥。

## 一、合并记录

LAUNCH.md 列出的 12 条 lane 在切分支时**已经全部包含在 staging 里**（qx-s2 第二、三轮集成已按同一顺序 `--no-ff` 合入，冲突解法见 `qx-s2/RESULT.md` §二、§五）。逐条核对远端头：`git rev-list --count HEAD..origin/claude/<lane>` 全部为 0，所以这里的合并都是空操作，没有生成新的合并提交，也没有新增 UI-integrity baseline 条目。

| # | Lane | 远端头 | staging 中的合并提交 | 本分支动作 |
|---|---|---|---|---|
| 1 | qx-u1 | `00057e4b` | `8c450090` | 已包含，空操作 |
| 2 | qx-u2 | `89b8627b` | `ea19147f` | 已包含，空操作 |
| 3 | qx-u3 | `1b75fcaa` | `874d5b39` | 已包含，空操作 |
| 4 | r2-visual | `8263e076` | `c742dda5` | 已包含，空操作 |
| 5 | r2-play | `3b7b9bdb` | `61be3ee1` | 已包含，空操作 |
| 6 | r2-finish | `0073a0b4` | `246911b6` | 已包含，空操作 |
| 7 | r2-join | `3d097ae9` | `37d3e97e` | 已包含，空操作 |
| 8 | r2-kits | `51e44422` | `1c7de1f4` | 已包含，空操作 |
| 9 | r2-create | `f459d42a` | `5db52ed7` | 已包含，空操作 |
| 10 | r2-merchant | `b654620a` | `10d0f28d` | 已包含，空操作 |
| 11 | r2-store（有 STATUS: COMPLETE） | `9d3c450c` | `df796224` | 已包含，空操作 |
| 12 | qx-sm（有 STATUS 行） | `0259d535` | `03716ded` | 已包含，空操作 |

**注意**：staging 里同时已经合入了 `r3-visual`（`ccbc838d`）、`r3-share`（`e31a602f`）、`r3-pass`（`e6415284`）。LAUNCH.md 说不要合 r3-*/r4-*，但它们在指定的底座里已经存在。我**没有回退**它们（回退会和 r2-store/r2-kits 等后续合并交错，风险大），见「待你决定」1。r4-* 不在底座里。

## 二、底座提交

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机未验 |
|---|---|---|---|---|---|
| BASE-A 外观变量 `--lk-*`（`e6d476b0`） | 已完成 | 按 `proto/game/look.js` 的 STYLES × PAL（星尘 / 纸本 / 末世 / 夜城 × 黑蓝红黄白，共 20 组）与 `page.css` 的派生量，逐字换算出 `--lk-bg/ink/mute/a/b`、字体 `--lk-fd/fw/ls/fs/fm`、圆角 `--lk-r/rb`、切角 `--lk-cut`，以及派生的 `--lk-card/pop/ctl/dim` 和 `--lk-*-rgb`。派生色预先算成定值（不用 `color-mix`，部分基础库不支持），透明叠色写成 `rgba(var(--lk-ink-rgb), α)`。挂载点：玩家页根节点已有的 `lookClass`（`look--<风格>`、`look-palette--<配色>`）和章节配色 `.atmosphere--<配色>`。读者类：`.lk-pop`（弹窗）、`.lk-card`（卡片）、`.lk-btn--primary/accent/ghost/text/ok`、`.lk-chip`、`.lk-title`、`.lk-story`、`.lk-num`。星尘 = 果冻玻璃（半透明渐变 + `backdrop-filter: blur saturate brightness` + 顶边高光 + 斜向反光 `::before`）；末世 = 锈蚀（锈斑径向渐变 + 左缘锈线 + 横纹 + 锈色描边 + 标题锈色错位阴影 + 正文压暗影）；夜城主按钮用强调色、标题霓虹光；纸本按钮用标题字体。`.lk-plain` 把整组变量强制回玩家黑（`--cy-color-*`），并关掉玻璃 / 锈蚀，供结算和平台页使用。 | `chengyinhub-xcx/pages/play/look-tokens.wxss`（新）、`pages/play/index.wxss`（首行 `@import`） | `tests/unit/launch-look-tokens-illus.test.js`：20 组配色逐值对照原型 PAL、四种外观默认值、玻璃 / 锈蚀 / `.lk-plain` 规则存在、不含 `@keyframes` / `color-mix(` / `transition: all`。`ds-hardcode-gate.sh` 只报两行 `rpx` 尺寸提示（与原型 px×2 对应），色值行都带 `ds-ok`。 | 真机上 `backdrop-filter` 与 `clip-path: polygon(calc())` 在低版本基础库的表现 |
| BASE-B `cy-illus` 插图组件（`125de3bd`） | 已完成 | 移植 `proto/game/icons.js` 全部 25 张空态风格插图（线索、勋章、邮戳、钥匙、成功、失败、调查中、队伍、席位、通行证、积分、章节印、名次、雷达、背包、任务、邀请、评价、未解锁、已解锁、等待、藏品、闪光、提示、光芒），SVG 路径逐字不变。小程序不能内联 `<svg>`，所以拆成两层 data URI（白色有机形底 + 手绘物件），分别做动画。**进场**：底层 scale .3 → 1、物件 scale .5 → 1；钥匙从 −70° 摆入、章节印从 1.5 倍压下、失败 / 积分从上方落下、成功 / 闪光 / 光芒从 0 旋转放大。**待机**：底层呼吸，物件按原型分类做漂浮 / 慢转 / 沙漏翻转 / 奖杯摇摆 / 钥匙摆动 / 吊挂摇 / 放大镜扫 / 云飘。全部是 transform/opacity 过渡，由计时器在 `is-idle` 上往复切换，**不新增 keyframes**。减少动态效果（系统或应用内开关，或宿主传 `reducedMotion`）直接终态、不起计时器；页面隐藏时暂停待机。属性：`name`、`size`（rpx）、`n`（章节印数字，已转义）、`delay`、`bare`、`loop`、`reducedMotion`；方法 `replay()`。 | `chengyinhub-xcx/pages/play/components/cy-illus/{art.js,index.js,index.json,index.wxml,index.wxss}`（新）、`tests/unit/launch-look-tokens-illus.test.js`（新） | 同一单测：25 张、未知名回落线索、章节印数字转义、组件进场 / 减动效分支；另有一条守卫：任何其他分包里的 `cy-illus` 副本必须与规范实现逐字相同（只允许 `motion-preference` 的相对路径不同）。 | 原型里个别子部件的独立动画（感叹气泡摆动、雷达指针、硬币依次落下）在两层图片里只能整体动，已尽量贴近 |

**放置**：`cy-illus` 约 18 KB 源码（折算约 11 KiB），主包只剩约 17 KiB 余量，不算「很小」，所以按 LAUNCH.md 放在 `pages/play` 分包作为规范实现。其他分包要用时，把整个目录逐字复制到本分包 `components/cy-illus/`，只改 `index.js` 里 `motion-preference.js` 的相对路径；单测会检查副本一致。

**截图**：两条底座提交都没有改动任何现有元素的类名或样式（`--lk-*` 是新变量名，`.lk-*` 类和 `cy-illus` 目前没有页面在用），所以现有页面像素不变，没有 before/after 可拍。真正挂到各屏上的工作由 l-play / l-kits / l-after 在各自的截图里完成。

## 三、门禁（在 `125de3bd`）

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`） | **17144 个测试，17134 通过，10 失败**。底座是 17138 / 10 失败（qx-s2），本分支 +6 个测试全部通过；10 条失败与 qx-s2 §六列出的完全相同（同样 10 个文件各 1 条），都是 lane 自身带来、在 staging 上已经是红的，不是底座引入的 |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**（U5、A11Y、Legacy 全 PASS） |
| `build-action-ledger.js --write` / `build-route-matrix.js --json` | 5936 个控件，167 条路由；`git status` 无改动（无 diff） |
| `mvn -q -DskipTests package`（`LANG=C.UTF-8`） | 通过 |
| 管理端 `chengyinhub-ui` `npm run build:prod` | 通过（13 个 webpack 警告，0 错误）。lockfile 指向 `registry.npmmirror.com`，容器代理拒绝该源，照 qx-s0/qx-s2 用 `npm ci --registry=https://registry.npmjs.org/ --replace-registry-host=registry.npmmirror.com`，lockfile 未改 |
| keyframes 棘轮 | 上限仍 146 未改；实测仍 169（与底座相同），本分支 +0 |

## 四、主包体积

`node chengyinhub-xcx/scripts/mainpkg-size-report.js --json` 的 `mainTotal` = 3,284,987 字节，× 0.633 ≈ **2030.7 KiB < 2048**，与底座完全相同（本分支所有新文件都在 `pages/play` 分包）。余量约 17.3 KiB：后续 lane 的新代码一律放分包；如果确实要进主包，先把 `club-direct-ledger` 和 10 个零引用组件移出主包（qx-s2 §四）。

## 五、Cross-lane（给后续 lane）

1. **l-play / l-kits**：主题内弹窗、卡片、按钮请直接加 `.lk-pop` / `.lk-card` / `.lk-btn lk-btn--primary` 等类，或在组件样式里读 `var(--lk-*)`；自定义组件需要在自己的 wxss 里 `@import "/pages/play/look-tokens.wxss"` 才能用这些类（变量本身会穿透组件继承）。章节内换配色只需保留现有 `.atmosphere--*`，`--lk-*` 自动跟随。
2. **l-after**：结算 / 排行榜 / 评价 / 分享等平台页不要挂 `look--*`；如果这些层还在玩家页根节点下面，外层加 `.lk-plain`，即回到玩家黑、无玻璃 / 锈蚀。
3. **所有用到插图的 lane**（运行结束、指认结果、相册、票夹、通行证购买成功等）：用 `<cy-illus name="success" size="200" />`；分包外使用请按上面的规则逐字复制。
4. 10 条既有失败的归属与修法见 `qx-s2/RESULT.md` §六（r2-finish、r3-share、r2-join、r2-merchant、r2-play、u3），launch 的各 lane 在碰到这些文件时顺手修；底座没有替它们改。

## 待你决定

1. **底座里已经有 r3-visual / r3-share / r3-pass**（staging 之前已合入）。LAUNCH.md 说 r3-* 被原型取代、不要合。默认：保留不回退，由对应新 lane（l-play、l-after、l-pass）按原型覆盖同一元素；如果你要彻底去掉，需要 qx-final 在合完各 lane 后统一 revert 这三个合并提交并重跑门禁。
2. **keyframes 棘轮 169 > 146** 仍是红的（底座本来就红）。默认：上限不动，由引入的 lane 改成过渡。
3. **cy-illus 不进主包**。默认：`pages/play` 一份规范实现，其他分包逐字复制（有单测守一致）。如果你更想要主包一份共享组件，需要先腾出约 11 KiB 主包空间。

STATUS: COMPLETE - 底座可用：12 条 lane 已在 staging 中（空操作合并），两条底座提交（--lk-* 外观变量、cy-illus）已推送；UI-GATE-0、台账 / 路由矩阵零差异、mvn 打包、管理端构建、主包 2030.7 KiB 都通过；全量单测 10 条失败与 staging 底座相同（非本分支引入）
