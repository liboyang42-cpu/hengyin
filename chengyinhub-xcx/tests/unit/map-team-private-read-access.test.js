'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
function mount(initialUid) {
 const file=path.resolve(__dirname,'../../subpackageRoam/nearby/index.js'); let def,uid=initialUid;
 const requests=[];const app={globalData:{authorization:'a'},getUserID:()=>uid,sendRequest:r=>requests.push(r),
 firstLogin:()=>{uid=7;return Promise.resolve({ok:true});}};
 vm.runInNewContext(fs.readFileSync(file,'utf8'),{getApp:()=>app,Page:d=>{def=d;},require:createRequire(file),wx:{}});
 const page=Object.assign({},def,{data:JSON.parse(JSON.stringify(def.data)),setData(d){Object.assign(this.data,d);}});
 return {page,requests,switchUid:v=>{uid=v;},app};
}
test('游客我的队伍有登录入口，不请求私有列表或显示假0',async()=>{
 const h=mount('');h.page.loadMyTeams(false);assert.equal(h.requests.length,0);assert.equal(h.page.data.myReadAccess,'login');
 assert.equal(h.page.data.myTeamsN,0);assert.equal(h.page.data.myLoaded,false);
 await h.page.loginForMyTeams();assert.equal(h.requests.length,2);
});
test('我的队伍401/403立即清内容，其余失败不是无队伍，异常HTTP解除loading',()=>{
 for(const callback of ['success','fail','successStatusAbnormal']) for(const code of [401,403,500]){
  const h=mount(7);h.page.loadMyTeams(false);h.page.data.myRows=[{key:'private'}];
  for(const req of h.requests) req[callback]({code},callback==='success'?undefined:code);
  assert.equal(h.page.data.myLoading,false);assert.equal(h.page.data.myReadAccess,code===401?'login':code===403?'denied':'');
  if(code===500){assert.equal(h.page.data.myReadError,true);assert.equal(h.page.data.myLoaded,false);}
  else assert.equal(h.page.data.myRows.length,0);
 }
});
test('账号切换后旧双请求不能覆盖新队伍或改成无权限',()=>{
 const h=mount(7);h.page.loadMyTeams(false);const old=h.requests.slice();
 h.switchUid(8);h.page.refresh=()=>{};h.page.onShow();assert.equal(h.requests.length,4);
 old[0].success({code:200,data:[]});old[1].fail({code:403},403);assert.equal(h.page.data.myReadAccess,'');
 for(const req of h.requests.slice(2))req.success({code:200,data:[]});
 assert.equal(h.page.data.myLoaded,true);assert.equal(h.page.data.myReadError,false);
});
test('只读到一个列表不把部分数据说成完整空态；退出后登录不继续读取',async()=>{
 const h=mount(7);h.page.loadMyTeams(false);h.requests[0].success({code:200,data:[]});h.requests[1].fail();
 assert.equal(h.page.data.myLoaded,false);assert.equal(h.page.data.myReadError,true);assert.equal(h.page.data.myTeamsN,0);
 const g=mount('');let resolve;g.app.firstLogin=()=>new Promise(r=>{resolve=r;});const p=g.page.loginForMyTeams();
 g.page.onUnload();g.switchUid(7);resolve({ok:true});await p;assert.equal(g.requests.length,0);
});
