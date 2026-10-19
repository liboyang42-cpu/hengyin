# P3-1D performance harness

This directory is a safe, reproducible skeleton for D13 performance work. It does not claim current capacity and does not contain approved load-shape numbers.

## Safety gates

- Default mode is dry-run. `read-wrk2.sh`, `write-k6.sh`, and `write-jmeter.sh` print the command and open no network connection unless `--no-dry-run` is passed.
- `PERF_BASE_URL` is required for every command. The harness rejects production domains and IPs, including `chengyinhub.com`, `www.chengyinhub.com`, `api.chengyinhub.com`, and the production DB host and app server IPs (matched by SHA-256 so the IPs are not written in this public repo; extra hosts can be denied via the comma-separated `CHENGYIN_FORBIDDEN_DB_HOSTS` environment variable).
- Write-load execution is locked unless all are true: target is non-production, `PERF_ALLOW_WRITE_LOAD=I_UNDERSTAND_THIS_IS_NOT_PRODUCTION`, and `PERF_PAYMENT_MODE=stub`.
- Real payment, production write pressure, production callbacks, production users, production orders, and production secrets are out of bounds.
- Missing load tools fail closed in non-dry-run mode with `TOOL_MISSING:<tool>`. Dry-run mode does not require wrk2/k6/JMeter.

## Threshold manifest

`manifest.json` is the single parseable threshold source:

- read: P95/P99 `300/800ms`
- write transaction: P95/P99 `800/2000ms`
- payment callback processing: P95/P99 `2000/5000ms`
- upload: P95/P99 `1500/3000ms`
- business error rate: `<0.5%`
- dependency error rate: `>1%/5min` warn, alert-only

Do not add QPS, RPS, concurrency, user-count, or capacity numbers to the manifest without a new approved baseline.

## Local commands

```bash
PERF_BASE_URL=http://127.0.0.1:18080 bash ci/perf/read-wrk2.sh
PERF_BASE_URL=http://127.0.0.1:18080 bash ci/perf/write-k6.sh
PERF_BASE_URL=http://127.0.0.1:18080 bash ci/perf/write-jmeter.sh
PERF_BASE_URL=http://127.0.0.1:18080 python3 ci/perf/perf_harness.py validate-manifest
PERF_BASE_URL=http://127.0.0.1:18080 python3 ci/perf/perf_harness.py validate-result --result result.json
```

Non-dry-run read tests additionally require the operator to set wrk2 shape variables explicitly: `PERF_WRK_THREADS`, `PERF_WRK_CONNECTIONS`, `PERF_DURATION`, and `PERF_RATE_RPS`. The skeleton provides no defaults because unapproved defaults would become capacity claims.

## Environment boundary

- Docker摸底: local jar + MySQL + Redis only. Use it to find script errors, obvious application bottlenecks, and instrumentation gaps; do not translate Docker numbers into production capacity.
- 临时 ECS 校准: use an isolated, same-class temporary ECS with scrubbed seed data, stub payment, and separate callbacks. Use it to calibrate machine-level deltas after Docker摸底.
- Production: read-only low-peak probing only after explicit time-window approval; write and payment pressure stay forbidden.
