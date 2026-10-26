'use strict';
// Internal author contract. The transport adapter must map the writer's actual API to this shape.
const KEY=/^(clue|relation|tag|counter)\.[a-z][a-z0-9_]{0,47}$/;
const clone=x=>JSON.parse(JSON.stringify(x));
const stable=x=>JSON.stringify(x,(_,v)=>v && typeof v==='object' && !Array.isArray(v)?Object.keys(v).sort().reduce((a,k)=>{a[k]=v[k];return a;},{}):v);
function version(value) {
  if(typeof value==='number' && !Number.isSafeInteger(value)) throw Error('版本号精度不完整，请重新读取');
  const s=String(value==null?'':value);
  if(!/^(0|[1-9][0-9]{0,18})$/.test(s) || s.length===19 && s>'9223372036854775807') throw Error('缺少有效版本，请重新读取');
  return s;
}
function parse(raw) {
  const value=typeof raw==='string'?JSON.parse(raw || '{}'):clone(raw || {});
  if(!value || typeof value!=='object' || Array.isArray(value)) throw Error('属性规则格式不正确');
  return value;
}
function binding(raw) {
  if(!raw || raw.schemaVersion!==1 || Object.keys(raw).some(k=>!['schemaVersion','registryVersion','enabledKeys'].includes(k))) throw Error('属性引用格式不正确');
  if(!Array.isArray(raw.enabledKeys) || raw.enabledKeys.length>32 || raw.enabledKeys.some(k=>typeof k!=='string' || !KEY.test(k)) || new Set(raw.enabledKeys).size!==raw.enabledKeys.length) throw Error('启用属性须为不重复的已注册键，最多32项');
  if(typeof raw.registryVersion!=='string' || !/^[1-9][0-9]*$/.test(raw.registryVersion)) throw Error('注册版本必须是正整数的规范字符串');
  return {schemaVersion:1,registryVersion:version(raw.registryVersion),enabledKeys:raw.enabledKeys.slice()};
}
const fields=[['label','名称','label'],['initialValue','初值','initial'],['minValue','下限','min'],['maxValue','上限','max'],['visible','可见性','visible'],['persistScope','保留范围','persistScope'],['displayType','显示方式','displayType'],['color','颜色','color'],['icon','图标','icon'],['sortOrder','排序','sortOrder'],['rulesJson','显示规则','rulesJson']];
function snapshot(raw,topicId) {
  if(!raw || String(raw.topicId)!==String(topicId) || !Array.isArray(raw.definitions)) throw Error('注册定义不属于当前主题或尚未完整读取');
  const seen=new Set();
  const definitions=raw.definitions.map(row=>{
    if(!row || !KEY.test(row.stateKey || '') || seen.has(row.stateKey)) throw Error('注册定义包含无效或重复键');
    seen.add(row.stateKey);
    const type={clue:'flag',tag:'tag',counter:'counter',relation:'relation'}[row.stateKey.split('.')[0]];
    if(row.keyType!==type) throw Error('原键类型不一致，不能直接统一定义');
    if(!['run','topic'].includes(row.persistScope)) throw Error('属性保留范围未知，不能自动继承');
    if(fields.some(([key])=>!Object.prototype.hasOwnProperty.call(row,key)) || !['run','party','deployment'].includes(row.scope)) throw Error('注册定义不完整，请重新读取完整版本');
    if(![row.initialValue,row.minValue,row.maxValue].every(Number.isSafeInteger) || row.minValue>row.initialValue || row.initialValue>row.maxValue || typeof row.visible!=='boolean') throw Error('注册定义的初值、范围或可见性不完整');
    return clone(row);
  });
  const checked=binding({schemaVersion:1,registryVersion:raw.registryVersion,enabledKeys:[]});
  return {topicId:String(topicId),registryVersion:checked.registryVersion,definitions};
}
function session(rawRules,rawSnapshot,context) {
  const rules=parse(rawRules),registry=snapshot(rawSnapshot,context.topicId);
  const current=rules.attributeBinding?binding(rules.attributeBinding):null;
  return {rules,registry,context:{topicId:String(context.topicId),configVersion:version(context.configVersion)},
    original:typeof rawRules==='string'?rawRules:JSON.stringify(rawRules || {}),
    enabledKeys:current?current.enabledKeys.slice():[], fingerprint:stable([rules,registry,context.topicId,version(context.configVersion)])};
}
function conflicts(s,enabledKeys) {
  return enabledKeys.map(key=>{
    const registered=s.registry.definitions.find(d=>d.stateKey===key);
    if(!registered) throw Error('属性未注册：'+key);
    let legacy=(s.rules.attributes || []).find(a=>a.key===key);
    if(s.rules.attributeDefinition) {
      try {
        frozenReadback(s.rules,s.context.configVersion,s.context.topicId);
        const frozen=s.rules.attributeDefinition.attributes.find(a=>a.key===key);
        if(frozen) legacy=Object.assign({},frozen,{rulesJson:frozen.rules===null?null:JSON.stringify(frozen.rules)});
      } catch(incomplete) { /* Partial snapshots remain explicit missing-field conflicts. */ }
    }
    const differences=legacy?fields.filter(([key,,old])=>!Object.prototype.hasOwnProperty.call(legacy,old) || stable(legacy[old])!==stable(registered[key])).map(([key,label,old])=>({field:key,label,previous:Object.prototype.hasOwnProperty.call(legacy,old)?String(legacy[old]):'旧配置未记录',registered:registered[key]===null?'未设置':String(registered[key])})):[];
    if(legacy && legacy.keyType && legacy.keyType!==registered.keyType) throw Error('原键类型不能改变：'+key);
    return {key,label:registered.label,differences};
  }).filter(r=>r.differences.length);
}
function confirm(s,enabledKeys,acceptedKeys) {
  const next=binding({schemaVersion:1,registryVersion:s.registry.registryVersion,enabledKeys});
  const differences=conflicts(s,next.enabledKeys);
  if(differences.some(d=>!acceptedKeys.includes(d.key))) throw Error('请逐项确认差异，或先调整注册定义');
  for(const link of s.rules.statLinks || []) if(!next.enabledKeys.includes(link.sourceKey)) throw Error('仍有数值联动引用未启用属性：'+link.sourceKey);
  const rules=clone(s.rules);delete rules.attributeSnapshot;delete rules.attributeDefinition;
  rules.attributes=projection(s.registry,next.enabledKeys);
  rules.attributeBinding=next;
  if(next.enabledKeys.length) rules.stateEnabled=true;
  return {rules:JSON.stringify(rules),registryVersion:next.registryVersion,configVersion:s.context.configVersion,
    sourceRules:s.original,fingerprint:s.fingerprint,enabledKeys:next.enabledKeys,acceptedKeys:acceptedKeys.slice()};
}
function projection(registry,enabledKeys) {
  return enabledKeys.map(key=>{
    const row=registry.definitions.find(r=>r.stateKey===key);if(!row)throw Error('属性未注册：'+key);
    return {key,label:row.label,initial:row.initialValue,min:row.minValue,max:row.maxValue,visible:row.visible};
  });
}
function requestRules(raw,loadedRaw,registry) {
  const rules=parse(raw),loaded=parse(loadedRaw);
  if(!rules.attributeBinding) {
    if(loaded.attributeBinding) throw Error('不能用旧属性副本覆盖注册引用，请重新读取');
    return raw; // Legacy payload stays byte-for-byte intact; conversion is always explicit.
  }
  rules.attributeBinding=binding(rules.attributeBinding);
  if(registry) {
    const current=snapshot(registry,registry.topicId);
    if(current.registryVersion!==rules.attributeBinding.registryVersion) throw Error('注册版本已变化，请重新确认差异');
    const asserted=projection(current,rules.attributeBinding.enabledKeys);
    if(stable(rules.attributes)!==stable(asserted)) throw Error('属性定义已由注册表管理，请确认差异后保存');
  } else if(stable(rules.attributeBinding)!==stable(loaded.attributeBinding) || stable(rules.attributes)!==stable(loaded.attributes)) {
    throw Error('请先读取注册版本并确认属性定义');
  }
  for(const link of rules.statLinks || []) if(!rules.attributeBinding.enabledKeys.includes(link.sourceKey)) throw Error('数值联动引用了未启用属性：'+link.sourceKey);
  delete rules.attributeDefinition;delete rules.attributeSnapshot;
  return JSON.stringify(rules);
}
function frozenReadback(rawRules,configVersion,topicId) {
  const rules=parse(rawRules);
  if(!rules.attributeBinding) return;
  const bound=binding(rules.attributeBinding),def=rules.attributeDefinition;
  if(!def || def.schemaVersion!==1 || def.configVersion!==version(configVersion) || def.registryVersion!==bound.registryVersion || !Array.isArray(def.attributes)) throw Error('云端属性冻结版本不一致或不完整');
  const definitions=def.attributes.map(row=>{
    const required=['key','keyType','label','initial','min','max','visible','displayType','persistScope','color','icon','sortOrder','rules'];
    if(!row || required.some(k=>!Object.prototype.hasOwnProperty.call(row,k))) throw Error('云端属性冻结定义缺少字段');
    return {stateKey:row.key,keyType:row.keyType,scope:'run',label:row.label,initialValue:row.initial,minValue:row.min,maxValue:row.max,visible:row.visible,displayType:row.displayType,persistScope:row.persistScope,color:row.color,icon:row.icon,sortOrder:row.sortOrder,rulesJson:row.rules===null?null:JSON.stringify(row.rules)};
  });
  const registry=snapshot({topicId,registryVersion:def.registryVersion,definitions},topicId);
  if(stable(definitions.map(row=>row.stateKey).sort())!==stable(bound.enabledKeys.slice().sort()) || stable(projection(registry,bound.enabledKeys))!==stable(rules.attributes)) throw Error('云端启用键或属性投影与冻结定义不一致');
}
module.exports={version,parse,binding,snapshot,session,conflicts,confirm,requestRules,projection,frozenReadback};
