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
    request:r=>{requests.push(r);return{abort(){}};},login:r=>logins.push(r),showLoading(){},hideLoading(){},hideToast(){}};
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
test('guest initialization finishes and public reads work before any native login or identity exchange',async()=>{
  const h=mount();await h.app.appInitial({});
  assert.equal(h.logins.length,0);assert.equal(h.requests.some(r=>r.url.endsWith('/api/login/code')),false);
  h.app.sendRequest({url:'/api/topic/list',method:'GET',auth:false,success(){}});
  assert.ok(h.requests.some(r=>r.url.endsWith('/api/topic/list')));
  h.app.declineUsageConsent();await h.app.appInitial({});assert.equal(h.logins.length,0);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));reply(first,{code:200});await assert.rejects(attempt,/同意记录失败/);
  const id=JSON.parse(first.data).requestId;h.app._consentClient=null;
  const retry=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  const second=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(second.data).requestId,id);
  consentReply(second);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await retry;
  assert.equal(h.logins.length,1);
});
test('actor switch during consent readback cannot make a foreign owner agreement successful',async()=>{
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents')),id=JSON.parse(first.data).requestId;
  const rejected=assert.rejects(attempt,/同意记录超时/);t.mock.timers.tick(10001);await rejected;
  assert.equal(h.app.getUsageConsent().granted(),true);assert.equal(h.storage.get('cy_usage_pending_v1:7').pending.privacy_policy.requestId,id);
  h.app._usageConsent=null;h.app._consentClient=null;await h.app.firstLogin();await settle();
  const retry=h.requests.filter(r=>r.url.endsWith('/consents')).at(-1);assert.equal(JSON.parse(retry.data).requestId,id);
  consentReply(retry);await settle();consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
test('failed durable receipt update keeps the saved document pending for exact idempotent retry',async()=>{
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
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
  const h=mount();const attempt=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();await exchange(h);
  const first=h.requests.find(r=>r.url.endsWith('/consents'));h.app.declineUsageConsent();consentReply(first);
  await assert.rejects(attempt,/账号或选择已变化/);assert.equal(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending).length,2);
  await h.app.firstLogin();await settle();assert.equal(h.requests.filter(r=>r.url.endsWith('/consents')).length,1);
  const retry=h.app.agreeUsageConsent({detail:{buttonId:'agree'}},true);await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await settle();
  consentReply(h.requests.filter(r=>r.url.endsWith('/consents')).at(-1));await retry;
  assert.deepEqual(Object.keys(h.storage.get('cy_usage_pending_v1:7').pending),[]);
});
