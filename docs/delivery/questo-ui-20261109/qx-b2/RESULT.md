# qx-b2 · New gameplay types (player and author): result

Branch `claude/qx-b2-20261109` from `origin/integrate/questo-ui-20261110` (449fb80d). Spec: `docs/questo-teardown/UI优化施工清单.md`
§十 UG-01..04, UG-10, UG-11, §23.1, §27.9, §十二, §十三.

## Items

| ID | Status | What changed | Files | How verified | Still unverified on a real phone |
|---|---|---|---|---|---|
| UG-01 player | fixed | New `cy-playkit-hangman` screen: slot row (prefilled / blank / separator, horizontal scroll, no wrapping), tap a blank to edit only that cell, whole-word box, one 提交. Submits `SUBMIT_HANGMAN` with `{slots}` (all editable cells filled) or `{input}`; nothing judged or stored locally; server `FILLED` chars restore on re-entry; errors keep the input. Solved shows the server `display`. | `pages/play/components/playkit-hangman/*`, `pages/play/utils/playkit-questo.js`, `pages/play/utils/playkit-view.js`, dispatcher `pages/play/components/playkit/*` | `tests/unit/questo-b2-player.test.js`; rig shots `UG-01-*` | Keyboard focus/IME on device; a live session from a published theme (not built in the rig) |
| UG-01 author | fixed | 逐字猜词 editor: answer, cell grid (tap = prefill / blank; separators locked), aliases, 区分大小写. Shape errors live as red text; 「请填写答案」 only after a save attempt. | `pages/publish/temp/questo-author.js`, `pages/publish/temp/index.{js,wxml,wxss}` | unit tests; real save → read-back (`evidence/author-save-readback.md`, draft 6005) | — |
| UG-02 | fixed | Player: option image + text in the existing `qa__opt` row, fixed 4:3 thumbnail, text stays the label, alt goes into `aria-label`; only `https://` or site paths render — `media-asset:`, `http:`, `data:` show text only; a load error drops the image. Author: 选项图 slot + 图片替代文字 per option, pairing errors in red under the field. | `pages/play/components/playkit-qa/*`, `pages/play/utils/playkit-questo.js`, temp editor | unit tests; read-back draft 6006; shots `UG-02-*` | Image loading from OSS on device |
| UG-03 | fixed | Player: `cy-playkit-accusation` (corkboard, intro text, suspect cards with avatar / name / identity; tap = select + show only the testimony the server returned; 再想想 clears; 确认指认 opens the shared modal 「这是最终指认：…」 and only then submits the original `SUBMIT_QA {optionId}`). Author: 指认结案 switch on single-choice QA; suspects follow the options (same id, name = option label; no 正确人物 field), avatar, identity, testimony `{key,text}`, 线索板, both endings, 「待运行验证」 tag. | `pages/play/components/playkit-accusation/*`, `playkit-view.js`, temp editor | unit tests; read-back draft 6006; shots `UG-03-*` | Server-side refusal messages (`REFUSED`) in a live session |
| UG-04 / §27.9 | fixed | Outcome label only from `accusation.result`: CORRECT → 正常结案, CORRECT+early → 提前结案, FAILED → 指认失败; WRONG keeps investigating with the server feedback; qa finished without a result → 结算确认中. `readyForBase` is never read; `recordAs` never shown to players; the generic 「本次玩法未通过」 is not stacked on the authored lose screen. Author: 允许提前指认 switch + free-text 记录名 (no dropdown, no internal codes). | same | unit tests; shots `UG-04-*` | Live early-accusation progress gate |
| UG-10 | fixed | Player: `MIRROR` flips only the question text layer (`scaleX(-1)`), never options/input/buttons; `REVERSE` renders the server string as is; unknown/STANDARD = original. Mode is read from the node's public projection. Author: 谜面呈现 原样/镜像/倒序 under the question (原样 writes no key); editor always shows the original text; 试玩 follows the player rule. | `playkit-qa`, `pages/play/index.js` (one line), `playkit-questo.js`, temp editor, `advanced-game-preview.js` | unit tests; read-back draft 6006; shots `UG-10-*` | — |
| UG-11 | fixed | New 提示 group below 结果反馈: 本题启用提示 switch, one 整局免费次数 field (`hintFreeUsesPerRun`), up to 6 layers each with 类型 (文字/图片/首字/数字/答案/跳过 — no location), content for that type, 消耗积分. No per-layer free count, no gems/coins. Old 一级/二级/备用答案 block moved to the same place and hidden while the ladder is on. Field names never shown. | temp editor, `questo-author.js` | unit tests; real save → read-back (draft 6005); shots `UG-11-*` | — |
| Catalog / matrix | fixed | 逐字猜词 added to the node game catalog (问答) and to the picker; selecting it creates and enables the section; 试玩 builder for hangman and accusation. The support matrix is fetched from the server, which already lists `hangman`; contract test now registers `hintLadder` as a root tool section (was a pre-existing red test). | `node-game-catalog.js`, `advanced-game-preview.js`, `tests/unit/advanced-game-config-slowtask.test.js` | unit tests; shot `CAT-*` | — |
| §23.1 answer aliases | fixed | 其他可接受写法 under the text-QA answer → private `answerPolicy{version:1,normalizationPolicy:'LEGACY_V1',acceptedAliases}`; hangman aliases in its own section. Empty lists write no key. | temp editor, `questo-author.js` | unit tests; shot `A23-alias-after` | — |
| §23.1 placeCountryCode | needs owner | Not built. The backend has no author write path for `contentContract.placeCountryCode` (only the location-binding service writes `content_contract_json`). Default implemented: no field shown. | — | read `TopicLocationContract` / `CmsTopicMapper.updateTopicLocationContract` | — |
| §23.1 arrivalPolicy / arrivalReading | needs owner | Not built. They live in `scenes[].nodeConfigs[nodeId]`, but that same entry is treated as the node's full template override (client `theme-ownership.effectiveTemplate`, server `GameplayTemplateRules`); writing an arrival-only block would replace the node's gameplay. Needs a contract for where an arrival-only block may live. Default implemented: no field shown. | — | read `TopicRoutePublicationCompiler.arrivalAuthorConfig`, `theme-ownership.js` | — |

