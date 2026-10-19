const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '../../subpackageB/pages/im/chat/index.js'), 'utf8');

function loadPage(app, wx) {
  let definition;
  const sandbox = {
    require(name) {
      if (name.includes('datetime')) return { toTimestamp: () => 0, chinaDateKey: () => '', chinaParts: () => null };
      return { isRecordList: Array.isArray, isBizOk: res => res && res.code === 200 };
    },
    getApp: () => app,
    Page: value => { definition = value; },
    wx,
    setTimeout: fn => fn(), clearTimeout() {}, setInterval() { return 1; }, clearInterval() {},
    console,
  };
  vm.runInNewContext(source, sandbox, { filename: 'im/chat/index.js' });
  definition.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) rendered(); };
  definition.data = Object.assign({}, definition.data, {
    myId: 7, conversationId: 88, loadState: 'ready', msgs: [], disabled: false, readOnly: false,
  });
  definition.decorate = list => list;
  definition.scrollBottom = () => {};
  return definition;
}

function storageMock() {
  const values = new Map();
  return {
    values,
    getStorageSync: key => values.get(key),
    setStorageSync: (key, value) => values.set(key, value),
    removeStorageSync: key => values.delete(key),
  };
}

(function catchesEveryPageBeforeReadAndScopesCursor() {
  const wx = storageMock();
  const calls = [];
  const app = { getUserID: () => 7, sendRequest(options) {
    calls.push(options);
    if (options.url === '/api/im/read') return options.success && options.success({ code: 200 });
    const after = Number(options.data.after_id);
    if (after === 10) return options.success({ code: 200, data: { list: [{ id: 11 }, { id: 12 }], nextAfter: 12, watermark: 14, hasMore: true } });
    return options.success({ code: 200, data: { list: [{ id: 13 }, { id: 14 }], nextAfter: 14, watermark: 14, hasMore: false } });
  } };
  const page = loadPage(app, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 10);
  page.syncNewMessages();
  assert.deepStrictEqual(Array.from(page.data.msgs, m => m.id), [11, 12, 13, 14]);
  assert.strictEqual(wx.getStorageSync('im_cursor_v1_7_88'), 14);
  const read = calls.find(call => call.url === '/api/im/read');
  assert.strictEqual(read.data.read_to, 14);
  page.data.conversationId = 89;
  assert.strictEqual(page._storedAfterId(), 0, '另一个会话不得复用游标');
  page.data.myId = 8;
  page.data.conversationId = 88;
  assert.strictEqual(page._storedAfterId(), 0, '另一个账号不得复用游标');
})();

(function failedPageDoesNotCommitOrRead() {
  const wx = storageMock();
  let reads = 0;
  const app = { getUserID: () => 7, sendRequest(options) {
    if (options.url === '/api/im/read') { reads++; return; }
    if (Number(options.data.after_id) === 3) return options.success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 6, hasMore: true } });
    options.fail({ errMsg: 'offline' });
  } };
  const page = loadPage(app, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  assert.strictEqual(reads, 0);
})();

(function persistenceFailureBlocksSendAndRetryReusesPayload() {
  let sends = 0;
  const brokenWx = storageMock();
  brokenWx.setStorageSync = () => { throw new Error('full'); };
  const blocked = loadPage({ getUserID: () => 7, sendRequest() { sends++; }, tips() {} }, brokenWx);
  blocked._sendMessage({ msg_type: 1, content: 'secret' });
  assert.strictEqual(sends, 0, '提交前元数据落盘失败时不得发送');

  const wx = storageMock();
  const payloads = [];
  const app = { getUserID: () => 7, sendRequest(options) { payloads.push(options.data); options.fail({}); }, tips() {} };
  const page = loadPage(app, wx);
  page._sendMessage({ msg_type: 1, content: 'same body' });
  const failed = page.data.msgs[0];
  page._sendMessage(failed.sendPayload, failed.id);
  assert.strictEqual(payloads.length, 2);
  assert.strictEqual(payloads[1].client_message_id, payloads[0].client_message_id);
  assert.strictEqual(payloads[1].content, payloads[0].content);
  const stored = wx.getStorageSync(page._scopeKey('pending'));
  assert.strictEqual(stored.length, 1);
  assert.strictEqual(Object.prototype.hasOwnProperty.call(stored[0], 'content'), false, '不得明文持久化正文');
})();

