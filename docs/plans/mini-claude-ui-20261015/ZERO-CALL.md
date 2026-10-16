# 零调用接口终表（BL-133，2026-10-16 快照，基线 `d0307a78`）

> 只写文档。扫描 `chengyinhub-admin/.../controller/api/*.java` 的全部映射（111 个控制器、720 个「方法 + 路径」），对照基线分支小程序 `chengyinhub-xcx/` 下所有 `.js/.wxml/.wxs/.json` 里的 `/api/...` 字面量（**排除** `tests/`、`scripts/`、`node_modules/`），得到 **220 个零调用映射**，逐条归类。
> 接线 PR 判定：对每个 open `claude/bl-*`/`claude/cl*` PR，取 `git diff base...head`（同样排除 tests/scripts）新增行里的 `/api/...` 字面量再匹配；只按前缀匹配上的结果已逐条人工剔除（如 #27 并没有调用 organizer-invites/transfer，#144 只做 GET 列表/详情）。
> 局限：匹配只看路径、不看 HTTP 方法；动态拼接（变量拼 base + 段）可能漏判，已对可疑项逐个 grep 复核。类别 D/E 的依据引用各 BLOCKED PR，详细字段对照见对应 PR 正文。

## 汇总

| 类别 | 数量 | 说明 |
|---|---|---|
| A 已由 open PR 接线 | 37 | 合入对应 PR 后不再是零调用 |
| B 用户决定排除 | 21 | 会费 / 群聊 / 投票 / 邀请候任主办与转交 / 被举报方视图 |
| C 资金、商城或支付 | 32 | 本线不碰资金政策 |
| S 非小程序调用方 | 12 | 服务端回调、App 端登录、健康检查、旧上传口 |
| D 宿主在 Codex | 61 | Codex `owned_files` 或运行时服务；本线只能交补丁 |
| E 已核实 BLOCKED / 已有等价 | 23 | 每条都有对应 BLOCKED PR |
| X 无现成宿主、尚未立卡 | 34 | 需产品决定是否做、放哪 |
| **合计** | **220** | |

结论：除 A 类外，剩下的零调用接口要么属于用户已经排除、资金、Codex 宿主或非小程序调用方，要么已经核实为 BLOCKED。X 类 34 条是唯一可能新开卡的池子，但都需要产品先决定入口（见 `DECISIONS-NEEDED.md`，BL-128）。

### A. 已由 open PR 接线（37）

