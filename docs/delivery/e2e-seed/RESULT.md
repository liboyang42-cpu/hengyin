# E2E TEST seed and backend fixes — result (2026-11-13)

Branch: `fix/userinfo-role`, from `integration/backend-app-20261112` (c96d345ff).
Environment: the **TEST** backend only. That is be-int on VPS `work`: port 8120, database `cy_e2e_beint` (docker `be-int-mysql`), Redis `be-int-redis` db 9, public URL https://8120-agqyfqxs5vwolymtoibzq3fhde.instabox.tech. Nothing here touches production.

## 1. `/api/userInfo` returns the member role
- Commit 571ac0f08: `ApiLoginController.userInfo` reads `ums_member.role`, the same source `LoginResponseVO.role` uses for `/api/login/*`. If the member has no role, it falls back to `player`. The role always ends up in `{player, club, merchant}`.
- Commit b89b142d0: when the session `AppUser` has no nickname, `nickName` falls back to `ums_member.nickname`, the same source `/api/user/info` uses. Before this, `/api/userInfo` returned `nickName: null` after a phone login.
- `/api/user/info` already returned `role`, so it is unchanged.
- Tests: `ApiLoginControllerLoginResponseTest` covers all three roles, the missing-role fallback and the nickname fallback (6/6 green). This is the old `/data/home/userinfo-role.patch`, re-applied cleanly, plus the nickname change.

## 2. TEST-only providers (profile `e2e-test` + property, never default or production)
- Commit 072e22e2d:
  - `E2eTestSmsCodeSender` (framework) never sends anything. `SmsVerificationService` still generates the code, rate-limits it and stores it in Redis `sms_<phone>_code`, where the harness reads it.
  - `E2eTestContentSecurityService` (system) lets text through. The self-hosted sensitive-word library still blocks. It never calls WeChat `msgSecCheck` and never submits media.
- Both beans need `@Profile("e2e-test")` **and** `chengyin.e2e.test-providers=true`. `application.yml` mentions neither one. `E2eTestProvidersProfileTest` (3/3) pins both conditions and checks that the default profile cannot activate them.
- TEST wiring lives on the VPS only, not in the repo:
  - `app/start.sh` passes `--spring.profiles.active=druid,e2e-test` and exports placeholder `CHENGYIN_SMS_*` values (not credentials), so SMS counts as configured.
  - `app/application-beint.properties` sets `chengyin.e2e.test-providers=true`.
  - The startup log shows both `[e2e-test] … active` WARN lines.
- Known limits:
  - Image IM messages get a fake trace and stay `check_status=0`, because no callback ever arrives.
  - Square posts with a location or media are still held for manual review by `ContentSafetyGateImpl` (`PRE_REVIEW`), and TEST has no reviewer. Text-only posts publish.

## 3. Seed (`docs/delivery/e2e-seed/`)
| File | What it does |
|---|---|
| `seed.sql` | Content in the reserved id range 992000–992199. Every row is an upsert, so reruns keep the published route, sign-ups and progress. |
| `seed_api.py` | Writes through the real endpoints: publishes the topic, square post, club channel, DM, free sign-up. Idempotent. |
| `verify.sh` | The curl checklist below. |

Run on the VPS, after `scripts/e2e/build_schema.py` and `seed_fixtures.py`:
```
docker exec -i be-int-mysql mysql -uroot --default-character-set=utf8mb4 cy_e2e_beint < docs/delivery/e2e-seed/seed.sql
python3 docs/delivery/e2e-seed/seed_api.py
bash docs/delivery/e2e-seed/verify.sh            # or: bash verify.sh https://8120-…instabox.tech
```

What is seeded:
- **Players.** 13900000004 (member 9004, "玩家小城") and 13900000009 (member 9009, "玩家二号") already existed from `seed_fixtures.py`. 9004 now also belongs to club 7001, because sign-up for that club's activity is members-only.
- **Topic 992001 "E2E 城市定向·老城三章".**
  - Classic orientation (`product_type=1`), club 7001, owner 9003, `lifecycle=3` (SELLING).
  - Published through the real `POST /api/topic/publish-draft` in `simple` mode: `published_config_version=1`, one content revision. play/nodes rejects an unpublished topic with "路线尚未发布".
  - Three chapters: 992011, 992012, 992013.
  - Six nodes, each using a kit template whose JSON passed `AdvancedGameConfigValidator`:

    | Node | Name | Kit |
    |---|---|---|
    | 992021 | 老城钟楼 | qa TYPE + hint ladder (2 levels, 1 free use per run) |
    | 992022 | 老邮局 | qa PICK (choice) |
    | 992023 | 骑楼拱廊 | photoCheck |
    | 992024 | 石板巷 | diceRoll d20 (DC 12) |
    | 992025 | 码头旧仓 | qa PICK + hint ladder |
    | 992026 | 江边岔路 | diceRoll d6 |

    The hint ladders are frozen into the published route snapshot. The play/nodes projection hides answers and ladders, which is by design: hints are served through `/api/play/help/*` once a run exists.
