/* No filesystem/storage side effects. Callers choose whether to save returned JSONL chunks. */
const FORMAT='chengyin-exploration-export-v1';
const LONG_MAX='9223372036854775807';
function decimal(value,zero){return typeof value==='string' && (zero?/^(0|[1-9]\d*)$/:/^[1-9]\d*$/).test(value) && (value.length<LONG_MAX.length || value.length===LONG_MAX.length&&value<=LONG_MAX);}
function compare(a,b){return a.length-b.length || (a===b?0:a>b?1:-1);}
function date(value){if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||value<'2000-01-01')return false;const d=new Date(value+'T00:00:00Z');return !isNaN(d.getTime())&&d.toISOString().slice(0,10)===value;}
function range(start,end){return date(start)&&date(end)&&start<end&&(Date.parse(end)-Date.parse(start))/86400000<=366;}
function cursor(value){return typeof value==='string'&&/^[A-Za-z0-9_-]{40,512}$/.test(value);}
const keys=['format','timeZone','snapshotId','startDate','endDateExclusive','asOfMs','expiresAtMs','upperRecordId','expectedRows','pageSize'];
function descriptor(value){
  if(!value||value.format!==FORMAT||value.timeZone!=='Asia/Shanghai'||typeof value.snapshotId!=='string'||!/^[0-9a-f]{64}$/.test(value.snapshotId)||!range(value.startDate,value.endDateExclusive)
    ||!Number.isSafeInteger(value.asOfMs)||value.asOfMs<=0||value.expiresAtMs!==value.asOfMs+86400000||!decimal(value.upperRecordId,true)
    ||!Number.isInteger(value.expectedRows)||value.expectedRows<0||value.expectedRows>1000||!Number.isInteger(value.pageSize)||value.pageSize<1||value.pageSize>100
    ||(value.expectedRows===0?value.upperRecordId!=='0':value.upperRecordId==='0'))throw new Error('导出范围未确认');
  const clean={};keys.forEach(k=>{clean[k]=value[k];});return clean;
}
function createExplorationExportClient(options){
  if(!options||typeof options.sendRequest!=='function'||typeof options.getMemberId!=='function'||typeof options.getLoginEpoch!=='function')throw new Error('需要请求通道与登录会话 epoch');
  const owner=String(options.getMemberId()||''),epoch=options.getLoginEpoch();if(!decimal(owner,false)||epoch==null)throw new Error('请先登录');
  let disposed=false,generation=0,state=null,flight=null;
  function check(){if(disposed||String(options.getMemberId()||'')!==owner||options.getLoginEpoch()!==epoch)throw new Error('登录状态已变化，请重新准备导出');}
  function send(url,data,token){ const path = url.slice(url.lastIndexOf('/') + 1);
    check();return new Promise((resolve,reject)=>{
      let settled=false;const timer=setTimeout(()=>finish(new Error('导出响应未确认，可重试原分页')),options.timeoutMs||15000);
      function finish(error,value){if(settled)return;settled=true;clearTimeout(timer);try{check();if(token!==generation)throw new Error('导出请求已过期');}catch(e){reject(e);return;}if(error)reject(error);else resolve(value);}
      try{options.sendRequest({url:url,method:'POST',data:JSON.stringify(data),header:{'Content-Type':'application/json'},hideLoading:true,autoErrorToast:false,
        success(res){if(!res||res.code!==200||!res.data||typeof res.data!=='object')finish(new Error('导出响应未确认'));else finish(null,res.data);},fail(){finish(new Error('导出响应未确认，可重试原分页'));}});}catch(e){finish(e);}
    });
  }
  function prepare(input){
    let token,payload;
    try{check();if(!input||!range(input.startDate,input.endDateExclusive))throw new Error('导出范围须为 1–366 天，请缩短日期范围');if(input.pageSize!=null&&(!Number.isInteger(input.pageSize)||input.pageSize<1||input.pageSize>100))throw new Error('每页须为 1–100 条');payload={startDate:input.startDate,endDateExclusive:input.endDateExclusive,pageSize:input.pageSize==null?100:input.pageSize};token=++generation;state=null;flight=null;}catch(e){return Promise.reject(e);}
    return send('/api/personal-exploration-export/prepare',payload,token).then(value=>{
      check();if(token!==generation)throw new Error('导出请求已过期');const snapshot=descriptor(value);
      if(snapshot.startDate!==payload.startDate||snapshot.endDateExclusive!==payload.endDateExclusive||snapshot.pageSize!==payload.pageSize||!cursor(value.cursor))throw new Error('导出响应不属于本次范围');
      state={snapshot,cursor:value.cursor,offset:0,lastRecordId:null,part:0,complete:false,changed:false};return checkpoint();
    });
  }
  function checkpoint(){check();if(!state)return null;return {owner,snapshot:{...state.snapshot},cursor:state.cursor,offset:state.offset,lastRecordId:state.lastRecordId,part:state.part,complete:state.complete,changed:state.changed};}
  function resume(saved){
    check();const snapshot=descriptor(saved&&saved.snapshot);
    if(!saved||saved.owner!==owner||!cursor(saved.cursor)||!Number.isInteger(saved.offset)||saved.offset<0||saved.offset>=Math.max(1,snapshot.expectedRows)||!Number.isInteger(saved.part)||saved.part<0||saved.part>1000||saved.complete!==false||saved.changed!==false
      ||(saved.offset===0?saved.lastRecordId!==null||saved.part!==0:!decimal(saved.lastRecordId,false)||compare(saved.lastRecordId,snapshot.upperRecordId)>0||saved.part<1))throw new Error('恢复位置无效，请使用原完整 checkpoint');
    generation++;flight=null;state={snapshot,cursor:saved.cursor,offset:saved.offset,lastRecordId:saved.lastRecordId,part:saved.part,complete:false,changed:false};return checkpoint();
  }
  function projectRow(row,snapshot){
    if(!row||!decimal(row.recordId,false)||compare(row.recordId,snapshot.upperRecordId)>0||typeof row.completedAt!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+08:00$/.test(row.completedAt)
      ||!date(row.completedAt.slice(0,10))||row.completedAt.slice(0,10)<snapshot.startDate||row.completedAt.slice(0,10)>=snapshot.endDateExclusive||!Number.isFinite(Date.parse(row.completedAt))||new Date(Date.parse(row.completedAt)+28800000).toISOString().slice(0,23)+'+08:00'!==row.completedAt||Date.parse(row.completedAt)>snapshot.asOfMs
      ||row.routeVersion!==null&&!decimal(row.routeVersion,true)||row.elapsedSeconds!==null&&(!Number.isSafeInteger(row.elapsedSeconds)||row.elapsedSeconds<0)
      ||!['AVAILABLE','UNAVAILABLE'].includes(row.routeAvailability)||(row.routeAvailability==='UNAVAILABLE'?row.routeTitle!==null:row.routeTitle!==null&&typeof row.routeTitle!=='string'))throw new Error('导出记录无效');
    const out={};for(const k of ['recordId','completedAt','routeVersion','elapsedSeconds','routeAvailability','routeTitle'])out[k]=row[k];return out;
  }
  function next(){
    let original,token;
    try{check();if(!state||state.complete||state.changed)throw new Error('请准备新的导出范围');if(flight)throw new Error('分页读取中');original=state;token=++generation;}catch(e){return Promise.reject(e);}
    const marker={};flight=marker;
    marker.promise=send('/api/personal-exploration-export/page',{cursor:original.cursor},token).then(value=>{
      check();if(token!==generation||state!==original)throw new Error('导出请求已过期');
      const snapshot=descriptor(value);if(keys.some(k=>snapshot[k]!==original.snapshot[k])||value.cursor!==original.cursor||value.offset!==original.offset||!Array.isArray(value.items)||value.items.length>snapshot.pageSize||typeof value.complete!=='boolean')throw new Error('分页与原导出不匹配');
      if(value.state==='SOURCE_CHANGED'){
        if(value.complete||value.nextCursor!==null||value.items.length||value.emittedRows!==original.offset)throw new Error('数据变化结果未确认');state={...original,changed:true};return {state:'SOURCE_CHANGED',complete:false,restartRequired:true,data:null,checkpoint:checkpoint()};
      }
      const items=value.items.map(row=>projectRow(row,snapshot));let last=original.lastRecordId;
      for(const item of items){if(last!==null&&compare(item.recordId,last)>=0)throw new Error('重复或错序分页');last=item.recordId;}
      const emitted=original.offset+items.length;
      if(value.emittedRows!==emitted||emitted>snapshot.expectedRows||(value.complete?(value.state!=='COMPLETE'||value.nextCursor!==null||emitted!==snapshot.expectedRows):(value.state!=='MORE'||!cursor(value.nextCursor)||value.nextCursor===original.cursor||items.length!==snapshot.pageSize||emitted>=snapshot.expectedRows)))throw new Error('分页条数或完成状态无效');
      const part=original.part+1;const metadata={type:'metadata',format:FORMAT,snapshot,part,offset:original.offset};
      const lines=[JSON.stringify(metadata),...items.map(record=>JSON.stringify({type:'record',...record})),JSON.stringify({type:'footer',part,emittedRows:emitted,expectedRows:snapshot.expectedRows,complete:value.complete})];
      const data=lines.join('\n')+'\n';
      const filename='chengyin-exploration-'+snapshot.startDate.replace(/-/g,'')+'-'+snapshot.endDateExclusive.replace(/-/g,'')+'-part-'+String(part).padStart(4,'0')+'.jsonl';
      state={snapshot,cursor:value.nextCursor,offset:emitted,lastRecordId:last,part,complete:value.complete,changed:false};
      return {state:value.state,complete:value.complete,items,data,filename,mimeType:'application/x-ndjson',checkpoint:checkpoint()};
    }).finally(()=>{if(flight===marker)flight=null;check();if(token!==generation)throw new Error('导出请求已过期');});return marker.promise;
  }
  return {prepare,next,retry:next,resume,checkpoint,dispose(){disposed=true;generation++;state=null;flight=null;}};
}
module.exports={createExplorationExportClient};
