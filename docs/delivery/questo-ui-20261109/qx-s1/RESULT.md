# qx-s1 · staging 刷新 + 补拍截图 — RESULT（2026-11-10）

分支 `staging/questo-review-20261110`。只推了 staging，没有推 `master`、`integrate` 或任何 lane 分支；没有合并 PR、没有标 ready；没有碰生产、VPS 或私有仓；没有新增或修改 migration；没有运行 `ci/xcx-check.sh` 和 `ds-hardcode-gate-selftest.sh`。

## 一、staging 刷新（第 1 步）

- 合并前 staging：`07ce40be`（qx-c3 重建后的版本）。
- 合入：`origin/claude/qx-c5-20261110` 的 `07a3d5e9`（已包含 qx-c4 的 `955069da`）。合并提交 `176a8a48`。
- **唯一冲突**：`chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayHelpApplication.java`。按 c4/c5 RESULT 记载的方式解决：保留 staging 的 `frozenResolved(...)` 拆分；在 `frozenResolved` 里把原来的 `default` 判断换成 `FirstRunHelpIdentity.helpInstance(session, parseSnapshot(session.getConfigSnapshotJson()))`，返回 null 时照旧拒绝「原局身份不可用，请重新进入本局」。其余文件自动合并。`PlayHelpAuditController` 经同一个 `frozenResolved` 读同一身份。

| 门禁（在 `176a8a48`） | 结果 |
|---|---|
| `node --test chengyinhub-xcx/tests/unit/*.test.js`（仓库根目录） | **16967 个测试，16967 通过，0 失败** |
| `node chengyinhub-xcx/tests/lint/ui-integrity.js` | `UI-GATE-0:PASS`（A11Y PASS，Legacy error 0 / warn 0） |
| `build-action-ledger.js --write` / `build-route-matrix.js --json` | 5875 个控件；`git status` 无改动（无 diff） |
| `mvn -q -DskipTests package` | 通过（需 `LANG=C.UTF-8`，POSIX 语言环境下 javac 读不了中文文件名，这是容器环境问题，与代码无关） |
| c4/c5 涉及的 Java 测试：`PlayHelp*Test`（13 个类）、`FirstRunHelpIdentityTest`、`GameplayTemplateSlotRuntimeTest`、`TopicRouteRuntimeServiceImplTest` | **342 个测试，0 失败，0 错误**，BUILD SUCCESS。其中 `GameplayTemplateSlotRuntimeTest` 29、`TopicRouteRuntimeServiceImplTest` 102、`PlayHelpRunQuotaApplicationTest` 45、`PlayHelpApplicationTest` 32、`PlayHelpActivityFirstRunTest` 6、`PlayHelpSelfPlayRunTest` 9、`FirstRunHelpIdentityTest` 4、`PlayHelpAuditControllerTest` 4 |

没有往 UI-integrity baseline 加条目。`PlayHelpRunQuotaConcurrencyMySqlIT` 需要真 MySQL IT 库，不在这次范围内，没有跑。

## 二、走查 rig

- rig：`claude/cloud-walk-20261101-rig`，在独立 worktree `$HOME/rig` 里运行，叠加了 staging 的 `rig-up-sha-param.diff`、`rig-app-oss-seam.diff`（其中已含 qx-c1 的媒体缝），以及 club 分支的 `tools/harness-patches.diff`。`CHENGYIN_OSS_*` 用的是占位值（`oss-rig.invalid` / `rig-placeholder`），不是凭据。
- **after 包**：`up.sh` 以 `SHA=176a8a48` 构建 jar 和小程序浏览器包（新 staging）。
- **before 包**：同一个后端；另起一个 rig web 服务（端口 8098），小程序包用 `6c7a2961`（上一版 staging）构建。这四个页面在两个版本之间只有前端改动（`subpackageJourney/pages/{report,landing}` 和 `pages/publish/preset`、`pages/templatedetail/topic-detail` 的 wxml/json/js），读的是同一批接口，所以前后对比是同一页面、同一数据、同一状态。
- 夹具（全部真实接口，例外已标明）：
  - 自玩局：staging 的 `free_orient_theme.py` 加 c4 的「Line 3」补丁 → 主题 990014（`fixture-selfplay.txt`）。其中 `self_play=1, self_play_price=0` 一步沿用 staging 原有的 SQL 夹具。
  - 活动场次局：c5 的 `activity_orient_theme_c5.py` → 主题 990015、活动 11；再用 c5 的 `lead_start.py` 到场 + 出发（`fixture-activity.txt`）。
  - 战报：玩家 9004 在 990014 上真实打卡第 1 站、在第 2 站答「Line 3」完局（`walk-self-finish.txt`，出现「本局完成」）。
  - 落地页：玩家 9004 调真实分享接口 `POST /api/marketing/referral/token` 签发推广凭据（`referral.txt`，令牌已脱敏），由 9005 打开 `k=r` 链接。
  - 采用：发布者 9003 调真实 `POST /api/template/topic-template/save-as` 把 990014 另存为模板 990016（`save-as.txt`）。**RIG-DB-ONLY**：没有任何接口能写 `cms_topic.city_code`，所以按 qx-b4 的做法在 rig 库里把模板来源城市设为 `310100`（`rig_city_code.py`），否则 `/info` 不下发 `locationRequirement.cityCode`，弹层不会出现。
