const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

global.Behavior = value => value;
global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
global.getApp = () => ({ globalData: { features: {} }, isDevEnv: () => false, sendRequest() {} });
let qaDef, pageDef;
global.Component = value => { qaDef = value; };
require('../../pages/play/components/playkit-qa/index.js');
global.Page = value => { pageDef = value; };
require('../../pages/play/index.js');

function qa(passed, finished, mode = 'type') {
  const events = [];
  const instance = Object.assign({}, qaDef.methods, {
    data: Object.assign({}, qaDef.data, { mode, passed, finished, feedbackText: '', maxTries: 0 }),
    setData(update) { Object.assign(this.data, update); },
    selectComponent: () => null,
    triggerEvent(name, detail) { events.push({ name, detail }); },
  });
  qaDef.observers['show, options, mode, multi, passed, finished, judge, lastOptionId, lastOptionIds, feedbackText, feedbackImages, sessionId, nodeId'].call(instance,
    true, [], mode, false, passed, finished);
  clearTimeout(instance._introTimer);
  return { instance, events };
}

test('服务端已答对的文字/选择/照片题重进显示继续，不再提交已结束的答案', () => {
  for (const mode of ['type', 'pick', 'shot']) {
    const { instance, events } = qa(true, true, mode);
    assert.equal(instance.data.ctaLabel, '继续');
    assert.equal(instance.data.ctaDisabled, false);
    instance.onCta();
    assert.deepEqual(events, [{ name: 'continue', detail: undefined }]);
  }
});

test('耗尽机会的失败题不允许继续；普通未完成题保留提交入口', () => {
  const failed = qa(false, true);
  assert.equal(failed.instance.data.ctaDisabled, true);
  failed.instance.onCta();
  assert.equal(failed.events.length, 0);
  assert.equal(qa(false, false).instance.data.ctaLabel, '提交');
});

test('继续事件必须同时匹配问答类型与服务端可继续状态', () => {
  for (const [kitType, ready, eventType, expected] of [
    ['qa', true, 'qa', 1], ['qa', false, 'qa', 0], ['scan', true, 'qa', 0], ['qa', true, 'scan', 0],
  ]) {
    let advanced = 0;
    const page = Object.assign({}, pageDef, {
      data: { isPreview: false, playKit: { kit: { type: kitType }, readyForBase: ready } },
      continueStoryGame() { advanced++; },
    });
    page.onPlayKitAction({ detail: { type: eventType, action: 'continue', detail: { passed: true } } });
    assert.equal(advanced, expected);
  }
});

test('关闭高级面板总是清理旧题面，避免题面继续写入已关闭的会话', () => {
  for (const chapterFull of [false, true]) {
    const page = Object.assign({}, pageDef, {
      data: { chapterFull, advancedPlay: { show: true, node: { nodeId: 257 } },
        playKit: { show: true, kit: { type: 'qa', sessionId: 16 } } },
      setData(update) { Object.assign(this.data, update); }, openChapterFull() {},
    });
    page.onAdvancedClose();
    assert.deepEqual(page.data.playKit, { show: false, kit: null });
  }
});

test('分发器递交权威通过状态和继续事件', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit/index.wxml'), 'utf8');
  const qaBlock = source.match(/<cy-playkit-qa[\s\S]*?\/>/)[0];
  assert.match(qaBlock, /passed="\{\{kit\.passed\}\}"/);
  assert.match(qaBlock, /finished="\{\{kit\.finished\}\}"/);
  assert.match(qaBlock, /bind:continue="onContinue"/);
});
