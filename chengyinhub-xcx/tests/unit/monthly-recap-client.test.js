const test=require('node:test'); const assert=require('node:assert/strict');
const {createMonthlyRecapClient}=require('../../subpackageTools/lib/features/monthly-recap-client');
function setup() { let owner='7:session1';const sent=[];return {sent,owner(v){owner=v;},client:createMonthlyRecapClient({getIdentity:()=>owner,sendRequest:r=>sent.push(r),timeoutMs:30})}; }
function result(overrides) {return Object.assign({month:'2026-10',kind:'routes',counts:{routes:2,places:0,stamps:0},items:[{id:'99',title:'路线'}],hasMore:false,nextBeforeId:null},overrides);}
function ok(req,data){req.success({code:200,data});}
test('empty actual facts remain zero and empty, not fabricated cards',async()=>{const s=setup();const p=s.client.load('2026-10');ok(s.sent[0],result({items:[],counts:{routes:0,places:0,stamps:0}}));assert.deepEqual((await p).items,[]);});
test('new month overtakes old response',async()=>{const s=setup();const a=s.client.load('2026-09');const b=s.client.load('2026-10');ok(s.sent[1],result());await b;ok(s.sent[0],result({month:'2026-09'}));await assert.rejects(a,/过期/);assert.equal(s.client.snapshot().month,'2026-10');});
test('account switch rejects old response and clears snapshot',async()=>{const s=setup();const p=s.client.load('2026-10');s.owner('8:session2');ok(s.sent[0],result());await assert.rejects(p,/过期/);assert.equal(s.client.snapshot(),null);});
test('pagination deduplicates string IDs and retains values above JS safe integer',async()=>{const s=setup();let p=s.client.load('2026-10');ok(s.sent[0],result({items:[{id:'9007199254740999'}],hasMore:true,nextBeforeId:'9007199254740999'}));await p;p=s.client.next();assert.equal(s.sent[1].data.beforeId,'9007199254740999');ok(s.sent[1],result({items:[{id:'9007199254740999'},{id:'88'}]}));assert.equal((await p).items.length,2);});
test('response for different kind cannot replace requested list',async()=>{const s=setup();const p=s.client.load('2026-10','stamps');ok(s.sent[0],result());await assert.rejects(p,/未确认/);assert.equal(s.client.snapshot(),null);});
test('bad month fails before network',async()=>{const s=setup();await assert.rejects(s.client.load('2026-13'));assert.equal(s.sent.length,0);});
test('no next page does not request or pretend to load',async()=>{const s=setup();await assert.rejects(s.client.next(),/没有/);assert.equal(s.sent.length,0);});
test('snapshot is copied so caller mutations cannot corrupt paging',async()=>{const s=setup();const p=s.client.load('2026-10');ok(s.sent[0],result());const v=await p;v.items[0].id='1';assert.equal(s.client.snapshot().items[0].id,'99');});
test('current network failure clears previously visible facts until revalidated',async()=>{const s=setup();let p=s.client.load('2026-10');ok(s.sent[0],result());await p;p=s.client.load('2026-09');s.sent[1].fail();await assert.rejects(p);assert.equal(s.client.snapshot(),null);});
test('timeout and late result cannot update state',async()=>{const s=setup();const p=s.client.load('2026-10');await assert.rejects(p,/超时/);ok(s.sent[0],result());assert.equal(s.client.snapshot(),null);});
test('dispose invalidates old request',async()=>{const s=setup();const p=s.client.load('2026-10');s.client.dispose();ok(s.sent[0],result());await assert.rejects(p,/过期/);});
test('invalid item does not replace confirmed snapshot',async()=>{const s=setup();const p=s.client.load('2026-10');ok(s.sent[0],result({items:[{id:'NaN'}]}));await assert.rejects(p,/编号/);assert.equal(s.client.snapshot(),null);});

test('current 403 refresh clears titles whose access may have been revoked',async()=>{const s=setup();let p=s.client.load('2026-10');ok(s.sent[0],result());await p;p=s.client.load('2026-10');s.sent[1].success({code:403,msg:'denied'});await assert.rejects(p);assert.equal(s.client.snapshot(),null);});

test('next page replaces previous facts whose visibility was not revalidated',async()=>{const s=setup();let p=s.client.load('2026-10');ok(s.sent[0],result({hasMore:true,nextBeforeId:'99'}));await p;p=s.client.next();ok(s.sent[1],result({items:[]}));assert.deepEqual((await p).items,[]);assert.deepEqual(s.client.snapshot().items,[]);});
