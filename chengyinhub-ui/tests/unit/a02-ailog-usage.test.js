const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/ailog/index.vue'), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .+$/gm, '')
  .replace(/export\s+default\s*/, 'return ')
const component = new Function('CyCopyValue', 'CyDataTable', 'CyFilterBar', 'CyPageHeader', 'CyStatusTag', script)({}, {}, {}, {}, {})

test('AI 日志 token formatter 区分缺失与真实零，不重新计算估算值', () => {
  const tokenText = component.methods.tokenText
  for (const value of [null, undefined]) assert.equal(tokenText(value), '未知')
  for (const value of [0, 23, '0', '9007199254740993']) assert.equal(tokenText(value), String(value))
})

// Execute the actual interpolation expressions; this is not a Vue render or visual check.
function tokenInterpolations(fragment, scope) {
  return [...fragment.matchAll(/\{\{\s*(tokenText\([^}]+\))\s*\}\}/g)]
    .map(match => new Function('tokenText', 'row', 'detail', 'return ' + match[1])(
      component.methods.tokenText, scope, scope
    ))
}

test('列表和详情的入、出、合计均消费同一 formatter，缺失不会变成零或空白', () => {
  const list = source.match(/<template #tokens="\{ row \}">([\s\S]*?)<\/template>/)[1]
  const detail = source.match(/<dt>耗时 \/ Token（估算）<\/dt>\s*<dd>([\s\S]*?)<\/dd>/)[1]
  for (const [values, expected] of [
    [{ tokenIn: null, tokenOut: undefined, tokenEst: null }, ['未知', '未知', '未知']],
    [{ tokenIn: 0, tokenOut: 0, tokenEst: 0 }, ['0', '0', '0']],
    [{ tokenIn: 12, tokenOut: 5, tokenEst: 17 }, ['12', '5', '17']]
  ]) {
    assert.deepEqual(tokenInterpolations(list, values), [expected[2], expected[0], expected[1]])
    assert.deepEqual(tokenInterpolations(detail, values), expected)
  }
  assert.doesNotMatch(list + detail, /\{\{\s*(?:row|detail)\.token(?:In|Out|Est)\b/)
})

test('列表列名和详情标题说明 token 为估算', () => {
  assert.equal(component.data().tableColumns.find(column => column.key === 'tokens').label, 'Token（估算）')
  assert.match(source, /<dt>耗时 \/ Token（估算）<\/dt>/)
})
