'use strict'

/* 玩法模板编辑器 UX 走查(2026-11-19,对照 Kahoot / Typeform / Questo 创作流)。
 * 主人规则:逐元素改现有界面、不重做;不加说明文字或次级标签;输入问题 = 字段下方红字。
 * 每条对应走查里的一项:
 *   1 排序与「高级设置」折叠组  2 玩法规则按玩法预填  3 商铺类型只给商家
 *   4 前置说明示例文字跟形式走  5 置灰按钮点了定位到缺项  6 选择玩法的名字 / 说明  7 参与方式有标题
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const DIR = path.join(ROOT, 'pages/publish/temp')
const RAW = fs.readFileSync(path.join(DIR, 'index.wxml'), 'utf8')
const WXML = RAW.replace(/<!--[\s\S]*?-->/g, '')
const JS = fs.readFileSync(path.join(DIR, 'index.js'), 'utf8')
const layout = require('../../pages/publish/temp/editor-layout.js')
const r5 = require('../../pages/publish/temp/r5-editor.js')
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')

const editState = WXML.slice(WXML.indexOf('<scroll-view wx:else scroll-y class="cg-scroll"'), WXML.indexOf('</scroll-view>', WXML.indexOf('<scroll-view wx:else scroll-y class="cg-scroll"')))
const at = needle => { const i = editState.indexOf(needle); assert.ok(i >= 0, '编辑态里找不到 ' + needle); return i }
const advancedStart = () => at('id="sec-advanced"')

test('1 排序:玩法 → 参与方式 → 玩法设置 → 结果反馈 → 玩法规则 → 封面 / 模板信息 → 高级设置', () => {
  const order = ['id="sec-game"', 'id="sec-join"', 'id="game-cfg"', 'id="sec-fb"', 'id="sec-rule"', 'id="sec-top"', 'id="sec-basic"', 'id="sec-advanced"']
  const pos = order.map(at)
  pos.reduce((prev, cur, i) => { assert.ok(cur > prev, order[i] + ' 应排在 ' + order[i - 1] + ' 之后'); return cur })
})

test('1 高级设置是一个折叠组,收着选填项,展开状态记在本机', () => {
  assert.equal((editState.match(/id="sec-advanced"/g) || []).length, 1)
  assert.match(editState, /bindtap="toggleAdvancedGroup"[^>]*aria-expanded="\{\{advancedOpen\}\}"/)
  const tail = editState.slice(advancedStart())
  for (const id of ['id="sec-hint"', 'id="sec-reward"', 'id="sec-template-tools"']) assert.ok(tail.includes(id), id + ' 应在高级设置里')
  for (const label of ['效果作用于', '所需材料', '作答规则']) assert.ok(tail.includes(label), label + ' 应在高级设置里')
  assert.ok(!editState.slice(0, advancedStart()).includes('效果作用于'), '效果作用于不再留在结果反馈里')
  assert.ok(!editState.slice(0, advancedStart()).includes('所需材料'), '所需材料不再留在模板信息里')
  // 折叠组里的每一块都跟着 advancedOpen
  assert.match(tail, /id="sec-reward" wx:if="\{\{ advancedOpen &&/)
  assert.match(tail, /id="sec-template-tools" wx:if="\{\{advancedOpen\}\}"/)
  // 记住展开状态
  const store = { v: {}, getStorageSync(k) { return this.v[k] }, setStorageSync(k, v) { this.v[k] = v } }
  assert.equal(layout.readAdvancedOpen(store), false)
  layout.writeAdvancedOpen(store, true)
  assert.equal(layout.readAdvancedOpen(store), true)
  layout.writeAdvancedOpen(store, false)
  assert.equal(layout.readAdvancedOpen(store), false)
  const broken = { getStorageSync() { throw new Error('x') }, setStorageSync() { throw new Error('x') } }
  assert.equal(layout.readAdvancedOpen(broken), false)
  assert.doesNotThrow(() => layout.writeAdvancedOpen(broken, true))
  assert.match(JS, /advancedOpen: editorLayout\.readAdvancedOpen\(wx\)/)
})

test('1 必填项不进高级设置;组里的项报错先展开', () => {
  const tail = editState.slice(advancedStart())
  assert.ok(!/class="req"/.test(tail), '高级设置里不能有带星号的必填项')
  // 拍照审核的次数上限是必填,留在玩法设置里
  assert.match(editState.slice(0, advancedStart()), /次数上限（1–10） <text class="req"/)
  assert.deepEqual(layout.ADVANCED_GROUP_ERRORS, ['reward'])
  assert.match(JS, /ADVANCED_GROUP_ERRORS\.indexOf\(key\) >= 0 && !this\.data\.advancedOpen\) this\.setData\(\{ advancedOpen: true \}\)/)
})

test('1 作答规则的次数上限 / 次数用完后 / 奖励分只出现在高级设置里', () => {
  const head = editState.slice(0, advancedStart())
  assert.ok(!head.includes('>奖励分<'), '玩法设置里不再有奖励分')
  assert.ok(!/>次数上限<|次数上限（0 = 不限）/.test(head), '玩法设置里不再有选填的次数上限')
  assert.ok(!head.includes('>次数用完后<'))
  assert.ok(!head.includes('bind:change="pickQaMaxTries"') && !head.includes('bind:change="pickQaReveal"'))
  const tail = editState.slice(advancedStart())
  assert.ok(tail.includes('bind:change="pickQaMaxTries"') && tail.includes('bind:change="pickQaReveal"'))
  assert.match(tail, /value="\{\{advanced\[item\.section\]\[item\.field\]\}\}"[^>]*data-section="\{\{item\.section\}\}" data-field="\{\{item\.field\}\}"[^>]*bindinput="onAdvancedField"/)

  const keys = rows => rows.map(r => r.key)
  assert.deepEqual(keys(layout.answerRuleRows({ qa: { mode: 'PICK' } }, 'qa', {})), ['qa.maxTries', 'qa.reveal', 'qa.xp'])
  assert.deepEqual(layout.answerRuleRows({ qa: { mode: 'PICK' } }, 'qa', {}).map(r => r.kind), ['qaTries', 'qaReveal', 'number'])
  assert.deepEqual(keys(layout.answerRuleRows({ qa: { mode: 'PICK', judge: false } }, 'qa', {})), ['qa.xp'])
  assert.deepEqual(keys(layout.answerRuleRows({ qa: { mode: 'TYPE' } }, 'qa', {})), ['qa.maxTries', 'qa.xp'])
  assert.deepEqual(keys(layout.answerRuleRows({}, 'typeIn', {})), ['typeIn.tries', 'typeIn.xp'])
  assert.deepEqual(keys(layout.answerRuleRows({}, 'photoCheck', {})), ['photoCheck.xp'], '拍照审核的必填次数上限不折叠')
  assert.deepEqual(keys(layout.answerRuleRows({ qa: { mode: 'TYPE' } }, 'sort', { qa: true })), ['qa.maxTries', 'qa.xp'], '多段里的问答段也算')
  assert.deepEqual(keys(layout.answerRuleRows({ blindTaste: { enabled: true } }, '', {})), ['blindTaste.xp'])
  assert.deepEqual(layout.answerRuleRows({}, 'countdown', {}), [])
  assert.match(JS, /editorLayout\.answerRuleRows\(this\.data\.advanced, this\.data\.gameSection, this\.data\.segmentOn\)/)
})

test('2 玩法规则按所选玩法预填,作者改过不覆盖', () => {
  const pick = r5.ruleSteps({ qa: { mode: 'PICK' } }, 'qa')
  assert.equal(pick.length, 3)
  assert.equal(pick[0], '玩家从选项里选一个')
  assert.equal(r5.ruleSteps({ qa: { mode: 'TYPE' } }, 'qa')[0], '玩家打字作答')
  assert.deepEqual(r5.ruleSteps({}, ''), [])
  // 与结果反馈的 1-2-3 步同一份文字
  assert.deepEqual(r5.view({ qa: { enabled: true, mode: 'PICK', options: [] } }, 0).fbSteps, r5.ruleSteps({ qa: { enabled: true, mode: 'PICK' } }, 'qa'))

  assert.equal(layout.shouldPrefillRules([''], null), true)
  assert.equal(layout.shouldPrefillRules(['  ', ''], undefined), true)
  assert.equal(layout.shouldPrefillRules(['我自己写的'], null), false)
  assert.equal(layout.shouldPrefillRules(pick, pick), true, '仍是上次自动填的,换玩法时跟着换')
  assert.equal(layout.shouldPrefillRules([pick[0] + '!', pick[1], pick[2]], pick), false, '改过一步就不碰')
  assert.match(JS, /this\._prefillRuleSteps\(\);\s*this\.refreshPreviewState\(\);/)
})

test('3 商铺类型只在商家(scope=MERCHANT)出现', () => {
  const i = at('>商铺类型<')
  const field = editState.lastIndexOf('<view class="cg-field', i)
  assert.match(editState.slice(field, i), /wx:if="\{\{ operationScope === 'MERCHANT' \}\}"/)
  assert.match(JS, /operationScope: options\.scope === 'MERCHANT' \? 'MERCHANT' : ''/)
})

test('4 前置说明 / 题干的示例文字跟问答形式走,不再借盲品的', () => {
  const qaStart = at("gameSection === 'qa' || segmentOn.qa")
  const qa = editState.slice(qaStart, editState.indexOf('作答规则', qaStart))
  assert.ok(!qa.includes('先闭上眼睛尝一口'), '问答的前置说明不能用盲品示例')
  assert.match(qa, /placeholder="\{\{qaPh\[advanced\.qa\.mode\]\.lead \|\| qaPh\.TYPE\.lead\}\}"/)
  assert.match(qa, /placeholder="\{\{qaPh\[advanced\.qa\.mode\]\.title \|\| qaPh\.TYPE\.title\}\}"/)
  const ph = layout.QA_PLACEHOLDERS
  assert.deepEqual(Object.keys(ph).sort(), ['PICK', 'SHOT', 'TYPE'])
  const leads = Object.values(ph).map(v => v.lead)
  assert.equal(new Set(leads).size, 3, '三种形式各有自己的示例')
  for (const v of Object.values(ph)) { assert.ok(!/尝/.test(v.lead + v.title)); assert.match(v.title, /^例如 /); assert.match(v.lead, /^例如 /) }
})

test('5 置灰的查看预览 / 发布玩法:点了滚到第一处缺项,红字落在字段下面', () => {
  assert.match(WXML, /disabled="\{\{!canOpenPreview\}\}"[^>]*bind:disabledtap="onPreviewDisabledTap"/)
  assert.match(WXML, /disabled="\{\{!canPublishGame\}\}"[^>]*bind:disabledtap="onPreviewDisabledTap"/)
  assert.match(JS, /onPreviewDisabledTap: function \(\) \{\s*this\.validateForm\(this\.data\.formData, false\);/)
  // 定位到字段而不是空的模块锚点
  assert.match(JS, /description: 'fld-description'/)
  assert.match(JS, /categoryIds: 'fld-categoryIds'/)
  assert.match(JS, /finish: 'sec-game'/)
  assert.match(JS, /advanced: this\.data\.advancedErrorField \? 'fld-' \+ this\.data\.advancedErrorField\.replace\('\.', '-'\) : 'game-cfg'/)
  for (const id of ['fld-description', 'fld-categoryIds', 'fld-qa-title', 'fld-qa-answerText', 'fld-qa-options', 'game-cfg', 'sec-game', 'sec-top']) {
    assert.ok(editState.includes('id="' + id + '"'), '缺锚点 ' + id)
  }
  for (const field of ['qa.title', 'qa.answerText', 'qa.options']) {
    assert.match(editState, new RegExp('<cy-inline-error kind="field"[^>]*wx:if="\\{\\{errors\\.advanced && advancedErrorField === \'' + field.replace('.', '\\.') + '\'\\}\\}"'))
  }
  // 认不出字段的错误显示在玩法设置顶部(定位 game-cfg 就能看到),认得出的不重复报
  const top = editState.slice(at('id="game-cfg"'), at('id="game-cfg"') + 900)
  assert.match(top, /wx:if="\{\{supportError \|\| advancedConfigError \|\| \(errors\.advanced && !advancedErrorField\)\}\}"/)

  assert.equal(layout.advancedErrorField('问答题干不能为空', 'qa'), 'qa.title')
  assert.equal(layout.advancedErrorField('打字问答必须填正确答案', 'qa'), 'qa.answerText')
  assert.equal(layout.advancedErrorField('选项问答须为 2 至 4 个选项', 'qa'), 'qa.options')
  assert.equal(layout.advancedErrorField('问答选项文案不能为空', 'qa'), 'qa.options')
  assert.equal(layout.advancedErrorField('估数题干不能为空', 'estimate'), '')
  assert.equal(layout.advancedErrorField('', 'qa'), '')
  assert.match(JS, /advancedErrorField: editorLayout\.advancedErrorField\(errors\.advanced, this\.data\.gameSection\)/)
})

test('6 选择玩法:两枚 D20 的名字说清差别,每一行都有一句说明', () => {
  const check = catalog.findGame('check')
  const d20 = catalog.findGame('d20')
  assert.notEqual(check.label, d20.label)
  assert.ok(!['D20', 'D20 加值'].includes(check.label) && !['D20', 'D20 加值'].includes(d20.label))
  assert.match(check.label, /三档/)
  assert.match(d20.label, /自定/)
  const groups = r5.view({}, 0).gameGroups
  const rows = [].concat(...groups.map(g => g.items))
  assert.ok(rows.length > 10)
  for (const row of rows) assert.ok(row.sub || row.reason, row.label + ' 缺一句说明')
  for (const label of ['文字问答', '闭眼盲品', '今日城市签']) {
    const row = rows.find(r => r.label === label)
    assert.ok(row && row.sub, label + ' 应有说明')
    assert.ok(row.sub.length <= 20, label + ' 说明保持一行')
  }
})

test('7 参与方式的下拉带着它的标题,排在玩法之后', () => {
  const sec = at('id="sec-join"')
  assert.ok(sec > at('id="sec-game"'))
  const after = editState.slice(sec, sec + 600)
  assert.match(after, /<text class="cg-sec__t">参与方式<\/text>/)
  assert.ok(after.indexOf('参与方式</text>') < after.indexOf('bind:change="pickParticipation"'))
})

/* ===== 真实页面行为(同 generic-gameplay-r5-editor 的装法) ===== */
let definition
const app = { globalData: {}, tips() {}, getUserID: () => 9, getAuthorization: () => '', sendRequest() {} }
const clone = v => JSON.parse(JSON.stringify(v))
const ev = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } })
function page() {
  global.getApp = () => app
  global.Page = value => { definition = value }
  global.wx = Object.assign({ getBackgroundAudioManager: () => ({ stop() {} }), showToast() {} }, require('../helpers/memory-storage.js')())
  delete require.cache[require.resolve('../../pages/publish/temp/index.js')]; require('../../pages/publish/temp/index.js')
  const p = Object.assign({}, definition, { data: clone(definition.data) })
  p.setData = function (patch, done) { for (const [k, value] of Object.entries(patch)) { const keys = k.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const key of keys.slice(0, -1)) at = at[key]; at[keys.at(-1)] = value } if (done) done() }
  p._refreshOutcomeContract = () => ({ items: [], errors: [] }); p._syncCheckDerived = () => {}; p.hasModule = () => false
  p._supportMatrix = require('./fixtures/gameplay-support-matrix.json').data
  return p
}

