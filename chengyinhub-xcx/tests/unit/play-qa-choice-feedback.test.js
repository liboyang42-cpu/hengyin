const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pickPlayKit, serverPayload } = require('../../pages/play/utils/playkit-view');

global.Behavior = value => value;
global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
global.getApp = () => ({ globalData: {} });
let def;
global.Component = value => { def = value; };
require('../../pages/play/components/playkit-qa/index');
const observer = def.observers['show, options, mode, multi, passed, finished, judge, lastOptionId, lastOptionIds, feedbackText, feedbackImages, sessionId, nodeId'];
// Shape: AdvancedGameRuntimeServiceImpl.buildPlayKit qa projection (options only id/label).
// New receipt fields follow the QA-BE contract; zeroview fixtures contain no QA views.
function view(overrides = {}) {
  return { sessionId: 16, version: 2, playKit: { qa: Object.assign({ mode: 'PICK',
    title: '选择饮品', options: [{ id: 'cold', label: '冷萃' }, { id: 'latte', label: '拿铁' }],
    maxTries: 2, attempts: 0, passed: false, finished: false,
  }, overrides) } };
}
function mount(response) {
  const kit = pickPlayKit(response);
  const events = [];
  const data = Object.fromEntries(Object.entries(def.properties).map(([key, prop]) => [key, prop.value]));
  const inst = Object.assign({}, def.methods, { data: Object.assign(data, def.data, kit, { feedbackText: kit.feedback }),
    setData(patch) { Object.assign(this.data, patch); }, selectComponent: () => null,
    triggerEvent(name, detail) { events.push({ name, detail }); },
  });
  observer.call(inst, true, kit.options, kit.mode, kit.multi, kit.passed, kit.finished);
  clearTimeout(inst._introTimer);
  return { inst, events, kit };
}
const pick = (inst, i) => inst.onPick({ currentTarget: { dataset: { i } } });

test('judge=false forces single select and confirms exactly once under double taps', () => {
  const { inst, events, kit } = mount(view({ judge: false, multi: true, answerId: 'cold' }));
  assert.equal(kit.multi, false);
  assert.equal(kit.reveal, false);
  assert.equal(inst.data.triesCap, 0);
  assert.equal(inst.data.ctaLabel, '确认选择');
  inst.onCta();
  assert.equal(events.length, 0);
  pick(inst, 0); pick(inst, 0); pick(inst, 1);
  assert.equal(events.length, 0);
  assert.deepEqual(inst.data.optionList.map(o => o.state), ['', 'is-on']);
  inst.onCta(); inst.onCta(); pick(inst, 0); inst._submitPick();
  assert.deepEqual(events, [{ name: 'submit', detail: { mode: 'pick', index: 1, optionId: 'latte' } }]);
  assert.deepEqual(serverPayload('qa', 'submit', events[0].detail), { optionId: 'latte' });
});

for (const passed of [true, false]) test(`finished ${passed ? 'passed' : 'failed'} QA reopens read-only with recorded choice and receipt`, () => {
  const { inst, events } = mount(view({ judge: true, multi: true, attempts: 1, finished: true, passed,
    lastOptionIds: ['latte'], lastFeedback: '奶香', lastFeedbackImages: ['/profile/latte.png'] }));
  assert.deepEqual(inst.data.optionList.map(o => o.chosen), [false, true]);
  assert.equal(inst.data.feedback, '奶香');
  assert.deepEqual(inst.data.shownFeedbackImages, ['/profile/latte.png']);
  pick(inst, 0); inst._submitPick(); inst.onCta(); inst.onSubmitText();
  assert.equal(events.filter(e => e.name === 'submit' || e.name === 'shoot').length, 0);
});

