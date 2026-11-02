# Round 2: owner decisions, 2026-11-02

The BRIEF.md rules still bind. Work on your lane's existing branch and draft PR; pull first, because the branch may have moved. Before starting, check the branch has been quiet: if its newest commit is under 10 minutes old, or `RESULT.md` has no `STATUS:` last line, wait and re-check every 5 minutes, up to 60 minutes. Never force-push. Never merge. Update `RESULT.md` and its last line, and add before/after shots for visible changes.

## l1-play

- **P-23 compass unreadable, approved:** the author can tick 「允许跳过」 on the compass game, default off. After two failed reads, show 「跳过这一站」. The server completes it with 0 points and records the skip. In a leader-run session the leader can also release the station on the spot. Add a backend skip-completion path open only to compass/sensor games, checking attempt count and ownership. Prove it over HTTP in the rig; a skip by another member or for a non-sensor game must be refused.
- **P-26, approved:** keep asking for location on each play. No change. Mark it `no change, owner confirmed`.
- **P-29:** still open from round 1. A chapter's closing text must show before the next chapter card. Do it if you can walk the chapter change in the rig; otherwise leave it `not done` and say exactly why.

## l2-explore

- **P-22, approved:** rename the metric 「累计打卡 N 处」, counting redeemed shops plus completed city stations, without double counting. Hide km until mileage has a real source. Missing data on this page still shows 0.
- **P-28, approved:** hide 「队伍位置」 in the free-exploration menu when no leader is involved (`lead.freeEntry`). Report 「切换章节」 if it looks wrong; do not change it.
- **Wallet subtitle, approved:** keep both lines. No change.

## l3-sell

- **P-01, approved:** when a theme is bound to a club and has no partner club, count the publishing club as its organiser, so a club can run its own paid city theme through sale review. Keep the existing rules when a partner exists, and check club ownership.
  - Do NOT change `assertCollectible`. A club-owned theme can reach sale, but payment stays refused until the merchant-account model is live, and the organiser must see that plainly.
  - Prove it over HTTP: the sale path succeeds for a club's own theme, and the quote still returns the payment refusal.
  - Do not change payouts, shares or settlement. If this changes who is recorded as being paid, stop and list it under `needs owner`.
- Your RESULT.md says a draft PR exists, but none was visible at 08:51 UTC. Open it if it is missing.

## Payment model (owner decision, recorded for all sessions; not lane work)

- Each organiser (merchant or club) uses their own WeChat merchant account. Ticket money goes straight to it and never to the platform first. The platform charges a 3% commission on actual ticket revenue.
- Co-organisers settle offline with the organiser. They confirm receipt themselves; that is a record only and cannot block withdrawal, because the platform does not hold the money.
- Refunds use one platform-wide rule: 100% more than 24 hours before the start, 50% within 24 hours, none after the start. A merchant dropping out is handled manually, case by case, with the 3% recalculated.
- Merchant numbers are reviewed once by the platform after WeChat authorisation; changing the number means a new review. Without an approved number, turning on 「本章开放商家承接」 or publishing a paid theme shows a pop-up, and the ticket page says nothing about it. Clubs WeChat will not allow are told to apply. Platform-owned themes keep the platform merchant account and pay no 3%.
- China build only. US providers, accounts and flows need a separate decision. This does not authorise payment or deployment changes.
- **Status:** design approved; no code until 服务商 approval. `assertCollectible` stays closed until then, so paid themes launch as platform-owned for now. No lane changes this.
