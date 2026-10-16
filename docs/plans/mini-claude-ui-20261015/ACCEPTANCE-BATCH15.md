# 第十五批本地验收清单（BL-158–166，BL-167）

> 只写文档。第十五批都是修本线新代码的文案、门禁登记、判定或计数，不改现有 UI 与标题。每个分支都从被修的 PR 切出，合入前 diff 会带上被修 PR 的提交，只需审最后一两个提交。
> 快照 2026-10-16 03:20Z，PR 状态以 GitHub 为准。期望值照抄各 PR 正文（#214 为本线实测），本卡未复跑。
> 统一前置：`cd chengyinhub-xcx`。

## 一、逐卡验收命令与前后对照

| 卡 | PR | 须在其后合并 | 命令 | 改前（被修 PR 分支上） | 改后（期望） |
|---|---|---|---|---|---|
| BL-158 触达任务页禁用词 | #205 | #144 BL-100 | `node --test tests/unit/audit-f01-f17-contract.test.js tests/unit/merchant-crm-campaigns.test.js tests/unit/r9-32-crm-write-ack-contract.test.js` | F13 失败，列出 5 处「回执」 | pass 16 / fail 0 |
| | | | `node --test tests/unit/merchant-crm-*.test.js` | — | pass 22 / fail 0 |
| BL-159 安全感受摘要路径 | #206 | #109 BL-66 | `node scripts/ui-validity-gates.js` | `U1 FAIL: subpackageMember/components/cy/profile/index.js:720 … 没有后端 @*Mapping` | `U1: 通过（540 个前端字面量 /api 调用，1340 个后端映射）`；同一命令仍报 `U5 FAIL …profile/index.js（白名单 1，实际 2）`，U5 需 #216（BL-168） |
| | | | `node --test tests/unit/merchant-safety-summary-bl66.test.js` | — | pass 4 / fail 0 |
| BL-160 U5 白名单同步 | #207 | 各被补 PR（#122 #123 #128 #141 #114 #73） | 在各分支 `git apply` `BL-160-U5-PATCHES.md` 对应一节后：`node scripts/ui-validity-gates.js \| grep ^U5`；根目录 `node ci/silent-error-gate.js` | 6 个分支 U5 均 FAIL（见 #207 表） | U5 通过；silent-error-gate 未登记/超额 0，剩基线 2 条（`pages/publish/fabu/index.js`、`subpackageMember/complaint/index.js`，基线落后 master 的存量问题，exit 1 不变） |
| BL-161（1/2）收藏点位页副文案 | #208 | #84 BL-42 | `node tests/lint/ui-integrity.js --warn-as-error` | `node-favorites/index.js:12/:42` 两条 WARN/LEGACY | `UI-GATE-0: PASS` |
| | | | `node --test tests/unit/roam-node-favorites.test.js` | — | pass 3 / fail 0 |
| BL-161（2/2）官方收件箱提示 | #209 | #43 BL-06 | `node tests/lint/ui-integrity.js --warn-as-error` | `official-inbox/index.js:202 提示文案 21 字` WARN | 该条消失；仍剩 2 条 `official-inbox/index.js:103 → :109` U1-VARIABLE 基线键漂移（#43 自带，#194 X-8，归协调者） |
| | | | `node --test tests/unit/official-inbox-contract.test.js tests/unit/official-inbox-invite-card.test.js` | — | pass 5 / fail 0 |
| BL-162（1/2）清除角色声音 | #210 | #88 BL-43 | `node --test tests/unit/npc-voice-reset-bl43.test.js tests/unit/success-toast-ratchet.test.js tests/unit/merchant-npc-voice-size-contract.test.js` | #88 合入后棘轮会超上限 101 | pass 20 / fail 0，上限仍 101 |
| BL-162（2/2）收藏据点 | #211 | #112 BL-69 | `node --test tests/unit/merchant-node-favorite-bl69.test.js tests/unit/success-toast-ratchet.test.js` | #112 合入后棘轮会超上限 | pass 6 + 5 / fail 0，上限仍 101 |
| BL-163 勋章颁发回执 | #212 | #23 CL3、#192 BL-148 | `node --test tests/unit/club-badge-utils.test.js`（PR 正文跑的是 `grep -rln "club-badge\|club/badges" tests/unit` 全部） | #192 上 todo 1 | 相关全部 pass 48 / fail 0 / **todo 0** |
| BL-164 私信解除拉黑回读 | #213 | #104 BL-61、#201 BL-154 | `node --test tests/unit/im-unblock-confirm-handler.test.js`（PR 正文跑的是引用 `im/chat/index` 与 `im-unblock` 的全部单测） | #201 上 todo 3 | 相关全部 pass 226 / fail 0 / **todo 0** |
| BL-165 项目操作面板计数 | #214 | #164 BL-120 | `node --test tests/unit/merchant-card-project-actions-contract.test.js tests/unit/myproject-save-as-template.test.js` | 计数断言 fail 1（expected 5, actual 6） | pass 8 / fail 0 |
| BL-166 安全感受半屏 U5 理由 | #218（只文档，补丁交协调者） | #132 BL-89 | 在 #132 分支 `git apply` `BL-166-U5-SAFETY-SHEET.md` 补丁后：`node tests/lint/ui-integrity.js` | `UI-GATE-0: FAIL (1 violations)`，`[U5] …safety-rating-sheet/index.js:115` 未登记 | `U5 PASS: 需逐项理由 29 … 逐项理由 29`，`UI-GATE-0: PASS`；棘轮 28→29 正好顶到冻结上限 29（与 #120 争同一名额，见 BL-170） |

