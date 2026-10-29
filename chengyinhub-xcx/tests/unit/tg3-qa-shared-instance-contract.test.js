'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js');
const ROOT = path.resolve(__dirname, '../..');
const file = 'pages/play/components/playkit-qa/index.js';
const code = fs.readFileSync(path.join(ROOT, file), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));

function componentFactory() {
  let definition;
  const timers = [], camera = [], messages = [];
  vm.runInNewContext(code, {
    Component(value) { definition = value; }, console,
    wx: { chooseMedia(options) { camera.push(options); }, showToast(options) { messages.push(options.title); } },
    setTimeout(callback) { timers.push(callback); return timers.length; }, clearTimeout() {},
    getCurrentPages: () => [],
    require(id) {
      if (id.includes('reduced-motion')) return {};
      if (id.includes('/motion.js')) return { haptic() {} };
      throw new Error('Unexpected component port ' + id);
    },
  }, { filename: file });
  const observerKey = Object.keys(definition.observers).find(key => key.startsWith('show, options,'));
  assert.match(observerKey, /sessionId, nodeId$/);
  function mount(view) {
    const events = [], writes = [];
    const stage = { begin() {}, _stopAll() {}, miss: () => false, nudge() {}, fail() {}, win() {} };
    const values = Object.fromEntries(Object.entries(definition.properties).map(([key, prop]) => [key, plain(prop.value)]));
    const instance = Object.assign({}, definition.methods, {
      data: Object.assign(values, plain(definition.data)),
      setData(patch) { writes.push(plain(patch)); Object.assign(this.data, patch); },
      selectComponent: () => stage,
      triggerEvent(name, detail) { events.push({ name, detail: detail === undefined ? undefined : plain(detail) }); },
    });
    function render(next, show = true) {
      const kit = pickPlayKit(next);
      Object.assign(instance.data, plain(kit), { show, feedbackText: kit.feedback });
      definition.observers[observerKey].call(instance, show, kit.options, kit.mode, kit.multi, kit.passed, kit.finished);
      return kit;
    }
    const kit = render(view);
    return { instance, events, writes, kit, render, definition };
  }
  return { mount, definition, timers, camera, messages };
}
function theme(topicId, sessionId, nodeId, title, choices, extra = {}) {
  return { topicId, sessionId, nodeId, version: 1, status: 'RUNNING', playKit: { qa: Object.assign({
    mode: 'PICK', title, options: choices.map(([id, label]) => ({ id, label })),
    attempts: 0, passed: false, finished: false, maxTries: 2,
  }, extra) } };
}
const FIRST = () => theme(10, 101, 501, '主题甲：选择饮品', [['cold', '冷萃'], ['tea', '清茶'], ['milk', '牛奶']], { judge: false });
const SECOND = () => theme(11, 102, 502, '主题乙：选择路径', [['east', '东门'], ['west', '西门']], { judge: false });
const pick = (instance, index) => instance.onPick({ currentTarget: { dataset: { i: index } } });

