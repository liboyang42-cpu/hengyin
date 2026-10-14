'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { createSessionStore } = require('../../utils/session/session-store.js');
const { createSessionManager } = require('../../utils/session/session-manager.js');
const root = path.resolve(__dirname, '../..');
function mount() {
  let app, def; const requests = [], logins = [], storage = new Map();
  const wx = { request: r => { requests.push(r); return { abort() {} }; }, login: r => logins.push(r),
    getStorageSync: k => storage.get(k), setStorageSync: (k,v) => storage.set(k,v), removeStorageSync: k => storage.delete(k),
    showToast() {}, showLoading() {}, hideLoading() {}, hideToast() {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App: value => { app = value; }, wx, console,
    require: f => f === './utils/toast.js' ? Object.assign(() => {}, { hide() {} }) : require(path.join(root,f)) });
  const session = createSessionStore({target: app.globalData, wx});
  app.getSession = () => session; app.isDevEnv = () => false; app.loadFeatureFlags = () => {};
  session.setUserID('7'); session.setAuthorization('fixture-seven'); app.globalData.siteBaseUrl = 'http://127.0.0.1:8129';
  app.getSessionManager = () => createSessionManager({session, isDevEnv: () => false,
    wxLogin: async () => ({code: 'renewal'}), exchangeCode: async () => ({code:401}) });
  global.getApp = () => app; global.wx = wx;
  delete require.cache[require.resolve('../../utils/deregister-flow.js')];
  const file = path.join(root, 'components/cy/scene-settings-deregister/index.js');
  vm.runInNewContext(fs.readFileSync(file,'utf8'), {Component: value => { def = value; }, require:createRequire(file), console});
  const page = Object.assign({}, def.methods, {data: JSON.parse(JSON.stringify(def.data)),
    setData(patch, callback) {Object.assign(this.data,patch); if(callback) callback();} });
  return {app, def, page, requests, logins, session};
}
function reply(r,statusCode,data) {r.success({statusCode,data}); if(r.complete) r.complete({statusCode,data});}
async function settle() {for(let i=0;i<40;i++) await Promise.resolve();}
function precheck(h,method='WECHAT') {
  h.def.lifetimes.attached.call(h.page);
  reply(h.requests[0],200,{code:200,data:{status:'NORMAL'}});
  reply(h.requests[1],200,{code:200,data:{status:'ELIGIBLE',blockers:[],verificationMethod:method}});
}
test('real component status and precheck use actual App transport and explicitly allow fresh WeChat only', async () => {
  const h=mount(); precheck(h); assert.equal(h.page.data.verificationMethod,'WECHAT');
  h.app.recordConsent=async () => {}; h.page.toggleConfirmed({detail:{value:['confirmed']}});
  assert.equal(h.page.data.canApply,true); h.page.apply(); await settle();
  assert.equal(h.logins.length,1); assert.equal(h.requests.length,2);
  h.logins[0].success({code:'fresh-once'}); await settle();
  const submit=h.requests.at(-1); assert.match(submit.url,/\/deregister\/apply$/);
  assert.equal(submit.data.wxcode,'fresh-once'); assert.equal(submit.data.smscode,undefined);
  reply(submit,200,{code:200,data:{status:'PENDING'}});
  assert.equal(h.page.data.status,'PENDING'); assert.equal(h.page.data.canApply,false);
  h.page.apply(); await settle(); assert.equal(h.logins.length,1);
});
test('actual transport owned 401 clears identity and old WeChat eligibility, while 403 stays denied', async () => {
  const h=mount(); precheck(h); h.page.loadPrecheck();
  reply(h.requests.at(-1),401,{code:401}); await settle();
  assert.equal(String(h.app.getUserID()||''),''); assert.equal(h.page.data.status,'LOGIN_REQUIRED');
  assert.equal(h.page.data.verificationMethod,'UNAVAILABLE'); assert.equal(h.page.data.canApply,false);
  const denied=mount(); precheck(denied); denied.page.loadPrecheck(); reply(denied.requests.at(-1),403,{code:403});
  assert.equal(denied.page.data.status,'DENIED'); assert.equal(denied.page.data.verificationMethod,'UNAVAILABLE');
});
test('missing legacy verification field means SMS; invalid and PENDING modes cannot authorize submission', () => {
  const legacy=mount(); precheck(legacy,null); assert.equal(legacy.page.data.verificationMethod,'SMS');
  legacy.page.toggleConfirmed({detail:{value:['confirmed']}}); assert.equal(legacy.page.data.canApply,false);
  legacy.page.inputCode({detail:{value:'123456'}}); assert.equal(legacy.page.data.canApply,true);
  const invalid=mount(); precheck(invalid,'ANY'); invalid.page.toggleConfirmed({detail:{value:['confirmed']}});
  invalid.page.inputCode({detail:{value:'123456'}}); assert.equal(invalid.page.data.canApply,false); invalid.page.apply();
  assert.equal(invalid.requests.length,2); assert.equal(invalid.logins.length,0);
});
for (const leave of ['hide','detach','actor','token']) test('late fresh WeChat callback cannot submit after '+leave,async()=>{
  const h=mount(); precheck(h); h.app.recordConsent=async()=>{};
  h.page.toggleConfirmed({detail:{value:['confirmed']}}); h.page.apply(); await settle();
  if(leave==='hide') h.def.pageLifetimes.hide.call(h.page);
  if(leave==='detach') h.def.lifetimes.detached.call(h.page);
  if(leave==='actor') h.session.setUserID('8');
  if(leave==='token') h.session.setAuthorization('changed-seven');
  const snapshot=JSON.stringify(h.page.data); h.logins[0].success({code:'late'}); await settle();
  assert.equal(h.requests.length,2); assert.equal(JSON.stringify(h.page.data),snapshot);
  if(leave==='hide') {h.def.pageLifetimes.show.call(h.page); assert.equal(h.requests.length,3); assert.equal(h.page.data.submitting,false);}
});
test('fresh login failure preserves confirmation and next attempt obtains another code',async()=>{
  const h=mount(); precheck(h); h.app.recordConsent=async()=>{}; h.page.toggleConfirmed({detail:{value:['confirmed']}});
  h.page.apply(); await settle(); h.logins[0].fail({errMsg:'fixture failed'}); await settle();
  assert.equal(h.page.data.submitting,false); assert.equal(h.page.data.confirmed,true); assert.match(h.page.data.applyError,/微信/);
  h.page.apply(); await settle(); assert.equal(h.logins.length,2); h.logins[1].fail({}); await settle();
});

