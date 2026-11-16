# Server money gates (W7 MERCHANT-3)

Companion to the app runbook (`chengyin-app: docs/roadmap/money-switch/RUNBOOK.md`). The admin page 资金开关 is the
one fast off switch for the server money writes listed as "Q5" below: `MoneyGate` reads the Q5 area on every request,
so switching an area off stops that path on the next request, without a configuration change or a restart. Each path
also keeps its legacy property (AND), so a property that is off still closes the path.

`MoneyWritePathInventoryTest` fails when a new WeChat collect or pay-out call site appears without an entry here.

| Server money path | Client | Server gate | Q5 area |
|---|---|---|---|
| Club dues `renew` / `invoice/resume` (new prepay only) | mini-program | Q5 `tickets` AND `chengyin.collection-model.enabled` AND SPLIT to the club's own sub-merchant. The platform account never collects dues (`CLM…` refused on the platform JSAPI and APP paths in every mode) | **tickets** |
| External-game payments `/api/external-game/orders` | app | `chengyin.release.money-payments` AND Q5 (`MiscMoneySwitch.paymentsOpen`) | **tickets** |
| `/api/pay/create` | app | `chengyin.release.money-payments` AND Q5 (`MiscMoneySwitch.paymentsOpen`) | **tickets** |
| `/api/asset/withdraw` | app | `money-payments` AND `payout` AND Q5 (`MiscMoneySwitch.payoutOpen`) | **payout** |
| Reward-pledge cash, referral bounty cash | app / admin | `money-payments` AND `payout` AND Q5 (`CashPayoutSwitch.open`) | **payout** |
| Creator sale money state | server job | `money-payments` AND Q5: `PENDING_PAYOUT` only when both are on, else `HELD_MONEY_OFF` | **payout** |
| Pass App Store, template App Store | app | Q5 `pass` + own flags | pass |
| Merchant plan checkout / grant / confirm, visit billing, sponsored-quest charge | mini-program / admin | Q5 `merchantMoney` | merchantMoney |
| Withdrawal request / approve transfer | mini-program / app / admin | Q5 `withdrawal` AND `withdrawal.fast-withdraw.enabled` | withdrawal |
| S2 split settlement (分账) and split ticket orders | job / mini-program | `chengyin.collection-model.enabled` (own flag, default false; no longer the app money ceiling) | none (legal switch) |
| Ticket registration JSAPI / APP order | mini-program / app | `wx.pay.enabled`; APP also needs `wx.pay.app-appid` | none: live business before Q5 |
| Merchant commerce (premium template, promotion slot, custom event) | mini-program | `wx.pay.enabled` | none: live business before Q5 |
| Marketing-product cash | mini-program / app | `cashChannel.enabled()` AND wx.pay ready | none: live business before Q5 |
| Co-op deposit | mini-program / app | wx.pay ready (APP: `wx.pay.app-appid`) | none: live business before Q5 |
| Wallet withdrawal `/api/wallet/withdraw`, club settlement withdrawal `/api/club/settlement/withdraw` | mini-program | WeChat transfer readiness only | none: live business before Q5 |
| Workshop template WeChat APP order | app | workshop app channel flag AND `wx.pay.app-appid` | none (app side reads `pass`) |

## Rollback

- Stop a Q5 path: switch its area off on 资金开关. Takes effect on the next request.
- Stop S2: set `CHENGYIN_COLLECTION_MODEL_ENABLED=false` and restart. Ticket sales fall back to today's platform
  collection; club dues stay refused (they are never collected by the platform).
- The rows marked "none: live business before Q5" are not stopped by 资金开关. Putting them under a Q5 area would stop
  money that moves in production today; that needs an owner decision (see `docs/roadmap/x-money-gates/RESULT.md`).
