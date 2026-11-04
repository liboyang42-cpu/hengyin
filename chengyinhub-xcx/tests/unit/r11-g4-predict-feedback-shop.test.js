// 第十一轮 g4 第 5 项:预测站没有承接商家时,编辑器不再提供「结果反馈」。
// 有了 outcomeRules 的预测要等这一站的商家给答案才算完(AdvancedGameRuntimeServiceImpl:predict 只认 node.merchantMemberId 的商家结算),
// 没商家就永远完不了;所以只在本站有商家(或商家自己写玩法)时才让作者配。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const P = path.join(ROOT, 'pages/publish/utils/publish/')
const cfg = require(P + 'advanced-game-config.js')
const catalog = require(P + 'node-game-catalog.js')
const matrix = require(P + 'gameplay-support-matrix.js')
const { view } = require(path.join(ROOT, 'pages/publish/temp/r5-editor.js'))

function predictConfig() {
  const c = catalog.applyToConfig(cfg.defaultConfig(), 'predict')
  Object.assign(c.predict, { question: '明天会下雨吗？', options: [{ key: 'A', label: '会' }, { key: 'B', label: '不会' }] })
  assert.equal(cfg.validate(c), '')
  return c
}

test('主题编辑器传给玩法编辑器的上下文带上本站有没有承接商家', () => {
  assert.equal(matrix.context('{}', { id: 5, merchantMemberId: 8001 }).shop, true)
  assert.equal(matrix.context('{}', { id: 5 }).shop, false)
  assert.equal(matrix.context('{}', { id: 5, merchantMemberId: 0 }).shop, false)
})

test('预测站没有商家:不列结果反馈(成功 / 失败 / 每个选项之后都不列)', () => {
  const c = predictConfig()
  const v = view(c, 0, null, matrix.context('{}', { id: 5 }))
  assert.deepEqual(v.afterRows, [])
  assert.deepEqual(v.fbRows, [])
  assert.deepEqual(v.optionAfterRows, [])
  assert.equal(c.outcomeRules, undefined, '选了预测也不会自动落 outcomeRules')
  // 没有主题上下文(从「我的玩法」单独打开)同样不提供
  assert.deepEqual(view(c, 0, null, {}).afterRows, [])
})

test('预测站有商家,或商家自己写玩法:照常提供结果反馈', () => {
  const c = predictConfig()
  const v = view(c, 0, null, matrix.context('{}', { id: 5, merchantMemberId: 8001 }))
  assert.ok(v.afterRows.some(row => row.path === 'outcomeRules.pass'))
  assert.ok(v.afterRows.some(row => /^predict\.options\.0\.after$/.test(row.path)))
  const src = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  assert.match(src, /if \(options\.scope === 'MERCHANT'\) this\._supportContext\.shop = true;/)
})

test('已经配过结果反馈的旧预测模板照常列出,作者还能关掉', () => {
  const c = predictConfig()
  c.outcomeRules = { version: 1, pass: { reply: '猜对了' }, fail: {} }
  const v = view(c, 0, null, {})
  assert.ok(v.fbRows.some(row => row.key === 'pass' && row.has))
})

test('别的玩法不受影响:没商家的选项问答照常有结果反馈', () => {
  const c = catalog.applyToConfig(cfg.defaultConfig(), 'qaPick')
  const v = view(c, 0, null, {})
  assert.ok(v.afterRows.some(row => row.path === 'outcomeRules.pass'))
})
