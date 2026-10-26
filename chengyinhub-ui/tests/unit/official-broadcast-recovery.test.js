const test=require('node:test'),assert=require('node:assert/strict');
const {createBroadcastRecovery}=require('../../src/utils/official-broadcast-recovery');
function setup(values={}){
 let actor='7',token='session',view;const sent=[],queries=[],cancelled=[];
 const storage={getItem:k=>values[k]||null,setItem:(k,v)=>values[k]=v,removeItem:k=>delete values[k]};
 const options={storage,identity:()=>({id:actor,token}),random:b=>b.fill(1),publish:async p=>{sent.push(p);throw Error('timeout');},query:async key=>{queries.push(key);return {code:200,data:{state:'UNKNOWN',requestId:key}};},cancel:async key=>{cancelled.push(key);return {code:200,data:{state:'CANCELLED',requestId:key}};},changed:v=>{view=v;}};
 return {options,values,sent,queries,cancelled,get view(){return view;},open:()=>createBroadcastRecovery(options),switch(){actor='8';token='other';}};
}
test('creation timeout freezes original payload through reopen/query/retry; only terminal permits archive',async()=>{
 const h=setup(),a=h.open(),input={title:'original',audience:'player'};await a.start(input);input.title='changed';assert.equal(h.sent.length,1);assert.ok(h.view.record);a.dispose();const b=h.open();await b.query();assert.ok(h.view.record);await b.retry();assert.deepEqual(h.sent[1],h.sent[0]);b.acknowledge();assert.ok(h.view.record);await b.cancel();assert.equal(h.view.record.result.state,'CANCELLED');b.acknowledge();assert.equal(Object.keys(h.values).length,0);
});
test('create success is not delivery success; matching receipt returns exact broadcast ID',async()=>{
 const h=setup();h.options.publish=async p=>{h.sent.push(p);return {code:200,data:9007199254740992};};h.options.query=async key=>({code:200,data:{state:'APPLIED',requestId:key,broadcastId:'9007199254740993'}});const r=h.open();await r.start({title:'one'});assert.equal(h.view.record.result.broadcastId,'9007199254740993');assert.equal(h.view.record.result.delivered,undefined);
});
test('cross-account callbacks and stale concurrent snapshots cannot overwrite or send',async()=>{
 const h=setup(),a=h.open(),b=h.open();await a.start({title:'first'});await b.start({title:'second'});assert.equal(h.sent.length,1);const before=JSON.stringify(h.values);h.switch();await a.retry();assert.equal(JSON.stringify(h.values),before);assert.equal(h.sent.length,1);const other=h.open();assert.equal(other.pending(),false);
});
test('unreadable storage prevents network calls',async()=>{const h=setup();h.options.storage.getItem=()=>{throw Error('unavailable');};const r=h.open();await r.start({title:'x'});assert.equal(h.sent.length,0);assert.ok(h.view.error);});

test('mismatched restored receipt blocks archive and network, preserving original intent',async()=>{
 const key='official-broadcast-create-v1:7';const h=setup({[key]:JSON.stringify({actor:'7',payload:{clientRequestId:'a'.repeat(32)},result:{requestId:'b'.repeat(32),state:'APPLIED'}})});const before=JSON.stringify(h.values),r=h.open();assert.ok(h.view.error);r.acknowledge();await r.retry();assert.equal(JSON.stringify(h.values),before);assert.equal(h.sent.length,0);
});
