# 可关闭 PR 建议清单（BL-134，2026-10-16 快照，基线 `d0307a78`）

> 只写文档。**本卡不关 PR、不删分支**，由用户拍板后批量处理。
> 「diff 为空」= `git diff --name-only base...head` 为 0 个文件（只有领取/说明空提交），共 **59** 个 open PR，已逐个用 git 复核。
> 「已写入 DECISIONS-NEEDED.md」按 BL-128（#172）当前内容核对，括号内是该文件里的分节。监督结论见 `REVIEW-OPEN.md`（BL-129，#174）；合并顺序见 `MERGE-ORDER.md`（BL-127，#171）。

## 汇总

| 组 | 数量 | 建议 |
|---|---|---|
| 1 现在即可关闭 | 25 | 直接批量关 |
| 2 等用户拍板 | 12 | 拍板后关或转实现卡 |
| 3 已转交 Codex / 后端合同 | 13 | 协调者确认已登记（PR #21 / #103 `READ_CONTRACT_REQUESTS.md`）后关 |
| 4 等上游合入 | 9 | 上游合入后开新卡并关 |
| 合计（diff 为空） | 59 | |
| 5 有代码但不应合并的 PR | 2 | 见 §5 |
| 6 无 PR 的重复 / 作废分支 | 15 | 见 §6，由分支所有者或协调者删除 |

### 与 DECISIONS-NEEDED.md 的口径差异（需要统一）
- **#66 BL-30**：BL-128 放在「一、需要用户拍板」（圆角三选一），但 BL-30 已在用户 2026-10-15 晚的决定里作废，监督结论是 CLOSE。建议从拍板表中移出，直接关闭。
- **#53 BL-16**：BL-128 放在「四、等其他 PR」（等 CL4 #27），但 BL-16 同样已作废，监督写明「CL4 合并后也不要重开」。建议直接关闭。
- **#173 BL-130**：BL-128 快照时还没有这个 PR，未收录；它是无代码的 BLOCKED，可直接关闭。

## 1. 现在即可关闭（已等价 / 已完成 / 已作废，无需拍板）

| PR | 分支 | 结论 | 已写入 DECISIONS-NEEDED.md（BL-128） |
|---|---|---|---|
| #48 | `bl-12-club-sheet` | pages/club 无自绘弹层，已完成 | 是（五、已等价 / 已完成） |
| #53 | `bl-16-official-no-border` | BL-16 已被用户作废，CL4 合并后也不重开（监督结论 CLOSE） | 是（四、等其他 PR） |
| #63 | `bl-28-roam-history-tabs` | 漫游历史已无 .hs-fp 药丸，无收编目标 | 是（五、已等价 / 已完成） |
| #66 | `bl-30-roam-tokens` | BL-30 已被用户作废（监督结论 CLOSE） | 是（一、需要用户拍板） |
| #78 | `bl-37-report-sheet-shared` | 共用举报半屏已由 #73 交付 | 是（五、已等价 / 已完成） |
| #80 | `bl-39-publish-checklist` | 已在 CL4 #27 主办控制台实现 | 是（五、已等价 / 已完成） |
| #86 | `bl-45-club-badge-wall` | 成员只读勋章入口已在 CL3 #23 | 是（五、已等价 / 已完成） |
| #89 | `bl-47-state-key-validate` | 状态键校验与回显基线已有 | 是（五、已等价 / 已完成） |
| #90 | `bl-48-points-states` | 积分明细四态与重试基线已有 | 是（五、已等价 / 已完成） |
| #96 | `bl-53-roam-filter-account` | 账号隔离已在 #37 实现 | 是（五、已等价 / 已完成） |
| #102 | `bl-58-a11y-new-pages` | 本批新增 UI 无障碍无缺口 | 是（五、已等价 / 已完成） |
| #116 | `bl-73-batch3-unit-tests` | 第三批单测已随各 PR 交付 | 是（五、已等价 / 已完成） |
| #121 | `bl-78-customer-roster` | 客户名册已存在（监督结论 CLOSE） | 是（五、已等价 / 已完成） |
| #124 | `bl-81-batch4-unit-tests` | 第四批单测已随各 PR 交付 | 是（五、已等价 / 已完成） |
| #133 | `bl-90-batch5-unit-tests` | 第五批单测已随各 PR 交付 | 是（五、已等价 / 已完成） |
| #142 | `bl-99-batch7-tests` | 测试已随 #141 交付 | 是（五、已等价 / 已完成） |
| #148 | `bl-104-merchant-orders` | 工作台已有订单/异常列表 | 是（五、已等价 / 已完成） |
| #149 | `bl-105-topic-to-merchant` | 合作中心「广场」已是可承接主题列表 | 是（五、已等价 / 已完成） |
| #154 | `bl-110-recommendation` | 首页推荐位已有运营数据源 | 是（五、已等价 / 已完成） |
| #155 | `bl-111-discovery-filter` | 现有筛选与后端 schema 一致 | 是（五、已等价 / 已完成） |
| #157 | `bl-113-ledger-summary` | 台账已有服务端汇总，口径不等价 | 是（五、已等价 / 已完成） |
| #160 | `bl-116-circle-card` | 圈层记录卡已接入 | 是（五、已等价 / 已完成） |
| #168 | `bl-124-merchant-public-detail` | 现有信息行已等价（电话/官网另见 BACKEND-FINDINGS F2） | 是（一、需要用户拍板） |
| #169 | `bl-125-template-group-category` | 模板库已有等价分区（后端问题见 BACKEND-FINDINGS F1） | 是（五、已等价 / 已完成） |
| #173 | `bl-130-batch11-tests` | BL-124/125 均 BLOCKED，无代码可测 | 否 |

