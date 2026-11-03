const test = require('node:test');
const assert = require('node:assert/strict');
const { createCapsuleClient } = require('../../subpackageTools/utils/features/capsule-client');
const draft = { requestId: 'capsule-request-0001', body: '未来见', unlockDate: '2027-01-01' };
function setup() {
  let owner = '7:login-1'; const sent = [];
  const client = createCapsuleClient({ getIdentity: () => owner, sendRequest: req => sent.push(req), timeoutMs: 25 });
  return { client, sent, owner(value) { owner = value; } };
}
function ok(req, data) { req.success({ code: 200, data }); }
const locked = { id: '9007199254740999', state: 'LOCKED', unlockAtMs: 1798732800000 };
test('double submit joins original request and sends JSON without owner supplied by caller', async () => {
  const s=setup(); const a=s.client.create(draft), b=s.client.create(draft);
  assert.equal(a,b); assert.equal(s.sent.length,1); assert.equal(JSON.parse(s.sent[0].data).body,'未来见');
  assert.equal(s.sent[0].header['Content-Type'],'application/json');
  ok(s.sent[0],locked); assert.equal((await a).id,locked.id); assert.equal(s.client.pendingRequestId(),null);
});
test('network uncertainty retains exact key and payload; changed text cannot replace pending attempt', async () => {
  const s=setup(); const a=s.client.create(draft); s.sent[0].fail(); await assert.rejects(a);
  assert.equal(s.client.pendingRequestId(),draft.requestId);
  await assert.rejects(s.client.create({ ...draft, body:'changed' }),/待确认/);
  const b=s.client.retry(); assert.deepEqual(s.sent[1].data,s.sent[0].data); ok(s.sent[1],locked); await b;
});
test('malformed success preserves original pending operation', async () => {
  const s=setup(); const p=s.client.create(draft); ok(s.sent[0],{ nope:true }); await assert.rejects(p);
  assert.equal(s.client.pendingRequestId(),draft.requestId);
});
test('strict code check rejects string success', async () => {
  const s=setup(); const p=s.client.create(draft); s.sent[0].success({code:'200',data:locked}); await assert.rejects(p);
});
test('deadline reports unknown and late success cannot mutate pending state', async () => {
  const s=setup(); const p=s.client.create(draft); await assert.rejects(p,/超时/);
  ok(s.sent[0],locked); assert.equal(s.client.pendingRequestId(),draft.requestId);
});
test('account change invalidates old callback and clears private draft', async () => {
  const s=setup(); const p=s.client.create(draft); s.owner('8:login-2'); ok(s.sent[0],locked); await assert.rejects(p,/会话/);
  assert.equal(s.client.pendingRequestId(),null); await assert.rejects(s.client.retry(),/没有/);
});
test('same member new login epoch invalidates old callbacks', async () => {
  const s=setup(); const p=s.client.detail('1'); s.owner('7:login-3'); ok(s.sent[0],locked); await assert.rejects(p,/会话/);
});
test('locked detail strips accidental content and URLs from the wire', async () => {
  const s=setup(); const p=s.client.detail('1'); ok(s.sent[0],{...locked,body:'secret',photoUrl:'private'});
  const value=await p; assert.equal('body' in value,false); assert.equal('photoUrl' in value,false);
});
test('open detail preserves available content', async () => {
  const s=setup(); const p=s.client.detail('1'); ok(s.sent[0],{...locked,state:'OPEN',body:'hello'}); assert.equal((await p).body,'hello');
});
test('list never stores raw locked contents', async () => {
  const s=setup(); const p=s.client.list(null,20); ok(s.sent[0],{items:[{...locked,body:'secret'}],hasMore:false,nextBeforeId:null});
  assert.equal('body' in (await p).items[0],false);
});
test('receipt uses original key and returns deletion tombstone without content', async () => {
  const s=setup(); const p=s.client.receipt(draft.requestId); assert.equal(s.sent[0].data.requestId,draft.requestId);
  ok(s.sent[0],{...locked,state:'DELETED',body:'secret'}); assert.equal((await p).state,'DELETED');
});
test('dispose fences in-flight callbacks', async () => {
  const s=setup(); const p=s.client.detail('1'); s.client.dispose(); ok(s.sent[0],locked); await assert.rejects(p,/会话/);
});
test('logged-out operations never send', async () => {
  const s=setup(); s.owner(''); await assert.rejects(s.client.create(draft),/登录/); assert.equal(s.sent.length,0);
});
test('delete uses form parameters and confirms only returned deletion', async () => {
  const s=setup(); const p=s.client.remove('1'); assert.equal(s.sent[0].method,'POST'); assert.deepEqual(s.sent[0].data,{id:'1'});
  ok(s.sent[0],{id:'1',state:'DELETED'}); assert.equal((await p).state,'DELETED');
});

test('confirmed receipt resolves only the original pending request', async () => {
  const s=setup(); const first=s.client.create(draft); s.sent[0].fail(); await assert.rejects(first);
  const read=s.client.receipt(draft.requestId); ok(s.sent[1],locked); await read;
  assert.equal(s.client.pendingRequestId(),null);
  const next=s.client.create({...draft,requestId:'capsule-request-0002'}); ok(s.sent[2],{...locked,id:'2'}); await next;
});
test('unrelated receipt cannot clear current pending request', async () => {
  const s=setup(); const first=s.client.create(draft); s.sent[0].fail(); await assert.rejects(first);
  const read=s.client.receipt('capsule-request-other'); ok(s.sent[1],locked); await read;
  assert.equal(s.client.pendingRequestId(),draft.requestId);
});

test('first definitive 400 permits correcting invalid create content', async () => {
  const s=setup(); const p=s.client.create(draft); s.sent[0].success({code:400,msg:'invalid date'}); await assert.rejects(p);
  assert.equal(s.client.pendingRequestId(),null);
  const fixed=s.client.create({...draft,requestId:'capsule-request-0002',unlockDate:'2027-02-01'});ok(s.sent[1],locked);await fixed;
});
test('400 after unknown retry does not erase possible earlier commit', async () => {
  const s=setup(); let p=s.client.create(draft);s.sent[0].fail();await assert.rejects(p);
  p=s.client.retry();s.sent[1].success({code:400,msg:'expired credential'});await assert.rejects(p);
  assert.equal(s.client.pendingRequestId(),draft.requestId);
  await assert.rejects(s.client.create({...draft,requestId:'capsule-request-0002'}),/待确认/);
  p=s.client.receipt(draft.requestId);ok(s.sent[2],locked);await p;assert.equal(s.client.pendingRequestId(),null);
});
test('same-key conflict 409 retains original key for receipt recovery', async () => {
  const s=setup();const p=s.client.create(draft);s.sent[0].success({code:409,msg:'conflict'});await assert.rejects(p);
  assert.equal(s.client.pendingRequestId(),draft.requestId);
});
