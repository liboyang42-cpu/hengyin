const test=require('node:test');const assert=require('node:assert/strict');
const {createExplorationExportClient}=require('../../subpackageTools/lib/features/exploration-export');
const C1='a'.repeat(70),C2='b'.repeat(70);
const base={format:'chengyin-exploration-export-v1',timeZone:'Asia/Shanghai',snapshotId:'f'.repeat(64),startDate:'2026-10-01',endDateExclusive:'2026-10-13',asOfMs:Date.parse('2026-10-12T12:00:00+08:00'),expiresAtMs:Date.parse('2026-10-13T12:00:00+08:00'),upperRecordId:'9223372036854775807',expectedRows:2,pageSize:1};
const row=(id='9223372036854775807')=>({recordId:id,completedAt:'2026-10-12T10:00:00.000+08:00',routeVersion:'2',elapsedSeconds:3600,routeAvailability:'AVAILABLE',routeTitle:'河岸 🌆'});
function fixture(extra={}){let member=41,epoch=1;const calls=[];const options={getMemberId:()=>member,getLoginEpoch:()=>epoch,sendRequest:r=>calls.push(r),timeoutMs:100,...extra};const client=createExplorationExportClient(options);return {client,calls,options,switchTo:v=>{member=v;epoch++;},respond:(i,data,code=200)=>calls[i].success({code,data})};}
async function prepared(f,snapshot=base){const p=f.client.prepare({startDate:snapshot.startDate,endDateExclusive:snapshot.endDateExclusive,pageSize:snapshot.pageSize});f.respond(f.calls.length-1,{...snapshot,cursor:C1});await p;}
function page(overrides={}){return {...base,cursor:C1,offset:0,items:[row()],emittedRows:1,complete:false,state:'MORE',nextCursor:C2,...overrides};}
test('bounded prepare followed by stable long-string ID pagination creates JSONL chunks',async()=>{
 const f=fixture();await prepared(f);const first=f.client.next();f.respond(1,page());const a=await first;assert.equal(a.complete,false);assert.equal(a.items[0].recordId,'9223372036854775807');assert.equal(a.filename,'chengyin-exploration-20261001-20261013-part-0001.jsonl');
 const last=f.client.next();f.respond(2,page({cursor:C2,offset:1,items:[row('8')],emittedRows:2,complete:true,state:'COMPLETE',nextCursor:null}));const b=await last;assert.equal(b.complete,true);assert.equal(b.checkpoint.offset,2);await assert.rejects(f.client.next());
});
test('JSONL safely preserves formulas quotes newlines Unicode without CSV evaluation',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next(),title='=HYPERLINK("evil")\n中文 🌆\u2028路径/../';f.respond(1,page({items:[{...row(),routeTitle:title}]}));const out=await p;const lines=out.data.trimEnd().split('\n');assert.equal(lines.length,3);assert.equal(JSON.parse(lines[1]).routeTitle,title);assert.ok(!out.filename.includes('evil'));assert.ok(!out.filename.includes('..'));
});
test('unknown network result keeps cursor and retry uses identical page request',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.calls[1].fail();await assert.rejects(p);assert.equal(f.client.checkpoint().cursor,C1);const retry=f.client.retry();assert.equal(f.calls[2].data,f.calls[1].data);f.respond(2,page());await retry;
});
test('timeout keeps pending pagination position',async()=>{
 const f=fixture({timeoutMs:5});await prepared(f);await assert.rejects(f.client.next());assert.equal(f.client.checkpoint().offset,0);
});
test('resume carries range watermark count offset and last ID but no private title',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page());await p;const checkpoint=f.client.checkpoint();assert.ok(!JSON.stringify(checkpoint).includes('河岸'));const resumed=createExplorationExportClient(f.options);resumed.resume(checkpoint);const next=resumed.next();f.respond(2,page({cursor:C2,offset:1,items:[row('8')],emittedRows:2,complete:true,state:'COMPLETE',nextCursor:null}));assert.equal((await next).complete,true);
});
test('source change is explicit incomplete and never emits a misleading file',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page({items:[],emittedRows:0,complete:false,state:'SOURCE_CHANGED',nextCursor:null}));const out=await p;assert.equal(out.complete,false);assert.equal(out.data,null);assert.equal(out.restartRequired,true);await assert.rejects(f.client.next());
});
test('early terminal response cannot silently omit expected records',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page({complete:true,state:'COMPLETE',nextCursor:null}));await assert.rejects(p);assert.equal(f.client.checkpoint().offset,0);
});
test('tampered snapshot and offset cannot replace bound export',async()=>{
 for(const mutation of [{snapshotId:'a'.repeat(64)},{expectedRows:1},{upperRecordId:'999'},{offset:1},{cursor:C2},{asOfMs:base.asOfMs+1}]){const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page(mutation));await assert.rejects(p);}
});
test('duplicate or ascending IDs across pages are rejected',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page());await p;const second=f.client.next();f.respond(2,page({cursor:C2,offset:1,items:[row()],emittedRows:2,complete:true,state:'COMPLETE',nextCursor:null}));await assert.rejects(second);assert.equal(f.client.checkpoint().offset,1);
});
test('unavailable route title must be erased and never leaks accidental raw fields',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page({items:[{...row(),routeAvailability:'UNAVAILABLE',routeTitle:null,stateJson:'secret',latitude:31,memberId:42}]}));const out=await p;assert.equal(out.items[0].routeTitle,null);assert.ok(!out.data.includes('secret'));assert.ok(!out.data.includes('latitude'));
 const bad=f.client.next();f.respond(2,page({cursor:C2,offset:1,items:[{...row('8'),routeAvailability:'UNAVAILABLE',routeTitle:'旧私密标题'}],emittedRows:2,complete:true,state:'COMPLETE',nextCursor:null}));await assert.rejects(bad);
});
test('invalid timestamp future fact negative elapsed and noncanonical IDs are rejected',async()=>{
 for(const mutation of [{completedAt:'2026-10-12T24:00:00.000+08:00'},{completedAt:'2026-10-12T13:00:00.000+08:00'},{elapsedSeconds:-1},{recordId:'08'},{recordId:8},{routeVersion:'2.0'}]){const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page({items:[{...row(),...mutation}]}));await assert.rejects(p);}
});
test('strict numeric 200 and complete response validation preserve cursor on malformed success',async()=>{
 for(const [data,code] of [[page(),'200'],[page({items:null}),200],[page({complete:'false'}),200],[page({emittedRows:2}),200]]){const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,data,code);await assert.rejects(p);assert.equal(f.client.checkpoint().cursor,C1);}
});
test('same account A-B-A epoch rejects old personal export response',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.switchTo(42);f.switchTo(41);f.respond(1,page());await assert.rejects(p);assert.throws(()=>f.client.checkpoint());
});
test('identity changes in success-to-projection microtask window reject private strings',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.respond(1,page());f.switchTo(42);await assert.rejects(p);
});
test('new prepare fences prior late page and preserves newly selected window',async()=>{
 const f=fixture();await prepared(f);const old=f.client.next();const newer=f.client.prepare({startDate:base.startDate,endDateExclusive:base.endDateExclusive,pageSize:1});f.respond(2,{...base,cursor:C2});await newer;f.respond(1,page());await assert.rejects(old);assert.equal(f.client.checkpoint().cursor,C2);
});
test('double next is single flight',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();await assert.rejects(f.client.next());assert.equal(f.calls.length,2);f.respond(1,page());await p;
});
test('dispose permanently rejects all operations and late results',async()=>{
 const f=fixture();await prepared(f);const p=f.client.next();f.client.dispose();f.respond(1,page());await assert.rejects(p);await assert.rejects(f.client.prepare({startDate:base.startDate,endDateExclusive:base.endDateExclusive}));assert.throws(()=>f.client.resume({}));
});
test('zero matching records produces an explicitly empty complete chunk',async()=>{
 const f=fixture(),empty={...base,upperRecordId:'0',expectedRows:0};await prepared(f,empty);const p=f.client.next();f.respond(1,{...empty,cursor:C1,offset:0,items:[],emittedRows:0,complete:true,state:'COMPLETE',nextCursor:null});const out=await p;assert.equal(out.complete,true);assert.equal(out.data.trim().split('\n').length,2);
});
test('invalid date windows and oversized pages never issue requests',async()=>{
 const f=fixture();for(const input of [{startDate:'2026-02-30',endDateExclusive:'2026-03-10'},{startDate:'2020-01-01',endDateExclusive:'2026-10-12'},{startDate:base.startDate,endDateExclusive:base.endDateExclusive,pageSize:101}])await assert.rejects(f.client.prepare(input));assert.equal(f.calls.length,0);
});
test('resume rejects foreign owner terminal snapshots and impossible offsets',async()=>{
 const f=fixture();await prepared(f);const old=f.client.checkpoint();for(const mutation of [{owner:'42'},{complete:true},{changed:true},{offset:3},{lastRecordId:'8'}])assert.throws(()=>f.client.resume({...old,...mutation}));
});
test('latest prepare wins for out-of-order prepare callbacks',async()=>{
 const f=fixture();const one=f.client.prepare({startDate:base.startDate,endDateExclusive:base.endDateExclusive,pageSize:1});const two=f.client.prepare({startDate:base.startDate,endDateExclusive:base.endDateExclusive,pageSize:1});f.respond(1,{...base,cursor:C2});await two;f.respond(0,{...base,cursor:C1});await assert.rejects(one);assert.equal(f.client.checkpoint().cursor,C2);
});
