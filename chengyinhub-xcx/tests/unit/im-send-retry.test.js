const assert = require('node:assert/strict');
const { test } = require('node:test');
const path = require('node:path');
const vm = require('node:vm');
const fs = require('node:fs');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../subpackageB/pages/im/chat/index.js');
function chat() {
  let page;
  const requests = [];
  let imageCallback;
  let account = 1;
  const app = { sendRequest: r => requests.push(r), getUserID: () => account,
    chooseImage: cb => { imageCallback = cb; } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    require: createRequire(file), getApp: () => app,
    Page: p => { page = p; }, wx: {}, setTimeout: () => {}, clearTimeout() {},
  }, { filename: file });
  page.data = { ...page.data, loadState: 'ready', conversationId: 26, myId: 1, msgs: [], text: 'same text' };
  page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, selectedImage: () => imageCallback(['https://cdn/image.jpg']), account: id => { account = id; } };
}
test('a retry and an intentional identical new message must have distinguishable request identities', () => {
  const { page, requests } = chat();
  page.onSend();
  requests[0].fail({ errMsg: 'response lost after commit' });
  page.onResend({ currentTarget: { dataset: { index: 0 } } });
  const first = JSON.parse(JSON.stringify(requests[0].data));
  const retry = JSON.parse(JSON.stringify(requests[1].data));
  assert.deepEqual(retry, first, 'retry must preserve the original logical send');
  requests[1].success({ code: 200, data: { id: 61, senderId: 1, msgType: 1, content: 'same text' } });
  page.setData({ text: 'same text' });
  page.onSend();
  assert.notDeepEqual(JSON.parse(JSON.stringify(requests[2].data)), first,
    'a new intentional send is indistinguishable from retry on the current wire contract');
});
test('retrying a failed bubble must preserve an unrelated current draft', () => {
  const { page, requests } = chat();
  page.onSend();
  requests[0].fail({ errMsg: 'response lost after commit' });
  page.setData({ text: 'next message draft' });
  page.onResend({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(page.data.text, 'next message draft');
});

test('image and card retries retain original type and payload, not text messages', () => {
  for (const kind of ['image', 'card']) {
    const h = chat();
    if (kind === 'image') { h.page.onPickImage(); h.selectedImage(); }
    else h.page.sendCard({ cardType: 'location', name: 'meeting', lat: 31, lng: 121 });
    const first = JSON.parse(JSON.stringify(h.requests[0].data));
    h.requests[0].fail({ errMsg: 'lost' });
    h.page.onResend({ currentTarget: { dataset: { index: 0 } } });
    assert.deepEqual(JSON.parse(JSON.stringify(h.requests[1].data)), first);
    assert.equal(first.msg_type, kind === 'image' ? 2 : 3);
  }
});

test('polling the committed message replaces pending bubble and late failure cannot undo it', () => {
  const h = chat();
  h.page.onSend();
  h.page.pollNew();
  h.requests[1].success({ code: 200, data: { list: [{ id: 61, senderId: 1, msgType: 1,
    content: 'same text', clientMessageId: h.requests[0].data.client_message_id }] } });
  h.requests[0].fail({ errMsg: 'lost' });
  assert.equal(h.page.data.msgs.length, 1);
  assert.equal(h.page.data.msgs[0].id, 61);
  assert.equal(h.page.data.msgs[0].failed, undefined);
  assert.equal(h.page.data.sending, false);
});

test('account switch prevents new sends, image callbacks and late responses from changing this view', () => {
  const h = chat();
  h.page.onPickImage();
  h.page.onSend();
  const before = JSON.stringify(h.page.data);
  h.account(2);
  h.selectedImage();
  h.requests[0].success({ code: 200, data: { id: 61, senderId: 1 } });
  assert.equal(h.requests.length, 1);
  assert.equal(JSON.stringify(h.page.data), before);
});

test('refresh keeps a failed unsent message available for retry', () => {
  const h = chat();
  h.page.onSend();
  h.requests[0].fail({ errMsg: 'offline before commit' });
  h.page.loadMessages(false);
  h.requests[1].success({ code: 200, data: { list: [], hasMore: false } });
  assert.equal(h.page.data.msgs.length, 1);
  assert.equal(h.page.data.msgs[0].failed, true);
  h.page.onResend({ currentTarget: { dataset: { index: 0 } } });
  const sends = h.requests.filter(r => r.url === '/api/im/send');
  assert.equal(sends.length, 2);
  assert.deepEqual(sends[1].data, sends[0].data);
});

test('refresh reconciles a committed send and retains an unrelated failed send', () => {
  const h = chat();
  h.page.onSend();
  const first = h.requests[0].data.client_message_id;
  h.requests[0].fail({ errMsg: 'response lost' });
  h.page.setData({ text: 'another message' });
  h.page.onSend();
  h.requests[1].fail({ errMsg: 'offline' });
  h.page.loadMessages(false);
  h.requests[2].success({ code: 200, data: { list: [{ id: 61, senderId: 1, msgType: 1,
    content: 'same text', clientMessageId: first }], hasMore: false } });
  assert.equal(h.page.data.msgs.length, 2);
  assert.equal(h.page.data.msgs[0].id, 61);
  assert.equal(h.page.data.msgs[0].sendPayload, undefined);
  assert.equal(h.page.data.msgs[1].content, 'another message');
  assert.equal(h.page.data.msgs[1].failed, true);
  assert.equal(h.page.data.sending, false);
});

test('malformed success ids retain a failed message for retry', () => {
  for (const id of [true, [61], {}, '6e1', ' 61 ', 0, -1]) {
    const h = chat();
    h.page.onSend();
    h.requests[0].success({ code: 200, data: { id, senderId: 1 } });
    assert.equal(h.page.data.msgs[0].failed, true, JSON.stringify(id));
    assert.ok(h.page.data.msgs[0].sendPayload);
  }
});

test('older history reconciles a lost receipt without duplicating the failed bubble', () => {
  const h = chat();
  h.page.onSend();
  const key = h.requests[0].data.client_message_id;
  h.requests[0].fail({ errMsg: 'lost receipt' });
  h.page.setData({ hasMore: true, cursor: 90 });
  h.page.loadMore();
  h.requests[1].success({ code: 200, data: { list: [
    { id: 60, senderId: 2, msgType: 1, content: 'earlier' },
    { id: 61, senderId: 1, msgType: 1, content: 'same text', clientMessageId: key }
  ], hasMore: false } });
  assert.equal(h.page.data.msgs.length, 2);
  assert.equal(h.page.data.msgs[0].id, 60);
  assert.equal(h.page.data.msgs[1].id, 61);
  assert.equal(h.page.data.msgs[1].failed, undefined);
});


function pollHarness() {
  const h = chat();
  h.page.scrollBottom = () => {};
  h.reads = 0;
  h.page._markReadWhenVisible = () => { h.reads++; };
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [{ id: 100, senderId: 1, msgType: 1, content: 'baseline' }], hasMore: false } });
  h.requests.length = 0;
  h.reads = 0;
  return h;
}
function pollReply(request, ids, hasMore) {
  request.success({ code: 200, data: { list: ids.map(id => ({ id, senderId: 2, msgType: 1, content: 'message' })),
    hasMore, nextCursor: ids.length ? Math.min(...ids) : 0 } });
}
for (const count of [15, 16, 31, 70]) {
  test(`poll bridges all ${count} newly arrived messages before advancing or marking read`, () => {
    const h = pollHarness();
    const ids = Array.from({ length: count }, (_, i) => 103 + i * 3);
    h.page.pollNew();
    let offset = ids.length;
    let index = 0;
    while (offset > 0) {
      assert.ok(h.requests[index], 'must request the next older page');
      const start = Math.max(0, offset - 15);
      const chunk = ids.slice(start, offset);
      pollReply(h.requests[index++], chunk, start > 0);
      offset = start;
      if (offset) {
        assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [100], 'no partial merge');
        assert.equal(h.reads, 0, 'no premature read');
      }
    }
    assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [100, ...ids]);
    assert.equal(h.page._pollWatermark, ids.at(-1));
    assert.equal(h.reads, 1);
    assert.equal(h.page.data.hasMore, false, 'historical pagination is independent');
  });
}
test('own successful high ID does not hide lower incoming messages from the server watermark', () => {
  const h = pollHarness();
  h.page.setData({ text: 'own' }); h.page.onSend();
  h.requests[0].success({ code: 200, data: { id: 200, senderId: 1, msgType: 1, content: 'own' } });
  assert.equal(h.page._pollWatermark, 100);
  h.requests.length = 0;
  h.page.pollNew();
  pollReply(h.requests[0], [200, ...Array.from({ length: 14 }, (_, i) => 103 + i)].sort((a,b) => a-b), true);
  pollReply(h.requests[1], [100, 101, 102], false);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [100, ...Array.from({ length: 16 }, (_, i) => 101 + i), 200]);
  assert.equal(h.page.data.msgs.filter(m => m.id === 200).length, 1);
});
test('middle poll page failure keeps the old list and watermark; next round bridges without duplication', () => {
  const h = pollHarness(); h.page.pollNew();
  pollReply(h.requests[0], Array.from({ length: 15 }, (_, i) => 102 + i), true);
  assert.ok(h.requests[1]); h.requests[1].fail({ errMsg: 'offline' });
  assert.equal(h.page._pollWatermark, 100);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [100]); assert.equal(h.reads, 0);
  h.page.pollNew(); pollReply(h.requests[2], Array.from({ length: 15 }, (_, i) => 102 + i), true);
  pollReply(h.requests[3], [100, 101], false);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), Array.from({ length: 17 }, (_, i) => 100 + i));
  assert.equal(h.reads, 1);
});
test('poll is single flight and late old round cannot release a newer round after hiding', () => {
  const h = pollHarness(); h.page.pollNew(); h.page.pollNew(); assert.equal(h.requests.length, 1);
  h.page.onHide(); pollReply(h.requests[0], [101], false);
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [100]); assert.equal(h.reads, 0);
  h.page._hidden = false; h.page.pollNew();
  h.requests[0].fail({ errMsg: 'late failure' }); h.page.pollNew();
  assert.equal(h.requests.length, 2);
  pollReply(h.requests[1], [101], false); assert.equal(h.page._pollWatermark, 101);
});
test('poll bridge rejects stale account, conversation and unload callbacks', () => {
  for (const change of ['account', 'conversation', 'unload']) {
    const h = pollHarness(); h.page.pollNew();
    pollReply(h.requests[0], Array.from({ length: 15 }, (_, i) => 102 + i), true);
    if (change === 'account') h.account(2);
    if (change === 'conversation') h.page.data.conversationId = 27;
    if (change === 'unload') h.page.onUnload();
    const before = JSON.stringify(h.page.data); pollReply(h.requests[1], [100,101], false);
    assert.equal(JSON.stringify(h.page.data), before); assert.equal(h.reads, 0);
  }
});