(function multiplePendingRecoverExactlyWithoutInventingBody() {
  const wx = storageMock();
  const calls = [];
  const app = { getUserID: () => 7, sendRequest(options) {
    calls.push(options);
    if (options.data.client_message_id === 'send_0123456789012345') return options.success({
      code: 200, data: { id: 41, senderId: 7, clientMessageId: 'send_0123456789012345', content: 'server fact' },
    });
    if (options.data.client_message_id === 'send_0123456789012346') return options.success({ code: 200, data: null });
  } };
  const page = loadPage(app, wx);
  page._messageRequestEpoch = 3;
  page._savePendingSend({ accountId: 7, conversationId: 88, clientMessageId: 'send_0123456789012345', msgType: 1, state: 'submitting' });
  page._savePendingSend({ accountId: 7, conversationId: 88, clientMessageId: 'send_0123456789012346', msgType: 1, state: 'submitting' });
  assert.strictEqual(wx.getStorageSync(page._scopeKey('pending')).length, 2, '后一条不得覆盖前一条');
  page._recoverPendingFromMessages([]);
  assert.strictEqual(calls.length, 2);
  assert.strictEqual(page.data.msgs.length, 1);
  assert.strictEqual(page.data.msgs[0].id, 41);
  const remaining = wx.getStorageSync(page._scopeKey('pending'));
  assert.strictEqual(remaining.length, 1);
  assert.strictEqual(remaining[0].clientMessageId, 'send_0123456789012346');
  assert.strictEqual(remaining[0].state, 'recovery-missing-body');
  assert.strictEqual(page.data.msgs.some(message => message.clientMessageId === 'send_0123456789012346'), false,
    '原文不在时不得伪造消息');
})();

(function hiddenOrStalePageMustNotReadOrUnlockNewSync() {
  const wx = storageMock();
  const calls = [];
  const app = { getUserID: () => 7, sendRequest(options) { calls.push(options); } };
  const page = loadPage(app, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  const old = calls[0];
  page.onHide();
  old.success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
  assert.strictEqual(page.data.msgs.length, 0, '后台回包不当作用户已消费');
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  page._visible = true;
  page.syncNewMessages();
  old.success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
  assert.strictEqual(page._syncing, true, '旧代回包不得解掉新同步锁');
  calls[1].success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
  assert.strictEqual(calls.filter(c => c.url === '/api/im/read').length, 1);
})();

(function invalidOrNonAdvancingPageCannotCommitRead() {
  for (const data of [
    { list: [{ id: 4 }], hasMore: false },
    { list: [], nextAfter: 3, watermark: 8, hasMore: true },
    { list: [], nextAfter: 4, watermark: 8, hasMore: true },
    { list: [{ id: '4x' }], nextAfter: 4, watermark: 8, hasMore: false },
    { list: [{ id: 4 }, { id: 4 }], nextAfter: 4, watermark: 8, hasMore: false },
    { list: [{ id: 9 }], nextAfter: 9, watermark: 8, hasMore: false },
    { list: [{ id: 4 }], nextAfter: 5, watermark: 8, hasMore: false },
    { list: [{ id: 2 }], nextAfter: 2, watermark: 8, hasMore: false },
  ]) {
    const wx = storageMock();
    const calls = [];
    const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); o.success({ code: 200, data }); } }, wx);
    wx.setStorageSync(page._scopeKey('cursor'), 3);
    page.syncNewMessages();
    assert.strictEqual(calls.length, 1, '异常合同不继续递归或发已读');
    assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  }
})();

(function renderMustFinishBeforeReadAndBackgroundRenderCannotCommit() {
  for (const hideBeforeRender of [false, true]) {
    const wx = storageMock();
    const calls = [];
    const app = { getUserID: () => 7, sendRequest(options) {
      calls.push(options);
      if (options.url === '/api/im/messages') options.success({
        code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false },
      });
    } };
    const page = loadPage(app, wx);
    let rendered;
    page.setData = function (patch, callback) { Object.assign(this.data, patch); rendered = callback; };
    wx.setStorageSync(page._scopeKey('cursor'), 3);
    page.syncNewMessages();
    assert.strictEqual(calls.length, 1, 'render pending must not acknowledge');
    assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
    assert.strictEqual(page._syncing, true);
    const finishRender = rendered;
    if (hideBeforeRender) page.onHide();
    finishRender();
    assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), hideBeforeRender ? 3 : 4);
    assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, hideBeforeRender ? 0 : 1);
  }
})();

