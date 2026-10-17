# Lucide seed image candidates

These 12 icons were exported without redrawing from the Lucide components in the [Chengyin Brand Handbook](https://www.figma.com/design/0kqS1aPs7etjkxjMZy6Bwc/城瘾-Brand-Handbook), Components · Icons page `70:2`. Native SVG geometry is 24×24. PNG files are transparent 192×192 rasterizations, then normalized on the test VPS by the existing compiled `UploadImagePolicy.sanitizeMetadata` using Java 21.0.9. The normal upload path re-encodes PNG metadata; canonical PNG bytes and hashes differ from the original exports. Each record retains `png.original_export_sha256`, while `png.sha256` identifies the supplied canonical file. All 442368 RGBA pixels across 12 files were compared and remained identical; a second actual sanitization produced identical bytes and SHA for every file. This normalization made zero uploads and zero provider calls. Small numeric differences from the official SVG paths were compared visually and by raster alpha shape; these are not byte-identical upstream SVGs.

The official [Lucide license](https://lucide.dev/license), pinned to upstream commit `dab294f313897bacaa968b725b3259fa37c17bd1`, is retained in full in `LICENSE`, including ISC and Feather-derived MIT copyright and permission notices. Preserve this file with all distributed copies. The machine-readable mapping includes each Figma node, pinned upstream source, original SQL fingerprint, and SVG/PNG SHA-256.

These files are candidates. Existing category and preset fields continue to contain ordinary image URLs. A local repository asset path is not a deployed URL. Real normal-upload approval, independent media-registry verification, and byte readback are required before preparing a new exact forward replacement. No upload, approval or database replacement is implied by this package. Seven preset records are TEST_FIXTURE only and remain excluded from fresh production. Route icons identify routes or landmarks; they do not represent maps. Existing route coordinates and old SQL remain unchanged.

## Actual replacement workflow

1. Verify the intended development/test backend, its task database and the real image-review provider. Use the existing authenticated Admin normal upload (`POST /common/upload`) for all 12 PNGs. The first complete run requires actual provider approval; a disabled or unavailable provider must stop the process. Do not manufacture `APPROVED` records or simulated successful receipts.
2. Retain each actual upload's `mapping_key`, `http_status`, and complete `response` (including `code`, `url`, `fileName`) in a private receipt bundle with an `uploads` array of exactly 12 entries. Keep real authentication material outside the bundle. Preserve the original bytes and response evidence. Missing or unsuccessful receipts cannot be replaced with repository paths or guessed URLs.
3. On the dedicated test VPS, use the read-only preparation tool `scripts/prepare_licensed_seed_assets.py`. Its required arguments are `--receipts <private-receipts.json> --schema <verified-cy_e2e-task-schema> --server-uuid <independently-verified-MySQL-UUID> --origin <verified-http-loopback-backend-origin> --db-port <verified-task-database-port> --output <new-private-plan.json>`. Database authentication is supplied only through `CY_ASSET_DB_USER` and `CY_ASSET_DB_PASSWORD` environment variables; never print credentials. The tool checks the actual server UUID/database, approved SYSTEM/LOCAL image registry, exact upload URL, independently fetched PNG SHA, original SQL fingerprints and uniquely matched seed records before producing output.
4. The output is a task-test-database compare-and-set parameter plan. The tool executes no database updates. It is not production SQL, proof of completed replacement, or business acceptance. A separately reviewed atomic apply and independent readback of every affected record and all unrelated fields are still required. Preserve route coordinates, custom images and non-target records; repeated application must be idempotent. Keep the seven TEST_FIXTURE presets excluded from fresh production. Device and old-client image visibility must be verified against real uploaded resources before acceptance.

| Mapping key | Existing purpose | Icon | Figma node | Execution scope |
| --- | --- | --- | --- | --- |
| preset-style-1 | 深夜城市探险 | moon-star | 591:3709 | TEST_FIXTURE |
| preset-style-2 | 咖啡漫游 | coffee | 591:4841 | TEST_FIXTURE |
| preset-style-3 | 复古胶片 Citywalk | camera | 591:5205 | TEST_FIXTURE |
| preset-style-4 | 解谜街区 | puzzle | 591:3399 | TEST_FIXTURE |
| preset-route-1 | 外滩夜线 | route | 591:3275 | TEST_FIXTURE |
| preset-route-2 | 武康路文艺线 | waypoints | 591:2449 | TEST_FIXTURE |
| preset-route-3 | 豫园老城线 | landmark | 591:4051 | TEST_FIXTURE |
| category-1 | 衣物洗护 | washing-machine | 591:2461 | MANAGED_PRODUCTION_SEED |
| category-2 | 家政维修 | wrench | 591:2403 | MANAGED_PRODUCTION_SEED |
| category-3 | 出行服务 | car-front | 591:5185 | MANAGED_PRODUCTION_SEED |
| category-4 | 礼品纪念 | gift | 591:4315 | MANAGED_PRODUCTION_SEED |
| category-5 | 生活美学 | palette | 591:3573 | MANAGED_PRODUCTION_SEED |
