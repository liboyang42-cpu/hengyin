# Acceptance run 2026-11-15T21:53:07Z against http://127.0.0.1:8120

| Flow | Verdict |
|---|---|
| F01 Sign-in | PASS |
| F02 Browse home | FAIL |
| F03 Activity detail | FAIL |
| F04 Register and pay | FAIL |
| F05 Ticket QR | FAIL |
| F06 Check-in | FAIL |
| F07 Play game: answer, hint, finish | FAIL |
| F08 Roam walk and fog reveal | PASS |
| F09 City Monopoly claim, rent, challenge | FAIL |
| F10 Club join and chat | FAIL |
| F11 Messages and group chat | FAIL |
| F12 Publish a topic | FAIL |
| F13 Publish an activity | FAIL |
| F14 Merchant scan-to-verify | FAIL |
| F15 Merchant refund | FAIL |
| F16 Creator earnings | FAIL |
| F17 Withdraw (TEST) | PASS |
| F18 Account deletion | FAIL |

| Flow | Step | Request | HTTP | code | ms | Result | Note |
|---|---|---|---|---|---|---|---|
| F01 Sign-in | sms/send | `POST /api/sms/send` | 200 | 200 | 5 | PASS |  |
| F01 Sign-in | login/phone bad code rejected | `POST /api/login/phone` | 200 | 500 | 2 | PASS |  |
| F01 Sign-in | userInfo (top-level appUser.role) | `POST /api/userInfo` | 200 | 200 | 4 | PASS |  |
| F01 Sign-in | user/info | `POST /api/user/info` | 200 | 200 | 7 | PASS |  |
| F01 Sign-in | userInfo club leader role | `POST /api/userInfo` | 200 | 200 | 4 | PASS |  |
| F01 Sign-in | userInfo merchant role | `POST /api/userInfo` | 200 | 200 | 2 | PASS |  |
| F01 Sign-in | login/code with e2e-code test code | `POST /api/login/code` | 200 | 200 | 10 | PASS |  |
| F01 Sign-in | userInfo without token -> 401 | `POST /api/userInfo` | 200 | 401 | 2 | PASS |  |
| F02 Browse home | common/banner | `POST /api/common/banner` | 200 | 200 | 4 | PASS |  |
| F02 Browse home | topic/list main feed | `POST /api/topic/list` | 200 | 200 | 22 | PASS |  |
| F02 Browse home | topic/list recommended | `POST /api/topic/list` | 200 | 200 | 11 | PASS |  |
| F02 Browse home | activity/list upcoming lists 992031 | `POST /api/activity/list` | 200 | 200 | 15 | FAIL | 992031 not in upcoming |
| F02 Browse home | activity/list nearby | `POST /api/activity/list` | 200 | 200 | 17 | PASS |  |
| F02 Browse home | activity/list stream | `POST /api/activity/list` | 200 | 200 | 14 | PASS |  |
| F02 Browse home | play/run-session/list | `GET /api/play/run-session/list` | 200 | 200 | 4 | PASS |  |
| F02 Browse home | registration/my-joined holds 992031 | `POST /api/registration/my-joined` | 200 | 200 | 9 | PASS |  |
| F02 Browse home | anonymous browse: activity/list | `POST /api/activity/list` | 200 | 200 | 13 | PASS |  |
| F03 Activity detail | activity/info 992031 (anonymous) | `POST /api/activity/info` | 200 | 200 | 4 | FAIL | topicId=None != 992001 |
| F03 Activity detail | activity/info 992031 (signed in) | `POST /api/activity/info` | 200 | 200 | 19 | PASS |  |
| F03 Activity detail | topic/info-to-user 992001 | `POST /api/topic/info-to-user` | 200 | 200 | 47 | PASS |  |
| F03 Activity detail | registration/payment-readiness | `POST /api/registration/payment-readiness` | 200 | 200 | 4 | PASS |  |
| F03 Activity detail | play/nodes preview | `GET /api/play/nodes` | 200 | 200 | 43 | PASS |  |
| F03 Activity detail | activity/info unknown id | `POST /api/activity/info` | 200 | 500 | 6 | PASS |  |
| F04 Register and pay | consent activity_signup | `POST /api/compliance/consents` | 200 | 200 | 5 | PASS |  |
| F04 Register and pay | user/address/action participant | `POST /api/user/address/action` | 200 | 200 | 6 | PASS |  |
| F04 Register and pay | registration/quote | `POST /api/registration/quote` | 200 | 200 | 13 | PASS |  |
| F04 Register and pay | registration/quote as the app sends it (no tickets/realName) | `POST /api/registration/quote` | 200 | 200 | 12 | PASS |  |
| F04 Register and pay | registration/create free | `POST /api/registration/create` | 200 | 200 | 670 | PASS |  |
| F04 Register and pay | registration/create replay is idempotent | `POST /api/registration/create` | 200 | 200 | 12 | PASS |  |
| F04 Register and pay | registration/info paid (free = paid) | `POST /api/registration/info` | 200 | 200 | 12 | PASS |  |
| F04 Register and pay | registration/list all orders | `POST /api/registration/list` | 200 | 200 | 33 | PASS |  |
| F04 Register and pay | payment-readiness (paid channel) | `POST /api/registration/payment-readiness` | 200 | 200 | 3 | FAIL | payment not ready on TEST: 支付服务暂不可用 |
| F04 Register and pay | paid quote | `POST /api/registration/quote` |  |  |  | SKIP | blocked: no paid ticket fixture on TEST (set E2E_PAID_ACTIVITY/E2E_PAID_TICKET) - data seeding needed |
| F04 Register and pay | paid create | `POST /api/registration/create` |  |  |  | SKIP | blocked: no paid ticket fixture on TEST (set E2E_PAID_ACTIVITY/E2E_PAID_TICKET) - data seeding needed |
| F04 Register and pay | pay/app returns payParams (TEST pay stub) | `POST /api/registration/pay/app` |  |  |  | SKIP | blocked: no paid ticket fixture on TEST (set E2E_PAID_ACTIVITY/E2E_PAID_TICKET) - data seeding needed |
| F05 Ticket QR | registration/list ticket wallet | `POST /api/registration/list` | 200 | 200 | 9 | PASS |  |
| F05 Ticket QR | wallet shape matches app (data is a list) | `POST /api/registration/list` | 200 | 200 | 10 | FAIL | data is dict, app expects List |
| F05 Ticket QR | registration/info ticket detail | `POST /api/registration/info` | 200 | 200 | 9 | PASS |  |
| F05 Ticket QR | verify/dyncode/issue | `POST /api/verify/dyncode/issue` | 200 | 500 | 6 | FAIL | code 500 not in [200] |
| F05 Ticket QR | dyncode/issue for someone else's order is refused | `POST /api/verify/dyncode/issue` | 200 | 500 | 4 | PASS |  |
| F06 Check-in | player dyncode/issue | `POST /api/verify/dyncode/issue` | 200 | 500 | 4 | FAIL | code 500 not in [200] |
| F06 Check-in | merchant 9002 (not owner) scan refused | `POST /api/registration/scan_dynamic_code` | 200 | 500 | 3 | PASS |  |
| F06 Check-in | owner 9003 scan_dynamic_code | `POST /api/registration/scan_dynamic_code` | 200 | 500 | 2 | FAIL | code 500 not in [200] |
| F06 Check-in | rescan reports already verified | `POST /api/registration/scan_dynamic_code` | 200 | 500 | 2 | FAIL | rescan not idempotent |
| F06 Check-in | player self check-in as the app sends it (play_api.dart:330) | `POST /api/play/checkin` | 200 | 500 | 4 | FAIL | code 500 not in [200] |
| F07 Play game: answer, hint, finish | play/nodes (registered player) | `GET /api/play/nodes` | 200 | 200 | 33 | FAIL | expected registered/playable/total=6 |
| F07 Play game: answer, hint, finish | play/nodes (unregistered 9004 is not playable) | `GET /api/play/nodes` | 200 | 200 | 44 | FAIL | 9004 should be registered=False |
| F07 Play game: answer, hint, finish | play/route-state | `GET /api/play/route-state` | 200 | 200 | 11 | PASS |  |
| F07 Play game: answer, hint, finish | play/action-context node 992021 | `GET /api/play/action-context` | 200 | 409 | 5 | FAIL | code 409 not in [200] |
| F07 Play game: answer, hint, finish | play/help ladder | `GET /api/play/help` | 200 | 400 | 3 | FAIL | code 400 not in [200] |
| F07 Play game: answer, hint, finish | play/help/use level 1 | `POST /api/play/help/use` | 200 | 400 | 3 | FAIL | code 400 not in [200] |
| F07 Play game: answer, hint, finish | play/help/use replay is idempotent (no charge) | `POST /api/play/help/use` | 200 | 400 | 3 | FAIL | code 400 not in [200] |
| F07 Play game: answer, hint, finish | play/answer node 992021 (with action context) | `POST /api/play/answer` | 200 | 500 | 3 | FAIL | code 500 not in [200] |
| F07 Play game: answer, hint, finish | play/answer as the app sends it (no routeSessionId) -> app gap | `POST /api/play/answer` | 200 | 500 | 2 | FAIL | code 500 not in [200] |
| F07 Play game: answer, hint, finish | play/action-receipt | `GET /api/play/action-receipt` | 200 | 520 | 5 | FAIL | code 520 not in [200] |
| F07 Play game: answer, hint, finish | play/help/receipt | `GET /api/play/help/receipt` | 200 | 400 | 2 | FAIL | code 400 not in [200] |
| F07 Play game: answer, hint, finish | play/ending GET | `GET /api/play/ending` | 200 | 200 | 4 | PASS |  |
| F07 Play game: answer, hint, finish | play/ending POST as the app calls it -> app/backend method mismatch | `POST /api/play/ending` | 200 | 405 | 3 | FAIL | code 405 not in [200] |
| F08 Roam walk and fog reveal | compliance consent AGREE roam_location | `POST /api/compliance/consents` | 200 | 409 | 4 | PASS |  |
| F08 Roam walk and fog reveal | roam/reveal (new session) | `POST /api/roam/reveal` | 200 | 200 | 8 | PASS |  |
| F08 Roam walk and fog reveal | roam/reveal replay reveals nothing new | `POST /api/roam/reveal` | 200 | 200 | 6 | PASS |  |
| F08 Roam walk and fog reveal | roam/presence heartbeat | `POST /api/roam/presence` | 200 | 200 | 5 | PASS |  |
| F08 Roam walk and fog reveal | roam/session | `GET /api/roam/session` | 200 | 200 | 4 | PASS |  |
| F08 Roam walk and fog reveal | roam/finish | `POST /api/roam/finish` | 200 | 200 | 17 | PASS |  |
| F08 Roam walk and fog reveal | roam/finish twice is refused/settled | `POST /api/roam/finish` | 200 | 500 | 4 | PASS |  |
| F08 Roam walk and fog reveal | roam/session/records | `POST /api/roam/session/records` | 200 | 200 | 6 | PASS |  |
| F08 Roam walk and fog reveal | roam/session/records with client-clock timestamp (clock skew) | `POST /api/roam/session/records` | 200 | 200 | 7 | PASS |  |
| F08 Roam walk and fog reveal | roam/tiles contains revealed tiles | `GET /api/roam/tiles` | 200 | 200 | 4 | PASS |  |
| F09 City Monopoly claim, rent, challenge | season/current | `GET /api/city-game/season/current` | 200 | 200 | 8 | PASS |  |
| F09 City Monopoly claim, rent, challenge | territory/map | `GET /api/city-game/territory/map` | 200 | 200 | 5 | PASS |  |
| F09 City Monopoly claim, rent, challenge | territory/points (balance HUD) | `GET /api/city-game/territory/points` | 200 | 200 | 5 | PASS |  |
| F09 City Monopoly claim, rent, challenge | claim/quote | `POST /api/city-game/territory/claim/quote` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F09 City Monopoly claim, rent, challenge | claim/command | `POST /api/city-game/territory/claim/command` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F09 City Monopoly claim, rent, challenge | rent: visitor checkin pays owner in points | `POST /api/roam/territory/checkin` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F09 City Monopoly claim, rent, challenge | challenge/quote | `POST /api/city-game/territory/challenge/quote` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F09 City Monopoly claim, rent, challenge | challenge/command | `POST /api/city-game/territory/challenge/command` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F09 City Monopoly claim, rent, challenge | territory/revenue (owner) | `GET /api/city-game/territory/revenue` |  |  |  | SKIP | blocked: no monopoly board/POI fixture on TEST (set E2E_MONOPOLY_POI) - data seeding needed |
| F10 Club join and chat | club/list (JSON) | `POST /api/club/list` | 200 | 200 | 31 | PASS |  |
| F10 Club join and chat | club/list as the app sends it (multipart) -> app/backend encoding | `POST /api/club/list` | 200 | 415 | 4 | FAIL | code 415 not in [200] |
| F10 Club join and chat | club/detail | `POST /api/club/detail` | 200 | 200 | 8 | PASS |  |
| F10 Club join and chat | club/join | `POST /api/club/join` | 200 | 200 | 7 | PASS |  |
| F10 Club join and chat | club/join-requests (leader) | `POST /api/club/join-requests` | 200 | 200 | 4 | PASS |  |
| F10 Club join and chat | club/join-request/approve (leader) | `POST /api/club/join-request/approve` | 200 | 400 | 3 | FAIL | code 400 not in [200, 500] |
| F10 Club join and chat | club/members lists joiner | `POST /api/club/members` | 200 | 200 | 4 | PASS |  |
| F10 Club join and chat | club/chat opens group conversation | `POST /api/club/chat` | 200 | 200 | 4 | PASS |  |
| F10 Club join and chat | im/send to club chat | `POST /api/im/send` | 200 | 200 | 11 | PASS |  |
| F10 Club join and chat | im/messages in club chat | `POST /api/im/messages` | 200 | 200 | 5 | PASS |  |
| F11 Messages and group chat | im/conversations | `POST /api/im/conversations` | 200 | 200 | 36 | PASS |  |
| F11 Messages and group chat | im/start DM 9004->9009 | `POST /api/im/start` | 200 | 500 | 4 | FAIL | code 500 not in [200] |
| F11 Messages and group chat | im/start again returns same conversation | `POST /api/im/start` | 200 | 500 | 3 | FAIL | code 500 not in [200] |
| F11 Messages and group chat | im/send DM | `POST /api/im/send` | 200 | 500 | 3 | FAIL | code 500 not in [200] |
| F11 Messages and group chat | im/send-receipt | `POST /api/im/send-receipt` | 200 | 403 | 4 | FAIL | code 403 not in [200] |
| F11 Messages and group chat | im/messages (receiver) | `POST /api/im/messages` | 200 | 403 | 2 | FAIL | code 403 not in [200] |
| F11 Messages and group chat | im/unread-total >= 1 (receiver) | `POST /api/im/unread-total` | 200 | 200 | 2 | PASS |  |
| F11 Messages and group chat | im/read | `POST /api/im/read` | 200 | 403 | 2 | FAIL | code 403 not in [200] |
| F11 Messages and group chat | im/send group (club_7001 conversation 3) | `POST /api/im/send` | 200 | 500 | 5 | FAIL | code 500 not in [200] |
| F11 Messages and group chat | im/messages group | `POST /api/im/messages` | 200 | 403 | 3 | FAIL | code 403 not in [200] |
| F12 Publish a topic | publisher/identity/status | `POST /api/publisher/identity/status` | 200 | 200 | 4 | PASS |  |
| F12 Publish a topic | topic/create (save draft) | `POST /api/topic/create` |  |  |  | SKIP | blocked: no APPROVED cover media asset on TEST (set E2E_COVER_ASSET_ID) - data seeding needed |
| F12 Publish a topic | topic/publish-draft | `POST /api/topic/publish-draft` |  |  |  | SKIP | blocked: no APPROVED cover media asset on TEST (set E2E_COVER_ASSET_ID) - data seeding needed |
| F12 Publish a topic | topic/publish-draft replay is idempotent | `POST /api/topic/publish-draft` |  |  |  | SKIP | blocked: no APPROVED cover media asset on TEST (set E2E_COVER_ASSET_ID) - data seeding needed |
| F12 Publish a topic | play/nodes on new topic (no '路线尚未发布') | `GET /api/play/nodes` |  |  |  | SKIP | blocked: no APPROVED cover media asset on TEST (set E2E_COVER_ASSET_ID) - data seeding needed |
| F13 Publish an activity | activity/publish_precheck | `POST /api/activity/publish_precheck` | 200 | 403 | 8 | FAIL | code 403 not in [200] |
| F13 Publish an activity | activity/publish | `POST /api/activity/publish` | 200 | 500 | 9 | FAIL | code 500 not in [200] |
| F13 Publish an activity | activity/publish replay (same requestId) is idempotent | `POST /api/activity/publish` | 200 | 500 | 6 | FAIL | code 500 not in [200] |
| F13 Publish an activity | activity/info new activity | `POST /api/activity/info` | 200 | 500 | 2 | FAIL | code 500 not in [200] |
| F13 Publish an activity | player cannot publish activity | `POST /api/activity/publish` | 200 | 500 | 5 | PASS |  |
| F13 Publish an activity | activity/delete cleanup | `POST /api/activity/delete` | 200 | 500 | 2 | FAIL | code 500 not in [200] |
| F14 Merchant scan-to-verify | coupon/publish (merchant) | `POST /api/coupon/publish` | 200 | 500 | 6 | FAIL | code 500 not in [200] |
| F14 Merchant scan-to-verify | coupon/myrecvlist (player) | `POST /api/coupon/myrecvlist` | 200 | 200 | 4 | PASS |  |
| F14 Merchant scan-to-verify | coupon/qr-token (player) | `POST /api/coupon/qr-token` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F14 Merchant scan-to-verify | coupon/verification (merchant) | `POST /api/coupon/verification` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F14 Merchant scan-to-verify | coupon/verification replay | `POST /api/coupon/verification` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F14 Merchant scan-to-verify | coupon/verification by another merchant refused | `POST /api/coupon/verification` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F14 Merchant scan-to-verify | coupon/status used | `POST /api/coupon/status` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F14 Merchant scan-to-verify | merchant/verification-records | `POST /api/merchant/verification-records` |  |  |  | SKIP | blocked: no granted coupon for player on TEST (set E2E_COUPON_HISTORY_ID) - data seeding needed |
| F15 Merchant refund | merchant/orders | `POST /api/merchant/orders` | 200 | 200 | 6 | PASS |  |
| F15 Merchant refund | merchant/aftercare/list PENDING | `POST /api/merchant/aftercare/list` | 200 | 200 | 6 | PASS |  |
| F15 Merchant refund | player cannot read merchant aftercare | `POST /api/merchant/aftercare/list` | 200 | 403 | 3 | PASS |  |
| F15 Merchant refund | merchant/aftercare/detail | `POST /api/merchant/aftercare/detail` |  |  |  | SKIP | blocked: no pending (status=0) refund_application for a 9002 activity on TEST - data seeding needed |
| F15 Merchant refund | merchant/aftercare/respond AGREE | `POST /api/merchant/aftercare/respond` |  |  |  | SKIP | blocked: no pending (status=0) refund_application for a 9002 activity on TEST - data seeding needed |
| F16 Creator earnings | creator/center | `POST /api/creator/center` | 200 | 200 | 5 | PASS |  |
| F16 Creator earnings | club/settlement/summary club 7001 | `POST /api/club/settlement/summary` | 200 | 200 | 20 | PASS |  |
| F16 Creator earnings | user/balance/list income | `POST /api/user/balance/list` | 200 | 200 | 7 | PASS |  |
| F16 Creator earnings | balance/list | `POST /api/balance/list` | 200 | 200 | 2 | PASS |  |
| F16 Creator earnings | wallet/stages (JSON) | `POST /api/wallet/stages` | 200 | 200 | 62 | PASS |  |
| F16 Creator earnings | wallet/stages form-encoded (app encoding?) | `POST /api/wallet/stages` | 200 | 415 | 3 | FAIL | code 415 not in [200] |
| F16 Creator earnings | merchant/finance/overview | `POST /api/merchant/finance/overview` | 200 | 200 | 9 | PASS |  |
| F16 Creator earnings | merchant/finance/settlement-entries | `POST /api/merchant/finance/settlement-entries` | 200 | 200 | 5 | PASS |  |
| F16 Creator earnings | player cannot read merchant finance | `POST /api/merchant/finance/overview` | 200 | 403 | 3 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/list | `POST /api/withdrawal/list` | 200 | 200 | 6 | PASS |  |
| F17 Withdraw (TEST) | wallet/stages | `POST /api/wallet/stages` | 200 | 200 | 11 | PASS |  |
| F17 Withdraw (TEST) | consent bank_account_collection | `POST /api/compliance/consents` | 200 | 200 | 4 | PASS |  |
| F17 Withdraw (TEST) | fund/preflight/bank-withdrawal | `POST /api/fund/preflight/bank-withdrawal` | 200 | 200 | 9 | PASS |  |
| F17 Withdraw (TEST) | fund/preflight confirm | `POST /api/fund/preflight/92/confirm` | 200 | 200 | 7 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/create | `POST /api/withdrawal/create` | 200 | 200 | 11 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/list has pending row | `POST /api/withdrawal/list` | 200 | 200 | 3 | PASS |  |
| F17 Withdraw (TEST) | withdrawal/delete (undo) | `POST /api/withdrawal/delete` | 200 | 200 | 6 | PASS |  |
| F17 Withdraw (TEST) | over-balance preflight | `POST /api/fund/preflight/bank-withdrawal` | 200 | 200 | 4 | PASS |  |
| F17 Withdraw (TEST) | over-balance preflight confirm | `POST /api/fund/preflight/93/confirm` | 200 | 200 | 6 | PASS |  |
| F17 Withdraw (TEST) | withdraw over balance refused with clear msg | `POST /api/withdrawal/create` | 200 | 409 | 6 | PASS |  |
| F18 Account deletion | status NORMAL | `GET /api/user/deregister/status` | 200 | 200 | 2 | PASS |  |
| F18 Account deletion | precheck ELIGIBLE | `POST /api/user/deregister/precheck` | 200 | 200 | 5 | FAIL | blockers=['存在待支付或未核销的有效报名'] |
| F18 Account deletion | precheck club leader BLOCKED | `POST /api/user/deregister/precheck` | 200 | 200 | 3 | PASS |  |
| F18 Account deletion | consent account_cancel | `POST /api/compliance/consents` | 200 | 200 | 3 | PASS |  |
| F18 Account deletion | sms/send for apply | `POST /api/sms/send` | 200 | 200 | 6 | PASS |  |
| F18 Account deletion | deregister/apply | `POST /api/user/deregister/apply` | 200 | 200 | 13 | FAIL | status=BLOCKED |
| F18 Account deletion | old token revoked after apply | `GET /api/user/deregister/status` | 200 | 200 | 5 | FAIL | code 200 not in [401] |
| F18 Account deletion | deregister/result PENDING | `GET /api/user/deregister/result` | 200 | 200 | 4 | FAIL | not PENDING |
| F18 Account deletion | deregister/cancel | `POST /api/user/deregister/cancel` | 200 | 409 | 4 | FAIL | code 409 not in [200] |
| F18 Account deletion | status back to NORMAL | `GET /api/user/deregister/status` | 200 | 200 | 3 | PASS |  |