| 方法 | 路径 | 源码 | 接线 PR |
|---|---|---|---|
| GET | `/api/ai/npc/chat/data/export` | `ApiAiNpcController.java:410` | #27 |
| GET | `/api/audit/{taskId}/detail` | `ApiAuditController.java:26` | #130 |
| POST | `/api/club/badge/create` | `ApiClubController.java:1144` | #23 |
| POST | `/api/club/badge/list` | `ApiClubController.java:1174` | #23 |
| POST | `/api/club/badge/grant` | `ApiClubController.java:1183` | #23 |
| GET | `/api/v1/community/notifications` | `ApiCommunityPostController.java:214` | #25 |
| POST | `/api/community-trust/reports/submit` | `ApiCommunityTrustController.java:36` | #118、#73、#74、#75、#77、#95 |
| GET | `/api/community-trust/reports/taxonomy` | `ApiCommunityTrustController.java:49` | #118、#25、#73、#74、#75、#77、#95 |
| GET | `/api/community-trust/reports/mine` | `ApiCommunityTrustController.java:54` | #25 |
| GET | `/api/community-trust/reports/{reportId}` | `ApiCommunityTrustController.java:65` | #118、#25、#73、#74、#75、#77、#95 |
| POST | `/api/community-trust/reports/appeal` | `ApiCommunityTrustController.java:87` | #25 |
| POST | `/api/community-trust/contributions/submit` | `ApiCommunityTrustController.java:100` | #110 |
| GET | `/api/community-trust/contributions/taxonomy` | `ApiCommunityTrustController.java:114` | #110 |
| GET | `/api/community-trust/contributions/{contributionId}` | `ApiCommunityTrustController.java:144` | #110 |
| POST | `/api/community-trust/safety/ratings` | `ApiCommunityTrustController.java:155` | #132 |
| GET | `/api/community-trust/safety/taxonomy` | `ApiCommunityTrustController.java:169` | #132 |
| GET | `/api/community-trust/safety/merchants/{merchantId}` | `ApiCommunityTrustController.java:174` | #109 |
| POST | `/api/coop/credit` | `ApiCoopController.java:1051` | #147 |
| POST | `/api/im/unblock` | `ApiImController.java:244` | #104、#118 |
| POST | `/api/merchant/subscription` | `ApiMerchantController.java:533` | #152、#158 |
| POST | `/api/merchant/npc/voice/reset` | `ApiMerchantController.java:965` | #88 |
| POST | `/api/merchant/coupon-summary` | `ApiMerchantController.java:1529` | #120 |
| POST | `/api/merchant/verification-records` | `ApiMerchantController.java:1661` | #119 |
| GET | `/api/merchant/crm/campaigns` | `ApiMerchantCrmController.java:119` | #144 |
| GET | `/api/merchant/crm/campaigns/{campaignId}` | `ApiMerchantCrmController.java:128` | #144 |
| GET | `/api/merchant/ops/prerequisites` | `ApiMerchantOpsController.java:26` | #128 |
| POST | `/api/object-card/rename` | `ApiObjectCardController.java:74` | #122 |
| GET | `/api/official/active-for-roam` | `ApiOfficialEventController.java:130` | #83 |
| GET | `/api/official/v2/events/{id}/publish-check` | `ApiOfficialEventController.java:272` | #27 |
| POST | `/api/official/v2/events/{id}/schedule` | `ApiOfficialEventController.java:280` | #27 |
| POST | `/api/official/v2/events/{id}/pause` | `ApiOfficialEventController.java:309` | #27 |
| POST | `/api/official/v2/events/{id}/resume` | `ApiOfficialEventController.java:320` | #27 |
| POST | `/api/registration/count_by_date` | `ApiRegistrationController.java:692` | #141 |
| POST | `/api/roam/shop/visitors` | `ApiRoamController.java:313` | #113 |
| POST | `/api/user/nickname/check` | `ApiUmsMemberController.java:112` | #23 |
| POST | `/api/user/nickname/suggest` | `ApiUmsMemberController.java:118` | #23 |
| POST | `/api/user/player-code` | `ApiUmsMemberController.java:131` | #114 |