## 2. 等用户拍板后再关（拍板选「不做」即关；选「做」则另开实现卡后关）

| PR | 分支 | 结论 | 已写入 DECISIONS-NEEDED.md（BL-128） |
|---|---|---|---|
| #61 | `bl-25-club-state-shell` | 俱乐部「无权限」态留页内还是自动返回 | 是（一、需要用户拍板） |
| #106 | `bl-63-guidelines-ack` | 发帖是否迁 v1 社区流程 | 是（一、需要用户拍板） |
| #111 | `bl-68-club-apply-status` | 俱乐部驳回提示位置 + 需后端读接口 | 是（一、需要用户拍板） |
| #127 | `bl-84-spoiler-pref` | revealAll 偏好是否开放 | 是（一、需要用户拍板） |
| #129 | `bl-86-merchant-funnel` | 是否恢复营销页「经营事件」区块 | 是（一、需要用户拍板） |
| #131 | `bl-88-agreement-version` | 协议正文/版本以谁为准 | 是（一、需要用户拍板） |
| #146 | `bl-102-square-summary` | 推文分区是否加统计行 | 是（一、需要用户拍板） |
| #153 | `bl-109-creator-center` | 是否开放创作者申请/收益 | 是（一、需要用户拍板） |
| #156 | `bl-112-ip-world` | 是否新开 IP 世界入口 | 是（一、需要用户拍板） |
| #162 | `bl-118-template-info` | 模板货架点卡是否先进详情 | 是（一、需要用户拍板） |
| #163 | `bl-119-club-interest` | 随 #162 的决定 | 是（一、需要用户拍板） |
| #165 | `bl-121-club-open-session` | 「开一场」走 event-ops 还是 open-session | 是（一、需要用户拍板） |

## 3. 记录型：已交 Codex / 协调者，转交确认后可关

| PR | 分支 | 结论 | 已写入 DECISIONS-NEEDED.md（BL-128） |
|---|---|---|---|
| #41 | `bl-07-medal-club-group` | 缺读合同：勋章墙 clubId/clubName | 是（三、需后端补合同） |
| #79 | `bl-38-draft-coorganizers` | 缺读合同：V2 草稿（含 parties） | 是（三、需后端补合同） |
| #82 | `bl-40-party-status` | 缺读合同：协办方状态 parties | 是（三、需后端补合同） |
| #87 | `bl-46-badge-grant-log` | 缺读合同：勋章发放记录 | 是（三、需后端补合同） |
| #92 | `bl-50-official-inbox-states` | 缺读合同：party-inbox 分页 | 是（三、需后端补合同） |
| #115 | `bl-72-official-invites` | 缺读合同：邀约跟踪 topicName/商家名 | 是（三、需后端补合同） |
| #135 | `bl-92-play-casebook` | 宿主 pages/play/index.js（Codex） | 是（二、Codex 宿主类） |
| #136 | `bl-93-play-backpack` | 宿主 pages/play/index.js（Codex） | 是（二、Codex 宿主类） |
| #137 | `bl-94-theme-history` | 宿主 participation-detail / pages/play（Codex）+ 缺 createTime | 是（二、Codex 宿主类） |
| #139 | `bl-96-liked-activities` | 唯一入口 mylike.js（Codex），正文附补丁描述；最新监督 CHANGES NEEDED 待复审 | 是（二、Codex 宿主类） |
| #140 | `bl-97-merchant-drafts` | 云端草稿需接 Codex 编辑器 | 是（二、Codex 宿主类） |
| #145 | `bl-101-xp-budget` | 宿主 fabu/index.js（Codex），正文附补丁 | 是（二、Codex 宿主类） |
| #161 | `bl-117-theme-card` | 宿主 pages/play/index.js（Codex） | 是（二、Codex 宿主类） |

