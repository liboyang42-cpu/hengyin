'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const {createRequire}=require('node:module');
const {createSessionStore}=require('../../utils/session/session-store.js');
const root=path.resolve(__dirname,'../..');
function mount(){
  let app;const requests=[],modals=[],uploads=[],storage=new Map();
  const wx={request:r=>{requests.push(r);return{abort(){}};},showModal:r=>modals.push(r),
    getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),
    showLoading(){},hideLoading(){},showToast(){},hideToast(){}};
  vm.runInNewContext(fs.readFileSync(path.join(root,'app.js'),'utf8'),{App:a=>{app=a;},wx,console,setTimeout,clearTimeout,
    require:f=>f==='./utils/toast.js'?Object.assign(()=>{},{hide(){}}):require(path.join(root,f))});
  const session=createSessionStore({target:app.globalData,wx});app.getSession=()=>session;app.isDevEnv=()=>false;
  app.globalData.siteBaseUrl='http://127.0.0.1:8129';session.setUserID('7');session.setAuthorization('fixture-seven');
  global.wx=wx;
  return {app,wx,requests,modals,session,uploads};
}
function reply(r,body){r.success({statusCode:200,data:body});}
function saved(r){const input=JSON.parse(r.data);reply(r,{code:200,data:Object.assign(input,{docVersion:'v1.0'})});}
async function settle(){for(let i=0;i<40;i++)await Promise.resolve();}
for(const feature of ['photo','voice'])test(feature+': separate refusal affects only that invocation; acceptance must persist exact scoped document',async()=>{
  const h=mount();let called=0;const refused=h.app.requestSeparateConsent(feature).then(()=>called++);
  const rejection=assert.rejects(refused,/拒绝/);h.modals[0].success({confirm:false});await rejection;
  assert.equal(called,0);assert.equal(h.requests.length,0);assert.equal(h.session.getUserID(),'7');
  const accepted=h.app.requestSeparateConsent(feature).then(()=>called++);h.modals[1].success({confirm:true});await settle();
  assert.equal(called,0);assert.equal(h.requests.length,1);
  const body=JSON.parse(h.requests[0].data);assert.equal(body.docType,feature==='photo'?'photo_model_processing':'voice_clone_processing');
  assert.equal(body.scene,feature==='photo'?'object_photo':'merchant_voice');saved(h.requests[0]);await accepted;assert.equal(called,1);
});
test('failed consent persistence and obsolete actor/token/page modal callbacks never authorize provider work',async()=>{
  for(const change of ['actor','token','page','failure']){
    const h=mount();let current=true,called=0;
    const promise=h.app.requestSeparateConsent('photo',()=>current).then(()=>called++);
    const rejection=assert.rejects(promise,/变化|失败/);
    if(change==='actor')h.session.setUserID('8');
    if(change==='token')h.session.setAuthorization('new-seven');
    if(change==='page')current=false;
    h.modals[0].success({confirm:true});await settle();
    if(change==='failure')reply(h.requests[0],{code:500});
    await rejection;assert.equal(called,0);if(change!=='failure')assert.equal(h.requests.length,0);
  }
});
function play(h){
  let def;global.wx=h.wx;global.getApp=()=>h.app;
  const file=path.join(root,'pages/play/index.js');
  vm.runInNewContext(fs.readFileSync(file,'utf8'),{Page:p=>{def=p;},getApp:()=>h.app,wx:h.wx,
    require:createRequire(file),console,setTimeout,clearTimeout});
  const state={sessionId:17,version:3},actions=[];
  // 真实 selectComponent 对同一个已挂载组件每次返回同一实例
  const advancedGame={data:{state},action:(...args)=>actions.push(args)};
  const page=Object.assign({},def,{data:JSON.parse(JSON.stringify(def.data)),
    setData(patch){for(const[k,v]of Object.entries(patch)){const parts=k.split('.');let t=this.data;for(const p of parts.slice(0,-1)){t[p]=t[p]||{};t=t[p];}t[parts.at(-1)]=v;}},
    selectComponent:()=>advancedGame});
  page.data.playKit={kit:{type:'photocheck',mode:'CARD',sessionId:17,version:3}};
  let aborted=false;h.app.createPageBoundOperation=()=>({onAbort(){},isAborted:()=>aborted,finish(){},attach(){}});
  h.app.getUploadClient=()=>({uploadAll:(paths,opts)=>{h.uploads.push(paths);opts.onDone({ok:true,results:['/profile/upload/approved.jpg']});return{abort(){}};}});
  return {page,state,actions,abort(){aborted=true;}};
}
test('actual play photo handler waits for separate consent and genuine readback before upload and runtime action',async()=>{
  const h=mount(),p=play(h);p.page._submitKitPhoto({tempFilePath:'/tmp/photo.jpg'},{name:'SUBMIT_PHOTO_CHECK',payload:url=>({imageUrl:url})});
  assert.equal(h.uploads.length,0);h.modals[0].success({confirm:true});await settle();saved(h.requests[0]);await settle();
  assert.equal(h.uploads.length,1);assert.equal(p.actions[0][0],'SUBMIT_PHOTO_CHECK');
});
for(const change of ['refuse','question','leave'])test('actual play '+change+' keeps photo private and sends no runtime action',async()=>{
  const h=mount(),p=play(h);p.page._submitKitPhoto({tempFilePath:'/tmp/photo.jpg'},{name:'SUBMIT_PHOTO_CHECK'});
  if(change==='question')p.state.version=4;if(change==='leave')p.abort();
  h.modals[0].success({confirm:change!=='refuse'});await settle();if(h.requests[0]){saved(h.requests[0]);await settle();}
  assert.equal(h.uploads.length,0);assert.equal(p.actions.length,0);assert.equal(p.page._kitPhotoUploading,false);
});
