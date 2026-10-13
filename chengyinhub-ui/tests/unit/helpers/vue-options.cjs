const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

module.exports = function loadPage(relativePath, globals = {}) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../../src/views', relativePath), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s+[\s\S]*?\s+from\s+['"][^'"]+['"];?/g, '')
    .replace(/components:\s*\{[^}]*\},?/, '')
    .replace('export default', 'module.exports =')
  const context = { module: { exports: {} }, process: { env: {} }, getToken: () => undefined, ...globals }
  vm.runInNewContext(script, context)
  const options = context.module.exports
  const page = { ...options.methods }
  Object.assign(page, options.data.call(page))
  return { page, source }
}
