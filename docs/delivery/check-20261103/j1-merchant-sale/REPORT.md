# j1-merchant-sale: pre-launch check (first pass)

Work in progress. This file is updated after each section of the journey.

- Commit tested: `8b78f5ca3c06f486631e827827e973cbb47e3094` (on `integ/launch-test-20261103`).
- Rig: `claude/cloud-walk-20261101-rig` with the club lane's `harness-patches.diff`, `up.sh` pinned to the commit above, Docker mirror `mirror.gcr.io`, `LANG=C.UTF-8`. Real jar, real MySQL 8 and Redis, real mini-program code in a headless browser at 390×844 (shots at 2x). **This is not a phone.**

## Section A: merchant applies and is approved (done)

| # | Step | Result | Shot | Read-back |
|---|---|---|---|---|
| A1 | Player home → 「成为商家」 banner | PASS | 01 | — |
| A2 | Apply step 1: name, industry, phone | PASS | 02–05 | — |
| A3 | Apply step 2: address, hours, intro | PASS (address is the rig's fixed 「上海市」) | 06–08 | — |
| A4 | Apply step 3: business licence upload | NOT VERIFIED: the upload calls WeChat `img_sec_check`, unreachable from the rig (HARNESS, 「安全检查服务异常，请稍后重试」). Licence set on the page by rig fixture. | 09–12 | — |
| A5 | Apply step 4: identity + consent, submit | PASS | 13–16 | `mms_merchant` 8006 status 0; `ums_member_identity` encrypted, source merchant_apply |
| A6 | Platform approves (admin API `PUT /business/merchant {status:1}`) | PASS (admin web UI not walked) | — | 8006 status 1, account_status 1 |
| A7 | Reopen app → merchant home | PASS | 17 | — |
| A8 | 品牌中心 → 门店信息 → fix address | PASS | 19–22 | address 上海市徐汇区复兴西路62号, 31.2085/121.4426 |

STATUS: PARTIAL - walk in progress