- rig 侧补丁（HARNESS，不进产品代码）：
  - `rig-nav-region-code.diff`：rig 的原生地区选择器替身补上 `detail.code`（真机会给 adcode），和 qx-b4 用过的补丁同义。只用于 before 包的「换一个城市」。
  - `portal-sim.js`：rig 运行时不实现 `<root-portal>`，`cy-dropdown` 打开的固定层会留在弹层里（被 `sh__panel` 的 `backdrop-filter` 当作包含块，又被弹层裁切），面板落到屏幕外。微信会把 root-portal 内容挂到页面根。脚本在面板打开后把这一层连同 `cy-dropdown` 自己的样式搬到页面主题根下，事件监听不变，产品样式不改。之后点「北京市」走的是组件自己的 `onPick` → 页面 `onAdoptCity`。

## 三、截图条目

截图都在 `qx-s1/shots/`，390×844，JPEG q70，每张都由我本人逐张看过。

| ID | 状态 | 改了什么 | 文件 | 怎么验证 | 真机上仍未验证 |
|---|---|---|---|---|---|
| QX-B3-REPORT（本局战报标题） | fixed（补拍） | 合同强制的可见变化：标题「本局战报」从正文头部移到导航栏下方居中（`cy-page-title`），日期「2026.11.10」从标题上方移到标题下方。其余数字、行、按钮位置不变。 | `subpackageJourney/pages/report/index.{wxml,json}`（qx-b3 `4a267460`，本次未改代码） | `QX-B3-REPORT-before.jpg`（6c7a2961）/`QX-B3-REPORT-after.jpg`（176a8a48）。数据来自真实 `GET /api/play/player-journey/report`：已通关、2/2、用时「未知」（服务端没给，如实显示）、100 解谜分、第 1 名。自检：无重叠、无截字、无空标题。 | 真机字体、安全区 |
| QX-B3-LANDING（分享落地页标题） | fixed（补拍） | 合同强制的可见变化：「朋友邀请你一起玩」从头像和昵称下方移到导航栏下方，现在位于邀请人头像之上。卡片和按钮不变。 | `subpackageJourney/pages/landing/index.{wxml,json}`（qx-b3 `4a267460`） | `QX-B3-LANDING-before.jpg`/`-after.jpg`。9005 打开 9004 真实签发的 `k=r` 链接，`POST /api/referral/landing/referral` 真实回包。自检：无重叠、无截字。 | 微信分享卡片本身 |
| QX-B4-PRESET（预设页主题） | fixed（补拍） | 合同强制的可见变化：页面根 `theme-dark` 改成 `theme-topic-editor`，整页由深色变为浅色，与其他发布页一致；三个阶段的标题合并成一个无条件 `cy-page-title`，文字不变。 | `pages/publish/preset/index.{wxml,json}`（qx-b4 `768ead08`） | 列表态 `QX-B4-PRESET-before/after.jpg`、填写态 `QX-B4-PRESET-FORM-before/after.jpg`。真实 `/api/preset/gameplay/list` 返回 8 个预设。自检：无重叠、无截字；标题和第一张卡片之间间距很小，before 也是这样，不是本次改动带来的。 | 胶囊按钮在浅底上的样子（rig 胶囊是替身） |
| QX-B4-CITY（采用换城选择器） | fixed（补拍） | 合同强制的可见变化：「换一个城市」从原生地区选择器（滚轮）改为 kit `cy-dropdown` 内联面板，候选是首页同一份 20 个城市（北京市 … 合肥市，6 位城市码）。弹层其余部分不变，选中后显示城市名和「需重新绑定 2 个地点」，前后一致。 | `pages/templatedetail/topic-detail/index.{js,wxml,json}`、`pages/templatedetail/utils/topic-adoption.js`（qx-b4 `768ead08`） | 弹层初始 `QX-B4-CITY-SHEET-before/after.jpg`；面板展开 `QX-B4-CITY-OPEN-after.jpg`（北京市…成都市）和滚到底 `QX-B4-CITY-OPEN-SCROLLED-after.jpg`（…沈阳市、济南市、合肥市），共 20 个城市，与 `adoptCities()` 一致；选中北京市 `QX-B4-CITY-PICKED-before/after.jpg`。面板展开态**只有 after**：before 是微信原生滚轮，rig 画不出来，没有用假图代替。面板向上展开，盖住弹层标题和「沿用原地点」行，这是下拉层的正常覆盖，选完即收起。 | 真机 `root-portal` 层级（rig 里靠 `portal-sim.js` 模拟）；城市只有 20 个（见待业主确认） |
| UG-05-SELF / UG-05-SELF-USE（自玩局提示阶梯） | fixed（集成后复拍） | 本次没有改代码。证明 c4+c5 与 b1 的 UI 在集成后的 staging 上能一起工作：自玩局（`activity_id=0`）的服务端回 `enabled:true`，b1 的提示弹层出现，顶部「本局剩余免费 1 次」；点「文字提示」解锁，内容「看站厅里的线路图」。 | — | `UG-05-SELF-after.jpg`、`UG-05-SELF-USE-after.jpg`；`walk-self-ladder.txt`：`GET /api/play/help?runId=1&nodeId=72` 200 `enabled:true`，`POST /api/play/help/use` 返回内容。 | 真机渲染 |
| UG-06-SELF（自玩局计数） | fixed（集成后复拍） | 用掉「首字」（由整局免费额度覆盖）后，计数变为「本局剩余免费 0 次」，「答案」显示「30 积分」。 | — | `UG-06-SELF-after.jpg`。库回读 `play_help_run_quota(990014, activity 0, firstrun:52b6…, member 9004, total 1, used 1)`。 | — |
| UG-07-SELF（自玩局分数明细） | fixed（集成后复拍） | 「分数明细」展开：本题解谜分 100、本题得分 100、第 1 层 0 积分、第 2 层免费。 | — | `UG-07-SELF-after.jpg`；`walk-self-receipt.txt`：`GET /api/play/help/receipt` 返回 `SCORED`、base 100、net 100。 | — |
| UG-05-ACT / UG-05-ACT-USE（活动场次提示阶梯） | fixed（集成后复拍） | 俱乐部开的场次（活动 11）首局同样回 `enabled:true`，弹层、免费 1 次、解锁内容与自玩局一致。 | — | `UG-05-ACT-after.jpg`、`UG-05-ACT-USE-after.jpg`；`walk-act-ladder.txt`：`GET /api/play/help?runId=2&nodeId=74` 200 `enabled:true`。站 1 打卡 `POST /api/play/arrive` 返回 `firstTime:true`（`walk-act-st1.txt`），说明首局奖励仍按 `default` 进度发放。 | 真机渲染 |
| UG-06-ACT（活动场次计数） | fixed（集成后复拍） | 同 UG-06-SELF，计数到 0、「答案」30 积分。 | — | `UG-06-ACT-after.jpg`。库回读 `play_help_run_quota(990015, activity 11, firstrun:a196…, member 9004, total 1, used 1)`。 | — |
| UG-07-ACT（活动场次分数明细） | fixed（集成后复拍） | 同 UG-07-SELF。 | — | `UG-07-ACT-after.jpg`；`walk-act-receipt.txt`。 | — |

