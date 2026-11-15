const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const pv = require('../../pages/publish/temp/preview-verdict.js');
const draft = require('../../pages/publish/utils/publish/server-draft.js');
const authored={qa:{answerText:'排队'},answerPolicy:{version:1,normalizationPolicy:'LEGACY_V1',acceptedAliases:['待发送','尚未送达','未送达']}};
const judge=(text, advanced=authored)=>pv.judge(advanced,{type:'qa'},'submit',{mode:'type',input:text});
test('PREVIEW-ALIAS-01: explicitly authored aliases pass; canonical remains unchanged',()=>{
  for(const value of authored.answerPolicy.acceptedAliases) assert.equal(judge(value),'win');
  assert.equal(authored.qa.answerText,'排队');
});
test('preview controls: primary passes, wrong/empty/unconfigured text fails',()=>{
  assert.equal(judge('排队'),'win');
  for(const value of ['已送达','','等待','排队中']) assert.equal(judge(value),'fail');
  assert.equal(judge('待发送',{qa:{answerText:'排队'}}),'fail');
  assert.equal(judge('待发送',{...authored,qa:{answerText:''}}),'fail');
});
test('preview malformed policy never adds an alias; explicit alias is not separator-split',()=>{
  for(const answerPolicy of [{...authored.answerPolicy,version:2},{...authored.answerPolicy,normalizationPolicy:'FUZZY'},
    {...authored.answerPolicy,acceptedAliases:'待发送'},{...authored.answerPolicy,extra:true}])
    assert.equal(judge('待发送',{...authored,answerPolicy}),'fail');
  assert.equal(judge('待发送',{...authored,answerPolicy:{version:1,acceptedAliases:['待发送，尚未送达']}}),'fail');
});
test('policy rejects aliases duplicated under backend LEGACY_V1 normalizedKey, not canonical duplication',()=>{
  for(const aliases of [['待发送','待发送'],['WAIT','wait'],['ＷＡＩＴ','wait'],['待 发\t送','待发送']]) {
    const answerPolicy={version:1,acceptedAliases:aliases};
    assert.equal(judge(aliases[0],{...authored,answerPolicy}),'fail');
    assert.equal(judge('排队',{...authored,answerPolicy}),'win');
  }
  assert.equal(judge('待发送',{...authored,answerPolicy:{version:1,acceptedAliases:['排队','待发送']}}),'win');
});
test('policy Java trim and normalization boundaries are retained, not widened to JS trim or NFKC dedup',()=>{
  for(const aliases of [['待发送','\u00a0待发送\u00a0'],['待发送','待　发送'],['待发送','【待发送】']])
    assert.equal(judge('待发送',{...authored,answerPolicy:{version:1,acceptedAliases:aliases}}),'win');
  // Java String.trim preserves NBSP, so 201 retained UTF16 units exceed the policy limit.
  assert.equal(judge('x'.repeat(199),{...authored,answerPolicy:{version:1,acceptedAliases:['\u00a0'+'x'.repeat(199)+'\u00a0']}}),'fail');
  // ASCII boundary controls are trimmed by Java before the length check.
  assert.equal(judge('待发送',{...authored,answerPolicy:{version:1,acceptedAliases:['\x00 待发送\x1f']}}),'win');
  assert.equal(judge('待发送',{...authored,qa:{answerText:'\u00a0'},answerPolicy:{version:1,acceptedAliases:['待发送']}}),'win');
  assert.equal(judge('待发送',{...authored,qa:{answerText:'\u3000'},answerPolicy:{version:1,acceptedAliases:['待发送']}}),'fail');
  for (const answerText of ['\x00', '\x00 \x1f'])
    assert.equal(judge('待发送',{...authored,qa:{answerText},answerPolicy:{version:1,acceptedAliases:['待发送']}}),'fail');
});
const caps=[{key:'opening-card',type:'cap',cap:'open',content:' 还没送出去 '},
  {key:'letter',type:'text',content:'第二封回信正在排队。'},
  {key:'ending-card',type:'cap',cap:'end',content:' 等下一封。 '}];
