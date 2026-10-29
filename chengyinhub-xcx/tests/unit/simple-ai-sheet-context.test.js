'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { makeOptionSheet, harness, journal, item, record, RID, OTHER } = require('./simple-ai-v2-guards.test.js');
function show(h, rows = [item(100)]) {
  h.finish(h.take('/recovery'), { code: 200, data: { items: rows, nextCursor: null } });
  return h.page.selectComponent('#ai-history-sheet');
}
function closeEvent(page, sheet) {
  sheet.onClose(); const event = page._sheetEvents.shift(); assert.equal(event.name, 'cancel'); return { detail: event.detail };
}

test('legacy normal/default-index selection and cancel retain exact payloads without context or one-shot semantics', () => {
  const events = []; const sheet = makeOptionSheet({ show: true, items: ['one', 'two'], defaultIndex: 1 }, (name, detail) => events.push({ name, detail }));
  sheet.onConfirm(); sheet.onRow({ currentTarget: { dataset: { index: 0 } } }); sheet.onConfirm(); sheet.onClose();
  assert.deepEqual(events, [{ name: 'select', detail: { index: 1, label: 'two' } }, { name: 'select', detail: { index: 0, label: 'one' } }, { name: 'cancel', detail: undefined }]);
});
test('legacy immediate/disabled row and normal pick behavior stay unchanged', () => {
  const events = []; const sheet = makeOptionSheet({ show: true, immediate: true, items: ['one', { label: 'off', disabled: true }] }, (name, detail) => events.push({ name, detail }));
  sheet.onRow({ currentTarget: { dataset: { index: 1 } } }); assert.equal(events.length, 0);
  sheet.onRow({ currentTarget: { dataset: { index: 0 } } }); sheet.onRow({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(events, Array.from({ length: 2 }, () => ({ name: 'select', detail: { index: 0, label: 'one' } })));
});
test('bound component context is instance-only, immutable per opening, and emits at most one terminal event', () => {
  const events = []; const sheet = makeOptionSheet({ show: true, items: ['one'] }, (name, detail) => events.push({ name, detail }));
  assert.equal(sheet.bindEventContext('first'), true); assert.equal(sheet.bindEventContext('replacement'), false);
  sheet.onRow({ currentTarget: { dataset: { index: 0 } } }); sheet.onConfirm(); sheet.onClose(); sheet.onConfirm();
  assert.deepEqual(events, [{ name: 'select', detail: { index: 0, label: 'one', eventContext: 'first' } }]);
  assert.equal(sheet.data.eventContext, undefined); assert.equal(sheet.data._boundEventContext, undefined);
});
test('detached old component cannot emit or be rebound; already emitted event retains its old opening context', () => {
  const events = []; const old = makeOptionSheet({ show: true, immediate: true, items: ['old'] }, (name, detail) => events.push({ name, detail }));
  old.bindEventContext('old'); old.onRow({ currentTarget: { dataset: { index: 0 } } }); old.detach();
  old.onRow({ currentTarget: { dataset: { index: 0 } } }); old.onConfirm(); old.onClose(); assert.equal(old.bindEventContext('new'), false);
  assert.equal(events.length, 1); assert.equal(events[0].detail.eventContext, 'old');
  const fresh = makeOptionSheet({ show: true, items: ['new'] }); assert.equal(fresh.bindEventContext('new'), true);
});
test('Page receives actual component event context without dataset hop/epoch; full private identity remains captured', () => {
  const h = harness(); const sheet = show(h); const ctx = h.page._sheetContext;
  assert.equal(h.page.data.aiSheetHop, undefined); assert.equal(h.page.data.aiSheetEpoch, undefined);
  assert.ok(Number.isSafeInteger(ctx.hop)); assert.ok(Number.isSafeInteger(ctx.epoch));
  assert.equal(ctx.owner, '7'); assert.equal(ctx.revision, h.store.getRevision()); assert.equal(ctx.auth, h.store.getAuthorization());
  assert.equal(sheet._boundEventContext, ctx.eventContext); const event = h.selection(0); delete event.currentTarget;
  assert.deepEqual(Object.keys(event.detail).sort(), ['eventContext', 'index', 'label']);
  assert.equal(JSON.stringify(event.detail).includes('local-token'), false);
  h.page.onAiHistorySelect(event); assert.deepEqual(h.take('/request').data, { requestId: RID });
  assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
});
test('queued select/cancel from previous opening cannot select or close a new opening even for same owner and pending', () => {
  for (const kind of ['select', 'cancel']) {
    const h = harness(); const first = show(h); const event = kind === 'select' ? h.selection(0) : closeEvent(h.page, first);
    const oldContext = first._boundEventContext; h.page.openAiHistory(); const fresh = show(h, [item(101)]);
    assert.notEqual(fresh, first); assert.notEqual(fresh._boundEventContext, oldContext);
    const data = JSON.stringify(h.page.data), values = JSON.stringify(h.values), writes = h.writes.length, requests = h.sent.length;
    h.page.onAiHistorySelect(event); h.page.onAiHistoryCancel(event);
    first.onConfirm(); first.onClose();
    assert.equal(JSON.stringify(h.page.data), data); assert.equal(JSON.stringify(h.values), values); assert.equal(h.writes.length, writes); assert.equal(h.sent.length, requests);
    h.page.onAiHistorySelect(h.selection(0)); assert.deepEqual(h.take('/request').data, { requestId: item(101).requestId });
  }
});
test('actual sheet events preserve owner/auth/session ABA/page/hop/pending guards for select and cancel', () => {
  const changes = [h => h.switchOwner('8', 'B'), h => { h.switchOwner('8', 'B'); h.switchOwner('7', 'local-token-A'); },
    h => h.store.setAuthorization('local-token-A'), h => h.page.onHide(), h => h.page.onUnload(), h => { h.page._hops.sheet++; },
    h => { h.values[journal.key('7')] = record({ requestId: OTHER, state: 'UNKNOWN', stage: 'KNOWN' }); }];
  for (const change of changes) {
    const h = harness(); show(h); const event = h.selection(0); change(h);
    const data = JSON.stringify(h.page.data), values = JSON.stringify(h.values), writes = h.writes.length, requests = h.sent.length;
    h.page.onAiHistorySelect(event); h.page.onAiHistoryCancel(event);
    assert.equal(JSON.stringify(h.page.data), data); assert.equal(JSON.stringify(h.values), values); assert.equal(h.writes.length, writes); assert.equal(h.sent.length, requests);
  }
});
test('late render callback after same-token ABA cannot bind or render a sheet for the new session', () => {
  const h = harness({ deferSetData: true }); show(h); assert.equal(h.delayed.length, 1);
  h.switchOwner('8', 'B'); h.switchOwner('7', 'local-token-A'); const before = JSON.stringify(h.page.data);
  h.delayed.shift()(); assert.equal(JSON.stringify(h.page.data), before); assert.equal(h.page.selectComponent('#ai-history-sheet'), null);
  assert.equal(h.sent.filter(r => /prepare|execute|request$/.test(r.url)).length, 0);
});
test('a retired render callback cannot bind the new instance; the latest opening still accepts its own event', () => {
  const h = harness({ deferSetData: true }); show(h); const old = h.delayed.shift();
  h.page.openAiHistory(); show(h, [item(101)]); const before = JSON.stringify(h.page.data); old();
  assert.equal(JSON.stringify(h.page.data), before);
  while (h.delayed.length) h.delayed.shift()();
  h.page.onAiHistorySelect(h.selection(0)); assert.deepEqual(h.take('/request').data, { requestId: item(101).requestId });
});
test('missing component binding and storage failure before binding both fail closed without a POST or identity write', () => {
  const h = harness({ missingOptionSheet: true }); show(h);
  assert.equal(h.page.data.aiHistoryShow, false); assert.match(h.page.data.aiError, /历史选择暂不可用/); assert.equal(h.values[journal.key('7')], undefined);
  const b = harness({ deferSetData: true }); show(b); b.delayed.shift()(); const instance = b.page.selectComponent('#ai-history-sheet');
  b.flags.read = true; const writes = b.writes.length; b.delayed.shift()();
  assert.equal(instance._boundEventContext, undefined); assert.equal(b.page.data.aiHistoryShow, false); assert.equal(b.writes.length, writes);
  assert.equal(b.sent.filter(r => /prepare|execute|request$/.test(r.url)).length, 0);
});
