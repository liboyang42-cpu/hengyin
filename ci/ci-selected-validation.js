#!/usr/bin/env node
'use strict'
// Fixed admission/receipts for existing ci.yml jobs; native mini runner is unchanged.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const cp = require('node:child_process')
const discovery = require('./xcx-unit-manifest')
const ROOT = path.resolve(__dirname, '..')
const JOBS = ['fast_gates', 'backend', 'mini_unit', 'mini_static', 'admin_ui', 'admin_ui_contract',
  'dependency_audit', 'migration_layout', 'migration_safety']
const SCHEMA = 'CY-CI-SELECTION-1'
const need = (ok, code) => { if (!ok) throw new Error(code) }
const hash = value => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')

function select(input) {
  need(['push', 'pull_request', 'workflow_dispatch'].includes(input.event), 'INVALID_EVENT')
  need(input.event === 'workflow_dispatch' ? ['full', 'selected'].includes(input.scope)
    : input.scope === '' || input.scope === 'full', 'INVALID_SCOPE')
  let shards
  try { shards = JSON.parse(input.shards) } catch (_) { throw new Error('INVALID_SHARDS_JSON') }
  need(Array.isArray(shards) && shards.every(n => Number.isInteger(n) && n >= 1 && n <= 20)
    && new Set(shards).size === shards.length, 'INVALID_OR_DUPLICATE_SHARD')
  if (input.scope !== 'selected') {
    need(input.jobs === '' && shards.length === 0, 'FULL_HAS_SELECTION')
    return { selected: false, scope: 'FULL_DEFAULT_ROUTING', jobs: [], shards: [] }
  }
  need(/^[a-z_]+(?:,[a-z_]+)*$/.test(input.jobs), 'EMPTY_OR_INVALID_JOBS')
  const jobs = input.jobs.split(',')
  need(new Set(jobs).size === jobs.length && jobs.every(job => JOBS.includes(job)), 'UNKNOWN_OR_DUPLICATE_JOB')
  need(jobs.includes('mini_unit') ? shards.length > 0 : shards.length === 0, 'SHARDS_AND_MINI_JOB_MISMATCH')
  return { selected: true, scope: 'PARTIAL_SELECTED_ONLY', jobs, shards }
}

function identity(env) {
  need(env.GITHUB_REPOSITORY === 'liboyang42-cpu/hengyin' && /^[a-f0-9]{40}$/.test(env.GITHUB_SHA || '')
    && [env.GITHUB_RUN_ID, env.GITHUB_RUN_ATTEMPT].every(s => /^[1-9][0-9]*$/.test(s || '')), 'SOURCE_RUN_REQUIRED')
  return { sourceSha: env.GITHUB_SHA, runId: env.GITHUB_RUN_ID, runAttempt: env.GITHUB_RUN_ATTEMPT }
}

function sameIdentity(value, id) {
  need(value && value.schema === SCHEMA && Object.keys(id).every(key => value[key] === id[key]), 'FOREIGN_RECEIPT')
}

function currentManifest() {
  const value = discovery.currentManifest()
  discovery.assertUnitManifest(value)
  return { value, identity: { files: value.files.length, sha256: hash(value) } }
}

function selectedSummary(plan, needs, receipts) {
  const problems = []
  for (const job of ['changes', 'secret_scan', ...plan.jobs]) {
    if (!needs[job] || needs[job].result !== 'success') problems.push('SELECTED_JOB_NOT_SUCCESS:' + job)
  }
  for (const job of JOBS.filter(job => !plan.jobs.includes(job))) {
    if (!needs[job] || needs[job].result !== 'skipped') problems.push('UNSELECTED_JOB_NOT_SKIPPED:' + job)
  }
  try {
    need(Array.isArray(receipts) && receipts.length === plan.shards.length
      && new Set(receipts.map(row => row.shard)).size === plan.shards.length, 'MINI_RECEIPT_SET')
    for (const row of receipts) {
      sameIdentity(row, { sourceSha: plan.sourceSha, runId: plan.runId, runAttempt: plan.runAttempt })
      need(plan.shards.includes(row.shard) && row.scope === plan.scope && row.unitStepOutcome === 'success'
        && row.verdict === 'PASS_SELECTED_SHARD' && row.manifestSha256 === plan.manifest.sha256,
      'MINI_NOT_EXECUTED_OR_FOREIGN')
    }
  } catch (_) { problems.push('SELECTED_MINI_EVIDENCE_UNVERIFIED') }
  return { schema: SCHEMA, sourceSha: plan.sourceSha, runId: plan.runId, runAttempt: plan.runAttempt,
    scope: 'PARTIAL_SELECTED_ONLY', fullSuiteVerified: false, jobs: plan.jobs, selectedShards: plan.shards,
    manifest: plan.manifest, affectedSet: 'OPERATOR_SELECTED_NOT_AUTOMATICALLY_PROVEN',
    verdict: problems.length ? 'FAIL' : 'PASS_SELECTED_ONLY', problems }
}

function save(env, name, value) {
  const directory = path.join(env.RUNNER_TEMP, 'ci-selection')
  fs.mkdirSync(directory, { recursive: true })
  fs.writeFileSync(path.join(directory, name), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })
}

