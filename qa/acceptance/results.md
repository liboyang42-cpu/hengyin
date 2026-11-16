# Acceptance run 2026-11-16T20:29:22Z against http://127.0.0.1:8120

| Flow | Verdict | Pass / fail / TEST-SKIP |
|---|---|---|
| F01 Sign-in | PASS | 8 / 0 / 0 |
| F02 Browse home | PASS | 10 / 0 / 0 |
| F03 Activity detail | PASS | 6 / 0 / 0 |
| F04 Register and pay | PASS | 10 / 0 / 1 |
| F05 Ticket QR | PASS | 5 / 0 / 0 |
| F06 Check-in | FAIL | 10 / 1 / 0 |
| F07 Play game: answer, hint, finish | PASS | 16 / 0 / 0 |
| F08 Roam walk and fog reveal | PASS | 10 / 0 / 0 |
| F09 City Monopoly claim, rent, challenge | PASS | 11 / 0 / 0 |
| F10 Club join and chat | PASS | 9 / 0 / 0 |
| F11 Messages and group chat | PASS | 12 / 0 / 0 |
| F12 Publish a topic | PASS | 5 / 0 / 0 |
| F13 Publish an activity | PASS | 6 / 0 / 0 |
| F14 Merchant scan-to-verify | PASS | 9 / 0 / 0 |
| F15 Merchant refund | PASS | 7 / 0 / 0 |
| F16 Creator earnings | PASS | 8 / 0 / 0 |
| F17 Withdraw (TEST) | PASS | 11 / 0 / 0 |
| F18 Account deletion | PASS | 10 / 0 / 0 |

