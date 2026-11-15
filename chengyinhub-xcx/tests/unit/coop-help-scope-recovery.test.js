'use strict';
// GARCH-01: actual WXML handlers, Page projections and request-client; transport stays in-process.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const fromRoot = p => path.resolve(__dirname, '../..', p);
const {createRequestClient} = require(fromRoot('utils/transport/request-client.js'));
const template = fs.readFileSync(fromRoot('pages/play/theme-backpack.wxml'), 'utf8');
function cooperation(instance='inst-A', gameSessionId=50) {
  return {gameSessionId, authorityEpoch:1, questions:[{questKey:'q1',nodeId:11,runtimeInstance:instance}]};
}
function fixture() {
  const requests=[];
  const actor={id:7, epoch:0};
  const transport=createRequestClient({wxRequest:r=>{requests.push(r);return {abort(){}};},
    getBaseUrl:()=> 'https://fixture.invalid', getUserID:()=>actor.id, getAuthorization:()=> 'fixture-only',getSessionRevision:()=>actor.epoch});
  const app={globalData:{},getUserID:()=>actor.id,sendRequest:transport.send};
  Object.defineProperty(app,'_loginEpoch',{get:()=>actor.epoch});
  global.getApp=()=>app;
  global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({windowWidth:375,statusBarHeight:20})};
  let config; global.Page=c=>{config=c;};
  delete require.cache[require.resolve(fromRoot('pages/play/index.js'))];
  require(fromRoot('pages/play/index.js'));
  const page=Object.assign({},config,{data:JSON.parse(JSON.stringify(config.data))});
  page.setData=(patch,cb)=>{for(const [key,value] of Object.entries(patch)){const parts=key.split('.');let target=page.data;while(parts.length>1){const key=parts.shift();target=target[key] || (target[key]={});}target[parts[0]]=value;}if(cb)cb();};
  page.data.activityId=70;page.data.topicId=31;page._routeState={sessionId:'route-1'};
  page._stopThemePoll=()=>{};page._startThemePoll=()=>{};
  page._applyZeroHpView=()=>{};page._applyR5OutcomeView=()=>{};page._applyPlayerOpsView=()=>{};
  function apply(instance='inst-A',session=50) {
    page._applyThemeTeamView({cooperation:cooperation(instance,session),contents:[],events:[],scope:'TEAM'},
      {sessionId:session,activityId:70,status:'RUNNING',revision:1});
  }
  apply();
  function dispatch(handler,data) {
    assert.ok(template.includes('bindtap="'+handler+'"'),'handler must be bound in actual WXML');
    assert.equal(typeof page[handler],'function');
    return page[handler]({currentTarget:{dataset:data}});
  }
  function reply(body){requests.at(-1).success({statusCode:200,data:body});}
  return {page,requests,actor,apply,dispatch,reply};
}
async function open(f, levels=[{level:1,type:'TEXT',usable:true,free:true}]) {
  const p=f.dispatch('openCoopHelp',{key:'q1'});
  f.reply({code:200,data:{levels}});
  assert.equal(await p,true);
}
test('CONTROL: actual WXML -> Page -> request-client -> current VIEW succeeds',async()=>{
 const f=fixture();await open(f);assert.equal(f.page.data.coopHelp.rows[0].level,1);
});
test('CONTROL: actual transport rejects successful old-account content',async()=>{
 const f=fixture();await open(f);const p=f.dispatch('useCoopHelp',{level:1});f.actor.id=8;f.actor.epoch++;
 f.reply({code:200,data:{content:'old-private-fixture',cooperation:cooperation()}});await p;
 assert.notEqual(f.page.data.coopHelp.content,'old-private-fixture');
});
test('RED-1: late VIEW must not populate replacement runtime instance',async()=>{
 const f=fixture();const p=f.dispatch('openCoopHelp',{key:'q1'});f.apply('inst-B');
 f.reply({code:200,data:{levels:[{level:1,type:'TEXT',usable:true,free:true}],cooperation:cooperation()}});
 assert.equal(await p,false);assert.equal(f.page.data.themeBag.cooperation.questions[0].runtimeInstance,'inst-B');
});
test('RED-2: late USE must not replace new session cooperation or display old content',async()=>{
 const f=fixture();await open(f);const p=f.dispatch('useCoopHelp',{level:1});f.apply('inst-B',51);
 f.reply({code:200,data:{content:'old-instance-fixture',cooperation:cooperation()}});
 await p;assert.equal(f.page.data.themeBag.cooperation.gameSessionId,51);
 assert.notEqual(f.page.data.coopHelp && f.page.data.coopHelp.content,'old-instance-fixture');
});
test('RED-3: disposed Page must reject pending help response',async()=>{
 const f=fixture();const p=f.dispatch('openCoopHelp',{key:'q1'});
 // Exact lifecycle disposal markers set by production onUnload; unrelated cleanup not invoked.
 f.page._playDisposed=true;f.page._themeEpoch=1;f.page._playLoadEpoch=1;
 f.reply({code:200,data:{levels:[{level:1,type:'TEXT',usable:true,free:true}]}});
 assert.equal(await p,false);
});
test('RED-4: UNKNOWN continuation cannot rebind original requestId to replacement instance',async()=>{
 const f=fixture();await open(f);const first=f.dispatch('useCoopHelp',{level:1});
 const original=structuredClone(f.requests.at(-1).data);
 f.reply({code:520,httpFail:true});await first;f.apply('inst-B');
 const count=f.requests.length;const retry=f.dispatch('useCoopHelp',{level:1});
 const sent=f.requests.length>count ? structuredClone(f.requests.at(-1).data) : null;
 if(sent)f.reply({code:409,msg:'同一请求编号不能更换帮助动作'});await retry;
 assert.ok(!sent || JSON.stringify(sent)===JSON.stringify(original),'must retain original scope/body or refuse; current implementation sends inst-B with original requestId');
});

