# qx-a5d · 文章式故事编辑器 — RESULT

Base `origin/master` 2bb811437 · branch `claude/qx-a5d-20261109` · page `pages/publish/fabu`（城市定向章节「编辑故事流」）

Owner: 「这样一段一段写太麻烦了 你看一下x article是怎么做的 我看到是可以直接复制 然后玩法/图片/音频之类的统一顶部标签 然后添加 然后添加之后可以拖动 放到合适的剧情里那种 也可以添加分割线之类的」

## Items

| ID | Status | What changed | Files | Verified | Unverified on a real phone |
|---|---|---|---|---|---|
| A5D-1 One continuous article | fixed | Each chapter is one writing surface. The per-gap 「+」 insert rows, the guide rail and per-paragraph dots are gone, so paragraphs read as one document. Pressing Enter or pasting multi-paragraph text at the caret splits it into paragraph blocks (blank lines dropped, as the player view already does). The caret moves to the right paragraph. Paragraphs with a speaker (`who`) or a node beat keep their line breaks. A paragraph over 5000 characters is cut at the server limit. Tapping the blank space at the end continues the last paragraph or starts a new one. | `fabu/index.wxml`, `fabu/index.wxss`, `fabu/index.js` (`onStoryTextInput`, `onStoryTextFocus`, `onStoryTailTap`), `utils/publish/pro-editor-story.js` (new `splitParagraphs` command and `paragraphParts`) | Unit tests: `story-article-editor.test.js` covers Enter mid/end, paste at caret, unchanged newlines, `who` blocks, metadata copy, key errors and the 5000 cap. Rig shots `A5D-paste-before/after` show the same paste becoming 3 blocks after vs 1 block before (read back from page data). | Native textarea Enter and paste events on iOS/Android. Moving `focus` + `cursor` to the next paragraph (the rig was patched to honour these attributes, see below). Back-space at the start of a paragraph does **not** merge it into the one above, because WeChat sends no input event for it. An empty paragraph is still removed on blur. |
| A5D-2 Fixed top insert bar | fixed | One bar: 玩法 · 图片 · 音频 · 分割线 · 地点 (地点 hidden on the opening chapter, as before). It sits under the chapter name. While typing it docks above the keyboard (`adjust-position=false` + `bindkeyboardheightchange`, and the scroll area pads by keyboard + bar). Every kind inserts at the caret recorded on blur. Mid-paragraph inserts split the paragraph via the existing `insertAtCaret`. At the start or end it inserts before or after without an empty duplicate. With no caret it inserts after the selected block, or at the end. 图片 / 玩法 / 地点 keep their existing flows (upload first; node sheet / template picker), and the insert lands at the caret when they return. A stale caret (paragraph changed or removed) falls back to the block position and never splits the wrong text. | `fabu/index.wxml`, `fabu/index.js` (`onStoryToolbarInsert`, `_storyInsertAnchor`, `_insertStoryMedia`, node insert in confirm path), `pro-editor-story.js` (`caretInsertCommand`) | Unit tests: caret mid/start/end, stale caret, page-level divider at blur caret, audio at end, image upload then insert at caret. Rig: `A5D-insert-after` (audio inserted at a mid-paragraph caret splits 「她推开咖啡馆的门，｜铃铛响了一声。」; divider at the end). `A5D-keyboard-after` shows the bar docked above a simulated 300 px keyboard (the rig has no soft keyboard, so the page's real `onStoryKeyboard` handler received height 300). | Real keyboard height events and bar position on notch phones. Caret capture on blur when the bar is tapped (WeChat textarea blur carries `cursor`; the rig was patched to pass it). 图片 in the rig: upload is rejected by the rig's content-security stub (「安全检查服务异常」, HARNESS), so the image path is unit-tested only. |
| A5D-3 Long-press drag for every block | fixed | Removed the 「节点块不做拖拽」 restriction. Long-press any block (paragraphs, image, audio, album, divider, game or place card, mood/voice/odd/reveal) to lift it, with `wx.vibrateShort({type:'light'})`. It follows the finger and the blocks it passes slide aside to make room (geometry measured at pick-up, so heights can differ). The area auto-scrolls near the top and bottom edges. On drop it vibrates again, pushes an undo snapshot, and calls the existing `moveBlock`. Releasing in place selects the block (shows its ✕), so long-press-to-select still works. Long-press on the paragraph being typed is left to native text selection. Node-beat blocks keep the model's existing refusal (toast). Transitions only, disabled under `prefers-reduced-motion`. | `fabu/index.js` (`onStoryDragStart/Move/End`, `_storyDragUpdate`, `onStoryScroll`), new `utils/publish/story-drag.js`, `fabu/index.wxss` | Unit tests: geometry (`targetIndex`, `shiftFor`, `edgeScrollStep`), page drag reorder with two vibrations, release-in-place selects. Rig: real mouse long-press drag of the audio card from position 3 to 2. `A5D-drag-mid` shows the paragraph sliding down; `A5D-drop-after` shows the new order (read back from page data). | Touch long-press vs scroll on a real device, haptics, auto-scroll feel on long chapters. |
| A5D-4 Divider block | fixed | 分割线 inserts the existing `divider` block (server `ChapterFlowCompiler` already accepts it). The editor now renders it as a short centred rule with a ✕ when selected; previously an unknown block fell through to the node-card branch. | `fabu/index.wxml`, `fabu/index.wxss` | Round-trip tests; `A5D-insert-after`. | Player rendering: see Cross-lane. |
| A5D-5 Saving | fixed | No payload shape change. Order, keys, divider and node blocks round-trip: editor → `toPayloadChapter` → read-back → `materializeChapter` → `toPayloadChapter` is identical block for block. The description projection still stops at the first node. | `pro-editor-story.js` | `story-article-editor.test.js` (moves of text, image, divider and game card, then save→reload→save equal; page `_storyPayloadChapter` round-trip). Existing story-flow suites stay green (`pro-editor-story-flow`, `story-article-commands`, `pro-editor-fabu-flow`, `publish-story-dream-vars`, `story-textarea-reflow-contract`, `publish-story-editor-hit-targets-contract`). | A real publish against the backend was not run in the rig (the publisher needs identity verification). |

### Contract tests updated (owner's new words override)

- `pro-editor-fabu-flow.test.js`: 「正文两处菜单只提供文字、图片、音频、地点」 → one top bar with `game, image, audio, divider, node`.
- `publish-story-dream-vars.test.js`: no per-gap 「+」 menu handlers; 5 toolbar entries.
- `publish-story-editor-hit-targets-contract.test.js`: the 44 px rule moves from `.story-insert*` (removed) to `.story-toolbar__item`. The aria names change to 插入玩法/图片/音频/分割线/地点 and 在文末继续写.

### Gates

- `node --test` on the story and fabu suites: pass (numbers in the PR).
- `node tests/lint/ui-integrity.js`: `UI-GATE-0:PASS`. The 11 existing fabu U4-DYNAMIC anchors were re-anchored for line shifts only (same call lines, reasons and count; no new entries) and the digest was recomputed. Two dynamic `setData` calls my first draft introduced were rewritten as literals instead of being baselined.
- `scripts/uiaudit/action-ledger.json` was rebuilt with `--write`. The diff touches only `pages/publish/fabu/*` entries (the removed seam menus and the new toolbar/drag bindings).

## Evidence (`shots/`, 390 px, JPEG q70, walkthrough rig, merchant fixture 9002, new chapter)

| Shot | State |
|---|---|
| `A5D-empty-before` / `A5D-empty-after` | New empty chapter: lone 「+」 seam → insert bar + 「写下这一章的故事…」 |
| `A5D-written-before` / `A5D-written-after` | One paragraph written, keyboard down |
| `A5D-paste-before` / `A5D-paste-after` | Same three-paragraph text pasted at once: 1 block → 3 paragraphs |
| `A5D-insert-after` | 音频 inserted at a caret in the middle of paragraph 2 (split), 分割线 at the end |
| `A5D-drag-mid` | Audio card lifted mid-drag; the paragraph below slides to make room |
| `A5D-drop-after` | After drop: audio card now between paragraph 1 and 2 |
| `A5D-keyboard-after` | Bar docked above the keyboard (keyboard simulated, labelled in shot) |

Rig: `RIG.md` from `claude/cloud-walk-20261101-rig`, `harness-patches.diff` from `claude/cloud-walk-20261101-club`, candidate SHA set to 2bb811437. Before shots use the master bundle; after shots use this branch's `chengyinhub-xcx` (same backend). The pinned MySQL/Redis digests were pulled from `mirror.gcr.io` because Docker Hub returned 429; the image IDs are identical.

Extra rig-only patch (rig worktree, not product code) in `mpweb/src/runtime.js`, marked `QX-A5D HARNESS PATCH`. It makes the browser textarea behave like WeChat's in three ways: a changed controlled `value` applies even to the focused field; `focus` / `cursor` / `auto-height` are honoured; and `blur` carries `cursor`. Without it every textarea renders 150 px tall and focus never moves, which is a rig artefact.

## Cross-lane

- **qx-a1 (play page, `pages/play/index.js` ~3823, `index.wxml`)**: the player story column skips `divider` blocks today (no item is produced; nothing breaks). To show the divider authors can now add, add a `kind: 'divider'` item (one `.chfull__para` wrapper with a short rule) in the block loop. `play-history.js` already keeps dividers. I did not touch play files.

## needs owner

- **Back-space merge.** Pressing back-space at the very start of a paragraph does not join it to the paragraph above, because WeChat's textarea sends no event for it. Default implemented: an emptied paragraph disappears on blur, and the ✕ (long-press, release) deletes a paragraph. Say if you want an explicit 「合并到上一段」 action.
- **Fewer insert kinds.** The bar shows 玩法 · 图片 · 音频 · 分割线 · 地点. 文字 is not a button, because typing or Enter makes paragraphs. Album/mood/voice blocks have no new entry points; they stay editable and draggable where they already exist.

STATUS: COMPLETE
