---
name: payout-erqing-20261110
description: Owner wants merchant/creator payouts without 二清 risk; current code collects everything on the platform mchid and redistributes via internal ledger + T+7 balance + withdraw
metadata:
  node_type: memory
  type: project
  originSessionId: 356c3f1b-c006-42a4-8c1c-61c5eeb6a529
  modified: 2026-11-10T08:48:19.281Z
---

2026-11-10 owner (re W2-11 收款账户): "The main issue concerns 二清… Questo doesn't actually have merchants; ideally we need a way to distribute funds directly."

Current state (verified in code): no sub_mchid anywhere; every player payment goes to the platform WeChat Pay merchant number (WechatPayServiceImpl#buildPrepayRequest), then coop_settlement line types (PLATFORM / INITIATOR_* / LEAD_FEE / MERCHANT_VERIFY) credit member balances T+7 and members withdraw (member_cash_withdraw) — that is the 二清 pattern.

Options I proposed: (A) WeChat Pay 服务商 + 特约商户 + 分账; (B) 电商收付通 (二级商户, multi-party split — fits our multi-line settlement); (C) Questo-like: platform is seller of record, merchants/creators paid as suppliers (invoice / licensed 灵活用工 for individuals). Recommendation: C for official themes + membership, B for merchant-sold tickets/activities. Money/payment change → needs owner decision + payment-provider/legal confirmation before any code. Related: [[questo-ui-wave-20261109]].
