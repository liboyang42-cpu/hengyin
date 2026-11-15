# C3 channels-events — backend receipt (`feature/r-channels-events`, base `origin/roadmap/be-integ` @ 81b65553d)

App half: chengyin-app `cloud/r-channels-events` (base `origin/roadmap/integ`), receipt at the same path there.

Owner ask (2026-11-15): "A channel should be able to send messages, post events, and create channels, similar to Discord."
This package covers **events posted in channels**. C1 (channels-structure) and C2 (channels-messaging) own channels/roles and messages.

## Findings / brief items

| Item | Status | Notes |
|---|---|---|
| 1. Post an event from a channel | Closed | `POST /api/channels/{id}/events` runs the **existing** `/api/activity/publish` (identity, quota, content safety, review, ticket) for a club activity with one ticket (capacity = stock, classic mode, meeting point = place), then links it (`channel_event`) and posts an event card into the channel's conversation with the existing message model: `msg_type` 1 (old clients read the text "【活动】title · time · place") and `extra_json.cardType = "channel_event"`. An activity held for review gets no card until it is approved (cards are posted lazily on the next channel read); a refused one is closed. |
| Who may post | Closed (conservative) | Club **owner** with `SEND_MESSAGE` in that channel. The existing publish path accepts only the club owner as host, so admins/roles are refused with a plain message. C1 has not added an event capability yet; see open questions. |
| Paid events | Closed (off) | Price > 0 is refused with `PAID_EVENTS_OFF` unless the money switch `tickets` area is open (`MoneySwitchService`, which is off in production by the server ceiling). Paid tickets then go through the existing ticket path. No production switch was changed. |
| 2. RSVP going / maybe / no, counts, attendee list, capacity | Closed | "Going" **is** a confirmed enrolment (`cms_registration` payment_status 2, registration_status 2: the same predicate the activity channel uses). Without one, `rsvp GOING` returns `409 ENROL_REQUIRED` and `GET .../enrol-target` names the activity and ticket for the existing `/api/registration/create` (consent, capacity, payment unchanged). Maybe/no are stored in `channel_event_rsvp`; a confirmed enrolment always reads as going. Saying "no" never cancels an enrolment (refund rules live in the cancel path); the answer says `still_enrolled`. Capacity = ticket stock, enforced by enrolment; `spots_left` never goes below 0. |
| Reminders | Closed | Event tickets are classic (`mode` 1) tickets, so the **existing** before-start job (`BeforeStartNotifyTask` → `TeamGroupServiceImpl.notifyBeforeStartBatch`) reminds enrolled members by push and WeChat notice unchanged. Members who said "maybe" now get the same app push (`pushFanout.eventReminder`, one per ticket and member), never the WeChat notice. No new job, no new `@Scheduled`. |
| Check-in | Closed | The host's card points at the club-ops event-day tool (ticket scan, roster) for the activity; nothing new server-side. |
| 3. Event chat thread | Closed | The thread is the **existing** activity channel (`activity_<id>`, `InGameChannelService`): enrolment already joins it; RSVP going/maybe and `POST .../thread` (any member who can see the channel) join it through a new `InGameChannelService.ensureActivityThreadMember`. |
| 3. Photo album (CLB-12) | Closed | `channel_event_photo` rows point at the member's own app media asset (`asset_id`, or `image_url` of an image the member uploaded through the existing upload; resolved by object key and owner). Others see a photo only when the asset is `APPROVED` by the existing media review; the uploader sees their own pending photo. Upload: attendees (confirmed enrolment) and the host, from the start until 30 days after the end, 30 photos each. View: club members who can see the channel, and attendees. Removal: uploader, host, club owner/admins; admin console can remove and restore. |
| 4. Game event cards | Closed | The card payload uses the in-game `game_event` field names where they overlap (`cardType`, `eventType`, `activityId`, `target`), so one card component draws both (app). Game events posted into the activity channel therefore look like event cards in the event's thread. |
| 5. Admin console | Closed | 频道活动 page (`/clubs-roam/channel-events`): events with live going/maybe counts, album with reviewer preview, remove/restore. Permissions `business:channelEvent:list` / `:moderate`, granted to no role. |
| 5. Mini-program | No change needed | The mini-program does not show channel conversations (`subpackageB/pages/im/list` keeps groups out). A channel event is an ordinary club activity there: detail page, enrolment, reminders work unchanged. |
| 5. Erasure | Closed | Account deletion deletes the member's RSVPs and album rows and unlinks them as poster (the event stays as a club activity). |

