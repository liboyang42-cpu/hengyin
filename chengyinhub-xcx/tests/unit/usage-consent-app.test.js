'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {createSessionStore}=require('../../utils/session/session-store.js');
const root=path.resolve(__dirname,'../..');
function mount() {
  let app; const storage=new Map(), requests=[], logins=[], native=[]; let shown=0;
  const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),
    request:r=>{requests.push(r);return{abort(){}};},login:r=>logins.push(r),showLoading(){},hideLoading(){},hideToast(){},
    getStorageInfoSync:()=>({keys:[...storage.keys()]}),reLaunch:r=>{wx.relaunched=r.url;}};
  vm.runInNewContext(fs.readFileSync(path.join(root,'app.js'),'utf8'),{App:a=>{app=a;},wx,console,setTimeout,clearTimeout,
    require:f=>f==='./utils/toast.js'?Object.assign(()=>{},{hide(){}}):require(path.join(root,f))});
  app.getSession=(()=>{const session=createSessionStore({target:app.globalData,wx});return()=>session;})();
  app.isDevEnv=()=>false; app.globalData.siteBaseUrl='http://127.0.0.1:8129';
  app.openPrivacyAuthorizationPage=()=>{shown++;}; app._privacyResolvers=[r=>native.push(r)];
  return{app,wx,storage,requests,logins,native,shown:()=>shown};
}
async function settle(){for(let i=0;i<40;i++)await Promise.resolve();}
function reply(r,data,statusCode=200){r.success({statusCode,data});if(r.complete)r.complete({statusCode,data});}
function consentReply(r){const body=JSON.parse(r.data);reply(r,{code:200,data:Object.assign(body,{docVersion:body.docType==='privacy_policy'?'v2.1':'v2.2'})});}
async function exchange(h){h.logins.at(-1).success({code:'fresh-login'});await settle();const r=h.requests.find(x=>x.url.endsWith('/api/login/code'));assert.ok(r);reply(r,{token:'fixture-seven',data:{id:'7',role:'player',userType:1,wxOpenId:'fixture-openid'}});await settle();}

