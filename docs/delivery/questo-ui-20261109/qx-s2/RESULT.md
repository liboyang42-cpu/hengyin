# qx-s2 · 第二、三轮 staging 集成 — RESULT（2026-11-10）

分支 `staging/questo-review-20261110`（只推了 staging）。没有推 `master`、`integrate/*` 或任何 lane 分支；没有合并 PR、没有标 ready；没有碰生产、VPS 或私有仓；没有新增或修改 migration；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`；没有提交任何密钥。

## 一、等待与到齐

- 10:33 UTC 开始每 15 分钟轮询一次（上限 14 小时）。10:33 时 7/12 到齐；r2-store、r2-create、r3-visual、r3-share、r3-pass 未到（r3-pass 分支当时还不存在）。
- 12:04 UTC 12 条全部有 `STATUS:` 行，没有 lane 被跳过：
  - COMPLETE：r2-play、r2-join、r2-store、r2-kits、r2-create、r2-merchant、r2-visual、r3-visual、r3-pass。
  - PARTIAL：r2-finish（「队内」「队伍」页签无组队夹具；视频分享、保存相册待真机）、r3-share（活动分享卡无夹具；真封面、小程序码、保存相册、视频分享待真机）。
  - qx-sm：「完成（草稿 PR 待审；6 项待你决定）」。
- 合并前 staging：`6d51e844`（qx-s1 结果）。等待期间先合了 u1、u2、u3、r2-visual。
- 写本文件前核对过远端：15 个 lane 的远端头都已包含在 staging 里，合并后没有新提交。

## 二、合并记录

全部 `git merge --no-ff`，顺序按任务给定。「门禁」一列是合并提交上跑的该 lane 改过的单测文件（括号里是文件数），不含 `action-ledger.test.js`：这个文件单跑一次要 6 分钟，所以每次合并都用同一表达式对重建后的台账直接求值核对计数，最后再整跑（见三）。

| # | Lane（头） | 合并提交 | 冲突与解法 | 该 lane 单测 | UI-GATE-0 | 台账控件数 |
|---|---|---|---|---|---|---|
| 1 | qx-u1 `00057e4b` | `8c450090` | 台账、`action-ledger.test.js` 计数：两侧注释都保留，数字按重建台账重测 | 74/0（6） | PASS | 5870 |
| 2 | qx-u2 `89b8627b` | `ea19147f` | 同上（相邻两行同时冲突，逐行合并） | 6/0（1） | PASS | 5876 |
| 3 | qx-u3 `1b75fcaa` | `874d5b39` | **`cy/sheet`、`cy/scene-sheet` wxml**：保留 u1 的 WXS 跟手手势，叠加 u3 的 `_under` 叠层景深类。**落地页 js/wxml**：保留 u2 版式，叠加 u3 主按钮收勾（`morph` + `claimDone`）；u3 的标题浮出 `headRise` 原挂在 u2 已删掉的正文标题上，改挂到 u2 的 `.ob-copy` 事实行（qx-u3 测试要求两处 `headRise`）。台账 | 10/0（1） | PASS | 5876 |
| 4 | r2-visual `8263e076` | `c742dda5` | 台账、计数 | 51/0（9） | PASS | 5882 |
| 5 | r3-visual `14eb4787` | `ccbc838d` | 台账、计数 | 20/0（3） | PASS | 5887 |
| 6 | r2-play `3b7b9bdb` | `61be3ee1` | `pages/play/play-help.wxss`：u3 行尾收勾（`.phl__act-box` 等）与 r2-play 提示居中弹窗（`.phl-pop*`）选择器不相交，两侧都保留。台账 | 139/0（9） | PASS | 5889 |
| 7 | r2-finish `0073a0b4` | `246911b6` | `pages/play/index.wxss` 两侧新增规则不相交，都保留；`journey-view.js` 保留 u2 的 `factLine` 和 r2-finish 的榜单函数，合并导出。**战报页三格（同一元素）**：u2 P7 的叠层战报卡和 r2-finish b3-p7「三格收进一张分组卡」改的是同一块，按「后一轮业主点名的改动优先」取 r2-finish，u2 叠卡在战报页退场。底栏保留 u2 的白色药丸 `ob-footer`，加上 r2-finish 的「结算」按钮。`qx-u2-onboarding` 的 P7 断言改成同样的业务含义（不计分 / 用时未知弱化显示、不画成 0）。台账 | 45/0（5）；qx-u2 6/0 | PASS | 5907 |
| 8 | r3-share `6adeb4ca` | `e31a602f` | 只有台账冲突，重建后数字不变 | 12/0（3） | PASS | 5907 |
| 9 | r2-join `3d097ae9` | `37d3e97e` | **落地页（同一元素）**：r2-join b3-p1-5 把正文重做成「邀请人 ↔ 你」+「怎么玩」模式卡，与 u2 的叠卡 hero、P4 横向分页改的是同一块，取 r2-join，u2 的叠卡和 swiper（`onModeChange`、`factLine`、`cardIcon`）退场。保留：u2 的白色药丸主按钮（`ob-footer`）和「先逛逛」，u3 的收勾 morph，`headRise` 改挂到 `.jl-facts`。`qx-u2-onboarding` 的 P1/P4 断言改成合并后的结构，保留「切换模式不发请求」。台账 | 23/0（2）；qx-u2 + qx-u3 39/0 | PASS | 5904 |
| 10 | r2-store `9d3c450c` | `df796224` | 落地页领取失败分支：用 r2-store 的「自己购买」出口，加上 u3 的 `busy:false`。台账 | 62/0（5） | PASS | 5913 |
| 11 | r3-pass `079879bf` | `e6415284` | 台账、计数 | 8/0（1） | PASS | 5917 |
| 12 | r2-kits `51e44422` | `1c7de1f4` | 台账、计数 | 116/0（5） | PASS | 5920 |
| 13 | r2-create `f459d42a` | `5db52ed7` | `publish/temp/index.wxss`：r2-kits 的填字格改版 + r2-create 的素材来源样式，两侧都留。**模板详情（同一元素）**：r2-create b4-u02 撤掉了采用换城弹层，取 r2-create；u3 挂在弹层「确定」上的收勾随弹层退场，「应用」按钮上残留的 u3 morph 属性和 `morph-success` 引用一并删掉（数据已不存在）；`qx-u3-motion` 改为只守「复制失败不进编辑器」。`build-action-ledger.js`：u1 的 `binding.wxs` 判定和 r2-create 的 `wxsModules` 参数并存。`ui-integrity-baseline.js`：两侧的说明都保留；fabu 5 条 U4-DYNAMIC 锚点按 r2-join 删掉的 8 行再平移（6989→6981、7001→6993、7068→7060、7626→7618、9908→9900），逐行对照 r2-create 头的原行，内容完全相同；temp 用 r2-create 的重锚结果；`u4Dynamic` digest 用门禁自己的 `baselineKeyDigest` 重算，**没有新增登记**。`route-matrix.json` 重新生成（167）。`get-auth-matrix` 直接 GET 124 → 123（预设页退场）。台账 | 201/3（14），3 条在 `8435ceb6` 修好 | PASS | 5930 |
| 14 | r2-merchant `b654620a` | `10d0f28d` | **AI 店铺角色首次介绍（同一页）**：业主 a6-w2-16 要求舞台回到上一版 3D 物件，取 r2-merchant 的舞台和光晕；u2 的顶栏（返回 / 分段进度 / 跳过）、舞台左右滑、标题句点和减动效规则改的是别的元素，都保留。台账 | 25/0（3）；qx-u2 6/0 | PASS | 5936 |
| 15 | qx-sm `0259d535` | `03716ded` | 无冲突 | 7/0（2） | PASS | 5936 |

staging 上的小修提交（只修集成时才出现的问题）：

- `8435ceb6` fix：
  - `title-system-owner-contract`：r2-finish 新增的结算页 `subpackageJourney/pages/settle` 补进标题盘点（custom 导航，没有 cy-page-title）。合并树 settle +1、preset −1，结果 167，custom 166。
  - `route-matrix-freshness` 改为 167，原因同上。
  - `overlay-motion-20261023-contract`：断言放行 u3 叠加的 `sheetDepth`（退场 behavior 仍是 `exitMotion(250)`，且排第一）。
- `77056d67` fix：
  - `build-action-ledger.js`：u1 原来对任意 `{{a.b}}` 直接放行，和 r2-create 的「只有本文件声明过的 wxs 模块才放行，未声明的要显式失败」矛盾，改为统一走 `wxsModules`。u1 的 `gs.*` 绑定所在的 WXML 都声明了 `<wxs module="gs">`，台账重建后逐字节不变（5936）。
  - `action-ledger.test`：选章控件 id 的行号 525 → 530（只是行号位移）。
  - `r2-visual-items.test`：断言放行第二个参数 `wxsModules`。
  - `popup-navigation-height-runtime.test`：沙箱补上 u3 的 `behaviors/sheet-depth.js` 空桩。

## 三、最终门禁

| 门禁 | 结果 |
|---|---|
| 小程序全量单测（仓库根 `node --test chengyinhub-xcx/tests/unit/*.test.js`，在 `77056d67`） | **17138 个测试，17128 通过，10 失败**。10 条全部是 lane 自己的分支上就已经红的（见六），集成带来的失败已全部修好 |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | **UI-GATE-0:PASS**（U1–U5、A11Y、Legacy 全 PASS；U4 动态 setData 63/63 已登记） |
| `build-action-ledger.js --write` / `build-route-matrix.js --json` | 5936 个控件，167 条路由；`git status` 无改动（无 diff） |
| `action-ledger.test.js` 整跑 | 48/48 通过 |
| `mvn -q -DskipTests package` | 通过（容器里需要 `LANG=C.UTF-8`；第一次 `-o` 离线跑缺 BOM，改为联网跑后通过） |
| 改动类的 Java 测试 | `mvn -pl chengyinhub-admin -am -Dtest=<25 个类> test`：**451 个测试，0 失败，0 错误**，BUILD SUCCESS。25 个类：本轮改过的 `*Test`（`ChapterTextFormatFlowTest`、`AdvancedGameHelpWiringTest`、`AuditTaskStateCharacterizationTest`、`AuditTaskStateMachineTest`、`SeatInvitationStateMachineTest`），加上改过的主类对应的测试（`AdvancedGameRuntimeServiceImplTest`、`AdvancedGamePublicProjectionTest`、`RegistrationSeatInvitationServiceTest`、`ChapterFlow{Copy,Transaction,WriteService}Test`、`AuditTask*Test` 14 个，含 `AuditTaskQueueH2Test`、`AuditTaskMapperCasContractTest`） |
| 管理端 `chengyinhub-ui` `npm run build:prod` | 通过（13 个 webpack 警告，0 错误）。本轮没有改管理端。lockfile 指向 `registry.npmmirror.com`，容器代理拒绝该源，所以照 qx-s0 的做法用 `npm ci --registry=https://registry.npmjs.org/ --replace-registry-host=registry.npmmirror.com`，lockfile 未改 |
| 主包体积 | **2030.7 KiB < 2048**（见四） |
| keyframes 棘轮 | `KEYFRAMES_CEILING` 仍是 **146，未改**；但当前树实测 169 组，测试是红的，全部来自 lane 自身（见六 Cross-lane 1） |

## 四、主包体积

`node chengyinhub-xcx/scripts/mainpkg-size-report.js --json` 的 `mainTotal` × 0.633：

| 树 | 源码字节 | 估算 |
|---|---|---|
| 合并前 staging `6d51e844` | 3,233,159 | 1998.6 KiB |
| 本轮合完 `77056d67` | 3,284,987 | **2030.7 KiB** |

增加 32.1 KiB，余量约 17.3 KiB，没有搬页面或组件。下一波如果还要往主包里加东西，先按 MOTION.md §5.3 把 `club-direct-ledger` 和 staging/RESULT §4 列出的 10 个零引用组件移出主包。这是按源码估算的值，权威数字以 Mac 上 DevTools 的上传体积为准。

## 五、同一元素被两条 lane 改动时的取舍（已按「后一轮业主点名优先」执行）

| 页面 / 元素 | 取用 | 退场 | 保留的另一侧改动 |
|---|---|---|---|
| 战报页三格数据 | r2-finish b3-p7 分组卡 | qx-u2 P7 叠层战报卡（NRC 大数字） | u2 白色药丸分享按钮 |
| 分享落地页正文 | r2-join b3-p1-5「同行」+ 怎么玩 | qx-u2 叠卡 hero、P4 横向分页 | u2 药丸主按钮、「先逛逛」；u3 收勾、事实行浮出 |
| 模板详情采用 | r2-create b4-u02（去掉换城弹层） | qx-u3 弹层「确定」收勾 | — |
| AI 店铺角色首次介绍舞台 | r2-merchant a6-w2-16（3D 物件） | 积木 logo（u2 时已存在） | u2 顶栏返回 / 进度 / 跳过、左右滑 |

## 六、Cross-lane（lane 自己的问题；各自分支上已经是红的，staging 没有替它们改）

全量单测里剩下的 10 条失败都属于下面几项。每一项都在对应 lane 的分支头上复跑过，结果同样是红的（已逐个检出 worktree 复跑）。旧 staging 基线 `6d51e844` 上这些测试全绿。

1. **keyframes 棘轮 169 > 146**（`motion-property-ratchet`）。MOTION.md 规定不准新增 `@keyframes`，但：
   - r2-finish +3（`pv-ending-ring`、`st-pop`、`st-ring`）；
   - r3-share +3（`sc-rise`、`sc-pop`、`sc-up`）；
   - r2-join +9（落地页 `jl-*`、组队 `tm-seat-in`、`tj-seat-in` 等，r2-store 与 r3-pass 叠在 r2-join 之上，跟着变红）；
   - r2-merchant +8（`ni-rise` 等）。
   
   上限没有调高。需要各 lane 改成 transition / WXS。
2. **减动效覆盖**（`reduced-motion-coverage-contract`）：上面这些新动画里，settle（r2-finish）、share-card（r3-share）、landing / team（r2-join）、ai-npc（r2-merchant）的位移动画缺少 `--reduced` 规则或 `wx:if="{{!reducedMotion}}"`。
3. **r2-finish**（r3-share 叠在它上面，也跟着变红）：
   - 结算页 `subpackageJourney/pages/settle`：根栈返回审计 `onBack`、`done`（`all-page-back-stack`）；实底顶栏没有标题（`custom-nav-page-surface`）；4 处原生 toast（`no-system-toast`）；没有登记进页面清理清单（`page-cleanup-audit`）。
   - 榜单组件 `rank-board`：用了系统 ActionSheet（`no-system-actionsheet`）；WXSS 用标签选择器 `.rb-digit__col text`、`.rb-av image`（`component-wxss-selector-lint`，微信组件里不生效）；`cy-pressed` 在组件样式里取不到（`press-feedback-resolves`）。
   - `pages/play/index.wxss` 故事文字动效段之后追加了 `@keyframes`（`theme-look` 要求那一段只用过渡）。
4. **r2-play**：`pages/play/utils/play-location-once.js` 新增 1 处原生 toast（`no-system-toast`）。
5. **r2-join**：模式卡点选直接调 `wx.vibrateShort`，MOTION.md §5.1 要求改用 `utils/motion.js` 的触感 helper（不影响门禁，顺带记下）。
6. qx-u3 的两条测试桩问题（`overlay-motion-20261023`、`popup-navigation-height-runtime`）在 u3 自己的分支上也是红的。它们只是测试沙箱没跟上 u3 新加的 `sheetDepth`，产品代码没错，所以在 staging 上已经小修（`8435ceb6`、`77056d67`）。这里记一笔。

## 待你决定

1. **战报页三格**：现在显示 r2-finish 的分组卡，qx-u2 的叠层战报卡（计分时显示粗斜体大数字）不再出现。默认：按规则用 r2-finish。如果你更想要 u2 的叠卡，需要在 r2-finish 的分组卡和 u2 叠卡之间二选一，不能两个都留（数据会重复）。
2. **分享落地页**：现在显示 r2-join 的「同行」结构，u2 的倾斜叠卡和横向分页模式卡不再出现。默认：用 r2-join，同时保留 u2 的白色药丸主按钮和「先逛逛」。「先逛逛」放在底栏里，底栏只在有主按钮或报错时出现，所以没有主按钮时「先逛逛」也不出现。
3. **模板「应用」的收勾动效**：u3 原本在采用弹层的「确定」上收勾，弹层被 r2-create 撤掉以后，这个动效也一起没了。默认：不补。如果要在「应用」按钮上补收勾，需要给这个 ghost 按钮加 loading 和 done 状态，rig 截图确认后再上。
4. **keyframes 棘轮**：四条 lane 一共新增 23 组。默认：上限保持 146 不动，由 lane 自己改成 transition。如果你接受其中某些动效（比如结局印章的光环），需要明确批准调高上限，并在测试里写清日期和原因。
5. qx-sm 留给你的 6 项（报名支付「已过期」怎么显示、退款打款回写守卫、管理端直接改状态、组队「已开始」态、活动删除竞态、成团文案）原样保留，见 `qx-sm/RESULT.md`。

## 全量单测明细（`77056d67`）

- 第一次整跑在 qx-sm 合入后的 `03716ded`：17138 个测试，20 条失败。逐条检出 worktree，到旧 staging 基线和 15 个 lane 头上复跑分类：
  - 10 条是集成带来的，已在 `77056d67` 修好：台账构建器 2 条、选章行号 1 条、`r2-visual-items` 1 条、`popup-navigation-height-runtime` 7 条中的 6 条再加 1 条 u3 沙箱桩。
  - 其余 10 条是 lane 自己的问题。
- 复跑 `77056d67`：17138 个测试，17128 通过，10 失败。失败所在文件：`all-page-back-stack-contract`、`component-wxss-selector-lint`、`custom-nav-page-surface-contract`、`motion-property-ratchet`、`no-system-actionsheet-contract`、`no-system-toast-contract`、`page-cleanup-audit`、`press-feedback-resolves-contract`、`reduced-motion-coverage-contract`、`theme-look`，各 1 条，归属见六。

STATUS: PARTIAL - 15 条全部合入 staging，UI-GATE-0、台账和路由矩阵零差异、mvn 打包、Java 451 个测试、管理端构建、主包 2030.7 KiB 都通过；小程序全量单测还剩 10 条失败（含 keyframes 棘轮 169>146），都是 r2-finish、r3-share、r2-join、r2-merchant、r2-play 自己分支上就已经红的，见 Cross-lane