test('a new arrival beyond the first poll head is recovered on the following round', () => {
  const h = pollHarness(); h.page.pollNew();
  pollReply(h.requests[0], Array.from({ length: 15 }, (_, i) => 102+i), true);
  pollReply(h.requests[1], [100,101], false);
  assert.equal(h.page._pollWatermark, 116);
  h.page.pollNew(); pollReply(h.requests[2], [116,117], false);
  assert.deepEqual(Array.from(h.page.data.msgs, m=>m.id), Array.from({length:18},(_,i)=>100+i));
});
test('invalid non-decreasing poll cursor fails safely without partial merge or read', () => {
  const h = pollHarness(); h.page.pollNew();
  h.requests[0].success({ code:200, data:{ list:[{id:101,senderId:2,msgType:1}],hasMore:true,nextCursor:102 } });
  assert.equal(h.page._pollWatermark,100); assert.equal(h.reads,0);
  assert.deepEqual(Array.from(h.page.data.msgs,m=>m.id),[100]);
  h.page.pollNew(); assert.equal(h.requests.length,2);
});
test('initial successful empty read establishes zero watermark and failed initial read can recover', () => {
  const h=chat(); h.page._markReadWhenVisible=()=>{};
  h.page.pollNew(); h.page.pollNew(); assert.equal(h.requests.length,1);
  assert.equal(h.requests[0].data.size,30);
  h.requests[0].fail({errMsg:'offline'});
  h.page.pollNew(); h.requests[1].success({code:200,data:{list:[],hasMore:false}});
  assert.equal(h.page._pollWatermark,0);
  h.page.pollNew(); assert.equal(h.requests[2].data.size,15);
});
test('first-page reload cancels a bridge and establishes watermark from the server rather than pending', () => {
  const h=pollHarness(); h.page.pollNew();
  pollReply(h.requests[0],Array.from({length:15},(_,i)=>102+i),true);
  h.page.data.msgs.push({id:'tmp_pending',senderId:1,clientMessageId:'pending_logical_id',sendPayload:{},failed:true});
  h.page.loadMessages(false);
  pollReply(h.requests[1],[100,101],false);
  assert.equal(h.page._pollWatermark,100);
  h.requests[2].success({code:200,data:{list:[{id:117,senderId:2,msgType:1}],hasMore:false}});
  assert.equal(h.page._pollWatermark,117);
  assert.ok(h.page.data.msgs.some(m=>m.id==='tmp_pending'));
});

