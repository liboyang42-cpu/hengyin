const fs = require('node:fs')
const path = require('node:path')
const TITLES = require('./manifest')
const EXPECTED_COUNT = TITLES.length
function assertInvocation(args) { if (args.length) throw new Error('Template-choice gate accepts no arguments or subset/discovery overrides') }
function validateWitness(witness, runId) {
  const requireValue = (condition, message) => { if (!condition) throw new Error(`Template-choice runtime: ${message}`) }
  requireValue(/^[a-f0-9]{32}$/.test(runId || '') && witness.runId === runId && witness.schema === 1, 'missing or stale run ID')
  requireValue(witness.status === 'passed' && witness.discovered === EXPECTED_COUNT, 'run failed or wrong discovery count')
  requireValue(Array.isArray(witness.errors) && witness.errors.length === 0, 'global errors')
  requireValue(Array.isArray(witness.begun) && witness.begun.length === EXPECTED_COUNT && Array.isArray(witness.ended) && witness.ended.length === EXPECTED_COUNT, 'all seven tests must begin and finish')
  const ids = new Set(), ended = new Set(), titles = new Set()
  for (const event of witness.begun) {
    requireValue(typeof event.id === 'string' && event.id && !ids.has(event.id) && event.retry === 0, 'duplicate or retried start')
    requireValue(TITLES.includes(event.title) && !titles.has(event.title) && event.file === 'editor.browser.test.js', 'unexpected or duplicate test identity')
    ids.add(event.id); titles.add(event.title)
  }
  for (const event of witness.ended) {
    requireValue(ids.has(event.id) && !ended.has(event.id) && event.retry === 0 && event.status === 'passed' && event.expectedStatus === 'passed', 'skipped, failed, expected failure or duplicate finish')
    ended.add(event.id)
  }
  return [...ended].sort()
}
function validateReport(report) {
  if (!report || report.errors?.length || report.stats?.expected !== EXPECTED_COUNT || report.stats?.unexpected !== 0 || report.stats?.flaky !== 0 || report.stats?.skipped !== 0) throw new Error('Template-choice JSON report must show seven expected passes, zero errors/skips/retries')
  const specs = suites => suites.flatMap(suite => [...(suite.specs || []), ...specs(suite.suites || [])])
  const found = specs(report.suites || [])
  if (found.length !== EXPECTED_COUNT || new Set(found.map(spec => spec.title)).size !== EXPECTED_COUNT) throw new Error('Template-choice JSON identities missing or duplicated')
  for (const spec of found) {
    if (!TITLES.includes(spec.title) || spec.tests.length !== 1 || spec.tests[0].projectName !== 'template-choice-chromium' || spec.tests[0].expectedStatus !== 'passed' || spec.tests[0].results.length !== 1 || spec.tests[0].results[0].status !== 'passed' || spec.tests[0].results[0].retry !== 0) throw new Error('Template-choice JSON contains unexpected identity, result or retry')
  }
  return { executed: EXPECTED_COUNT, userFlow: EXPECTED_COUNT, integration: 0 }
}
class TemplateChoiceReporter {
  constructor() { this.begun = []; this.ended = []; this.errors = []; this.discovered = 0 }
  onBegin(_, suite) { this.discovered = suite.allTests().length }
  onTestBegin(test, result) { this.begun.push({ id: test.id, title: test.title, file: path.basename(test.location.file), retry: result.retry }) }
  onTestEnd(test, result) { this.ended.push({ id: test.id, status: result.status, expectedStatus: test.expectedStatus, retry: result.retry }) }
  onError(error) { this.errors.push(error.message || String(error)) }
  onEnd(result) {
    const witness = { schema: 1, runId: process.env.AUDIT_BROWSER_GATE_RUN_ID, status: result.status, discovered: this.discovered, begun: this.begun, ended: this.ended, errors: this.errors }
    let failed = false
    try { validateWitness(witness, process.env.AUDIT_BROWSER_GATE_RUN_ID) } catch (error) { failed = true; witness.errors.push(error.message) }
    const file = path.resolve(__dirname, '../evidence/template-choice-browser/runtime.json')
    fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, `${JSON.stringify(witness, null, 2)}\n`, { flag: 'wx' })
    if (failed) return { status: 'failed' }
  }
}
module.exports = TemplateChoiceReporter
Object.assign(module.exports, { validateWitness, validateReport, assertInvocation, EXPECTED_COUNT })
