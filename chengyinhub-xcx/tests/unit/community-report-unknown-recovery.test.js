'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const intent = require('../../utils/publish/publish-intent');
const tick = () => new Promise(resolve => setImmediate(resolve));
const taxonomy = { policyVersion: 'current-policy', reasons: [{ code: 'FIRST', label: '第一项' }, { code: 'SPAM', label: '垃圾广告' }] };
function harness(store = new Map(), settings = {}) {
  const file = path.resolve(__dirname, '../../utils/community-post-api.js');
  const module = new Module(file); module.filename = file; module.paths = Module._nodeModulePaths(path.dirname(file));
  module._compile(fs.readFileSync(process.env.REPORT_BASELINE_SOURCE || file, 'utf8'), file);
  let owner = '990005', writes=0; const calls = [], results = [], failures = [], dialogs = [];
  const wx = { getStorageSync: key => store.get(key), setStorageSync(key, value) {
    if (settings.storageFails || ++writes===settings.storageFailureOnWrite) throw Error('synthetic storage unavailable');
    store.set(key, JSON.parse(JSON.stringify(value)));
  }, removeStorageSync: key => store.delete(key), showModal: options => dialogs.push(options) };
  const app = { globalData: {}, getUserID: () => owner, tips() {}, sendRequest(options) {
    calls.push(options); return { abort() {} };
  } };
  const client = module.exports.createClient(app, wx);
  const request = (options = {}) => client.request({ url: '/api/comment/report', data: { id: 27 },
    selectReportReason: labels => { assert.deepEqual(labels, ['第一项', '垃圾广告']); return Promise.resolve(1); },
    success: value => results.push(value), fail: value => failures.push(value), ...options });
  const reply = async (index, data, code = 200) => { calls[index].success({ code, data }); await tick(); };
  const wire = () => Array.from(store.values()).find(value => value.wire).wire;
  const row = (body = wire()) => ({ id: 71, targetType: body.targetType, targetId: body.targetId,
    reasonCode: body.reasonCode.trim().toUpperCase(), policyVersion: body.policyVersion.trim(),
    description: body.description.trim(), evidenceAssetIds: body.evidenceAssetIds });
  return { store, calls, results, failures, dialogs, request, reply, wire, row, owner: value => { owner = value; } };
}
async function firstPost(h, options) {
  h.request(options); await tick(); await h.reply(0, { reporting: taxonomy });
  assert.equal(h.calls[1].url, '/api/v1/community/reports');
  return h.calls[1];
}