(function changedWatermarkCannotAcknowledgePartialCatchup() {
  const wx = storageMock();
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(options) {
    calls.push(options);
    const first = calls.length === 1;
    options.success({ code: 200, data: {
      list: [{ id: first ? 4 : 5 }], nextAfter: first ? 4 : 5,
      watermark: first ? 8 : 9, hasMore: first,
    } });
  } }, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  assert.strictEqual(calls.length, 2);
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, 0);
})();

(function initialAndHistoryCallbacksMustBelongToCurrentView() {
  for (const method of ['loadMessages', 'loadMore']) {
    for (const stale of ['account', 'conversation', 'hide', 'unload', 'refresh']) {
      for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
        const wx = storageMock();
        const calls = [];
        let owner = 7;
        const page = loadPage({ getUserID: () => owner, sendRequest(o) { calls.push(o); } }, wx);
        page.data.hasMore = true;
        page.data.cursor = 20;
        page[method](false);
        const request = calls[0];
        if (stale === 'account') owner = 8;
        if (stale === 'conversation') page.data.conversationId = 89;
        if (stale === 'hide') page.onHide();
        if (stale === 'unload') page.onUnload();
        if (stale === 'refresh') page.loadMessages(false);
        const before = JSON.stringify(page.data);
        request[callback]({ code: callback === 'success' ? 200 : 500,
          data: { list: [{ id: 19, content: 'old account' }], hasMore: false } });
        assert.strictEqual(JSON.stringify(page.data), before,
          method + '/' + stale + '/' + callback + ' must not change the current view');
        assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, 0);
      }
    }
  }
})();

(function refreshingInvalidatesOldIncrementalPages() {
  const wx = storageMock();
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  const old = calls[0];
  page.loadMessages(false);
  const before = JSON.stringify(page.data);
  old.success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
  assert.strictEqual(JSON.stringify(page.data), before);
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, 0);
})();

(function foregroundRestartsInterruptedInitialLoadButDoesNotReopenClosedChat() {
  const wx = storageMock();
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  page.data.loadState = 'loading';
  page.loadMessages(true);
  page.onShow();
  assert.strictEqual(calls.length, 1, 'onShow must not duplicate a pending initial load');
  page.onHide();
  page.onShow();
  assert.strictEqual(calls.length, 2, 'foreground must restart the interrupted initial load');
  calls[1].success({ code: 409, errorCode: 'HANGOUT_CLOSED' });
  page.onHide();
  page.onShow();
  page.pollNew();
  assert.strictEqual(calls.length, 2, 'closed conversation must not be reopened by polling');
})();

(function initialRenderMustCompleteBeforeStartingCatchup() {
  for (const hideBeforeRender of [false, true]) {
    const wx = storageMock();
    const calls = [];
    const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
    const callbacks = [];
    page.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) callbacks.push(rendered); };
    page.loadMessages(true);
    calls[0].success({ code: 200, data: { list: [{ id: 3 }], hasMore: false } });
    page.pollNew();
    assert.strictEqual(calls.length, 1, 'no read or catchup before initial render');
    if (hideBeforeRender) page.onHide();
    callbacks[0]();
    assert.strictEqual(calls.length, hideBeforeRender ? 1 : 2);
    assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, 0);
  }
})();

(function hidingBeforeInitialRenderRequiresANewRenderedInitialPage() {
  const wx = storageMock();
  const calls = [];
  const callbacks = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  page.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) callbacks.push(rendered); };
  page.loadMessages(true);
  calls[0].success({ code: 200, data: { list: [{ id: 3 }], hasMore: false } });
  page.onHide();
  page.onShow();
  assert.strictEqual(calls[1].data.cursor_id, 0, 'foreground must render a new initial page before catchup');
  callbacks[0]();
  assert.strictEqual(calls.length, 2, 'stale initial render cannot start another sync');
  assert.strictEqual(calls.filter(call => call.url === '/api/im/read').length, 0);
})();

