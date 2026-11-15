# Acceptance run 2026-11-15T16:43:16Z against https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech

| Flow | Verdict |
|---|---|
| F01 Sign-in | BLOCKED |
| F02 Browse home | BLOCKED |
| F03 Activity detail | BLOCKED |
| F04 Register and pay | BLOCKED |
| F05 Ticket QR | BLOCKED |
| F06 Check-in | BLOCKED |
| F07 Play game: answer, hint, finish | BLOCKED |
| F08 Roam walk and fog reveal | BLOCKED |
| F09 City Monopoly claim, rent, challenge | BLOCKED |
| F10 Club join and chat | BLOCKED |
| F11 Messages and group chat | BLOCKED |
| F12 Publish a topic | BLOCKED |
| F13 Publish an activity | BLOCKED |
| F14 Merchant scan-to-verify | BLOCKED |
| F15 Merchant refund | BLOCKED |
| F16 Creator earnings | BLOCKED |
| F17 Withdraw (TEST) | BLOCKED |
| F18 Account deletion | BLOCKED |

| Flow | Step | Request | HTTP | code | ms | Result | Note |
|---|---|---|---|---|---|---|---|
| F01 Sign-in | sms/send | `POST /api/sms/send` | None | None | 45 | ERROR | transport: ProxyError: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel co |
| F01 Sign-in | login/phone bad code rejected | `POST /api/login/phone` |  |  |  | SKIP | blocked: transport error at 'sms/send' |
| F01 Sign-in | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F02 Browse home | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F03 Activity detail | activity/info 992031 (anonymous) | `POST /api/activity/info` | None | None | 167 | ERROR | transport: ProxyError: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/activity/info (Caused by ProxyError('Unable to connect to proxy', OSError('Tunn |
| F03 Activity detail | activity/info 992031 (signed in) | `POST /api/activity/info` |  |  |  | SKIP | blocked: transport error at 'activity/info 992031 (anonymous)' |
| F03 Activity detail | topic/info-to-user 992001 | `POST /api/topic/info-to-user` |  |  |  | SKIP | blocked: transport error at 'activity/info 992031 (anonymous)' |
| F03 Activity detail | registration/payment-readiness | `POST /api/registration/payment-readiness` |  |  |  | SKIP | blocked: transport error at 'activity/info 992031 (anonymous)' |
| F03 Activity detail | play/nodes preview | `GET /api/play/nodes` |  |  |  | SKIP | blocked: transport error at 'activity/info 992031 (anonymous)' |
| F03 Activity detail | activity/info unknown id | `POST /api/activity/info` |  |  |  | SKIP | blocked: transport error at 'activity/info 992031 (anonymous)' |
| F04 Register and pay | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F05 Ticket QR | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F06 Check-in | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F07 Play game: answer, hint, finish | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F08 Roam walk and fog reveal | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F09 City Monopoly claim, rent, challenge | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F10 Club join and chat | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F11 Messages and group chat | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F12 Publish a topic | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F13 Publish an activity | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F14 Merchant scan-to-verify | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F15 Merchant refund | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F16 Creator earnings | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F17 Withdraw (TEST) | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
| F18 Account deletion | (precondition) | `- -` |  |  |  | BLOCKED | cannot reach TEST server: HTTPSConnectionPool(host='8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech', port=443): Max retries exceeded with url: /api/sms/send (Caused by ProxyError('Unable to connect to proxy', OSError('Tunnel connection failed: 403 Forbidden'))) |
