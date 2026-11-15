# P2 links-and-push: backend result

Branch: `feature/r-links-push` (base `fix/r5-be-infra-stubs`). Package brief: `roadmap/briefs:docs/roadmap/briefs/p2-links-push.md`, backend items 4 to 7.

## Findings closed (backend parts)

| ID | Status | What closes it |
|---|---|---|
| GRO-02, FLOW-04, BE-02 | Closed on the server, pending owner keys | Device registry, APNs sender (ES256 token auth, HTTP/2), fan-out for chat messages and @mentions, club notices and event reminders. With no APNs configuration, sending does nothing and logs one WARN. |
| CLB-02 | Closed | Club notices accept `channel: "APNS"` next to `IN_APP`. The in-app record is written as before. After each in-app delivery succeeds, the server also pushes to that recipient's devices. Other channels still return 501. |
| GRO-01, FLOW-05 (hosting part) | Server side done, owner hosting pending | The AASA file is at `chengyinhub-ui/public/.well-known/apple-app-site-association`, a verbatim copy of app `docs/release/aasa.json`. The backend also serves it anonymously at `GET /.well-known/apple-app-site-association` as application/json. |

## Contract for the iOS app

Routes need a signed-in member (the app token, same as other `/api/*`). Bodies are JSON. Responses use the standard `AjaxResult` (`code`, `msg`, `data`).

- `POST /api/push/device/register`
  - Body: `{"platform":"IOS","token":"<hex>","environment":"SANDBOX"|"PRODUCTION","bundleId":"com.chengyinhub.chengyinApp","locale":"zh-Hans"}`
  - Returns `data {"deviceId": <long>, "enabled": true}`.
  - Validation:
    - `token` is hex, an even number of 64 to 200 characters, and is stored in lower case.
    - `platform` must be `IOS`.
    - `environment` must be exactly `SANDBOX` or `PRODUCTION`.
    - `bundleId` is required: reverse-DNS form, at most 128 characters.
    - `locale` is optional: BCP-47-like, at most 35 characters.
    - Any other key is rejected with code 400. No sign-in returns an error.
  - Upsert on (token, environment): the token is re-bound to the caller (an account switch moves it), re-enabled, and `last_seen_at` is refreshed.
- `POST /api/push/device/unregister`
  - Body: `{"token":"<hex>"}`. This is the only key allowed.
  - Returns `data {"enabled": false}`.
  - Disables the token only if it belongs to the caller. It succeeds even when the token is unknown or already disabled.
- APNs payload:

  ```json
  {"aps":{"alert":{"title":"…","body":"…"},"sound":"default","thread-id":"chat-12"},
   "page":"/subpackageB/pages/im/chat/index?conversationId=12&type=1",
   "kind":"chat","conversation_id":"12"}
  ```

  - `kind` is `chat`, `club` or `event`. `conversation_id` is a string and appears only for `chat`.
  - `page` is always a relative mini-program source path. Values:
    - chat: `/subpackageB/pages/im/chat/index?conversationId=<id>&type=<conversation type>`
    - club notice: `/pages/club/detail/index?id=<clubId>`, or `/pages/activity/detail/index?id=<activityId>` when the notice is for one activity
    - event reminder: `/pages/activity/detail/index?id=<id>`, or `/pages/topic/index/index?id=<id>` for theme tickets
  - `thread-id` and `apns-collapse-id` are `chat-<conversationId>`, `club-<clubId>` or `event-<ticketId>`.
  - The payload is capped at 4096 bytes: the title is cut to 64 code points, the body to 240, then the body is shortened further if needed.
  - The generic strings are in Chinese. They switch to English when the device locale starts with `en`.
- Club notice: `POST /api/club/event-notification/send` with `"channel":"APNS"`. The response keeps `channel:"IN_APP"` and adds `pushChannel:"APNS"`. Preview adds `apns`: `AVAILABLE`, `NOT_CONFIGURED` or `UNAVAILABLE`.
- `GET /api/im/notify-log`: `sources.apns` is now true only when the APNs sender is configured. It still returns an empty list with `delivery_proven=false`, because attempts are not stored. `GET /api/im/device/attempts` is unchanged apart from its wording.

