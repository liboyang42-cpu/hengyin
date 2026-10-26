const {test}=require('node:test');const assert=require('node:assert/strict');
function page(storage={}) {
 let config,member='7',auth='a';const sent=[],messages=[];
 global.wx={getStorageSync:k=>storage[k],setStorageSync:(k,v)=>storage[k]=v,removeStorageSync:k=>delete storage[k],getSystemInfoSync:()=>({}),showToast:o=>messages.push(o.title)};
 global.getApp=()=>({globalData:{},getUserID:()=>member,getAuthorization:()=>auth,sendRequest:o=>sent.push(o),getRequestErrorMessage:(r,f)=>r.msg||f});
 global.Page=c=>config=c;delete require.cache[require.resolve('../../pages/publish/fabu/index')];require('../../pages/publish/fabu/index');
 const p={...config,data:structuredClone(config.data)};p.setData=(v,cb)=>{for(const [key,value] of Object.entries(v)){const parts=key.split('.');let o=p.data;for(const part of parts.slice(0,-1))o=o[part];o[parts.at(-1)]=value;}if(cb)cb()};
 Object.assign(p.data,{draftMemberId:'7',draftUuid:'draft-a',editingTopicId:'501',operationScope:'',selectedTempSource:'public',selectedTempId:11,nodesForm:{name:'原稿',templateId:5}});
 p._templateSupportReason=()=>'';let bound=null;p._bindNodeTemplate=v=>{bound=v;return true};p.getStoryDraftList=()=>sent.push({url:'list'});
 return {p,sent,storage,messages,realBind:config._bindNodeTemplate,bound:()=>bound,member:()=>member='8',auth:()=>auth='b'};
}
const source={id:'9007199254740993',title:'来源'};
const version='a'.repeat(64),draftId='9007199254740995';
const detail={id:draftId,title:'采用稿',advancedConfigJson:'{}'};
function start(h){h.p._adoptPublicTemplate(source);h.sent[0].success({code:200,data:{originalTemplateId:source.id,sourceVersion:version,title:source.title}});return h.sent[1];}
function receipt(h,status='COMPLETED'){const r=Object.values(h.storage)[0];return {code:200,data:{requestId:r.requestId,originalTemplateId:source.id,sourceVersion:version,status,draftId}};}
test('丢ACK退出重进按原身份查回原请求，大ID回执与详情确认后才绑定',()=>{
 const h=page();start(h).fail();const saved=Object.values(h.storage)[0];assert.equal(saved.payload.originalTemplateId,source.id);assert.equal(saved.payload.sourceVersion,version);assert.equal(saved.target.node.templateId,5);
 const next=page(h.storage);next.p._adoptPublicTemplate(source);assert.equal(next.sent[0].url,'/api/template/adoption-receipt');assert.equal(next.sent[0].data.requestId,saved.requestId);assert.equal(next.bound(),null);
 next.sent[0].success(receipt(next));assert.equal(next.sent[1].url,'/api/template/adoption-detail');next.sent[1].success({code:200,data:detail});assert.deepEqual(next.bound(),detail);assert.equal(Object.keys(next.storage).length,0);
});
test('负查询/PENDING只恢复同请求同版本，不偷换最新来源；查询失败不写',()=>{
 for(const state of ['UNKNOWN','PENDING']){
  const h=page();const write=start(h),frozen=write.data;write.fail();const next=page(h.storage);next.p._adoptPublicTemplate({...source,title:'新标题'});
  const r=receipt(next,state);next.sent[0].success(r);assert.equal(next.sent[1].url,'/api/template/adopt-draft');assert.equal(next.sent[1].data,frozen);assert.equal(next.bound(),null);
 }
 const h=page();start(h).fail();const next=page(h.storage);next.p._adoptPublicTemplate(source);next.sent[0].fail();assert.equal(next.sent.length,1);assert.ok(Object.values(next.storage)[0]);
});
test('来源变动明确拒绝保原稿；同号异参/错误回执不清记录',()=>{
 for(const bad of ['conflict','version','source','id','rejected']){
  const h=page();const write=start(h);let reply=receipt(h);if(bad==='conflict')reply={code:409,msg:'ADOPTION_REQUEST_CONFLICT'};
  if(bad==='version')reply.data.sourceVersion='b'.repeat(64);if(bad==='source')reply.data.originalTemplateId='9007199254740992';if(bad==='id')reply.data.draftId=9007199254740995;
  if(bad==='rejected'){reply.data.status='REJECTED';reply.data.reason='SOURCE_VERSION_CONFLICT';}
  write.success(reply);assert.equal(h.bound(),null);assert.equal(h.p.data.nodesForm.templateId,5);assert.equal(Object.keys(h.storage).length,bad==='rejected'?0:1);
 }
});
test('采用或详情迟到不能跨账号、授权、scope、草稿、节点或隐藏卸载绑定',()=>{
 for(const phase of ['adopt','detail'])for(const change of ['member','auth','scope','draft','node','hidden','unloaded']){
  const h=page();const write=start(h);if(phase==='detail')write.success(receipt(h));
  if(change==='member')h.member();if(change==='auth')h.auth();if(change==='scope')h.p.data.operationScope='MERCHANT';if(change==='draft')h.p.data.draftUuid='other';if(change==='node')h.p.data.nodesForm.name='继续编辑';if(change==='hidden')h.p._hidden=true;if(change==='unloaded')h.p._unloaded=true;
  h.sent.at(-1).success(phase==='detail'?{code:200,data:detail}:receipt(h));assert.equal(h.bound(),null);assert.equal(h.p.data.nodesForm.templateId,5);
 }
});
test('存储失败不写；旧无请求号UNKNOWN不伪造新请求；实际查看入口保留',()=>{
 const h=page();wx.setStorageSync=()=>{throw new Error('storage full')};h.p._adoptPublicTemplate(source);h.sent[0].success({code:200,data:{originalTemplateId:source.id,sourceVersion:version}});assert.equal(h.sent.length,1);
 const next=page();require('../../pages/publish/utils/publish/template-adoption').write(wx,{schemaVersion:1,owner:'7',scope:'',status:'UNKNOWN',payload:{originalTemplateId:source.id}});
 next.p._adoptPublicTemplate(source);assert.equal(next.sent.length,0);assert.match(next.p.data.templateAdoptionError,/旧采用/);next.p.inspectAdoptedTemplates();assert.equal(next.sent[0].url,'list');assert.equal(next.p.data.nodesForm.templateId,5);
});

test('采用大ID贯通真实节点绑定、配置保存及语义回读，不与相邻Long混同',()=>{
 const h=page();h.p._bindNodeTemplate=h.realBind;h.p._openNodePlanEndings=()=>{};const write=start(h);write.success(receipt(h));h.sent.at(-1).success({code:200,data:detail});
 assert.equal(h.p.data.nodesForm.templateId,draftId);
 const binding=require('../../pages/publish/utils/publish/template-binding');
 const bound=binding.reconcile([],draftId,null,[],'{}','{}',detail);assert.equal(bound.templateId,draftId);
 assert.equal(binding.sharedSites([{nodes:[{templateId:draftId},{templateId:'9007199254740996'}]}],draftId).length,1);
 const {readbackIssue}=require('../../pages/publish/utils/publish/server-draft');
 const payload={name:'主题',chapters:[{name:'章',nodes:[{name:'站',templateId:draftId}]}]};
 const back={topic:{name:'主题'},chapters:[{name:'章',cmsTopicNodeList:[{id:11,name:'站',templateId:'9007199254740996'}]}]};
 assert.match(readbackIssue(payload,back,false),/玩法/);
});
