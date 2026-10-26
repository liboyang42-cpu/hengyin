// Durable, account-scoped creation intent. No token is persisted.
function createBroadcastRecovery({ storage, identity, random, publish, query, cancel, changed }) {
 const actor=identity(),key='official-broadcast-create-v1:'+actor.id;
 let record=null,error='',busy=false,disposed=false;
 const current=()=>!disposed&&identity().id===actor.id&&identity().token===actor.token;
 const emit=()=>{if(current())changed({record,error,busy});};
 const save=value=>{storage.setItem(key,JSON.stringify(value));if(storage.getItem(key)!==JSON.stringify(value))throw Error('原请求未保存');record=value;};
 try{const raw=storage.getItem(key);if(raw){record=JSON.parse(raw);if(record.actor!==actor.id||!record.payload||!/^[a-f0-9]{32}$/.test(record.payload.clientRequestId))throw Error('invalid');if(record.result&&(record.result.requestId!==record.payload.clientRequestId||!['APPLIED','CANCELLED'].includes(record.result.state)))throw Error('invalid receipt');}}catch(_){error='原请求不可读，请保留记录并重试读取';}
 const matches=()=>{try{if(JSON.stringify(record)!==JSON.stringify(JSON.parse(storage.getItem(key)||'null')))throw Error('changed');return true;}catch(_){error='原记录已变化或不可读，请重新读取';emit();return false;}};
 async function act(mode){
  if(!current()||busy||error||!record||record.result||!matches())return;
  busy=true;emit();const requestId=record.payload.clientRequestId;
  try{
   if(mode==='submit')await publish(JSON.parse(JSON.stringify(record.payload)));
   const res=mode==='cancel'?await cancel(requestId):await query(requestId);
   if(!current()||!matches())return;
   const receipt=res&&res.data;
   if(res.code==200&&receipt&&receipt.requestId===requestId&&['APPLIED','CANCELLED'].includes(receipt.state))save({...record,result:receipt});
  }catch(_){/* Transport/HTTP failure is UNKNOWN. Retain exact payload. */}
  finally{busy=false;emit();}
 }
 emit();return {
  async start(payload){if(!current()||busy||error||record||!actor.id||!matches())return;
   try{const bytes=new Uint8Array(16);random(bytes);const requestId=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');save({actor:actor.id,payload:{...JSON.parse(JSON.stringify(payload)),clientRequestId:requestId}});}catch(_){error='原请求无法保存，尚未发送';emit();return;}await act('submit');},
  retry:()=>act('submit'),query:()=>act('query'),cancel:()=>act('cancel'),
  acknowledge(){if(!current()||busy||error||!record||!record.result||!matches())return;try{storage.removeItem(key);if(storage.getItem(key))throw Error('clear');record=null;}catch(_){error='归档失败，请重新读取';}emit();},
  dispose(){disposed=true;},pending:()=>!!record||!!error||busy
 };
}
module.exports={createBroadcastRecovery};