**集成后身份回读**（`rig/readback-s1.txt`，只读 SELECT）：

- `topic_route_session`：自玩局 `(1, 9004, 990014, activity 0, 'default', firstrun:52b65a4d-…)`，活动局 `(2, 9004, 990015, activity 11, 'default', firstrun:a196a5ca-…)`。两局各自冻结了一个 `firstrun:` 身份，进度实例都还是 `default`。
- `play_help_run_quota`：两行，分别按各自的活动号和局身份记账。
- `player_node_progress`：两局都是 `default`。

**截图自检**：提示弹层是固定高度的 half 档，内容在弹层里滚动，所以 UG-05/06 底部的「跳过本题」「分数明细」只露出一半，UG-07 顶部的「答案」行被滚出视野。这和 c4/c5 的原图一致，属于弹层内部滚动，不是裁字。没有发现空标题、互相矛盾的数字或文字重叠。

## 四、rig 证据文件（`qx-s1/rig/`）

- 夹具与回读：`fixture-selfplay.txt`、`fixture-activity.txt`、`save-as.txt`、`rig-city-code.txt`、`referral.txt`、`readback-s1.txt`。
- 脚本：`issue_referral.py`、`save_as_template.py`、`rig_city_code.py`（RIG-DB-ONLY）、`readback_s1.py`、`portal-sim.js`、`play-driver.mjs`（staging 的驱动，只改了 rig 路径）、`rig-nav-region-code.diff`。
- 步骤：`st1-*.json`、`ladder-*.json`、`receipt-*.json`、`finish-self.json`、`adopt-*.json`。
- 走查日志：`walk-*.txt`（含页面全部可见文字和接口回包）。OSS 签名和推广令牌都已替换成 `<redacted>`。

## 待业主确认

1. **采用换城只有 20 个城市**（qx-c3 needs owner 3 原样延续）。默认实现：首页同一份 20 个城市，用 GB/T 2260 城市码。如果要全国城市，需要地区数据源或 kit 级地区选择器。
2. **分数明细里作者定价为 0 的层显示「0 积分」**（c4/c5 Cross-lane 已提过，属于 b1）。默认实现：照服务端事实 `free:false, pointsCharged:0` 显示「0 积分」，没改。

## Cross-lane

- 没有新增跨 lane 问题。c4 和 c5 之前在本地做的 staging 合并（`935037fa`、`d1420d4b`）没有推送；这次推送的 `176a8a48` 用的是同样的冲突解决方式，现在 staging 上已经有了。

STATUS: COMPLETE