function pair(){return {
  sent:{name:'私有回归夹具',chapters:[{name:'迟到的六分钟',nodes:[],blocks:structuredClone(caps)}]},
  got:{isDraft:true,topic:{id:501,status:0,name:'私有回归夹具'},chapters:[{id:101,topicId:501,name:'迟到的六分钟',cmsTopicNodeList:[],blocks:caps.map(b=>({...b,...(b.type==='cap'?{cap:b.cap.trim(),content:b.content.trim()}:{})}))}]}
};}
test('DRAFT-CAP-01: normal materialized start/end cap readback confirms',()=>{
  const {sent,got}=pair(); assert.equal(draft.readbackIssue(sent,got,true),'');
});
test('cap negative controls preserve content, key, card kind, count, ownership and unknown-type checks',()=>{
  for(const change of [b=>{b[0].content='已被改写';},b=>{b[0].key='other';},b=>{b[0].cap='end';},
    b=>{b[0].cap='middle';},b=>{b[0].content='';},b=>{b[0].content='长'.repeat(501);},
    b=>{b[0].type='unregistered';},b=>{b[0].content='\u00a0还没送出去\u00a0';},b=>{b.pop();},b=>{b.reverse();}]) {
    const {sent,got}=pair();change(got.chapters[0].blocks);assert.notEqual(draft.readbackIssue(sent,got,true),'');
  }
  const {sent,got}=pair();got.chapters[0].topicId=999;assert.match(draft.readbackIssue(sent,got,true),/归属/);
});
test('DRAFT-RETRY-01: pending recovery precedes current full-form validation; regular publish still validates',()=>{
  const src=fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.js'),'utf8');
  const begin=src.indexOf('  submitForm() {');
  const stop=src.indexOf('    // 经典定向团',begin);
  const prefix=src.slice(begin+'  submitForm() {'.length,stop);
  const fn=new Function('app','cyToast',prefix);
  function fixture(pending,busy=false,loading=false){
    const hits=[];const page={data:{submitting:false,editingTopicId:'501',editLoaded:!loading},_precheckAcked:true,
      _serverDraftAttempt:pending?{topicId:'501',payload:{name:'original'}}:null,
      _serverDraftBusy:()=>busy,saveServerDraft(){hits.push('recover');},validateForm(){hits.push('validate-current');return false;}};
    fn.call(page,{},text=>hits.push('toast'));return {hits,page};
  }
  const pending=fixture(true);assert.deepEqual(pending.hits,['recover']);assert.equal(pending.page._precheckAcked,false);
  assert.deepEqual(fixture(false).hits,['validate-current']);
  assert.deepEqual(fixture(true,true).hits,['toast']);assert.deepEqual(fixture(true,false,true).hits,['toast']);
});

test('pending recovery reads only the original topic and preserves later local changes',()=>{
  const {sent,got}=pair(), calls=[];let failure;
  const saver=draft.createServerDraftSaver({
    save(){throw new Error('recovery must not write');},
    publish(){throw new Error('draft recovery must not publish');},
    readback(id,callback){calls.push(id);callback({ok:true,data:got});}
  });
  saver.save(sent,{recoverSave:true,recoveryTopicId:501,hasChanges:()=>true,
    onSuccess(){throw new Error('later local changes must not be replaced');},onFail(result){failure=result;}});
  assert.deepEqual(calls,[501]);assert.equal(failure.localChanges,true);assert.equal(failure.unknown,false);
});


test('32 distinct aliases use the real QA builder;33 and PICK display labels stay rejected',()=>{
  const builder=require('../../pages/publish/utils/publish/advanced-game-preview.js');
  const aliases=Array.from({length:32},(_,i)=>'alias'+i);
  const advanced={qa:{enabled:true,mode:'TYPE',title:'Synthetic',answerText:'CANON'},
    answerPolicy:{version:1,normalizationPolicy:'LEGACY_V1',acceptedAliases:aliases}};
  const before=structuredClone(advanced),kit=builder.buildPreviewKit(advanced);
  assert.equal(kit.type,'qa');
  for(const input of aliases) assert.equal(pv.judge(advanced,kit,'submit',{mode:'type',input}),'win');
  assert.equal(pv.judge(advanced,kit,'submit',{mode:'type',input:'CANON'}),'win');
  assert.equal(pv.judge(advanced,kit,'submit',{mode:'type',input:'wrong'}),'fail');
  const over={...advanced,answerPolicy:{...advanced.answerPolicy,acceptedAliases:[...aliases,'alias32']}};
  assert.equal(pv.judge(over,kit,'submit',{mode:'type',input:'alias32'}),'fail');
  assert.equal(pv.judge(over,kit,'submit',{mode:'type',input:'CANON'}),'win');
  const pick={...advanced,qa:{enabled:true,mode:'PICK',title:'Synthetic',options:[
    {id:'real-id',label:'alias31',correct:true},{id:'wrong-id',label:'Other',correct:false}]}};
  const pickKit=builder.buildPreviewKit(pick);
  assert.equal(pv.judge(pick,pickKit,'submit',{mode:'pick',optionId:'alias31'}),'fail');
  assert.equal(pv.judge(pick,pickKit,'submit',{mode:'pick',optionId:'real-id'}),'win');
  assert.deepEqual(advanced,before);
});