## 4. 等上游 PR 合入：上游合入后转为新实现卡，本 PR 关闭

| PR | 分支 | 结论 | 已写入 DECISIONS-NEEDED.md（BL-128） |
|---|---|---|---|
| #71 | `bl-31-report-mark-read` | 等 CL2 #25 | 是（四、等其他 PR） |
| #76 | `bl-35-report-official` | 等 CL4 #27 | 是（四、等其他 PR） |
| #85 | `bl-44-npc-retention` | 等 CL4 #27 | 是（四、等其他 PR） |
| #94 | `bl-52-appeal-receipt` | 等 CL2 #25 | 是（四、等其他 PR） |
| #97 | `bl-55-badge-nickname-tests` | 等 CL3 #23（测试已随 #23） | 是（四、等其他 PR） |
| #99 | `bl-56-official-organizer-tests` | 等 CL4 #27 / CL6 #35 | 是（四、等其他 PR） |
| #105 | `bl-62-my-enforcements` | 等 CL2 #25 | 是（四、等其他 PR） |
| #107 | `bl-64-notify-prefs` | 等 CL2 #25 + 小程序展示 v1 三类通知 | 是（四、等其他 PR） |
| #108 | `bl-65-official-progress` | 等 CL4 #27 | 是（四、等其他 PR） |

## 5. 有代码、但不应合并的 PR

| PR | 结论 | 已写入 DECISIONS-NEEDED.md |
|---|---|---|
| #75 BL-34 | 标题是 BLOCKED，但分支带着 #73 的全部代码（10 个文件）。监督结论 OK（「BLOCKED 记录，不要合并」）。是否另加举报入口待拍板；**无论怎么选都关闭、不合并** | 是（一、需要用户拍板） |
| #118 BL-75 | 是 #77 与 #104 的菜单合并预演，已包含二者的提交和旧版 #73。按 MERGE-ORDER §3.1 推荐方案 A：合 #77 + #104 后关闭 #118 | 否（MERGE-ORDER §3.1 有取舍说明） |

## 6. 无 PR 的重复 / 作废分支（`git diff --name-only` 文件数）

| 分支 | 文件数 | 结论 |
|---|---|---|
| `claude/bl-22-list-key-fix` | 0 | 重复认领；正式 PR #58 |
| `claude/bl-27-honest-empty` | 0 | 重复认领；正式 PR #65 |
| `claude/bl-40-party-status-list` | 0 | 重复认领；正式 PR #82 |
| `claude/bl-107-acceptance-batch58` | 0 | 重复认领；正式 PR #151 |
| `claude/bl-08-nav-member` | 0 | BL-08 已作废 |
| `claude/bl-51-report-reason-note` | 10 | **有代码**（含旧版 #73），与 #95 重复；不要合 |
| `claude/bl-08-member-nav-bar` | 23 | BL-08 已作废（改现有 UI）；不要合 |
| `claude/bl-09-club-nav-bar` | 24 | BL-09 已作废；不要合 |
| `claude/bl-10-merchant-nav-bar` | 12 | BL-10 已作废；不要合 |
| `claude/bl-11-roam-p3-nav-bar` | 11 | BL-11 已作废；不要合 |
| `claude/bl-13-merchant-sheet` | 3 | BL-13 已作废；不要合 |
| `claude/bl-14-publish-sheet` | 3 | BL-14 已作废；不要合 |
| `claude/bl-15-merchant-borderless` | 3 | BL-15 已作废；不要合 |
| `claude/bl-24-merchant-state-shell` | 12 | BL-24 已作废；不要合 |
| `claude/bl-29-club-tokens` | 1 | BL-29 已作废；不要合 |

## 复核方法

```bash
B=origin/claude/mini-ui-features-20261015
git fetch origin '+refs/heads/claude/bl-*:refs/remotes/origin/claude/bl-*'
# 某 PR 分支是否 diff 为空
git diff --name-only "$B...origin/<branch>" | wc -l
# 列出无 PR 的 claude/bl-* 分支：与 open PR 的 head 列表做差集
```