test('only server receipt feedback is shown; image-only/text-only/empty pass through unchanged', () => {
  for (const [text, images] of [['奶香', []], ['', ['/profile/latte.png']], ['奶香', ['/profile/a.png', '/profile/b.png', '/profile/c.png']], ['', []]]) {
    const response = view({ judge: false, finished: true, passed: true, attempts: 1,
      lastOptionId: 'latte', lastFeedback: text, lastFeedbackImages: images });
    response.playKit.qa.options[0].fb = '不可展示另一选项';
    response.playKit.qa.options[0].feedbackImages = ['/profile/other.png'];
    const { inst, kit } = mount(response);
    assert.equal(inst.data.feedback, text);
    assert.deepEqual(inst.data.shownFeedbackImages, images);
    assert.equal(JSON.stringify(kit).includes('不可展示'), false);
    assert.equal(JSON.stringify(kit).includes('/profile/other.png'), false);
    assert.deepEqual(inst.data.optionList.map(o => o.chosen), [false, true]);
    assert.equal(inst.data.ctaLabel, '继续');
  }
});

test('three images per selected option: two selected options show and preview all six in selection order', () => {
  const a = ['/profile/a1.png', '/profile/a2.png', '/profile/a3.png'];
  const b = ['/profile/b1.png', '/profile/b2.png', '/profile/b3.png'];
  const response = view({ judge: true, multi: true, attempts: 1, finished: true, passed: true,
    options: [{ id: 'cold', label: '冷萃' }, { id: 'latte', label: '拿铁' }, { id: 'tea', label: '茶' }],
    lastOptionIds: ['cold', 'latte'], answerIds: ['cold', 'latte'], lastFeedback: '甲 乙',
    lastFeedbackImages: a.concat(b) });
  const { inst, kit } = mount(response);
  assert.equal(kit.feedbackImages.length, 6);
  assert.deepEqual(inst.data.shownFeedbackImages, a.concat(b));
  const previews = [];
  wx.previewImage = value => previews.push(value);
  inst.onPreviewFeedback({ currentTarget: { dataset: { i: 5 } } });
  assert.deepEqual(previews, [{ current: '/profile/b3.png', urls: a.concat(b) }]);
  assert.deepEqual(inst.data.optionList.map(o => o.chosen), [true, true, false]);
});

test('multi-choice reveal: exhausted judged question marks every server answer separately from the recorded choice', () => {
  const options = [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }, { id: 'c', label: '丙' }];
  const { inst, kit } = mount(view({ judge: true, multi: true, maxTries: 2, attempts: 2, finished: true, passed: false,
    options, lastOptionIds: ['a', 'c'], answerIds: ['a', 'b'] }));
  assert.equal(kit.reveal, true);
  assert.deepEqual(inst.data.optionList.map(o => o.answer), [true, true, false]);
  assert.deepEqual(inst.data.optionList.map(o => o.chosen), [true, false, true]);
  assert.deepEqual(inst.data.optionList.map(o => o.state), ['is-right', 'is-right', 'is-wrong']);
});

test('multi-choice without server answers keeps them hidden; judge=false never applies retained answer fields', () => {
  const options = [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }, { id: 'c', label: '丙' }];
  const hidden = mount(view({ judge: true, multi: true, maxTries: 2, attempts: 2, finished: true, passed: false,
    options, lastOptionIds: ['a', 'c'] }));
  assert.equal(hidden.kit.reveal, false);
  assert.deepEqual(hidden.inst.data.optionList.map(o => o.answer), [false, false, false]);
  assert.deepEqual(hidden.inst.data.optionList.map(o => o.state), ['is-on', '', 'is-on']);
  const off = mount(view({ judge: false, multi: true, finished: true, passed: true, attempts: 1,
    options, lastOptionId: 'b', answerIds: ['a', 'b'], answerId: 'a' }));
  assert.equal(off.kit.reveal, false);
  assert.deepEqual(off.kit.answerIds, []);
  assert.equal(off.kit.answerId, '');
  assert.deepEqual(off.inst.data.optionList.map(o => o.answer), [false, false, false]);
  assert.deepEqual(off.inst.data.optionList.map(o => o.state), ['', 'is-on', '']);
});

test('single-choice reveal remains compatible', () => {
  const { inst, kit } = mount(view({ judge: true, maxTries: 1, attempts: 1, finished: true, passed: false,
    lastOptionId: 'cold', answerId: 'latte' }));
  assert.equal(kit.reveal, true);
  assert.deepEqual(inst.data.optionList.map(o => o.state), ['is-wrong', 'is-right']);
  assert.deepEqual(inst.data.optionList.map(o => o.answer), [false, true]);
});