require('node:test').test('unconfirmed persistence retains the exact draft and never sends', () => {
  for (const failure of ['drop', 'read-error', 'changed']) {
    const wx = storageMock();
    const calls = [];
    const read = wx.getStorageSync;
    const write = wx.setStorageSync;
    let wrote = false;
    wx.setStorageSync = (key, value) => {
      wrote = true;
      if (failure !== 'drop') write(key, failure === 'changed' ? [] : value);
    };
    wx.getStorageSync = key => {
      if (wrote && failure === 'read-error') throw new Error('read failed after write');
      return read(key);
    };
    const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); }, tips() {} }, wx);
    page.data.text = '  original draft  ';
    page.onSend();
    assert.strictEqual(calls.length, 0, failure + ' cannot authorize a send');
    assert.strictEqual(page.data.text, '  original draft  ', 'unsafe persistence must keep the exact draft');
    assert.strictEqual(page.data.msgs.length, 0, 'no pretend submitted message');
  }
});

require('node:test').test('unconfirmed cursor writes cannot acknowledge read', () => {
  for (const failure of ['drop', 'read-error', 'changed']) {
    const wx = storageMock();
    const calls = [];
    const write = wx.setStorageSync;
    const read = wx.getStorageSync;
    const page = loadPage({ getUserID: () => 7, sendRequest(o) {
      calls.push(o);
      if (o.url === '/api/im/messages') o.success({ code: 200,
        data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
    } }, wx);
    write(page._scopeKey('cursor'), 3);
    let wrote = false;
    wx.setStorageSync = (key, value) => {
      wrote = true;
      if (failure !== 'drop') write(key, failure === 'changed' ? 5 : value);
    };
    wx.getStorageSync = key => {
      if (wrote && failure === 'read-error') throw new Error('read failed after write');
      return read(key);
    };
    page.syncNewMessages();
    assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0,
      failure + ' cannot prove a consumed cursor');
  }
});

require('node:test').test('an early terminal page cannot skip the frozen watermark', () => {
  for (const data of [
    { list: [], nextAfter: 3, watermark: 6, hasMore: false },
    { list: [{ id: 4 }], nextAfter: 4, watermark: 6, hasMore: false },
    { list: [], nextAfter: 6, watermark: 6, hasMore: false },
  ]) {
    const wx = storageMock();
    const calls = [];
    const page = loadPage({ getUserID: () => 7, sendRequest(o) {
      calls.push(o);
      if (o.url === '/api/im/messages') o.success({ code: 200, data });
    } }, wx);
    wx.setStorageSync(page._scopeKey('cursor'), 3);
    page.syncNewMessages();
    assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3, 'unfetched range cannot be committed');
    assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0);
    assert.strictEqual(page.data.msgs.length, 0, 'invalid terminal response must not be rendered');
  }
});

require('node:test').test('a send recovery cannot move an uncommitted sync baseline past missing messages', () => {
  const wx = storageMock();
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  page.data.msgs = [{ id: 3 }];
  page.syncNewMessages();
  assert.strictEqual(calls[0].data.after_id, 3);
  calls[0].fail({ errMsg: 'offline' });
  page._mergeMessages([{ id: 8, senderId: 7, clientMessageId: 'send_0123456789012345' }]);
  page.pollNew();
  assert.strictEqual(calls[1].data.after_id, 3, 'retry must resume the unread gap, not the recovered send ID');
  calls[1].success({ code: 200, data: {
    list: [{ id: 4 }, { id: 6 }, { id: 8, senderId: 7, clientMessageId: 'send_0123456789012345' }],
    nextAfter: 8, watermark: 8, hasMore: false,
  } });
  assert.deepStrictEqual(Array.from(page.data.msgs, m => m.id).sort((a, b) => a - b), [3, 4, 6, 8]);
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 8);
});

