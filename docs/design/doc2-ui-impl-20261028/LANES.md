# 文档二 UI 实施 · 各车道共用规则（2026-10-28）

Read AGENTS.md and chengyinhub-xcx/AGENTS.md first and follow them: Chinese commit messages with a feat:/fix: prefix; owner/scope checks on every write API; new schema only as new sql/migration_*.sql; never edit or delete existing migrations; no secrets; no mock/test domains; no error suppression; never run ci/xcx-check.sh.

## RULE 0 — OWNER, 2026-10-28 09:00Z (overrides everything below)
"Do not alter the pages we have already created. We are only looking for minor adjustments, small additions, and slight optimizations—not a complete redesign. Please build upon our existing work."
- Start from the existing wxml/wxss/js of each page and component on the base branch. Change only the elements the prototype marks as 新增/改动, and make each change the smallest diff that reaches the prototype's result.
- Do not rewrite, re-layout, restyle or rename existing pages, components, classes, tokens or files. Do not create parallel "v2" components that replace working ones. Do not swap existing controls for new ones unless the prototype marks that exact element as 改动.
- Reuse existing components (cy-sheet, cy-switch, node-card, node-pill, cy-btn, cy-modal, existing list/row styles). Add a new component only for a genuinely new element with no existing counterpart, and keep it small.
- Where a lane prompt says "replace" (e.g. topic-state-keys, story-rule-editor), read it as "edit in place". Keep the component, its file, its data flow and save path. Change only what shows raw keys/English or what the prototype marks, inside the same component.
- Keep the diff small and reviewable. In the PR body, list every existing file you touched with a one-line reason.
- If a prototype detail would need a large rewrite, implement the nearest small change and list the gap in the PR body instead.

## Goal
The owner approved interactive HTML prototypes. Implement them so the running mini program matches the HTML exactly — layout, copy, states, motion — excluding the review marks and side panels. Read index.html AND README.md of each prototype, including the 联动 panel and every '需后端' note:
- A editor + play: branch docs/doc2-proto-v4-a-20261027 @ b4a751ae, docs/design/doc2-proto-v4-20261027/a-editor-play/ (+ shots/ PNGs)
- B team / event / safety: docs/doc2-proto-v4-b-20261027 @ 851f1fd3, docs/design/doc2-proto-v4-20261027/b-team-event/
- C me / settings / invoice: docs/doc2-proto-v4-c-20261027 @ a2835f24, docs/design/doc2-proto-v4-20261027/c-me-settings/

## Owner rules (all final)
- No English text or internal keys in the UI; 'd20' is the only Latin token. Keys are system-generated and never shown or typed.
- No secondary labels, helper captions or explanatory footers; the layout explains. Keep only one-sentence errors, empty states and confirm dialogs.
- Pick, don't configure. iOS sheet stacking (parent recedes), nav bar ‹ parent / title / 完成; at most ONE extra level from any sheet; prefer inline editing.
- Apple-grade copy, one term per concept. Never reword strings that mirror backend messages.
- Pages not in the prototypes stay exactly as on the base branch.
- Mall/store features (F3/F4) stay paused. Minor mode stays off. Checkout never needs guardian authorization. No 参与详情 entries for character card/score.

## Branching
- BASE: integrate/doc2-ui-impl-20261028 (= PR #625 head aad4bc9b, W8 closeout, not yet on master).
- Create your lane branch from it and open a DRAFT PR into integrate/doc2-ui-impl-20261028 titled '[doc2-ui Lx] …'.
- Push often; never force-push.

## Lanes
| Lane | Scope |
|---|---|
| L1 editor-d20 | topic-detail sheet, d20 与角色设定, 可玩时段 bottom sheet, 开局前准备 list, 发布确认 |
| L2 editor-keys-state | 线索与物品 (replaces topic-state-keys), 初始状态, 编辑数值 |
| L3 editor-node-route | 节点编辑, 到达与引导, 下一站 条件与效果 (replaces W8 story-rule-editor) |
| L4 gameplay-template | 编辑玩法模板 · 检定, 消耗与效果 |
| L5 play-page | everything on the play page from A and B |
| L6 team-event | the rest of B |
| L7 me-invoice-appeal | C |
| L8 backend-contracts | all '需后端' backend work; contract file docs/design/doc2-ui-impl-20261028/api-contracts.md on branch feature/cy27-l8-backend |

- Do not edit other lanes' files. If you must touch a shared file, keep the hunk minimal and list it in the PR body under '共享文件'.
- Do not edit shared registry files (app.json, ledgers, ds/UI gate baselines, route/screenshot matrices). Put the needed entries in the PR body under 'CY-HANDOFF' for the integrator.

## Backend contract
Front-end lanes code against L8's contract file (`git fetch origin feature/cy27-l8-backend`). If it isn't there yet, code against the prototype README notes and say so in the PR.

## Environment
The owner's dev DB and backends are on a VPS you cannot reach. Rely on unit tests, the repo's real-storage IT that runs in GitHub CI, and CI on your PR.

## Verify
- Run the repo's mini-program unit tests and the relevant Maven module tests.
- Render changed wxml with the repo's existing headless/wcc harness if available, and compare with the prototype shots.
- Make CI on your PR green.

## Final report (short)
PR number, head sha, what's done, what's stubbed and why, CY-HANDOFF list, CI status.
