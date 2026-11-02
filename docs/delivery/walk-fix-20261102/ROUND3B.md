# Round 3 addendum: club lane, owner decisions, 2026-11-02

BRIEF.md and ROUND3.md still bind. Branch `claude/walk-fix-1102-l6-club`, draft PR #716. Start only when the branch's newest commit is over 10 minutes old and `l6-club/RESULT.md` ends with `STATUS:` for round 3; re-check until then. Never merge a PR. Finish without waiting for answers.

The owner's decision: the recruiting sheet and the other club pages follow the Figma style; fill in whatever is missing. Cloud sessions cannot open the Figma file, so this file describes the frames. Reuse the dark `cy-scene-sheet` and existing components. Do not redraw other pages.

## Recruiting sheet (title 承接商家): replaces the plain 商家 list on `pages/club/topic-detail`

Choose the layout by theme type; both are half-height or taller dark sheets with a close button.

**Station-based theme**
- Progress line 「已招 4/6 站 · 2 家待确认」 with a progress bar.
- Stations grouped under a chapter heading, with 「3 站 · 已招 2」 on the right.
- Filled row: number circle, station name, gameplay name; on the right, merchant name and status (已中标 in green, 待审核 in amber).
- Unfilled row: dashed outline, number circle, 「未命名站点」 or the station's name, 「玩法待配置」, white pill button 去招商.
- Full-width outline button at the bottom: 查看候选池.

**Free exploration: recruit by chapter**
- One card per chapter: chapter name, status tag 招募中 (green) or 未开放 (grey).
- Category and capacity line, for example 「咖啡 / 轻食 · 需可容纳 6 人」.
- Term chips, for example 场地免费, 平台预设条款, 工作日 19:00 后.
- 「已通过 2 / 上限 3 家」 and 「权益门槛 ≥ ¥20 体验权益」.
- Bottom row: 设置招募条件 on the left, white pill 查看申请 N on the right.
- An unopened chapter shows only name, category line and an outline pill 开放招募.

**Rules**
- Keep merchant contact details and the copy-phone action from the current list, shown after the merchant accepts.
- Reuse the existing recruiting data and actions in `pages/topic/components/project-host`, `pages/topic/merchantinfo` and `pages/publish/fabu`. Every write checks club and theme ownership. The approved merchant-number pop-up rule on 本章开放商家承接 still applies: switch stays off without an approved number.
- Figma's explanatory sentences at the top and bottom of these sheets are left out under the owner's no-helper-captions rule. Keep data lines. List each one dropped in RESULT.md.
- If an action has no backend, build it if it is a small owner-checked write; otherwise show nothing for it and list it under `needs owner`. No dead buttons.

## Phone numbers (changes ROUND3 C-05)

The organiser and whoever runs the session must be able to call a player during the game. Show the full number and a call action to the 主理人, 副主理人 and the session's executing party. Other roles stay masked. Figma R1 has a row 「可见客户手机号」 under 分配固定角色, default 否, for 活动核销员: add it to `pages/club/roles`, with a server-enforced permission. Enforce on the server, not only in the view; log nothing with full numbers. This changes a permission rule, so state each changed check in RESULT.md for the owner to review.

## Other club frames: fill in what is missing

Compare and fill to match, keeping real data:
- **R1 角色与权限:** 当前委派 rows with scope line and 撤销; 分配固定角色 with 选择成员, 角色, 可见客户手机号 and white 分配 button.
- **R4 群发通知:** 选择受众 with counts, 内容 with 标题 and 内容, 投递状态 with 总人数, 已送达站内, 失败 and 重试, white 发送 button.
- **主题设置:** theme name with 已开卖 at the top, 编辑主题内容 row, 开放商家对接 group with per-chapter rows on the right (咖啡 / 轻食 · 3 家 or 未开放), and 在俱乐部页的展示 group. Keep 结束主题 and 承接审核 where they are now.

**Do not restore R2 会费设置.** The owner deleted that page on 2026-09-09.
