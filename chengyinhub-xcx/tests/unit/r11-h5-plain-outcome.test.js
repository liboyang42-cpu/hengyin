// 第十一轮 h5 第 1 项:没有通用局的主题里,站点自己的结果反馈判完就在本站摆作者写的话,没过可以在本站重来。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')
const runtime = require('../../pages/play/utils/r5-player-runtime.js')

const failed = (extra) => Object.assign({ sessionId: 2, version: 1, status: 'FAILED', ownerType: 1, canRetry: true,
  config: { outcomeRulesVersion: 1 }, playKit: { qa: { mode: 'PICK', title: '老洋房门口那棵树是什么树？', lastFeedback: '再看看叶子。' } } }, extra)

test('没有通用局的主题:结果反馈没过的站给「重新开始本次玩法」,通用局照旧由任务结算接管', () => {
  const plain = pickPlayKit(failed({ plainOutcome: true }), new Date())
  assert.equal(plain.terminal, true)
  assert.equal(plain.canRetry, true)
  assert.equal(plain.retryLabel, '重新开始本次玩法')
  const generic = pickPlayKit(failed({ plainOutcome: false }), new Date())
  assert.notEqual(generic.terminal, true)
})

function page(themeBag) {
  const p = Object.assign({ data: Object.assign({ themeBag }, runtime.data), setData(patch) { for (const k of Object.keys(patch)) this.data[k] = patch[k] } }, runtime.methods())
  return p
}
const view = (version, outcomeFeedback) => ({ sessionId: 2, version, config: { outcomeRulesVersion: 1 }, outcomeFeedback })

test('没有通用局的主题:判完那一次摆作者写的反馈;进页时已有的结果不补弹,同一次不重复弹', () => {
  const p = page({ quests: [] })
  p._applyR5PlainOutcome(view(1, { outcome: 'FAIL', reply: '旧结果', images: [] }))
  assert.equal(p.data.r5Verdict.show, false, '进页第一份视图只记下')
  p._applyR5PlainOutcome(view(2, null))
  p._applyR5PlainOutcome(view(3, { outcome: 'FAIL', reply: '再看看，换个方向想想。', images: [] }))
  assert.deepEqual(p.data.r5Verdict, { show: true, kind: 'fail', text: '再看看，换个方向想想。', images: [] })
  p.data.r5Verdict = { show: false, kind: 'win', text: '', images: [] }
  p._applyR5PlainOutcome(view(3, { outcome: 'FAIL', reply: '再看看，换个方向想想。', images: [] }))
  assert.equal(p.data.r5Verdict.show, false)
  p._applyR5PlainOutcome(view(5, { outcome: 'PASS', reply: '对了，钟楼就在江边。', images: [] }))
  assert.equal(p.data.r5Verdict.kind, 'win')
})

test('通用局主题(背包有任务)不走这条:判定屏仍由任务结算回执驱动,不重复弹', () => {
  const p = page({ quests: [{ questKey: 'node_81' }] })
  p._applyR5PlainOutcome(view(1, null))
  p._applyR5PlainOutcome(view(2, { outcome: 'FAIL', reply: '再看看', images: [] }))
  assert.equal(p.data.r5Verdict.show, false)
})