## Structural templates for new screens

- Hangman player screen: `playkit-sort` (stage, row cards, feedback bar, single bottom button) + `playkit-qa` input underline.
- Accusation player screen: `playkit-sort` row card + selected outline, `playkit-qa` feedback bar; confirm uses `utils/modal.js`.
- Author sections: existing temp-editor rows (`cg-cfg-label`, `cg-adv-repeat`, `cg-qmedia`, `cy-switch`, `cy-dropdown`).

## Evidence

`shots/` (390 px, JPEG q70). Before = base bundle, after = this branch, same page and same input.

- Author editor (real page, real backend, creator `e2e-openid-9002`): `CAT`, `UG-02-author`, `UG-10-author`, `UG-11` before/after; after-only `UG-01-author` (empty / normal / error), `UG-03-author`, `UG-04-author`, `UG-11-layers`, `A23-alias`.
- Player screens: the real `pages/play/index` with the real `cy-playkit` components; the kit is produced by each tree's own `pickPlayKit` from the same server-shaped session view (field names from `AdvancedGameRuntimeServiceImpl` / `HangmanGameplay.publicProjection` / `accusationView`). Before = base `pickPlayKit` on the same view (hangman → no screen; accusation → plain pick; outcome → 「答对了」).
  States: hangman normal, typing, restored, solved; image options; mirror and reverse; accusation normal, board+intro, selected+dossier, confirm modal, wrong attempt, 结算确认中, 正常结案, 提前结案, 指认失败.
- Rig limitation (HARNESS): no published theme with these nodes was built, so no live `/api/play/advanced/start` session drives the player screens; the unknown-result state stays the existing advanced-game 「再确认一次」 panel (unchanged). `<icon>` radio glyphs in the editor do not render in the rig.
- `evidence/author-save-readback.md`: two drafts saved and read back through the real backend validators.

## Checks

- `node --test tests/unit/questo-b2-player.test.js tests/unit/questo-b2-author.test.js` → 23 pass.
- Updated contract tests: `playkit-view-contract` (hangman screen, accusation producible), `playkit-photocheck-frame-contract` (copies the new helper), `advanced-game-config-slowtask` (hintLadder root section), `screenshot-fixture-completeness` via `scripts/shot-matrix.js` F103/F104.
- Full unit suite: see PR body (compared against a clean base worktree).
- `node tests/lint/ui-integrity.js` → `UI-GATE-0:PASS`; action ledger rebuilt with `build-action-ledger.js --write` (shared file, new controls only from this lane).

## Cross-lane

- qx-b1 (help panel / skip): the author ladder writes `hintLadder` + `hintFreeUsesPerRun`; the player help panel is theirs.
- Backend: the runtime session view passes option `img` and suspect `avatar` as raw stored references (`QaOptionMediaSupport.media`), so app-uploaded images arrive as `media-asset:{id}` and the client correctly shows text only. `QaOptionMediaSupport.project(qa, visible)` already exists with a visibility resolver; the runtime path should use it so approved images actually show.
- qx-b7 (iOS): same contracts; nothing iOS-specific added here.

## Needs owner

1. `placeCountryCode`: add an owner/scope-checked author write path, or keep it admin-only? Default: no field.
2. Arrival policy/reading: where may an arrival-only node block live without overriding the node's template? Default: no field.
3. UG-11 「预览玩家看到的样子」 button: the editor's existing 「按这份配置试玩」 already previews the question screen; a ladder-specific preview needs the help panel from qx-b1. Default: not added.

STATUS: PARTIAL - §23.1 placeCountryCode and arrivalPolicy/arrivalReading need an owner decision (no safe write path); player screens verified with real page code and server-shaped views, not a live published session.