// 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:单独同意隐私协议不再建号。下列用例的
// 「同意 → 登录 → 留痕」链路改由写前登录触发:游客点了「微信登录」(firstLogin 在途、等隐私闸),再勾选同意。
function agreeDuringLogin(h){h.app.firstLogin();return h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);}
async function logoutThroughSettings(h) {
  let page;
  vm.runInNewContext(fs.readFileSync(path.join(root,'pages/shezhi/shezhi.js'),'utf8'),{
    Page:p=>{page=p;},getApp:()=>h.app,wx:h.wx,console,setTimeout,clearTimeout,
    require:f=>require(path.resolve(root,'pages/shezhi',f))
  });
  page.selectComponent=()=>({busyOn(){},failed(message){assert.fail(message);}});
  h.storage.set('roam_memory_v1:7:sessions',[{sessionId:'logout-fixture'}]);
  page.onDangerConfirm({detail:{key:'account.logout'}});
  reply(h.requests.filter(r=>r.url.endsWith('/api/logout')).at(-1),{code:200});await settle();
  for(const key of ['authorization','open_id','user_id','user_type','avatar','nickname','role']) assert.equal(h.app.globalData[key],'');
  assert.equal(h.storage.has('authorization'),false);
  assert.equal(h.storage.has('cy_usage_choice_v1'),false);
  assert.equal([...h.storage.keys()].some(key=>key.startsWith('cy_consent_retry:')),false);
  assert.equal(h.storage.get('roam_memory_v1:7:sessions')[0].sessionId,'logout-fixture');
  assert.equal(h.wx.relaunched,'/pages/index/index');
}
async function reloginAfterLogout(h,owner='7') {
  const before=h.requests.length,shown=h.shown(),waiting=h.app.firstLogin();await settle();
  assert.equal(h.shown(),shown,'same-instance explicit choice remains effective');
  h.logins.at(-1).success({code:'logout-relogin'});await settle();
  const request=h.requests.slice(before).find(r=>r.url.endsWith('/api/login/code'));assert.ok(request);
  reply(request,{token:'fixture-relogin-'+owner,data:{id:owner,role:'player',userType:1,wxOpenId:'fixture-openid-'+owner}});
  assert.equal((await waiting).ok,true);await settle();
}
test('guest initialization finishes and public reads work before any native login or identity exchange',async()=>{
  const h=mount();await h.app.appInitial({});
  assert.equal(h.logins.length,0);assert.equal(h.requests.some(r=>r.url.endsWith('/api/login/code')),false);
  h.app.sendRequest({url:'/api/topic/list',method:'GET',auth:false,success(){}});
  assert.ok(h.requests.some(r=>r.url.endsWith('/api/topic/list')));
  h.app.declineUsageConsent();await h.app.appInitial({});assert.equal(h.logins.length,0);
});
test('2026-10-24 guest agreement stays a guest: no wx.login or /api/login/code, consent is recorded after the first write-gate login',async()=>{
  const h=mount();const result=await h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  assert.equal(result.guest,true);assert.equal(h.native[0].event,'agree');assert.equal(h.app.getUsageConsent().granted(),true);
  assert.equal(h.logins.length,0);assert.equal(h.requests.some(r=>r.url.endsWith('/api/login/code')),false);
  assert.equal(h.requests.some(r=>r.url.endsWith('/consents')),false);assert.ok(!h.app.getUserID());
  const login=h.app.firstLogin();await settle();await exchange(h);assert.equal((await login).ok,true);
  const privacy=h.requests.find(r=>r.url.endsWith('/consents'));assert.ok(privacy,'first login backfills the consent record');
  assert.equal(JSON.parse(privacy.data).docType,'privacy_policy');
});
test('checkbox refusal never resolves native agree or obtains identity; refusal settles pending firstLogin as consentDeclined',async()=>{
  const h=mount();await assert.rejects(h.app.agreeUsageConsent({detail:{buttonId:'agree'}},false),/勾选/);
  assert.equal(h.native.length,0);assert.equal(h.logins.length,0);
  const login=h.app.firstLogin();await settle();assert.equal(h.logins.length,0);assert.equal(h.shown(),1);
  h.app.declineUsageConsent();const result=await login;assert.equal(result.ok,false);assert.equal(result.reason,'consentDeclined');
  assert.equal(h.requests.length,0);
});
test('native agree precedes actual wxLogin, then current-owner privacy AND service UUID records must both persist',async()=>{
  const h=mount();const waiting=h.app.firstLogin();await settle();
  const agreeing=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  assert.equal(h.native[0].event,'agree');assert.equal(h.logins.length,1);assert.equal(h.requests.length,0);
  await exchange(h);assert.equal((await waiting).ok,true);
  const privacy=h.requests.find(r=>r.url.endsWith('/consents'));assert.ok(privacy);assert.equal(JSON.parse(privacy.data).docType,'privacy_policy');
  let completed=false;agreeing.then(()=>{completed=true;});assert.equal(completed,false);
  consentReply(privacy);await settle();const service=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.equal(JSON.parse(service.data).docType,'user_service_agreement');assert.equal(completed,false);
  consentReply(service);await agreeing;assert.equal(completed,true);
});
test('failed backend write retains the same owner UUID across a new consent client and retry; raw 200 cannot count as saved',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));reply(first,{code:200});await assert.rejects(attempt,/同意记录失败/);
  const id=JSON.parse(first.data).requestId;h.app._consentClient=null;
  const retry=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  const second=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(second.data).requestId,id);
  consentReply(second);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await retry;
  assert.equal(h.logins.length,1);
});
test('actor switch during consent readback cannot make a foreign owner agreement successful',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));h.app.getSession().setUserID('8');consentReply(first);
  await assert.rejects(attempt,/同意记录失败/);assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,1);
});
test('privacy page and real gate: one sentence, one checkbox line linking both agreements, agree needs the tick',()=>{
  for(const name of ['pages/privacy','components/cy/privacy-gate']){
    const w=fs.readFileSync(path.join(root,name,'index.wxml'),'utf8');
    assert.match(w,/checkbox-group[^>]*bindchange="toggleService"/);assert.match(w,/type="service"/);
    assert.match(w,/catchtap="openService"[^>]*>《用户服务协议》/);assert.match(w,/catchtap="openPrivacyContract"[^>]*>《隐私保护指引》/);
    assert.match(w,/open-type="agreePrivacyAuthorization"[^>]*disabled="\{\{submitting \|\| !serviceAgreed\}\}"/);
    const j=fs.readFileSync(path.join(root,name,'index.js'),'utf8');
    assert.match(j,/app\.agreeUsageConsent\(event, this\.data\.serviceAgreed\)/);
  }
});
test('cold reentry retries unsaved explicit consent with both original UUIDs and frozen versions',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));const pending=h.storage.get('cy_usage_pending_v1:7');
  assert.equal(pending.versions.privacy_policy,'v2.1');assert.equal(pending.versions.user_service_agreement,'v2.2');
  const privacyId=pending.pending.privacy_policy.requestId,serviceId=pending.pending.user_service_agreement.requestId;
  assert.equal(JSON.parse(first.data).requestId,privacyId);reply(first,{code:200});await assert.rejects(attempt,/同意记录失败/);
  h.app._usageConsent=null;h.app._consentClient=null;assert.equal(h.app.getUsageConsent().granted(),true);
  assert.equal((await h.app.firstLogin()).ok,true);await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(retry.data).requestId,privacyId);
  consentReply(retry);await settle();const service=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.equal(JSON.parse(service.data).requestId,serviceId);consentReply(service);await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
  const count=h.requests.filter(r=>r.url.endsWith('/consents')).length;
  h.app._usageConsent=null;await h.app.firstLogin();await settle();
  assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,count);assert.equal(h.logins.length,1);
});
test('only the failed service record is retried after partial readback and process reconstruction',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  consentReply(h.requests.find(r=>r.url.endsWith('/consents')));await settle();
  const service=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1),id=JSON.parse(service.data).requestId;
  reply(service,{code:500});await assert.rejects(attempt,/同意记录失败/);
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),['user_service_agreement']);
  h.app._usageConsent=null;h.app._consentClient=null;await h.app.firstLogin();await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.equal(JSON.parse(retry.data).docType,'user_service_agreement');assert.equal(JSON.parse(retry.data).requestId,id);
  consentReply(retry);await settle();assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('a late old-owner receipt cannot clear the new owner pending records or send its next document',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));
  h.app.getSession().setUserID('8');h.app.getSession().setAuthorization('fixture-eight');await h.app.firstLogin();await settle();
  const second=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.notEqual(JSON.parse(second.data).requestId,JSON.parse(first.data).requestId);
  const ownerEightId=h.storage.get('cy_usage_pending_v1:8').pending.privacy_policy.requestId;
  consentReply(first);await assert.rejects(attempt,/同意记录失败/);
  assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,2);
  assert.equal(h.storage.get('cy_usage_pending_v1:8').pending.privacy_policy.requestId,ownerEightId);
  assert.equal(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending).length,2);
  consentReply(second);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:8').pending),[]);
  assert.equal(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending).length,2);
});
test('same owner token replacement keeps pending despite an otherwise valid old-token readback',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents')),id=JSON.parse(first.data).requestId;
  h.app.getSession().setAuthorization('fixture-refreshed');consentReply(first);await assert.rejects(attempt,/同意记录失败/);
  assert.equal(h.storage.get('cy_usage_pending_v1:7').pending.privacy_policy.requestId,id);
  h.app._usageConsent=null;h.app._consentClient=null;await h.app.firstLogin();await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(retry.data).requestId,id);
  consentReply(retry);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('timed out recording remains pending and reconstructing retries the original UUID',async(t)=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents')),id=JSON.parse(first.data).requestId;
  const rejected=assert.rejects(attempt,/同意记录超时/);t.mock.timers.tick(10001);await rejected;
  assert.equal(h.app.getUsageConsent().granted(),true);assert.equal(h.storage.get('cy_usage_pending_v1:7').pending.privacy_policy.requestId,id);
  h.app._usageConsent=null;h.app._consentClient=null;await h.app.firstLogin();await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(retry.data).requestId,id);
  consentReply(retry);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('failed durable receipt update keeps the saved document pending for exact idempotent retry',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents')),id=JSON.parse(first.data).requestId;
  const set=h.wx.setStorageSync;h.wx.setStorageSync=(key,value)=>{if(key==='cy_usage_pending_v1:7'&&!value.pending.privacy_policy)throw new Error('storage unavailable');set(key,value);};
  consentReply(first);await assert.rejects(attempt,/storage unavailable/);
  assert.equal(h.storage.get('cy_usage_pending_v1:7').pending.privacy_policy.requestId,id);
  assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,1);
  h.wx.setStorageSync=set;h.app._usageConsent=null;h.app._consentClient=null;await h.app.firstLogin();await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(retry.data).requestId,id);
  consentReply(retry);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('refusal during pending receipt prevents clearing and no login writes until explicit agree again',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));h.app.declineUsageConsent();consentReply(first);
  await assert.rejects(attempt,/账号或选择已变化/);assert.equal(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending).length,2);
  await h.app.firstLogin();await settle();assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,1);
  const retry=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await retry;
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});

