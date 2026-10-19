/** Hosted fixture only. Builds real src/main.js and router with no source rewriting. */
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { verifyReceipt: verifyProduction } = require('../server/audit-bundle-provenance')
const { OUTPUT, ENV, stamp } = require('./bundle-provenance')
const UI_ROOT = path.resolve(__dirname, '../../..')
function buildEnvironment(source) {
  const env = { ...source }
  for (const key of Object.keys(env)) if (/^(?:VUE_APP_|NODE_OPTIONS$|NODE_PATH$)/.test(key)) delete env[key]
  return { ...env, ...ENV }
}
function main() {
  if (process.argv.length !== 2 || process.env.GITHUB_ACTIONS !== 'true' || process.env.CI !== 'true') throw new Error('Only the fixed hosted CI fixture build is accepted')
  const { receipt } = verifyProduction(UI_ROOT)
  // No checked-in environment file may activate the production feature flag.
  for (const file of fs.readdirSync(UI_ROOT).filter(name => /^\.env(?:\.|$)/.test(name))) {
    if (/^\s*VUE_APP_CITY_BOARD_DRAFTS\s*=\s*['"]?true['"]?\s*$/m.test(fs.readFileSync(path.join(UI_ROOT, file), 'utf8'))) throw new Error('Production environment activates W20')
  }
  const cli = require.resolve('@vue/cli-service/bin/vue-cli-service.js', { paths: [UI_ROOT] })
  const result = spawnSync(process.execPath, [cli, 'build', '--mode', 'production', '--dest', OUTPUT], {
    cwd: UI_ROOT, env: buildEnvironment(process.env), stdio: 'inherit', timeout: 5 * 60 * 1000
  })
  if (result.error || result.status !== 0) throw new Error('Isolated compiled W20 build failed')
  stamp(UI_ROOT, receipt.commit)
  // The ordinary production dist must remain unchanged and retain its receipt.
  verifyProduction(UI_ROOT, receipt.commit)
}
if (require.main === module) { try { main() } catch (error) { console.error(error.message); process.exitCode = 1 } }
module.exports = { buildEnvironment }