## Preference rules used (existing model, nothing new)

The rules come from `im_notify_pref`, `im_conversation_member.muted` and `im_club_notify_default`:

- **Chat is not pushed:**
  - to the sender
  - to a member who blocked the sender
  - when the conversation is muted (the old `muted` flag, a permanent mute or a timed mute). This holds even for @mentions, matching the unread badge.
  - in quiet hours, whether set on the conversation or on the account default
  - for image messages (they become visible only after review) and for system or merchant conversations
  - for channel (type 5) messages when `VIEW_CHANNEL`/`READ_HISTORY` cannot be confirmed through `IChannelConversationGate.requireRead`
- **Chat notification level** (0 all, 1 mentions only, 2 off, 3 inherit) is the first value that is set, in this order:
  1. the conversation level, unless it is 3 (inherit)
  2. the account default
  3. for club channels and club groups, the club shared default (unconfigured counts as 1, mentions only)
  4. 0 (all)
- **What counts as a mention:** a USER mention of the recipient, or EVERYONE. `suppress_broadcast` turns off @everyone.
- **`show_preview=false`:** the body becomes the generic "你收到一条新消息" ("You have a new message").
- **Club notices and event reminders are skipped** when:
  - the account default level is 2 (off), or the account default is in quiet hours
  - the member's own club preference (scope 2) is muted, level 2, or in quiet hours
- **Bursts:** a member gets at most one push per (member, thread) every 30 seconds. This is kept in memory on each instance, and `apns-collapse-id` lets the device replace older ones.
- **Event reminders:** at most one push per (ticket, member). Redis `setIfAbsent` with a 48-hour TTL enforces this, with an in-memory fallback.

## Files

- `chengyinhub-admin/sql/migration_20261115_links_push.sql` (new, idempotent, rollback note at the top)
  - `push_device`: unique on (token, environment).
  - `club_event_campaign_push`: marks campaigns that also push. `club_event_notification_campaign` is not altered, because its CHECK shape is pinned by an assertion in `migration_club_role_event_ops_20260823.sql`.
- `chengyinhub-system/.../business/push/` (new):
  - `PushDevice`: the device row.
  - `PushDeviceService`: validation, register and unregister.
  - `ApnsJwt`: ES256 signing with JDK `SHA256withECDSAinP1363Format`, and PKCS#8 PEM parsing that accepts literal `\n`.
  - `ApnsTransport` and `JdkApnsTransport`: `java.net.http.HttpClient`, HTTP_2.
  - `ApnsSender`: config, the JWT cache with a 50-minute refresh, headers, environment matching, the disable path for 410 and for 400 with BadDeviceToken, DeviceTokenNotForTopic or Unregistered, the one-time WARN, and token masking.
  - `PushMessage` and `PushPayloadBuilder`: the payload contract and the 4 KB cap.
  - `PushCollapseWindow`: the 30-second burst limit.
  - `PushPolicy`: the preference rules.
  - `PushFanoutService`: sends after commit on the existing `threadPoolTaskExecutor`.
- `chengyinhub-system/.../business/service/IPushFanoutService.java` (new): the interface used by the IM, club and reminder code.
- `chengyinhub-system/.../business/mapper/push/PushMapper.java` and `resources/mapper/business/push/PushMapper.xml` (new).
- `ImServiceImpl.send`: after the message and unread effects, calls `pushFanout.chatMessageSent(id)`. The push is a side path: it runs after commit and any error is swallowed.
- `ClubEventNotificationServiceImpl`:
  - accepts `APNS` when the fan-out bean exists (otherwise 501 as before) and marks the campaign
  - pushes after each successful in-app delivery, including retries
  - replay checks the channel
  - preview reports `apns`
- `ClubEventCampaign`: adds the non-persisted `pushChannel` field.
- `TeamGroupServiceImpl.notifyBeforeStartBatch`: the existing hourly reminder producer (`BeforeStartNotifyTask`, sys_job 102) now also calls `pushFanout.eventReminder(...)` for each ticket and paid member it scans.
- `ChannelNotifyService`:
  - `sources.apns` now shows whether the sender is configured
  - `inQuiet` is now public so `PushPolicy` can reuse it
  - comment wording updated