## Changes, file by file

- `chengyinhub-admin/sql/migration_20261116_channel_events.sql` (new, id `20261116_channel_events`, deps `20261105_l8_channel_lifecycle`, `20261012_missing_business_menu`): `channel_event`, `channel_event_rsvp`, `channel_event_photo` (`CREATE TABLE IF NOT EXISTS`), menu + button guarded by existence checks, runner assertion; rollback note at the top. No money/inventory table touched.
- `ci/migration-dependencies.json`: the entry above.
- `chengyinhub-system/.../business/channelevent/` (new):
  - `ChannelEventDraft`: form validation (title ≤ 60, start in the future, ≤ 7 days, ≤ 1 year ahead, place + pin, capacity 1–1000, price 0–9999.99, request id).
  - `ChannelEventRow`: row model.
  - `ChannelEventService`: posting rules, publish request, attach + card, review wait, list/detail views, RSVP, enrol target, attendees, thread, maybe-reminder source, album (asset or own image URL), removal, admin reads.
  - `mapper/ChannelEventMapper`: annotation SQL over the new tables plus narrow reads of `cms_activity`, `oms_ticket`, `cms_registration`, `ums_member`, `im_channel`/`im_conversation`, and the card insert / last-message / unread fan-out (sender excluded).
- `chengyinhub-admin/.../controller/api/ApiChannelEventController.java` (new): app routes (below), same HTTP shell as the other channel routes (`ChannelHttpResponse`: status = `code`, stable `errorCode`).
- `chengyinhub-admin/.../controller/business/ChannelEventAdminController.java` (new): `/business/channel-event/list`, `/{id}/photos`, `/photo/{id}/remove|restore`.
- `InGameChannelService`: `ensureActivityThreadMember(activityId, memberId)` (adds one member to the activity channel, host as role 1).
- `TeamGroupServiceImpl.notifyBeforeStartBatch`: `remindChannelEventMaybes(ticket)` (optional bean, errors logged, never stops the enrolled reminders).
- `MemberPrivacyErasureService` + `MemberPrivacyErasureMapper(.java/.xml)`: `channel_event_rsvp`, `channel_event_photo` deleted; `channel_event.poster_member_id` set to 0.
- `chengyinhub-ui/src/api/business/channelEvent.js`, `chengyinhub-ui/src/views/business/channelevent/index.vue` (new): admin page.
- Tests (new): `ChannelEventDraftTest` (5), `ChannelEventServiceTest` (23), `ChannelEventMapperH2Test` (4, real SQL on H2 MySQL mode), `ApiChannelEventControllerTest` (2), `ChannelEventMaybeReminderTest` (2); `MemberPrivacyErasureServiceTest` extended (channel event tables seeded for two members, only one erased).

## App contract

All routes need the app token. Bodies JSON. Answers `AjaxResult` (`code`, `msg`, `data`); refusals carry `errorCode`.

| Route | Body / query | Answer `data` |
|---|---|---|
| `GET /api/channels/{channelId}/events` | — | `{channel_id, can_post, paid_events_open, events: [view]}` (running and upcoming first, then recent past; an event in review only for its poster) |
| `POST /api/channels/{channelId}/events` | `{title, startAt, endAt, placeName, address?, lat, lng, capacity, description?, price?, requestId}`; times `yyyy-MM-dd HH:mm:ss` China time or epoch ms; `requestId` `[A-Za-z0-9_-]{8,64}` makes a retry return the same activity | `view` (full) |
| `GET /api/channel-events/{id}` | — | `view` (full) |
| `POST /api/channel-events/{id}/rsvp` | `{status: GOING\|MAYBE\|NO}` | `view` + `still_enrolled` |
| `GET /api/channel-events/{id}/enrol-target` | — | `{owner_type: 2, owner_id, ticket_id, paid, paid_events_open, remaining}` |
| `GET /api/channel-events/{id}/attendees` | — | `{going: [person], maybe: [person]}`, person = `{member_id, nickname, avatar(http(s) or null)}`, up to 200 each |
| `POST /api/channel-events/{id}/thread` | `{}` | `{conversation_id, conversation_type: 2, title}` |
| `GET /api/channel-events/{id}/photos` | `?before_id=` | `{photos: [{id, member_id, url, review_state, mine}], next_before_id, can_upload}` |
| `POST /api/channel-events/{id}/photos` | `{asset_id}` or `{image_url}` | photo |
| `POST /api/channel-event-photos/{id}/remove` | `{}` | `{id, removed: true}` |