test('2 真实页面:选玩法预填规则,换玩法跟着换;作者改过的不覆盖;预填不算作者改动', () => {
  const p = page()
  p.pickGame(ev({ key: 'qaPick' }))
  assert.deepEqual(p.data.ruleSteps, r5.ruleSteps(p.data.advanced, 'qa'))
  assert.equal(p.data.formData.ruleInstructions, p.data.ruleSteps.join('\n'))
  assert.equal(p.data.dirty, false, '只选了玩法,退出不该弹未保存')
  p.pickGame(ev({ key: 'qaText' }))
  assert.equal(p.data.ruleSteps[0], '玩家打字作答', '还是自动填的那份,跟着玩法换')
  p.onRuleStepInput(ev({ index: 0 }, '先去桥头找石碑'))
  p.pickGame(ev({ key: 'qaPick' }))
  assert.equal(p.data.ruleSteps[0], '先去桥头找石碑', '作者改过就不碰')
})

test('1/5 真实页面:高级设置开合记在本机;完成奖励报错先展开;题干缺项定位到题干', () => {
  const p = page()
  assert.equal(p.data.advancedOpen, false)
  p.toggleAdvancedGroup()
  assert.equal(p.data.advancedOpen, true)
  assert.equal(layout.readAdvancedOpen(global.wx), true)
  p.toggleAdvancedGroup()
  assert.equal(layout.readAdvancedOpen(global.wx), false)

  const scrolled = []
  const realTimeout = global.setTimeout
  global.setTimeout = fn => { fn(); return 0 }
  try {
    const set = p.setData
    p.setData = function (patch, done) { if (patch.scrollIntoView) scrolled.push(patch.scrollIntoView); return set.call(this, patch, done) }
    p.hasModule = key => key === 'reward'
    p.scrollToError('reward')
    assert.equal(p.data.advancedOpen, true, '组里的项报错先展开')
    assert.equal(scrolled.pop(), 'sec-reward')
    p.data.advancedErrorField = 'qa.title'
    p.scrollToError('advanced')
    assert.equal(scrolled.pop(), 'fld-qa-title')
    p.data.advancedErrorField = ''
    p.scrollToError('advanced')
    assert.equal(scrolled.pop(), 'game-cfg')
    p.scrollToError('finish')
    assert.equal(scrolled.pop(), 'sec-game')
  } finally { global.setTimeout = realTimeout }
})

test('5 缺项按页面顺序报:玩法 / 玩法设置在前,名字与模板信息其后,高级设置里的最后', () => {
  const m = JS.match(/const PUBLISH_ERROR_ORDER = \[([^\]]+)\]/)
  assert.ok(m)
  const order = m[1].match(/'([a-zA-Z]+)'/g).map(s => s.slice(1, -1))
  assert.deepEqual(order.slice(0, 3), ['finish', 'advanced', 'title'])
  assert.equal(order.at(-1), 'reward')
  assert.ok(order.includes('interactionType'), 'F-PU-1:据点玩法形态不能漏')
})
