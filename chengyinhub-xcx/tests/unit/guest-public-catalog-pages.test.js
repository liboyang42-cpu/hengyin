'use strict';
// 2026-11-14 owner: public content reads as guest; writes/private tabs use the existing cancellable login modal.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const guard = require('../../utils/auth/require-login.js');
const root = path.resolve(__dirname, '../..');
const flush = async () => { for (let i=0;i<40;i++) await Promise.resolve(); };

function mount(relative) {
  let definition, loginCount=0;
  const requests=[], modals=[];
  const app = { globalData:{features:{}}, id:'', token:'', getUserID(){return this.id;}, getAuthorization(){return this.token;},
    getImgUrl:v=>v, getUserRole:()=> 'player', isDevEnv:()=>false,
    sendRequest:r=>requests.push(r), firstLogin(){loginCount++;this.id='7';this.token='fixture-member';return Promise.resolve({ok:true});} };
  const wx = { getStorageSync:()=>'', getWindowInfo:()=>({windowWidth:390}), getDeviceInfo:()=>({platform:'ios'}),
    getSystemInfoSync:()=>({platform:'ios'}), canIUse:()=>false, stopPullDownRefresh(){}, nextTick:f=>f(),
    navigateTo(){throw Error('guest action must wait for login');}, showLoading(){},hideLoading(){},showToast(){} };
  const page = {data:{}};
  const gate=guard.createRequireLogin({getApp:()=>app,getPages:()=>[page],show:o=>modals.push(o)});
  app.ensureLoginFor=(reason,resume,options)=>{if(guard.hasSession(app))return true;gate.withLogin({...options,reason},resume);return false;};
  global.getApp=()=>app;global.wx=wx;
  const file=path.join(root,relative);
  vm.runInNewContext(fs.readFileSync(file,'utf8'), {Page:d=>definition=d,Component:d=>definition=d,
    getApp:()=>app,getCurrentPages:()=>[page],wx,require:createRequire(file),setTimeout,clearTimeout,console});
  Object.assign(page,definition,definition.methods,{data:JSON.parse(JSON.stringify(definition.data)),
    setData(patch,cb){for(const [k,v] of Object.entries(patch)){const keys=k.split('.');let target=this.data;
      keys.slice(0,-1).forEach(p=>target=target[p]||(target[p]={}));target[keys.at(-1)]=v;}if(cb)cb();},triggerEvent(){}});
  return {app,page,wx,requests,modals,logins:()=>loginCount};
}

test('guest roam entry and nearby map request public layers, with no login or private passport reads', async()=>{
  const h=mount('pages/roam/index.js');h.page.data.entryLocated=true;
  h.page._loadEntryMap(h.page.data.center);
  for(const route of ['/api/roam/entry/nearby','/api/team/nearby','/api/roam/pois'])assert.ok(h.requests.some(r=>r.url===route),route);
  assert.equal(h.modals.length,0);assert.equal(h.page.data.entryReadAccess,'');
  h.requests.length=0;h.wx.getStorageSync=()=>{throw Error('no guest personal history');};
  await h.page._loadIntroPassport();
  assert.deepEqual(h.requests.map(r=>r.url).sort(),['/api/official/active-for-roam','/api/preset/route/list']);
  assert.equal(h.page.data.passportError,false);assert.equal(h.page.data.passport.stats.length,0);
  assert.deepEqual(Array.from(h.page.data.passport.plays,p=>p.key),['event','discover']);
  assert.ok(h.page.data.passport.tiles.every(t=>t.value===''));
  const n=mount('subpackageRoam/nearby/index.js');n.page._queueIcons=()=>Promise.resolve(false);
  n.page.refresh(n.page.data.center);assert.equal(n.requests.length,3);assert.equal(n.modals.length,0);
});

test('guest shop loads public products while never requesting orders, gifts or balance',async()=>{
  const h=mount('subpackageMember/market/passes/index.js');h.page.onLoad({});
  assert.deepEqual(h.requests.map(r=>r.url),['/api/marketing/product/list','/api/platform-pass']);
  h.requests[0].success({code:200,data:[{productId:3,productName:'公开通票',productKind:1,cashPrice:20}]});
  h.requests[1].success({code:200,data:{enabled:false}});await flush();
  assert.equal(h.page.data.state,'ready');assert.equal(h.page.data.products[0].name,'公开通票');
  assert.equal(h.page.data.pointBalance,null);assert.equal(h.modals.length,0);
  h.page.onTab({detail:{key:'orders'}});assert.equal(h.modals.length,1);
  h.modals[0].success({confirm:false});await flush();
  assert.equal(h.page.data.tab,'shop');assert.equal(h.requests.length,2);assert.equal(h.logins(),0);
});

test('catalog identity switch clears private orders and ignores old holding callbacks',async()=>{
  const h=mount('subpackageMember/market/passes/index.js');h.app.id='7';h.app.token='fixture-7';
  h.page.loadMember();const old=h.requests[0];h.page.data.orders=[{id:'private'}];h.page.data.member={side:'private validity'};
  h.app.id='';h.app.token='';h.page.load();
  old.success({code:200,data:{enabled:true,holding:{active:true,coverageEnd:'2027-12-10'}}});
  assert.equal(h.page.data.orders.length,0);assert.equal(h.page.data.member,null);
});