`view`: `id, channel_id, club_id, activity_id, ticket_id, message_id, title, start_at, end_at (epoch ms), place_name, lat, lng, capacity, paid, state (UPCOMING|LIVE|ENDED|IN_REVIEW|CANCELLED), going_count, maybe_count, no_count, spots_left, my_rsvp, enrolled`; full adds `enrolment_waiting_payment, is_host, can_check_in, can_upload_photos, can_moderate_album`.

Card `extra_json` (≤ 1000 chars): `{cardType:"channel_event", eventType:"event", eventId, activityId, title, target(place), startAt, endAt, capacity, paid}`. Counts are read live from the detail route.

Error codes: `EVENT_*` form codes (400), `GUILD_FORBIDDEN`, `EVENT_POST_FORBIDDEN`, `ALBUM_UPLOAD_FORBIDDEN`, `ALBUM_ASSET_NOT_OWNED`, `PHOTO_REMOVE_FORBIDDEN` (403), `EVENT_NOT_FOUND`, `CHANNEL_NOT_FOUND`, `PHOTO_NOT_FOUND` (404), `PAID_EVENTS_OFF`, `ENROL_REQUIRED`, `EVENT_CLOSED`, `EVENT_PUBLISH_REFUSED` (msg = the publish path's own text), `EVENT_ACTIVITY_MISMATCH`, `CHANNEL_NOT_ACTIVE`, `EVENT_CLUB_ONLY`, `ALBUM_LIMIT`, `ALBUM_DUPLICATE`, `THREAD_UNAVAILABLE` (409).

## Verification

All Maven runs with `LANG=C.UTF-8 LC_ALL=C.UTF-8`.

- `mvn -B -q -DskipTests compile` → OK.
- `mvn -B -q -pl chengyinhub-admin -am test -Dtest='ChannelEvent*Test,ApiChannelEventControllerTest,MemberPrivacyErasureServiceTest,BeforeStartNotifyRetryContractTest' -Dsurefire.failIfNoSpecifiedTests=false` → 47 tests, 0 failures (Draft 5, Service 23, MapperH2 4, Controller 2, MaybeReminder 2, Erasure 4, BeforeStart 7).
- Regression sweep (on d17c77e61, before the album-by-URL commit, whose own tests are in the line above) `-Dtest='*Channel*Test,*InGame*Test,*Erasure*Test,*Anonymous*Test,*PermitAll*Test,*Route*Test,*TeamGroup*Test,*BeforeStart*Test,*Migration*Test,*Security*Test,*Guard*Test'` → **1751 run, 0 failures, 0 errors**.
- `bash ci/migration-safety-check.sh` → `迁移安全门禁:通过`. No `rollback_*.sql` added.
- `chengyinhub-ui`: `node --test "tests/unit/**/*.test.js"` → 573 tests, 30 failures on both base and branch, identical lists (no new failure).
- The H2 test caught one real defect before push (an ambiguous `isPaid()`/`getPaid()` pair broke MyBatis inserts); fixed.

## Not verified

- No MySQL here: the migration and the annotation SQL ran only on H2 (MySQL mode). `ON DUPLICATE KEY UPDATE ... VALUES(status)` and the list ordering expression should be checked on MySQL 8 (deprecation warning for `VALUES()` is expected, not an error).
- The full chain in a running server: `/api/activity/publish` called from the channel route (same request thread and token), push fan-out of the card message, the in-game thread membership.
- Vue page not built or opened.

## Owner actions and open questions

1. **Who may post events.** Today only the club owner (the existing publish path hosts activities only for the owner). Should admins or a C1 role be able to post, and if so whose activity is it (quota, settlement, refunds)? C1 can add an event capability; the service then needs the owner's answer on hosting.
2. **"Maybe" reminders** are app push only. Confirm, or turn them off.
3. **Album window**: open from the start until 30 days after the end, 30 photos per person. Confirm.
4. **Saying "no"** keeps a paid or free enrolment (no automatic cancel/refund). Confirm this is wanted rather than auto-cancelling free enrolments.
5. Apply the migration through the ledger; grant `business:channelEvent:list` / `:moderate` to the operators who should see the page.
6. Branches not in this base (`feature/extgame-money`, `feature/game-types`, `feature/sys-weather`): nothing needed from them.
7. No hard-coded secrets found in the touched files.
