'use strict';
const route = require('./topic-route-graph');
const clone = value => JSON.parse(JSON.stringify(value));
const options = pairs => pairs.map(([value, label]) => ({ value, label }));
const conditionTypes = options([['AND','全部满足'],['OR','任一满足'],['NOT','不满足'],['VISIT_COUNT','访问次数'],['ROLE_IN','角色'],['HAS_CONDITION','持有状态'],['ITEM_EQUIPPED','已装备物品'],['CHECK_RESULT','检定结果'],['NODE_COMPLETED','已完成节点'],['HAS_TAG','持有标签'],['EQ','数值等于'],['NE','数值不等于'],['GT','数值大于'],['GTE','数值至少'],['LT','数值小于'],['LTE','数值至多']]);
const effectTypes = options([['ADD_ITEM','发放待领取物品'],['GRANT_BADGE','授予徽章'],['GRANT_XP','授予经验'],['ADD_CONDITION','添加状态'],['REMOVE_CONDITION','移除状态'],['EQUIP','装备已持有物品'],['UNEQUIP','卸下物品'],['TICK_CLOCK','推进进度钟'],['SET','设置数值'],['INC','增加数值'],['ADD_TAG','添加标签']]);
const comparison = options([['EQ','等于'],['NE','不等于'],['GT','大于'],['GTE','至少'],['LT','小于'],['LTE','至多']]);
const text = (key,label,extra) => Object.assign({key,label,kind:'text'},extra);
const number = (key,label) => text(key,label,{kind:'number'});
function fields(row, nodes, variables) {
  const node = text('nodeId','节点',{kind:'pick',options:nodes.map(n=>({value:String(n.id),label:n.label || n.name || String(n.id)}))});
  const variable = text('var','数值键',{kind:'pick',options:Object.keys(variables).map(k=>({value:k,label:k}))});
  switch(row.op) {
    case 'VISIT_COUNT': return [node,text('cmp','比较方式',{kind:'pick',options:comparison,default:'GTE'}),number('value','次数（0–99）')];
    case 'CHECK_RESULT': return [node,text('result','结果',{kind:'pick',options:options([['PASS','通过'],['FAIL','失败']])})];
    case 'NODE_COMPLETED': return [node];
    case 'ROLE_IN': return [text('values','角色代码（逗号分隔，最多10项）',{kind:'list'})];
    case 'HAS_CONDITION': case 'ADD_CONDITION': case 'REMOVE_CONDITION': return [text('value','状态代码')];
    case 'ITEM_EQUIPPED': case 'UNEQUIP': return [text('itemId','物品代码')];
    case 'ADD_ITEM': return [text('itemId','物品代码'),number('quantity','数量（1–99）')];
    case 'EQUIP': return [text('itemId','物品代码'),text('slot','装备位置（选填）',{optional:true})];
    case 'GRANT_BADGE': return [text('value','徽章代码')];
    case 'GRANT_XP': return [number('value','经验（1–100）'),text('id','发放标识（选填；同节点同标识只发一次）',{optional:true})];
    case 'TICK_CLOCK': return [Object.assign({},variable,{options:variable.options.filter(o=>o.value.startsWith('counter.'))}),number('value','推进格数'),number('max','总格数（2–12）')];
    case 'HAS_TAG': case 'ADD_TAG': return [text('value','标签键（tag.开头）')];
    case 'AND': case 'OR': case 'NOT': return [];
    default: return conditionTypes.concat(effectTypes).some(t=>t.value===row.op) ? [variable,number('value','数值')] : [];
  }
}
function rows(value,nodes,variables) {
  const out=[];
  function walk(row,path,depth,kind) {
    row=row && typeof row==='object' ? row : {};
    const types=kind==='condition'?conditionTypes:effectTypes;
    const type=types.find(t=>t.value===row.op);
    const params=fields(row,nodes,variables).map(f=>{
      const raw=row[f.key]===undefined && f.key==='nodeId'?row.nodeKey:row[f.key];
      const value=raw===undefined?(f.default || ''):raw;
      const selected=(f.options || []).find(o=>o.value===String(value));
      return Object.assign({},f,{selectedIndex:Math.max(0,(f.options || []).findIndex(o=>o.value===String(value))),value:Array.isArray(value)?value.join(','):value,display:selected?selected.label:(value===''?'请选择':String(value)+'（请核对）')});
    });
    out.push({path,depth,kind,op:row.op || '',label:type?type.label:'不支持的规则：'+(row.op || '空'),typeIndex:Math.max(0,types.findIndex(t=>t.value===row.op)),types,fields:params,
      canAdd:kind==='condition' && depth<3 && (['AND','OR'].includes(row.op) && (row.conditions || []).length<10 || row.op==='NOT' && !row.condition),
      unsupported:!type});
    if(depth>=3) return;
    if(['AND','OR'].includes(row.op) && Array.isArray(row.conditions)) row.conditions.forEach((c,i)=>walk(c,path+'.conditions.'+i,depth+1,kind));
    if(row.op==='NOT' && row.condition) walk(row.condition,path+'.condition',depth+1,kind);
  }
  (value.conditions || []).forEach((r,i)=>walk(r,'conditions.'+i,0,'condition'));
  (value.effects || []).forEach((r,i)=>walk(r,'effects.'+i,0,'effect'));
  return out;
}
function location(root,path) {
  if(!/^(conditions|effects)\.\d+(\.(conditions\.\d+|condition))*$/.test(path)) throw Error('规则位置已变化，请重新打开');
  const keys=path.split('.');let parent=root;
  for(const key of keys.slice(0,-1)) { if(!parent || !Object.prototype.hasOwnProperty.call(parent,key)) throw Error('规则已不存在');parent=parent[key]; }
  return {parent,key:keys[keys.length-1]};
}
function change(value,action,nodes=[],variables={}) {
  const out=clone(value || {conditions:[],effects:[]});
  if(action.type==='addRoot') {
    const kind=action.kind==='effect'?'effects':'conditions';out[kind]=out[kind] || [];
    out[kind].push({op:kind==='effects'?'ADD_CONDITION':'HAS_CONDITION',value:''});return out;
  }
  const loc=location(out,action.path); const row=loc.parent[loc.key];if(!row) throw Error('规则已不存在');
  const descriptor=rows(out,nodes,variables).find(r=>r.path===action.path);
  if(action.type==='remove') { if(Array.isArray(loc.parent)) loc.parent.splice(Number(loc.key),1);else delete loc.parent[loc.key]; }
  else if(action.type==='op') {
    if(!descriptor.types.some(t=>t.value===action.value)) throw Error('不支持的规则');
    if(row.op===action.value) return out;
    if(['AND','OR'].includes(row.op) && ['AND','OR'].includes(action.value)) { row.op=action.value; return out; }
    if(['AND','OR','NOT'].includes(action.value) && descriptor.depth>=3) throw Error('条件组合最多嵌套3层');
    const next={op:action.value}; if(['AND','OR'].includes(action.value)) next.conditions=[];
    if(action.value==='VISIT_COUNT') next.cmp='GTE';
    loc.parent[loc.key]=next;
  } else if(action.type==='addChild') {
    if(!descriptor.canAdd) throw Error('不能继续添加子条件');
    if(row.op==='NOT') row.condition={op:'HAS_CONDITION',value:''};else row.conditions.push({op:'HAS_CONDITION',value:''});
  } else if(action.type==='field') {
    const f=descriptor.fields.find(f=>f.key===action.key);if(!f) throw Error('不支持的参数');
    let val=String(action.value==null?'':action.value).trim();
    if(f.optional && !val) delete row[f.key];
    else {
      if(f.kind==='pick' && !f.options.some(o=>o.value===val)) throw Error('请选择有效选项');
      if(f.kind==='number' && val!=='' && /^-?\d+$/.test(val) && Number.isSafeInteger(Number(val))) val=Number(val);
      if(f.kind==='list') val=val?val.split(/[,，]/).map(v=>v.trim()):[];
      row[f.key]=val;
      if(f.key==='nodeId') delete row.nodeKey;
    }
  } else throw Error('不支持的编辑操作');
  return out;
}
function errors(value,nodes,variables) {
  const ids=nodes.map(n=>String(n.id));
  const graph={schemaVersion:1,startNodeId:ids[0] || '',terminalNodeIds:ids.slice(-1),variables,
    edges:[{id:'author_rules',fromNodeId:ids[0] || '',toNodeId:ids[ids.length-1] || '',trigger:{type:'CHOICE',outcomeCode:'GO'},conditions:value.conditions || [],effects:value.effects || []}],fallbacks:[]};
  return route.validate(graph,nodes).errors.filter(e=>/^(conditions|effects)\[/.test(e.field || ''));
}
module.exports={conditionTypes,effectTypes,rows,change,errors};