test('feedback images preview uses only the server receipt list', () => {
  const { inst } = mount(view({ finished: true, passed: false, lastFeedbackImages: ['/profile/a.png', '/profile/b.png'] }));
  const previews = [];
  wx.previewImage = value => previews.push(value);
  inst.onPreviewFeedback({ currentTarget: { dataset: { i: 1 } } });
  inst.onPreviewFeedback({ currentTarget: { dataset: { i: 9 } } });
  assert.deepEqual(previews, [{ current: '/profile/b.png', urls: ['/profile/a.png', '/profile/b.png'] }]);
});

test('legacy and explicit judge=true preserve immediate single-pick submit', () => {
  for (const fields of [{}, { judge: true }]) {
    const { inst, events } = mount(view(fields));
    pick(inst, 1);
    assert.deepEqual(events, [{ name: 'submit', detail: { mode: 'pick', index: 1, optionId: 'latte' } }]);
    assert.equal(inst.data.triesCap, 1);
  }
});

test('completed choice label and preview handler are reachable in WXML; no tries or reveal for judge=false', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit-qa/index.wxml'), 'utf8');
  assert.match(source, /wx:if="\{\{item.chosen\}\}">你的选择/);
  assert.match(source, /wx:if="\{\{item.answer\}\}">正确答案/);
  assert.match(source, /bindtap="onPreviewFeedback"/);
  assert.match(source, /aria-disabled="\{\{finished \|\| passed \|\| submitted\}\}"/);
});

test('dispatcher binds the receipt fields to the real QA component', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit/index.wxml'), 'utf8');
  const qa = source.match(/<cy-playkit-qa[\s\S]*?\/>/)[0];
  for (const field of ['judge', 'lastOptionId', 'lastOptionIds', 'feedbackImages', 'answerId', 'answerIds', 'reveal']) assert.ok(qa.includes('kit.' + field));
});

let pageDef;
global.Page = value => { pageDef = value; };
require('../../pages/play/index');

test('real Page failure handler unlocks confirmed failure only for the current unfinished QA session', () => {
  for (const [unknown, sessionId, finished, expected] of [
    [false, 16, false, 1], [true, 16, false, 0], [false, 17, false, 0], [false, 16, true, 0],
  ]) {
    const { inst, events, kit } = mount(view({ judge: false, finished }));
    pick(inst, 1); inst.onCta();
    const page = Object.assign({}, pageDef, {
      data: { playKit: { kit } },
      selectComponent: () => ({ data: { unknown } }),
      setData(patch) {
        const seq = patch['playKit.kit.failureSeq'];
        if (seq) { kit.failureSeq = seq; def.observers.failureSeq.call(inst, seq); }
      },
    });
    page.onAdvancedActionFail({ detail: { action: 'SUBMIT_QA', sessionId } });
    assert.equal(kit.failureSeq || 0, expected);
    const before = events.length;
    inst.onCta();
    assert.equal(events.length - before, expected);
  }
});

test('real Page translates a confirmed choice to existing SUBMIT_QA action', () => {
  const { inst, kit, events } = mount(view({ judge: false }));
  const calls = [];
  const page = Object.assign({}, pageDef, {
    data: { isPreview: false, playKit: { kit } },
    selectComponent: () => ({ action: (name, payload) => calls.push({ name, payload }) }),
  });
  pick(inst, 1); inst.onCta(); inst.onCta();
  for (const event of events) page.onPlayKitAction({ detail: { type: 'qa', action: event.name, detail: event.detail } });
  assert.deepEqual(calls, [{ name: 'SUBMIT_QA', payload: { optionId: 'latte' } }]);
});


test('finished receipt stops the existing stage timer without starting another game', () => {
  const { inst } = mount(view({ finished: true, passed: false }));
  let stopped = 0;
  inst.selectComponent = () => ({ _stopAll() { stopped++; }, begin() { assert.fail('must remain read-only'); } });
  observer.call(inst, true, inst.data.options, 'pick', false, false, true);
  clearTimeout(inst._introTimer);
  assert.equal(stopped, 1);
});
