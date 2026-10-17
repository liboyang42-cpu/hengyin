'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const {createSessionStore}=require('../../utils/session/session-store');
const {createSessionManager}=require('../../utils/session/session-manager');
function mount(uid='7') {
 const root=path.resolve(__dirname,'../..');let app,definition;const requests=[];
 const wx={request:r=>{requests.push(r);return{abort(){}};},getSystemInfoSync:()=>({}),getStorageSync:()=>'',pageScrollTo:()=>{}};
 vm.runInNewContext(fs.readFileSync(path.join(root,'app.js'),'utf8'),{App:d=>app=d,wx,console,require:f=>f==='./utils/toast.js'?Object.assign(()=>{},{hide(){}}):require(path.join(root,f))});
 const storage=new Map(),session=createSessionStore({target:app.globalData,wx:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k)}});
 app.getSession=()=>session;app.isDevEnv=()=>false;app.loadFeatureFlags=()=>{};app.isAppReady=true;app.globalData.siteBaseUrl='http://127.0.0.1:8129';session.setUserID(uid);session.setAuthorization(uid?'fixture-old':'');
 app.getSessionManager=()=>createSessionManager({session,isDevEnv:()=>false,wxLogin:async()=>({code:'fixture'}),exchangeCode:async()=>({code:401})});
 const file=path.join(root,'pages/topic/merchantinfo/merchantinfo.js');
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:d=>definition=d,getApp:()=>app,wx,getCurrentPages:()=>[],setTimeout,clearTimeout,clearInterval,require:f=>{
  if(/merchant-theme/.test(f))return{merchantPageShow(){},merchantPageRestore(){}};
  if(/motion-preference/.test(f))return{readReducedMotion:()=>false};
  if(/toast.js/.test(f))return Object.assign(()=>{},{success(){}});
  return require(path.resolve(path.dirname(file),f));
 }});
 const page=Object.assign({},definition,{data:JSON.parse(JSON.stringify(definition.data))});
 page.setData=patch=>{for(const[k,v]of Object.entries(patch)){const keys=k.split('.');let at=page.data;for(const key of keys.slice(0,-1))at=at[key]||(at[key]={});at[keys.at(-1)]=v}};
 // Only rendering the returned host projection is stubbed; all request/session/lifecycle code is actual.
 page.applyHome=d=>{page.setData({hostPlayers:d.host.players||{},hostTopic:d.topic||{}})};
 page.onLoad({topicId:'10',scope:'CLUB'});
 return{app,page,session,requests};
}
const settle=async()=>{for(let n=0;n<40;n++)await Promise.resolve()};
function reply(r,status,data){r.success({statusCode:status,data});if(r.complete)r.complete({})}
function dirty(h){h.page.data.hostPlayers={phone:'private-old'};h.page.data.playerSheet.rows=[{id:99}];h.page.data.info={name:'old'};h.page.data.checkinQrUrl='private-old';h.page._hostChapters=[{id:99}];}
const host={code:200,data:{role:'host',topic:{id:10},host:{players:{total:0}}}};
test('guest does not issue private reads and gets a real login entry',()=>{
 const h=mount('');assert.equal(h.requests.length,0);assert.equal(h.page.data.projectReadAuth,'login');
 for(const name of ['loadProjectHome','loadDetail','loadBrowseData','loadMyChapterNodes','loadHostChapters','openPlayerSheet','goStation'])h.page[name]();
 assert.equal(h.requests.length,0);
});
test('actual owned failed renewal clears all old private projections and exits loading',async()=>{
 const h=mount();dirty(h);reply(h.requests[0],401,{code:401});await settle();assert.equal(h.requests.length,1);assert.equal(h.page._loadingProjectHome,false);assert.equal(h.page.data.projectReadAuth,'login');
 assert.equal(h.page.data.playerSheet.rows.length,0);assert.equal(Object.keys(h.page.data.hostPlayers).length,0);assert.equal(Object.keys(h.page.data.info).length,0);assert.equal(h.page.data.checkinQrUrl,'');assert.equal(h.page._hostChapters,null);
});
test('actual owned successful renewal consumes its response without treating new token as a different account',async()=>{
 const h=mount();h.app.getSessionManager=()=>createSessionManager({session:h.session,isDevEnv:()=>false,wxLogin:async()=>({code:'fixture'}),exchangeCode:async()=>({token:'fixture-renew',data:{id:'7',role:'club',userType:2}})});
 reply(h.requests[0],401,{code:401});await settle();assert.equal(h.requests.length,2);reply(h.requests[1],200,host);assert.equal(h.page.data.role,'host');assert.equal(h.page._loadingProjectHome,false);assert.equal(h.page.data.projectReadAuth,'');
 const count=h.requests.length;h.page.onShow();assert.equal(h.requests.length,count);
});
for(const uid of ['7','8'])test('external session '+uid+' cannot consume old200, and current identity reloads once',()=>{
 const h=mount();dirty(h);h.session.setUserID(uid);h.session.setAuthorization('fixture-external');reply(h.requests[0],200,host);
 assert.equal(h.requests.length,2);assert.equal(Object.keys(h.page.data.hostPlayers).length,0);reply(h.requests[1],200,host);assert.equal(h.page.data.role,'host');assert.equal(h.page._loadingProjectHome,false);
});
test('old superseded callback cannot clear new owner data',()=>{
 const h=mount();h.session.setUserID('8');h.session.setAuthorization('fixture-external');h.page.onShow();assert.equal(h.requests.length,2);h.page.data.hostPlayers={current:true};reply(h.requests[0],200,host);assert.equal(h.page.data.hostPlayers.current,true);assert.equal(h.requests.length,2);
});
test('hidden change clears data without restarting; show resumes actual current read',()=>{
 const h=mount();dirty(h);h.page.onHide();h.session.setAuthorization('fixture-external');reply(h.requests[0],200,host);assert.equal(h.requests.length,1);assert.equal(Object.keys(h.page.data.hostPlayers).length,0);h.page.onShow();assert.equal(h.requests.length,2);
});
test('unload prevents old session callback from restarting or mutating data',()=>{
 const h=mount();h.page.onUnload();h.page.data.hostPlayers={marker:true};h.session.setAuthorization('fixture-external');reply(h.requests[0],200,host);assert.equal(h.requests.length,1);assert.equal(h.page.data.hostPlayers.marker,true);
});
for(const status of [403,500])test('actual '+status+' retains the distinction between permission and service failure',()=>{
 const h=mount();dirty(h);reply(h.requests[0],status,{code:status,msg:'fixture'});assert.equal(h.page._loadingProjectHome,false);assert.equal(h.page.data.projectReadAuth,status===403?'denied':'');assert.equal(h.page.data.loadFailed,status===500);
 if(status===403)assert.equal(Object.keys(h.page.data.hostPlayers).length,0);assert.equal(h.session.getUserID(),'7');
});
test('late subsidiary private callback cannot reset a new host read',()=>{
 const h=mount();reply(h.requests[0],200,host);const old=h.requests.find(r=>r.url.endsWith('/owner-list'));assert.ok(old);h.page.loadProjectHome();h.page.data.hostPlayers={current:true};h.session.setAuthorization('fixture-external');reply(old,200,{code:200,data:[]});assert.equal(h.page.data.hostPlayers.current,true);assert.equal(h.requests.length,4);
});
test('duplicate lazy chapter read does not invalidate its own busy request',()=>{
 const h=mount();h.page.loadHostChapters();const first=h.requests.at(-1);h.page.loadHostChapters();assert.equal(h.requests.length,2);
 reply(first,200,{code:200,data:{chaptersList:[{id:20}],canResubmitSupplyReview:true}});
 assert.equal(h.page._hostChapters[0].id,20);assert.equal(h.page._hostChaptersLoading,false);
});
