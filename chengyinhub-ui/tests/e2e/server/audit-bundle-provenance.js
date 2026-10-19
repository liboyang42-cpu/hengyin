/** Build receipt for the ordinary production build; never creates application assets. */
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFileSync } = require('node:child_process')
const UI_ROOT = path.resolve(__dirname, '../../..')
const RECEIPT = '.audit-production-build.json'
const SOURCE_ROOTS = ['src', 'public', 'build', 'package.json', 'package-lock.json', 'vue.config.js', 'babel.config.js', '.env', '.env.production']
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex')
const fail = message => { throw new Error(`Production bundle provenance: ${message}`) }
function readRegular(root, relative) {
  if (!relative || path.isAbsolute(relative) || relative.split(/[\\/]/).some(part => !part || part === '.' || part === '..')) fail('unsafe relative path')
  let current = root
  if (!fs.lstatSync(root).isDirectory() || fs.lstatSync(root).isSymbolicLink()) fail('root must be a real directory')
  for (const part of relative.split('/')) {
    current = path.join(current, part)
    if (fs.lstatSync(current).isSymbolicLink()) fail(`symlink rejected: ${relative}`)
  }
  const fd = fs.openSync(current, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW)
  try {
    const stat = fs.fstatSync(fd)
    if (!stat.isFile()) fail(`not a regular file: ${relative}`)
    const real = fs.realpathSync(current)
    const realRoot = fs.realpathSync(root)
    if (!real.startsWith(`${realRoot}${path.sep}`)) fail(`outside bundle: ${relative}`)
    return fs.readFileSync(fd)
  } finally { fs.closeSync(fd) }
}
function inventory(root, roots, exclude = new Set()) {
  const files = {}
  function walk(relative) {
    if (exclude.has(relative)) return
    const absolute = path.join(root, relative)
    const stat = fs.lstatSync(absolute)
    if (stat.isSymbolicLink()) fail(`symlink rejected: ${relative}`)
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(absolute).sort()) walk(relative ? `${relative}/${name}` : name)
    } else {
      const bytes = readRegular(root, relative)
      files[relative] = { sha256: digest(bytes), bytes: bytes.length }
    }
  }
  for (const relative of roots) walk(relative)
  return files
}
function sourceInventory(uiRoot) {
  // All .env variants are included: a local mode override must invalidate the receipt.
  const roots = [...new Set([...SOURCE_ROOTS, ...fs.readdirSync(uiRoot).filter(name => /^\.env(?:\.|$)/.test(name))])]
    .filter(relative => fs.existsSync(path.join(uiRoot, relative))).sort()
  for (const required of ['src', 'public', 'build', 'package.json', 'package-lock.json', 'vue.config.js']) {
    if (!roots.includes(required)) fail(`missing build input ${required}`)
  }
  return inventory(uiRoot, roots)
}
function gitCommit(uiRoot) {
  return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: uiRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}
function verifyReceipt(uiRoot = UI_ROOT, expectedCommit) {
  const dist = path.join(uiRoot, 'dist')
  const receipt = JSON.parse(readRegular(dist, RECEIPT).toString('utf8'))
  if (receipt.schema !== 1 || receipt.command !== 'npm run build:prod' || receipt.mode !== 'production'
      || !/^[a-f0-9]{40,64}$/.test(receipt.commit) || !Number.isFinite(Date.parse(receipt.builtAt))) fail('invalid build receipt')
  const expected = expectedCommit || process.env.GITHUB_SHA || gitCommit(uiRoot)
  if (receipt.commit !== expected) fail('commit does not match current checkout/CI SHA')
  const version = JSON.parse(readRegular(dist, 'version.json').toString('utf8'))
  if (version.commit !== receipt.commit || version.builtAt !== receipt.builtAt) fail('version.json disagrees with receipt')
  const inputs = sourceInventory(uiRoot)
  const outputs = inventory(dist, fs.readdirSync(dist).sort(), new Set([RECEIPT]))
  if (JSON.stringify(inputs) !== JSON.stringify(receipt.inputs)) fail('source or build configuration changed after build')
  if (JSON.stringify(outputs) !== JSON.stringify(receipt.outputs)) fail('bundle is incomplete or changed after build')
  if (!outputs['index.html'] || !Object.keys(outputs).some(name => /^static\/js\/app\.[a-f0-9]+\.js$/.test(name))) fail('missing production app bundle')
  return { receipt, dist, outputs }
}
function stamp(uiRoot, commit) {
  if (!/^[a-f0-9]{40,64}$/.test(commit) || commit !== gitCommit(uiRoot)) fail('stamp commit must match HEAD')
  const dist = path.join(uiRoot, 'dist')
  const version = JSON.parse(readRegular(dist, 'version.json').toString('utf8'))
  if (version.commit !== commit || !Number.isFinite(Date.parse(version.builtAt))) fail('missing version from successful ci/ui-build.sh')
  const receipt = { schema: 1, command: 'npm run build:prod', mode: 'production', commit, builtAt: version.builtAt,
    inputs: sourceInventory(uiRoot), outputs: inventory(dist, fs.readdirSync(dist).sort(), new Set([RECEIPT])) }
  // Called only by ci/ui-build.sh after its unmodified production command succeeds.
  const destination = path.join(dist, RECEIPT)
  if (fs.existsSync(destination) && fs.lstatSync(destination).isSymbolicLink()) fail('receipt symlink rejected')
  fs.writeFileSync(destination, `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'w', mode: 0o600 })
  verifyReceipt(uiRoot, commit)
}
if (require.main === module) {
  try {
    if (process.argv.length !== 3) fail('usage: node audit-bundle-provenance.js <commit> (via ci/ui-build.sh)')
    stamp(UI_ROOT, process.argv[2])
  } catch (error) { console.error(error.message); process.exitCode = 1 }
}
module.exports = { RECEIPT, readRegular, inventory, sourceInventory, verifyReceipt, digest }