test('a completed multi-page poll replaces pending by logical ID and late send failure cannot undo it', () => {
  const h=pollHarness(); h.page.setData({text:'pending'}); h.page.onSend();
  const logicalId=h.requests[0].data.client_message_id;
  h.page.pollNew();
  const list=Array.from({length:15},(_,i)=>({id:103+i,senderId:2,msgType:1,content:'message'}));
  list[14]={id:117,senderId:1,msgType:1,content:'pending',clientMessageId:logicalId};
  h.requests[1].success({code:200,data:{list,hasMore:true,nextCursor:103}});
  assert.ok(h.page.data.msgs.some(m=>m.sendPayload));
  pollReply(h.requests[2],[100,101,102],false);
  h.requests[0].fail({errMsg:'late response lost'});
  assert.equal(h.page.data.msgs.filter(m=>m.clientMessageId===logicalId).length,1);
  assert.equal(h.page.data.msgs.find(m=>m.clientMessageId===logicalId).id,117);
  assert.equal(h.page.data.msgs.some(m=>m.sending||m.failed),false);
  assert.equal(h.page._pollWatermark,117);
});
test('historical pagination cannot advance the server poll watermark', () => {
  const h=pollHarness(); h.page.setData({hasMore:true,cursor:100}); h.page.loadMore();
  pollReply(h.requests[0],[90,91],false);
  assert.equal(h.page._pollWatermark,100);
});

