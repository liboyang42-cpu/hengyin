/** Separate receipt for the test-only W20 build. Never accepts a production activation. */
const fs = require('node:fs')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { readRegular, inventory, sourceInventory, digest } = require('../server/audit-bundle-provenance')
const UI_ROOT = path.resolve(__dirname, '../../..')
const OUTPUT = 'tests/e2e/evidence/city-board-dist'
const RECEIPT = '.city-board-test-build.json'
const COMMAND = 'vue-cli-service build --mode production --dest tests/e2e/evidence/city-board-dist'
const ENV = { NODE_ENV: 'production', VUE_APP_BASE_API: '/prod-api', VUE_APP_CITY_BOARD_DRAFTS: 'true' }
const fail = message => { throw new Error(`City board test provenance: ${message}`) }
function inputs(uiRoot) {
  return { ...sourceInventory(uiRoot), ...inventory(uiRoot, ['tests/e2e/city-board']) }
}
function gitCommit(uiRoot) { return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: uiRoot, encoding: 'utf8' }).trim() }
function verifyReceipt(uiRoot = UI_ROOT, expectedCommit) {
  const dist = path.join(uiRoot, OUTPUT)
  const receipt = JSON.parse(readRegular(dist, RECEIPT).toString('utf8'))
  if (receipt.schema !== 1 || receipt.purpose !== 'SYNTHETIC_API_BROWSER_ONLY' || receipt.command !== COMMAND
      || JSON.stringify(receipt.environment) !== JSON.stringify(ENV) || !/^[a-f0-9]{40}$/.test(receipt.commit)
      || !Number.isFinite(Date.parse(receipt.builtAt))) fail('invalid test-build receipt')
  if (receipt.commit !== (expectedCommit || process.env.GITHUB_SHA || gitCommit(uiRoot))) fail('wrong checkout commit')
  const outputs = inventory(dist, fs.readdirSync(dist).sort(), new Set([RECEIPT]))
  if (JSON.stringify(inputs(uiRoot)) !== JSON.stringify(receipt.inputs)) fail('changed build/test inputs')
  if (JSON.stringify(outputs) !== JSON.stringify(receipt.outputs)) fail('changed or incomplete bundle')
  if (!outputs['index.html'] || !Object.keys(outputs).some(p => /^static\/js\/app\.[a-f0-9]+\.js$/.test(p))) fail('missing compiled app')
  return { receipt, dist, outputs }
}
function stamp(uiRoot, commit) {
  if (commit !== gitCommit(uiRoot)) fail('commit must match HEAD')
  const dist = path.join(uiRoot, OUTPUT)
  const receipt = { schema: 1, purpose: 'SYNTHETIC_API_BROWSER_ONLY', command: COMMAND, environment: ENV,
    commit, builtAt: new Date().toISOString(), inputs: inputs(uiRoot), outputs: inventory(dist, fs.readdirSync(dist).sort()) }
  fs.writeFileSync(path.join(dist, RECEIPT), `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx', mode: 0o600 })
  verifyReceipt(uiRoot, commit)
}
module.exports = { OUTPUT, RECEIPT, COMMAND, ENV, readRegular, inventory, digest, verifyReceipt, stamp, inputs }
