const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
let actor, requests, app, flow;
beforeEach(() => {
  actor = 7; requests = [];
  app = { getUserID: () => actor, sendRequest: o => requests.push(o) };
  global.getApp = () => app;
  const modulePath = require.resolve('../../utils/deregister-flow.js');
  delete require.cache[modulePath];
  const { initialData, methods } = require(modulePath);
  flow = Object.assign({data: initialData(), setData(patch) {Object.assign(this.data, patch);}}, methods);
});
test('anonymous status and precheck show login without private requests', () => {
  actor = 0; flow.loadStatus(); flow.loadPrecheck();
  assert.equal(requests.length, 0); assert.equal(flow.data.status, 'LOGIN_REQUIRED');
});
test('real 401 clears private fields and real 403 shows denied', () => {
  flow.loadStatus(); Object.assign(flow.data, {phone:'fixture',smscode:'123456',canApply:true});
  requests[0].successStatusAbnormal({code:500},401);
  assert.equal(flow.data.status, 'LOGIN_REQUIRED'); assert.equal(flow.data.phone,''); assert.equal(flow.data.smscode,''); assert.equal(flow.data.canApply,false);
  flow.loadStatus(); requests[1].fail({statusCode:403}); assert.equal(flow.data.status,'DENIED');
});
test('HTTP500 and network failure remain read failures', () => {
  flow.loadStatus(); requests[0].successStatusAbnormal({code:500},500); assert.equal(flow.data.status,'ERROR');
  flow.loadPrecheck(); requests[1].fail({errMsg:'request:fail'}); assert.equal(flow.data.status,'ERROR');
});
test('previous identity response cannot restore eligibility or blockers', () => {
  flow.loadStatus(); actor=8;
  requests[0].success({code:200,data:{status:'NORMAL'}}); assert.equal(requests.length,2); assert.equal(requests[1].url, '/api/user/deregister/status');
  flow.loadPrecheck(); actor=9;
  requests[2].success({code:200,data:{status:'ELIGIBLE',blockers:[]}}); assert.notEqual(flow.data.status,'ELIGIBLE');
});
test('login uses existing flow once and rechecks only after success', async () => {
  actor=0; flow.loadStatus(); let resolve, calls=0;
  app.firstLogin=()=>{calls++;return new Promise(r=>resolve=r);};
  flow.loginToRead();flow.loginToRead();assert.equal(calls,1);assert.equal(requests.length,0);
  actor=7;resolve({ok:true});await new Promise(r=>setImmediate(r));
  assert.equal(requests.length,1);assert.equal(requests[0].url,'/api/user/deregister/status');
});
test('disposed component ignores late login callback', async () => {
  actor=0;flow.loadStatus();let resolve;
  app.firstLogin=()=>new Promise(r=>resolve=r);flow.loginToRead();flow.dispose();actor=7;resolve({ok:true});
  await new Promise(r=>setImmediate(r));assert.equal(requests.length,0);
});
test('precheck access denial cannot become eligible', () => {
  flow.loadPrecheck(); requests[0].success({code:403,data:{status:'ELIGIBLE',blockers:[]}});
  assert.equal(flow.data.status,'DENIED');assert.equal(flow.data.canApply,false);
});

test('synchronous login failure unlocks retry without pretending it succeeded', async () => {
  actor=0;app.firstLogin=()=>{throw new Error('fixture SDK unavailable');};
  assert.doesNotThrow(()=>flow.loginToRead());assert.equal(flow._readLoginPending,false);assert.equal(requests.length,0);
  app.firstLogin=()=>{actor=7;return Promise.resolve({ok:true});};flow.loginToRead();
  await new Promise(r=>setImmediate(r));assert.equal(requests.length,1);
});
