const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')

function loadList(responses) {
  let def
  const prev = { getApp: global.getApp, Component: global.Component }
  global.getApp = () => ({ sendRequest(opts) { opts.success(responses[opts.url]) } })
  global.Component = (c) => { def = c }
  try {
    const mod = require.resolve('../../components/cy/scene-roam-task-list/index.js')
    delete require.cache[mod]
    require(mod)
  } finally { Object.assign(global, prev) }
  const vm = Object.assign({ data: JSON.parse(JSON.stringify(def.data)) }, def.methods)
  vm.setData = function (patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) }
  return vm
}

// PRIVATE-GROUP-official-task-source:漫游入口/列表叫“官方活动任务”,my-events 的行只标来源。
test('漫游入口与列表标题改名为官方活动任务', () => {
  const wxml = read('pages/roam/index.wxml')
  // 2026-10-27 owner P-31:「官方活动任务」一行随更多菜单精简撤掉;列表标题仍是「官方活动任务」
  assert.doesNotMatch(wxml, /附近挑战/)
  assert.match(read('utils/scene-registry.js'), /'roam-task-list': \{[^}]*title: '官方活动任务'/)
})

test('my-events 的行标“来自你的报名”,公共列表的行不标;不把报名写成当前资格', () => {
  const row = { id: 7, title: 'A', status: 2 }
  const vm = loadList({
    '/api/official/events': { code: 200, data: [row] },
    '/api/official/my-events': { code: 200, data: [row] },
  })
  vm.load()
  assert.equal(vm.data.all[0]._sourceText, '')
  assert.equal(vm.data.mine[0]._sourceText, '来自你的报名')
  vm.selectTab({ currentTarget: { dataset: { tab: 3 } } })
  assert.equal(vm.data.events[0]._sourceText, '来自你的报名')

  const wxml = read('components/cy/scene-roam-task-list/index.wxml')
  assert.match(wxml, /wx:if="\{\{item\._sourceText\}\}"[^>]*>\{\{item\._sourceText\}\}/)
  const js = read('components/cy/scene-roam-task-list/index.js')
  for (const src of [wxml, js]) assert.doesNotMatch(src, /有资格|可参与|已获得资格|符合条件/)
})
