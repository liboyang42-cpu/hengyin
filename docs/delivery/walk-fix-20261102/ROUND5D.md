# Round 5D: owner's decisions, 2026-11-03

Pull your branch first; another run may have just pushed. Merge, no force push. Commit and push after every finished item. Draft only; never merge. Never touch production. Finish with a `Round 5D` section in RESULT.md, tests red on old code, UI-GATE-0:PASS, no CI failures, last line `STATUS: COMPLETE` or `STATUS: PARTIAL - reason`.

## l3-sell: input pop-ups on the first pages users see
Pages: `pages/activity/baoming` and `pages/merchant/apply` only, including components they own. The editor pages wait until after launch.

**Owner: these pop-ups differ from one another, so be careful not to break anything.** Before changing any of them, list it in RESULT with file:line, trigger and type:
- **Convert** only a pop-up that reports a problem with what the user just typed or left empty, to `cy-inline-error` under that field, with the label's red `*`. The page must scroll to the first error. The submit path must still be blocked exactly as before.
- **Leave:** confirmations (cancel, pay, non-refundable, within-24 h), consent, login prompts, server or network failures, messages relayed from the backend, success notices, anything with two buttons, anything whose code path you can't fully follow. If you aren't sure, leave it and list why.

For each converted pop-up, test: the invalid case shows the line and doesn't submit; fixing the field clears the line; valid input submits with an unchanged payload. Run all existing tests for both pages. In the rig, walk both pages end to end with valid input and with each invalid case, and shoot the error states. Payment and sign-up behaviour must not change.

## l1-play: P-08 button
Add 「返回地图」 at the very bottom of the 等待放行 screen, using the existing bottom button style. It closes the waiting screen and shows the map. Being released later still works, whether the player is on the map or back on this screen: they must reach the chapter with the normal `cy-chapter-flip`, and must not get stuck or miss it. Add a way back to the waiting state if they tap the held chapter again. Test each path and shoot it.

## l8-safety: sensitive content library
**Owner: the backend gets its own library that detects sensitive keywords and images, automatically.** Do this after your ROUND5C section. No new dependencies, plain Java. It adds to WeChat's checks and doesn't replace them.

**Words**
- New tables via a new `sql/migration_*.sql` (registered in `ci/migration-dependencies.json`): words with category, action and enabled state, plus an allow-list for phrases that contain a word but are fine (`成人票`, `血量`).
- An in-memory matcher (DFA or trie), loaded from the table and refreshed when the table changes, across instances through the existing Redis if one is used for this kind of thing. A failed load must not silently disable checks: keep the last good copy and log an error.
- Normalise before matching: full-width to half-width, case, traditional to simplified if practical without a library, and symbols, spaces or emoji placed between characters.
- Plug it in at one place all text passes through: `IWxContentSecurityService.checkText` and `ContentSafetyGate`, before calling WeChat. A hit is refused at submit with the existing violation message. Never show the matched word. It must work when WeChat is down or off. Find any text path that skips those and list it.

**Images**
- New table of known-bad image hashes: a perceptual hash (dHash with `ImageIO`) plus SHA-256.
- Check in `AppUploadService` before storage, and in the async image path. A hit is refused or removed automatically.
- Be honest in RESULT: this catches copies of known images only. A new image still depends on WeChat.

**Existing content.** A scheduled job re-checks public text when words are added, using the existing Quartz setup, and removes hits through the same automatic path as ROUND5C, on comments, posts, club posts, themes and activities, and merchant and club profiles. It has a per-run limit and is idempotent. A switch turns removal off so it only reports, default report-only until the owner has seen a run.

**Management.** Admin pages in `chengyinhub-ui` to list, add, disable and bulk-import words and allow-list phrases, add a bad image, and view recent hits. Use the RuoYi menu and `@PreAuthorize`. The hit log stores content type, id and category, with no personal data.

**Word list: do not commit any word list to this public repo.** Tests use made-up tokens. Provide the import format and a script that reports false positives: run a given list against all text in the repo's seed data and the mini-program's bundled text, and print the hits. Report the numbers.

Prove with tests: word hit, split with symbols, allow-list, WeChat down, failed load, image exact and near-duplicate, the re-check job in both modes. Update the one-page table from ROUND5C.