for (const [name,change] of [
 ['account',f=>{f.actor.id=8;}],
 ['login generation',f=>{f.actor.epoch++;}],
 ['activity',f=>{f.page.data.activityId=71;}],
 ['topic',f=>{f.page.data.topicId=32;}],
 ['route session',f=>{f.page._routeState.sessionId='route-2';}],
 ['team',f=>{f.page.data.themeBag.teamId=2;}],
 ['authority epoch',f=>{f.page.data.themeBag.cooperation.authorityEpoch=2;}],
 ['question node',f=>{f.page.data.themeBag.cooperation.questions[0].nodeId=12;}],
 ['unload',f=>{f.page._playDisposed=true;}],
]) test('stale '+name+' refuses a new help command before transport',async()=>{
 const f=fixture();await open(f);change(f);const count=f.requests.length;
 const p=f.dispatch('useCoopHelp',{level:1});
 // Settle unsafe baseline requests so a failure cannot hang the test process.
 if(f.requests.length>count) f.reply({code:409,msg:'stale fixture request'});
 assert.equal(await p,false);assert.equal(f.requests.length,count);
});

test('same-scope UNKNOWN retries the exact original body after projection refresh',async()=>{
 const f=fixture();await open(f);const p=f.dispatch('useCoopHelp',{level:1});
 const original=structuredClone(f.requests.at(-1).data);
 f.reply({code:520,httpFail:true});await p;f.apply();
 const retry=f.dispatch('useCoopHelp',{level:1});
 assert.deepEqual(f.requests.at(-1).data,original);
 f.reply({code:200,data:{content:'current-hint',cooperation:cooperation()}});
 assert.equal(await retry,true);assert.equal(f.page.data.coopHelp.content,'current-hint');
 assert.equal(f.page.data.coopHelp.unknown,null);
});

test('UNKNOWN blocks a different level or SKIP until the original command is confirmed',async()=>{
 const f=fixture();await open(f,[{level:1,type:'TEXT',usable:true,free:true},{level:2,type:'TEXT',usable:true,free:true},{level:3,type:'SKIP',usable:true}]);
 const p=f.dispatch('useCoopHelp',{level:1});f.reply({code:520,httpFail:true});await p;
 const count=f.requests.length;
 for(const [handler,data] of [['useCoopHelp',{level:2}],['confirmCoopSkip',{}]]) {
   const attempt=f.dispatch(handler,data);
   if(f.requests.length>count)f.reply({code:409,msg:'original pending'});
   assert.equal(await attempt,false);assert.equal(f.requests.length,count);
 }
 assert.equal(f.page.data.coopHelp.unknown.level,1);
});

test('close/reopen in the same scope retains the UNKNOWN original request',async()=>{
 const f=fixture();await open(f);const p=f.dispatch('useCoopHelp',{level:1});
 const original=structuredClone(f.requests.at(-1).data);
 f.reply({code:520,httpFail:true});await p;
 f.dispatch('closeCoopHelp',{});await open(f);
 assert.equal(f.page.data.coopHelp.unknown.requestId,original.requestId);
 const retry=f.dispatch('useCoopHelp',{level:1});assert.deepEqual(f.requests.at(-1).data,original);
 f.reply({code:200,data:{content:'confirmed-original'}});assert.equal(await retry,true);
});

test('SKIP UNKNOWN replays the original body and success refreshes authority once',async()=>{
 const f=fixture();await open(f,[{level:3,type:'SKIP',usable:true}]);
 let refreshed=0;f.page.refreshThemeBackpack=()=>{refreshed++;return Promise.resolve(true);};
 f.dispatch('askCoopSkip',{});assert.equal(f.page.data.coopHelp.skipConfirm,true);
 const first=f.dispatch('confirmCoopSkip',{}),original=structuredClone(f.requests.at(-1).data);
 f.reply({code:520,httpFail:true});await first;assert.equal(refreshed,0);
 const retry=f.dispatch('confirmCoopSkip',{});assert.deepEqual(f.requests.at(-1).data,original);
 f.reply({code:200,data:{skipped:true}});assert.equal(await retry,true);
 assert.equal(refreshed,1);assert.equal(f.page.data.coopHelp,null);
});

test('reopened panel and current scope win over an older VIEW callback',async()=>{
 const f=fixture();const old=f.dispatch('openCoopHelp',{key:'q1'}),oldRequest=f.requests.at(-1);
 f.dispatch('closeCoopHelp',{});f.apply('inst-B');await open(f);
 oldRequest.success({statusCode:200,data:{code:200,data:{levels:[],cooperation:cooperation()}}});
 assert.equal(await old,false);assert.equal(f.page.data.coopHelp.rows.length,1);
 assert.equal(f.page.data.themeBag.cooperation.questions[0].runtimeInstance,'inst-B');
});

test('same-instance late USE may show its result but cannot regress refreshed cooperation',async()=>{
 const f=fixture();await open(f);const p=f.dispatch('useCoopHelp',{level:1});
 f.apply();f.page.data.themeBag.cooperation.questions[0].answerRevision=9;
 f.reply({code:200,data:{content:'original-result',cooperation:cooperation()}});
 assert.equal(await p,true);assert.equal(f.page.data.coopHelp.content,'original-result');
 assert.equal(f.page.data.themeBag.cooperation.questions[0].answerRevision,9);
});
