PARTIAL

Backend works (2026-11-02 ~04:25 UTC): `bash up.sh` brings up real MySQL 8.0.45 + Redis 7.4 (Docker), builds cy_e2e_51,
seeds fixtures, compiles the e2e stubs and starts the candidate a1d30fb on 127.0.0.1:8099 in ~75 s (plus ~2 min jar build).
`chains.py player` ran platform → merchant_club → sale_close → player: all PASS (receipts in evidence/).
Browser runtime (mpweb/) is still being built; this file will be updated. See RIG.md (coming).
