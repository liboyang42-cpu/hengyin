const {test}=require('node:test');const assert=require('node:assert/strict');
const {createServerDraftSaver}=require('../../pages/publish/utils/publish/server-draft');
const payload={name:'原稿',configVersion:'3',chapters:[],imgUrl:'approved.jpg'};
const draft=()=>({topic:{id:501,status:0,name:'原稿',configVersion:'4',imgUrl:'approved.jpg'},isDraft:true,chapters:[]});
function begin(){const requests=[],results=[];const saver=createServerDraftSaver({save:(p,cb)=>requests.push({kind:'save',cb}),readback:(id,cb)=>requests.push({kind:'read',cb}),publish:(id,cb)=>requests.push({kind:'publish',cb})});saver.saveAndPublish(payload,{onSuccess:r=>results.push({ok:true,...r}),onFail:r=>results.push({ok:false,...r})});requests[0].cb({ok:true,data:501});requests[1].cb({ok:true,data:draft()});return {requests,results,saver};}
test('版本化发布成功回包后必须再次回读同版本和内容，不提前清草稿',()=>{
 const h=begin();h.requests[2].cb({ok:true,data:{topicId:501,published:true}});assert.equal(h.results.length,0);assert.equal(h.requests[3].kind,'read');
 const back=draft();back.isDraft=false;back.topic.status=1;h.requests[3].cb({ok:true,data:back});assert.equal(h.results[0].ok,true);assert.equal(h.results[0].detail.isDraft,false);
});
test('发布正回包但草稿/版本/媒体内容未吻合保持UNKNOWN，不重发',()=>{
 for(const mutation of [d=>d,d=>{d.isDraft=false;d.topic.configVersion='5';return d},d=>{d.isDraft=false;d.topic.imgUrl='other.jpg';return d}]){
  const h=begin();h.requests[2].cb({ok:true,data:{topicId:501,published:true}});assert.ok(h.requests[3]);h.requests[3].cb({ok:true,data:mutation(draft())});assert.equal(h.results[0].unknown,true);assert.equal(h.results[0].ok,false);
  h.saver.saveAndPublish(payload,{onFail:r=>h.results.push(r)});assert.equal(h.requests.at(-1).kind,'read');assert.equal(h.requests.filter(r=>r.kind==='publish').length,1);
  const later=draft();later.isDraft=false;later.topic.configVersion='5';h.requests.at(-1).cb({ok:true,data:later});assert.equal(h.results.at(-1).unknown,true);
 }
});
test('UNKNOWN重进不能把较新已发布版本当成原请求；缺版本旧marker也不猜',()=>{
 for(const marker of [{configVersion:'4'},{}]){
  let callback;const results=[];const saver=createServerDraftSaver({pendingPublish:()=>501,pendingPublication:()=>marker,readback:(id,cb)=>callback=cb,save:()=>assert.fail('不得重新保存'),publish:()=>assert.fail('不得重发')});
  saver.saveAndPublish(payload,{onSuccess:r=>results.push(r),onFail:r=>results.push(r)});const back=draft();back.isDraft=false;back.topic.configVersion='5';callback({ok:true,data:back});assert.equal(results[0].unknown,true);
 }
});
test('媒体待审/拒绝的确定失败保留已确认草稿，不能当发布成功或UNKNOWN重放',()=>{
 for(const msg of ['图片待审核，暂不能公开','图片审核未通过，请更换']){const h=begin();h.requests[2].cb({ok:false,msg});assert.equal(h.results[0].ok,false);assert.equal(h.results[0].unknown,false);assert.equal(h.results[0].detail.isDraft,true);assert.equal(h.results[0].msg,msg);assert.equal(h.requests.length,3);}
});
