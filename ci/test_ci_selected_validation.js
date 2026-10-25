'use strict'
// SOURCE controls only; synthetic job receipts, no workflow, Node mini suite or service starts.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const gate = require('./ci-selected-validation')
const full = { event: 'workflow_dispatch', scope: 'full', jobs: '', shards: '[]' }
const input = { ...full, scope: 'selected', jobs: 'backend,mini_unit,mini_static', shards: '[1,7]' }
const id = { sourceSha: 'a'.repeat(40), runId: '123', runAttempt: '2' }
const plan = () => ({ schema: gate.SCHEMA, ...id, ...gate.select(input), manifest: { files: 300, sha256: 'b'.repeat(64) } })
const needs = p => Object.fromEntries(['changes', 'secret_scan', ...gate.JOBS].map(job =>
  [job, { result: ['changes', 'secret_scan', ...p.jobs].includes(job) ? 'success' : 'skipped' }]))
const receipts = p => p.shards.map(shard => ({ schema: gate.SCHEMA, ...id, scope: p.scope, shard,
  unitStepOutcome: 'success', verdict: 'PASS_SELECTED_SHARD', manifestSha256: p.manifest.sha256 }))

test('PR/push/default manual preserve original full routing, selection is never a fallback', () => {
  assert.equal(gate.select(full).selected, false)
  for (const event of ['push', 'pull_request']) assert.equal(gate.select({ ...full, event, scope: '' }).selected, false)
  assert.deepEqual(gate.select(input).shards, [1, 7])
  for (const changed of [{ scope: '' }, { scope: 'FULL' }, { jobs: 'backend' }, { shards: '[1]' }]) {
    assert.throws(() => gate.select({ ...full, ...changed }))
  }
  assert.throws(() => gate.select({ ...input, event: 'pull_request' }))
})

test('only exact job IDs and unique explicit native shard numbers enter', () => {
  for (const changed of [{ jobs: '' }, { jobs: 'backend,backend' }, { jobs: 'secret_scan' },
    { jobs: 'backend,other' }, { jobs: ' backend' }, { jobs: 'backend;canary' }, { jobs: 'backend\ncanary' },
    { shards: '' }, { shards: '[1,1]' }, { shards: '[0]' }, { shards: '[21]' }, { shards: '["1"]' },
    { shards: '[true]' }, { shards: 'null' }, { shards: '{}' }, { shards: '[]' },
    { jobs: 'backend', shards: '[1]' }]) assert.throws(() => gate.select({ ...input, ...changed }))
  assert.equal(gate.select({ ...input, jobs: 'backend', shards: '[]' }).selected, true)
  assert.equal(gate.select({ ...input, shards: JSON.stringify(Array.from({ length: 20 }, (_, i) => i + 1)) }).shards.length, 20)
})

test('selected jobs and exact shard receipts sign only PARTIAL, never full CI', () => {
  const p = plan(), result = gate.selectedSummary(p, needs(p), receipts(p))
  assert.equal(result.verdict, 'PASS_SELECTED_ONLY')
  assert.equal(result.fullSuiteVerified, false)
  assert.equal(result.scope, 'PARTIAL_SELECTED_ONLY')
  assert.deepEqual(result.selectedShards, [1, 7])
  const noMini = { ...p, jobs: ['backend'], shards: [], manifest: null }
  assert.equal(gate.selectedSummary(noMini, needs(noMini), []).verdict, 'PASS_SELECTED_ONLY')
})

test('missing/unexecuted jobs, foreign, duplicate and incomplete mini evidence are red', () => {
  const p = plan()
  for (const job of ['changes', 'secret_scan', ...p.jobs]) {
    for (const result of ['failure', 'cancelled', 'skipped', undefined]) {
      const n = needs(p); n[job] = result ? { result } : undefined
      assert.equal(gate.selectedSummary(p, n, receipts(p)).verdict, 'FAIL')
    }
  }
  const unexpected = needs(p); unexpected.admin_ui.result = 'success'
  assert.equal(gate.selectedSummary(p, unexpected, receipts(p)).verdict, 'FAIL')
  for (const mutate of [rows => rows.pop(), rows => rows.push(rows[0]), rows => { rows[1] = rows[0] },
    rows => { rows[0].sourceSha = 'c'.repeat(40) }, rows => { rows[0].runId = 'old' },
    rows => { rows[0].runAttempt = '1' }, rows => { rows[0].schema = 'foreign' },
    rows => { rows[0].unitStepOutcome = 'skipped' }, rows => { rows[0].verdict = 'PASS' },
    rows => { rows[0].manifestSha256 = 'c'.repeat(64) }, rows => { rows[0].shard = 20 }]) {
    const rows = receipts(p); mutate(rows)
    assert.equal(gate.selectedSummary(p, needs(p), rows).verdict, 'FAIL')
  }
  assert.equal(gate.selectedSummary(p, needs(p), null).verdict, 'FAIL')
})

test('source/run/attempt identities are exact, not old green evidence', () => {
  const env = { GITHUB_REPOSITORY: 'liboyang42-cpu/hengyin', GITHUB_SHA: id.sourceSha,
    GITHUB_RUN_ID: id.runId, GITHUB_RUN_ATTEMPT: id.runAttempt }
  assert.deepEqual(gate.identity(env), id)
  for (const key of Object.keys(env)) assert.throws(() => gate.identity({ ...env, [key]: '' }))
  gate.sameIdentity({ schema: gate.SCHEMA, ...id }, id)
  assert.throws(() => gate.sameIdentity({ schema: gate.SCHEMA, ...id, runAttempt: '1' }, id))
})

test('actual CI keeps native 20-way runner, unconditional secret scan, full required tests and partial output', () => {
  const yaml = fs.readFileSync(path.join(__dirname, '../.github/workflows/ci.yml'), 'utf8')
  const job = name => yaml.split('\n  ' + name + ':\n')[1].split(/\n  [a-z_]+:\n/)[0]
  assert.match(yaml, /options: \[full, selected\]/)
  assert.match(yaml, /run-name:.*PARTIAL SELECTED CI/)
  assert.match(yaml, /&& '-partial' \|\| ''/)
  const unit = job('mini_unit')
  for (const token of ['max-parallel: 20', 'fail-fast: false', '19, 20]',
    'node ci/xcx-unit-manifest.js --selftest', 'node --test --test-shard=${{ matrix.shard }}/20 "tests/unit/**/*.test.js"',
    'exclude: ${{ fromJSON(needs.changes.outputs.mini_exclude) }}', 'CI_UNIT_OUTCOME: ${{ steps.unit.outcome }}',
    'ci-selected-mini-${{ matrix.shard }}-a${{ github.run_attempt }}']) assert.ok(unit.includes(token), token)
  const secret = job('secret_scan')
  assert.doesNotMatch(secret, /\n    (if|needs):/)
  const mini = job('mini_program')
  for (const token of ['PARTIAL SELECTED CI summary', "if: ${{ needs.changes.outputs.selected != 'true' }}",
    'test "$CHANGES_RESULT" = success', 'test "$UNIT_RESULT" = success', 'test "$STATIC_RESULT" = success',
    'CI_NEEDS: ${{ toJSON(needs) }}', 'ci-selected-summary-${{ github.run_attempt }}']) assert.ok(mini.includes(token), token)
  for (const name of gate.JOBS) {
    assert.ok(job(name).includes('PARTIAL SELECTED'))
    assert.ok(job(name).includes("needs.changes.outputs.run_" + name + " == 'true'"))
  }
})
