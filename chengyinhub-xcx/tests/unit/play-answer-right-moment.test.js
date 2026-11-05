// P-11:老问答答对后先停一拍「对了」,带作者揭示与本站碎片。
// 钉住:什么时候停、停了等不等玩家点「继续」、什么时候照旧直接走完成回执。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { rightMoment, RIGHT_AUTO_MS } = require('../../pages/play/utils/answer-right-moment.js');

test('有揭示或碎片:停住等玩家点继续', () => {
  const m = rightMoment({ data: { firstTime: true, answerReveal: ' 第 7 根。 ' }, node: { fragmentText: '钥匙不在锁里，在数字里' }, mode: 1 });
  assert.deepEqual(m, { text: '', reveal: '第 7 根。', fragment: '钥匙不在锁里，在数字里', hold: true });
});

test('只有「对了」:短停后自动继续', () => {
  const m = rightMoment({ data: { firstTime: true }, node: {}, mode: 1 });
  assert.deepEqual(m, { text: '', reveal: '', fragment: '', hold: false });
  assert.ok(RIGHT_AUTO_MS >= 800 && RIGHT_AUTO_MS <= 1600);
});

test('探店日碎片另有翻卡,这里不重复', () => {
  const m = rightMoment({ data: { firstTime: true }, node: { fragmentText: '碎片' }, mode: 2 });
  assert.equal(m.fragment, '');
  assert.equal(m.hold, false);
});

test('凭证流 / 本局运行时回执 / 重复提交:不停这一拍(负控)', () => {
  assert.equal(rightMoment({ data: { firstTime: true }, genericProof: true }), null);
  assert.equal(rightMoment({ data: { themeRuntime: {} } }), null);
  assert.equal(rightMoment({ data: { sharedRuntime: {} } }), null);
  assert.equal(rightMoment({ data: { done: false } }), null);
  assert.equal(rightMoment({ data: { firstTime: false, answerReveal: 'x' } }), null);
});

test('play 页:答对走 _answerRight,题卡有「对了」与继续键,离页会把完成落下', () => {
  const root = path.join(__dirname, '../../pages/play');
  const js = fs.readFileSync(path.join(root, 'index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(root, 'index.wxml'), 'utf8');
  assert.match(js, /that\._answerRight\(g\.nodeId, r\.data, r\.msg\)/);
  assert.doesNotMatch(js, /if \(r\.msg\) cyToast\(r\.msg\); that\.onComplete\(g\.nodeId, r\.data\)/);
  assert.match(js.slice(js.indexOf('  onHide() {'), js.indexOf('  _clearTransientUiTimers() {')), /this\._flushAnswerRight\(\);/);
  assert.match(wxml, /<text>\{\{gRight\.text \|\| '对了'\}\}<\/text>/);
  assert.match(wxml, /bindtap="continueAfterRight"[^>]*>继续</);
  assert.match(wxml, /gp2__input \{\{gWrong \? 'is-wrong' : ''\}\}/);
});

// 第三轮(业主):答对 / 答错写作者在模板里配的反馈;「对了」与揭示只在作者什么都没写时兜底。
test('作者的完成反馈与答对后揭示都保留', () => {
  const m = rightMoment({ data: { firstTime: true, answerReveal: '第 7 根。' }, node: { passText: ' 恭喜你解开了老城的第一个秘密！ ', fragmentText: '碎片' }, mode: 1 });
  assert.deepEqual(m, { text: '恭喜你解开了老城的第一个秘密！', reveal: '第 7 根。', fragment: '碎片', hold: true });
});

test('P-10:答错兜底不拿模板文字反馈(那是完成后的话)', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.doesNotMatch(js, /feedback: n\.feedbackText \|\|/);
  assert.match(js, /feedback: '答案还没咬合,再看看四周……', passText: String\(n\.feedbackText \|\| ''\)\.trim\(\),/);
  assert.match(js, /'game\.feedback': r\.msg \|\| g\.feedback/);
  assert.match(js, /feedback: node\.feedback, passText: node\.passText,/);
});