### B. 用户决定排除（会费/群聊/投票/候任主办与转交/被举报方视图）（21）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| POST | `/api/club/chat` | `ApiClubController.java:1121` | 群聊：用户决定不做群聊 UI |
| POST | `/api/club/membership/overview` | `ApiClubMembershipController.java:26` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/setting` | `ApiClubMembershipController.java:35` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/setting/update` | `ApiClubMembershipController.java:44` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/plans` | `ApiClubMembershipController.java:53` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/plans/manage` | `ApiClubMembershipController.java:62` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/plan/save` | `ApiClubMembershipController.java:71` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/renew` | `ApiClubMembershipController.java:80` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/invoice/resume` | `ApiClubMembershipController.java:89` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/invoice/close` | `ApiClubMembershipController.java:98` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/invoice` | `ApiClubMembershipController.java:107` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/finance/invoices` | `ApiClubMembershipController.java:116` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/term/cancel` | `ApiClubMembershipController.java:125` | 会费：用户决定不做会费 UI |
| POST | `/api/club/membership/refund/review` | `ApiClubMembershipController.java:136` | 会费：用户决定不做会费 UI |
| GET | `/api/community-trust/reports/{reportId}/subject` | `ApiCommunityTrustController.java:76` | 被举报方视图：用户决定不做（CL2） |
| POST | `/api/im/poll/create` | `ApiImPollController.java:22` | 投票：用户决定不做投票 UI |
| POST | `/api/im/poll/vote` | `ApiImPollController.java:27` | 投票：用户决定不做投票 UI |
| POST | `/api/im/poll/close` | `ApiImPollController.java:32` | 投票：用户决定不做投票 UI |
| POST | `/api/im/poll/result` | `ApiImPollController.java:37` | 投票：用户决定不做投票 UI |
| POST | `/api/official/v2/events/{id}/organizer-invites` | `ApiOfficialEventController.java:289` | 邀请候任主办/转交：用户决定暂不做（CL4） |
| POST | `/api/official/v2/events/{id}/organizer-transfer` | `ApiOfficialEventController.java:299` | 邀请候任主办/转交：用户决定暂不做（CL4） |

### C. 资金、商城或支付（32）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| POST | `/api/balance/list` | `ApiBalanceController.java:36` | 资金/商城/钱包 |
| POST | `/api/cart/list` | `ApiCartController.java:39` | 资金/商城/钱包 |
| POST | `/api/cart/delete` | `ApiCartController.java:60` | 资金/商城/钱包 |
| POST | `/api/cart/cart/add` | `ApiCartController.java:75` | 资金/商城/钱包 |
| POST | `/api/cart/update` | `ApiCartController.java:92` | 资金/商城/钱包 |
| POST | `/api/cart/settlement` | `ApiCartController.java:108` | 资金/商城/钱包 |
| POST | `/api/cart/order/settlement` | `ApiCartController.java:124` | 资金/商城/钱包 |
| POST | `/api/club-compensation/hours/report` | `ApiClubCompensationController.java:65` | 补偿工时上报（资金结算链路） |
| POST | `/api/club/settlement/withdraw` | `ApiClubSettlementController.java:128` | 提现 |
| POST | `/api/coop/deposit/create/app` | `ApiCoopController.java:184` | 保证金 App 支付 |
| POST | `/api/gift/give` | `ApiCrowdfundingController.java:60` | 资金/商城/钱包 |
| POST | `/api/gift/list` | `ApiGiftController.java:36` | 资金/商城/钱包 |
| POST | `/api/merchant/settlements/ledger` | `ApiMerchantController.java:1788` | 结算/预测结算资金域 |
| POST | `/api/merchant/predict/pending` | `ApiPredictSettleController.java:103` | 结算/预测结算资金域 |
| POST | `/api/product/list` | `ApiProductController.java:47` | 商城商品（小程序无商城） |
| POST | `/api/product/info` | `ApiProductController.java:79` | 商城商品（小程序无商城） |
| POST | `/api/product/like` | `ApiProductController.java:106` | 商城商品（小程序无商城） |
| POST | `/api/product/mylikelist` | `ApiProductController.java:152` | 商城商品（小程序无商城） |
| POST | `/api/registration/pay/app` | `ApiRegistrationController.java:269` | 支付/退款回调或 App 支付 |
| POST | `/api/registration/callback` | `ApiRegistrationController.java:1895` | 支付/退款回调或 App 支付 |
| POST | `/api/registration/refund-callback` | `ApiRegistrationController.java:1907` | 支付/退款回调或 App 支付 |
| POST | `/api/user/balance/statistics` | `ApiUmsMemberController.java:179` | 收益/余额资金域 |
| POST | `/api/user/income/breakdown` | `ApiUmsMemberController.java:303` | 收益/余额资金域 |
| POST | `/api/user/income/topic-stats` | `ApiUmsMemberController.java:324` | 收益/余额资金域 |
| POST | `/api/user/income/invite-stats` | `ApiUmsMemberController.java:345` | 收益/余额资金域 |
| POST | `/api/user/income/brand-stats` | `ApiUmsMemberController.java:366` | 收益/余额资金域 |
| POST | `/api/user/income/topic-list` | `ApiUmsMemberController.java:387` | 收益/余额资金域 |
| POST | `/api/user/income/brand-list` | `ApiUmsMemberController.java:417` | 收益/余额资金域 |
| POST | `/api/wallet/balance` | `ApiWalletController.java:46` | 资金/商城/钱包 |
| POST | `/api/wallet/withdraw` | `ApiWalletController.java:72` | 资金/商城/钱包 |
| POST | `/api/wallet/withdraws` | `ApiWalletController.java:89` | 资金/商城/钱包 |
| POST | `/api/withdrawal/delete` | `ApiWithdrawalController.java:111` | 资金/商城/钱包 |

### S. 非小程序调用方（回调 / App 端 / 系统）（12）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| POST | `/api/common/upload` | `ApiCommonController.java:168` | 旧上传口（小程序走 /api/common/uploadOSS） |
| POST | `/api/common/uploads` | `ApiCommonController.java:192` | 旧上传口（小程序走 /api/common/uploadOSS） |
| GET | `/api/content-sec/callback` | `ApiContentSecurityController.java:44` | 微信内容安全回调（服务端被调） |
| POST | `/api/content-sec/callback` | `ApiContentSecurityController.java:70` | 微信内容安全回调（服务端被调） |
| POST | `/api/login` | `ApiLoginController.java:112` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/userInfo` | `ApiLoginController.java:317` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/register` | `ApiLoginController.java:444` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/login/phone` | `ApiLoginController.java:524` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/login/wechat/app` | `ApiLoginController.java:617` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/login/apple` | `ApiLoginController.java:757` | App/账号密码端登录（小程序走 /api/login/code） |
| POST | `/api/findpass` | `ApiLoginController.java:866` | App/账号密码端登录（小程序走 /api/login/code） |
| GET | `/api/status` | `ApiServiceStatusController.java:19` | 服务健康检查 |

