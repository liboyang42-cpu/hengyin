/** Fixed entrypoint. Arguments cannot turn discovery/subsets/retries into a green gate. */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const { assertInvocation, validateReport } = require('./report-validator')
const { validateWitness } = require('./gate-reporter')
const { verifyReceipt, readRegular, RECEIPT } = require('./bundle-provenance')
const UI_ROOT = path.resolve(__dirname, '../../..')
const EVIDENCE = path.join(__dirname, '../evidence/city-board-browser')
const TIMEOUT_MS = 9 * 60 * 1000
function resetEvidence(root = EVIDENCE) {
  // Never follow an evidence symlink into an unrelated directory during cleanup.
  for (let current = root; current !== UI_ROOT && current !== path.dirname(current); current = path.dirname(current)) {
    if (fs.existsSync(current) && fs.lstatSync(current).isSymbolicLink()) throw new Error('Evidence path may not contain symlinks')
  }
  fs.rmSync(root, { recursive: true, force: true })
  fs.mkdirSync(root, { recursive: true })
}
function childEnvironment(runId) {
  const env = { ...process.env, CITY_BOARD_BROWSER_GATE_RUN_ID: runId, CITY_BOARD_BROWSER_GATE_PARENT_PID: String(process.pid) }
  // Prevent alternate runtimes, remote browser endpoints or reporter destinations.
  for (const key of Object.keys(env)) if (/^(?:PW_TEST_|PLAYWRIGHT_JSON_|PLAYWRIGHT_HTML_)/.test(key)) delete env[key]
  for (const key of Object.keys(env)) if (/^(?:http_proxy|https_proxy|all_proxy|ftp_proxy|no_proxy)$/i.test(key)) delete env[key]
  delete env.PLAYWRIGHT_TEST_CONNECT_WS_ENDPOINT
  delete env.PLAYWRIGHT_NODEJS_PATH
  delete env.SELENIUM_REMOTE_URL
  delete env.NODE_OPTIONS
  delete env.NODE_PATH
  return env
}
async function main(args = process.argv.slice(2)) {
  let child, timeout, killTimer, cleanupPromise, evidenceReady = false, interrupted = false
  const runId = crypto.randomBytes(16).toString('hex')
  const startedAt = Date.now()
  const stop = signal => {
    interrupted = true
    if (child && child.exitCode === null) {
      try { if (process.platform === 'win32') child.kill(signal); else process.kill(-child.pid, signal) } catch (_) {}
      if (!cleanupPromise) cleanupPromise = new Promise(resolve => {
        killTimer = setTimeout(() => {
          try { if (process.platform === 'win32') child.kill('SIGKILL'); else process.kill(-child.pid, 'SIGKILL') } catch (_) {}
          resolve()
        }, 5000)
      })
    }
  }
  const onTerm = () => stop('SIGTERM')
  const onInt = () => stop('SIGINT')
  try {
    resetEvidence()
    evidenceReady = true
    assertInvocation(args)
    const { receipt, dist } = verifyReceipt(UI_ROOT)
    const buildReceiptBytes = readRegular(dist, RECEIPT)
    const buildReceiptSha256 = crypto.createHash('sha256').update(buildReceiptBytes).digest('hex')
    fs.writeFileSync(path.join(EVIDENCE, 'build-receipt.json'), buildReceiptBytes, { flag: 'wx' })
    const pkg = require(path.join(UI_ROOT, 'node_modules/@playwright/test/package.json'))
    if (pkg.version !== '1.62.0') throw new Error('Expected locked @playwright/test 1.62.0')
    const cli = path.join(path.dirname(require.resolve('playwright/package.json', { paths: [UI_ROOT] })), 'cli.js')
    child = spawn(process.execPath, [cli, 'test', '--config=./tests/e2e/city-board/playwright.config.js'], {
      cwd: UI_ROOT, stdio: 'inherit', env: childEnvironment(runId), detached: process.platform !== 'win32'
    })
    process.once('SIGTERM', onTerm)
    process.once('SIGINT', onInt)
    timeout = setTimeout(() => stop('SIGTERM'), TIMEOUT_MS)
    const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', (status, signal) => resolve(signal ? 1 : status)) })
    if (interrupted || code !== 0) throw new Error(`Browser process ${interrupted ? 'interrupted/timed out' : `exited ${code}`}; not a browser pass`)
    const report = JSON.parse(readRegular(EVIDENCE, 'report.json').toString('utf8'))
    const summary = validateReport(report, { startedAt, uiRoot: UI_ROOT })
    const runtime = JSON.parse(readRegular(EVIDENCE, 'runtime.json').toString('utf8'))
    const ids = validateWitness(runtime, runId)
    const collect = suites => suites.flatMap(suite => [...suite.specs.map(spec => spec.id), ...collect(suite.suites || [])])
    if (JSON.stringify(ids) !== JSON.stringify(collect(report.suites).sort())) throw new Error('JSON report and live runtime witness disagree')
    verifyReceipt(UI_ROOT, receipt.commit)
    if (!readRegular(dist, RECEIPT).equals(buildReceiptBytes)) throw new Error('Build receipt changed during browser execution')
    fs.writeFileSync(path.join(EVIDENCE, 'gate.json'), `${JSON.stringify({ status: 'passed', runId, startedAt: new Date(startedAt).toISOString(), commit: receipt.commit, buildReceiptSha256, ...summary }, null, 2)}\n`, { flag: 'wx' })
    console.log(`City board browser gate: ${summary.executed} executed, ${summary.userFlow} browser user-flow and ${summary.integration} tagged integration; zero skips/retries.`)
  } catch (error) {
    console.error(`City board browser gate FAILED: ${error.message}`)
    if (evidenceReady) {
      fs.writeFileSync(path.join(EVIDENCE, 'gate-failure.json'), `${JSON.stringify({ status: 'failed', runId, error: error.message }, null, 2)}\n`)
    }
    process.exitCode = 1
  } finally {
    clearTimeout(timeout)
    if (cleanupPromise) await cleanupPromise
    process.removeListener('SIGTERM', onTerm)
    process.removeListener('SIGINT', onInt)
  }
}
if (require.main === module) main()
module.exports = { assertInvocation, resetEvidence, childEnvironment }