test('bridged pages decorate time dividers in chronological order', () => {
  const h = pollHarness(); h.page.pollNew();
  h.requests[0].success({ code: 200, data: {
    list: Array.from({ length: 15 }, (_, i) => ({ id: 102 + i, senderId: 2, msgType: 1,
      createTime: `2026-10-10 08:06:${String(i).padStart(2, '0')}` })),
    hasMore: true, nextCursor: 102,
  } });
  h.requests[1].success({ code: 200, data: { list: [
    { id: 100, senderId: 2, msgType: 1, createTime: '2026-10-10 07:50:00' },
    { id: 101, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:00' },
  ], hasMore: false, nextCursor: 100 } });
  assert.ok(h.page.data.msgs.find(m => m.id === 101).timeDivider, 'oldest bridged message starts its time group');
  assert.ok(h.page.data.msgs.find(m => m.id === 102).timeDivider, 'six minute gap retains its divider');
});

test('older pagination removes the former page-head divider within the same time group', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [
    { id: 20, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:03' },
  ], hasMore: true, nextCursor: 20 } });
  h.page.loadMore();
  h.requests[1].success({ code: 200, data: { list: [
    { id: 18, senderId: 2, msgType: 1, createTime: '2026-10-10 07:50:00' },
    { id: 19, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:00' },
  ], hasMore: false, nextCursor: 18 } });
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [18, 19, 20]);
  assert.ok(h.page.data.msgs[0].timeDivider);
  assert.ok(h.page.data.msgs[1].timeDivider, 'ten minute gap remains visible');
  assert.equal(h.page.data.msgs[2].timeDivider, '', 'three seconds is the same group across pages');
});

test('send receipts share the previous time group and retain genuine gaps', () => {
  for (const [time, newGroup] of [['08:00:03', false], ['08:06:03', true]]) {
    const h = chat(); h.page._markReadWhenVisible = () => {};
    h.page.loadMessages(false);
    h.requests[0].success({ code: 200, data: { list: [
      { id: 20, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:00' },
    ], hasMore: false } });
    h.page.onSend();
    h.requests[1].success({ code: 200, data: { id: 21, senderId: 1, msgType: 1,
      createTime: `2026-10-10 ${time}`, clientMessageId: h.requests[1].data.client_message_id } });
    assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [20, 21]);
    assert.equal(!!h.page.data.msgs[1].timeDivider, newGroup);
    assert.equal(h.page.data.sending, false);
    assert.equal(h.page.data.msgs[1].sendPayload, undefined);
  }
});

test('poll append groups the first new message with the preceding visible message', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [
    { id: 20, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:00' },
  ], hasMore: false } });
  h.page.pollNew();
  h.requests[1].success({ code: 200, data: { list: [
    { id: 21, senderId: 2, msgType: 1, createTime: '2026-10-10 08:00:03' },
    { id: 22, senderId: 2, msgType: 1, createTime: '2026-10-10 08:06:03' },
  ], hasMore: false } });
  assert.deepEqual(Array.from(h.page.data.msgs, m => m.id), [20, 21, 22]);
  assert.equal(h.page.data.msgs[1].timeDivider, '');
  assert.ok(h.page.data.msgs[2].timeDivider);
  assert.equal(h.page._pollWatermark, 22);
});