test('two themes use one production PICK component and keep selection/input/feedback isolated', () => {
  const factory = componentFactory(), first = factory.mount(FIRST()), second = factory.mount(SECOND());
  assert.equal(first.definition, second.definition);
  assert.equal(first.kit.type, 'qa'); assert.equal(second.kit.type, 'qa');
  assert.equal(first.instance.data.title, '主题甲：选择饮品'); assert.equal(second.instance.data.title, '主题乙：选择路径');
  pick(first.instance, 1); first.instance.onInput({ detail: { value: '甲的临时输入' } });
  assert.deepEqual(plain(first.instance.data.optionList.map(option => option.state)), ['', 'is-on', '']);
  assert.deepEqual(plain(second.instance.data.optionList.map(option => option.state)), ['', '']);
  assert.equal(second.instance.data.typed, ''); assert.equal(second.events.length, 0);
  first.instance.onCta();
  assert.deepEqual(first.events, [{ name: 'submit', detail: { mode: 'pick', index: 1, optionId: 'tea' } }]);
  assert.equal(serverAction('qa', 'submit'), 'SUBMIT_QA');
  assert.deepEqual(serverPayload('qa', 'submit', first.events[0].detail), { optionId: 'tea' });
  assert.equal(second.events.length, 0);
});
test('reusing the same instance resets burning, text, selected options and prior receipt without local judging', () => {
  const factory = componentFactory(), mounted = factory.mount(FIRST());
  pick(mounted.instance, 0); mounted.instance.onBurn(); mounted.instance.onInput({ detail: { value: '原输入' } });
  mounted.instance.setData({ feedback: '甲的旧回执', shownFeedbackImages: ['old.png'], submitted: true });
  mounted.render(SECOND());
  assert.equal(mounted.instance.data.burning, false); assert.equal(mounted.instance.data.typed, '');
  assert.equal(mounted.instance.data.feedback, ''); assert.deepEqual(plain(mounted.instance.data.shownFeedbackImages), []);
  assert.equal(mounted.instance.data.submitted, false); assert.equal(mounted.instance.data.ctaDisabled, true);
  assert.deepEqual(plain(mounted.instance.data.optionList.map(option => option.id)), ['east', 'west']);
  pick(mounted.instance, 0); mounted.instance.onCta();
  assert.equal(mounted.events.at(-1).detail.optionId, 'east');
});
test('identical content under a different original session/node still invalidates the old intro callback', () => {
  const factory = componentFactory(), source = FIRST(), mounted = factory.mount(source);
  const oldIntro = factory.timers.at(-1);
  mounted.render(Object.assign({}, source, { topicId: 12, sessionId: 103, nodeId: 503 }));
  const count = mounted.writes.length; oldIntro();
  assert.equal(mounted.writes.length, count); assert.equal(mounted.instance.data.intro, true);
  factory.timers.at(-1)(); assert.equal(mounted.instance.data.intro, false);
});
test('hide/detach and switching to another theme discard late media results rather than emitting into the new run', () => {
  for (const invalidate of ['switch', 'hide', 'detach']) {
    const factory = componentFactory(), source = FIRST(); source.playKit.qa.mode = 'SHOT';
    const mounted = factory.mount(source); mounted.instance.onCta();
    assert.equal(factory.camera.length, 1); const reply = factory.camera[0].success;
    if (invalidate === 'switch') mounted.render(SECOND());
    else if (invalidate === 'hide') mounted.render(source, false);
    else factory.definition.lifetimes.detached.call(mounted.instance);
    reply({ tempFiles: [{ tempFilePath: 'original-only.jpg', size: 128 }] });
    assert.equal(mounted.events.length, 0); assert.equal(factory.messages.length, 0);
  }
});
test('the active photo error and success branches retain the original payload and never emit a verdict', () => {
  const factory = componentFactory(), source = FIRST(); source.playKit.qa.mode = 'SHOT';
  const mounted = factory.mount(source); mounted.instance.onCta(); factory.camera[0].success({ tempFiles: [] });
  assert.deepEqual(factory.messages, ['没拿到照片']); assert.equal(mounted.events.length, 0);
  mounted.instance.onCta(); factory.camera[1].success({ tempFiles: [{ tempFilePath: 'current.jpg', size: 256 }] });
  assert.deepEqual(mounted.events, [{ name: 'shoot', detail: { tempFilePath: 'current.jpg', size: 256 } }]);
});
test('only server receipt updates produce pass/failure feedback; the next theme starts clean', () => {
  const factory = componentFactory(), source = FIRST(); source.playKit.qa.judge = true;
  const mounted = factory.mount(source); pick(mounted.instance, 0);
  assert.equal(mounted.instance.data.passed, false); assert.equal(mounted.instance.data.feedback, '');
  const wrong = FIRST(); Object.assign(wrong.playKit.qa, { judge: true, attempts: 1, lastFeedback: '原服务端错误回执' });
  mounted.render(wrong); assert.equal(mounted.instance.data.feedback, '原服务端错误回执');
  assert.equal(mounted.instance.data.ctaLabel, '提交');
  const passed = FIRST(); Object.assign(passed.playKit.qa, { judge: true, passed: true, finished: true, attempts: 2, lastFeedback: '原服务端成功回执' });
  mounted.render(passed); assert.equal(mounted.instance.data.ctaLabel, '继续');
  mounted.instance.onCta(); assert.equal(mounted.events.at(-1).name, 'continue');
  mounted.render(SECOND()); assert.equal(mounted.instance.data.passed, false); assert.equal(mounted.instance.data.feedback, '');
  assert.equal(mounted.instance.data.ctaLabel, '确认选择');
  for (const option of mounted.instance.data.optionList) {
    assert.equal(Object.hasOwn(option, 'correct'), false); assert.equal(Object.hasOwn(option, 'isCorrect'), false);
  }
});

test('QA observer只写派生字段，异步上下文只读冻结且立即识别原session/node变化', () => {
  const factory = componentFactory(), mounted = factory.mount(FIRST());
  const watched = Object.keys(factory.definition.observers).find(key => key.startsWith('show, options,')).split(',').map(key => key.trim());
  const propertyWrites = mounted.writes.flatMap(patch => Object.keys(patch).filter(key => watched.includes(key)));
  assert.deepEqual(propertyWrites, [], 'observer不得直接写回任何原监听属性');
  const before = plain(mounted.instance.data), writes = mounted.writes.length;
  const context = mounted.instance._qaContext();
  assert.equal(Object.isFrozen(context), true);
  assert.deepEqual(plain(mounted.instance.data), before);
  assert.equal(mounted.writes.length, writes, '获取局身份快照不能调用setData');
  assert.equal(mounted.instance._isQaContextCurrent(context), true);
  mounted.instance.data.sessionId = 999;
  assert.equal(mounted.instance._isQaContextCurrent(context), false, '新session即使尚未通知observer也不能接旧回调');
  mounted.instance.data.sessionId = before.sessionId;
  mounted.instance.data.nodeId = 999;
  assert.equal(mounted.instance._isQaContextCurrent(context), false, '原node也参与异步身份');
  assert.equal(mounted.writes.length, writes);
});
