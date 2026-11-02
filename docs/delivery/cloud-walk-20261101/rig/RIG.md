# Cloud walk rig (2026-11-01 walkthrough)

Candidate: `master` a1d30fb295d361122aec76fa9c180b4b496a9b0a. Everything below runs inside one Claude Code cloud
container; nothing touches production, the VPS or the private repository. All secrets are generated at run time.

## Quick start

```bash
git fetch origin claude/cloud-walk-20261101-rig
git checkout claude/cloud-walk-20261101-rig        # or: git show origin/claude/...:path > file for each file
export RIG_HOME=$HOME/walk-rig                       # default; candidate worktree, jar, venv, logs, secrets live here
bash docs/delivery/cloud-walk-20261101/rig/up.sh     # backend + browser runtime
R=docs/delivery/cloud-walk-20261101/rig
PY=$RIG_HOME/venv/bin/python
$PY $R/seed-walk.py list                             # identities
node $R/mpweb/walk.mjs '{"path":"pages/index/index","identity":"e2e-openid-9004","agree":true}' \
     '[{"wait":1500},{"shot":"01-home"},{"tap":"主题"},{"dump":1}]'
```

Walkers on their own branches: run `up.sh` straight from the rig branch without switching your branch, e.g.
`git worktree add $HOME/rig origin/claude/cloud-walk-20261101-rig && bash $HOME/rig/docs/delivery/cloud-walk-20261101/rig/up.sh`.

### Timing measured in this container

| Step | Cold | Warm |
|---|---|---|
| dockerd start (not running by default) | ~3 s | 0 |
| `mvn -DskipTests package` (JDK 21, deps from Maven Central via proxy) | ~4 min incl. downloads; 1m40 with a warm `~/.m2` | skipped with `RIG_SKIP_BUILD=1` |
| explode + javac 4 stubs | 4 s | |
| MySQL 8.0.45 + Redis 7.4 containers (pinned digests from `ci/real-storage-it-registry.json`) | 15 s (+~30 s first image pull) | |
| `build_schema.py` + `seed_fixtures.py` → cy_e2e_51, 451 tables | 17 s | |
| app start (`Started RuoYiApplication`) | 30–36 s | |
| browser bundle (158 pages, 389 components) + puppeteer-core install | ~10 s + npm | |
| **total** | **~6–7 min** | **~75 s** |

`up.sh` is idempotent: rerunning it throws the database away (fresh containers, fresh schema) and restarts the app.
Restart only the app: `$PY $R/app.py restart` (keeps data; same secrets). Stop: `$PY $R/app.py stop`.

## What up.sh does (reuse, not reinvention)

It runs the candidate's own `ci/chain_acceptance.py` stages with `--gate` and `--source` both pointing at the candidate
worktree — the same stages `.github/workflows/chain-acceptance.yml` runs: `plan` → `precheck` (master basis, JDK from
the pom: 21) → `explode` (exploded jar + `E2eWechatPayStub`, `E2eWechatPayGatewayStub`, `E2eFaultInjectionFilter`,
`E2eWxContentSecurityStub` compiled in) → `storage-start` (pinned MySQL/Redis, redis-cli shim in `$RIG_HOME/work/bin`)
→ `schema` (`build_schema.py --drop`, `seed_fixtures.py`, read-backs: 9 fixture members, 5 merchants, 2 clubs, captcha off;
quartz jobs stay as seeded, i.e. paused like CI). Then `app.py` starts the app with chain_acceptance's own
`java_command` and fixture overlay `ci/chain-acceptance/application-e2e.properties` (127.0.0.1:8099, DB 3316,
Redis db 9), plus two extra args for the login seam (below).

Secrets: `app.py` takes chain_acceptance's `app_env` (random JWT secret, PII key, WX secrets, Druid password, coupon QR
secret) and persists them once in `$RIG_HOME/secrets.env` (mode 600, outside the repo) so the PII key survives restarts.

Proof (`evidence/*.json`): `$PY $R/chains.py player` ran platform → merchant_club → sale_close → player through
chain_acceptance's own `run_chains` with terminal SQL read-backs: **all four PASS** (topic 990014; player chain 124 s).
`chains.py` writes real rows; run it only if you want its data (topic 990014 「E2E 探店日一期」, activity 13 ...).

## Browser runtime (`mpweb/`)

Extends `mpweb-seed`. `build.sh` bundles the candidate's real `chengyinhub-xcx`: `app.js`, `app.json`, every page in
`pages` and all `subPackages` (158 pages), their component closure (389) and WXSS, compiled by the seed's tools.
`server.mjs` (127.0.0.1:8097) serves the shell, the bundle, the mini-program's static assets, and proxies `/__api/*`
to the real backend. `shell.html` is a 390×844 phone (status bar 47 px, capsule, native nav bar for pages without
`navigationStyle: custom`).

