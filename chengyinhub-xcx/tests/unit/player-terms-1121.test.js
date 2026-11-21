// 1121 N6:玩家浏览面一样东西一个名字。搜索结果里主题模板对玩家就是「主题」、玩法模板就是「玩法」,
// 同名两组合成一个页签;商家(创作视角)保留「主题模板 / 玩法模板」。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const ds = require('../../utils/discover-search.js')

test('玩家视角:模板结果卡标「主题 / 玩法」,商家视角保留原词', () => {
  const [topicTpl] = ds.decorateRows('topicTemplate', [{ id: 1, name: '风味巡游' }], { playerView: true })
  const [gameTpl] = ds.decorateRows('gameTemplate', { rows: [{ id: 2, title: '暗号' }] }, { playerView: true })
  assert.equal(topicTpl.typeLabel, '主题')
  assert.equal(topicTpl.detail, '主题')
  assert.equal(gameTpl.typeLabel, '玩法')
  const [merchantTpl] = ds.decorateRows('topicTemplate', [{ id: 1, name: '风味巡游' }], { playerView: false })
  assert.equal(merchantTpl.typeLabel, '主题模板')
  assert.equal(ds.decorateRows('gameTemplate', [{ id: 2, title: '暗号' }], {})[0].typeLabel, '玩法模板')
})

test('玩家页签同名合并、条数相加,点「主题」同时出两组', () => {
  const groups = [
    { type: 'topic', items: [{}, {}] },
    { type: 'activity', items: [{}] },
    { type: 'topicTemplate', items: [{}] },
    { type: 'gameTemplate', items: [{}, {}, {}] },
  ]
  assert.deepEqual(ds.resultTabs(groups, true), [
    { key: 'topic', label: '主题 3' },
    { key: 'activity', label: '活动 1' },
    { key: 'gameTemplate', label: '玩法 3' },
  ])
  assert.deepEqual(ds.resultTabs(groups, false).map((t) => t.label), ['主题 2', '活动 1', '主题模板 1', '玩法模板 3'])
  assert.equal(ds.tabKeyOf('topicTemplate', true), 'topic')
  assert.equal(ds.tabKeyOf('topicTemplate', false), 'topicTemplate')
})

test('搜索结果页按视角取名字与页签,不再直接读 TYPE_META 原词', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/search2/result/index.js'), 'utf8')
  assert.doesNotMatch(js, /TYPE_META\[[^\]]+\]\.label/)
  assert.equal((js.match(/discoverSearch\.resultTabs\(/g) || []).length, 3)
  assert.match(js, /const filters = Object\.assign\(\{\}, this\._filters, \{ playerView: !this\.data\.merchantView \}\)/)
  assert.match(js, /discoverSearch\.tabKeyOf\(group\.type, !this\.data\.merchantView\) === type/)
})
