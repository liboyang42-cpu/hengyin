/** Strict executed-result gate. Discovery/list output is never execution evidence. */
const path = require('node:path')
const { readRegular } = require('./server/audit-bundle-provenance')
const SCREENSHOTS = ['candidate-evidence-desktop', 'candidate-evidence-narrow', 'frozen-partial-text-safe-rendering']
const EXPECTED_COUNT = 50
const FILE_COUNTS = { 'audit-console.browser.test.js': 25, 'candidate-preview.browser.test.js': 18, 'npc-generic-boundary.browser.test.js': 7 }
const demand = (condition, message) => { if (!condition) throw new Error(`Audit browser gate: ${message}`) }
function assertInvocation(args) {
  demand(Array.isArray(args) && args.length === 0, 'no arguments accepted; list, focus, filters, shards, retries and reporter overrides are forbidden')
}
function flatten(suites, ancestors = []) {
  demand(Array.isArray(suites), 'missing suites')
  return suites.flatMap(suite => {
    demand(suite && Array.isArray(suite.specs), 'malformed suite')
    return [...suite.specs.map(spec => ({ ...spec, fullTitle: [...ancestors, suite.title, spec.title].join(' > ') })),
      ...flatten(suite.suites || [], [...ancestors, suite.title])]
  })
}
function validateReport(report, { startedAt, uiRoot, now = Date.now(), evidenceRoot = path.join(uiRoot, 'tests/e2e/evidence/audit-browser') }) {
  demand(report && report.config && report.stats && Array.isArray(report.errors) && report.errors.length === 0, 'missing report or global errors')
  const config = report.config
  demand(config.version === '1.62.0', 'unexpected Playwright version')
  demand(config.forbidOnly === true && config.workers === 1 && config.fullyParallel === false && config.shard === null, 'unsafe execution config')
  demand(config.failOnFlakyTests === true && config.globalTimeout === 8 * 60 * 1000, 'flaky-test gate disabled')
  demand(config.configFile === path.join(uiRoot, 'tests/e2e/playwright.audit.config.js'), 'wrong config file')
  demand(config.rootDir === path.join(uiRoot, 'tests/e2e/audit'), 'wrong suite root')
  demand(config.grepInvert === null && config.maxFailures === 0 && JSON.stringify(config.grep) === '{}', 'filtered execution')
  const argv = config.argv
  demand(Array.isArray(argv) && argv.length === 4 && argv[2] === 'test'
    && argv[3] === '--config=./tests/e2e/playwright.audit.config.js', 'noncanonical command')
  demand(Array.isArray(config.projects) && config.projects.length === 1, 'unexpected project count')
  const project = config.projects[0]
  demand(project.name === 'audit-chromium' && project.id === 'audit-chromium' && project.retries === 0
    && project.repeatEach === 1 && project.testDir === config.rootDir
    && JSON.stringify(project.testMatch) === '["**/*.browser.test.js"]'
    && Array.isArray(project.testIgnore) && project.testIgnore.length === 0, 'retries, repeat, project or test selection changed')
  const stats = report.stats
  demand(stats.expected === EXPECTED_COUNT && stats.skipped === 0 && stats.unexpected === 0 && stats.flaky === 0, 'expected exactly 50 passes and zero skipped/unexpected/flaky')
  const begin = Date.parse(stats.startTime)
  demand(Number.isFinite(begin) && begin >= startedAt && begin <= now && Number.isFinite(stats.duration) && stats.duration > 0, 'stale or invalid run timestamp')
  const specs = flatten(report.suites)
  demand(specs.length === EXPECTED_COUNT, 'expected exactly 50 specs')
  const ids = new Set(), titles = new Set(), counts = {}, resultTimes = []
  let integration = 0
  for (const spec of specs) {
    demand(typeof spec.id === 'string' && spec.id.length > 0 && !ids.has(spec.id), 'missing or duplicate spec ID')
    ids.add(spec.id)
    demand(typeof spec.title === 'string' && spec.title.length > 0 && !titles.has(spec.fullTitle), 'missing or duplicate title')
    titles.add(spec.fullTitle)
    demand(Object.hasOwn(FILE_COUNTS, spec.file), 'unexpected test file')
    counts[spec.file] = (counts[spec.file] || 0) + 1
    demand(spec.ok === true && Array.isArray(spec.tests) && spec.tests.length === 1, 'spec did not pass exactly one project')
    const current = spec.tests[0]
    demand(current.projectName === 'audit-chromium' && current.projectId === 'audit-chromium'
      && current.expectedStatus === 'passed' && current.status === 'expected', 'test failed, skipped, flaky or expected-to-fail')
    demand(Array.isArray(current.annotations) && !current.annotations.some(annotation => ['skip', 'fixme', 'fail', 'slow', 'only'].includes(annotation.type)), 'disallowed test annotation')
    demand(Array.isArray(current.results) && current.results.length === 1, 'missing execution or retry')
    const result = current.results[0]
    demand(result.status === 'passed' && result.retry === 0 && Number.isInteger(result.workerIndex) && result.workerIndex >= 0
      && Number.isInteger(result.parallelIndex) && result.parallelIndex >= 0 && Number.isFinite(result.duration) && result.duration > 0
      && Array.isArray(result.errors) && result.errors.length === 0 && !result.error, 'invalid executed result')
    const ledgers = (result.attachments || []).filter(attachment => attachment.name === 'synthetic-network-ledger')
    demand(ledgers.length === 1 && ledgers[0].contentType === 'application/json' && typeof ledgers[0].body === 'string', 'missing inline network ledger')
    let ledger
    try { ledger = JSON.parse(Buffer.from(ledgers[0].body, 'base64').toString('utf8')) } catch (_) { demand(false, 'invalid network ledger') }
    demand(ledger && Array.isArray(ledger.calls) && ledger.calls.length > 0
      && Array.isArray(ledger.blocked) && ledger.blocked.length === 0
      && Array.isArray(ledger.pageErrors) && ledger.pageErrors.length === 0, 'network isolation or application errors')
    const time = Date.parse(result.startTime)
    demand(Number.isFinite(time) && time >= begin && time <= now, 'stale test result')
    resultTimes.push(time)
    const tags = spec.tags || []
    demand(Array.isArray(tags), 'malformed tags')
    const tagged = tags.includes('integration') // Playwright JSON strips the leading @.
    demand(tagged === spec.title.startsWith('[browser-integration] '), 'integration title/tag mismatch')
    if (tagged) integration++
  }
  for (const [file, count] of Object.entries(FILE_COUNTS)) demand(counts[file] === count, `wrong count for ${file}`)
  demand(integration === 5, 'expected exactly 45 user-flow cases and 5 explicitly tagged browser-integration cases')
  validateScreenshots(specs.flatMap(spec => spec.tests[0].results[0].attachments), evidenceRoot)
  return { executed: specs.length, userFlow: specs.length - integration, integration }
}
function validateScreenshots(attachments, evidenceRoot) {
  for (const name of SCREENSHOTS) {
    const matching = attachments.filter(attachment => attachment.name === name)
    demand(matching.length === 1 && matching[0].contentType === 'image/png' && typeof matching[0].path === 'string', `missing unique PNG evidence: ${name}`)
    const absolute = path.resolve(matching[0].path)
    const relative = path.relative(evidenceRoot, absolute)
    demand(relative && !path.isAbsolute(relative) && !relative.split(path.sep).includes('..'), 'screenshot outside evidence root')
    const bytes = readRegular(evidenceRoot, relative.split(path.sep).join('/'))
    demand(bytes.length > 32 && bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10]))
      && bytes.subarray(12, 16).toString('ascii') === 'IHDR' && bytes.readUInt32BE(16) > 0 && bytes.readUInt32BE(20) > 0, `invalid PNG evidence: ${name}`)
  }
}
module.exports = { SCREENSHOTS, validateScreenshots, EXPECTED_COUNT, FILE_COUNTS, assertInvocation, validateReport }