test('late-approved lower ID appears after already-read text by server visibility tuple', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  const old = { id: 100, senderId: 2, msgType: 1, content: 'read text', visibleOrderAt: '2026-10-12 08:00:00.000001' };
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [old], hasMore: false, nextCursor: 100 } });
  h.page.pollNew();
  h.requests[1].success({ code: 200, data: { list: [old,
    { id: 50, senderId: 2, msgType: 2, content: 'approved image', visibleOrderAt: '2026-10-12 08:00:00.000002' }], hasMore: false, nextCursor: 100 } });
  assert.deepEqual(Array.from(h.page.data.msgs, row => row.id), [100, 50]);
  assert.equal(h.page._pollWatermark.stamp, '2026-10-12 08:00:00.000002');
  assert.equal(h.page._pollWatermark.id, 50);
});

test('visible-order pagination uses oldest tuple ID even when IDs increase on the older page', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  const row = (id, micro) => ({ id, senderId: 2, msgType: 1, visibleOrderAt: '2026-10-12 08:00:00.' + micro });
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [row(100, '000001')], hasMore: false } });
  h.page.pollNew();
  h.requests[1].success({ code: 200, data: { list: [row(50, '000003'), row(200, '000004')], hasMore: true, nextCursor: 50 } });
  assert.equal(h.requests[2].data.cursor_id, 50);
  h.requests[2].success({ code: 200, data: { list: [row(100, '000001'), row(150, '000002')], hasMore: false, nextCursor: 100 } });
  assert.deepEqual(Array.from(h.page.data.msgs, row => row.id), [100, 150, 50, 200]);
  assert.equal(h.page._pollWatermark.id, 200);
});

test('same visible microsecond orders by ID and invalid tuple cursor cannot advance watermark', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  const stamp = '2026-10-12 08:00:00.000001';
  const row = id => ({ id, senderId: 2, msgType: 1, visibleOrderAt: stamp });
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [row(100)], hasMore: false } });
  h.page.pollNew();
  h.requests[1].success({ code: 200, data: { list: [row(102), row(103)], hasMore: true, nextCursor: 103 } });
  assert.equal(h.requests.length, 2, 'cursor must equal server ASC first row');
  assert.equal(h.page._pollWatermark.id, 100);
  h.page.pollNew();
  h.requests[2].success({ code: 200, data: { list: [row(100), row(101)], hasMore: false } });
  assert.deepEqual(Array.from(h.page.data.msgs, row => row.id), [100, 101]);
});


test('only a self-owned block offers unblock and a successful write rereads true peer blocking state', () => {
  const h = chat(); h.page._markReadWhenVisible = () => {};
  h.page.loadMessages(false);
  h.requests[0].success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } });
  assert.ok(h.page.data.moreSheetItems.includes('解除拉黑'));
  h.page.onMoreSelect({ detail: { label: '解除拉黑' } });
  h.requests[1].success({ code: 200, data: [{ conversationId: 26, type: 1, counterparty: { id: 2 } }] });
  assert.equal(h.requests[2].url, '/api/im/unblock');
  assert.equal(h.requests[2].data.target_member_id, 2);
  h.requests[2].success({ code: 200 });
  assert.equal(h.page.data.disabled, true, 'write alone cannot claim both parties can send');
  h.requests[3].success({ code: 200, data: { list: [], blocked: true, blockedByMe: false } });
  assert.equal(h.page.data.disabled, true);
  assert.equal(h.page.data.blockedByMe, false);
  assert.ok(!h.page.data.moreSheetItems.includes('解除拉黑'));
  h.page.loadMessages(false);
  h.requests[4].success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } });
  assert.equal(h.page.data.disabled, false);
});

test('failed unblock and account-switched resolution cannot enable input or submit for another identity', () => {
  const h = chat(); h.page.data.blockedByMe = true; h.page.data.disabled = true;
  h.page.doBlock(true);
  h.requests[0].success({ code: 200, data: [{ conversationId: 26, type: 1, counterparty: { id: 2 } }] });
  h.requests[1].success({ code: 500 });
  assert.equal(h.page.data.disabled, true);
  h.page.doBlock(true); h.account(3);
  h.requests[2].success({ code: 200, data: [{ conversationId: 26, type: 1, counterparty: { id: 2 } }] });
  assert.equal(h.requests.length, 3);
});