### D. 宿主在 Codex（独占文件或运行时）（61）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| POST | `/api/activity/like_list` | `ApiActivityController.java:355` | 唯一入口 mylike.js（Codex）（#139） |
| POST | `/api/activity/like` | `ApiActivityController.java:705` | 唯一入口 mylike.js（Codex）（#139） |
| POST | `/api/ai/theme/merchant-plan` | `ApiAiController.java:129` | AI 网关（Codex） |
| POST | `/api/ai/node/generate` | `ApiAiController.java:187` | AI 网关（Codex） |
| POST | `/api/ai/template/fill` | `ApiAiController.java:207` | AI 网关（Codex） |
| POST | `/api/ai/chapter/overview` | `ApiAiController.java:230` | AI 网关（Codex） |
| POST | `/api/ai/merchant/task-suggest` | `ApiAiController.java:270` | AI 网关（Codex） |
| GET | `/api/v1/community/capabilities` | `ApiCommunityPostController.java:35` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/media/register` | `ApiCommunityPostController.java:39` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts` | `ApiCommunityPostController.java:44` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| PATCH | `/api/v1/community/posts/{id}` | `ApiCommunityPostController.java:49` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts/{id}/publish` | `ApiCommunityPostController.java:54` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/posts/{id}` | `ApiCommunityPostController.java:59` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/posts/{id}/location` | `ApiCommunityPostController.java:64` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/posts/{id}` | `ApiCommunityPostController.java:70` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/drafts` | `ApiCommunityPostController.java:78` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/posts/{id}/revisions` | `ApiCommunityPostController.java:84` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/reference-options/{type}` | `ApiCommunityPostController.java:89` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/feeds/{mode}` | `ApiCommunityPostController.java:95` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts/{id}/actions` | `ApiCommunityPostController.java:116` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/posts/{id}/actions/{type}` | `ApiCommunityPostController.java:121` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts/{id}/comments` | `ApiCommunityPostController.java:128` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/posts/{id}/comments` | `ApiCommunityPostController.java:133` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts/{postId}/comments/{commentId}/approve` | `ApiCommunityPostController.java:142` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/posts/{postId}/comments/{commentId}` | `ApiCommunityPostController.java:148` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/posts/{id}/shares` | `ApiCommunityPostController.java:154` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/comments/{id}/actions` | `ApiCommunityPostController.java:162` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/comments/{id}/actions/LIKE` | `ApiCommunityPostController.java:167` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/members/{memberId}/mute` | `ApiCommunityPostController.java:174` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| DELETE | `/api/v1/community/members/{memberId}/mute` | `ApiCommunityPostController.java:179` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/guidelines/active` | `ApiCommunityPostController.java:186` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/guidelines/ack` | `ApiCommunityPostController.java:193` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/reports` | `ApiCommunityPostController.java:198` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/appeals` | `ApiCommunityPostController.java:203` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/me/enforcements` | `ApiCommunityPostController.java:208` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| GET | `/api/v1/community/notification-preferences` | `ApiCommunityPostController.java:220` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| PATCH | `/api/v1/community/notification-preferences` | `ApiCommunityPostController.java:225` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/v1/community/notifications/{id}/read` | `ApiCommunityPostController.java:231` | CommunityPostServiceImpl 及 policy 为 Codex 独占；小程序发帖不走 v1（见 #106/#107） |
| POST | `/api/content-draft/save` | `ApiContentDraftController.java:25` | 云端草稿恢复需接 Codex 编辑器（#140） |
| POST | `/api/content-draft/restore` | `ApiContentDraftController.java:30` | 云端草稿恢复需接 Codex 编辑器（#140） |
| POST | `/api/content-draft/list` | `ApiContentDraftController.java:36` | 云端草稿恢复需接 Codex 编辑器（#140） |
| POST | `/api/content-draft/delete` | `ApiContentDraftController.java:41` | 云端草稿恢复需接 Codex 编辑器（#140） |
| POST | `/api/content-draft/publish` | `ApiContentDraftController.java:47` | 云端草稿恢复需接 Codex 编辑器（#140） |
| POST | `/api/play/check/rest` | `ApiPlayCheckController.java:71` | 宿主 pages/play/index.js（Codex） |
| POST | `/api/play/journey/ask` | `ApiPlayJourneyActionController.java:39` | 会话/路线运行时（Codex）；#135/#136 BLOCKED |
| POST | `/api/play/journey/call` | `ApiPlayJourneyActionController.java:54` | 会话/路线运行时（Codex）；#135/#136 BLOCKED |
| GET | `/api/play/journey/stage-end` | `ApiPlayJourneyController.java:27` | 会话/路线运行时（Codex）；#135/#136 BLOCKED |
| GET | `/api/play/journey/casebook` | `ApiPlayJourneyReadController.java:31` | 会话/路线运行时（Codex）；#135/#136 BLOCKED |
| GET | `/api/play/journey/backpack` | `ApiPlayJourneyReadController.java:44` | 会话/路线运行时（Codex）；#135/#136 BLOCKED |
| POST | `/api/play/sensor-result` | `ApiPlayProgressController.java:1861` | 宿主 pages/play/index.js（Codex） |
| POST | `/api/registration/verify_answer` | `ApiRegistrationController.java:683` | 答题校验属 play 运行时（Codex） |
| POST | `/api/spoiler/state` | `ApiSpoilerController.java:26` | 宿主 pages/play（Codex）；preference 见 #127 |
| POST | `/api/spoiler/preference` | `ApiSpoilerController.java:39` | 宿主 pages/play（Codex）；preference 见 #127 |
| POST | `/api/spoiler/reveal` | `ApiSpoilerController.java:50` | 宿主 pages/play（Codex）；preference 见 #127 |
| POST | `/api/play/theme/replay` | `ApiThemeRuntimeController.java:23` | 宿主 pages/play/index.js（Codex）；#137/#161 BLOCKED |
| GET | `/api/play/theme/card` | `ApiThemeRuntimeController.java:25` | 宿主 pages/play/index.js（Codex）；#137/#161 BLOCKED |
| GET | `/api/play/theme/history` | `ApiThemeRuntimeController.java:27` | 宿主 pages/play/index.js（Codex）；#137/#161 BLOCKED |
| POST | `/api/topic/publish-draft` | `ApiTopicController.java:1079` | ApiTopicController / 发布编辑器（Codex）；#145/#149 |
| POST | `/api/topic/xp-budget` | `ApiTopicController.java:1734` | ApiTopicController / 发布编辑器（Codex）；#145/#149 |
| POST | `/api/topic/list-to-merchant` | `ApiTopicController.java:2086` | ApiTopicController / 发布编辑器（Codex）；#145/#149 |
| GET | `/api/topic/publish-check` | `ApiTopicStateKeyController.java:164` | ApiTopicController / 发布编辑器（Codex）；#145/#149 |

