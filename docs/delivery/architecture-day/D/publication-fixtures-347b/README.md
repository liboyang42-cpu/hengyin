# 347b publication fixture SOURCE repair

Parent: `347bcd0226c3bed71616e38ea7c1391fc76efe5c`.
Helper-only prerequisite: `bc2b738fbd58dea6c8eabdb9d00eea22b21f364c`.
Only test sources and this D delivery directory change; production/schema/dependencies/CI are unchanged.

## Shared assembly contract

The original `wire/set/get/owner/wireRelocatedCollaborators` signatures remain. Set the original topic/chapter/node/template mapper ports and real `ThemeStateKeyService`, then call `wire`. Missing publication runtime/ref owners use their real implementations and these same ports. Plain mock current-read methods bridge to the fixture's original rows only when that method has no explicit stub. Real mapper beans/spies and explicit current-read stubs remain in place; no empty snapshot is manufactured. Real owner spies are retained.

`transactional` is an explicit extra helper for mapper-double unit fixtures. It creates a genuine Spring transaction interceptor and DataSourceTransactionManager around a controlled connection double that refuses SQL. This proves no database behavior. Existing AOP proxies are returned unchanged. The H2 rollback fixture retains its original real DataSource and transaction proxy.

## Owned corrections

- PartialSave: the supplied original XML establishes the loop ordinary select → lambda114 → ordinary select. Restubbing `when(currentRead)` executed the bridge and bound the nested ordinary read; use `doAnswer/doReturn` instead. Insert readback also uses `doReturn`. Capture original graph inserts/deletes for current reads; retain all draft, permission, amount, completeness, no-side-effect and same-ID assertions.
- ChapterFlowTransaction: real state-key/ref/runtime assembly; scratch node adapter persists and reads the actual status field. Original old rows explicitly remain active. Both original block-write/audit failure injection points and every JDBC rollback assertion remain.
- ChapterFlowWrite: current rows follow the original insert/update/delete Answers, separate from original historical capture lists. Real declaration/runtime assembly joins the explicit Spring transaction. Original exact two admission parent reads are asserted at the first original budget fence, before newly required compiler/declaration reads; the original parent→open-review→parent order and all final business assertions remain. The story-only positive publication fixture retains its story and adds a real playable node/block persisted by the original graph writer (formal freezing rejects zero playable nodes).
- Original case names retained: PartialSave 19; ChapterFlowTransaction 2; ChapterFlowWrite 44. No new/removed/disabled cases.

## Transferred seven

`transferred-seven-unfinished.review.patch.b64` preserves the edits already present when ownership was reassigned. It is a partial donor, not an apply-ready seven-class repair and not part of the four Java source postimages. No further edits were made after reassignment. Its seven classes still need their new owners' complete positive-content/current-fact assembly; published zero-node rows cannot satisfy real freezing. Consumers should transfer only relevant hunks rather than overwrite parallel work.

## Evidence limits

SOURCE only. No parser, compilation, Node/Java/JUnit/H2/MySQL/HTTP/provider/CI execution in this repair. All runtime checks are NOT_RUN. Git diffs/source inventory, exact preimage blob hashes and SHA256 manifests are source metadata, not acceptance evidence. No production DDL or changes to amounts, authorization, UNKNOWN, identity, cleanup or publication guards.

Root transport normalization: the unfinished review patch is base64 encoded without changing its decoded bytes, avoiding literal diff-context trailing spaces in repository documentation. Decoded SHA256: `a699c0d91af88dfee7819c7ceb300819abe44a5687741cb9351887d012e4b5e5`. The seven Java donor files remain unapplied until their new owners finish them.
