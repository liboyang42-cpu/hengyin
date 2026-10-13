# 商家资产中心 GitHub 参考（2026-07-22）

参考 [Mercur 的打款列表列定义](https://github.com/mercurjs/mercur/blob/main/packages/vendor/src/hooks/table/columns/use-payout-table-columns.tsx)、[状态与时间筛选](https://github.com/mercurjs/mercur/blob/main/packages/vendor/src/hooks/table/filters/use-payout-table-filters.tsx) 与 [单笔打款详情](https://github.com/mercurjs/mercur/blob/main/packages/vendor/src/pages/payouts/%5Bid%5D/_components/payout-general-section.tsx)。

可借鉴的信息架构是：可提现、处理中、已到账分开表达；金额、时间、状态可追溯；列表可钻取。城瘾现有商家台账已由 `/api/merchant/orders/ledger`、`/api/merchant/settlements/ledger` 和 `/api/coop/mybiz` 提供真实订单、结算、合作分润，资产中心的俱乐部收入由 `/api/coop/finance` 提供。

本轮不照搬 Stripe Connect/KYC、多币种和 webhook 状态机，也不虚构提现状态。只令资产中心的微信零钱入口服从后端 `fastWithdrawEnabled` 开关；接口可读余额不等于该出金通道已开通。
