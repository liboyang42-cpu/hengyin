const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function mount() {
  let owner='7', definition;
  const requests=[];
  const app={getUserID:()=>owner,getAuthorization:()=>owner?'Bearer unit-fixture':'',getRequestErrorMessage:(res,fallback)=>res&&res.msg||fallback,
    sendRequest:o=>{requests.push(o);return {aborted:false,abort(){this.aborted=true;}};}};
  global.getApp=()=>app;global.Page=d=>definition=d;global.wx={};
  const file=path.resolve(__dirname,'../../subpackageMember/complaint/index.js');delete require.cache[file];require(file);
  const page=Object.assign({},definition,{data:JSON.parse(JSON.stringify(definition.data)),setData(patch){Object.assign(this.data,patch);}});
  return {page,requests,owner(id){owner=id;}};
}
test('complaint progress uses actual private POST without actor parameter and only public progress fields',()=>{
  const h=mount();h.page.loadHistory();assert.equal(h.requests[0].url,'/api/coop/complaint/mine');assert.equal(h.requests[0].method,'POST');assert.equal(h.requests[0].data,undefined);
  h.requests[0].success({code:200,data:[{id:4,topicName:'我的活动',statusText:'处理中',reason:'我的投诉',handleNote:'处理中说明',responsibleMemberId:99,internalDeductAmount:200}]});
  h.requests[0].complete();assert.equal(h.page.data.history[0].statusText,'处理中');assert.equal(h.page.data.historyLoading,false);
  assert.equal(h.page.data.history[0].responsibleMemberId,undefined);assert.equal(h.page.data.history[0].internalDeductAmount,undefined);
});
test('complaint read network/500 preserves old history; true 401/403/parameter failures stay visible and clear private cache',()=>{
  for(const fail of ['network',500]){
    const h=mount();h.page._historyOwner='7';h.page.data.history=[{id:8,topicName:'旧记录'}];h.page.loadHistory();
    if(fail==='network')h.requests[0].fail({errMsg:'offline'});else h.requests[0].success({code:500,msg:'不可用'});
    h.requests[0].complete();assert.equal(h.page.data.history[0].id,8);assert.equal(h.page.data.historyAccessError,'');assert.equal(h.page.data.historyLoading,false);
  }
  for(const code of [400,401,403,422]){
    const h=mount();h.page.data.history=[{id:8}];h.page.loadHistory();h.requests[0].success({code,msg:'真实拒绝'});
    assert.equal(h.page.data.history.length,0);assert.equal(h.page.data.historyAccessError,'真实拒绝');
  }
});
test('anonymous/login abnormal have visible prompt and no private records',()=>{
  const h=mount();h.owner('');h.page.loadHistory();assert.equal(h.requests.length,0);assert.equal(h.page.data.historyAccessError,'请登录后查看处理进展');
  h.owner('7');h.page.loadHistory();h.requests[0].successStatusAbnormal({code:401,msg:'请重新登录'});assert.equal(h.page.data.historyAccessError,'请重新登录');
});
test('complaint older request, changed account and unloaded callbacks cannot replace current history',()=>{
  const h=mount();h.page.loadHistory();h.page.loadHistory();h.requests[1].success({code:200,data:[{id:2}]});
  h.requests[0].success({code:200,data:[{id:1}]});assert.equal(h.page.data.history[0].id,2);
  h.owner('99');h.requests[1].success({code:200,data:[{id:3}]});assert.equal(h.page.data.history[0].id,2);
  h.page.loadHistory();h.page.onUnload();h.requests[2].success({code:200,data:[{id:4}]});assert.equal(h.page.data.history.length,0);
});
test('club join reason is bound only for own rejected join status and not other applicants',()=>{
  const fs=require('node:fs');const wxml=fs.readFileSync(path.resolve(__dirname,'../../pages/club/detail/index.wxml'),'utf8');
  assert.match(wxml,/wx:if="\{\{!club\.isJoined && !club\.isOwner && club\.myJoinStatus === 2 && club\.myJoinReviewReason\}\}"[^>]*>申请未通过：\{\{club\.myJoinReviewReason\}\}/);
  assert.doesNotMatch(wxml,/item\.myJoinReviewReason/);
});
