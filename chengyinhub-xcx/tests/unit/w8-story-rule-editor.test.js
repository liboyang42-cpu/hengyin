const {test}=require('node:test');const assert=require('node:assert/strict');
const form=require('../../pages/publish/utils/publish/story-rule-form');
const nodes=[{id:'9007199254740993',label:'起点'},{id:'node_b',label:'终点'}],variables={'counter.time':0};
function component(value={conditions:[],effects:[]}) {
 let config;global.Component=c=>config=c;delete require.cache[require.resolve('../../pages/publish/components/story-rule-editor/index')];require('../../pages/publish/components/story-rule-editor/index');
 const events=[];const c={...config.methods,data:{...structuredClone(config.data),value,nodes,variables,identity:'edge:x'},setData(p){Object.assign(this.data,p)},triggerEvent(name,event){events.push(event)}};
 config.lifetimes.attached.call(c);return {c,events,detach:()=>config.lifetimes.detached.call(c)};
}
const event=(path,key,value)=>({currentTarget:{dataset:{path,key}},detail:{value}});
const cases=[
 ['condition','VISIT_COUNT',{nodeId:'9007199254740993',cmp:'GTE',value:'2'}],
 ['condition','ROLE_IN',{values:'NAVIGATOR,OBSERVER'}],['condition','HAS_CONDITION',{value:'inspired'}],
 ['condition','ITEM_EQUIPPED',{itemId:'lamp'}],['condition','CHECK_RESULT',{nodeId:'node_b',result:'PASS'}],
 ['effect','ADD_ITEM',{itemId:'lamp',quantity:'2'}],['effect','GRANT_BADGE',{value:'explorer'}],
 ['effect','GRANT_XP',{value:'4',id:'visit'}],['effect','ADD_CONDITION',{value:'inspired'}],
 ['effect','REMOVE_CONDITION',{value:'tired'}],['effect','EQUIP',{itemId:'lamp',slot:'hand'}],
 ['effect','UNEQUIP',{itemId:'lamp'}],['effect','TICK_CLOCK',{var:'counter.time',value:'1',max:'4'}]
];
for(const [kind,op,fields] of cases) test('真实Component编辑 '+op+' 的实际参数并回读',()=>{
 const {c,events}=component();c.add({currentTarget:{dataset:{kind}}});const path=kind==='condition'?'conditions.0':'effects.0';
 c.op(event(path,null,c.data.rows[0].types.findIndex(t=>t.value===op)));
 for(const [key,value] of Object.entries(fields)) {
  const field=c.data.rows[0].fields.find(f=>f.key===key);
  if(field.kind==='pick')c.pick(event(path,key,field.options.findIndex(o=>o.value===value)));else c.input(event(path,key,value));
 }
 assert.deepEqual(form.errors(events.at(-1).value,nodes,variables),[]);
 const reopened=component(events.at(-1).value).c;
 assert.deepEqual(reopened._draft,c._draft);
 if(op==='VISIT_COUNT')assert.equal(reopened._draft.conditions[0].nodeId,'9007199254740993');
});
test('组合树添加/修改/删除保持正确位置，未知规则拒绝，非法数值不变成0',()=>{
 const {c,events}=component();c.add({currentTarget:{dataset:{kind:'condition'}}});
 c.op(event('conditions.0',null,0));c.child({currentTarget:{dataset:{path:'conditions.0'}}});
 c.input(event('conditions.0.conditions.0','value','ready'));
 assert.equal(c._draft.conditions[0].conditions[0].value,'ready');
 c.op(event('conditions.0',null,1));
 assert.equal(c._draft.conditions[0].conditions[0].value,'ready','AND改OR不能丢子条件');
 c.remove({currentTarget:{dataset:{path:'conditions.0.conditions.0'}}});assert.ok(form.errors(c._draft,nodes,variables).length);
 const bad=component({conditions:[{op:'FUTURE_OP'}],effects:[{op:'GRANT_XP',value:2}]});
 assert.ok(bad.c.data.error);bad.c.input(event('effects.0','value',''));assert.equal(bad.c._draft.effects[0].value,'');assert.ok(bad.c.data.error);
 const count=events.length;c._active=false;c.input(event('conditions.0','value','late'));assert.equal(events.length,count);
});
test('NOT树三层限制、空可选参数去除、选项越界不修改与原对象隔离',()=>{
 const source={conditions:[{op:'NOT',condition:{op:'NOT',condition:{op:'NOT',condition:{op:'HAS_CONDITION',value:'ready'}}}}],effects:[{op:'GRANT_XP',value:1,id:'once'}]};
 const {c}=component(source);assert.equal(c.data.error,'');
 assert.throws(()=>form.change(c._draft,{type:'op',path:'conditions.0.condition.condition.condition',value:'AND'},nodes,variables),/3层/);
 c.input(event('effects.0','id',''));assert.equal(c._draft.effects[0].id,undefined);assert.equal(source.effects[0].id,'once');
 const before=structuredClone(c._draft);c.op(event('effects.0',null,999));assert.deepEqual(c._draft,before);
});

test('选择器反映当前值，撤销只回到本次打开前已完成配置，移除未知op后可继续编辑',()=>{
 const h=component({conditions:[{op:'FUTURE_OP'}],effects:[]});h.c.data.baseline={conditions:[{op:'ROLE_IN',values:['OBSERVER']}],effects:[]};
 h.c.remove({currentTarget:{dataset:{path:'conditions.0'}}});assert.equal(h.c.data.error,'');
 h.c.reset();assert.equal(h.events.at(-1).discard,true);assert.deepEqual(h.c._draft,h.c.data.baseline);
 const row=h.c.data.rows[0];assert.equal(row.types[row.typeIndex].value,'ROLE_IN');
 h.detach();const count=h.events.length;h.c.reset();assert.equal(h.events.length,count);
});
test('原生模板接上类型、参数、嵌套、删除和撤销事件，无JSON输入替代',()=>{
 const fs=require('node:fs');const path=require('node:path');const view=fs.readFileSync(path.join(__dirname,'../../pages/publish/components/story-rule-editor/index.wxml'),'utf8');
 for(const name of ['toggle','op','pick','input','child','remove','add','reset'])assert.match(view,new RegExp('bind(?:tap|change|input)="'+name+'"'));
 assert.match(view,/value="\{\{row.typeIndex\}\}"/);assert.match(view,/value="\{\{field.selectedIndex\}\}"/);assert.doesNotMatch(view,/textarea|JSON/);
});

test('原生操作构建NOT→OR组合并配置检定条件，序列化后仍可编辑',()=>{
 const {c}=component();c.add({currentTarget:{dataset:{kind:'condition'}}});
 const choose=(path,op)=>{const row=c.data.rows.find(r=>r.path===path);c.op(event(path,null,row.types.findIndex(t=>t.value===op)))};
 choose('conditions.0','NOT');c.child({currentTarget:{dataset:{path:'conditions.0'}}});
 choose('conditions.0.condition','OR');c.child({currentTarget:{dataset:{path:'conditions.0.condition'}}});
 const path='conditions.0.condition.conditions.0';choose(path,'CHECK_RESULT');
 c.pick(event(path,'nodeId',1));c.pick(event(path,'result',1));
 assert.equal(c.data.error,'');const again=component(JSON.parse(JSON.stringify(c._draft))).c;
 assert.deepEqual(again._draft.conditions,[{op:'NOT',condition:{op:'OR',conditions:[{op:'CHECK_RESULT',nodeId:'node_b',result:'FAIL'}]}}]);
});
