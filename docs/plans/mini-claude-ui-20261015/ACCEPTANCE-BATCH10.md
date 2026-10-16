# 本地验收清单汇总·第十批（BL-116–BL-122，2026-10-15）

> BL-123：汇总合入 `claude/mini-ui-features-20261015` 的第十批未合并 PR 的「本地验收清单」，原文照抄、不增删；验证阶段由用户统一执行。BLOCKED 项单列。生成方式：读取各 PR 正文（#160–#166）。
> 第九批（BL-108–114）清单在 BL-115（#159）`ACCEPTANCE-BATCH9.md`；本文件续接 BL-59（#101）`ACCEPTANCE.md`、BL-83（#126）`ACCEPTANCE-BATCH3-4.md`、BL-107（#151）`ACCEPTANCE-BATCH5-8.md`、BL-115（#159）。前述文件均未合并，为免文本冲突单独成文；合并后可原样追加到 `ACCEPTANCE.md` 末尾。

> **依赖与冲突提示**：#166（BL-122）叠在 #164（BL-120）之上，须在 #164 之后合并。#164 改了「我的项目」wxml/js，协调者必须重建 `action-ledger.json`（4480→4481 控件，新增 save-as 接口），并把 `ui-integrity-baseline.js` 的键 `subpackageA/pages/myproject/index.js:408` 改为 `:452`（详见 #164 评论）；silentError 已在 #164 内登记。

## 一、可验收的功能/测试 PR

### #164 BL-120 我的项目主题长按面板加「另存为模板」

分支：`claude/bl-120-save-as-template`

- [ ] 我的项目 → 主题 tab，长按「进行中 / 已下架 / 已完成」的主题：面板出现「另存为模板」；草稿、审核中、未通过的主题不出现。
- [ ] 点「另存为模板」会弹确认；点取消不发请求。
- [ ] 确认后提示「已另存为主题模板」，列表刷新；模板货架主题 tab 能看到该主题。
- [ ] 商家 scope（`?scope=MERCHANT`）下，店主/有权限岗位可以操作；无权限时如实显示后端报错。
- [ ] 后端拒绝时（如非审核通过），toast 显示后端原文。

### #166 BL-122 第十批单测与接口合同测试

分支：`claude/bl-122-batch10-tests`

- [ ] `node --test tests/unit/batch10-api-contract.test.js` 通过。

## 二、已实现 / BLOCKED 项（无代码改动）

| PR | BL | 原因 |
|---|---|---|
| #160 | BL-116 圈层探索记录卡 | 已实现：`pages/play/circle/index.js` 已读 `GET /api/circle-theme/session/{sessionId}/card` 并渲染记录卡。验收（PR 正文原话）：确认 `pages/play/circle` 结束时显示记录卡（现有行为） |
| #161 | BL-117 主题游玩记录卡 | 唯一宿主 `pages/play/index.js` 属 Codex；与 BL-94 同因无宿主 |
| #162 | BL-118 主题模板详情 | 货架主题卡点击直达发布器（2026-08-30 裁决），插详情会改现有行为，需产品决定 |
| #163 | BL-119 俱乐部承接意向 | 依赖 BL-118 |
| #165 | BL-121 俱乐部按合作主题开一场 | 现有「开一场」已走 event-ops 排期（Codex `ApiClubEventOpsController`）；再接 open-session 会出现两条开场链路，需产品决定 |
