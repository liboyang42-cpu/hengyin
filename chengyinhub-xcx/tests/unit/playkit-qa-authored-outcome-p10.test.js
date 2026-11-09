// P-10 第三轮(业主):答对 / 答错写作者在模板「结果反馈」里配的话(outcomeRules.pass / fail 的 reply、images)。
// 作者的话由结算判定屏 cy-play-verdict 摆(r5-player-runtime 读结算回执的 reply / images);
// 打字题自己的兜底句「不对。把它清掉，再试一次。」只在作者这一边什么都没写时出现。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')
const { outcomeReply } = require('../../pages/play/utils/r5-player-runtime.js')

const ROOT = path.join(__dirname, '../..')

function loadQa() {
  let def = null
  const prevC = global.Component, prevB = global.Behavior
  global.Behavior = (b) => b
  global.Component = (d) => { def = d }
  const file = path.join(ROOT, 'pages/play/components/playkit-qa/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prevC; global.Behavior = prevB
  return def
}

function qa(def, props) {
  const inst = {
    data: Object.assign({}, def.data, { mode: 'type', passed: false, finished: false, attempts: 0, feedbackText: '', authoredOutcome: '' }, props),
    setData(patch) { Object.assign(this.data, patch) },
  }
  return inst
}

test('服务端判完给 outcomeFeedback → kit.authoredOutcome;没给时为空串', () => {
  const base = { sessionId: 1, version: 2, status: 'RUNNING', playKit: { qa: { mode: 'TYPE', title: '江的另一个名字', attempts: 1 } } }
  assert.equal(pickPlayKit(base, new Date()).authoredOutcome, '')
  const authored = Object.assign({}, base, { outcomeFeedback: { outcome: 'FAIL', reply: '再去桥头看看石碑。', images: [] } })
  assert.equal(pickPlayKit(authored, new Date()).authoredOutcome, 'FAIL')
  const junk = Object.assign({}, base, { outcomeFeedback: { outcome: 'MAYBE' } })
  assert.equal(pickPlayKit(junk, new Date()).authoredOutcome, '')
})

test('作者写了没通过的话:答错只描红清空,不叠兜底句', () => {
  const def = loadQa()
  const c = qa(def, { authoredOutcome: 'FAIL', typed: '黄浦江' })
  def.observers.attempts.call(c, 0)   // 首次挂载
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.typeWrong, true)
  assert.equal(c.data.typed, '')
  assert.equal(c.data.feedback, '')
})

test('作者什么都没写:兜底句照旧(负控)', () => {
  const def = loadQa()
  const c = qa(def, { typed: '黄浦江' })
  def.observers.attempts.call(c, 0)
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.feedback, '不对。把它清掉，再试一次。')
})

test('作者的话从结算回执来:没通过的 reply 与配图原样交给判定屏', () => {
  const reply = outcomeReply({ lastOutcome: { attemptId: 'advanced:1:1', settled: true, status: 'SETTLED', outcome: 'FAIL', reply: '再去桥头看看石碑。', images: ['https://x/a.jpg'] } })
  assert.equal(reply.reply, '再去桥头看看石碑。')
  assert.deepEqual(reply.images, ['https://x/a.jpg'])
  assert.equal(reply.outcome, 'FAIL')
})

test('第四轮:作者的结果反馈比 attempts 晚到一拍,先写上的兜底句也要清掉(rig 实测)', () => {
  const def = loadQa()
  const c = qa(def, { typed: '黄浦江' })
  def.observers.attempts.call(c, 0)
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.feedback, '不对。把它清掉，再试一次。')
  c.data.authoredOutcome = 'FAIL'
  def.observers.authoredOutcome.call(c, 'FAIL')
  assert.equal(c.data.feedback, '')
  assert.equal(c.data.typeWrong, true, '描红留着')
  // 作者自己在题里配的反馈文字不动
  const d = qa(def, { feedbackText: '石碑上刻着呢', feedback: '石碑上刻着呢' })
  def.observers.authoredOutcome.call(d, 'FAIL')
  assert.equal(d.data.feedback, '石碑上刻着呢')
})
