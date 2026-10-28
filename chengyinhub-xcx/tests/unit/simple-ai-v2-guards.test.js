'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const journal = require('../../utils/ai-theme-draft-journal.js');
const { createSessionStore } = require('../../utils/session/session-store.js');
const PAGE = path.resolve(__dirname, '../../pages/publish/simple/index.js');
const PK = '12345678-1234-4234-8234-123456789abc';
const RID = 'td2_0123456789abcdef0123456789abcdef';
const OTHER = 'td2_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const future = () => new Date(Date.now() + 3600000).toISOString();
function view(state = 'PREPARED', extra = {}) {
  return Object.assign({ requestId: RID, state, replayed: true, contextAvailable: state === 'PREPARED',
    resultAvailable: false, result: null, productType: 2, reasonCode: null, executeBefore: future(), resultExpiresAt: state === 'SUCCEEDED' ? future() : null }, extra);
}
const DRAFT = { traceId: 'local-trace', remainingQuota: 1, draft: { title: '原草稿', nodes: [{ merchantName: '原地点' }] } };
function record(extra = {}) {
  return Object.assign({ version: 2, memberId: '7', prepareKey: PK, requestId: null,
    stage: 'PREPARE_UNCONFIRMED', state: null, reasonCode: null, createdAt: 100 }, extra);
}
function item(i, state = 'PREPARED', extra = {}) {
  return Object.assign({ cursor: String(i), prepareKey: i === 100 ? PK : '12345678-1234-4234-8234-' + String(i).padStart(12, '0'),
    requestId: i === 100 ? RID : 'td2_' + String(i).padStart(32, '0'), state, reasonCode: null, productType: 2,
    createdAt: new Date().toISOString(), executeBefore: future(), resultExpiresAt: state === 'SUCCEEDED' ? future() : null }, extra);
}
// Execute the real component; fixtures only model mounting/event delivery.
function makeOptionSheet(properties = {}, onEvent = () => {}) {
  const file = path.resolve(__dirname, '../../components/cy/option-sheet/index.js');
  let definition; global.Component = value => { definition = value; };
  const mod = new Module(file, module); mod.filename = file; mod.paths = module.paths;
  mod._compile(fs.readFileSync(file, 'utf8'), file);
  const defaults = Object.fromEntries(Object.entries(definition.properties).map(([key, prop]) => [key, prop.value]));
  const sheet = Object.assign({}, definition.methods, { data: Object.assign({}, defaults, JSON.parse(JSON.stringify(definition.data)), properties) });
  sheet.setData = patch => Object.assign(sheet.data, patch);
  sheet.triggerEvent = (name, detail) => onEvent(name, detail === undefined ? undefined : JSON.parse(JSON.stringify(detail)));
  sheet.detach = () => { if (definition.lifetimes && definition.lifetimes.detached) definition.lifetimes.detached.call(sheet); };
  definition.observers.items.call(sheet, sheet.data.items); definition.observers.show.call(sheet, sheet.data.show);
  return sheet;
}
function harness(options = {}) {
  const values = Object.assign({}, options.values), sent = [], pois = [], navigations = [], delayed = [], modals = [], writes = [];
  const flags = { read: false, write: false, noop: false, failRid: false, failExecute: false, afterWriteRead: false, archive: false, archiveRead: false, keys: false, active: false };
  let wrote = false;
  const wx = {
    getStorageSync(k) { if ((flags.archiveRead && k.includes(':history:') && values[k]) || flags.read || (flags.afterWriteRead && wrote)) throw Error('local read'); return values[k] === undefined ? '' : JSON.parse(JSON.stringify(values[k])); },
    setStorageSync(k, v) {
      if (k.endsWith(':active') && flags.active) throw Error('active write');
      if (k.includes(':history:') && flags.archive) throw Error('archive write');
      if (k.startsWith('ai_') && flags.write) throw Error('local write');
      if (k.startsWith('ai_') && flags.noop) return;
      if (k.startsWith('ai_theme_draft_v2:')) {
        if ((flags.failRid && v.requestId) || (flags.failExecute && v.stage === 'EXECUTE_UNCONFIRMED')) throw Error('local write');
        if (flags.noop) return;
        wrote = true;
      }
      values[k] = JSON.parse(JSON.stringify(v)); writes.push({ key: k, value: JSON.parse(JSON.stringify(v)) });
      if (options.afterStorageWrite) options.afterStorageWrite(k, v, values);
    },
    getStorageInfoSync() { if (flags.read || flags.keys) throw Error('local keys'); return { keys: Object.keys(values) }; },
    removeStorageSync(k) { delete values[k]; }, choosePoi(r) { pois.push(r); },
    navigateTo(r) { navigations.push(r.url); }, switchTab(r) { navigations.push(r.url); }, navigateBack() {}
  };
  const store = createSessionStore({ wx, now: () => 1000 });
  store.applySnapshot({ user_id: '7', authorization: 'local-token-A', role: 'player' });
  const app = { globalData: {}, getSession: () => store, getUserID: () => store.getUserID(),
    getAuthorization: () => store.getAuthorization(), sendRequest(r) { sent.push(r); } };
  global.wx = wx; global.getApp = () => app;
  function createPage(load = true) {
    let definition; global.Page = d => { definition = d; };
    const mod = new Module(PAGE, module); mod.filename = PAGE; mod.paths = module.paths;
    mod._compile(options.pageSource || fs.readFileSync(PAGE, 'utf8'), PAGE);
    const p = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
    let optionSheet = null;
    p._sheetEvents = [];
    p.setData = (patch, cb) => {
      for (const key of Object.keys(patch)) {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let target = p.data;
        for (const part of parts.slice(0, -1)) target = target[part];
        target[parts[parts.length - 1]] = patch[key];
      }
      if (p.data.aiHistoryShow && !optionSheet) {
        optionSheet = makeOptionSheet({ show: true, items: p.data.aiHistoryItems }, (name, detail) => p._sheetEvents.push({ name, detail }));
      } else if (!p.data.aiHistoryShow && optionSheet) { optionSheet.detach(); optionSheet = null; }
      if (cb) options.deferSetData ? delayed.push(cb) : cb();
    };
    p.selectComponent = id => id === '#cy-modal-host' ? { open: opts => modals.push(opts) }
      : id === '#ai-history-sheet' && !options.missingOptionSheet ? optionSheet : null;
    global.getCurrentPages = () => [p];
    if (load) p.onLoad(Object.assign({ mode: '2' }, options.entry)); return p;
  }
  Object.assign(flags, options.flags || {});
  const page = createPage();
  const take = suffix => { const i = sent.findIndex(r => r.url.endsWith(suffix)); assert.ok(i >= 0, 'missing request ' + suffix); return sent.splice(i, 1)[0]; };
  function finish(req, data, callback = 'success') { req[callback](data); if (req.complete) req.complete(); }
  const ready = () => { finish(take('/quota'), { code: 200, data: { limited: true, remaining: 3 } }); finish(take('/recovery'), { code: 200, data: { items: [], nextCursor: null } }); page.setData({ aiIdea: '原灵感' }); return page; };
  const begin = () => { page.generateAiPlan(); return take('/prepare'); };
  const prepare = () => { const req = begin(); finish(req, { code: 200, data: view('PREPARED', { replayed: false, productType: page.data.mode }) }); return take('/execute'); };
  const switchOwner = (id, token) => store.applySnapshot({ user_id: id, authorization: token, role: 'player' });
  const selection = (index, target = page) => {
    const sheet = target.selectComponent('#ai-history-sheet'); assert.ok(sheet, 'real option sheet mounted');
    sheet.onRow({ currentTarget: { dataset: { index } } }); sheet.onConfirm();
    const event = target._sheetEvents.shift(); assert.equal(event.name, 'select');
    return { detail: event.detail, currentTarget: { dataset: {} } };
  };
  return { page, app, wx, store, sent, values, flags, pois, navigations, delayed, modals, writes, createPage, take, finish, ready, begin, prepare, switchOwner, selection };
}
module.exports = { makeOptionSheet, harness, journal, view, record, item, RID, OTHER, PK, DRAFT, future };