test('actual report JSON preserves selected reason and caller text and persists complete wire before POST', async () => {
  for (const description of [undefined, '', ' \t ', '  我提供的说明  ']) {
    const h = harness(); const post = await firstPost(h, { data: { id: 27, description, request_id: 'report-original-key-12345' } });
    assert.deepEqual(post.header, { 'content-type': 'application/json' });
    assert.equal(post.data.reasonCode, 'SPAM'); assert.equal(post.data.description, description && description.trim() ? description : '垃圾广告');
    assert.equal(post.data.requestId, 'report-original-key-12345');
    assert.deepEqual(h.wire(), post.data);
    await h.reply(1, h.row()); assert.equal(h.results[0].code, 200); assert.equal(h.store.size, 0);
  }
});
test('report storage failure prevents its first POST', async () => {
  const h = harness(new Map(), { storageFails: true }); h.request(); await tick(); await h.reply(0, { reporting: taxonomy });
  assert.equal(h.calls.length, 1); assert.equal(h.results[0].code, 507);
});
test('unknown report cold recovery reads exact original key and never posts again when owner result matches', async () => {
  const a = harness(); await firstPost(a); const original = a.wire(); a.calls[1].fail({ errMsg: 'synthetic response lost' }); await tick();
  const b = harness(a.store); b.request({ data: { id: 27, description: '后续输入不得覆盖原举报' }, selectReportReason() { assert.fail('must recover original selection'); } }); await tick();
  assert.equal(b.calls[0].url, '/api/community-trust/reports/mine'); assert.equal(b.calls[0].method, 'GET');
  assert.deepEqual(b.calls[0].data, { requestId: original.requestId });
  await b.reply(0, [b.row(original)]);
  assert.equal(b.calls.length, 1); assert.equal(b.results[0].code, 200); assert.equal(b.store.size, 0);
});
test('empty exact lookup requires explicit consent then retries only unchanged original wire and key', async () => {
  const a = harness(); await firstPost(a); const original = a.wire(); a.calls[1].fail({ errMsg: 'lost' }); await tick();
  const b = harness(a.store); b.request(); await tick(); await b.reply(0, []);
  assert.equal(b.calls.length, 1); assert.equal(b.dialogs.length, 1);
  assert.match(b.dialogs[0].content, /不代表原提交未发生/);
  b.dialogs[0].success({ confirm: true }); await tick();
  assert.deepEqual(b.calls[1].data, original); await b.reply(1, b.row(original)); assert.equal(b.store.size, 0);
});
for (const mode of ['read-failure', 'multiple', 'mismatch', 'malformed']) {
  test('unknown report ' + mode + ' cannot POST or discard original intent', async () => {
    const a = harness(); await firstPost(a); a.calls[1].fail({ errMsg: 'lost' }); await tick();
    const b = harness(a.store); b.request(); await tick();
    if (mode === 'read-failure') { b.calls[0].fail({ errMsg: 'lookup failed' }); await tick(); }
    else await b.reply(0, mode === 'multiple' ? [b.row(), b.row()] : mode === 'mismatch' ? [{ ...b.row(), targetId: 99 }] : {});
    assert.equal(b.calls.length, 1); assert.equal(b.store.size, 1); assert.equal(b.dialogs.length, 0);
  });
}
test('cancel recovery preserves original intent and owner change during confirmation prevents replay', async () => {
  for (const changed of [false, true]) {
    const a = harness(); await firstPost(a); a.calls[1].fail({ errMsg: 'lost' }); await tick();
    const b = harness(a.store); b.request(); await tick(); await b.reply(0, []);
    if (changed) b.owner('990015'); b.dialogs[0].success({ confirm: changed }); await tick();
    assert.equal(b.calls.length, 1); assert.equal(b.store.size, 1);
  }
});
test('reason cancellation, overlapping requests and a late previous-owner result do not dispatch another report', async () => {
  const cancelled = harness(); cancelled.request({ selectReportReason: () => Promise.reject({ code: 499, msg: '已取消举报' }) });
  await tick(); await cancelled.reply(0, { reporting: taxonomy }); assert.equal(cancelled.calls.length, 1); assert.equal(cancelled.store.size, 0);
  const h = harness(); await firstPost(h); h.request(); await tick(); assert.equal(h.calls.length, 2);
  assert.equal(h.results[0].code, 409); h.owner('990015'); await h.reply(1, h.row());
  assert.equal(h.results.length, 1); assert.equal(h.store.size, 1);
});

test('report preserves actual caller evidence in original wire and rejects a different recovered evidence set', async () => {
  const a=harness();await firstPost(a,{data:{id:27,description:'实际说明',evidenceAssetIds:[12,13]}});
  assert.deepEqual(a.wire().evidenceAssetIds,[12,13]);a.calls[1].fail({errMsg:'lost'});await tick();
  const b=harness(a.store);b.request();await tick();await b.reply(0,[{...b.row(),evidenceAssetIds:[12]}]);
  assert.equal(b.calls.length,1);assert.equal(b.results[0].code,409);assert.equal(b.store.size,1);
});