### E. 已核实 BLOCKED 或已有等价实现（需产品决定才会接）（23）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| GET | `/api/agreement/get` | `ApiAgreementController.java:35` | versions 多版本回旧值、本地正文不同源（#131） |
| GET | `/api/agreement/versions` | `ApiAgreementController.java:49` | versions 多版本回旧值、本地正文不同源（#131） |
| POST | `/api/agreement/consent` | `ApiAgreementController.java:55` | versions 多版本回旧值、本地正文不同源（#131） |
| POST | `/api/club/open-session-topics` | `ApiClubController.java:1267` | 现有「开一场」走 event-ops（Codex），改走 open-session 需产品决定（#165） |
| POST | `/api/club/open-session` | `ApiClubController.java:1284` | 现有「开一场」走 event-ops（Codex），改走 open-session 需产品决定（#165） |
| GET | `/api/community-trust/contributions/mine` | `ApiCommunityTrustController.java:119` | 「我的纠错」入口 BLOCKED（#110） |
| POST | `/api/community-trust/contributions/supplement` | `ApiCommunityTrustController.java:130` | 「我的纠错」入口 BLOCKED（#110） |
| POST | `/api/creativesquare/summary` | `ApiCreativeSquareController.java:1112` | 无现成头部统计块（#146） |
| POST | `/api/creator/center` | `ApiCreatorCenterController.java:26` | 无创作者信息块，申请/收益需决定（#153） |
| POST | `/api/creator/apply` | `ApiCreatorCenterController.java:38` | 无创作者信息块，申请/收益需决定（#153） |
| POST | `/api/discovery-filter/schema` | `ApiDiscoveryFilterController.java:14` | 现有筛选已等价（#155） |
| POST | `/api/world/home` | `ApiIpWorldController.java:26` | 无 IP 世界入口（#156） |
| POST | `/api/world/quests` | `ApiIpWorldController.java:35` | 无 IP 世界入口（#156） |
| POST | `/api/merchant/funnel` | `ApiMerchantController.java:1647` | UI-09 已删除经营事件区（#129） |
| POST | `/api/merchant/orders` | `ApiMerchantController.java:1679` | 工作台已有台账/售后；口径不等价（#148/#157） |
| POST | `/api/merchant/orders/ledger` | `ApiMerchantController.java:1701` | 工作台已有台账/售后；口径不等价（#148/#157） |
| POST | `/api/merchant/customers` | `ApiMerchantController.java:1773` | 客户名册已用 CRM 接口（#121） |
| GET | `/api/merchant/ops/public-detail` | `ApiMerchantOpsController.java:36` | 现有行已等价，多出电话/官网需隐私决定（#168） |
| POST | `/api/official/invites` | `ApiOfficialEventController.java:193` | 只回原始 ID（#115） |
| GET | `/api/official/invites` | `ApiOfficialEventController.java:212` | 只回原始 ID（#115） |
| POST | `/api/official/v2/drafts` | `ApiOfficialEventController.java:260` | 草稿全量覆盖无读合同（#79） |
| POST | `/api/play/achievements` | `ApiPlayProgressController.java:3342` | 碎片/彩蛋无现有位置（#138 只补里程，数据取 growth） |
| POST | `/api/recommendation/list` | `ApiRecommendationController.java:27` | 首页推荐位已有运营数据源（#154） |

