const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const sourceRoot = path.join(__dirname, '../../src')
const tokenSource = fs.readFileSync(
  path.join(sourceRoot, 'assets/styles/tokens.scss'),
  'utf8'
)
const dynamicTokens = new Set(['--cy-detail-grid-columns'])

function collectFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return collectFiles(entryPath)
    return /\.(vue|scss)$/.test(entry.name) ? [entryPath] : []
  })
}

test('后台样式引用的设计 token 都有可解析定义', () => {
  const definedTokens = new Set([...tokenSource.matchAll(/(--cy-[\w-]+)\s*:/g)].map(match => match[1]))
  const usedTokens = new Set(
    collectFiles(sourceRoot).flatMap(file => [
      ...fs.readFileSync(file, 'utf8').matchAll(/var\((--cy-[\w-]+)/g)
    ].map(match => match[1]))
  )
  const unresolvedTokens = [...usedTokens]
    .filter(token => !definedTokens.has(token) && !dynamicTokens.has(token))
    .sort()

  assert.deepEqual(unresolvedTokens, [])
})