require('node:test').test('uncommitted baseline survives foreground and resets for another identity or conversation', () => {
  for (const transition of ['foreground', 'conversation', 'account', 'unload']) {
    const wx = storageMock();
    const calls = [];
    let owner = 7;
    const page = loadPage({ getUserID: () => owner, sendRequest(o) { calls.push(o); } }, wx);
    page.data.msgs = [{ id: 3 }];
    page.syncNewMessages();
    calls[0].fail({ errMsg: 'offline' });
    page._mergeMessages([{ id: 8 }]);
    if (transition === 'foreground') { page.onHide(); page.onShow(); }
    if (transition === 'conversation') { page.data.conversationId = 89; page.data.msgs = [{ id: 20 }]; page.pollNew(); }
    if (transition === 'account') { owner = 8; page.data.myId = 8; page.data.msgs = [{ id: 20 }]; page.pollNew(); }
    if (transition === 'unload') { page.onUnload(); page.pollNew(); }
    if (transition === 'unload') {
      assert.strictEqual(calls.length, 1);
    } else {
      assert.strictEqual(calls[1].data.after_id, transition === 'foreground' ? 3 : 20);
      const before = JSON.stringify(page.data);
      calls[0].success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
      assert.strictEqual(JSON.stringify(page.data), before, 'old response cannot consume a new view');
      assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0);
    }
  }
});

require('node:test').test('read consumption: closed incremental responses end polling in every callback', () => {
  for (const errorCode of ['HANGOUT_CLOSED', 'IM_CONVERSATION_CLOSED']) {
  for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
    const wx = storageMock();
    const calls = [];
    const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
    page.data.msgs = [{ id: 3 }];
    page.syncNewMessages();
    calls[0][callback]({ code: 409, errorCode, msg: 'closed' });
    assert.strictEqual(page.data.loadState, 'closed');
    page.pollNew();
    assert.strictEqual(calls.length, 1, 'terminated chat cannot keep polling or read');
  }
  }
});

require('node:test').test('read consumption: a persisted cursor without a rendered message cannot acknowledge', () => {
  const wx = storageMock();
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) {
    calls.push(o);
    if (o.url === '/api/im/messages') o.success({ code: 200, data: {
      list: [], nextAfter: 3, watermark: 3, hasMore: false,
    } });
  } }, wx);
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0);
});

require('node:test').test('read consumption: data assignment before render, hidden and stale views cannot read', () => {
  for (const stale of ['render-pending', 'hide', 'unload', 'account', 'conversation', 'closed']) {
    const wx = storageMock();
    const calls = [];
    const callbacks = [];
    let owner = 7;
    const page = loadPage({ getUserID: () => owner, sendRequest(o) { calls.push(o); } }, wx);
    page.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) callbacks.push(rendered); };
    page.loadMessages(true);
    calls[0].success({ code: 200, data: { list: [{ id: 4 }], hasMore: false } });
    if (stale !== 'render-pending') callbacks[0]();
    if (stale === 'hide') page.onHide();
    if (stale === 'unload') page.onUnload();
    if (stale === 'account') owner = 8;
    if (stale === 'conversation') page.data.conversationId = 89;
    if (stale === 'closed') page._setMessageLoadError({ errorCode: 'HANGOUT_CLOSED' });
    const beforeReads = calls.filter(o => o.url === '/api/im/read').length;
    page.markRead(4);
    assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, beforeReads, stale);
  }
});

require('node:test').test('read consumption: closing while incremental render is pending invalidates read and cursor', () => {
  const wx = storageMock();
  const calls = [];
  const callbacks = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  page.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) callbacks.push(rendered); };
  wx.setStorageSync(page._scopeKey('cursor'), 3);
  page.syncNewMessages();
  calls[0].success({ code: 200, data: { list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false } });
  page._setMessageLoadError({ errorCode: 'HANGOUT_CLOSED' });
  callbacks[0]();
  assert.strictEqual(wx.getStorageSync(page._scopeKey('cursor')), 3);
  assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0);
});

require('node:test').test('unconfirmed cursor replacement cannot change the retry baseline', () => {
  const wx = storageMock();
  const calls = [];
  const write = wx.setStorageSync;
  const page = loadPage({ getUserID: () => 7, sendRequest(o) {
    calls.push(o);
    if (calls.length === 1) o.success({ code: 200, data: {
      list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false,
    } });
  } }, wx);
  write(page._scopeKey('cursor'), 3);
  wx.setStorageSync = (key, value) => write(key, key === page._scopeKey('cursor') ? 5 : value);
  page.syncNewMessages();
  assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 0);
  page.pollNew();
  assert.strictEqual(calls[1].data.after_id, 3, 'unconfirmed storage must not overwrite a known uncommitted baseline');
});

