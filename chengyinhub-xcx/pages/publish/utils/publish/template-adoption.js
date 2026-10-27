// Original-command journal. Only requestId-bearing records use durable server receipts.
// Legacy records without requestId must never be upgraded by inventing a new command.
function id(value) {
  const text = String(value == null ? '' : value);
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  if (!/^[1-9][0-9]{0,18}$/.test(text) || text.length === 19 && text > '9223372036854775807') return '';
  return text;
}
function key(owner, scope, sourceId) {
  return 'author:template-adoption:v1:' + encodeURIComponent(String(owner)) + ':'
    + encodeURIComponent(scope || '') + ':' + sourceId;
}
function read(storage, owner, scope, sourceId) {
  const record = storage.getStorageSync(key(owner, scope, sourceId));
  if (!record) return null;
  if ((record.draftId && id(record.draftId) !== record.draftId) || record.schemaVersion !== 1 || record.owner !== String(owner) || record.scope !== (scope || '')
      || !record.payload || String(record.payload.originalTemplateId) !== sourceId) throw new Error('采用恢复记录无法读取，请保留记录核查');
  return JSON.parse(JSON.stringify(record));
}
function write(storage, record) {
  storage.setStorageSync(key(record.owner, record.scope, String(record.payload.originalTemplateId)), JSON.parse(JSON.stringify(record)));
}
function clear(storage, record) {
  storage.removeStorageSync(key(record.owner, record.scope, String(record.payload.originalTemplateId)));
}

// Shared original-command recovery for the template editor. No retry creates a new request ID.
function resume(options) {
  const {app,storage,owner,scope,current,onReady,onError,onBusy}=options;
  const sourceId=id(options.sourceId);
  const fail=message=>{onBusy(false);if(current())onError(message);};
  let record;
  if(!sourceId||!owner){fail('采用身份或来源编号无效');return;}
  try{record=read(storage,owner,scope,sourceId);}catch(error){fail(error.message);return;}
  const persist=()=>{try{write(storage,record);return true;}catch(_){fail('采用恢复记录保存失败，原稿保留');return false;}};
  const success=callback=>res=>{onBusy(false);if(current())callback(res);};
  const detail=res=>{
    const value=res&&res.code==200&&res.data;
    if(!value||id(value.id)!==record.draftId||!Object.prototype.hasOwnProperty.call(value,'advancedConfigJson')){fail('原采用草稿详情或版本未确认，原稿保留');return;}
    if(onReady(value)!==false){try{clear(storage,record);}catch(_){/* A remaining journal still points to the same result. */}}
  };
  const load=()=>{
    onBusy(true);
    if(record.requestId)app.sendRequest({url:'/api/template/adoption-detail',method:'GET',data:{requestId:record.requestId,scope},success:success(detail),fail:()=>fail('原采用详情读取失败，请按原请求重试')});
    else app.sendRequest({url:'/api/template/author-myinfo',method:'POST',data:{id:record.draftId,scope},success:success(detail),fail:()=>fail('原草稿读取失败，请重试')});
  };
  const accept=res=>{
    const facts=res&&res.code==200&&res.data;
    if(!facts||facts.requestId!==record.requestId||facts.sourceVersion!==record.sourceVersion||facts.originalTemplateId!==sourceId){fail('原采用结果未确认，请按原请求重试');return;}
    if(facts.status==='REJECTED'){
      try{clear(storage,record);}catch(_){}
      fail(facts.reason==='SOURCE_VERSION_CONFLICT'?'原采用被拒绝：来源已改变或下架，请重新选择':'原采用未通过内容、玩法能力或资产资格校验');return;
    }
    const draftId=id(facts.draftId);
    if(facts.status!=='COMPLETED'||!draftId){fail('原采用尚未确认完成');return;}
    record.draftId=draftId;if(persist())load();
  };
  const submit=()=>{if(!current())return;onBusy(true);app.sendRequest({url:'/api/template/adopt-draft',method:'POST',header:{'Content-Type':'application/json'},data:JSON.stringify(record.payload),success:success(accept),fail:(error,status)=>fail([404,405].includes(Number(status || error && (error.statusCode || error.status)))?'当前服务尚未支持采用请求，请更新服务后重查原请求':'采用结果未确认；重试会查询原请求')});};
  if(record){
    if(!record.requestId){if(record.draftId)load();else fail('旧采用缺少请求号，请到我的玩法核查，勿重复创建');return;}
    onBusy(true);app.sendRequest({url:'/api/template/adoption-receipt',method:'GET',data:{requestId:record.requestId,scope},success:success(res=>{
      const facts=res&&res.code==200&&res.data;
      if(facts&&facts.requestId===record.requestId&&['PENDING','UNKNOWN'].includes(facts.status))submit();else accept(res);
    }),fail:()=>fail('原采用请求查询失败，恢复记录保留')});return;
  }
  onBusy(true);app.sendRequest({url:'/api/template/adoption-source',method:'GET',data:{originalTemplateId:sourceId,scope},success:success(res=>{
    const source=res&&res.code==200&&res.data;
    if(!source||source.originalTemplateId!==sourceId||!/^[a-f0-9]{64}$/.test(source.sourceVersion||'')){fail('来源版本未确认，尚未发起采用');return;}
    const requestId='adopt_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    record={schemaVersion:1,status:'UNKNOWN',owner:String(owner),scope:scope||'',requestId,sourceVersion:source.sourceVersion,
      target:options.target||{},payload:{requestId,originalTemplateId:sourceId,sourceVersion:source.sourceVersion,scope:scope||''}};
    if(persist())submit();
  }),fail:()=>fail('来源版本读取失败，尚未发起采用')});
}
module.exports = { id, read, write, clear, resume };