- `ApiChannelNotifyController`: only the summary wording changed. The route is unchanged.
- `chengyinhub-admin/.../controller/api/ApiPushDeviceController.java` (new).
- `chengyinhub-admin/.../controller/common/WellKnownController.java` (new, `@Anonymous`) and `resources/well-known/apple-app-site-association`.
- `chengyinhub-ui/public/.well-known/apple-app-site-association` (new). A test keeps it identical to the backend copy.
- `chengyinhub-admin/src/main/resources/application.yml`: an `apns.*` block bound to `${APNS_KEY_ID:}`, `${APNS_TEAM_ID:}`, `${APNS_PRIVATE_KEY:}`, `${APNS_TOPIC:}` and `${APNS_ENV:}`. All are empty by default, so sending is off.
- Tests (in `chengyinhub-admin/src/test/java`):
  - `business/push/`: `PushDeviceServiceTest`, `ApnsSenderTest`, `PushPayloadBuilderTest`, `PushCollapseWindowTest`, `PushPolicyTest`, `PushFanoutServiceTest`
  - `web/controller/common/WellKnownControllerTest`
  - 3 new cases in `business/service/ClubEventNotificationServiceImplTest`

## Verification

```
LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -DskipTests compile
# exit 0

LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='PushDeviceServiceTest,ApnsSenderTest,PushPayloadBuilderTest,PushCollapseWindowTest,PushPolicyTest,PushFanoutServiceTest,WellKnownControllerTest,ClubEventNotificationServiceImplTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# 61 tests, 0 failures, 0 errors
#   ApnsSender 9, PushDevice 5, PushFanout 7, Payload 5, Policy 7, Collapse 1, WellKnown 2, ClubEventNotification 25

LANG=C.UTF-8 LC_ALL=C.UTF-8 mvn -B -q -pl chengyinhub-admin -am test \
  -Dtest='Im*Test,TeamGroup*Test,ChannelNotify*Test,NotifyFanout*Test,RestoreDispatch*Test,BeforeStart*Test,ClubEvent*Test,ClubWaitlist*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false
# regression sweep of the touched areas: 422 run, 0 failures, 0 errors, 29 skipped (MySQL ITs need the VPS)
```

What the tests prove:

- **Request validation:** platform, hex token and its length, environment enum, bundleId and locale formats, unknown keys rejected, sign-in required, unregister scoped to the owner and idempotent.
- **JWT:**
  - header `alg=ES256`, `kid`; claims `iss` and `iat`
  - a 64-byte P1363 signature that verifies against a test-generated P-256 key
  - the PEM is read from literal `\n` escapes
  - the cache is reused before 50 minutes and re-signed after
- **APNs requests:** headers (`authorization: bearer`, `apns-topic`, `apns-push-type: alert`, `apns-priority: 10`, `apns-collapse-id`, `apns-expiration`), the sandbox and production hosts, and environment matching.
- **Dead tokens:** 410 and 400 with BadDeviceToken or DeviceTokenNotForTopic disable the token. Other errors keep it.
- **No configuration:** sending is a no-op with no transport or database calls, and `configured=false`.
- **Logs:** the full token is never logged.
- **Fan-out:**
  - mute and mentions-only rules and the club default
  - channel messages are not pushed when read access cannot be checked
  - unreviewed messages are not pushed
  - the 30-second collapse window
  - club and event page paths
  - one reminder per ticket and member
- **Payload:** keys, `conversation_id` only for chat, rejection of absolute or scheme pages, the 4 KB cap with multi-byte text, and cuts that keep surrogate pairs whole.
- **Club notices:** `APNS` is accepted and still writes the IN_APP row; only recipients whose in-app delivery succeeded are pushed; without the fan-out bean the server still returns 501; preview availability is reported honestly.

## Not verified

