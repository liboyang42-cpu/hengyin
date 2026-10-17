'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const file='../../pages/topic/merchantinfo/merchantinfo.js';
function load() {
  let config, uid='7'; const sent=[],tips=[];
  const app={globalData:{},getUserID:()=>uid,sendRequest:r=>sent.push(r),tips:s=>tips.push(s),getRequestErrorMessage:(r,f)=>r&&r.msg||f};
  global.getApp=()=>app; global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({}),navigateTo:()=>{},showToast:()=>{}};
  global.Page=c=>{config=c};delete require.cache[require.resolve(file)];require(file);
  const page=Object.assign({},config,{data:JSON.parse(JSON.stringify(config.data))});
  page.setData=patch=>{for(const [key,value]of Object.entries(patch)){const parts=key.split('.');let at=page.data;for(const p of parts.slice(0,-1)){at[p]=at[p]||{};at=at[p]}at[parts.at(-1)]=value}};
  page.data.topicId=10;page.data.role='host';page.data.info={canResubmitSupplyReview:true};page.closeMoreSheet=()=>{};
  let reloads=0;page.loadProjectHome=()=>{reloads++};
  return {page,sent,tips,changeOwner:()=>{uid='8'},reloads:()=>reloads};
}
test('real owner menu exposes resubmit only on exact server cancelled-review proof',()=>{
 const h=load();let rows=h.page.buildMoreGroups().groups.flatMap(g=>g.visibleRows);
 assert.ok(rows.some(r=>r.key==='supplyReview'));
 h.page.data.info.canResubmitSupplyReview='true';rows=h.page.buildMoreGroups().groups.flatMap(g=>g.visibleRows);
 assert.ok(!rows.some(r=>r.key==='supplyReview'));
 h.page.resubmitSupplyReview();assert.equal(h.sent.length,0);
 h.page.data.info.canResubmitSupplyReview=true;h.page.data.role='join';h.page.resubmitSupplyReview();assert.equal(h.sent.length,0);
});
test('resubmit uses original topic once and reports pending without reopening signup',()=>{
 const h=load();h.page.resubmitSupplyReview();h.page.resubmitSupplyReview();assert.equal(h.sent.length,1);
 const r=h.sent[0];assert.equal(r.url,'/api/topic/publish-draft');assert.equal(r.method,'POST');assert.equal(r.retry,false);assert.equal(r.data.id,10);assert.ok(!('userStatus' in r.data));
 r.success({code:200,data:{reviewState:'PENDING'}});r.complete();assert.equal(h.reloads(),1);assert.match(h.tips[0],/报名保持暂停/);
});
test('failure and stale actor never report submitted or reopen signup',()=>{
 const h=load();h.page.resubmitSupplyReview();h.sent[0].success({code:500,msg:'供给尚未补足'});h.sent[0].complete();assert.equal(h.reloads(),0);assert.deepEqual(h.tips,['供给尚未补足']);
 h.page.resubmitSupplyReview();h.changeOwner();h.sent[1].success({code:200,data:{reviewState:'PENDING'}});h.sent[1].complete();assert.equal(h.reloads(),0);assert.equal(h.tips.length,1);
});
