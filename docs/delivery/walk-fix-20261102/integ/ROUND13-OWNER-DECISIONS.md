# Round 13 · owner and write-contract decisions

One plain line each: what a user sees, and whether I think it should block launch. These are left alone on purpose. They change money, stored data, moderation behaviour, or a design the owner already approved. Sources: `CODEX-PAGES-RESULT.md`, `ROUND12-K9-RESULT.md`, `ROUND12-K9-ORG-RESULT.md`, `ROUND12-K3-RESULT.md`, `ROUND12-K5-RESULT.md`. "Block?" is my recommendation, not a decision.

| # | Item | What a user sees | Block launch? |
|---|---|---|---|
| 1 | Coupon has no value field; default validity is the date picker's default | A merchant making a coupon cannot say how much it is worth, and the end date starts at whatever the picker offers. A player sees a coupon without an amount. | No, if coupons are not promoted at launch. Yes, if the launch story includes coupons. |
| 2 | Verifier identity: the order records the shop, not the clerk who scanned | A shop owner cannot see which clerk redeemed a ticket. Players see nothing different. | No. |
| 3 | `/api/coop/invite` accepts an invite without `shareMode` (the club branch of the invite sheet) | Not reachable today: the only screen that opens it sends the field. No user sees a problem. | No. Fix before the club invite branch is switched on. |
| 4 | Early redemption (redeeming before the session starts) | A shop can scan a ticket early; the money rule for it is not stated. | Lead's call. It touches money, so I did not look further. |
| 5 | Moderation: a draft saved while WeChat is busy is held on the update path but not on the create path | An author editing an existing theme may find the edit held for review when saving while WeChat check is slow; a new theme is not. Players are not affected. | No. |
| 6 | Moderation hit-log rows are rolled back when the save is refused | Nothing on screen. Staff lose the record of what was caught on a refused save. | No for players; yes if staff rely on the hit log for appeals. |
| 7 | An activity's bound game is not included in the safety text check | An author can put text in a bound game that is not scanned on save. | Lead's call: it is a moderation gap on user-written text. I would not ship it unnoticed. |
| 8 | Notice, disclosure and consequence lines (cancel, refund, logout dialogs; 评价 points line; chat footer 「系统通知不支持回复」; activity edit line) | Short grey lines under some titles. The owner rule says cut helper text, but approved contracts (CU-C-103, CU-C-120, c04-r3) pin some of them, and some are legal or consequence text. | No. Keep until the owner rules on each. |
| 9 | 「长按卡片管理」 on 我的项目 cards | Cards have no visible button; management is by long press only, because the owner's design says no buttons on cards. A new user may not find it. | No. Owner design. |
| 10 | Home role chip 「✓ 玩家 · 上海市」 | Shown at the top of home; pinned by the owner's 2026-10-27 phone layout. | No. |
| 11 | Invoice-title page title spacing | The invoice feature is switched off, so nobody sees it. | No. |
| 12 | Templates made with 另存为模板 carry no ticket types | A template copied into a new theme has no tickets; the author adds them by hand. | No. |
| 13 | Former club owner after a transfer loses club settlement access (their withdrawable amount) | The previous 主理人 can no longer open 俱乐部结算 for the club they handed over, including money still owed to them. | Lead's call. It is a money-visibility question. |
| 14 | 评价 timing: the server allows a review as soon as the order is redeemed | A player can review before the whole route is done. | No. |
| 15 | Old 主理人 gets 「欢迎加入」 after a transfer; ticket wallet 「生成中… · 今天过期」 after the end (F-22, F-23) | Odd wording shown once. | No. |

Not decisions, but still open in the page lists (not walked or not done): organiser/merchant invite wording details, customer tags, editor preview overlap, publish deep link without scope, and the data-filled pages (game node, coop settlement, batch detail, prediction) that need journey fixtures. See the new status line in `ROUND13-M5-RESULT.md`.