test('a late reason after page current=false cannot persist or submit an intent that was never sent', async () => {
  const h=harness();let active=true,select;
  h.request({current:()=>active,selectReportReason:()=>new Promise(resolve=>{select=resolve;})});
  await tick();await h.reply(0,{reporting:taxonomy});active=false;select(1);await tick();
  assert.equal(h.calls.length,1);assert.equal(h.store.size,0);assert.equal(h.results.length,0);
});
test('a sent report stays pending unchanged when its old page is no longer current', async () => {
  const h=harness();let active=true;await firstPost(h,{current:()=>active});const before=JSON.parse(JSON.stringify([...h.store]));
  active=false;h.calls[1].fail({errMsg:'lost'});await tick();
  assert.deepEqual([...h.store],before);assert.equal(h.wire().targetId,27);assert.equal(h.calls.length,2);assert.equal(h.results.length,0);
});
test('invalid caller request key rejects before persisting, and the next valid submission remains usable', async () => {
  const h=harness();h.request({data:{id:27,request_id:'short'}});await tick();await h.reply(0,{reporting:taxonomy});
  assert.equal(h.results[0].code,400);assert.equal(h.calls.length,1);assert.equal(h.store.size,0);
  h.request();await tick();await h.reply(1,{reporting:taxonomy});assert.equal(h.calls[2].url,'/api/v1/community/reports');
  await h.reply(2,h.row());assert.equal(h.results[1].code,200);assert.equal(h.store.size,0);
});

test('caller report key boundaries retain the server trimmed 8-96 character contract, not the internal storage-key regex', async () => {
  for(const key of ['8letters','x'.repeat(96),'  reason:带空格的原key  ']) {
    const h=harness();const post=await firstPost(h,{data:{id:27,request_id:key}});assert.equal(post.data.requestId,key);
    await h.reply(1,h.row());assert.equal(h.results[0].code,200);assert.equal(h.store.size,0);
  }
  for(const key of ['7letter','x'.repeat(97),'        ']) {
    const h=harness();h.request({data:{id:27,request_id:key}});await tick();await h.reply(0,{reporting:taxonomy});
    assert.equal(h.results[0].code,400);assert.equal(h.store.size,0);assert.equal(h.calls.length,1);
  }
});

test('a first explicit policy400 refreshes taxonomy on a user-confirmed correction without losing the target', async () => {
  const h=harness();await firstPost(h);const original=h.wire();await h.reply(1,{},400);
  h.request({data:{id:27,description:'修正说明'}});await tick();assert.equal(h.dialogs.length,1);
  h.dialogs[0].success({confirm:true});await tick();assert.equal(h.calls[2].url,'/api/v1/community/capabilities');
  await h.reply(2,{reporting:{...taxonomy,policyVersion:'refreshed-policy'}});
  assert.equal(h.calls[3].data.policyVersion,'refreshed-policy');assert.equal(h.calls[3].data.description,'修正说明');
  assert.equal(h.calls[3].data.requestId,original.requestId);assert.equal(h.calls[3].data.targetId,27);
  await h.reply(3,h.row());assert.equal(h.results[1].code,200);assert.equal(h.store.size,0);
});
test('a prior unknown report retry returning400 still keeps its original wire and next recovery reads the same key', async () => {
  const a=harness();await firstPost(a);const original=a.wire();a.calls[1].fail({errMsg:'lost'});await tick();
  const b=harness(a.store);b.request();await tick();await b.reply(0,[]);b.dialogs[0].success({confirm:true});await tick();await b.reply(1,{},400);
  assert.deepEqual(b.wire(),original);assert.equal([...b.store.values()][0].status,'unknown');
  b.request();await tick();assert.equal(b.calls[2].url,'/api/community-trust/reports/mine');assert.deepEqual(b.calls[2].data,{requestId:original.requestId});
});
test('report second storage write failure before POST removes only its own new partial and retry can submit', async () => {
  const h=harness(new Map(),{storageFailureOnWrite:2});h.request();await tick();await h.reply(0,{reporting:taxonomy});
  assert.equal(h.results[0].code,507);assert.equal(h.calls.length,1);assert.equal(h.store.size,0);
  h.request();await tick();await h.reply(1,{reporting:taxonomy});assert.equal(h.calls[2].url,'/api/v1/community/reports');
  await h.reply(2,h.row());assert.equal(h.results[1].code,200);
});
