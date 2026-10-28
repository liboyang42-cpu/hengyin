const {test}=require('node:test');const assert=require('node:assert/strict');
function page(){let config,member='7',auth='a';const uploads=[],stored={},sent=[];global.getApp=()=>({globalData:{},sendRequest:o=>sent.push(o),getUserID:()=>member,getAuthorization:()=>auth,chooseImage:(callback,count,options)=>uploads.push({callback,count,options})});global.wx={getStorageSync:k=>stored[k],setStorageSync:(k,v)=>stored[k]=v,getSystemInfoSync:()=>({}),showToast(){},hideLoading(){}};global.Page=c=>config=c;delete require.cache[require.resolve('../../pages/publish/fabu/index')];require('../../pages/publish/fabu/index');const p={...config,data:structuredClone(config.data)};p.setData=(patch,cb)=>{for(const [key,val]of Object.entries(patch)){const parts=key.split('.');let obj=p.data;for(const k of parts.slice(0,-1))obj=obj[k];obj[parts.at(-1)]=val;}if(cb)cb();};Object.assign(p.data,{draftMemberId:'7',draftUuid:'draft-a',operationScope:'',editingTopicId:'501'});p.refreshPrimaryActionState=()=>{};return {p,uploads,stored,sent,changeMember:()=>member='8',changeAuth:()=>auth='b'};}
test('封面上传迟到不能污染切号、切scope、切草稿或卸载后的页面',()=>{
 for(const kind of ['uploadBanner','uploadImgArr'])for(const change of ['member','auth','scope','draft','hide','unload']){
  const h=page();h.p[kind]();if(change==='member')h.changeMember();if(change==='auth')h.changeAuth();if(change==='scope')h.p.data.operationScope='MERCHANT';if(change==='draft')h.p.data.draftUuid='draft-b';if(change==='hide')h.p._hidden=true;if(change==='unload')h.p._unloaded=true;
  h.uploads[0].callback(['old.jpg']);assert.equal(h.p.data.formData.imgUrl,'');assert.deepEqual(h.p.data.imgArrList,[]);
 }
});
test('较新封面选择胜出；删除竖图会使在途旧上传失效',()=>{
 const h=page();h.p.uploadBanner();h.p.uploadBanner();h.uploads[1].callback(['new.jpg']);h.uploads[0].callback(['old.jpg']);assert.equal(h.p.data.formData.imgUrl,'new.jpg');
 h.p.uploadBanner();h.p.removeBanner();h.uploads[2].callback(['late.jpg']);assert.equal(h.p.data.formData.imgUrl,'');
});
test('横图删除校验索引；删除后迟到上传不复活图片，当前追加保留顺序和上限',()=>{
 const h=page();h.p._setImgArr(['a.jpg','bad.jpg']);h.p.uploadImgArr();
 for(const i of [undefined,-1,'bad',0.5,9])h.p.removeImgArr({currentTarget:{dataset:{i}}});assert.deepEqual(h.p.data.imgArrList,['a.jpg','bad.jpg']);
 h.p.removeImgArr({currentTarget:{dataset:{i:1}}});h.uploads[0].callback(['bad.jpg']);assert.deepEqual(h.p.data.imgArrList,['a.jpg']);
 h.p.uploadImgArr();h.uploads[1].callback(['b.jpg','c.jpg']);assert.equal(h.p.data.formData.imgArr,'a.jpg,b.jpg,c.jpg');
});
test('删除失败封面使用真实本地信封持久化，重新进入不复活',()=>{
 const h=page();h.p.data.formData.imgUrl='rejected.jpg';h.p._setImgArr(['ok.jpg','failed.jpg']);h.p._queueCloudDraftAutosave=()=>{};h.p._draftAutosaveReady=true;h.p._installDraftAutosave();
 h.p.removeBanner();h.p.removeImgArr({currentTarget:{dataset:{i:1}}});
 const draft=Object.values(h.stored).find(x=>x && x.formData);assert.ok(draft);assert.equal(draft.formData.imgUrl,'');assert.equal(draft.formData.imgArr,'ok.jpg');
 const next=page();next.p._applyDraftEnvelope(draft);assert.equal(next.p.data.formData.imgUrl,'');assert.deepEqual(next.p.data.imgArrList,['ok.jpg']);
});

test('未启用属性的真实Page也持久化原发布版本，正回包需回读后才完成',()=>{
 const h=page(),p=h.p,results=[];p._topicPublishCheck=(id,cb)=>cb({ok:true});
 const payload={name:'测试',configVersion:'3',chapters:[]};
 p.getServerDraftSaver().saveAndPublish(payload,{onSuccess:r=>results.push(r),onFail:r=>assert.fail(r.msg)});
 h.sent[0].success({code:200,data:501});
 const detail={isDraft:true,topic:{id:501,status:0,name:'测试',configVersion:'4'},chapters:[]};
 h.sent[1].success({code:200,data:detail});
 const publish=h.sent.find(r=>r.url==='/api/topic/publish-draft');assert.ok(publish);
 assert.equal(publish.data.configVersion,'4');assert.equal(p._publishAttempt.configVersion,'4');
 assert.ok(Object.values(h.stored).some(v=>v && v.publishAttempt && v.publishAttempt.configVersion==='4'));
 publish.success({code:200,data:{topicId:501,published:true}});assert.equal(results.length,0);
 assert.equal(h.sent.at(-1).url,'/api/topic/author-edit-detail');
 detail.isDraft=false;detail.topic.status=1;h.sent.at(-1).success({code:200,data:detail});assert.equal(results.length,1);
});
