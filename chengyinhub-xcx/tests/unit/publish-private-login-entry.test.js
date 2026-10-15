const test=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function mount(name){
 let owner='',definition;const requests=[],navigations=[];
 const app={globalData:{},getUserID:()=>owner,getUserRole:()=>'',getUserType:()=>0,getNickname:()=>'',getAvatar:()=>'',getAuthorization:()=>'',sendRequest:r=>requests.push(r)};
 global.getApp=()=>app;global.wx={switchTab:o=>navigations.push(o.url),getStorageSync:()=>'',getWindowInfo:()=>({statusBarHeight:20}),getMenuButtonBoundingClientRect:()=>({top:24,height:32})};global.Page=d=>definition=d;
 const file=path.resolve(__dirname,'../../pages/publish/'+name+'/index.js');delete require.cache[file];require(file);
 const page=Object.assign({},definition,{data:JSON.parse(JSON.stringify(definition.data)),setData(p){Object.assign(this.data,p);}});
 return {page,app,requests,navigations,owner(id){owner=id;}};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
for(const name of ['simple','fabu','temp','activity']){
 test(name+' anonymous deep link exposes explicit login and sends no bootstrap private reads',()=>{
  const h=mount(name);h.page.onLoad({id:'77',mode:'2'});assert.equal(h.requests.length,0);
  assert.equal(name==='activity'?h.page.data.accessState:h.page.data.creatorLoginRequired,name==='activity'?'login':true);
 });
 test(name+' explicit login is single-flight and resumes the existing initializer with original route',async()=>{
  const h=mount(name);const options={id:'77',mode:'2'};h.page.onLoad(options);let calls=0,loads=0,received;
  h.app.firstLogin=()=>{calls++;h.owner('9');return Promise.resolve();};
  if(name==='activity')h.page.retryAccessCheck=()=>{loads++;};else h.page.onLoad=route=>{loads++;received=route;};
  h.page.onCreatorLogin();h.page.onCreatorLogin();await flush();assert.equal(calls,1);assert.equal(loads,1);
  if(name!=='activity')assert.deepEqual(received,options);
 });
 test(name+' initial login completing after unload cannot resume editor initialization',async()=>{
  const h=mount(name);h.app.isAppReady=false;let resolve;h.app.waitForAppReady=()=>new Promise(r=>resolve=r);
  h.page.onLoad({id:'77'});h.page._unloaded=true;h.owner('9');resolve();await flush();assert.equal(h.requests.length,0);
 });
}
test('activity actual HTTP401 and403 stay visible while ordinary500 remains read-error and former-account response is ignored',()=>{
 for(const status of [401,403,500]){const h=mount('activity');h.owner('9');h.page.guardPublisherRole(()=>{});h.requests[0].successStatusAbnormal({statusCode:status},status);assert.equal(h.page.data.accessState,status===401?'login':status===403?'denied':'error');}
 const h=mount('activity');h.owner('9');let allowed=false;h.page.guardPublisherRole(()=>allowed=true);h.owner('99');h.requests[0].success({code:200,data:{role:'club'}});assert.equal(allowed,false);
});

for(const name of ['fabu','temp']) {
 test(name+' actual private detail rejects expose login/permission/parameters; generic500 stays read-failure',()=>{
  for(const code of [400,401,403,422,500]) {
   const h=mount(name);h.owner('7');
   if(name==='fabu')h.page.loadEditingTopic('77');else {h.page._templateId=77;h.page._editorMemberId='7';h.page.data.isMyScope=true;h.page.getData();}
   assert.equal(h.requests.length,1);h.requests[0].successStatusAbnormal({statusCode:code,msg:'真实响应'},code);
   assert.equal(h.page.data.creatorLoginRequired,code===401);
   assert.equal(name==='fabu'?h.page.data.editLoadIsAccess:h.page.data.loadErrorIsAccess,[400,403,422].includes(code));
   assert.equal(name==='fabu'?h.page.data.editLoadError:h.page.data.loadError,'真实响应');
  }
 });
 test(name+' private detail callback from previous owner cannot change the current editor',()=>{
  const h=mount(name);h.owner('7');if(name==='fabu')h.page.loadEditingTopic('77');else {h.page._templateId=77;h.page._editorMemberId='7';h.page.getData();}
  h.owner('99');h.requests[0].successStatusAbnormal({statusCode:403,msg:'旧账号权限'},403);
  assert.equal(name==='fabu'?h.page.data.editLoadIsAccess:h.page.data.loadErrorIsAccess,false);
 });
}
for(const name of ['fabu','simple','temp']) {
 test(name+' account switch hides the previous private editor and returns to existing template entry',()=>{
  const h=mount(name);h.page._creatorOwner='7';h.owner('99');h.page.onShow();
  assert.equal(h.page.data.creatorLoginRequired,true);assert.deepEqual(h.navigations,['/pages/template/index']);
 });
}