// F-R2S-07:注销流程三处恢复缺口。
async function submitWechat(h) {
  h.app.recordConsent = async () => {}; h.page.toggleConfirmed({detail:{value:['confirmed']}});
  h.page.apply(); await settle(); h.logins[0].success({code:'fresh'}); await settle();
  return h.requests.at(-1);
}
test('F-R2S-07 apply response lost: reads back /status and shows PENDING instead of asking to retry', async () => {
  const h = mount(); precheck(h);
  const submit = await submitWechat(h); assert.match(submit.url, /\/deregister\/apply$/);
  submit.fail({errMsg:'request:fail timeout'}); if (submit.complete) submit.complete({}); await settle();
  const readback = h.requests.at(-1); assert.match(readback.url, /\/deregister\/status$/);
  reply(readback, 200, {code:200, data:{status:'PENDING', executeAfter: Date.now() + 86400000}}); await settle();
  assert.equal(h.page.data.status, 'PENDING'); assert.equal(h.page.data.applyError, '');
  assert.equal(h.page.data.coolingOffEnded, false);
});
test('F-R2S-07 apply failure that did not take effect keeps the form and the visible error', async () => {
  const h = mount(); precheck(h);
  const submit = await submitWechat(h);
  reply(submit, 502, {msg:'bad gateway'}); await settle();
  const readback = h.requests.at(-1); assert.match(readback.url, /\/deregister\/status$/);
  reply(readback, 200, {code:200, data:{status:'NORMAL'}}); await settle();
  assert.equal(h.page.data.status, 'ELIGIBLE'); assert.equal(h.page.data.confirmed, true);
  assert.match(h.page.data.applyError, /服务暂时不可用/);
});
test('F-R2S-07 BLOCKED card offers an in-place recheck', () => {
  const h = mount(); h.def.lifetimes.attached.call(h.page);
  reply(h.requests[0],200,{code:200,data:{status:'NORMAL'}});
  reply(h.requests[1],200,{code:200,data:{status:'BLOCKED',blockers:['存在待支付或未核销的有效报名']}});
  assert.equal(h.page.data.status, 'BLOCKED');
  h.page.recheck(); assert.match(h.requests.at(-1).url, /\/deregister\/status$/);
  reply(h.requests.at(-1),200,{code:200,data:{status:'NORMAL'}});
  reply(h.requests.at(-1),200,{code:200,data:{status:'ELIGIBLE',blockers:[],verificationMethod:'SMS'}});
  assert.equal(h.page.data.status, 'ELIGIBLE');
  const wxml = fs.readFileSync(path.join(root, 'components/cy/scene-settings-deregister/index.wxml'), 'utf8');
  assert.match(wxml, /bindtap="recheck">我已处理，重新检查</);
});
test('F-R2S-07 after cooling-off ends but before the batch runs, cancel stays available', () => {
  const h = mount(); h.def.lifetimes.attached.call(h.page);
  reply(h.requests[0],200,{code:200,data:{status:'PENDING', executeAfter: Date.now() - 60000}});
  assert.equal(h.page.data.status, 'PENDING'); assert.equal(h.page.data.coolingOffEnded, true);
  let modals = 0; h.page.data.canceling = false;
  const realShow = require('../../utils/modal.js').show;
  require('../../utils/modal.js').show = () => { modals++; };
  try { h.page.cancel(); } finally { require('../../utils/modal.js').show = realShow; }
  assert.equal(modals, 1, '冷静期满但批次未执行时仍可发起撤销确认');
  const wxml = fs.readFileSync(path.join(root, 'components/cy/scene-settings-deregister/index.wxml'), 'utf8');
  assert.match(wxml, /<cy-btn class="deregister-action"[^>]*bindtap="cancel">撤销注销申请/);
  assert.doesNotMatch(wxml, /coolingOffEnded\}\}"[^>]*bindtap="cancel"/);
  assert.match(wxml, /冷静期已结束，账号将在随后的凌晨批次中完成注销；完成前仍可撤销/);
});
