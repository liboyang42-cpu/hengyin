const test=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function mount(kind){let owner='7',definition;const requests=[];const app={globalData:{},getUserID:()=>owner,sendRequest:r=>requests.push(r)};
 global.getApp=()=>app;global.wx={setNavigationBarColor(){},setBackgroundColor(){},getStorageSync:()=>'',getWindowInfo:()=>({statusBarHeight:20}),getSystemInfoSync:()=>({statusBarHeight:20}),disableAlertBeforeUnload(){}};global.getCurrentPages=()=>[];global.Page=d=>definition=d;
 const route=kind==='predict'?'predict':'citynode/create';const file=path.resolve(__dirname,'../../pages/merchant/'+route+'/index.js');delete require.cache[file];require(file);
 const page=Object.assign({},definition,{data:JSON.parse(JSON.stringify(definition.data)),setData(p){Object.assign(this.data,p);}});
 return {page,app,requests,owner(id){owner=id;}};
}
const flush=()=>new Promise(r=>setImmediate(r));
for(const kind of ['predict','citynode']){
 const key=kind==='predict'?'pageState':'accessState';
 test(kind+' anonymous entry does not query merchant access; explicit login reuses real access check',async()=>{
  const h=mount(kind);h.owner('');h.page.loadAccess();assert.equal(h.requests.length,0);assert.equal(h.page.data[key],'login');let calls=0;
  h.app.firstLogin=()=>{calls++;h.owner('9');return Promise.resolve();};h.page.onLogin();h.page.onLogin();await flush();assert.equal(calls,1);assert.equal(h.requests.length,1);assert.equal(h.requests[0].url,'/api/merchant/access/me');
 });
 test(kind+' real HTTP401/403 and parameter400/422 remain visible; ordinary500 is read-failure',()=>{
  for(const code of [400,401,403,422,500]){const h=mount(kind);h.page.loadAccess();h.requests[0].successStatusAbnormal({statusCode:code,msg:'真实响应'},code);assert.equal(h.page.data[key],code===401?'login':code===403?'no-permission':'error');assert.equal(h.page.data.accessErrorIsDenied,[400,422].includes(code));}
 });
 test(kind+' malformed success cannot impersonate a real denial or launch privileged merchant reads',()=>{
  const h=mount(kind);h.page.loadAccess();h.requests[0].success({code:200,data:null});assert.equal(h.page.data[key],'error');assert.equal(h.requests.length,1);
 });
 test(kind+' account switch clears private fields and rejects access response from previous member',()=>{
  const h=mount(kind);h.page.loadAccess();const old=h.requests[0];if(kind==='predict')h.page.data.items=[{rid:8}];else {h.page.data.tpl={id:8};h.page.data.picked={name:'旧店'};}
  h.owner('');h.page.onShow();old.success({code:200,data:{active:true,canManageProjects:true}});assert.equal(h.page.data[key],'login');
  if(kind==='predict')assert.equal(h.page.data.items.length,0);else {assert.equal(h.page.data.tpl,null);assert.equal(h.page.data.picked,null);}
 });
}