**一次跑单测**（各依赖 PR 与本批 PR 都合入后）：

```sh
node --test tests/unit/audit-f01-f17-contract.test.js tests/unit/merchant-crm-campaigns.test.js \
  tests/unit/r9-32-crm-write-ack-contract.test.js tests/unit/merchant-safety-summary-bl66.test.js \
  tests/unit/roam-node-favorites.test.js tests/unit/official-inbox-contract.test.js \
  tests/unit/official-inbox-invite-card.test.js tests/unit/npc-voice-reset-bl43.test.js \
  tests/unit/success-toast-ratchet.test.js tests/unit/merchant-npc-voice-size-contract.test.js \
  tests/unit/merchant-node-favorite-bl69.test.js tests/unit/club-badge-utils.test.js \
  tests/unit/im-unblock-confirm-handler.test.js \
  tests/unit/merchant-card-project-actions-contract.test.js tests/unit/myproject-save-as-template.test.js
node scripts/ui-validity-gates.js
node tests/lint/ui-integrity.js --warn-as-error
```

期望：单测 fail 0 / todo 0；`ui-validity-gates` U1、U5 通过（U5 还要 #207 各节补丁与 BL-166 登记到位）；`ui-integrity` 只剩协调者处理的基线项（如 #209 提到的 `official-inbox` U1-VARIABLE 漂移、BL-166 的 U5 理由）。再跑一次全量 `node --test "tests/unit/**/*.test.js"` 确认没有新增失败。

## 二、合并顺序要点

- 每个第十五批 PR 都必须排在它修的 PR 之后：#205←#144，#206←#109，#208←#84，#209←#43，#210←#88，#211←#112，#212←#23+#192，#213←#104+#201，#214←#164。
- #212 合入后 #192 的 todo 被转正，#204（BL-157）§一里 #192 的期望应改读为 todo 0；#213 合入后 #201 的期望同理改为 todo 0。
- #211 与 #109、#68 都改 `subpackageMember/components/cy/profile/index.js`，有合并冲突（见 `CONFLICTS.md`）；#206 也改该文件 `:720`。
- #207 不改任何代码分支，补丁需各 PR 或协调者 `git apply`；`ui-silent-error-allowlist.js` 末尾的多处追加在合并时都要保留，计数按最终树累计。
- #209 说明：#194 X-11 里 `im/chat` 18 字提示（叠加树 `:645`）不是 #130 新加的，应来自 #77 / #104，需监督线在叠加树上复核后另开 follow-up。

## 三、需要设备走查的项

| PR | 入口 | 看什么 |
|---|---|---|
| #205 | 商家工作台 → 触达记录 | 读屏标签「查看逐人结果」；半屏标题兜底「触达明细」；失败「触达明细没有加载出来」；无逐人数据「这次触达没有逐人结果」 |
| #206 | 商家公开主页 | 安全感受摘要照常请求并展示（与 #109 一致） |
| #208 | 漫游 → 我收藏的点位（断网） | 副文案「网络不太稳，点重试再拉一次」，重试可重新加载 |
| #209 | 官方收件箱接受/拒绝邀请，回读不一致 | 提示「已提交，状态未同步，稍后下拉刷新」完整显示 |
| #210 | 商家装修 → AI 角色 → 清除声音 | 声音区回到未录音，普通提示（无成功图标）；回读非 0 不提示成功 |
| #211 | 他人商家主页 → 收藏据点 | 按钮切换为「已收藏据点」，普通提示；再点恢复 |
| #212 | 俱乐部勋章颁发 | 成功文案、重复颁发文案、断网「结果未确认」 |
| #213 | 私信拉黑后解除 | 回读后才「已解除拉黑」；回读仍拉黑「解除还没生效，请重试」；断网「网络中断，解除结果未确认」 |
| #218（补丁应用后） | 安全感受半屏，商家 id 解析失败 | 显示「评价没有提交成功」与原因，可再次提交 |
| #207、#214 | — | 只跑命令 |

## 四、未验证

- 期望值除 #214 外照抄各 PR 正文，本卡未在叠加树复跑；没跑 CI、全量单测、`ci/xcx-check.sh`。
- BL-166 行按 #218（03:14Z 开出）正文补入。
