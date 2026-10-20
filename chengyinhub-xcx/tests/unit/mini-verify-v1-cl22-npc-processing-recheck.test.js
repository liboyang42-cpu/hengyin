'use strict';
// CL-22(F-R2S-15):NPC 回答还在审核中(PROCESSING / REQUEST_IN_PROGRESS)时,
// 组件用**同一个 requestId** 自动回查,拿回的是原问题的结果;不新增用户气泡,也不吞掉输入框里的下一条草稿。
// 自动回查有上限(3 次),之后留「重试」给用户手动回查。
// 负控:把 _stateOf 里 PROCESSING → CHECKING 的映射去掉,或让回查生成新 requestId,本文件必须真红。
const { test } = require('node:test');
const assert = require('node:assert/strict');

const COMPONENT_PATH = require.resolve('../../components/cy/npc-chat/index.js');

function loadChat(sendRequest) {
  let definition;
  const previous = { Component: global.Component, getApp: global.getApp };
  global.Component = (options) => { definition = options; };
  delete require.cache[COMPONENT_PATH];
  try { require(COMPONENT_PATH); } finally { global.Component = previous.Component; }
  const app = { sendRequest };
  global.getApp = () => app;
  const chat = {
    properties: { show: true, sessionId: 91, scope: 'roam' },
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(update) { Object.assign(this.data, update); },
    triggerEvent() {},
  };
  Object.assign(chat, definition.methods);
  return chat;
}

function withFakeTimers(run) {
  const timers = [];
  const real = { setTimeout: global.setTimeout, clearTimeout: global.clearTimeout };
  global.setTimeout = (fn, ms) => { const t = { fn, ms, cleared: false }; timers.push(t); return t; };
  global.clearTimeout = (t) => { if (t) t.cleared = true; };
  const flush = () => {
    for (let guard = 0; guard < 500; guard += 1) {
      const next = timers.find((t) => !t.cleared && !t.ran);
      if (!next) return;
      next.ran = true;
      next.fn();
    }
  };
  try { return run(timers, flush); } finally { Object.assign(global, real); }
}

const processing = (requestId, extra) => ({
  code: 200,
  data: Object.assign({ requestId, outcomeStatus: 'PROCESSING', safetyDecision: '', errorCode: '', safeText: '回答还在审核，请稍等' }, extra),
});
const succeeded = (requestId, text) => ({
  code: 200,
  data: { requestId, outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS', errorCode: '', safeText: text },
});

test('CL-22 PROCESSING:同一 requestId 自动回查,拿回原问题的结果,草稿与用户气泡不变', () => {
  withFakeTimers((timers, flush) => {
    const requests = [];
    const chat = loadChat((r) => { requests.push(r); return { abort() {} }; });
    chat.onInput({ detail: { value: '这家店几点关门' } });
    chat.onSend();
    const original = requests[0].data;
    requests[0].success(processing(original.requestId));
    assert.equal(chat.data.state, 'CHECKING');
    assert.equal(chat.data.retryAvailable, true, '处理中要留手动回查入口');

    chat.onInput({ detail: { value: '下一条想问的' } });
    const recheck = timers.find((t) => !t.cleared && !t.ran);
    assert.ok(recheck, '处理中必须排一次自动回查');
    recheck.ran = true; recheck.fn();
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[1].data, original, '回查必须带原 requestId / bizId / message');
    assert.equal(chat.data.input, '下一条想问的', '回查不得吞掉下一条草稿');

    requests[1].success(succeeded(original.requestId, '晚上十点关门'));
    flush();
    const assistants = chat.data.messages.filter((m) => m.role === 'assistant').map((m) => m.text);
    assert.equal(assistants[assistants.length - 1], '晚上十点关门');
    assert.equal(chat.data.messages.filter((m) => m.role === 'user').length, 1, '回查不新增用户气泡');
    assert.equal(chat.data.state, 'DONE');
    chat.cancelPending();
  });
});

test('CL-22 REQUEST_IN_PROGRESS 同样回查;自动回查 3 次后停下,仍可手动用原 requestId 回查', () => {
  withFakeTimers((timers) => {
    const requests = [];
    const chat = loadChat((r) => { requests.push(r); return { abort() {} }; });
    chat.onInput({ detail: { value: '问一次' } });
    chat.onSend();
    const requestId = requests[0].data.requestId;
    for (let i = 0; i < 4; i += 1) {
      requests[requests.length - 1].success(processing(requestId, i === 0 ? { outcomeStatus: '', errorCode: 'REQUEST_IN_PROGRESS' } : {}));
      const pending = timers.filter((t) => !t.cleared && !t.ran);
      if (i < 3) {
        assert.equal(pending.length, 1, '第 ' + (i + 1) + ' 次处理中应排自动回查');
        assert.equal(pending[0].ms, (i + 1) * 2000);
        pending[0].ran = true; pending[0].fn();
      } else {
        assert.equal(pending.length, 0, '超过 3 次不再自动回查');
      }
    }
    assert.equal(requests.length, 4);
    assert.ok(requests.every((r) => r.data.requestId === requestId));
    assert.equal(chat.data.retryAvailable, true);
    chat.onRetrySend();
    assert.equal(requests.length, 5);
    assert.equal(requests[4].data.requestId, requestId, '手动回查也查原结果');
    chat.cancelPending();
  });
});
