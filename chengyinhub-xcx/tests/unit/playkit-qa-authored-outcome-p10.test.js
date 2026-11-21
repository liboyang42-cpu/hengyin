// P-10 第三轮(业主):答对 / 答错写作者在模板「结果反馈」里配的话(outcomeRules.pass / fail 的 reply、images)。
// 作者的话由结算判定屏 cy-play-verdict 摆(r5-player-runtime 读结算回执的 reply / images);
// 打字题自己的兜底句「还不对，再看看题面」只在作者这一边什么都没写时出现。
// F02(2026-11-18):答错不再清空输入框 —— 保留并全选,玩家直接改;服务端错答回复显示在输入框下方。
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
  const file = path.join(ROOT, 'pages/playkit/components/playkit-qa/index.js')
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

test('u2 accepted own input survives result observers read-only; next question starts empty', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const def = loadQa();
  const c = Object.assign(qa(def, { sessionId: 42, nodeId: 1, show: true, reducedMotion: true }), def.methods,
    { selectComponent: () => null, triggerEvent() {} });
  const fields = Object.keys(def.observers).find(k => k.startsWith('show, options'));
  const update = patch => { Object.assign(c.data, patch); def.observers[fields].apply(c, fields.split(',').map(k => c.data[k.trim()])); };
  update({}); c.data.typed = '本人提交的答案'; c.data.ctaDisabled = false; c.onCta();
  c.data.typed = ''; update({ passed: true, attempts: 1 });
  assert.equal(c.data.typed, '本人提交的答案');
  let submitted = 0; c.triggerEvent = () => { submitted++; }; c.onInput({ detail: { value: '改写' } });
  assert.equal(c.data.typed, '本人提交的答案');
  update({ sessionId: 43, nodeId: 2, passed: false, attempts: 0 });
  assert.equal(c.data.typed, ''); assert.equal(submitted, 0);
});

test('u4 question image previews full original; missing image does nothing', () => {
  const def = loadQa(), calls = [];
  global.wx = Object.assign({}, global.wx, { previewImage: value => calls.push(value) });
  def.methods.onPreviewQuestion.call({ data: { imageUrl: 'https://fixture.invalid/question.jpg' } });
  def.methods.onPreviewQuestion.call({ data: { imageUrl: '' } });
  assert.deepEqual(calls, [{ current: 'https://fixture.invalid/question.jpg', urls: ['https://fixture.invalid/question.jpg'] }]);
});

test('服务端判完给 outcomeFeedback → kit.authoredOutcome;没给时为空串', () => {
  const base = { sessionId: 1, version: 2, status: 'RUNNING', playKit: { qa: { mode: 'TYPE', title: '江的另一个名字', attempts: 1 } } }
  assert.equal(pickPlayKit(base, new Date()).authoredOutcome, '')
  const authored = Object.assign({}, base, { outcomeFeedback: { outcome: 'FAIL', reply: '再去桥头看看石碑。', images: [] } })
  assert.equal(pickPlayKit(authored, new Date()).authoredOutcome, 'FAIL')
  const junk = Object.assign({}, base, { outcomeFeedback: { outcome: 'MAYBE' } })
  assert.equal(pickPlayKit(junk, new Date()).authoredOutcome, '')
})

test('作者写了没通过的话:答错描红、保留输入并全选,不叠兜底句', () => {
  const def = loadQa()
  const c = qa(def, { authoredOutcome: 'FAIL', typed: '黄浦江' })
  def.observers.attempts.call(c, 0)   // 首次挂载
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.typeWrong, true)
  assert.equal(c.data.typed, '黄浦江', 'F02:保留玩家输入')
  assert.equal(c.data.selectAll, 3, '全选高亮,玩家可以直接改')
  assert.equal(c.data.ctaDisabled, false, '输入还在,可以改了再交')
  assert.equal(c.data.feedback, '')
})

test('作者什么都没写:兜底句照旧(负控)', () => {
  const def = loadQa()
  const c = qa(def, { typed: '黄浦江' })
  def.observers.attempts.call(c, 0)
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.feedback, '还不对，再看看题面')
  assert.equal(c.data.typed, '黄浦江')
})

test('F02:服务端的错答回复(lastFeedback)显示在输入框下方,替换兜底句', () => {
  const def = loadQa()
  const c = qa(def, { typed: '文德', feedbackText: '桥名对了，题目问的是牌坊' })
  def.observers.attempts.call(c, 0)
  c.data.attempts = 1
  def.observers.attempts.call(c, 1)
  assert.equal(c.data.feedback, '桥名对了，题目问的是牌坊')
  assert.equal(c.data.typed, '文德')
  const kit = pickPlayKit({ sessionId: 1, version: 2, status: 'RUNNING',
    playKit: { qa: { mode: 'TYPE', title: '示例站点', attempts: 1, lastFeedback: '桥名对了，题目问的是牌坊' } } }, new Date())
  assert.equal(kit.feedback, '桥名对了，题目问的是牌坊')
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
  assert.equal(c.data.feedback, '还不对，再看看题面')
  c.data.authoredOutcome = 'FAIL'
  def.observers.authoredOutcome.call(c, 'FAIL')
  assert.equal(c.data.feedback, '')
  assert.equal(c.data.typeWrong, true, '描红留着')
  // 作者自己在题里配的反馈文字不动
  const d = qa(def, { feedbackText: '石碑上刻着呢', feedback: '石碑上刻着呢' })
  def.observers.authoredOutcome.call(d, 'FAIL')
  assert.equal(d.data.feedback, '石碑上刻着呢')
})

test('文字提交只在非空输入时启用；答错保留输入,改了可以直接再交', () => {
  const def = loadQa(); const c = qa(def); const events = [];
  c.triggerEvent = (name, detail) => events.push({ name, detail });
  c._answerIndex = def.methods._answerIndex;
  c.selectComponent = () => ({ miss: () => false, nudge() {} });
  assert.equal(c.data.ctaDisabled, true);
  def.methods.onInput.call(c, { detail: { value: '  ' } });
  def.methods.onCta.call(c);
  assert.equal(c.data.ctaDisabled, true); assert.equal(events.length, 0);
  def.methods.onInput.call(c, { detail: { value: 'C02、B04' } });
  assert.equal(c.data.ctaDisabled, false);
  def.methods.onCta.call(c);
  assert.deepEqual(events, [{ name: 'submit', detail: { mode: 'type', input: 'C02、B04' } }]);
  def.methods.settle.call(c, false, {});
  assert.equal(c.data.typed, 'C02、B04'); assert.equal(c.data.selectAll, 7); assert.equal(c.data.ctaDisabled, false);
  assert.equal(c.data.feedback, '还不对，再看看题面');
  def.methods.settle.call(c, false, { feedback: '前两个对了' });
  assert.equal(c.data.feedback, '前两个对了', '服务端回复替换兜底句');
  def.methods.onInput.call(c, { detail: { value: '' } });
  assert.equal(c.data.ctaDisabled, true); assert.equal(c.data.selectAll, -1);
});
