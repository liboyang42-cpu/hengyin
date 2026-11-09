# qx-a5 creator flow (small items): result

Base: `origin/master` 2bb811437. Branch: `claude/qx-a5-20261109`.

All screenshots come from the walkthrough rig (`claude/cloud-walk-20261101-rig` with `harness-patches.diff` from `claude/cloud-walk-20261101-club`). The backend is the master jar against a fresh `cy_e2e_51` database, and the browser bundle is built from the real `chengyinhub-xcx`. "before" shots are from the master bundle and "after" shots are from this branch's bundle, in the same page and state.

Rig-only data added to the rig database (not product code):
- Two topic templates, `城市藏宝猎人` and `上海像一部旧电影` (copies of fixture topic 990013 with `is_template=1`).
- Three public gameplay templates (7101–7103). 7103 carries a valid `timer` advanced config.
- Two of player 9004's own templates (6101/6102).
- Player 9008's role set to `club` for the activity publish page.
- In the fabu shots, the long address comes from overriding `wx.chooseLocation` (the rig's default address is short), and the paid price is set through the page's own `setData` + `syncIdentityReady`.

| ID | Status | What changed | Files | Verified | Not verified on a real phone |
|---|---|---|---|---|---|
| V3-29 | fixed | Topic ticket page: the 发布者实名 fields now sit in the same white card as 自玩票, so the left edge lines up with the section title and other cards. Activity page: 发布者实名 is styled as a section title (bold, section size, one step of space above), not a field label. | `pages/publish/fabu/step3.wxml`, `fabu/index.wxss`, `pages/publish/activity/index.wxml/.wxss` | `V3-29-before/after`, `V3-29-activity-before/after`; `publisher-identity-contract` test green | Native `<icon>` in `cy-consent-check` does not render in the rig, so the tick box is missing in both shots |
| V3-31 | already fixed | No code change needed. cc73a167 (2026-11-07, after the walkthrough) makes pure adoption skip the capability gate; editing an adopted config keeps the real check through the inherited-config hash. Proven in the rig: player 9004 (`canGameMechanic=false`, `canBranch=false`) adopted shelf template 7103 (timer enabled). The API returned `COMPLETED`, draft 6104 (`evidence/V3-31-rig-adopt-timer-template.json`). Through the UI, selecting 街角谜题 → 确定 adopted and bound it (`V3-31-after.jpg`). No 「还没解锁」 page. | — | `mvn -pl chengyinhub-admin -Dtest=TemplateAdoptSecretBackfillTest,TemplateAdoptionServiceTest` 27/27 pass; rig API and UI | — |
| V3-43 | fixed | Template shelf (player): the top bar is transparent while at the top, so the cover glow runs under the status and nav bars. It turns solid page colour once scrolled (`topbarSolid`, set only when crossing 4px). The merchant light theme is unchanged. | `pages/template/index.{js,wxml,wxss}` | `V3-43-before/after`, `V3-43-after-scrolled` | Real status-bar overlap on notch devices |
| V3-44 | fixed | 快速配置 removed. 自由探索 / 城市定向 now go straight to the pro editor; the second-level menu (快速配置 / 专业手动 / 返回) is gone. `pages/publish/simple` stays registered and routable, with an orphan exemption in `nav-route-exists`. | `components/cy/publish-sheet/index.{js,wxml}`, tests | `V3-44-before` (second level) / `V3-44-after` (tap 城市定向 → editor); `publish-quick-menu` tests | — |
| V3-45 | fixed | Removed the hairline under the back button (full-screen sheet `border-top`). 主题详情 and 票务 now use the same 「* 还有 N 项未填」 style (`pd-missing`: caption, danger colour, same spacing). There is no arrow after the 票务 missing text. | `pages/publish/fabu/index.{wxml,wxss}` | `V3-45-before/after` | — |
| V3-46 | fixed | 新建故事玩法 list removed from the picker. Its now-unused `storyGameOptions` / `storySupportReasons` / `supportNode*` setData were removed (UI gate U4), and the support-matrix tests now read `_nodeSupportContext()`. | `fabu/index.{wxml,js}`, `tests/unit/gameplay-support-matrix-ui.test.js` | `V3-46-before/after`; `gameplay-support-matrix-ui` green | — |
| V3-47 | fixed | 自己建一个 is the first card of 我的模板: same card size and radius, with the 3D 互动 image from the publish menu (no new asset). The rail always shows. The bottom button is now 确定 (`confirmTemp`). Drafts stay hidden (67e48cd87). | `fabu/index.{wxml,wxss}` | `V3-47-before/after`; tapping the card opens `pages/publish/temp` (`V3-47-after-create-tap`); 确定 adopts and binds (`V3-31-after`) | — |
| V3-48 | fixed | 地点 row: the address is one line with an ellipsis and 「地点」 stays horizontal. This uses a new opt-in `clip` prop on the publish `cy-list-row`, so other rows are unchanged. | `pages/publish/components/list-row/*`, `fabu/index.wxml` | `V3-48-before` (「地 点」 stacked, address past the arrow) / `V3-48-after` | — |
| V3-50 | fixed | Selected template card: the outer 4rpx ring was clipped by the horizontal rail (left edge missing). It is now an inset ring in `--cy-color-selection` plus the kit's selected mark (`.mode-opt__mark.is-on`) in the top-right corner. | `fabu/index.{wxml,wxss}` | `V3-50-before/after` | — |

## needs owner
- V3-47: the picker header still has 返回 / 完成, and the new bottom 确定 does the same as 完成. Default implemented: the header is kept unchanged (baseline rule). Should the header 完成 go?

## Gates
- `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`. 11 `fabu/index.js` U4-DYNAMIC anchors were moved by line number only: a script mapped them with `git diff` and checked each line's text is identical. The digest was recomputed, and no entries were added.
- The action ledger was rebuilt: 5751 → 5747 controls, with the counts in `action-ledger.test.js` updated and explained.
- Unit tests: the full `node --test tests/unit/*.test.js` run is 16766/16766 green. Targeted suites: publish-quick-menu, nav-route-exists, overlay-close-accessibility, action-ledger, gameplay-support-matrix-ui, r29-v3-41-template-picker, publisher-identity-contract.
- Shared files: `scripts/uiaudit/action-ledger.json` was rebuilt (required by these items). `app.js` and `app.json` were not touched. No new assets; nothing was added to the main package.

## Cross-lane
- The node sheet's 玩法 switch is a white knob on grey when off and green when on. That is lane a2 (V3-20) and was left alone.
- The activity page's 「发布前摘要」 box looks oddly indented in the shots. It is outside a5 scope.

STATUS: COMPLETE