require('node:test').test('read consumption: foreground acknowledges only after its new render completes', () => {
  const wx = storageMock();
  const calls = [];
  const callbacks = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
  page.data.msgs = [{ id: 4 }];
  page._consumeRenderedMessages(page.data.msgs);
  page.onHide();
  page.setData = function (patch, rendered) { Object.assign(this.data, patch); if (rendered) callbacks.push(rendered); };
  page.onShow();
  page.markRead(4);
  assert.strictEqual(calls.length, 0, 'foreground data alone does not authorize read');
  callbacks[0]();
  calls[0].success({ code: 200, data: { list: [], nextAfter: 4, watermark: 4, hasMore: false } });
  assert.strictEqual(calls.filter(o => o.url === '/api/im/read').length, 1);
  assert.strictEqual(calls[1].data.read_to, 4);
});

require('node:test').test('read terminal response closes only the current rendered view', () => {
  for (const errorCode of ['HANGOUT_CLOSED', 'IM_CONVERSATION_CLOSED']) {
    for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
      for (const stale of ['current', 'hide', 'account', 'conversation']) {
        const calls = [];
        let owner = 7;
        const page = loadPage({ getUserID: () => owner, sendRequest(o) { calls.push(o); } }, storageMock());
        page.data.msgs = [{ id: 4 }];
        page._consumeRenderedMessages(page.data.msgs);
        page.markRead(4);
        if (stale === 'hide') page.onHide();
        if (stale === 'account') owner = 8;
        if (stale === 'conversation') page.data.conversationId = 89;
        calls[0][callback]({ code: 409, errorCode });
        assert.strictEqual(page.data.loadState === 'closed', stale === 'current');
      }
    }
  }
});

require('node:test').test('synchronous read rejection cannot be reopened by the completed sync', () => {
  const calls = [];
  const page = loadPage({ getUserID: () => 7, sendRequest(o) {
    calls.push(o);
    if (o.url === '/api/im/messages') o.success({ code: 200, data: {
      list: [{ id: 4 }], nextAfter: 4, watermark: 4, hasMore: false,
    } });
    else if (o.successStatusAbnormal) o.successStatusAbnormal({ code: 409, errorCode: 'IM_CONVERSATION_CLOSED' });
  } }, storageMock());
  page.syncNewMessages();
  assert.strictEqual(page.data.loadState, 'closed');
  page.pollNew();
  assert.strictEqual(calls.length, 2);
});

require('node:test').test('history send and recovery consume terminal replies without discarding pending identity', () => {
  for (const entry of ['history', 'send', 'recovery']) {
    for (const callback of ['success', 'fail', 'successStatusAbnormal']) {
      for (const stale of [false, true]) {
        const calls = [];
        const wx = storageMock();
        const page = loadPage({ getUserID: () => 7, sendRequest(o) { calls.push(o); } }, wx);
        page.data.hasMore = true;
        const pending = { accountId: 7, conversationId: 88, clientMessageId: 'send_0123456789012345', state: 'submitting' };
        if (entry === 'history') page.loadMore();
        if (entry === 'send') page._sendMessage({ msg_type: 1, content: 'keep this draft' });
        if (entry === 'recovery') {
          page._savePendingSend(pending);
          page._recoverPendingSend(pending);
        }
        const pendingBefore = JSON.stringify(wx.getStorageSync(page._scopeKey('pending')));
        if (stale) page.data.conversationId = 89;
        calls[0][callback]({ code: 409, errorCode: 'IM_CONVERSATION_CLOSED', msg: 'closed' });
        assert.strictEqual(page.data.loadState === 'closed', !stale, entry + ':' + callback);
        if (!stale) {
          assert.strictEqual(JSON.stringify(wx.getStorageSync(page._scopeKey('pending'))), pendingBefore);
          assert.strictEqual(page.data.loadingMore, false);
        }
      }
    }
  }
});

console.log('im-chat-sync-recovery: 13 embedded scenarios passed; persistence cases use node:test');
