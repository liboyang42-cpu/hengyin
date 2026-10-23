/* 2026-10-22 完成页勋章堆(pages/play/utils/finish-medals.js):只放真拿到的。 */
const test = require('node:test')
const assert = require('node:assert/strict')
const { buildFinishMedals } = require('../../pages/play/utils/finish-medals.js')

test('主题通关徽章只在全部完成且后端下发了 reward 时出现', () => {
  const reward = { finishMedalName: '外滩夜行者', finishMedalImg: 'm.png' }
  assert.equal(buildFinishMedals({ allDone: false, reward }).length, 0)
  assert.equal(buildFinishMedals({ allDone: true, reward: {} }).length, 0)
  assert.deepEqual(buildFinishMedals({ allDone: true, reward })[0], { key: 'theme', name: '外滩夜行者', img: 'm.png', sub: '通关 · 主题徽章', glyph: 'flag' })
})

test('站点名次奖牌只收本局真夺到且该站已完成的,同站不重复', () => {
  const nodes = [{ nodeId: 1, done: true }, { nodeId: 2, done: false }]
  const won = [{ nodeId: 1, name: '头号', rank: 2 }, { nodeId: 1, name: '头号', rank: 2 }, { nodeId: 2, name: '没走完', rank: 1 }]
  const out = buildFinishMedals({ allDone: false, nodes, won })
  assert.deepEqual(out.map((m) => m.sub), ['第 2 名 · 站点奖牌'])
  assert.deepEqual(buildFinishMedals({}), [])
})
