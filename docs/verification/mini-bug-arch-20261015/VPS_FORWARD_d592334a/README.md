# VPS FORWARD d592334a - fixed-clock IT compilation

COMPILE PASS ONLY. Actual tests: 0. All 11 PlayerGrowthConcurrencyMySqlIT methods remain NOT_RUN against true MySQL.

- Frozen source: `d592334abee1a379963e7b72a58eec5e0f7bd5ad`. Relative to bde, only the original MySQL IT clock/seed date and three owned coordination documents changed. Production, dependencies and all 11 assertions are unchanged. The previous 28 ordinary tests were not repeated.
- Archive SHA256: `69bcf8ee5c2347030bf81c7f780556b914b94a3225c59b669f65134813ac6a38`.
- Existing 212 guard: `2026-10-16T06:08:19.135291+00:00` to `2026-10-16T06:10:59.528066+00:00`, exit0/completed and BUILD SUCCESS; 1536MiB with equal memory-swap, 1CPU, 256PIDs, network none and the shared build lock.
- Both the main IT class and anonymous clock class are fresh from this attempt; exact hashes are in summary.json. No Surefire XML exists and no tests executed.
- Exact owned container `528f45346b1fe71f5db5d028bbf9e47bbd50bbf782eaf195d59c0a116d9eac3c` was confirmed absent using explicit chengyin-aws filtered discovery.

The first admission at 05:52:29Z returned exit75/another_build_holds_lock/containerId null, with no compilation and zero tests. Original run/result/summary are retained as *-admission-refused-055229Z.json. Retry followed normal lock release; the initial receipt is preserved in summary.json.

Task `/home/ubuntu/previews/cy-vps-forward-d592334a-20261016` retains source/cache, archive, selection, run/guard receipts, compilation log and class files. Compiling a deterministic fixture does not establish real database concurrency or midnight behavior.

True MySQL/Redis, runtime HTTP, device, business acceptance, integration, merge and deployment remain unverified. The human decision is to retain existing entries without adding one. No change to 212/CI/network and no manual CI trigger.
