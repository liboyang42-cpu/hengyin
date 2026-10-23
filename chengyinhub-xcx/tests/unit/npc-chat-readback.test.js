// W10b #27:cy-npc-chat 关闭/重开后按 requestId 只读回读上一条未确认的问题,不重新提交。
const assert = require('node:assert/strict');
const test = require('node:test');

const COMPONENT_PATH = require.resolve('../../subpackageMember/components/cy/npc-chat/index.js');
let definition;
let activeApp;

global.getApp = () => activeApp;
(function load() {
  const previous = global.Component;
  global.Component = (options) => { definition = options; };
  delete require.cache[COMPONENT_PATH];
  require(COMPONENT_PATH);
  global.Component = previous;
})();

function createChat(requests, properties) {
  activeApp = { sendRequest(options) { requests.push(options); return { abort() {} }; } };
  const chat = {
    properties: Object.assign({ show: true, sessionId: 91, scope: 'roam' }, properties),
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(update) { Object.assign(this.data, update); },
    triggerEvent() {},
  };
  Object.assign(chat, definition.methods);
  return chat;
}

function setShow(chat, visible) {
  chat.properties.show = visible;
  definition.properties.show.observer.call(chat, visible);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function sendAndLose(chat, requests, message) {
  chat.onInput({ detail: { value: message } });
  chat.onSend();
  const sent = requests.at(-1);
  sent.fail();
  return sent;
}

test('网络失败后关掉再打开:只读回读原 requestId,拿到已审核回复,不重发 POST', async () => {
  const requests = [];
  const chat = createChat(requests);
  const sent = sendAndLose(chat, requests, '下一步去哪？');
  setShow(chat, false);
  assert.deepEqual(chat.data.messages, [], '关闭仍清空可见对话');
  setShow(chat, true);

  const readback = requests.at(-1);
  assert.equal(requests.length, 2);
  assert.equal(readback.url, '/api/ai/npc/chat/request');
  assert.equal(readback.method, 'GET');
  assert.deepEqual(readback.data, { requestId: sent.data.requestId, scope: 'roam', bizId: 91 });
  assert.equal(chat.data.messages[0].text, '下一步去哪？', '恢复时把那条问题放回来');
  assert.equal(chat.data.state, 'CHECKING');

  readback.success({ code: 200, data: { requestId: sent.data.requestId, outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS', safeText: '往东走' } });
  await sleep(60);
  assert.equal(chat.data.messages.at(-1).text, '往东走');
  assert.equal(chat.data.state, 'DONE');
  setShow(chat, false);
  setShow(chat, true);
  assert.equal(requests.length, 2, '已恢复的问题不再回读');
});

test('回读仍在处理:「查询处理结果」只再读一次,不走 POST', () => {
  const requests = [];
  const chat = createChat(requests);
  const sent = sendAndLose(chat, requests, '在吗');
  setShow(chat, false);
  setShow(chat, true);
  requests.at(-1).success({ code: 200, data: { requestId: sent.data.requestId, outcomeStatus: 'PROCESSING', errorCode: 'REQUEST_OUTCOME_UNKNOWN', safeText: '原请求结果待确认' } });
  assert.equal(chat.data.state, 'CHECKING');
  assert.equal(chat.data.retryAvailable, true);
  chat.onRetrySend();
  assert.equal(requests.at(-1).url, '/api/ai/npc/chat/request');
  assert.equal(requests.filter((r) => r.method === 'POST').length, 1, '始终只有最初那一次提交');
});

test('回读 NOT_FOUND 才把重新发送交还给用户,且沿用原 requestId', () => {
  const requests = [];
  const chat = createChat(requests);
  const sent = sendAndLose(chat, requests, '你好');
  setShow(chat, false);
  setShow(chat, true);
  requests.at(-1).success({ code: 200, data: { requestId: sent.data.requestId, outcomeStatus: 'NOT_FOUND', errorCode: 'REQUEST_NOT_FOUND', safeText: '原问题没有送达，可以再发一次。' } });
  assert.equal(chat.data.state, 'FAILED');
  assert.equal(chat.data.retryAvailable, true);
  chat.onRetrySend();
  const resend = requests.at(-1);
  assert.equal(resend.method, 'POST');
  assert.equal(resend.data.requestId, sent.data.requestId);
});

test('回读本身失败:保持待确认,不提供重发', () => {
  const requests = [];
  const chat = createChat(requests);
  sendAndLose(chat, requests, '你好');
  setShow(chat, false);
  setShow(chat, true);
  requests.at(-1).fail();
  assert.equal(chat.data.state, 'CHECKING');
  chat.onRetrySend();
  assert.equal(requests.at(-1).url, '/api/ai/npc/chat/request');
});

test('换了会话或轨道就不回读别处的问题;已有明确结论的问题也不回读', async () => {
  const requests = [];
  const chat = createChat(requests);
  sendAndLose(chat, requests, '你好');
  setShow(chat, false);
  chat.properties.sessionId = 92;
  setShow(chat, true);
  assert.equal(requests.length, 1);

  const merchant = createChat(requests, { scope: 'merchant', sessionId: 7 });
  merchant.onInput({ detail: { value: '几点关门' } });
  merchant.onSend();
  const sent = requests.at(-1);
  sent.success({ code: 200, data: { requestId: sent.data.requestId, outcomeStatus: 'REJECTED', safetyDecision: 'BLOCK', errorCode: 'INPUT_BLOCK', safeText: '换个说法' } });
  setShow(merchant, false);
  setShow(merchant, true);
  assert.equal(requests.at(-1), sent, '已拿到结论的问题不回读');
});

test('迟到的回读响应在再次关闭后被丢弃', () => {
  const requests = [];
  const chat = createChat(requests);
  const sent = sendAndLose(chat, requests, '你好');
  setShow(chat, false);
  setShow(chat, true);
  const readback = requests.at(-1);
  setShow(chat, false);
  readback.success({ code: 200, data: { requestId: sent.data.requestId, outcomeStatus: 'SUCCEEDED', safetyDecision: 'PASS', safeText: '迟到' } });
  assert.deepEqual(chat.data.messages, []);
});
