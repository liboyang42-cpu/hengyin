# W7 x-channel-talk — backend receipt (hengyin)

Branch `feature/r-x-channel-talk` from `origin/roadmap/be-integ` (`d814ff1b5`). `feature/r-x-channel-screen` and
`feature/r-x-channel-safety` are already in that base (checked with `git merge-base --is-ancestor`), so nothing was merged.
App side: chengyin-app `cloud/r-x-channel-talk` (receipt `docs/roadmap/x-channel-talk/RESULT.md` there).
Review: `review/opus-w7:docs/review/opus/social/ISSUES.md` (PKG-D "Talk like Discord").

## Issues

| ID | Backend status | Notes |
|---|---|---|
| SOC-3 | Fixed (step 5, plus one gap found while fixing) | Channel snapshots now carry `replyTo`. The sender's own send and receipt responses now carry `clientMessageId` (see below). |
| SOC-11 | No backend change needed | Reaction, extras, thread and typing routes were already live; SOC-4's send gate on react/typing is in the base. |
| SOC-12 | No backend change needed | Photos already upload to platform storage. |

## Changes, file by file

- `chengyinhub-system/.../channel/platform/ChannelMessageService.java`
  - `snapshots(...)` adds `replyTo: {id, state, summary, senderId, senderName}` to every visible message whose stored
    `extra_json` (written by `MessageComposeSupport`) holds `reply_to_id`. Quoted heads are read in one batch per page
    (`selectMessages`), and rows already on the page are reused.
  - States: `VISIBLE` (status 0 and passed review; summary ≤ 80 code points + "…", text/card/poll only), `RECALLED`
    (status 1), `BLOCKED` (status 2), `UNAVAILABLE` (pending review, another conversation, or missing). Only `VISIBLE`
    carries a summary or sender, so recalled or erased text never reappears inside someone else's message.
  - New helpers `replyToId`, `quoteView`, `quoteSummary` (package-visible, static, tested).
- `chengyinhub-admin/.../controller/api/ApiImController.java`
  - `send` and `send-receipt` return `ownChannelMessage(...)`: the same channel snapshot plus `clientMessageId`.
    Why: the channel snapshot has never carried `clientMessageId`, but the app's text client checks it on every send
    and receipt. Without it every 频道 send ended as "result unknown", and replies could never confirm. Only these two
    responses get it, and both go to the sender only. Additive field; no route or field removed or renamed.
- `chengyinhub-admin/src/test/.../ChannelMessageServiceTest.java`: `snapshotCarriesReplyQuoteVisibleRecalledAndForeign`,
  `replyToIdReadsOnlyServerDecimalIds`.

## Verification

| Command | Result |
|---|---|
| `LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test -Dtest=ChannelMessageServiceTest -Dsurefire.failIfNoSpecifiedTests=false` | exit 0; `Tests run: 12, Failures: 0, Errors: 0` |

The REPLY mention row on a reply was already covered by `MessageComposeSupportTest` (`insertMention(70, "REPLY", 4L)`).
No migration, no sys_job, no `@Scheduled`, no new personal-data table, no new player message text, so the migration,
stagger, two-node, erasure and punctuation ratchets are not touched.

Not verified here: a live round trip on TEST (reply in #闲聊 → the quoted author gets "[有人@你] …" and both clients
show the quote).

## Overlap with the UI agent

- `fix/registration-list-r3` does not touch `ApiImController.java` or `ChannelMessageService.java`. Nothing cherry-picked.

## Owner actions / open questions

- None for the backend. The mini-program still has no replies, mentions or reactions (review SOC-11 step 6 is optional
  and was not done).
