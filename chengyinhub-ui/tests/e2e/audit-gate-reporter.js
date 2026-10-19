/** Independent runtime witness: --list never executes onTestBegin/onTestEnd. */
const fs = require('node:fs')
const path = require('node:path')
const { EXPECTED_COUNT } = require('./audit-report-validator')
class AuditGateReporter {
  constructor() { this.begun = []; this.ended = []; this.errors = []; this.discovered = 0 }
  onBegin(_, suite) { this.discovered = suite.allTests().length }
  onTestBegin(test, result) { this.begun.push({ id: test.id, retry: result.retry }) }
  onTestEnd(test, result) { this.ended.push({ id: test.id, status: result.status, expectedStatus: test.expectedStatus, retry: result.retry }) }
  onError(error) { this.errors.push(error.message || String(error)) }
  onEnd(result) {
    const witness = { schema: 1, runId: process.env.AUDIT_BROWSER_GATE_RUN_ID, status: result.status,
      discovered: this.discovered, begun: this.begun, ended: this.ended, errors: this.errors }
    let failed = false
    try { validateWitness(witness, process.env.AUDIT_BROWSER_GATE_RUN_ID) } catch (error) { failed = true; witness.errors.push(error.message) }
    const file = path.resolve(__dirname, 'evidence/audit-browser/runtime.json')
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, `${JSON.stringify(witness, null, 2)}\n`, { flag: 'wx' })
    if (failed) return { status: 'failed' }
  }
}
function validateWitness(witness, runId) {
  const requireValue = (condition, message) => { if (!condition) throw new Error(`Audit runtime witness: ${message}`) }
  requireValue(/^[a-f0-9]{32}$/.test(runId || '') && witness.runId === runId && witness.schema === 1, 'missing or stale run ID')
  requireValue(witness.status === 'passed' && witness.discovered === EXPECTED_COUNT, 'run failed or wrong discovery count')
  requireValue(Array.isArray(witness.errors) && witness.errors.length === 0, 'global errors')
  requireValue(Array.isArray(witness.begun) && witness.begun.length === EXPECTED_COUNT
    && Array.isArray(witness.ended) && witness.ended.length === EXPECTED_COUNT, '50 tests must begin and finish')
  const begun = new Set(), ended = new Set()
  for (const event of witness.begun) {
    requireValue(typeof event.id === 'string' && event.id && !begun.has(event.id) && event.retry === 0, 'duplicate or retried start')
    begun.add(event.id)
  }
  for (const event of witness.ended) {
    requireValue(begun.has(event.id) && !ended.has(event.id) && event.retry === 0
      && event.status === 'passed' && event.expectedStatus === 'passed', 'skipped, failed, expected failure or duplicate finish')
    ended.add(event.id)
  }
  return [...ended].sort()
}
module.exports = AuditGateReporter
module.exports.validateWitness = validateWitness