test('guest product detail never quotes; purchase cancel has no payment, quote or navigation effect',async()=>{
  const h=mount('subpackageMember/market/pass/index.js');h.page._id='3';h.page.quote();
  assert.equal(h.page.data.buy.state,'login');assert.equal(h.requests.length,0);
  h.page.onBuy({currentTarget:{dataset:{mode:1}}});assert.equal(h.modals.length,1);
  h.modals[0].success({confirm:false});await flush();
  assert.equal(h.requests.length,0);assert.equal(h.logins(),0);
});

for(const [relative,method,data,event] of [
  ['subpackageRoam/nearby/index.js','apply',{current:{teamId:8}}],
  ['subpackageRoam/nearby/index.js','_startChallenge',{poiCard:{id:9}}],
  ['subpackageRoam/nearby/index.js','onReviewSubmit',{poiCard:{id:9,myStars:5,myText:'公开评价'}}],
  ['subpackageMember/market/membership/index.js','onBuy',{view:{selected:'month',channel:'XPAY'}}],
  ['pages/roam/index.js','onPassportTileTap',{}, {currentTarget:{dataset:{key:'history'}}}],
])test('cancel existing login modal prevents '+relative+' '+method,async()=>{
  const h=mount(relative);Object.assign(h.page.data,data);h.page[method](event);
  assert.equal(h.modals.length,1);h.modals[0].success({confirm:false});await flush();
  assert.equal(h.logins(),0);assert.equal(h.requests.length,0);
});

test('nearby login cannot resume after hide and return to the same page',async()=>{
  const h=mount('subpackageRoam/nearby/index.js');h.page.data.current={teamId:8};
  h.page.apply();h.page.onHide();h.page._hidden=false;
  h.modals[0].success({confirm:true});await flush();
  assert.equal(h.logins(),1);assert.equal(h.requests.length,0);
});

test('guest official task list does not call my-events; private tab cancel leaves public list',async()=>{
  const h=mount('components/cy/scene-roam-task-list/index.js');h.page.load();
  assert.deepEqual(h.requests.map(r=>r.url),['/api/official/active-for-roam']);
  h.requests[0].success({code:200,data:[{id:3,title:'公开活动',status:2}]});
  assert.equal(h.page.data.state,'ready');assert.equal(h.page.data.all.length,1);
  h.page.selectTab({currentTarget:{dataset:{tab:3}}});h.modals[0].success({confirm:false});await flush();
  assert.equal(h.page.data.tab,0);assert.equal(h.requests.length,1);
});

test('2026-11-14 curated guest cards use published cover and real navigation coordinates',()=>{
  const h=mount('pages/roam/index.js');
  const row={id:91,type:1,name:'公开公园',lat:31.2,lng:121.4,coverImg:'park.jpg',tags:'["roam:seed:v1:park"]'};
  h.page._entryPlaces=[row];
  h.page.onEntryCityStrip({detail:{inCity:true,strip:{city:'SH',name:'上海',count:1,items:[{id:'park',seed:'roam:seed:v1:park',name:'公开公园'}]}}});
  assert.equal(h.page.data.entryCityStrip.items[0].cover,'park.jpg');
  h.page.onEntryCityPlaceTap({currentTarget:{dataset:{idx:0}}});
  assert.equal(h.page.data.entryPlace.cover,'park.jpg');
  assert.equal(h.page.data.entryPlace.lat,31.2);assert.equal(h.page.data.entryPlace.lng,121.4);
  assert.equal(h.modals.length,0);assert.equal(h.requests.length,0);
});

test('2026-11-14 guest search reads all public categories without login notices',()=>{
  const h=mount('pages/search2/result/index.js');
  h.page.onLoad({keyword:'城市',categoryId:'3'});
  assert.deepEqual(h.requests.map(r=>r.url),['/api/topic/list','/api/activity/list','/api/club/list','/api/merchant/list','/api/template/topic-template/list','/api/template/list']);
  for(const r of h.requests)r.success({code:200,data:r.url==='/api/club/list'?[{id:7,name:'城市俱乐部'}]:{rows:[]}});
  assert.ok(h.page.data.resultTabs.some(t=>t.label==='俱乐部 1'));
  assert.equal(h.page.data.visibleResults.length,1);assert.equal(h.modals.length,0);
  assert.equal(h.page.data.searchError,'');
});

test('guest global leaderboard uses GET and no own rank; stale account reply is discarded',()=>{
  const h=mount('subpackageP3/pages/growthcenter/leaderboard/index.js');h.page.loadBoard();
  assert.equal(h.requests[0].method,'GET');assert.equal(h.modals.length,0);
  h.requests[0].success({code:200,data:{list:[{rank:1,nickname:'公开玩家',score:20}]}});
  assert.equal(h.page.data.top3[0].name,'公开玩家');assert.equal(h.page.data.me,null);
  h.app.id='7';h.app.token='fixture-member';h.page.loadBoard();const old=h.requests.at(-1);
  h.app.id='';h.app.token='';old.success({code:200,data:{list:[],me:{rank:1,nickname:'private'}}});
  assert.equal(h.page.data.me,null);assert.equal(h.requests.at(-1).method,'GET');
});