- **Free activity 992031 with free ticket 992041.**
  - Price 0, 200 seats. The activity window is already open, which play/nodes needs.
  - The ticket's session starts 30 days out, so sign-up stays open: activity tickets stop selling at `ticket.start_time`.
  - 9009 is signed up (registration 74, zero-yuan).
  - 9004 is deliberately **not** signed up, so the app can run the sign-up flow itself. Its quote comes back at 0.00.
- **Club 7001 "城瘾跑团".**
  - Description set, two posts (992051 is a pinned announcement, 992052 is a regular post).
  - Channel: the club group chat (IM conversation `club_7001`, id 3) holds a welcome line from 9003 and a reply from 9009.
- **Square.** One published `MOMENT` post by 9004 ("老城三章初体验"), shown in `/api/v1/community/feeds/LATEST`. The legacy `/api/creativesquare/list` returns 410 on this backend, so that table is not seeded.
- **IM.** A DM from 9009 to 9004.
- **Publisher identity.** Registered for 9003 with the chain_platform fixture values; publishing requires it.
- **Not seeded.** Journey seats and report rows: none of the endpoints above need them.

## 4. Rebuild, restart and verification
- **Jar:** `mvn -o -T 1 -DskipTests package` with `MAVEN_OPTS=-Xmx1536m` under `flock /tmp/maven.lock`, built from `fix/userinfo-role` (b89b142d0, which sits on c96d345ff).
- **Downtime:** 8120 was down from 2026-11-13 08:29:53 to 08:30:21 UTC, 28 seconds.
- **Rollback:** the previous files are kept in `app/` as `app.jar.prev-c96d345`, `start.sh.prev-c96d345` and `application-beint.properties.prev-c96d345`.

`verify.sh` on loopback, after the seed and a second `seed_api.py` run (which only replays):
```
login 13900000009: sms/send=[200 验证码已发送] login=[200 role=player]
login 13900000004: sms/send=[200 验证码已发送] login=[200 role=player]
userInfo 9009: 200 role=player nickName=玩家二号
userInfo 9004: 200 role=player nickName=玩家小城
user/info 9004: 200 role=player
topic/info-to-user 992001 (as 9004): 200 E2E 城市定向·老城三章 lifecycle=3 chaptersList=1/totalChapterCount=3 activities=[992031]
play/nodes 9009 (signed up): 200 registered=True playable=True total=6 chapters=3
play/nodes 9004 (not signed up): 200 registered=False total=6
registration/quote 9004: 200 操作成功 payAmount=0.0
registration/create free 9009 (idempotent replay of the seed sign-up): 200 报名创建成功 registrationId=74 payableAmount=0.0
im/send 9004->9009: 200 操作成功 checkStatus=1
club/home 9009: 200 joined=[7001, 7002]
club/post/list 7001: 200 posts=2
club/chat 7001 (channel): 200 {'conversationId': 3}
community feed LATEST: 200 items=1
```
Through the public URL from the Mac: `sms/send` returned 200, then `/api/userInfo` returned 200 with `role=player nickName=玩家小城`, and `play/nodes` returned 200 with `total=6`.

## Notes for app testers
- Sign in:
  1. Call `POST /api/sms/send phone=1390000000x`.
  2. Read the code with `docker exec be-int-redis redis-cli -n 9 GET sms_<phone>_code`.
  3. The old fixed-code route still works: `SET sms_<phone>_code '"123456"'`.
- The real send limits still apply: one send per 60 s and 10 per day per phone. On TEST, clear them with `DEL sms_limit_phone_<phone> sms_send_phone_day_<phone>`.
- `registration/create` logs harmless `WxSubscribeMsgServiceImpl` / `CommittedBusinessNotice` errors, because TEST has no WeChat token.