test('normal settings logout retains completed owner consent ledger without recording agreement again',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  consentReply(h.requests.find(r=>r.url.endsWith('/consents')));await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await attempt;
  const ledger=h.storage.get('cy_usage_pending_v1:7');
  assert.deepEqual(Object.keys(ledger.pending),[]);
  const count=h.requests.filter(r=>r.url.endsWith('/consents')).length;
  await logoutThroughSettings(h);
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger,'completed frozen-version receipt must survive logout');
  await reloginAfterLogout(h);
  assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,count,'logout must not turn a completed agreement into a new event');
});
test('normal settings logout resumes both pending consent documents with their original UUIDs',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));
  const ledger=h.storage.get('cy_usage_pending_v1:7');
  const privacyId=ledger.pending.privacy_policy.requestId,serviceId=ledger.pending.user_service_agreement.requestId;
  reply(first,{code:500,msg:'fixture consent unavailable'});await assert.rejects(attempt,/fixture consent unavailable/);
  await logoutThroughSettings(h);
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger,'failed write must retain the original durable identity');
  await reloginAfterLogout(h);
  const privacy=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(privacy.data).requestId,privacyId);
  consentReply(privacy);await settle();const service=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.equal(JSON.parse(service.data).requestId,serviceId);consentReply(service);await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('logout and another normal account login cannot consume the former owner consent ledger',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents')),ledger=h.storage.get('cy_usage_pending_v1:7');
  reply(first,{code:500,msg:'fixture consent unavailable'});await assert.rejects(attempt,/fixture consent unavailable/);
  await logoutThroughSettings(h);
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger);
  await reloginAfterLogout(h,'8');
  const privacy=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1),body=JSON.parse(privacy.data);
  assert.notEqual(body.requestId,ledger.pending.privacy_policy.requestId);
  assert.equal(h.storage.get('cy_usage_pending_v1:8').pending.privacy_policy.requestId,body.requestId);
  consentReply(privacy);await settle();const service=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);
  assert.notEqual(JSON.parse(service.data).requestId,ledger.pending.user_service_agreement.requestId);
  consentReply(service);await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:8').pending),[]);
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger);
  assert.equal(Object.keys(ledger.pending).length,2);
});
test('preserving an owner ledger on logout does not preserve the global choice or bypass a cold consent gate',async()=>{
  const h=mount();const attempt=agreeDuringLogin(h);await settle();await exchange(h);
  consentReply(h.requests.find(r=>r.url.endsWith('/consents')));await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await attempt;
  const ledger=h.storage.get('cy_usage_pending_v1:7');await logoutThroughSettings(h);
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger);
  h.app._usageConsent=null;h.app._consentClient=null;
  const shown=h.shown(),loginCount=h.logins.length,requestCount=h.requests.length,waiting=h.app.firstLogin();await settle();
  assert.equal(h.shown(),shown+1);
  assert.equal(h.logins.length,loginCount);
  assert.equal(h.requests.length,requestCount);
  h.app.declineUsageConsent();await waiting;
  assert.equal(h.storage.get('cy_usage_pending_v1:7'),ledger);
});