if (require.main === module) {
  test('H1/H2 journal write throw or no-op causes zero prepare/execute HTTP writes', () => {
    for (const flag of ['write', 'noop']) {
      const h = harness(); h.ready(); h.flags[flag] = true; h.page.generateAiPlan();
      assert.equal(h.sent.length, 0); assert.match(h.page.data.aiError, /无法可靠/);
    }
  });
  test('H10 unreadable or corrupt journal never becomes absence/admission', () => {
    for (const value of [{}, false, record({ idea: 'private' })]) {
      const h = harness({ values: { [journal.key('7')]: value } }); h.page.setData({ aiIdea: 'idea' }); h.page.generateAiPlan();
      assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    }
    const h = harness(); h.ready(); h.flags.read = true; h.page._restoreAiPending(); h.page.generateAiPlan();
    assert.equal(h.sent.length, 0);
  });
  test('H11 persistent journal has exactly eight fields, no idea/token/result; unknown has no TTL', () => {
    const h = harness(); h.ready(); const req = h.begin(); req.fail({}); req.complete();
    const saved = h.values[journal.key('7')]; assert.equal(Object.keys(saved).length, 8);
    assert.equal(saved.state, null); assert.equal(saved.stage, 'PREPARE_UNCONFIRMED');
    for (const field of ['idea', 'token', 'result', 'payload']) assert.equal(saved[field], undefined);
    assert.equal(h.values['ai_topic_draft_request:m7'], undefined);
  });
  test('server id persistence must precede execute; execute stage persistence must precede POST', () => {
    for (const flag of ['failRid', 'failExecute']) {
      const h = harness(); h.ready(); const req = h.begin(); h.flags[flag] = true;
      h.finish(req, { code: 200, data: view() });
      assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
      assert.match(h.page.data.aiError, /无法可靠/);
    }
  });
  test('same-page unconfirmed continuation uses frozen original key/idea/mode, not edited input', () => {
    const h = harness(); h.ready(); const first = h.begin(); const body = JSON.parse(first.data);
    first.fail({}); first.complete(); h.page.setData({ aiIdea: 'edited', mode: 1 });
    h.page.continueAiDraft(); const second = h.take('/prepare'); assert.deepEqual(JSON.parse(second.data), body);
  });
  test('cold prepareKey has no raw input and only GET; unconfirmed does not manufacture new id', () => {
    const original = record(); const h = harness({ values: { [journal.key('7')]: original } });
    const read = h.take('/request'); assert.deepEqual(read.data, { prepareKey: PK });
    h.finish(read, { code: 200, data: view('UNKNOWN', { requestId: null, contextAvailable: false, reasonCode: 'AI_DRAFT_PREPARE_UNCONFIRMED' }) });
    h.page.setData({ aiIdea: 'invented-new-idea' }); h.page.continueAiDraft();
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0); assert.equal(h.values[journal.key('7')].requestId, null);
  });
  test('H3 late A success/fail/complete never writes B journal or B page data', () => {
    const h = harness(); h.ready(); const old = h.prepare();
    const b = record({ memberId: '8' }); h.values[journal.key('8')] = b;
    h.switchOwner('8', 'local-token-B'); const before = JSON.stringify(h.page.data);
    old.success({ code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) }); old.fail({}); old.complete();
    assert.equal(JSON.stringify(h.page.data), before); assert.deepEqual(h.values[journal.key('8')], b);
  });
  test('H4 same-token same-time A-B-A rejected by actual monotonic session store revision', () => {
    const h = harness(); h.ready(); const old = h.prepare(); const before = JSON.stringify(h.page.data);
    h.switchOwner('8', 'local-token-B'); h.switchOwner('7', 'local-token-A');
    old.success({ code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) }); old.complete();
    assert.equal(JSON.stringify(h.page.data), before); assert.equal(h.page.data.aiPlan, null);
  });
  test('H5 hide/unload invalidates success/fail/complete and quota/POI callbacks', () => {
    for (const event of ['onHide', 'onUnload']) {
      const h = harness(); h.ready(); const old = h.prepare(); h.page[event](); const before = JSON.stringify(h.page.data);
      old.success({ code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) }); old.fail({}); old.complete();
      assert.equal(JSON.stringify(h.page.data), before);
    }
  });
  test('H6 malformed NOT_FOUND/404/500 and mismatched rid never clear identity or claim terminal', () => {
    for (const response of [{ code: 404 }, { code: 500 }, { code: 200, data: view('NOT_FOUND') }, { code: 200, data: view('FAILED', { requestId: OTHER }) }]) {
      const h = harness(); h.ready(); const old = h.prepare(); const saved = JSON.stringify(h.values[journal.key('7')]);
      h.finish(old, response); assert.equal(JSON.stringify(h.values[journal.key('7')]), saved); assert.equal(h.page.data.aiDraftPending, true);
    }
  });
  test('H7 UNKNOWN plus edited idea cannot allocate another key or execute', () => {
    const h = harness(); h.ready(); const old = h.prepare(); h.finish(old, { code: 409, data: view('UNKNOWN') });
    const saved = h.values[journal.key('7')]; h.page.setData({ aiIdea: 'different' }); h.page.generateAiPlan();
    const req = h.take('/request'); assert.deepEqual(req.data, { requestId: RID }); assert.deepEqual(h.values[journal.key('7')], saved);
  });
  test('H8 actual request-client may deliver ABA response but Page rejects it without changing transport', () => {
    const h = harness(); h.ready(); const wire = [];
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    const client = createRequestClient({ getUserID: h.app.getUserID, getAuthorization: h.app.getAuthorization,
      getBaseUrl: () => '', wxRequest: req => { wire.push(req); return { abort() {} }; } });
    h.app.sendRequest = req => client.send(req); h.page.generateAiPlan();
    h.switchOwner('8', 'local-token-B'); h.switchOwner('7', 'local-token-A');
    wire[0].success({ statusCode: 200, data: { code: 200, data: view() } }); wire[0].complete({});
    assert.equal(wire.length, 1); assert.equal(h.values[journal.key('7')].requestId, null);
  });
  test('H9 late prepare.complete never clears execute loading or journal', () => {
    const h = harness(); h.ready(); const first = h.begin(); first.success({ code: 200, data: view() });
    const execute = h.take('/execute'); assert.equal(h.page.data.aiGenerating, true);
    first.complete(); assert.equal(h.page.data.aiGenerating, true);
    execute.fail({}); execute.complete(); assert.equal(h.page.data.aiGenerating, false);
  });
  test('H12 SUCCEEDED unavailable/expired body retains success and original identity/old plan', () => {
    for (const reasonCode of ['AI_DRAFT_RESULT_UNAVAILABLE', 'AI_DRAFT_RESULT_EXPIRED']) {
      const h = harness(); h.ready(); h.page.setData({ aiPlan: { title: 'keep' }, aiPlanNodes: [{ confirmed: true }] });
      const req = h.prepare(); h.finish(req, { code: 503, data: view('SUCCEEDED', { reasonCode }) }, 'successStatusAbnormal');
      assert.equal(h.values[journal.key('7')].state, 'SUCCEEDED'); assert.equal(h.values[journal.key('7')].requestId, RID);
      assert.equal(h.page.data.aiPlan.title, 'keep'); assert.equal(h.page.data.aiPlanNodes[0].confirmed, true);
    }
  });
  test('owner recovery validates all states and pages decimal Long strings without rounding', () => {
    const h = harness(); const first = h.take('/recovery');
    const rows = Array.from({ length: 20 }, (_, i) => item(100 - i, ['SUCCEEDED', 'FAILED', 'UNKNOWN', 'PROCESSING', 'PREPARED'][i % 5], { cursor: String(9223372036854775807n - BigInt(i)) }));
    h.finish(first, { code: 200, data: { items: rows, nextCursor: rows[19].cursor } });
    assert.equal(h.page.data.aiHistoryItems.length, 21); assert.match(h.page.data.aiHistoryItems[0], /已成功/);
    h.page.onAiHistorySelect(h.selection(20)); const next = h.take('/recovery');
    assert.deepEqual(next.data, { beforeId: '9223372036854775788' });
    h.finish(next, { code: 200, data: { items: [item(1, 'FAILED', { cursor: '9223372036854775787' })], nextCursor: null } });
    assert.equal(h.page.data.aiHistoryItems[1], '返回最新记录');
  });
  test('ABSENT journal cannot admit new action before valid owner recovery; read failure is not empty', () => {
    const h = harness(); h.page.setData({ aiIdea: 'idea' }); h.page.generateAiPlan();
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    h.finish(h.take('/recovery'), { code: 503 }); h.page.generateAiPlan();
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });
  test('invalid owner/shape/order/unsafe-number cursor/paging refuses new admission', () => {
    const bad = [ { items: [item(1, 'PREPARED', { memberId: '8' })], nextCursor: null },
      { items: [item(1), item(2)], nextCursor: null }, { items: [item(1, 'PREPARED', { cursor: 9007199254740992 })], nextCursor: null },
      { items: [item(1)], nextCursor: '1' }, { items: [], nextCursor: '1' } ];
    for (const data of bad) {
      const h = harness(); h.finish(h.take('/recovery'), { code: 200, data }); h.page.setData({ aiIdea: 'idea' }); h.page.generateAiPlan();
      assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0); assert.equal(h.page.data.aiHistoryShow, false);
    }
  });
  test('cold success is visible in metadata; selection persists exact identity before GET, no model', () => {
    const h = harness(); h.finish(h.take('/recovery'), { code: 200, data: { items: [item(100, 'SUCCEEDED')], nextCursor: null } });
    assert.match(h.page.data.aiHistoryItems[0], /已成功/); h.page.onAiHistorySelect(h.selection(0));
    const req = h.take('/request'); assert.deepEqual(req.data, { requestId: RID });
    assert.equal(h.values[journal.key('7')].requestId, RID); assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
  });
  test('recovery.complete cannot clear loading of selected original GET', () => {
    const h = harness(); const req = h.take('/recovery'); req.success({ code: 200, data: { items: [item(100)], nextCursor: null } });
    h.page.onAiHistorySelect(h.selection(0)); h.take('/request'); req.complete(); assert.equal(h.page.data.aiGenerating, true);
  });
  test('sheet stale event and same-owner new revision never select/read/replace a new identity', () => {
    const h = harness(); h.finish(h.take('/recovery'), { code: 200, data: { items: [item(100)], nextCursor: null } });
    const event = h.selection(0); h.store.setAuthorization('local-token-A'); h.page.onAiHistorySelect(event);
    assert.equal(h.values[journal.key('7')], undefined); assert.equal(h.sent.filter(r => r.url.endsWith('/request')).length, 0);
  });
  test('legacy UUID migrates identity with readback before precise removal, never copies raw idea/TTL deletes business draft', () => {
    const business = { name: 'user-business-draft' };
    const h = harness({ values: { 'ai_topic_draft_request:m7': { requestId: PK, idea: 'private-old-idea' }, 'ai_topic_draft:m7': business } });
    assert.equal(h.values['ai_topic_draft_request:m7'], undefined); const saved = h.values[journal.key('7')];
    assert.equal(saved.requestId, PK); assert.equal(saved.prepareKey, null); assert.equal(saved.idea, undefined);
    assert.deepEqual(h.take('/request').data, { requestId: PK }); assert.deepEqual(h.values['ai_topic_draft:m7'], business);
  });
  test('hide/show and token refresh only resume original read; expired PREPARED cannot POST', () => {
    const h = harness(); h.ready(); const req = h.prepare(); req.fail({}); req.complete();
    h.page.onHide(); h.store.setAuthorization('local-token-A'); h.page.onShow();
    const read = h.take('/request'); assert.deepEqual(read.data, { requestId: RID });
    h.finish(read, { code: 200, data: view('PREPARED', { executeBefore: '2000-01-01T00:00:00Z' }) });
    h.page.continueAiDraft(); assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
  });
  test('late quota and POI callbacks cannot affect new session/plan', () => {
    const h = harness(); const quota = h.take('/quota');
    h.switchOwner('8', 'local-token-B'); quota.success({ code: 200, data: { limited: true, remaining: 99 } });
    assert.equal(h.page.data.aiRemaining, 0);
    const b = harness(); b.ready(); const req = b.prepare(); b.finish(req, { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    b.page.confirmAiNodePoi({ currentTarget: { dataset: { index: 0 } } }); const old = b.pois[0]; b.page.onHide();
    old.success({ name: 'late', longitude: 1, latitude: 1 }); assert.equal(b.page.data.aiPlanNodes[0].confirmed, false);
  });
  test('same result readback preserves edited plan and confirmed POI; replayed old quota is not used', () => {
    const h = harness(); h.ready(); const req = h.prepare(); h.finish(req, { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    h.page.setData({ 'aiPlan.title': 'edited', 'aiPlanNodes[0].confirmed': true, 'aiPlanNodes[0].longitude': '1', 'aiPlanNodes[0].latitude': '2' });
    h.page.checkAiDraftRequest(); h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    assert.equal(h.page.data.aiPlan.title, 'edited'); assert.equal(h.page.data.aiPlanNodes[0].confirmed, true); assert.equal(h.page.data.aiRemaining, 3);
  });
  test('partial write/readback loss cannot send; restored storage recovers original key/snapshot read-only first', () => {
    const h = harness(); h.ready(); h.flags.afterWriteRead = true; h.page.generateAiPlan();
    assert.equal(h.sent.length, 0); const saved = h.values[journal.key('7')];
    h.flags.afterWriteRead = false; h.page.setData({ aiIdea: 'edited-after-loss' }); h.page.generateAiPlan();
    assert.deepEqual(h.take('/request').data, { prepareKey: saved.prepareKey });
    assert.equal(h.sent.filter(r => r.url.endsWith('/prepare')).length, 0);
  });
  test('warm journal loss restores original memory identity, never treats absence as permission for a new key', () => {
    const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
    const original = h.values[journal.key('7')]; delete h.values[journal.key('7')];
    h.page.onHide(); h.page.onShow(); assert.deepEqual(h.values[journal.key('7')], original);
    assert.deepEqual(h.take('/request').data, { requestId: RID });
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });
  test('legacy migration failures retain exact old identity and never execute or copy raw idea', () => {
    for (const flags of [{ write: true }, { noop: true }, { read: true }]) {
      const old = { requestId: PK, idea: 'old-private-input' };
      const h = harness({ values: { 'ai_topic_draft_request:m7': old }, flags });
      assert.deepEqual(h.values['ai_topic_draft_request:m7'], old);
      assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
      assert.equal(h.values[journal.key('7')], undefined);
    }
  });
  test('double click cannot start two prepares and all wire calls omit explicit Authorization', () => {
    const h = harness(); h.ready(); h.page.generateAiPlan(); h.page.generateAiPlan();
    assert.equal(h.sent.filter(r => r.url.endsWith('/prepare')).length, 1);
    for (const req of h.sent) assert.equal(req.header.Authorization, undefined);
  });
  test('a late quota started before execute cannot overwrite the newer quota', () => {
    const h = harness(); const quota = h.take('/quota'); h.finish(h.take('/recovery'), { code: 200, data: { items: [], nextCursor: null } });
    h.page.setData({ aiIdea: 'idea' }); const execute = h.prepare();
    quota.success({ code: 200, data: { limited: true, remaining: 99 } }); assert.equal(h.page.data.aiQuotaLoaded, false);
    h.finish(execute, { code: 200, data: view('SUCCEEDED', { replayed: false, resultAvailable: true, result: DRAFT }) });
    quota.success({ code: 200, data: { limited: true, remaining: 99 } }); assert.equal(h.page.data.aiRemaining, 1);
  });
  test('malformed result body keeps prior user plan and confirmed POI and never adopts its quota', () => {
    for (const draft of [{ title: 'bad', nodes: [] }, { title: '', nodes: [{ task: 'x' }] }, { title: 'bad', nodes: [null] }]) {
      const h = harness(); h.ready(); h.page.setData({ aiPlan: { title: 'keep' }, aiPlanNodes: [{ confirmed: true, longitude: '1', latitude: '2' }] });
      h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { replayed: false, resultAvailable: true, result: { draft, remainingQuota: 0 } }) });
      assert.equal(h.page.data.aiPlan.title, 'keep'); assert.equal(h.page.data.aiPlanNodes[0].confirmed, true); assert.equal(h.page.data.aiRemaining, 3);
    }
  });
  test('a role change or same-token refresh invalidates callbacks without waiting for onShow', () => {
    for (const change of [h => h.store.setUserRole('merchant'), h => h.store.setAuthorization('local-token-A')]) {
      const h = harness(); h.ready(); const req = h.prepare(); const before = JSON.stringify(h.page.data); change(h);
      h.finish(req, { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) }); assert.equal(JSON.stringify(h.page.data), before);
      h.page.generateAiPlan(); assert.ok(h.take('/request')); assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    }
  });
  test('late setData dependent callbacks cannot run under a newer session', () => {
    const h = harness({ deferSetData: true }); h.ready(); h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    h.store.setAuthorization('local-token-A'); const before = JSON.stringify(h.page.data);
    h.delayed.forEach(cb => cb()); assert.equal(JSON.stringify(h.page.data), before);
  });
  test('choosing a stale POI from another plan cannot mutate the newly applied plan', () => {
    const h = harness(); h.ready(); h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    h.page.confirmAiNodePoi({ currentTarget: { dataset: { index: 0 } } }); const poi = h.pois[0];
    h.page.generateAiPlan(); const prep = h.take('/prepare'); const secondId = OTHER;
    h.finish(prep, { code: 200, data: view('PREPARED', { requestId: secondId }) });
    h.finish(h.take('/execute'), { code: 200, data: view('SUCCEEDED', { requestId: secondId, resultAvailable: true, result: { ...DRAFT, draft: { title: 'new', nodes: [{ task: 'new-node' }] } } }) });
    poi.success({ longitude: 1, latitude: 2, name: 'stale' }); assert.equal(h.page.data.aiPlanNodes[0].confirmed, false); assert.equal(h.page.data.aiPlanNodes[0].name, 'new-node');
  });

  test('legacy NOT_FOUND/404 remains original legacy read-only identity; no v2 execute/upgrade', () => {
    const h = harness({ values: { 'ai_topic_draft_request:m7': { requestId: PK, idea: 'old' } } });
    h.finish(h.take('/request'), { code: 404 }); h.page.setData({ aiIdea: 'different' }); h.page.generateAiPlan();
    const read = h.take('/request'); assert.deepEqual(read.data, { requestId: PK });
    h.finish(read, { code: 200, data: view('UNKNOWN', { requestId: PK, executeBefore: null, reasonCode: 'LEGACY_UNBOUND' }) });
    assert.equal(h.values[journal.key('7')].requestId, PK); assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });
  test('non200 original PREPARED view is recorded but is not automatic execute permission', () => {
    for (const code of [403, 409, 503]) {
      const h = harness(); h.ready(); h.finish(h.begin(), { code, data: view() }, 'successStatusAbnormal');
      assert.equal(h.values[journal.key('7')].requestId, RID); assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
    }
  });
  test('cold success selected from metadata restores the body without executing or stale quota', () => {
    const h = harness(); h.finish(h.take('/recovery'), { code: 200, data: { items: [item(100, 'SUCCEEDED')], nextCursor: null } });
    h.page.onAiHistorySelect(h.selection(0)); h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    assert.equal(h.page.data.aiPlan.title, '原草稿'); assert.equal(h.values[journal.key('7')].state, 'SUCCEEDED');
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0); assert.equal(h.page.data.aiRemaining, 0);
  });
  test('same-page original mode is retained through map confirmation and only fabu editor navigation', () => {
    const h = harness(); h.ready(); const req = h.prepare(); h.page.setData({ mode: 1 });
    h.finish(req, { code: 200, data: view('SUCCEEDED', { replayed: false, resultAvailable: true, result: DRAFT }) });
    h.page.confirmAiNodePoi({ currentTarget: { dataset: { index: 0 } } }); h.pois[0].success({ name: 'confirmed', longitude: 1, latitude: 2 });
    h.page.enterAiEditor(); assert.match(h.navigations[0], /^\/pages\/publish\/fabu\/index\?mode=2/);
    assert.equal(h.values['ai_topic_draft:m7'].productType, 2);
    assert.equal(h.sent.filter(r => /topic.*create|publish/.test(r.url)).length, 0);
  });
  test('unknown cold result mode is visible/read-only rather than guessed from a different entry mode', () => {
    const h = harness({ values: { [journal.key('7')]: record({ requestId: RID, state: 'SUCCEEDED', stage: 'KNOWN' }) } });
    h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT, productType: null }) });
    h.page.confirmAiNodePoi({ currentTarget: { dataset: { index: 0 } } }); assert.equal(h.pois.length, 0);
    assert.equal(h.page.data.aiPlanModeKnown, false); assert.match(h.page.data.aiError, /模式未能确认/);
    h.page.setData({ 'aiPlanNodes[0].confirmed': true, 'aiPlanNodes[0].longitude': '1', 'aiPlanNodes[0].latitude': '2' });
    h.page.enterAiEditor(); assert.equal(h.navigations.length, 0); assert.match(h.page.data.aiError, /模式未能确认/);
  });
  test('terminal history selection archives the old fact and selects an existing RID without execution', () => {
    const h = harness(); h.ready(); h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    const before = h.values[journal.key('7')]; h.page.openAiHistory();
    h.finish(h.take('/recovery'), { code: 200, data: { items: [item(101, 'FAILED')], nextCursor: null } });
    h.page.onAiHistorySelect(h.selection(0)); assert.equal(h.values[journal.key('7')].requestId, item(101).requestId);
    assert.deepEqual(h.take('/request').data, { requestId: item(101).requestId });
    assert.deepEqual(journal.history(h.wx, '7').values, [before]);
    assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
  });
  test('two pages share original journal identity, so the second page does not create another prepare', () => {
    const h = harness(); h.ready(); h.page.generateAiPlan(); const first = h.take('/prepare');
    const p2 = h.createPage(); p2.setData({ aiIdea: 'different-page' }); p2.generateAiPlan();
    assert.equal(h.sent.filter(r => r.url.endsWith('/prepare')).length, 0);
    assert.deepEqual(h.take('/request').data, { prepareKey: JSON.parse(first.data).prepareKey });
  });
  test('actual transport refresh injects new auth into the same prepare, Page waits for readonly recovery', () => {
    const h = harness(); h.ready(); const wire = [];
    const { createRequestClient } = require('../../utils/transport/request-client.js');
    const client = createRequestClient({ getUserID: h.app.getUserID, getAuthorization: h.app.getAuthorization,
      getBaseUrl: () => '', wxRequest: req => { wire.push(req); return { abort() {} }; },
      reLogin: done => {
        h.store.setAuthorization('local-token-refreshed');
        const revision = h.store.getRevision();
        done(true, { isCurrentSession: () => h.store.getRevision() === revision
          && h.app.getUserID() === '7' && h.app.getAuthorization() === 'local-token-refreshed' });
      } });
    h.app.sendRequest = req => client.send(req); h.page.generateAiPlan();
    wire[0].success({ statusCode: 401, data: { code: 401 } });
    assert.equal(wire.length, 2); assert.equal(wire[1].header.Authorization, 'local-token-refreshed'); assert.equal(wire[1].data, wire[0].data);
    const original = JSON.stringify(h.values[journal.key('7')]);
    wire[1].success({ statusCode: 200, data: { code: 200, data: view() } }); wire[1].complete({});
    assert.equal(h.values[journal.key('7')].requestId, null); assert.equal(wire.length, 2);
    assert.equal(JSON.stringify(h.values[journal.key('7')]), original);
    assert.equal(h.page.data.aiGenerating, false);
    h.page.onShow(); const recovered = wire.find(r => r.method === 'GET' && r.url.endsWith('/request'));
    assert.ok(recovered); assert.deepEqual(recovered.data, { prepareKey: JSON.parse(wire[0].data).prepareKey });
    recovered.success({ statusCode: 200, data: { code: 200, data: view() } }); recovered.complete({});
    assert.equal(h.values[journal.key('7')].requestId, RID);
    assert.equal(wire.filter(r => r.url.endsWith('/execute')).length, 0, '原GET不发模型命令');
    const restored = JSON.stringify(h.values[journal.key('7')]);
    wire[1].success({ statusCode: 200, data: { code: 200, data: view() } }); wire[1].complete({});
    assert.equal(JSON.stringify(h.values[journal.key('7')]), restored);
    assert.equal(wire.filter(r => r.url.endsWith('/execute')).length, 0);
    h.page.continueAiDraft();
    const execute = wire.filter(r => r.url.endsWith('/execute'));
    assert.equal(execute.length, 1); assert.deepEqual(JSON.parse(execute[0].data), { requestId: RID });
    execute[0].success({ statusCode: 401, data: { code: 401 } }); execute[0].complete({});
    const retried = wire.filter(r => r.url.endsWith('/execute'));
    assert.equal(retried.length, 2); assert.equal(retried[1].data, retried[0].data);
    const executing = JSON.stringify(h.values[journal.key('7')]);
    retried[1].success({ statusCode: 200, data: { code: 200,
      data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) } }); retried[1].complete({});
    assert.equal(JSON.stringify(h.values[journal.key('7')]), executing, 'execute续登回包也必须先只读恢复');
    assert.equal(h.page.data.aiPlan, null); assert.equal(h.page.data.aiGenerating, false);
    h.page.onShow(); const terminal = wire.filter(r => r.method === 'GET' && r.url.endsWith('/request')).at(-1);
    assert.deepEqual(terminal.data, { requestId: RID });
    terminal.success({ statusCode: 200, data: { code: 200,
      data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) } }); terminal.complete({});
    assert.equal(h.page.data.aiPlan.title, DRAFT.draft.title);
    assert.equal(h.values[journal.key('7')].state, 'SUCCEEDED');
    assert.equal(wire.filter(r => r.url.endsWith('/execute')).length, 2, '恢复只读，不再发execute');
  });

  test('UNKNOWN confirmation uses the real modal; cancel writes nothing and sends nothing', () => {
    const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
    const before = JSON.stringify(h.values), count = h.writes.length;
    h.page.requestConfirmedNewAiAction(); assert.equal(h.modals.length, 1);
    assert.match(h.modals[0].content, /原记录会保留/); assert.match(h.modals[0].content, /额外占用/);
    h.modals[0].success({ cancel: true }); h.modals[0].complete();
    assert.equal(JSON.stringify(h.values), before); assert.equal(h.writes.length, count); assert.equal(h.sent.length, 0);
  });
  test('confirmed UNKNOWN archives original eight fields before a new prepare; modal complete cannot clear its loading', () => {
    const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
    const original = h.values[journal.key('7')]; h.page.setData({ aiIdea: 'new explicit idea', mode: 1 });
    h.page.requestConfirmedNewAiAction(); h.page.setData({ aiIdea: 'edited after prompt', mode: 2 });
    const confirm = h.modals[0]; confirm.success({ confirm: true }); const next = h.take('/prepare');
    const body = JSON.parse(next.data); assert.equal(body.idea, 'new explicit idea'); assert.equal(body.productType, 1);
    assert.notEqual(body.prepareKey, original.prepareKey); assert.deepEqual(journal.history(h.wx, '7').values, [original]);
    const archiveIndex = h.writes.findIndex(w => w.key.includes(':history:'));
    const newIndex = h.writes.findIndex(w => w.key === journal.key('7') && w.value.prepareKey === body.prepareKey);
    assert.ok(archiveIndex >= 0 && newIndex > archiveIndex);
    confirm.complete(); confirm.success({ confirm: true }); assert.equal(h.page.data.aiGenerating, true); assert.equal(h.sent.length, 0);
    h.finish(next, { code: 200, data: view('PREPARED', { requestId: OTHER, productType: 1 }) });
    assert.deepEqual(JSON.parse(h.take('/execute').data), { requestId: OTHER });
  });
  test('archive write/readback/active CAS failure all prohibit a new prepare', () => {
    for (const flag of ['archive', 'archiveRead', 'noop', 'afterWriteRead']) {
      const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
      h.page.requestConfirmedNewAiAction(); h.flags[flag] = true; h.modals[0].success({ confirm: true });
      assert.equal(h.sent.length, 0); assert.equal(h.values[journal.key('7')].requestId, RID);
    }
    const h = harness({ afterStorageWrite(k, v, values) {
      if (k.includes(':history:')) values[journal.key('7')] = record({ requestId: OTHER, state: 'UNKNOWN', stage: 'KNOWN' });
    } }); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
    h.page.requestConfirmedNewAiAction(); h.modals[0].success({ confirm: true });
    assert.equal(h.sent.length, 0); assert.equal(h.values[journal.key('7')].requestId, OTHER);
  });
  test('modal freezes actual session revision, page epoch, original pending and hop; stale confirmation has zero writes/posts', () => {
    const changes = [h => h.switchOwner('8', 'B'), h => { h.switchOwner('8', 'B'); h.switchOwner('7', 'local-token-A'); },
      h => h.store.setAuthorization('local-token-A'), h => h.page.onHide(), h => h.page.onUnload(),
      h => { h.values[journal.key('7')] = record({ requestId: OTHER, state: 'UNKNOWN', stage: 'KNOWN' }); },
      h => { h.page._hops.modal++; }];
    for (const change of changes) {
      const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
      h.page.requestConfirmedNewAiAction(); change(h); const data = JSON.stringify(h.page.data), values = JSON.stringify(h.values), count = h.writes.length;
      h.modals[0].success({ confirm: true }); h.modals[0].complete();
      assert.equal(h.writes.length, count); assert.equal(JSON.stringify(h.values), values); assert.equal(JSON.stringify(h.page.data), data); assert.equal(h.sent.length, 0);
    }
  });
  test('unknown prepare with no RID is archived by original key; cold local archive is only queried by that key', () => {
    const h = harness(); h.ready(); const old = h.begin(); old.fail({}); old.complete();
    const original = h.values[journal.key('7')]; h.page.requestConfirmedNewAiAction(); h.modals[0].success({ confirm: true });
    const next = h.take('/prepare'); next.fail({}); next.complete();
    assert.deepEqual(journal.history(h.wx, '7').values, [original]);
    const cold = h.createPage(); h.finish(h.take('/request'), { code: 500 }); cold.openLocalAiHistory();
    cold.onAiHistorySelect(h.selection(0, cold));
    assert.deepEqual(h.take('/request').data, { prepareKey: original.prepareKey });
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });
  test('PREPARED and PROCESSING never expose UNKNOWN-new confirmation or post a different identity', () => {
    for (const state of ['PREPARED', 'PROCESSING']) {
      const h = harness(); h.ready(); h.finish(h.begin(), { code: 409, data: view(state) }, 'successStatusAbnormal');
      h.page.setData({ aiIdea: 'edited' }); h.page.requestConfirmedNewAiAction(); assert.equal(h.modals.length, 0);
      h.page.generateAiPlan(); assert.deepEqual(h.take('/request').data, { requestId: RID });
      assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    }
  });
  test('unresolved active identity permits another history body only as a page-local read, without execute permission', () => {
    for (const selectedState of ['SUCCEEDED', 'PREPARED']) {
      const h = harness(); h.ready(); h.finish(h.prepare(), { code: 409, data: view('UNKNOWN') });
      const active = JSON.stringify(h.values[journal.key('7')]); h.page.openAiHistory();
      const selected = item(101, selectedState); h.finish(h.take('/recovery'), { code: 200, data: { items: [selected], nextCursor: null } });
      h.page.onAiHistorySelect(h.selection(0)); h.finish(h.take('/request'), { code: 200, data: view(selectedState, {
        requestId: selected.requestId, resultAvailable: selectedState === 'SUCCEEDED', result: selectedState === 'SUCCEEDED' ? DRAFT : null }) });
      assert.equal(JSON.stringify(h.values[journal.key('7')]), active); assert.match(h.page.data.aiHistoryPreviewLabel, /只读历史/);
      assert.equal(h.page.data.aiCanContinue, false); h.page.continueAiDraft();
      assert.deepEqual(h.take('/request').data, { requestId: RID }); assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    }
  });
  test('cold original productType overrides the current entry, while a null mode preserves body and blocks editor', () => {
    const h = harness({ entry: { mode: '1' } }); h.finish(h.take('/recovery'), { code: 200, data: { items: [item(100, 'SUCCEEDED')], nextCursor: null } });
    h.page.onAiHistorySelect(h.selection(0)); h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT }) });
    h.page.confirmAiNodePoi({ currentTarget: { dataset: { index: 0 } } }); h.pois[0].success({ name: 'confirmed', longitude: 1, latitude: 2 });
    h.page.enterAiEditor(); assert.equal(h.page.data.aiPlanProductType, 2); assert.equal(h.values['ai_topic_draft:m7'].productType, 2); assert.match(h.navigations[0], /mode=2/);
  });
  test('server/original-snapshot and metadata/view mode contradictions fail closed without changing active or granting new action', () => {
    const h = harness(); h.ready(); const first = h.begin(), old = JSON.stringify(h.values[journal.key('7')]);
    h.finish(first, { code: 200, data: view('PREPARED', { productType: 1 }) });
    assert.equal(JSON.stringify(h.values[journal.key('7')]), old); assert.equal(h.sent.length, 0);
    assert.match(h.page.data.aiError, /模式不一致/); h.page.requestConfirmedNewAiAction(); assert.equal(h.modals.length, 0);
    h.page.onHide(); h.page.onShow(); h.page.requestConfirmedNewAiAction(); assert.equal(h.modals.length, 0);
    const b = harness(); b.finish(b.take('/recovery'), { code: 200, data: { items: [item(100, 'SUCCEEDED', { productType: 1 })], nextCursor: null } });
    b.page.onAiHistorySelect(b.selection(0)); const saved = JSON.stringify(b.values[journal.key('7')]);
    b.finish(b.take('/request'), { code: 200, data: view('SUCCEEDED', { resultAvailable: true, result: DRAFT, productType: 2 }) });
    assert.equal(JSON.stringify(b.values[journal.key('7')]), saved); assert.equal(b.page.data.aiPlan, null);
  });
  test('absent active plus retained UNKNOWN is not new-action permission; history enumeration failure is not empty', () => {
    const prefix = journal.key('7').replace(/active$/, 'history:');
    const h = harness({ values: { [prefix + PK]: record() } });
    h.finish(h.take('/recovery'), { code: 200, data: { items: [], nextCursor: null } }); assert.match(h.page.data.aiError, /未确认/);
    h.page.setData({ aiIdea: 'new' }); h.page.generateAiPlan();
    assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    const b = harness(); b.flags.keys = true; b.finish(b.take('/recovery'), { code: 200, data: { items: [], nextCursor: null } });
    b.page.setData({ aiIdea: 'new' }); b.page.generateAiPlan(); assert.equal(b.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
  });

  test('prepare replay of PROCESSING/UNKNOWN/SUCCEEDED/FAILED never automatically executes', () => {
    for (const state of ['PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED']) {
      const h = harness(); h.ready(); h.finish(h.begin(), { code: 200, data: view(state) });
      assert.equal(h.values[journal.key('7')].state, state); assert.equal(h.sent.filter(r => r.url.endsWith('/execute')).length, 0);
    }
  });
  test('cold original PREPARED only executes after explicit continuation of the same server RID', () => {
    const p = record({ requestId: RID, state: 'PREPARED', stage: 'PREPARED' });
    const h = harness({ values: { [journal.key('7')]: p } });
    h.finish(h.take('/request'), { code: 200, data: view() }); assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    h.page.continueAiDraft(); assert.deepEqual(JSON.parse(h.take('/execute').data), { requestId: RID });
  });
  test('legacy migration removes neither an altered old-owner record nor any business draft', () => {
    const h = harness({ values: { 'ai_topic_draft_request:m7': { requestId: PK, idea: 'old' } },
      afterStorageWrite(k, v, values) {
        if (k === journal.key('7')) values['ai_topic_draft_request:m7'] = { requestId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' };
      } });
    assert.equal(h.values['ai_topic_draft_request:m7'].requestId, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa');
    assert.equal(h.values[journal.key('7')].requestId, PK);
  });
  test('malformed productType and noncanonical decimal cursors never become recovery evidence', () => {
    for (const extra of [{ productType: '2' }, { productType: 3 }, { cursor: '01' }, { cursor: '0' }, { cursor: '-1' },
      { cursor: '9223372036854775808' }, { cursor: '1e3' }]) {
      const h = harness(); h.finish(h.take('/recovery'), { code: 200, data: { items: [item(100, 'SUCCEEDED', extra)], nextCursor: null } });
      assert.equal(h.page.data.aiHistoryShow, false); h.page.setData({ aiIdea: 'x' }); h.page.generateAiPlan();
      assert.equal(h.sent.filter(r => /prepare|execute/.test(r.url)).length, 0);
    }
  });
  test('unknown result mode can be proved by a later same-RID read without overwriting local edits', () => {
    const h = harness({ values: { [journal.key('7')]: record({ requestId: RID, stage: 'KNOWN', state: 'SUCCEEDED' }) } });
    h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { productType: null, resultAvailable: true, result: DRAFT }) });
    h.page.setData({ 'aiPlan.title': 'keep edit' }); h.page.checkAiDraftRequest();
    h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { productType: 1, resultAvailable: true, result: DRAFT }) });
    assert.equal(h.page.data.aiPlan.title, 'keep edit'); assert.equal(h.page.data.aiPlanProductType, 1); assert.equal(h.page.data.aiPlanModeKnown, true);
    h.page.checkAiDraftRequest(); h.finish(h.take('/request'), { code: 200, data: view('SUCCEEDED', { productType: 2, resultAvailable: true, result: DRAFT }) });
    assert.match(h.page.data.aiError, /模式不一致/); assert.equal(h.page.data.canEnterAiEditor, false);
  });
  test('invalid fresh quota never overwrites valid quota, and exhausted display never blocks original read', () => {
    for (const remainingQuota of [-1, '2', NaN]) {
      const h = harness(); h.ready(); h.finish(h.prepare(), { code: 200, data: view('SUCCEEDED', {
        replayed: false, resultAvailable: true, result: { ...DRAFT, remainingQuota } }) }); assert.equal(h.page.data.aiRemaining, 3);
      h.page.setData({ aiRemaining: 0 }); h.page.checkAiDraftRequest(); assert.deepEqual(h.take('/request').data, { requestId: RID });
    }
  });

  test('archive succeeded/active write failed then original prepare resolves to RID; second explicit confirmation safely retains bound original', () => {
    const h = harness(); h.ready(); const first = h.begin(); first.fail({}); first.complete();
    const original = h.values[journal.key('7')]; h.page.requestConfirmedNewAiAction();
    h.flags.active = true; h.modals[0].success({ confirm: true }); assert.equal(h.sent.length, 0);
    assert.deepEqual(journal.history(h.wx, '7').values, [original]); h.flags.active = false;
    h.page.checkAiDraftRequest(); h.finish(h.take('/request'), { code: 200, data: view('UNKNOWN') });
    const bound = h.values[journal.key('7')]; assert.equal(bound.prepareKey, original.prepareKey);
    h.page.requestConfirmedNewAiAction(); h.modals[1].success({ confirm: true });
    assert.deepEqual(journal.history(h.wx, '7').values, [bound]); assert.ok(h.take('/prepare'));
  });

  test('gen exhausted is server-only: dedicated owner remaining >0 admits an explicit new draft, dedicated zero still rejects', () => {
    // Backend precondition: gen is exhausted. Its count is deliberately absent from the Page protocol;
    // this checks the dedicated quota projection, not the truth of a backend gen counter.
    for (const dedicatedRemaining of [2, 0]) {
      const h = harness(); h.finish(h.take('/quota'), { code: 200, data: { limited: true, remaining: dedicatedRemaining } });
      h.finish(h.take('/recovery'), { code: 200, data: { items: [], nextCursor: null } }); h.page.setData({ aiIdea: 'explicit new draft' });
      assert.equal(h.page.data.aiRemaining, dedicatedRemaining); h.page.generateAiPlan();
      if (dedicatedRemaining > 0) {
        const prepare = h.take('/prepare'); assert.equal(JSON.parse(prepare.data).idea, 'explicit new draft');
        h.finish(prepare, { code: 200, data: view() }); assert.deepEqual(JSON.parse(h.take('/execute').data), { requestId: RID });
      } else { assert.match(h.page.data.aiError, /次数用完/); assert.equal(h.values[journal.key('7')], undefined); }
      assert.equal(h.sent.length, 0, 'no general-pool probe or extra request');
    }
  });

}
