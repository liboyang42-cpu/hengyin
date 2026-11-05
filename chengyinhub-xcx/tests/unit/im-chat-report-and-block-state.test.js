const { test } = require('node:test')
const assert = require('node:assert/strict')

// 3-22:举报固定报最后一条(可能是自己发的);拉黑后重进会话输入框又能打字。
function loadChat() {
  let def
  const requests = []
  const tips = []
  const app = {
    globalData: {}, sendRequest: o => requests.push(o), tips: t => tips.push(t),
    getUserID: () => 1, getRequestErrorMessage: (_r, f) => f,
  };
  global.getApp = () => app
  global.wx = { getStorageSync: () => '' }
  global.Page = c => { def = c }
  const mod = require.resolve('../../subpackageB/pages/im/chat/index.js')
  delete require.cache[mod]
  require(mod)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.scrollBottom = () => {}
  page.markRead = () => {}
  return { page, requests, tips, app }
}

test('举报:只报对方发的最近一条,不报自己刚发的', () => {
  const { page, requests } = loadChat()
  page.data.myId = 1
  page.data.msgs = [{ id: 10, senderId: 2 }, { id: 11, senderId: 1 }, { id: 'tmp_x', senderId: 1 }]
  page.doReport()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].data.message_id, 10)
})

test('举报:对方没发过消息就不提交', () => {
  const { page, requests, tips } = loadChat()
  page.data.myId = 1
  page.data.msgs = [{ id: 11, senderId: 1 }]
  page.doReport()
  assert.equal(requests.length, 0)
  assert.equal(tips.length, 1)
})

test('进会话读服务端拉黑态:我拉黑了对方 → 禁用输入', () => {
  const { page, requests } = loadChat()
  page.data.myId = 1
  page.data.conversationId = 100
  page.loadMessages()
  requests[0].success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } })
  assert.equal(page.data.disabled, true)
  assert.equal(page.data.disabledText, '你已拉黑对方，无法发送消息')
})

test('进会话读服务端拉黑态:对方拉黑了我 → 也禁用', () => {
  const { page, requests } = loadChat()
  page.data.myId = 1
  page.data.conversationId = 100
  page.loadMessages()
  requests[0].success({ code: 200, data: { list: [], blocked: true, blockedByMe: false } })
  assert.equal(page.data.disabled, true)
  assert.equal(page.data.disabledText, '对方暂不可联系，无法发送消息')
})


function peerLookup() {
  const h = loadChat();
  Object.assign(h.page.data, { myId: 1, conversationId: 100, name: '', heroName: '对话',
    loadState: 'ready', disabled: false, msgs: [{ id: 10, content: '已有消息' }] });
  h.page._fillPeerName();
  return h;
}

for (const channel of ['fail', 'successStatusAbnormal', 'success']) {
  test('optional nickname failure keeps the usable chat via ' + channel, () => {
    const h = peerLookup();
    const before = JSON.stringify(h.page.data);
    assert.equal(h.requests[0].url, '/api/im/conversations');
    assert.equal(h.requests[0].silentError, true);
    h.requests[0][channel]({ code: 500, msg: 'controlled failure' }, 503);
    assert.equal(JSON.stringify(h.page.data), before);
    assert.deepEqual(h.tips, []);
    assert.equal(h.page._canSend(), true);
    h.page._fillPeerName();
    assert.equal(h.requests.length, 1, 'optional lookup does not loop on failure');
  });
}

const peerResponse = { code: 200, data: [{ conversationId: 100, type: 1, counterparty: { nickname: '小城' } }] };
test('optional nickname uses only the current peer without changing messages', () => {
  const h = peerLookup();
  const messages = h.page.data.msgs;
  h.requests[0].success(peerResponse);
  assert.equal(h.page.data.heroName, '小城');
  assert.equal(h.page.data.name, '小城');
  assert.equal(h.page.data.msgs, messages);
});

for (const retire of [h => { h.app.globalData.authorization = 'renewed'; },
  h => { h.app.getUserID = () => 2; }, h => { h.page._disposed = true; },
  h => { h.page.data.conversationId = 101; }, h => { h.page._viewEpoch = 1; }]) {
  test('retired optional nickname response cannot replace the title: ' + retire.toString(), () => {
    const h = peerLookup(); retire(h);
    h.requests[0].success(peerResponse);
    assert.equal(h.page.data.heroName, '对话');
    assert.equal(h.page.data.name, '');
  });
}


test('nickname silence has one explicit review and an extra call still fails the gate', () => {
  const fs = require('node:fs'), path = require('node:path');
  const gate = require('../../../ci/silent-error-gate');
  const root = path.resolve(__dirname, '../../..');
  const file = 'chengyinhub-xcx/subpackageB/pages/im/chat/index.js';
  const parsed = gate.parseAllowlist(fs.readFileSync(path.join(root, 'ci/silent-error-allowlist.txt'), 'utf8'));
  assert.deepEqual(parsed.problems, []);
  const entry = parsed.entries.get(file);
  const reviews = entry.lines.filter(line => line.reason.includes('_fillPeerName POST /api/im/conversations'));
  assert.equal(reviews.length, 1);
  assert.equal(reviews[0].count, 1);
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const entries = new Map([[file, entry]]);
  assert.equal(gate.reconcile(new Map([[file, gate.findSilentErrorSites(source)]]), entries).over.length, 0);
  assert.equal(gate.reconcile(new Map([[file, gate.findSilentErrorSites(source + '\napp.sendRequest({silentError:true})')]]), entries).over.length, 1);
});
