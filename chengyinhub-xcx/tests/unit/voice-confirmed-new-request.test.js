'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const journal = require('../../utils/voice-enrollment-journal.js');
const { createSessionStore } = require('../../utils/session/session-store.js');
const settle = async () => { for (let i=0;i<6;i++) await new Promise(resolve => setImmediate(resolve)); };
const SAMPLE = 'media-asset:73';
function fixture(kind, previousStorage) {
  const values=previousStorage || new Map(), requests=[], modals=[], state={holdStatus:false};
  let definition;
  const storage={ getStorageSync: key => values.get(key), setStorageSync: (key,value) => values.set(key,JSON.parse(JSON.stringify(value))),
    removeStorageSync: key=>values.delete(key) };
  const session=createSessionStore({storage,target:{}});session.setUserID('7');
  const app={globalData:{}, getUserID:()=>session.getUserID(),getSession:()=>session,getAuthorization:()=> 'token',
    setUserRole: role=>session.setUserRole(role),requestSeparateConsent:()=>Promise.resolve(),sendRequest:options=>{
      requests.push(options);
      if(options.url.endsWith('/status')&&!state.holdStatus){
        const body=typeof options.data==='string'?JSON.parse(options.data):options.data;
        const record=journal.load(storage,'7',kind==='merchant'?'merchant':'node:31').records.find(row=>row.requestId===body.voiceRequestId);
        if(record)options.success({code:200,data:{voiceRequestId:record.requestId,voiceIntentId:record.intentId||8,
          voiceIntentState:record.state,voiceStatus:1,voiceSample:SAMPLE,voiceSelected:true}});
      }
    }};
  const file=path.resolve(__dirname,'../../',kind==='merchant'?'pages/merchant/decor/ai-npc/index.js':'pages/topic/components/cy/node-npc-form/index.js');
  vm.runInNewContext(fs.readFileSync(file,'utf8'),{getApp:()=>app,Page:d=>{definition=d;},Component:d=>{definition=d;},wx:storage,
    setTimeout:()=>0,clearTimeout(){},console,require(id){
      if(id.endsWith('voice-enrollment-journal.js'))return journal;
      if(id.endsWith('modal.js'))return {show:options=>modals.push(options)};
      if(id.endsWith('merchant-theme.js'))return {merchantPageShow(){},merchantPageRestore(){}};
      if(id.endsWith('pixel-avatar.js'))return {AVATAR_IDS:['one'],stringifyCode:()=> 'px1:one',
        isPixelAvatar:code=>String(code||'').startsWith('px1:'),parseCode:code=>String(code).slice(4)};
      if(id.endsWith('pixel-avatar-data.js'))return {AVATARS:{one:{name:'fixture'}}};
      if(id.endsWith('upload-client.js'))return {MAX_UPLOAD_FILE_SIZE:10485760};
      return {};
    }});
  const target=Object.assign({},kind==='merchant'?definition:definition.methods);
  target.data=JSON.parse(JSON.stringify(definition.data));Object.assign(target.data,{show:true,nodeId:31,voiceSample:SAMPLE,voiceConsent:true});
  target.setData=(patch,callback)=>{for(const [key,value] of Object.entries(patch)){
    const parts=key.split('.');let cursor=target.data;for(const p of parts.slice(0,-1))cursor=cursor[p]||(cursor[p]={});
    cursor[parts[parts.length-1]]=value;
  }if(callback)callback();};
  target._voiceMember='7';target._hidden=false;target._destroyed=false;target._formVersion=1;
  const scope=kind==='merchant'?'merchant':'node:31';
  if(!previousStorage){const row=journal.begin(storage,'7',scope,null,false);journal.dispatched(storage,'7',scope,row);
    journal.observe(storage,'7',scope,row.requestId,{voiceRequestId:row.requestId,voiceIntentId:7,voiceIntentState:'UNKNOWN',voiceStatus:1,voiceSample:SAMPLE,voiceSelected:true});}
  target.setData({voiceUnknown:true,voiceStatus:1});
  const read=()=>journal.load(storage,'7',scope), payload=request=>typeof request.data==='string'?JSON.parse(request.data):request.data;
  return {target,storage,values,app,session,requests,modals,scope,read,payload,state};
}
for(const kind of ['merchant','node']) {

  async function hideAndReopen(f) {
    f.state.holdStatus=false;
    if(kind==='merchant') { f.target.onHide(); f.target.onShow(); }
    else {
      f.target._onHide();f.target.data.show=false;f.target.data.show=true;f.target._onOpen();
      const detail=f.requests.filter(row=>row.url.endsWith('/npc/detail')).at(-1);
      detail.success({code:200,data:{name:'fixture',avatar:'px1:one',voiceStatus:1,voiceSample:SAMPLE}});
    }
    await settle();
  }
  function unknownReply(f, request) {
    request.success({code:200,data:{voiceRequestId:f.payload(request).voiceRequestId,voiceIntentId:7,
      voiceIntentState:'UNKNOWN',voiceStatus:1,voiceSample:SAMPLE,voiceSelected:true}});
  }
  test(kind+' 隐藏重开释放旧状态查询flag，旧finally不能清新查询代次',async()=>{
    for(const outcome of ['success','failure']) {
      const f=fixture(kind),original=JSON.stringify(f.read());f.state.holdStatus=true;
      f.target.confirmNewVoiceRequest();const old=f.requests.at(-1);assert.equal(f.target._voiceConfirming,true);
      await hideAndReopen(f);assert.equal(f.target._voiceConfirming,false);assert.equal(f.target._voiceRiskModalOpen,false);
      f.state.holdStatus=true;f.target.confirmNewVoiceRequest();const fresh=f.requests.at(-1);
      assert.notEqual(fresh,old);assert.equal(f.target._voiceConfirming,true);
      if(outcome==='success')unknownReply(f,old);else old.successStatusAbnormal({code:500,msg:'old-query-failure'});
      await settle();assert.equal(f.target._voiceConfirming,true);assert.equal(f.modals.length,0);
      unknownReply(f,fresh);await settle();assert.equal(f.modals.length,1);assert.equal(f.target._voiceRiskModalOpen,true);
      f.modals[0].success({confirm:false});await settle();assert.equal(JSON.stringify(f.read()),original);
      assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);
    }
  });
  test(kind+' 隐藏重开释放旧modal，迟到success和complete不能碰新owner',async()=>{
    const f=fixture(kind),original=f.read().active;
    f.target.confirmNewVoiceRequest();await settle();const old=f.modals[0],oldOwner=f.target._voiceRiskModalOwner;
    assert.equal(f.target._voiceRiskModalOpen,true);await hideAndReopen(f);
    assert.equal(f.target._voiceRiskModalOpen,false);assert.equal(f.target._voiceRiskModalOwner,null);
    f.target.confirmNewVoiceRequest();await settle();assert.equal(f.modals.length,2);
    const fresh=f.modals[1],newOwner=f.target._voiceRiskModalOwner;assert.notEqual(newOwner,oldOwner);
    old.success({confirm:true});old.complete();await settle();
    assert.equal(f.target._voiceRiskModalOpen,true);assert.equal(f.target._voiceRiskModalOwner,newOwner);
    assert.equal(f.read().active,original);assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);
    fresh.success({confirm:true});await settle();assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,1);
    assert.notEqual(f.read().active,original);assert.equal(f.read().records.find(row=>row.requestId===original).state,'UNKNOWN');
  });
  test(kind+' 取消风险确认不创建身份也不派发',async()=>{
    const f=fixture(kind),original=JSON.stringify(f.read());f.target.confirmNewVoiceRequest();await settle();
    assert.match(f.modals[0].content,/重复音色或占用额度/);f.modals[0].success({confirm:false});await settle();
    assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);assert.equal(JSON.stringify(f.read()),original);
  });
  test(kind+' 明确确认创建新身份，原UNKNOWN不覆盖，重复确认最多一次派发',async()=>{
    const f=fixture(kind),original=f.read().active;f.target.confirmNewVoiceRequest();f.target.confirmNewVoiceRequest();await settle();assert.equal(f.modals.length,1);
    f.modals[0].success({confirm:true});f.modals[0].success({confirm:true});await settle();
    const writes=f.requests.filter(row=>row.url.endsWith('/enroll'));assert.equal(writes.length,1);
    const body=f.payload(writes[0]);assert.notEqual(body.voiceRequestId,original);assert.equal(body.voicePreviousIntentId,7);
    assert.equal(body.voiceDuplicateChargeConsent,true);assert.equal(body.voiceSample,SAMPLE);
    assert.equal(f.read().records.length,2);assert.equal(f.read().records.find(row=>row.requestId===original).state,'UNKNOWN');
    assert.equal(f.read().active,body.voiceRequestId);
  });
  test(kind+' 拒绝独立声音同意不创建新请求',async()=>{
    const f=fixture(kind),original=JSON.stringify(f.read());f.app.requestSeparateConsent=()=>Promise.reject(Error('同意被拒绝'));
    f.target.confirmNewVoiceRequest();await settle();f.modals[0].success({confirm:true});await settle();
    assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);assert.equal(JSON.stringify(f.read()),original);
  });
  test(kind+' 真实角色revision变化及A-B-A使迟到确认失效',async()=>{
    for(const change of [f=>f.app.setUserRole('MERCHANT_MANAGER'),f=>{f.session.setUserID('8');f.session.setUserID('7');}]){
      const f=fixture(kind),original=JSON.stringify(f.read());f.target.confirmNewVoiceRequest();await settle();change(f);
      f.modals[0].success({confirm:true});await settle();assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);assert.equal(JSON.stringify(f.read()),original);
    }
  });
  test(kind+' 持久写入读回失败时零派发且原UNKNOWN保留',async()=>{
    const f=fixture(kind),original=JSON.stringify(f.read());f.target.confirmNewVoiceRequest();await settle();f.storage.setStorageSync=()=>{};
    f.modals[0].success({confirm:true});await settle();assert.equal(f.requests.filter(row=>row.url.endsWith('/enroll')).length,0);assert.equal(JSON.stringify(f.read()),original);
  });
  test(kind+' 重进只按原新请求查询，不自动重发',async()=>{
    const f=fixture(kind);f.target.confirmNewVoiceRequest();await settle();f.modals[0].success({confirm:true});await settle();const active=f.read().active;
    const restart=fixture(kind,f.values);restart.target.refreshVoice();await settle();
    assert.equal(restart.requests.length,1);assert.ok(restart.requests[0].url.endsWith('/status'));
    assert.equal(restart.payload(restart.requests[0]).voiceRequestId,active);assert.equal(restart.read().records.length,2);
  });
  test(kind+' 新请求选定后迟到旧历史回执不能切回原声音',async()=>{
    const f=fixture(kind),old=f.read().active;f.state.holdStatus=true;f.target.readVoiceHistory({currentTarget:{dataset:{request:old}}});const late=f.requests[0];f.state.holdStatus=false;
    f.target.confirmNewVoiceRequest();await settle();f.modals[0].success({confirm:true});await settle();const active=f.read().active;
    // This reply was produced while the old request was selected; arrival after the new intent must not reselect it.
    late.success({code:200,data:{voiceRequestId:old,voiceIntentId:7,voiceIntentState:'PENDING',voiceStatus:1,voiceSample:SAMPLE,voiceSelected:true}});
    await settle();assert.equal(f.read().active,active);assert.notEqual(active,old);assert.equal(f.read().records.length,2);
  });
}
test('journal按账号/节点隔离，记录不含录音、token或供应商输入，容量满不丢UNKNOWN',()=>{
  const f=fixture('node'),one=f.read();assert.equal(journal.load(f.storage,'8','node:31').records.length,0);
  assert.equal(journal.load(f.storage,'7','node:32').records.length,0);
  assert.ok(one.records.every(row=>Object.keys(row).sort().join(',')==='createdAt,intentId,previousIntentId,requestId,state'));
  for(let i=1;i<20;i++) {const original=f.read().active,row=journal.begin(f.storage,'7',f.scope,original,true);journal.dispatched(f.storage,'7',f.scope,row);
    journal.observe(f.storage,'7',f.scope,row.requestId,{voiceRequestId:row.requestId,voiceIntentId:i+7,voiceIntentState:'UNKNOWN',voiceStatus:1,voiceSelected:true});}
  const full=JSON.stringify(f.read());assert.throws(()=>journal.begin(f.storage,'7',f.scope,f.read().active,true));assert.equal(JSON.stringify(f.read()),full);
});
