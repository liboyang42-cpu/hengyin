# S2 collection model: questions for counsel and WeChat Pay console steps

Everything below is about the model built on `feature/r-collection-model`. It acts only while the money switch's server
ceiling (`CHENGYIN_MONEY_SWITCH_SERVER_CEILING`) is on, which is false in production. Nothing changes in production until
counsel answers and the owner says "money on".

## The model, in one paragraph (for counsel)

The platform (城瘾) collects money only for its own official tickets: tickets on the platform's own content slot, and
activities run by the platform's own settlement account. Every ticket sold by a third party (a merchant, a club host,
or an individual organiser) is paid by the buyer directly to that seller's own WeChat Pay sub-merchant account
(电商收付通二级商户, ordered through the service-provider JSAPI `/v3/pay/partner/transactions/jsapi` with
`settle_info.profit_sharing = true`). After the existing hold period (T+N after the event ends, longer while a complaint
is open), the platform requests profit sharing (分账) of its service fee only, as a receiver amount on that payment; the
rest is released to the seller in the same request. A refund first returns the shared service fee (分账回退), then refunds
the buyer from the seller's sub-merchant. A third-party seller with no sub-merchant cannot sell paid tickets at all: the
buyer is told to contact the organiser. Club dues have no split path yet, so they are refused while the model is on.

## Questions for counsel

1. **Is this "no 二清"?** With the flow above, does the platform still fall under 二清 (collecting funds for others and
   settling to them) or any payment-business licensing requirement? In particular, is the platform's own service fee
   taken by 分账 from a sub-merchant payment an acceptable way for a marketplace without a payment licence to be paid?
2. **Official tickets.** We treat as "official" (platform collects): (a) theme tickets whose content slot is the
   platform's (`product_role` CONTENT = PLATFORM), (b) independent activities created by the platform's own settlement
   account. Are both clearly the platform's own sales? Activities co-hosted with a merchant venue are NOT official here.
3. **Hold period before sharing.** Funds stay frozen in the seller's sub-merchant until the event has ended plus the
   existing hold days (`FundsHoldPolicy.WITHDRAWABLE_AFTER_END_DAYS`), longer while a complaint is open. Is holding the
   seller's money frozen that long acceptable, and does it need to be in the seller agreement? WeChat's own limit on how
   long a payment can stay unshared (currently documented as 180 days for 电商收付通) must also be respected.
4. **Service-fee cap.** The share is capped by `payout.ecommerce.max-share-percent` (default 30%). WeChat sets its own
   cap per merchant in the console. Is the platform fee (today 3% for most tickets, configurable per theme) a "技术服务费"
   that may be taken by 分账, and does it need a separate invoice to the seller?
5. **Individuals as sellers.** Independent activities by individual organisers (no club, no merchant) are third-party
   sales. An individual cannot get a 二级商户 in the usual way (小微商户 may be possible). Until they do, their paid
   activities are refused. Is a 小微 sub-merchant acceptable for them, or should individuals only sell through a club?
6. **Multi-party (co-op) themes.** A theme whose ticket money belongs to several parties (co-op orders) is collected by
   the theme seller's sub-merchant and then shared to the other parties' sub-merchants by profit sharing (one receiver
   per party). Is sharing from one seller to other sellers (not only to the platform) allowed for us?
7. **Combined payment (合单支付) is paused under the model.** The existing combined-payment path (switch
   `payout.ecommerce.combined-enabled`, default off) puts the shares known only after settlement into a platform
   self-operated sub-order and pays those parties later as supplier payables. That is collect-then-pay. While the model
   is on, no new order uses it. Should it be removed for good, or is it acceptable with a supplier contract?
8. **Club dues.** Dues are a club's own income. Must they also go to the club's own sub-merchant (we would build that),
   or may a club host collect them outside the platform entirely?
9. **Existing balances.** Ticket money collected before the switch (2026-11-02 change: hosts without a merchant number
   still sold through the platform account) sits as member balances after T+14. Is paying those out through the existing
   withdrawal (another package, money-payout) acceptable, given they were collected before the model? How should we
   describe this in the seller agreement?
10. **Refund priority.** If the shared fee cannot be returned (e.g. the platform receiver account has no balance), the
    buyer's refund waits until the return succeeds. Is a delayed refund acceptable, or must the platform refund the
    buyer first from its own funds (which would be platform money, not the seller's)?
11. **App channel.** The native app pays through WeChat's APP channel, which has no service-provider order in our build.
    While the model is on, third-party tickets can only be paid in the mini-program. Is that acceptable for launch?

## WeChat Pay merchant-console steps for the owner

Do these in TEST first. Never paste a key, certificate or merchant id into chat, a ticket or the repository: put them in
the server's environment variables only.

1. **Become a service provider for 电商收付通.** In 微信支付商户平台 (pay.weixin.qq.com), apply for 电商收付通 as the
   platform (电商平台) with the company's business licence and the platform's business description. Note the
   service-provider merchant id (sp_mchid).
2. **Bind the mini-program appid** to the service-provider merchant (产品中心 → AppID 账号管理), so
   `sp_appid` can be used in the partner JSAPI order.
3. **API v3 key and certificate.** Set the API v3 key and download the merchant API certificate for the sp_mchid.
   Put them on the server only, referenced by `payout.ecommerce.api-v3-key`, `payout.ecommerce.private-key-path` and
   `payout.ecommerce.merchant-serial-number` (environment variables / server configuration). Never commit them.
4. **Notify URLs.** Configure `payout.ecommerce.notify-url` (partner payment results) and
   `payout.ecommerce.refund-notify-url` (refund results), HTTPS on the TEST server.
5. **Profit sharing.** Open 分账 for the platform; set the maximum sharing ratio (must be ≥ the platform fee, and we cap it
   at `payout.ecommerce.max-share-percent`). Decide which account receives the fee (`payout.ecommerce.platform-receiver-mchid`,
   default the sp_mchid itself).
6. **Sub-merchant applications.** For each merchant and club that will sell: operations register it in the admin
   console (电商收付通 → 收款账户 → 登记 → 提交进件) with its business documents; WeChat reviews; the seller signs
   (待签约 link) and verifies its account. When the state is FINISH the sub-merchant id is stored and the seller becomes
   collectible. Status is visible on 分账收款 → 卖家收款状态, and to merchants on the mini-program page 收款账户.
7. **Server configuration** (TEST only): `PAYOUT_ECOMMERCE_ENABLED=true`, seller routing and profit sharing on, sp_mchid,
   sp_appid, notify URL, then `CHENGYIN_MONEY_SWITCH_SERVER_CEILING=true`. Production stays false.
8. **Rehearse** one paid club ticket in TEST with a real sub-merchant and a 0.01-yuan ticket: pay, wait the hold (or
   shorten it in TEST), check the share on 分账收款 → 分账台账, then refund one and confirm the return happened first.