- Real APNs delivery: there is no key or device here, so a live HTTP/2 exchange with Apple was not run. `JdkApnsTransport` is only exercised through its response parsing.
- The migration was not run against MySQL. The VPS is required; MyBatis XML was checked only by compile and startup-free unit tests, not against a live schema.
- That nginx on www.chengyinhub.com serves `/.well-known/apple-app-site-association` with `Content-Type: application/json` and no redirect.
- Multi-instance behaviour of the in-memory collapse window. Each instance keeps its own window, so the worst case is one extra push per instance.

## Owner actions

1. In Apple Developer, enable Push Notifications and Associated Domains on App ID `9VHY8N755R.com.chengyinhub.chengyinApp`, then regenerate the profiles (app side).
2. Create an APNs auth key (.p8). Set these on the production and test app servers as environment variables (never commit them):
   - `APNS_KEY_ID`: the key ID
   - `APNS_TEAM_ID=9VHY8N755R`
   - `APNS_PRIVATE_KEY`: the full .p8 PEM text; literal `\n` line breaks are accepted
   - `APNS_TOPIC=com.chengyinhub.chengyinApp`
   - `APNS_ENV`: `sandbox` for TestFlight and debug builds that register with SANDBOX tokens, `production` for App Store builds

   Only devices whose registered `environment` matches `APNS_ENV` are pushed. Restart the service after setting them.
3. Run `chengyinhub-admin/sql/migration_20261115_links_push.sql` (test first, then production, with the usual pre-check).
4. Host the AASA file at `https://www.chengyinhub.com/.well-known/apple-app-site-association`: no redirect, HTTP 200, `Content-Type: application/json`. www.chengyinhub.com serves the admin UI dist at `/` and proxies `/prod-api` to this backend, so either:
   - (a) deploy the UI build, which now contains `dist/.well-known/apple-app-site-association`, and add an nginx rule:
     `location = /.well-known/apple-app-site-association { default_type application/json; try_files $uri =404; }`
   - or (b) proxy that one path to the backend: `location = /.well-known/apple-app-site-association { proxy_pass http://<backend>/.well-known/apple-app-site-association; }`

   Check it with `curl -sI https://www.chengyinhub.com/.well-known/apple-app-site-association` and with Apple's CDN at `https://app-site-association.cdn-apple.com/a/v1/www.chengyinhub.com`.

## Open questions

1. **Club default for club channels.** Club channels and club groups use the club shared default (unconfigured counts as 1, mentions only) when the member has neither a conversation level nor an account default row. The existing `/api/im/notify-pref` view resolves only conversation, then account default, falling back to 0. Should the push and the view agree on one chain?
2. **Mutes and mentions.** Mutes also block @mentions, matching the unread badge. Confirm that mentions should not break through a mute.
3. **Push text language.** Push text is Chinese, or English when the device locale starts with `en`. There is no server String Catalog; confirm the copy.
4. **Images and moderated media.** Image messages are not pushed, because they become visible only after review. There is no push on review pass yet.
5. **Reminder producers.** Event reminders exist only for the ticket "before start" job (`BeforeStartNotifyTask`, scanning 24 hours ahead every hour; sys_job 102 must be enabled). No separate club-activity reminder scheduler exists, and none was invented.
6. **Delivery history.** `/api/im/device/attempts` and `/api/im/notify-log` still store no per-attempt rows, so they stay empty with `delivery_proven=false`.
7. **Mentions on send.** On this base, `im_message_mention` rows are written only by message edits (`ChannelMessageService.edit`); `/api/im/send` writes none. `feature/ingame-channels` does not change that either. The push reads mention rows after commit, so @mentions on send will count as soon as a send path writes them in the same transaction. Until then, a mention-only member gets pushes only for edited-in mentions. Nothing here depends on another branch.

## App side (chengyin-app `cloud/r-links-push`)

The app sends exactly the register/unregister bodies above, using the member token. The `page`, `kind` and `conversation_id` keys are read as documented.

A chat tap opens the app's chat inbox. It does not open the transcript, because the app's reviewed rule for `subpackageB/pages/im/chat/index` takes no query. Club and event `page` values open the club detail, activity detail and topic detail in their tabs.
