const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8')

function loadList(responses) {
  let def
  const prev = { getApp: global.getApp, Component: global.Component }
  global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => 'fixture-member', sendRequest(opts) { opts.success(responses[opts.url]) } })
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
    '/api/official/active-for-roam': { code: 200, data: [row] },
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


// D-73（业主 2026-11-11）：漫游任务列表只列「报名中/进行中」且「绑定漫游」的活动；
// 「我的」也只保留落在这份可参与列表里的报名，已结束/未绑定漫游的不进漫游任务列表。
test('D-73 公共列表走 active-for-roam，而不是全量 official/events', () => {
  const js = read('components/cy/scene-roam-task-list/index.js')
  assert.match(js, /'\/api\/official\/active-for-roam'/)
  assert.doesNotMatch(js, /'\/api\/official\/events'/)
})

test('D-73 「我的」按可参与漫游列表取交集', () => {
  const roam = { id: 7, title: 'A', status: 2 }
  const ended = { id: 9, title: 'B', status: 5 }
  const vm = loadList({
    '/api/official/active-for-roam': { code: 200, data: [roam] },
    '/api/official/my-events': { code: 200, data: [roam, ended] },
  })
  vm.load()
  assert.deepEqual(vm.data.all.map((x) => x.id), [7])
  assert.deepEqual(vm.data.mine.map((x) => x.id), [7], '已结束/未绑定漫游的报名不进漫游任务列表')
  vm.selectTab({ currentTarget: { dataset: { tab: 3 } } })
  assert.deepEqual(vm.data.events.map((x) => x.id), [7])
})
