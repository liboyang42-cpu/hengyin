'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');
const INSIGHT = 'pages/merchant/marketing/ai-insight/index.js';
const APPLY = 'pages/topic/merchantapply/index.js';
const SEARCH = 'pages/search2/result/index.js';
const tick = () => new Promise(resolve => setImmediate(resolve));
function mount(relative, initialOwner = '7') {
  let definition, owner = initialOwner;
  const requests = [], logins = [];
  const app = { globalData: {}, getUserID: () => owner, getImgUrl: value => value,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    sendRequest(options) { requests.push(options); },
    firstLogin: () => new Promise(resolve => logins.push(resolve)),
    reLogin: () => new Promise(resolve => logins.push(resolve)) };
  const wx = { getSystemInfoSync: () => ({statusBarHeight:20}), getStorageSync() {},
    setNavigationBarColor() {}, setBackgroundColor() {}, getWindowInfo: () => ({statusBarHeight:20}) };
  global.wx = wx; global.getApp = () => app;
  const filename = path.join(ROOT, relative);
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    Page(value) { definition = value; }, getApp: () => app, wx,
    require(name) {
      if (name.includes('merchant-theme')) return { merchantPageShow() {}, merchantPageRestore() {} };
      if (name.includes('toast')) return Object.assign(() => {}, {success() {}});
      return require(path.resolve(path.dirname(filename), name));
    }, setTimeout, clearTimeout, getCurrentPages: () => [], console,
  });
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); } };
  return { page, app, requests, logins, owner(value) { owner = value; } };
}
test('店铺参谋匿名不读取私有经营数据，登录提示可见且可恢复', async () => {
  const h = mount(INSIGHT, ''); h.page.load();
  assert.equal(h.requests.length, 0); assert.equal(h.page.data.errorIsDenied, true);
  h.page.retry(); h.owner('7'); h.logins[0]({ok:true}); await tick();
  assert.equal(h.requests.length, 1);
});
for (const code of [401,403]) test(`店铺参谋真实${code}可见并清旧客户资料`, () => {
  const h = mount(INSIGHT); h.page.data.recentVisitors = [{name:'旧顾客'}]; h.page.load();
  h.requests[0].successStatusAbnormal({code,msg:'真实身份拒绝'},code);
  assert.equal(h.page.data.errorIsDenied,true); assert.equal(h.page.data.errorNeedsLogin,code===401);
  assert.equal(h.page.data.recentVisitors.length,0);
});
test('店铺参谋隐藏/卸载/换号后旧回包与登录恢复均不能重新读取或覆盖', async () => {
  for (const action of ['onHide','onUnload','owner']) {
    const h = mount(INSIGHT); h.page.load();
    if(action==='owner')h.owner('8');else h.page[action]();
    const before=JSON.stringify(h.page.data);h.requests[0].fail({code:403},403);
    assert.equal(JSON.stringify(h.page.data),before);
  }
  const h=mount(INSIGHT,'');h.page.load();h.page.retry();h.page.onHide();h.owner('7');h.logins[0]({ok:true});await tick();assert.equal(h.requests.length,0);
});
test('商家报名匿名不初始化本人门店或私有报名，提供登录入口',()=>{
 const h=mount(APPLY,'');h.page.onLoad({id:'12'});assert.equal(h.requests.length,0);assert.equal(h.page.data.loadState,'login');
});
test('商家报名门店旧回包不能跨账号/隐藏/卸载写入，当前本人仍正常回填',()=>{
 for(const action of ['owner','onHide','onUnload']){
  const h=mount(APPLY);h.page.loadMyShop();if(action==='owner')h.owner('8');else h.page[action]();
  h.requests[0].success({code:200,data:{name:'旧账号门店',address:'地址'}});assert.equal(h.page.data.shop.name,'');
 }
 const h=mount(APPLY);h.page.loadMyShop();h.requests[0].success({code:200,data:{name:'本人门店'}});assert.equal(h.page.data.shop.name,'本人门店');
});
test('商家报名登录恢复只初始化当前前台，真实403明确权限状态',async()=>{
 const h=mount(APPLY,'');h.page.onLoad({});h.page.retryLoadRegistration();h.owner('7');h.logins[0]({ok:true});await tick();assert.ok(h.requests.some(r=>r.url==='/api/merchant/info'));
 const p=mount(APPLY);p.page.loadMyShop();p.requests[0].successStatusAbnormal({code:403,msg:'无门店权限'},403);assert.equal(p.page.data.loadState,'permission');
});
test('游客搜索保留主题/活动/公开商家，不发需要登录的俱乐部请求',()=>{
 const h=mount(SEARCH,'');h.page.runSearch();assert.equal(h.requests.length,3);assert.ok(h.requests.every(r=>r.auth===false));assert.ok(!h.requests.some(r=>r.url==='/api/club/list'));
 for(const r of h.requests)r.success({code:200,data:[]});h.page.onUnload();
});
test('登录搜索仍包含俱乐部；账号/隐藏变化丢弃旧响应并保留当前公开搜索',()=>{
 for(const action of ['owner','onHide','onUnload']){
  const h=mount(SEARCH);h.page.runSearch();assert.equal(h.requests.length,6);if(action==='owner')h.owner('8');else h.page[action]();
  const before=JSON.stringify(h.page.data);for(const r of h.requests)r.success({code:200,data:[]});assert.equal(JSON.stringify(h.page.data),before);h.page.onUnload();
 }
});
test('商家报名本人续期失败清空会话后清旧门店，出现可恢复登录态；外部会话更换读当前本人',()=>{
 const h=mount(APPLY);h.page.loadMyShop();h.page.data.shop={name:'旧门店'};h.owner('');h.requests[0].fail({code:401},401);
 assert.equal(h.page.data.shop.name,'');assert.equal(h.page.data.loadState,'login');assert.equal(h.requests.length,1);
 const p=mount(APPLY);p.page.loadMyShop();p.owner('8');p.requests[0].fail({reason:'sessionChanged'});
 assert.equal(p.requests.length,2);assert.equal(p.page.data.shop.name,'');
});
