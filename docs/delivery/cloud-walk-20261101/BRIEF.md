# Cloud end-to-end walkthrough, 2026-11-01: shared brief (binding)

Candidate under test: `master` at `a1d30fb295d361122aec76fa9c180b4b496a9b0a`. Test this commit, not a newer one.

## The owner's request

Many fixes landed with no full walkthrough. Walk the product the way a real person uses it, with dummy data, from city orienteering (城市定向) to free exploration (自由探索). The point is the real experience, not the code: many things look fine technically, and only someone in the user's shoes finds what is illogical or counterintuitive. The flow must feel natural and seamless from the user's side.

So a finding is about what a person sees, understands and does. "Returns 200" is not a pass. For each screen ask: what does this person think is happening, what do they want next, is the next step obvious, and did the app keep its promise?

## Roles

| Lane | Branch | Lens |
|---|---|---|
| rig | `claude/cloud-walk-20261101-rig` | Stack + browser runtime for the walkers |
| player | `claude/cloud-walk-20261101-player` | Player, judged as a professional theme editor and a user analyst |
| merchant | `claude/cloud-walk-20261101-merchant` | Merchant, once a specific theme session has begun |
| club | `claude/cloud-walk-20261101-club` | Club organiser and leader, once a specific theme session has begun |

Each walker runs its own backend and database, and plays the other roles through real API calls or real pages when its story needs them.

## Hard limits

- No production. No VPS. No private repository. You have no credentials for them, and you must not look for any.
- Every secret is a throwaway value generated in the session. Never commit one. Generate the PII key (`CHENGYIN_WITHDRAWAL_PII_KEY`) once and reuse it across restarts, or registration and CRM reads fail with 「PII 解密失败」.
- The repository is public: dummy names, dummy phone numbers, no real people, no real keys. Tencent Map keys must not enter the repository.
- Test only. Do not change product code, tests, gates, ledgers, baselines, migrations or workflows. Do not fix what you find: report it. A local, uncommitted patch that gets you past a blocker is allowed only if the report states it exactly.
- Push only to your own lane branch. Do not open PRs, merge, deploy, or dispatch workflows. Nothing touches master.
- Do not run `ci/xcx-check.sh` or `ds-hardcode-gate-selftest.sh`: their selftests write fixtures and commits into the repository.
- Do not disable a check or fake a result to make a step pass. Never set `payment_status` directly: paid state goes through the real `/api/registration/callback` path, per `scripts/e2e/README-e2e-stub.md`.

## What counts as evidence

Label every result as exactly one of:
- **VERIFIED-UI**: real page code rendered in the browser runtime against the real backend, with a screenshot.
- **VERIFIED-API**: real HTTP against the real backend, with a database read-back.
- **NOT VERIFIED**: say why, and what would verify it.

The runtime is not WeChat. These can only be injected, so they stay **NOT VERIFIED** as real behaviour even when the flow after them is tested: `getPhoneNumber`, real WeChat login, real payment channel, camera scan, real GPS, compass, share cards, subscribe messages, native map rendering, device safe areas, keyboard, and audio or font loading from OSS.

Before reporting a bug, check it is not an artefact of the runtime or your fixtures (missing shim, wrong field name, no images because OSS is absent). Report suspected artefacts separately as **HARNESS**, never as product bugs. Do not claim "the whole flow passes"; state what ran and what did not.

## Dummy data needs to feel real

A walkthrough on thin data proves nothing. Author themes as a real editor would, through the real authoring and publish path where possible: a believable title, cover, chapter story text, real-looking Shanghai station names and coordinates, varied games and hints. Include at minimum:
- **City orienteering theme**: 3+ chapters, 8+ stations, story text, several game types, GPS arrival.
- **Free exploration theme**: 4+ merchants who joined after it was first published, each with a perk or game and a QR code.
- **A club-led session** and a platform or merchant-hosted session.

Respect these harness timing rules: open chapter recruitment at creation; read the V2 create response as `data.topicId`; the executing club leader needs publisher identity before `open-session`; a session's meeting time must be in the future, sign-up closes at start, and play needs start. So open a session a few minutes ahead, sign up and pay, then wait.

## Owner decisions: do not report these as bugs

You may note a real user-impact cost as an observation, but mark it **OWNER DECISION**.
- Stations with a location require arrival within 50 m before answering (`ARRIVE_FIRST`). Photo consent is asked once per run. Phone number is WeChat-only. One redemption code works at many stores.
- A club-led session may gate chapters behind the leader (「等队长放行」).
- All content, including topics and activities, is published first. Club names are globally unique. External links are refused.
- Messages are 收件箱/发件箱; system notices appear only in 城瘾小助手; there is no read-all.
- Guests can browse with no login on open; writes trigger a login pop-up.
- Merchant views are light; player views are dark.
- Terms: 帖文, 节点, 管理员, 商家. No sub-descriptions or helper captions. Missing data shows 「—」, not a fake 0 (marketing pages keep 0). Backend-mirrored messages are not reworded.
- Sheets use peek/half/full sizes. There are no sound effects: music and voiceover only.
- Text-only wording and art inside a theme are the author's content, not a code bug, but as the theme-editor lens you should still critique them.

Recommendations must fit the current design. Existing UI is the baseline: suggest small edits, never a redesign or restyle.

## Already known: do not re-report as new

Read the bodies of open PRs #701, #702, #703, #704 and the fix branch `fix/merchant-home-role-resync-20261101`. They are not on the tested commit. If you hit something they cover, list it under "Known, fix pending" with the PR number, and say whether the experience suggests the fix is sufficient.

## Report

Write to `docs/delivery/cloud-walk-20261101/<lane>/`:
- `REPORT.md`
- `shots/NN-short-name.jpg`: 390 wide, JPEG quality about 70, under 40 MB per lane in total. Review every screenshot yourself at readable size before citing it: empty headings, blank previews, overlaps, contradictory numbers.

`REPORT.md` contains, in this order:
1. **Verdict in five lines**: could this person finish what they came to do, and where would they give up?
2. **The journey, step by step**: what the person wanted, did, saw, and felt, with screenshots, including what worked well.
3. **Issues, ranked**: `ID`, severity, one-line user-facing problem, reproduction, what the user expected, screenshot, evidence label, likely code location, smallest suggested change.
   - **S1**: the person cannot finish, or loses money, data or trust.
   - **S2**: the person is confused or takes a wrong path, but can recover.
   - **S3**: friction or polish.
4. **Cross-role seams**: where what one role does is not reflected for another (lag, contradiction, no notification).
5. **Not verified and harness notes**: every injected capability, local patch and fixture.

Commit in Chinese with a type prefix, for example `docs: ...`. Commit and push after each major journey stage, so that a usage limit or cut-off loses little. Before each push, run gitleaks if you can get it (CI pins 8.21.2), and always grep the diff for keys and tokens.

The last line of `REPORT.md` is `STATUS: COMPLETE` or `STATUS: PARTIAL - <what is missing>`. Finish even if the owner is not around; do not wait for answers. When a question has no clear answer, take the most realistic user assumption, and record it.