### X. 无现成宿主、尚未立卡（需产品决定）（34）

| 方法 | 路径 | 源码 | 依据 |
|---|---|---|---|
| POST | `/api/betagame/list` | `ApiBetaGameController.java:56` | 内测游戏（含投票）无入口；vote 属投票类 |
| POST | `/api/betagame/info` | `ApiBetaGameController.java:68` | 内测游戏（含投票）无入口；vote 属投票类 |
| POST | `/api/betagame/vote` | `ApiBetaGameController.java:112` | 内测游戏（含投票）无入口；vote 属投票类 |
| POST | `/api/club/apply` | `ApiClubController.java:414` | 俱乐部「填完即有」走 create，无申请/审核态（#111） |
| POST | `/api/club/my-apply` | `ApiClubController.java:448` | 俱乐部「填完即有」走 create，无申请/审核态（#111） |
| POST | `/api/club/dissolution-blockers` | `ApiClubController.java:931` | 尚未逐条核实宿主 |
| POST | `/api/club/set-member-role` | `ApiClubController.java:1668` | 尚未逐条核实宿主 |
| POST | `/api/coop/offer/circle-supply/reconfirm` | `ApiCoopController.java:728` | 小程序已用 /reconfirm-current，此为旧口 |
| POST | `/api/coop/candidates` | `ApiCoopController.java:1064` | 尚未逐条核实宿主 |
| POST | `/api/growth/mission/complete` | `ApiGrowthController.java:51` | 尚未逐条核实宿主 |
| POST | `/api/merchant/business-status` | `ApiMerchantController.java:305` | 小程序已用 /business-status/update；此读口未用 |
| GET | `/api/merchant/crm/campaigns/coupons` | `ApiMerchantCrmController.java:74` | 触达任务写入/投递；#144 明确只做只读列表/详情，写操作需产品决定 |
| POST | `/api/merchant/crm/campaigns/preview` | `ApiMerchantCrmController.java:83` | 触达任务写入/投递；#144 明确只做只读列表/详情，写操作需产品决定 |
| POST | `/api/merchant/crm/campaigns` | `ApiMerchantCrmController.java:92` | 触达任务写入/投递；#144 明确只做只读列表/详情，写操作需产品决定 |
| POST | `/api/merchant/crm/campaigns/{campaignId}/dispatch` | `ApiMerchantCrmController.java:101` | 触达任务写入/投递；#144 明确只做只读列表/详情，写操作需产品决定 |
| POST | `/api/merchant/crm/campaigns/{campaignId}/retry` | `ApiMerchantCrmController.java:110` | 触达任务写入/投递；#144 明确只做只读列表/详情，写操作需产品决定 |
| POST | `/api/merchant/crm/broadcast/preview` | `ApiMerchantCrmController.java:137` | 商家群发/画像对账写操作，无现成入口 |
| POST | `/api/merchant/crm/broadcast` | `ApiMerchantCrmController.java:146` | 商家群发/画像对账写操作，无现成入口 |
| POST | `/api/merchant/crm/profiles/reconcile` | `ApiMerchantCrmController.java:164` | 商家群发/画像对账写操作，无现成入口 |
| POST | `/api/official/publish` | `ApiOfficialEventController.java:242` | 尚未逐条核实宿主 |
| POST | `/api/official/broadcast` | `ApiOfficialEventController.java:330` | 尚未逐条核实宿主 |
| GET | `/api/preset/style/list` | `ApiPresetController.java:29` | 尚未逐条核实宿主 |
| GET | `/api/roam/tiles` | `ApiRoamController.java:437` | 小程序已用分页版 /api/roam/tiles/page，此为旧全量口 |
| POST | `/api/roam/hangout/create` | `ApiRoamHangoutController.java:57` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| GET | `/api/roam/hangout/detail` | `ApiRoamHangoutController.java:66` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| POST | `/api/roam/hangout/join` | `ApiRoamHangoutController.java:79` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| POST | `/api/roam/hangout/leave` | `ApiRoamHangoutController.java:85` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| POST | `/api/roam/hangout/close` | `ApiRoamHangoutController.java:99` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| POST | `/api/roam/hangout/report` | `ApiRoamHangoutController.java:113` | 漫游约伴：小程序只读 /nearby，发起/加入/举报流程无宿主页 |
| POST | `/api/track/draft` | `ApiTrackController.java:60` | 轨迹草稿/转主题：漫游页无保存轨迹入口；转主题会进发布编辑器（Codex） |
| POST | `/api/track/drafts` | `ApiTrackController.java:88` | 轨迹草稿/转主题：漫游页无保存轨迹入口；转主题会进发布编辑器（Codex） |
| POST | `/api/track/to-topic` | `ApiTrackController.java:102` | 轨迹草稿/转主题：漫游页无保存轨迹入口；转主题会进发布编辑器（Codex） |
| POST | `/api/track/delete` | `ApiTrackController.java:170` | 轨迹草稿/转主题：漫游页无保存轨迹入口；转主题会进发布编辑器（Codex） |
| POST | `/api/user/statistics_chat` | `ApiUmsMemberController.java:276` | 尚未逐条核实宿主 |

## 复跑

```bash
# 1) 基线零调用表
node docs/plans/mini-claude-ui-20261015/ZERO-CALL-scan.js . > /tmp/zc.tsv
# 2) 各 open PR 新增的接口字面量（PR 号 + 分支名列表自备）
B=origin/claude/mini-ui-features-20261015
while read n b; do
  git diff "$B...origin/$b" -- chengyinhub-xcx ':!chengyinhub-xcx/tests' ':!chengyinhub-xcx/scripts' \
    | grep '^+' | grep -oE "['\"\`]/?api/[^'\"\` ]*" | sed "s/^./$n\t/"
done < prs.txt | sort -u > /tmp/pr-lits.tsv
# 3) 用 /tmp/zc.tsv 的路径（{var} 当作单段通配）匹配 /tmp/pr-lits.tsv；只按前缀命中的需人工复核
```