| Flow | Step | Request | HTTP | code | ms | Result | Note |
|---|---|---|---|---|---|---|---|
| F01 Sign-in | sms/send | `POST /api/sms/send` | 200 | 200 | 10 | PASS |  |
| F01 Sign-in | login/phone bad code rejected | `POST /api/login/phone` | 200 | 500 | 8 | PASS |  |
| F01 Sign-in | userInfo (top-level appUser.role) | `POST /api/userInfo` | 200 | 200 | 11 | PASS |  |
| F01 Sign-in | user/info | `POST /api/user/info` | 200 | 200 | 40 | PASS |  |
| F01 Sign-in | userInfo club leader role | `POST /api/userInfo` | 200 | 200 | 19 | PASS |  |
| F01 Sign-in | userInfo merchant role | `POST /api/userInfo` | 200 | 200 | 12 | PASS |  |
| F01 Sign-in | login/code with e2e-code test code | `POST /api/login/code` | 200 | 200 | 59 | PASS |  |
| F01 Sign-in | userInfo without token -> 401 | `POST /api/userInfo` | 200 | 401 | 17 | PASS |  |
| F02 Browse home | common/banner | `POST /api/common/banner` | 200 | 200 | 22 | PASS |  |
| F02 Browse home | topic/list main feed | `POST /api/topic/list` | 200 | 200 | 139 | PASS |  |
| F02 Browse home | topic/list recommended | `POST /api/topic/list` | 200 | 200 | 38 | PASS |  |
| F02 Browse home | activity/list upcoming page 1 | `POST /api/activity/list` | 200 | 200 | 114 | PASS |  |
| F02 Browse home | activity/list upcoming (all pages): club-run 992031 stays off the public shelf | `POST /api/activity/list` |  |  |  | PASS | checked every page |
| F02 Browse home | activity/list nearby | `POST /api/activity/list` | 200 | 200 | 45 | PASS |  |
| F02 Browse home | activity/list stream | `POST /api/activity/list` | 200 | 200 | 47 | PASS |  |
| F02 Browse home | play/run-session/list | `GET /api/play/run-session/list` | 200 | 200 | 21 | PASS |  |
| F02 Browse home | registration/my-joined holds 992031 | `POST /api/registration/my-joined` | 200 | 200 | 62 | PASS |  |
| F02 Browse home | anonymous browse: activity/list | `POST /api/activity/list` | 200 | 200 | 31 | PASS |  |
| F03 Activity detail | activity/info 992031 (anonymous) -> club gate | `POST /api/activity/info` | 200 | 200 | 45 | PASS |  |
| F03 Activity detail | activity/info 992031 (signed in) | `POST /api/activity/info` | 200 | 200 | 78 | PASS |  |
| F03 Activity detail | topic/info-to-user 992001 | `POST /api/topic/info-to-user` | 200 | 200 | 181 | PASS |  |
| F03 Activity detail | registration/payment-readiness | `POST /api/registration/payment-readiness` | 200 | 200 | 10 | PASS |  |
| F03 Activity detail | play/nodes preview | `GET /api/play/nodes` | 200 | 200 | 353 | PASS |  |
| F03 Activity detail | activity/info unknown id | `POST /api/activity/info` | 200 | 500 | 25 | PASS |  |
| F04 Register and pay | consent activity_signup | `POST /api/compliance/consents` | 200 | 200 | 33 | PASS |  |
| F04 Register and pay | user/address/action participant | `POST /api/user/address/action` | 200 | 200 | 36 | PASS |  |
| F04 Register and pay | registration/quote (native shape) | `POST /api/registration/quote` | 200 | 200 | 54 | PASS |  |
| F04 Register and pay | registration/create free | `POST /api/registration/create` | 200 | 200 | 483 | PASS |  |
| F04 Register and pay | registration/create replay is idempotent | `POST /api/registration/create` | 200 | 200 | 14 | PASS |  |
| F04 Register and pay | registration/info paid (free = paid) | `POST /api/registration/info` | 200 | 200 | 65 | PASS |  |
| F04 Register and pay | registration/list all orders | `POST /api/registration/list` | 200 | 200 | 157 | PASS |  |
| F04 Register and pay | payment-readiness reports TEST pay unavailable | `POST /api/registration/payment-readiness` | 200 | 200 | 12 | PASS |  |
| F04 Register and pay | paid quote | `POST /api/registration/quote` | 200 | 200 | 40 | PASS |  |
| F04 Register and pay | paid create refused cleanly while TEST pay is unavailable | `POST /api/registration/create` | 200 | 503 | 37 | PASS |  |
| F04 Register and pay | paid create -> pending order -> pay/app payParams | `POST /api/registration/pay/app` |  |  |  | TEST-SKIP | TEST has no pay sandbox: payment-readiness says '支付服务暂不可用' (no WeChat Pay merchant config on be-int; the only stub, scripts/e2e/E2eWechatPayStub.java.stub, is compiled into an exploded jar by hand and is not in the TEST jar) |
| F05 Ticket QR | registration/list ticket wallet (owner_type 2) | `POST /api/registration/list` | 200 | 200 | 42 | PASS |  |
| F05 Ticket QR | wallet decodes as the native client does ({total, rows}) | `POST /api/registration/list` | 200 | 200 | 44 | PASS |  |
| F05 Ticket QR | registration/info ticket detail | `POST /api/registration/info` | 200 | 200 | 29 | PASS |  |
| F05 Ticket QR | verify/dyncode/issue | `POST /api/verify/dyncode/issue` | 200 | 200 | 41 | PASS |  |
| F05 Ticket QR | dyncode/issue for someone else's order is refused | `POST /api/verify/dyncode/issue` | 200 | 500 | 16 | PASS |  |
| F06 Check-in | player dyncode/issue | `POST /api/verify/dyncode/issue` | 200 | 200 | 32 | PASS |  |
| F06 Check-in | merchant 9002 (not owner) scan refused | `POST /api/registration/scan_dynamic_code` | 200 | 500 | 31 | PASS |  |
| F06 Check-in | owner 9003 scan_dynamic_code | `POST /api/registration/scan_dynamic_code` | 200 | 200 | 87 | PASS |  |
| F06 Check-in | rescan reports already verified | `POST /api/registration/scan_dynamic_code` | 200 | 500 | 22 | PASS |  |
| F06 Check-in | self check-in: play/action-context node 992021 | `GET /api/play/action-context` | 200 | 200 | 19 | PASS |  |
| F06 Check-in | self check-in: play/advanced/start node 992021 | `POST /api/play/advanced/start` | 200 | 200 | 152 | PASS |  |
| F06 Check-in | self check-in: play/advanced/action SUBMIT_QA | `POST /api/play/advanced/action` | 200 | 200 | 82 | PASS |  |
| F06 Check-in | self check-in: play/action-context again | `GET /api/play/action-context` | 200 | 200 | 15 | PASS |  |
| F06 Check-in | self check-in as the native client sends it (no nodeId) -> app defect A5 | `POST /api/play/checkin` | 200 | 500 | 21 | FAIL | code 500 not in [200] |
| F06 Check-in | self check-in play/checkin (backend contract: action context + nodeId) | `POST /api/play/checkin` | 200 | 200 | 273 | PASS |  |
| F06 Check-in | self check-in replay (same routeActionId) is idempotent | `POST /api/play/checkin` | 200 | 200 | 22 | PASS |  |
| F07 Play game: answer, hint, finish | play/nodes (registered player) | `GET /api/play/nodes` | 200 | 200 | 99 | PASS |  |
| F07 Play game: answer, hint, finish | play/nodes (unregistered 9009 is not playable) | `GET /api/play/nodes` | 200 | 200 | 62 | PASS |  |
| F07 Play game: answer, hint, finish | play/route-state ACTIVE | `GET /api/play/route-state` | 200 | 200 | 35 | PASS |  |
| F07 Play game: answer, hint, finish | play/action-context node 992021 | `GET /api/play/action-context` | 200 | 200 | 16 | PASS |  |
| F07 Play game: answer, hint, finish | play/help ladder | `GET /api/play/help` | 200 | 200 | 60 | PASS |  |
| F07 Play game: answer, hint, finish | play/help/use level 1 | `POST /api/play/help/use` | 200 | 200 | 57 | PASS |  |
| F07 Play game: answer, hint, finish | play/help/use replay is idempotent (no charge) | `POST /api/play/help/use` | 200 | 200 | 35 | PASS |  |
| F07 Play game: answer, hint, finish | advanced qa: play/advanced/start node 992021 | `POST /api/play/advanced/start` | 200 | 200 | 52 | PASS |  |
| F07 Play game: answer, hint, finish | advanced qa: play/advanced/action SUBMIT_QA | `POST /api/play/advanced/action` | 200 | 200 | 51 | PASS |  |
| F07 Play game: answer, hint, finish | play/action-context after the advanced qa | `GET /api/play/action-context` | 200 | 200 | 14 | PASS |  |
| F07 Play game: answer, hint, finish | play/answer node 992021 (native shape, with action context) | `POST /api/play/answer` | 200 | 200 | 159 | PASS |  |
| F07 Play game: answer, hint, finish | play/answer replay (same routeActionId) returns the original outcome | `POST /api/play/answer` | 200 | 200 | 23 | PASS |  |
| F07 Play game: answer, hint, finish | play/action-receipt | `GET /api/play/action-receipt` | 200 | 200 | 12 | PASS |  |
| F07 Play game: answer, hint, finish | play/help/receipt | `GET /api/play/help/receipt` | 200 | 200 | 43 | PASS |  |
| F07 Play game: answer, hint, finish | play/route-state after the answer | `GET /api/play/route-state` | 200 | 200 | 28 | PASS |  |
| F07 Play game: answer, hint, finish | play/ending GET (as the native client reads it) | `GET /api/play/ending` | 200 | 200 | 18 | PASS |  |
| F08 Roam walk and fog reveal | consents/latest roam_location | `POST /api/compliance/consents/latest` | 200 | 200 | 14 | PASS |  |
| F08 Roam walk and fog reveal | roam/reveal (new session) | `POST /api/roam/reveal` | 200 | 200 | 37 | PASS |  |
| F08 Roam walk and fog reveal | roam/reveal replay reveals nothing new | `POST /api/roam/reveal` | 200 | 200 | 16 | PASS |  |
| F08 Roam walk and fog reveal | roam/presence heartbeat | `POST /api/roam/presence` | 200 | 200 | 20 | PASS |  |
| F08 Roam walk and fog reveal | roam/session | `GET /api/roam/session` | 200 | 200 | 9 | PASS |  |
| F08 Roam walk and fog reveal | roam/finish | `POST /api/roam/finish` | 200 | 200 | 45 | PASS |  |
| F08 Roam walk and fog reveal | roam/finish twice is refused/settled | `POST /api/roam/finish` | 200 | 500 | 12 | PASS |  |
| F08 Roam walk and fog reveal | roam/session/records | `POST /api/roam/session/records` | 200 | 200 | 18 | PASS |  |
| F08 Roam walk and fog reveal | roam/session/records with client-clock timestamp (clock skew) | `POST /api/roam/session/records` | 200 | 200 | 14 | PASS |  |
| F08 Roam walk and fog reveal | roam/tiles contains revealed tiles | `GET /api/roam/tiles` | 200 | 200 | 8 | PASS |  |
| F09 City Monopoly claim, rent, challenge | season/current | `GET /api/city-game/season/current` | 200 | 200 | 37 | PASS |  |
| F09 City Monopoly claim, rent, challenge | territory/map lists the QA board's properties | `GET /api/city-game/territory/map` | 200 | 200 | 17 | PASS |  |
| F09 City Monopoly claim, rent, challenge | territory/points (balance HUD, 积分) | `GET /api/city-game/territory/points` | 200 | 200 | 25 | PASS |  |
| F09 City Monopoly claim, rent, challenge | claim/quote | `POST /api/city-game/territory/claim/quote` | 200 | 200 | 45 | PASS |  |
| F09 City Monopoly claim, rent, challenge | claim/command | `POST /api/city-game/territory/claim/command` | 200 | 200 | 86 | PASS |  |
| F09 City Monopoly claim, rent, challenge | visitor consents/latest roam_location | `POST /api/compliance/consents/latest` | 200 | 200 | 11 | PASS |  |
| F09 City Monopoly claim, rent, challenge | rent: visitor check-in on the claimed property (POINTS) | `POST /api/roam/territory/checkin` | 200 | 200 | 49 | PASS |  |
| F09 City Monopoly claim, rent, challenge | challenge/quote on a freshly claimed property is refused (protection) | `POST /api/city-game/territory/challenge/quote` | 409 | 409 | 24 | PASS |  |
| F09 City Monopoly claim, rent, challenge | challenge/quote on the defended property | `POST /api/city-game/territory/challenge/quote` | 200 | 200 | 24 | PASS |  |
| F09 City Monopoly claim, rent, challenge | challenge/command (server d20) | `POST /api/city-game/territory/challenge/command` | 200 | 200 | 55 | PASS |  |
| F09 City Monopoly claim, rent, challenge | territory/revenue (owner) | `GET /api/city-game/territory/revenue` | 200 | 200 | 12 | PASS |  |
| F10 Club join and chat | club/list (JSON) | `POST /api/club/list` | 200 | 200 | 53 | PASS |  |
| F10 Club join and chat | club/detail | `POST /api/club/detail` | 200 | 200 | 32 | PASS |  |
| F10 Club join and chat | club/join | `POST /api/club/join` | 200 | 200 | 27 | PASS |  |
| F10 Club join and chat | club/join-requests (leader) | `POST /api/club/join-requests` | 200 | 200 | 20 | PASS |  |
| F10 Club join and chat | club/join-request/approve (leader) | `POST /api/club/join-request/approve` | 200 | 400 | 12 | PASS |  |
| F10 Club join and chat | club/members lists joiner | `POST /api/club/members` | 200 | 200 | 17 | PASS |  |
| F10 Club join and chat | club/chat opens group conversation | `POST /api/club/chat` | 200 | 200 | 21 | PASS |  |
| F10 Club join and chat | im/send to club chat | `POST /api/im/send` | 200 | 200 | 49 | PASS |  |
| F10 Club join and chat | im/messages in club chat | `POST /api/im/messages` | 200 | 200 | 25 | PASS |  |
| F11 Messages and group chat | im/conversations | `POST /api/im/conversations` | 200 | 200 | 89 | PASS |  |
| F11 Messages and group chat | im/start DM 9004->9009 | `POST /api/im/start` | 200 | 200 | 11 | PASS |  |
| F11 Messages and group chat | im/start again returns same conversation | `POST /api/im/start` | 200 | 200 | 8 | PASS |  |
| F11 Messages and group chat | im/send DM | `POST /api/im/send` | 200 | 200 | 14 | PASS |  |
| F11 Messages and group chat | im/send-receipt | `POST /api/im/send-receipt` | 200 | 200 | 15 | PASS |  |
| F11 Messages and group chat | im/messages (receiver) | `POST /api/im/messages` | 200 | 200 | 24 | PASS |  |
| F11 Messages and group chat | im/unread-total >= 1 (receiver) | `POST /api/im/unread-total` | 200 | 200 | 10 | PASS |  |
| F11 Messages and group chat | im/messages before read (receiver) | `POST /api/im/messages` | 200 | 200 | 17 | PASS |  |
| F11 Messages and group chat | im/read | `POST /api/im/read` | 200 | 200 | 26 | PASS |  |
| F11 Messages and group chat | club/chat (member 9004) opens the club 7001 group conversation | `POST /api/club/chat` | 200 | 200 | 22 | PASS |  |
| F11 Messages and group chat | im/send group (club 7001 chat) | `POST /api/im/send` | 200 | 200 | 25 | PASS |  |
| F11 Messages and group chat | im/messages group (member 9001) | `POST /api/im/messages` | 200 | 200 | 25 | PASS |  |
| F12 Publish a topic | publisher/identity/status | `POST /api/publisher/identity/status` | 200 | 200 | 10 | PASS |  |
| F12 Publish a topic | topic/create (save draft) | `POST /api/topic/create` | 200 | 200 | 122 | PASS |  |
| F12 Publish a topic | topic/publish-draft | `POST /api/topic/publish-draft` | 200 | 200 | 85 | PASS |  |
| F12 Publish a topic | topic/publish-draft replay is idempotent | `POST /api/topic/publish-draft` | 200 | 200 | 16 | PASS |  |
| F12 Publish a topic | play/nodes on new topic (no '路线尚未发布'; 402 = self-play pass gate) | `GET /api/play/nodes` | 200 | 402 | 18 | PASS |  |
| F13 Publish an activity | activity/publish_precheck | `POST /api/activity/publish_precheck` | 200 | 200 | 37 | PASS |  |
| F13 Publish an activity | activity/publish | `POST /api/activity/publish` | 200 | 200 | 108 | PASS |  |
| F13 Publish an activity | activity/publish replay (same requestId) is idempotent | `POST /api/activity/publish` | 200 | 200 | 16 | PASS |  |
| F13 Publish an activity | activity/info new activity | `POST /api/activity/info` | 200 | 200 | 29 | PASS |  |
| F13 Publish an activity | player cannot publish activity | `POST /api/activity/publish` | 200 | 500 | 46 | PASS |  |
| F13 Publish an activity | activity/delete cleanup | `POST /api/activity/delete` | 200 | 200 | 28 | PASS |  |
| F14 Merchant scan-to-verify | coupon/publish (merchant 9005, native JSON) | `POST /api/coupon/publish` | 200 | 200 | 32 | PASS |  |
| F14 Merchant scan-to-verify | coupon/myrecvlist (player) | `POST /api/coupon/myrecvlist` | 200 | 200 | 15 | PASS |  |
| F14 Merchant scan-to-verify | coupon/qr-token (player) | `POST /api/coupon/qr-token` | 200 | 200 | 17 | PASS |  |
| F14 Merchant scan-to-verify | coupon/verification (merchant 9002) | `POST /api/coupon/verification` | 200 | 200 | 27 | PASS |  |
| F14 Merchant scan-to-verify | coupon/verification replay | `POST /api/coupon/verification` | 200 | 200 | 20 | PASS |  |
| F14 Merchant scan-to-verify | coupon/verification by another merchant refused | `POST /api/coupon/verification` | 200 | 500 | 16 | PASS |  |
| F14 Merchant scan-to-verify | coupon/status used | `POST /api/coupon/status` | 200 | 200 | 9 | PASS |  |
| F14 Merchant scan-to-verify | merchant/verification-records | `POST /api/merchant/verification-records` | 200 | 200 | 14 | PASS |  |
| F14 Merchant scan-to-verify | coupon/stop the published coupon (cleanup, native multipart) | `POST /api/coupon/stop` | 200 | 200 | 15 | PASS |  |
| F15 Merchant refund | merchant/orders | `POST /api/merchant/orders` | 200 | 200 | 35 | PASS |  |
| F15 Merchant refund | merchant/aftercare/list PENDING | `POST /api/merchant/aftercare/list` | 200 | 200 | 29 | PASS |  |
| F15 Merchant refund | player cannot read merchant aftercare | `POST /api/merchant/aftercare/list` | 200 | 403 | 14 | PASS |  |
| F15 Merchant refund | player registration/cancel-refund (free order from F04) | `POST /api/registration/cancel-refund` | 200 | 200 | 43 | PASS |  |
| F15 Merchant refund | registration/info cancelled | `POST /api/registration/info` | 200 | 200 | 22 | PASS |  |
| F15 Merchant refund | merchant/aftercare/detail | `POST /api/merchant/aftercare/detail` | 200 | 200 | 23 | PASS |  |
| F15 Merchant refund | merchant/aftercare/respond AGREE | `POST /api/merchant/aftercare/respond` | 200 | 200 | 22 | PASS |  |
| F16 Creator earnings | creator/center | `POST /api/creator/center` | 200 | 200 | 14 | PASS |  |
| F16 Creator earnings | club/settlement/summary club 7001 | `POST /api/club/settlement/summary` | 200 | 200 | 23 | PASS |  |
| F16 Creator earnings | user/balance/list income | `POST /api/user/balance/list` | 200 | 200 | 41 | PASS |  |
| F16 Creator earnings | balance/list | `POST /api/balance/list` | 200 | 200 | 7 | PASS |  |
| F16 Creator earnings | wallet/stages (JSON) | `POST /api/wallet/stages` | 200 | 200 | 66 | PASS |  |
| F16 Creator earnings | merchant/finance/overview | `POST /api/merchant/finance/overview` | 200 | 200 | 20 | PASS |  |
| F16 Creator earnings | merchant/finance/settlement-entries | `POST /api/merchant/finance/settlement-entries` | 200 | 200 | 19 | PASS |  |
| F16 Creator earnings | player cannot read merchant finance | `POST /api/merchant/finance/overview` | 200 | 403 | 9 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/list | `POST /api/withdrawal/list` | 200 | 200 | 14 | PASS |  |
| F17 Withdraw (TEST) | wallet/stages | `POST /api/wallet/stages` | 200 | 200 | 26 | PASS |  |
| F17 Withdraw (TEST) | consent bank_account_collection | `POST /api/compliance/consents` | 200 | 200 | 11 | PASS |  |
| F17 Withdraw (TEST) | fund/preflight/bank-withdrawal | `POST /api/fund/preflight/bank-withdrawal` | 200 | 200 | 32 | PASS |  |
| F17 Withdraw (TEST) | fund/preflight confirm | `POST /api/fund/preflight/104/confirm` | 200 | 200 | 19 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/create | `POST /api/withdrawal/create` | 200 | 200 | 32 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/list has pending row | `POST /api/withdrawal/list` | 200 | 200 | 8 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/delete (undo) | `POST /api/withdrawal/delete` | 200 | 200 | 17 | PASS |  |
| F17 Withdraw (TEST) | over-balance preflight | `POST /api/fund/preflight/bank-withdrawal` | 200 | 200 | 17 | PASS |  |
| F17 Withdraw (TEST) | over-balance preflight confirm | `POST /api/fund/preflight/105/confirm` | 200 | 200 | 14 | PASS |  |
| F17 Withdraw (TEST) | withdraw over balance refused with clear msg | `POST /api/withdrawal/create` | 200 | 409 | 21 | PASS |  |
| F18 Account deletion | status NORMAL | `GET /api/user/deregister/status` | 200 | 200 | 8 | PASS |  |
| F18 Account deletion | precheck ELIGIBLE | `POST /api/user/deregister/precheck` | 200 | 200 | 19 | PASS |  |
| F18 Account deletion | precheck club leader BLOCKED | `POST /api/user/deregister/precheck` | 200 | 200 | 10 | PASS |  |
| F18 Account deletion | consent account_cancel | `POST /api/compliance/consents` | 200 | 200 | 10 | PASS |  |
| F18 Account deletion | sms/send for apply | `POST /api/sms/send` | 200 | 200 | 10 | PASS |  |
| F18 Account deletion | deregister/apply | `POST /api/user/deregister/apply` | 200 | 200 | 70 | PASS |  |
| F18 Account deletion | old token revoked after apply | `GET /api/user/deregister/status` | 401 | 401 | 26 | PASS |  |
| F18 Account deletion | deregister/result PENDING | `GET /api/user/deregister/result` | 200 | 200 | 12 | PASS |  |
| F18 Account deletion | deregister/cancel | `POST /api/user/deregister/cancel` | 200 | 200 | 19 | PASS |  |
| F18 Account deletion | status back to NORMAL | `GET /api/user/deregister/status` | 200 | 200 | 16 | PASS |  |
