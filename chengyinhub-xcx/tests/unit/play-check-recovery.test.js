'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
let owner = '9', requests = [], storage = new Map(), spec;
const app = { getUserID: () => owner, globalData: { features: {} }, isDevEnv: () => false,
  sendRequest(o) { requests.push(o); } };
global.getApp = () => app; global.Behavior = x => x;
global.wx = { getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k, structuredClone(v)),
  removeStorageSync: k => storage.delete(k), getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
global.Page = x => { spec = x; };
require('../../pages/play/index.js');
function page() {
  return { ...spec, _playLoadEpoch: 1, _routeState: { sessionId: '9007199254740993' },
    data: { ...structuredClone(spec.data), topicId: '17', activityId: 0,
      journeyCheck: { show: true, nodeId: '29', checkId: 'check1' } },
    setData(patch) { for (const [key,value] of Object.entries(patch)) {
      const parts=key.split('.'); let target=this.data;
      for (const part of parts.slice(0,-1)) target=target[part]; target[parts.at(-1)]=value;
    } } };
}
const receipt = (action='roll') => ({ sessionId: '9007199254740993', nodeId: '29', checkId: 'check1',
  actionId: 'check:29:check1:'+action, total: 12, dc: 13, dice: [12], settled: action==='settle' });
const act = (p, action) => p.onJourneyCheckAction({ detail: { action } });
beforeEach(() => { owner='9';requests=[];storage=new Map(); });
test('actual Page stores exact original run before write and rejects double click', async () => {
  const p=page(), pending=act(p,'roll'); act(p,'roll');
  assert.equal(requests.length,1); assert.equal(storage.size,1);
  assert.equal(requests[0].data.sessionId,'9007199254740993');
  requests[0].success({code:200,data:receipt()}); await pending;
  assert.equal(storage.size,0); assert.equal(p.data.journeyCheck.receipt.total,12);
});
test('network UNKNOWN survives close/reentry; 404 cannot release original action', async () => {
  let p=page();const pending=act(p,'settle'); requests[0].fail(); await pending;
  const original=structuredClone([...storage.values()][0]); p.closeJourneyCheck();p=page();p._restoreJourneyCheck();
  assert.equal(p.data.journeyCheck.unknown,true);act(p,'roll');assert.equal(requests.length,1);
  const read=act(p,'recover');assert.equal(requests[1].method,'GET');assert.equal(requests[1].url,'/api/play/check/receipt');
  assert.equal(requests[1].data.action,'settle');requests[1].success({code:404});await read;
  assert.deepEqual([...storage.values()][0],original);assert.equal(p.data.journeyCheck.unknown,true);
  const found=act(p,'recover');requests[2].success({code:200,data:receipt('settle')});await found;
  assert.equal(storage.size,0);assert.equal(p.data.journeyCheck.receipt.settled,true);
  assert.equal(requests.filter(r=>r.method==='POST').length,1);
});
test('late response cannot update a different account or original run', async () => {
  for(const change of [p=>{owner='10';},p=>{p._routeState.sessionId='88';}]) {
    owner='9';requests=[];storage=new Map();const p=page(),pending=act(p,'roll');change(p);
    requests[0].success({code:200,data:receipt()});await pending;
    assert.equal(p.data.journeyCheck.receipt,undefined);assert.equal(storage.size,1);
  }
});
test('definitive rejection releases only the failed write, generic error remains UNKNOWN', async () => {
  const p=page();let pending=act(p,'roll');requests[0].success({code:409,errorCode:'CHECK_REJECTED',msg:'请先扫码'});await pending;
  assert.equal(storage.size,0);assert.equal(p.data.journeyCheck.disabledReason,'请先扫码');
  pending=act(p,'roll');requests[1].success({code:500});await pending;
  assert.equal(storage.size,1);assert.equal(p.data.journeyCheck.unknown,true);
});
test('foreign or unbound receipt cannot release original request', async () => {
  const p=page(),pending=act(p,'roll');requests[0].success({code:200,data:{...receipt(),sessionId:'88'}});await pending;
  assert.equal(storage.size,1);assert.equal(p.data.journeyCheck.unknown,true);
});