function miniReceipts(env, plan) {
  if (!plan.jobs.includes('mini_unit')) return []
  const root = path.join(env.RUNNER_TEMP, 'ci-selected-mini')
  const expected = plan.shards.map(n => 'ci-selected-mini-' + n + '-a' + plan.runAttempt).sort()
  need(!fs.lstatSync(root).isSymbolicLink() && JSON.stringify(fs.readdirSync(root).sort()) === JSON.stringify(expected), 'MINI_ARTIFACT_SET')
  return expected.map(name => {
    const dir = path.join(root, name), file = path.join(dir, 'mini-result.json')
    need(!fs.lstatSync(dir).isSymbolicLink() && JSON.stringify(fs.readdirSync(dir)) === '["mini-result.json"]'
      && fs.lstatSync(file).isFile() && !fs.lstatSync(file).isSymbolicLink()
      && fs.statSync(file).size <= 1024 * 1024, 'MINI_ARTIFACT_UNVERIFIED')
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  })
}

function main(env = process.env) {
  const phase = process.argv[2]
  need(process.argv.length === 3 && ['plan', 'record-mini', 'summarize'].includes(phase), 'FIXED_PHASE_REQUIRED')
  const id = identity(env)
  need(cp.execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim() === id.sourceSha, 'CHECKOUT_MISMATCH')
  if (phase === 'plan') {
    need(env.GITHUB_JOB === 'changes', 'WRONG_PLAN_JOB')
    const selection = select({ event: env.GITHUB_EVENT_NAME, scope: env.CI_VALIDATION_SCOPE,
      jobs: env.CI_SELECTED_JOBS, shards: env.CI_MINI_SHARDS })
    const manifest = selection.jobs.includes('mini_unit') ? currentManifest() : null
    const plan = { schema: SCHEMA, ...id, ...selection, manifest: manifest && manifest.identity }
    save(env, 'plan.json', plan)
    if (manifest) save(env, 'mini-manifest.json', manifest.value)
    const outputs = { selected: String(plan.selected), selection_json: JSON.stringify(plan),
      mini_exclude: JSON.stringify(plan.selected && plan.jobs.includes('mini_unit')
        ? Array.from({ length: 20 }, (_, i) => i + 1).filter(n => !plan.shards.includes(n)).map(shard => ({ shard })) : []) }
    for (const job of JOBS) outputs['run_' + job] = String(plan.jobs.includes(job))
    fs.appendFileSync(env.GITHUB_OUTPUT, Object.entries(outputs).map(([k, v]) => k + '=' + v + '\n').join(''))
    fs.appendFileSync(env.GITHUB_STEP_SUMMARY, '### ' + plan.scope + '\n\nSource `' + id.sourceSha + '`; selected jobs `' +
      plan.jobs.join(',') + '`; selected shards `' + plan.shards.join(',') + '`. No old-SHA evidence reused; selected is not full CI.\n')
    return 0
  }
  need(env.GITHUB_EVENT_NAME === 'workflow_dispatch', 'MANUAL_ONLY')
  const plan = JSON.parse(env.CI_SELECTION_PLAN)
  sameIdentity(plan, id)
  const checked = select({ event: env.GITHUB_EVENT_NAME, scope: 'selected', jobs: plan.jobs.join(','), shards: JSON.stringify(plan.shards) })
  need(plan.selected === true && plan.scope === checked.scope, 'SELECTED_PLAN_REQUIRED')
  if (plan.jobs.includes('mini_unit')) need(JSON.stringify(plan.manifest) === JSON.stringify(currentManifest().identity), 'MANIFEST_DRIFT')
  if (phase === 'record-mini') {
    const shard = Number(env.CI_MATRIX_SHARD)
    need(env.GITHUB_JOB === 'mini_unit' && String(shard) === env.CI_MATRIX_SHARD && plan.shards.includes(shard), 'WRONG_MINI_JOB')
    const success = env.CI_UNIT_OUTCOME === 'success'
    save(env, 'mini-result.json', { schema: SCHEMA, ...id, scope: plan.scope, shard,
      unitStepOutcome: env.CI_UNIT_OUTCOME || 'UNKNOWN', manifestSha256: plan.manifest.sha256,
      verdict: success ? 'PASS_SELECTED_SHARD' : 'FAIL' })
    return success ? 0 : 1
  }
  need(env.GITHUB_JOB === 'mini_program', 'WRONG_SUMMARY_JOB')
  let receipts = null
  try { receipts = miniReceipts(env, plan) } catch (_) { /* absence remains a failed selected receipt set */ }
  const result = selectedSummary(plan, JSON.parse(env.CI_NEEDS), receipts)
  save(env, 'summary.json', result)
  fs.appendFileSync(env.GITHUB_STEP_SUMMARY, '### PARTIAL SELECTED results\n\n' + result.verdict +
    '; source `' + id.sourceSha + '`. Full suite verified: **false**; not a 20-shard/full-CI result.\n')
  return result.verdict === 'PASS_SELECTED_ONLY' ? 0 : 1
}

if (require.main === module) {
  try { process.exitCode = main() } catch (_) {
    console.error('CI selection FAIL-CLOSED: admission, source identity or selected evidence was not verified')
    process.exitCode = 1
  }
}
module.exports = { JOBS, SCHEMA, select, identity, sameIdentity, selectedSummary }
