const {test}=require('node:test');const assert=require('node:assert/strict');
const contract=require('../../pages/publish/utils/publish/attribute-binding');
const definition={stateKey:'counter.hp',keyType:'counter',scope:'run',label:'体力',initialValue:3,minValue:0,maxValue:20,visible:true,persistScope:'topic',displayType:'bar',color:null,icon:null,sortOrder:null,rulesJson:null};
const snapshot={topicId:'501',registryVersion:'2',definitions:[definition]};
const raw=JSON.stringify({schemaVersion:1,stateEnabled:true,attributes:[{key:'counter.hp',label:'旧体力',initial:3,min:-10000,max:10000,visible:true}],natAuto:false});
function session(r=raw,s=snapshot){return contract.session(r,s,{topicId:'501',configVersion:'3'})}
function confirmed(){return contract.confirm(session(),['counter.hp'],['counter.hp'])}
test('DEC02未确认不统一差异；显式确认绑定两个版本且不改原key类型',()=>{
 const s=session();assert.deepEqual(s.enabledKeys,[],'旧副本不自动启用');const differences=contract.conflicts(s,['counter.hp']);
 assert.ok(differences[0].differences.some(d=>d.field==='maxValue' && d.previous==='10000' && d.registered==='20'));
 assert.throws(()=>contract.confirm(s,['counter.hp'],[]),/逐项确认/);
 const result=confirmed(),rules=JSON.parse(result.rules);assert.equal(result.configVersion,'3');assert.equal(result.registryVersion,'2');assert.equal(rules.natAuto,false);
 assert.deepEqual(rules.attributeBinding,{schemaVersion:1,registryVersion:'2',enabledKeys:['counter.hp']});
 assert.deepEqual(rules.attributes,[{key:'counter.hp',label:'体力',initial:3,min:0,max:20,visible:true}]);
 assert.equal(raw,session().original);
});
test('注册版本严格使用正Long字符串；缺字段和未知持久范围不补默认',()=>{
 for(const v of [2,0,'0','02','2.0','9223372036854775808',null])assert.throws(()=>session(raw,{...snapshot,registryVersion:v}));
 assert.equal(session(raw,{...snapshot,registryVersion:'9223372036854775807'}).registry.registryVersion,'9223372036854775807');
 for(const field of ['persistScope','visible','minValue','color']){const row={...definition};delete row[field];assert.throws(()=>session(raw,{...snapshot,definitions:[row]}));}
 assert.throws(()=>session(raw,{...snapshot,definitions:[{...definition,persistScope:'unknown'}]}),/未知/);
 assert.throws(()=>session(raw,{...snapshot,definitions:[{...definition,keyType:'tag'}]}),/类型/);
});
test('旧客户端载荷保持字节；已绑定规则不能退回旧副本或带未启用联动',()=>{
 const legacy=' {"schemaVersion":1,"natAuto":false} ';assert.equal(contract.requestRules(legacy,''),legacy);
 const bound=confirmed().rules;assert.throws(()=>contract.requestRules(legacy,bound),/旧属性副本/);
 assert.throws(()=>contract.requestRules(bound,'',{...snapshot,registryVersion:'3'}),/版本已变化/);
 const outgoing=JSON.parse(contract.requestRules(bound,'',snapshot));assert.deepEqual(outgoing.attributeBinding.enabledKeys,['counter.hp']);
 const modified=JSON.parse(bound);modified.attributes[0].max=100;assert.throws(()=>contract.requestRules(JSON.stringify(modified),'',snapshot),/确认差异/);
 const linked=JSON.stringify({schemaVersion:1,statLinks:[{sourceKey:'counter.hp'}]});assert.throws(()=>contract.confirm(session(linked),[],[]),/未启用/);
});
function component(){let cfg;global.Component=c=>cfg=c;delete require.cache[require.resolve('../../pages/publish/components/attribute-binding-editor/index')];require('../../pages/publish/components/attribute-binding-editor/index');const events=[];const c={...cfg.methods,data:{...structuredClone(cfg.data),rules:raw,snapshot,topicId:'501',configVersion:'3',readOnly:false},setData(p){Object.assign(this.data,p)},triggerEvent(name,data){events.push({name,data})}};cfg.lifetimes.attached.call(c);return {c,events,cfg};}
test('真实Component逐项确认，刷新注册版本撤销旧确认，离开后不发事件',()=>{
 const {c,events,cfg}=component();c.select({detail:{value:['counter.hp']}});c.confirm();assert.match(c.data.error,/逐项确认/);assert.equal(events.length,0);
 c.accept({detail:{value:['counter.hp']}});c.confirm();assert.equal(events[0].data.registryVersion,'2');
 c.data.snapshot={...snapshot,registryVersion:'3'};cfg.observers['rules,snapshot,topicId,configVersion'].call(c);c.confirm();assert.equal(events.length,1);
 cfg.lifetimes.detached.call(c);c.accept({detail:{value:['counter.hp']}});c.confirm();assert.equal(events.length,1);
});
function page(){const sent=[],stored={};let member='7',cfg;global.wx={getStorageSync:k=>stored[k],setStorageSync:(k,v)=>stored[k]=v,getSystemInfoSync:()=>({}),showToast(){},showLoading(){},hideLoading(){}};global.getCurrentPages=()=>[];global.getApp=()=>({globalData:{},getUserID:()=>member,sendRequest:o=>sent.push(o),getRequestErrorMessage:(e,f)=>e.message || f});global.Page=c=>cfg=c;delete require.cache[require.resolve('../../pages/publish/fabu/index')];require('../../pages/publish/fabu/index');const p={...cfg,data:structuredClone(cfg.data)};p.setData=(patch,cb)=>{for(const [key,value] of Object.entries(patch)){const parts=key.split('.');let obj=p.data;for(const part of parts.slice(0,-1))obj=obj[part];obj[parts.at(-1)]=value;}if(cb)cb()};Object.assign(p.data,{editingTopicId:'501',draftMemberId:'7',editLoaded:true,editIsDraft:true,operationScope:''});Object.assign(p.data.formData,{configVersion:3,journeyRules:raw});p.data.initialStateEditor={show:true,rules:contract.parse(raw),values:[],error:''};return {p,sent,stored,switchMember:()=>member='8'};}
test('真实Page读取绑定主题/配置版本，确认只改引用和投影，切号迟到无效',()=>{
 const h=page();h.p.loadAttributeRegistry();assert.equal(h.sent[0].url,'/api/topic/attribute-binding');h.sent[0].success({code:200,data:{...snapshot,configVersion:'3'}});
 h.p.onAttributeBindingConfirm({detail:confirmed()});const rules=JSON.parse(h.p.data.formData.journeyRules);assert.deepEqual(rules.attributeBinding.enabledKeys,['counter.hp']);assert.equal(rules.natAuto,false);
 assert.ok(Object.keys(h.stored).length);const before=h.p.data.formData.journeyRules;h.p.loadAttributeRegistry();h.switchMember();h.sent[1].success({code:200,data:{...snapshot,registryVersion:'3',configVersion:'3'}});assert.equal(h.p.data.attributeRegistrySnapshot,null);assert.equal(h.p.data.formData.journeyRules,before);
});
test('真实Page拒绝旧规则/旧configVersion确认，不把读失败当可写',()=>{
 const h=page();h.p.loadAttributeRegistry();h.sent[0].success({code:200,data:{...snapshot,configVersion:'3'}});
 h.p.data.formData.journeyRules=JSON.stringify({...JSON.parse(raw),natAuto:true});h.p.onAttributeBindingConfirm({detail:confirmed()});assert.match(h.p.data.initialStateEditor.error,/已变化/);assert.equal(JSON.parse(h.p.data.formData.journeyRules).attributeBinding,undefined);
 h.p.loadAttributeRegistry();h.sent[1].success({code:200,data:{...snapshot,configVersion:'4'}});assert.equal(h.p._attributeRegistryWriteReady,false);assert.match(h.p.data.attributeRegistryError,/版本已变化/);
});
function frozenRules(){const rules=JSON.parse(confirmed().rules);rules.attributeDefinition={schemaVersion:1,configVersion:'4',registryVersion:'2',attributes:[{key:'counter.hp',keyType:'counter',label:'体力',initial:3,min:0,max:20,visible:true,displayType:'bar',persistScope:'topic',color:null,icon:null,sortOrder:null,rules:null}]};return rules;}
test('云保存回读核完整冻结定义与版本，不把旧partial或缺字段当保存成功',()=>{
 const rules=frozenRules();contract.frozenReadback(rules,'4','501');
 for(const field of ['color','persistScope','initial']){const bad=structuredClone(rules);delete bad.attributeDefinition.attributes[0][field];assert.throws(()=>contract.frozenReadback(bad,'4','501'),/缺少字段/);}
 assert.throws(()=>contract.frozenReadback(rules,'5','501'),/版本/);
 const bad=structuredClone(rules);bad.attributeDefinition.attributes[0].max=19;assert.throws(()=>contract.frozenReadback(bad,'4','501'),/投影/);
});
test('真实Page发布使用保存回读的双版本，UNKNOWN本地保留原版本',()=>{
 const h=page(),p=h.p;let pendingCheck;p._topicPublishCheck=(id,cb)=>pendingCheck=cb;
 const saver=p.getServerDraftSaver(),rules=confirmed().rules;
 const payload={name:'测试',configVersion:'3',journeyRules:rules,chapters:[]};let failure;
 saver.saveAndPublish(payload,{onFail:e=>failure=e});
 h.sent[0].success({code:200,data:{topicId:501}});
 h.sent[1].success({code:200,data:{isDraft:true,topic:{id:501,status:0,name:'测试',configVersion:'4',journeyRulesJson:JSON.stringify(frozenRules())},chapters:[]}});
 assert.equal(failure,undefined);assert.equal(typeof pendingCheck,'function');
 p.data.formData.configVersion='5';pendingCheck({ok:true});
 const publish=h.sent.find(r=>r.url==='/api/topic/publish-draft');assert.ok(publish);
 assert.equal(publish.data.configVersion,'4');assert.equal(publish.data.registryVersion,'2');
 assert.equal(p._publishAttempt.configVersion,'4');assert.equal(p._publishAttempt.registryVersion,'2');
 assert.ok(Object.values(h.stored).some(v=>v && v.publishAttempt && v.publishAttempt.configVersion==='4'));
});
test('重进后完整冻结元数据可显示原定义；注册保留范围变化必须重新确认',()=>{
 const complete=JSON.stringify(frozenRules());const s=contract.session(complete,snapshot,{topicId:'501',configVersion:'4'});
 assert.deepEqual(contract.conflicts(s,['counter.hp']),[]);
 const changed=contract.session(complete,{...snapshot,registryVersion:'3',definitions:[{...definition,persistScope:'run'}]},{topicId:'501',configVersion:'4'});
 assert.throws(()=>contract.confirm(changed,['counter.hp'],[]),/逐项确认/);
 assert.ok(contract.conflicts(changed,['counter.hp'])[0].differences.some(d=>d.field==='persistScope' && d.previous==='topic' && d.registered==='run'));
});
test('绑定定义仍可编辑statLinks，不能借旧数值面板覆盖注册定义',()=>{
 const {p}=page();p.data.formData.journeyRules=confirmed().rules;p._syncRuntimeStats=()=>{};
 const row=JSON.parse(confirmed().rules).attributes[0];p.data.runtimeHpLabel='体力';
 p.data.valueEditor={show:true,row:{...row},links:[{id:'hp_cost',sourceKey:'counter.hp',targetKey:'sys.hp',trigger:'PER_EVENT',frequency:'EVENT',amount:1,allowedReasons:['TASK_COST']}]};
 p.saveValueEditor();const saved=JSON.parse(p.data.formData.journeyRules);assert.equal(saved.statLinks.length,1);assert.deepEqual(saved.attributes,[row]);assert.equal(saved.attributeBinding.registryVersion,'2');
 p.data.valueEditor.show=true;p.data.valueEditor.row.max=100;p.saveValueEditor();assert.match(p.data.valueEditor.error,/定义已变化/);assert.equal(JSON.parse(p.data.formData.journeyRules).attributes[0].max,20);
});

test('属性读取不关闭统一错误提示，读取失败保留规则并结束可写准备',()=>{
 for(const failure of ['business','network']) {
  const h=page(),before=h.p.data.formData.journeyRules;h.p.loadAttributeRegistry();
  const request=h.sent[0];assert.notEqual(request.silentError,true);assert.notEqual(request.autoErrorToast,false);
  assert.equal(request.hideLoading,true,'只读请求不另开加载遮罩');
  if(failure==='business')request.success({code:409,msg:'注册版本已变化'});else request.fail({});
  assert.equal(h.p._attributeRegistryWriteReady,false);assert.equal(h.p.data.attributeRegistrySnapshot,null);
  assert.match(h.p.data.attributeRegistryError,/读取失败|版本已变化/);assert.equal(h.p.data.formData.journeyRules,before);
 }
});
