'use strict';
// BASELINE-ONLY negative evidence for source 864e708e. Expected to fail on that source.
// This is not a v2 acceptance suite: root must author v2 wire fixtures from the matrix.
// No network/WeChat/account operations: app.sendRequest and wx storage are local doubles.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = process.env.H_DRAFT_V2_SOURCE_ROOT;
if (!root) throw Error('H_DRAFT_V2_SOURCE_ROOT must name the frozen xcx source directory');
const pageFile = path.resolve(root, 'pages/publish/simple/index.js');
const draft = { traceId: 'review-local', remainingQuota: 2,
  draft: { title: 'original-local-draft', nodes: [{ merchantName: 'local-node' }] } };
function fixture() {
  let owner = '7', authorization = 'token-local-A', definition;
  const requests = [], storage = Object.create(null);
  const flags = { writeThrow: false, writeNoop: false, readThrow: false };
  const app = { globalData: {}, _featureFlagsEpoch: 1, getUserID: () => owner,
    getAuthorization: () => authorization, sendRequest: r => requests.push(r) };
  global.getApp = () => app;
  global.wx = {
    getStorageSync(k) { if (flags.readThrow) throw Error('local storage read unavailable'); return storage[k] || ''; },
    setStorageSync(k,v) { if (flags.writeThrow) throw Error('local storage write unavailable');
      if (!flags.writeNoop) storage[k] = JSON.parse(JSON.stringify(v)); },
    removeStorageSync(k) { delete storage[k]; }, switchTab() {}, navigateTo() {}, navigateBack() {}, choosePoi() {}
  };
  global.Page = d => { definition = d; };
  delete require.cache[require.resolve(pageFile)]; require(pageFile);
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } });
  page.onLoad({ mode: '2' }); requests.splice(0);
  page.setData({ aiIdea: 'local-idea-only' });
  return { page, requests, storage, flags, app,
    switchOwner(id, token) { owner = id; authorization = token; app._featureFlagsEpoch++; } };
}
test('storage write failure must stop before any prepare or execute write', () => {
  const f=fixture(); f.flags.writeThrow=true; f.page.generateAiPlan();
  assert.equal(f.requests.filter(r=>r.method==='POST').length,0);
});
test('silent storage loss must fail read-back verification before write', () => {
  const f=fixture(); f.flags.writeNoop=true; f.page.generateAiPlan();
  assert.equal(f.requests.filter(r=>r.method==='POST').length,0);
});
test('late A success cannot remove B durable pending', () => {
  const f=fixture(); f.page.generateAiPlan(); const old=f.requests.shift();
  const b={requestId:'B-original-local-key',idea:'B-local'};f.storage['ai_topic_draft_request:m8']=b;
  f.switchOwner('8','token-local-B');old.success({code:200,data:draft});
  assert.deepEqual(f.storage['ai_topic_draft_request:m8'],b);
});
test('A to B to A must invalidate old Page callback even when final member/token equal', () => {
  const f=fixture();f.page.generateAiPlan();const old=f.requests.shift();
  f.switchOwner('8','token-local-B');f.switchOwner('7','token-local-A');
  old.success({code:200,data:draft});assert.equal(f.page.data.aiPlan,null);
});
test('unloaded Page must ignore late successful body', () => {
  const f=fixture();f.page.generateAiPlan();const old=f.requests.shift();f.page.onUnload();
  old.success({code:200,data:draft});assert.equal(f.page.data.aiPlan,null);
});
test('legacy NOT_FOUND must retain unresolved identity', () => {
  const f=fixture();f.page.generateAiPlan();const post=f.requests.shift();const rid=JSON.parse(post.data).requestId;
  post.fail({});post.complete();f.page.checkAiDraftRequest();const read=f.requests.shift();
  read.success({code:200,data:{requestId:rid,state:'NOT_FOUND'}});
  assert.equal(f.page.data.aiDraftPending,true);
});
test('UNKNOWN plus changed idea must not allocate a replacement request', () => {
  const f=fixture();f.page.generateAiPlan();const post=f.requests.shift();const rid=JSON.parse(post.data).requestId;
  post.fail({});post.complete();f.page.checkAiDraftRequest();const read=f.requests.shift();
  read.success({code:200,data:{requestId:rid,state:'UNKNOWN'}});read.complete();
  f.page.setData({aiIdea:'different-local-idea'});f.page.generateAiPlan();
  assert.equal(f.requests.shift().method,'GET');
});
test('actual request-client must reject an A-B-A stale success', () => {
  const {createRequestClient}=require(path.resolve(root,'utils/transport/request-client.js'));
  let owner='7',auth='A-local',calls=0;const wire=[];
  const client=createRequestClient({getUserID:()=>owner,getAuthorization:()=>auth,
    getBaseUrl:()=>'',wxRequest:r=>{wire.push(r);return {abort(){}};}});
  client.send({url:'/api/ai/theme/draft/request',success(){calls++;}});
  owner='8';auth='B-local';owner='7';auth='A-local';
  wire[0].success({statusCode:200,data:{code:200}});assert.equal(calls,0);
});
test('old complete must not clear loading owned by a replacement pending', () => {
  const f=fixture();f.page.generateAiPlan();const old=f.requests.shift();
  f.page._setAiPending({requestId:'later-local-request',idea:'later'});f.page.setData({aiGenerating:true});
  old.complete();assert.equal(f.page.data.aiGenerating,true);
});
test('storage read error cannot mean there is no existing durable request', () => {
  const f=fixture();f.flags.readThrow=true;f.page._restoreAiPending();f.page.generateAiPlan();
  assert.equal(f.requests.filter(r=>r.method==='POST').length,0);
});
test('pending identity journal must not retain plaintext idea indefinitely', () => {
  const f=fixture();f.page.generateAiPlan();
  assert.equal(Object.prototype.hasOwnProperty.call(f.storage['ai_topic_draft_request:m7'],'idea'),false);
});
test('SUCCEEDED with unavailable body must not discard original identity', () => {
  const f=fixture();f.page.generateAiPlan();const p=f.requests.shift();const rid=JSON.parse(p.data).requestId;
  p.fail({});p.complete();f.page.checkAiDraftRequest();const read=f.requests.shift();
  read.success({code:200,data:{requestId:rid,state:'SUCCEEDED',resultAvailable:false,result:null,reasonCode:'AI_DRAFT_RESULT_UNAVAILABLE'}});
  assert.equal(f.page.data.aiDraftPending,true);
});
