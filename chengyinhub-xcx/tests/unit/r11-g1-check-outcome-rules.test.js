/* R11 g1 F2:D20 三档难度(check)与相册(album)作为一站玩法时,服务端只在带 outcomeRules 时才开得了局。
   编辑器选中时补上结果规则(与 typeIn 同一处)。主题发布检查另由服务端
   AdvancedGameConfigValidator.outcomeRulesMissingReason 兜底(主题开了检定时 check 走旅程检定,不拦)。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

function loadTempPage() {
  const file = path.resolve(__dirname, '../../pages/publish/temp/index.js')
  return fs.readFileSync(file, 'utf8')
}

test('编辑器选 check / album 时补结果规则,形状同结果反馈编辑器', () => {
  const src = loadTempPage()
  assert.match(src, /const OUTCOME_REQUIRED_SECTIONS = \['check', 'album'\];/)
  const at = src.indexOf("if (key === 'typeIn' && e.currentTarget.dataset.r5")
  const block = src.slice(at, at + 900)
  assert.match(block, /OUTCOME_REQUIRED_SECTIONS\.includes\(game\.section\) && !advanced\.outcomeRules/)
  assert.match(block, /\{ version: 1, pass: \{\} \}, require\('\.\.\/utils\/publish\/outcome-after\.js'\)\.once\(advanced\) \? \{\} : \{ fail: \{\} \}/)
})

test('补上的结果规则能过本地校验:check 带 pass+fail,album 只带 pass', () => {
  global.wx = global.wx || { getStorageSync() {}, setStorageSync() {} }
  const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')
  const agc = require('../../pages/publish/utils/publish/advanced-game-config.js')
  const after = require('../../pages/publish/utils/publish/outcome-after.js')
  const check = catalog.applyToConfig(agc.defaultConfig(), 'check', { demo: true })
  check.outcomeRules = Object.assign({ version: 1, pass: {} }, after.once(check) ? {} : { fail: {} })
  assert.deepEqual(check.outcomeRules, { version: 1, pass: {}, fail: {} })
  assert.equal(agc.validate(check), '')
  assert.ok(agc.serialize(check).includes('"outcomeRules"'))
  const album = catalog.applyToConfig(agc.defaultConfig(), 'album')
  album.album.images = [{ url: 'https://www.chengyinhub.com/a.png', line: 'x' }]
  album.outcomeRules = Object.assign({ version: 1, pass: {} }, after.once(album) ? {} : { fail: {} })
  assert.deepEqual(album.outcomeRules, { version: 1, pass: {} })
  assert.equal(agc.validate(album), '')
})
