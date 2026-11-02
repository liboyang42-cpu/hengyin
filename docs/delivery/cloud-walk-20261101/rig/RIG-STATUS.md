READY

Updated 2026-11-02 ~05:07 UTC, rig branch claude/cloud-walk-20261101-rig. Read RIG.md for commands and seams.

- Backend: `bash up.sh` → real MySQL 8.0.45 + Redis 7.4 (Docker; dockerd is started by the script), cy_e2e_51 built
  and seeded by the candidate's own scripts, e2e stubs compiled in, candidate a1d30fb on 127.0.0.1:8099.
  Cold ~6–7 min (Maven), warm `RIG_SKIP_BUILD=1` ~75 s. Verified twice from scratch.
- Chains: platform → merchant_club → sale_close → player all PASS, twice (evidence/*.json).
- Browser runtime: all 158 app.json pages + subpackages bundled with the real app.js; real wx.request to the backend;
  navigation stack; seams for login identity, GPS, compass, scan, phone, payment, camera. Rendered and reviewed against
  the real backend: home tab, theme detail, play page, merchant workbench (with tab bar), search map (stand-in).
  Zero page errors on those pages. Other pages are bundled and should run, but were not individually checked.
- Not yet exercised end to end: browser `wx.requestPayment` → `/__rig/pay` → paid read-back; scanCode / compass /
  getPhoneNumber on a product page. Native map is a canvas stand-in. See RIG.md "Known limitations".
- Environment notes: POSIX locale breaks javac (script sets C.UTF-8); system python3 cryptography is broken (script uses
  $RIG_HOME/venv); nothing was blocked by the network policy.