Real in the runtime: the actual `App({...})` from app.js (onLaunch, session-store, request client, consent gate,
login guard), the page/component code, WXML/WXSS (rpx at 390), `wx.request` (real `fetch` to the backend, real status
codes and bodies), `wx.uploadFile` (real multipart to the backend), storage (in-memory per browser session), navigation
stack (`navigateTo` with eventChannel, `redirectTo`, `switchTab` with persistent tab pages, `reLaunch`,
`navigateBack`, 10-page limit, onShow/onHide/onUnload), page scroll events, `showModal`/`showToast`/`showLoading`/
`showActionSheet` (rendered WeChat-style in `#sysLayer`), checkbox/radio/switch/label/picker change events, disabled
buttons. The tab bar is the product's in-page `components/tabBar` (the `custom-tab-bar` is an intentional empty shim).
`page{}` rules in app.wxss apply only to the page host, never to component hosts (`:host(.__never-page)`).

Driver: `node mpweb/walk.mjs <opts-json|file> <steps-json|file>` (prints route, network log, page errors, console
errors, runtime log) or `import { launch } from './mpweb/walk.mjs'`. Options: `path`, `identity`, `agree` (tick and
press the real privacy gate), `lat`/`lng`, `heading`, `scan`, `env` (`release` default), `settle` ms.
Steps: `open` (navigateTo / switchTab), `relaunch`, `back`, `tap` (visible text; `["text",{"nth":1}]`), `tapSel` (CSS,
pierces shadow DOM), `type` [selector, text], `wait`, `waitText`, `scroll` (px; scrolls the page's main scroll-view),
`shot` (390×844 JPEG q70 into `$RIG_SHOTS`, default `./shots`), `loc` [lat,lng] (also fires onLocationChange),
`heading`, `scan`, `pay` (`success`|`cancel`), `phone` (`ok`|`deny`), `modal` (`confirm`|`cancel`), `sheet`
(index or text), `login` (openid), `eval` (JS), `dump` (all visible text). Library extras: `w.data('path')` reads the
current page's data, `w.net()`, `w.console()`, `w.errors()`, `w.log()`.

Proof (`proof/`): against the real backend with identity 9009/9002 — home tab (`p01-home`), theme detail 「E2E 探店日一期」
(`p02-topic`), play page 「队伍集合中 0/2」 (`p03-play`), merchant workbench 「探店日样板咖啡 ¥0.00」 with tab bar
(`m01-merchant`). Zero page errors; the only console errors are 404s for `/profile/home-banners/*.gif` (OSS/upload
assets that do not exist in a fresh DB: HARNESS).

## Test seams (every injected piece)

| Seam | How | Real part behind it |
|---|---|---|
| Login for a chosen identity | `wx.login` returns `code = "rig-<openid>"`. The app is started with `--spring.profiles.active=druid,local` and `--chengyin.test.wechat-api-base-url=http://127.0.0.1:8097`; the product's own non-prod switch then sends `jscode2session` to `server.mjs`, which answers `{openid}`. With `identity=` the driver logs in before launch (real `POST /api/login/code`) and stores the 7 session fields session-store persists, so the app starts as a returning user. Without it the app starts as a guest and the real login pop-up path calls `wx.login` → same seam. | `/api/login/code`, member creation for new openids, JWT, RBAC role, `AppLoginService` |
| Identities | fixture `e2e-openid-9001..9009`; `seed-walk.py add-players N` creates `walk-player-k` by real login | — |
| `getPhoneNumber` | `<button open-type=getPhoneNumber>` fires `getphonenumber` with `{code:"rig-dummy-phone-code"}` (or `deny`). The backend's exchange with WeChat cannot succeed here. For a phone-bound account use `seed-walk.py phone-token <phone>` (fixture SMS code in Redis, real `/api/login/phone`). | NOT VERIFIED |
| `chooseAvatar`, `getUserInfo`, `agreePrivacyAuthorization` open-types | fire their events with a generated image / dummy profile / `{}` | the app's handlers |
| Payment (**browser leg not yet exercised**) | prepay is the compiled-in `E2eWechatPayStub` (`package=prepay_id=e2e-stub`). `wx.requestPayment` POSTs `/__rig/pay` → `pay.py`: picks the member's newest unpaid `cms_registration`, writes a VERIFIED raw payment fact (same function as `chain_player.py`), then calls the real `POST /api/registration/callback`; reports callback status + DB read-back. `payment_status` is never written. `pay: "cancel"` simulates the user closing the sheet. | callback handler, frozen amount check, order state machine, entitlements |
| `scanCode` | returns the payload set by `scan` (step or `?scan=`); with none set it fails `cancel` | whatever the page does with the payload |
| GPS | `getLocation`/`getFuzzyLocation`/`chooseLocation` return `loc`; `loc` step also fires `onLocationChange` listeners. Default 31.2304,121.4737 (People's Square) | server distance checks (ARRIVE_FIRST) |
| Compass | `onCompassChange` listeners receive `heading` every 500 ms | — |
| Camera / album | `chooseMedia`/`chooseImage`/camera `takePhoto` return a generated 640×480 JPEG; upload is real | upload endpoint |
| `wx.getAccountInfoSync().envVersion` | `release` by default (what a real user runs); `env=develop` shows developer-only entries | — |
| API base URL | requests to `https://www.chengyinhub.com/prod-api/*` are rewritten in the runtime to `/__api/*` → 127.0.0.1:8099. No product config is changed. | — |

Seam test status: login (both pre-launch and `/sns/jscode2session`), privacy gate, open-type, GPS, map, navigation and
real requests were exercised in the proof runs. `pay.py`'s callback path is the same function the PASSing player chain
uses, and `/__rig/pay` answers, but a browser `wx.requestPayment` → `/__rig/pay` → paid read-back was **not** run end to
end (fixture player 9004 first needs to join the club: 「请先加入俱乐部再报名」). `scanCode`, compass and `getPhoneNumber`
are wired but not exercised on a product page. Treat the first use of each as part of your walk and note it.

No product-code patches are applied (`compile.mjs` patch file is not used).

## Known limitations (report these as HARNESS, not product bugs)

- Native `<map>` is replaced by a canvas stand-in (`nav.js` `MP.drawMap`): dark streets, the page's own markers
  (callout/label text), polylines, circles and the injected GPS dot, labelled 「rig map stand-in」 (`proof/p04`).
  Tiles, gestures, native callouts, share cards, subscribe messages, safe areas, keyboard, real fonts/audio from OSS: NOT VERIFIED.
- Images on OSS / `/profile/upload/*` that are not in the fresh DB return 404; theme covers you upload through the real
  upload path do work (served by the backend from `$RIG_HOME/work/upload`). Chrome has no proxy configured, so external
  hosts (OSS, Tencent Map) are not reachable from the page.
- `picker` uses an action sheet (selector) or `MP.nextPickerValue` (date/time/region; `eval` it before tapping);
  `multiSelector` returns its current value. Native wheel UI is not reproduced.
- Storage is per browser session (each `walk.mjs` run starts clean, like a fresh install, except the identity seed).
- `canvas` 2d works; WebGL/XR/VisionKit pages degrade. `wx.getWeRunData` fails (deny).
- Animations follow real time; `MPWEB.setSpeed(n)` exists from the seed but is not exposed as a step.
- The privacy gate and the home 「首页城市定位」 dialog are real product behaviour; `agree:true` only presses the gate.
- Fresh-DB pages show empty states (no themes until you author them). The chain data (topic 990014) exists only if
  you ran `chains.py`.

## Cloud-environment facts discovered

- Docker 29.9 is installed but `dockerd` is not running; `up.sh` starts it (root, overlayfs works). Docker Hub pulls of
  the pinned images worked through the agent proxy.
- Default locale is POSIX: `javac` fails on the repo's Chinese file names ("Malformed input or input contains unmappable
  characters") → `up.sh` exports `LANG=C.UTF-8`.
- System `python3` (3.11) has a broken `cryptography` (pyo3 `PanicException` on import), which PyMySQL imports → `up.sh`
  makes `$RIG_HOME/venv` with PyMySQL 1.1.1 + cryptography from PyPI. Use `$RIG_HOME/venv/bin/python` for rig scripts.
- JDK 21 (OpenJDK 21.0.12), Maven at `/opt/maven`, Node 22, Google Chrome at `/usr/bin/google-chrome` are preinstalled;
  npm registry is reachable (puppeteer-core 23.11.1 installed into `$RIG_HOME/mpweb/node_modules`, symlinked, gitignored).
- Maven Central works through the proxy (JAVA_TOOL_OPTIONS already carry the proxy + truststore). A local `redis-server`
  binary exists but is not used; Redis runs in Docker like CI.
- No downloads were blocked for this rig.

## Files

`up.sh` · `app.py` (start/stop/restart app) · `chains.py` (run chains) · `pay.py` (payment seam) · `seed-walk.py`
(identities, tokens, session time shift) · `mpweb/` (`build.sh`, `server.mjs`, `shell.html`, `src/runtime.js`,
`src/ui.js`, `src/nav.js`, `walk.mjs`) · `evidence/` chain receipts · `proof/` screenshots.
