// F05 组队角色卡:encounter.roleView → 卡片。组队只看本人那一份(抬头「你的角色：X」、底部「队友在看另一份」);
// SOLO 合并全部视图,每段带角色名小标题;旧 roleMissing(没有视图)不画空卡。夹具中性。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { roleCardView, TEAM_FOOTER } = require('../../pages/play/utils/role-card-view.js')
const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

const viewA = { roleId: 'A', label: '记录员', title: '示例站点的门牌', body: '门牌上有三个字母。', items: [{ label: '第一位', text: 'A' }] }
const viewB = { roleId: 'B', label: '观察员', title: '示例站点的窗户', body: '', items: [{ label: '第二位', text: 'B' }, { label: '', text: '' }] }
const viewC = { roleId: 'C', label: '向导', title: '', body: '第三位是 C。', items: [] }

test('组队:抬头写本人角色,正文只有本人那一份,底部「队友在看另一份」', () => {
  const card = roleCardView({ role: 'B', roleLabel: '观察员', views: [viewB], otherRoleLabel: '向导', otherRoleLabels: ['向导', '记录员'] })
  assert.equal(card.mode, 'team')
  assert.equal(card.heading, '你的角色：观察员')
  assert.equal(card.footer, TEAM_FOOTER)
  assert.equal(TEAM_FOOTER, '队友在看另一份')
  assert.equal(card.sections.length, 1)
  assert.equal(card.sections[0].title, '示例站点的窗户')
  assert.equal(card.sections[0].label, '', '组队时不再在段上重复角色名')
  assert.deepEqual(card.sections[0].items.map((i) => i.text), ['B'], '空行不画')
})

test('组队:C / D 角色同样成立;roleLabel 缺时用视图 label', () => {
  assert.equal(roleCardView({ role: 'C', views: [viewC] }).heading, '你的角色：向导')
  assert.equal(roleCardView({ role: 'D', roleLabel: '联络员', views: [{ roleId: 'D', body: '示例' }] }).heading, '你的角色：联络员')
  assert.equal(roleCardView({ role: 'E', views: [viewA] }), null, '只认 A–D')
})

test('SOLO:全部视图合并成一张,每段带角色名小标题,没有抬头 / 底部', () => {
  const card = roleCardView({ role: 'SOLO', views: [viewA, viewB, viewC] })
  assert.equal(card.mode, 'solo')
  assert.equal(card.heading, '')
  assert.equal(card.footer, '')
  assert.deepEqual(card.sections.map((s) => s.label), ['记录员', '观察员', '向导'])
  assert.deepEqual(card.sections.map((s) => s.k), [0, 1, 2])
})

test('没有可显示的视图(旧 roleMissing / 空 / 坏形状)不画空卡', () => {
  assert.equal(roleCardView({ roleMissing: true, views: [] }), null)
  assert.equal(roleCardView(null), null)
  assert.equal(roleCardView({ role: 'SOLO', views: [{ roleId: 'A' }] }), null)
})

test('组件:observer 只写派生字段 card;WXML 按 card 渲染,组队底部写死同一句', () => {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  try { require('../../pages/play/components/role-card/index.js') } finally { global.Component = prev }
  const inst = { data: Object.assign({}, def.data), setData(p) { Object.assign(this.data, p) } }
  def.observers.view.call(inst, { role: 'A', roleLabel: '记录员', views: [viewA] })
  assert.equal(inst.data.card.heading, '你的角色：记录员')
  const wxml = read('pages/play/components/role-card/index.wxml')
  assert.match(wxml, /wx:if="\{\{card\}\}"/)
  assert.match(wxml, /\{\{card\.heading\}\}/)
  assert.match(wxml, /\{\{card\.footer\}\}/)
  assert.match(wxml, /\{\{item\.label\}\}/)
  assert.doesNotMatch(read('pages/play/components/role-card/index.wxss'), /#[0-9a-fA-F]{3,8}\b/, '不写死颜色')
})

test('页面:encounter 回包的 roleView 落到 stationRole,站点卡按同一站挂角色卡;能力声明 role.views', () => {
  const js = read('pages/play/index.js')
  const probe = js.slice(js.indexOf('  _probeJourneyCheck(node) {'), js.indexOf('  _isJourneyCheckIntentCurrent(intent) {'))
  assert.match(probe, /stationRole: r\.data\.roleView && typeof r\.data\.roleView === 'object' \? \{ nodeId: node\.nodeId, view: r\.data\.roleView \} : null/)
  assert.ok(probe.indexOf('stationRole') < probe.indexOf('r5PlayerRuntime.isR5(r.data)'), 'R5 主题也要出角色卡')
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<cy-role-card wx:if="\{\{stationRole && stationRole\.nodeId == sheet\.node\.nodeId\}\}" view="\{\{stationRole\.view\}\}" \/>/)
  assert.equal(JSON.parse(read('pages/play/index.json')).usingComponents['cy-role-card'], '/pages/play/components/role-card/index')
  global.wx = global.wx || {}
  assert.ok(require('../../pages/play/utils/client-capabilities.js').keys().includes('role.views'))
})
