// 10-28 原型 A e-route：story-rule-editor 就地改成句子列表。数据仍是 { conditions, effects }，键名照存；
// 界面只出现名字（'属性 · 观察 至少 3'、'已获得线索 · 半张货单'、'进度钟 · 巡夜人警觉 +1'）。
const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');
const form=require('../../pages/publish/utils/publish/story-rule-form');
const sentence=require('../../pages/publish/utils/publish/story-rule-sentence');
const nodes=[{id:'9007199254740993',label:'旧码头仓库'},{id:'node_b',label:'仓库二层'}];
const journey={stateEnabled:true,attributes:[{key:'counter.observe',name:'观察',initial:2},{key:'counter.nerve',name:'胆量'},{key:'clue.ledger_half',name:'半张货单'},{key:'tag.night',name:'夜行'}],
  rpg:{clocks:[{var:'counter.alarm',label:'巡夜人警觉',segments:6}],aspects:[{key:'hurt',label:'受了伤'}]}};
const runtime={items:[{id:'ilamp',name:'马灯'}]};
const names=sentence.buildNames(journey,runtime,nodes,{'sys.luck':3});
const variables=sentence.editorVariables({'sys.luck':3},names);
function component(value={conditions:[],effects:[]}) {
 let config;global.Component=c=>config=c;delete require.cache[require.resolve('../../pages/publish/components/story-rule-editor/index')];require('../../pages/publish/components/story-rule-editor/index');
 const events=[],layers=[];
 const c={...config.methods,data:{...structuredClone(config.data),value,nodes,variables,names,identity:'edge:x',readOnly:false},
  setData(p,cb){Object.assign(this.data,p);if(cb)cb();},triggerEvent(name,event){(name==='layer'?layers:events).push(event)}};
 config.lifetimes.attached.call(c);return {c,events,layers,detach:()=>config.lifetimes.detached.call(c)};
}
const tap=dataset=>({currentTarget:{dataset}});
// cy-segmented / cy-stepper 的 change：detail.value 带选中值，data-path 在宿主上。
const val=(dataset,value)=>({currentTarget:{dataset},detail:{value}});
const LATIN=/[A-Za-z_]{2,}/;
function visibleText(c) {
 return [...c.data.conditionRows,...c.data.effectRows].map(r=>r.text).concat((c.data.layerRows||[]).flatMap(r=>[r.label,...(r.names||[]).map(n=>n.label)])).join(' ');
}
function add(h,target,kind,key) {
 h.c.openAdd(tap({target}));h.c.onLayerKind(tap({kind}));if(key!==undefined)h.c.onLayerName(tap({key}));
}

test('名单只收有名字的项，属性 / 进度钟补进可用变量（初值同设定）',()=>{
 assert.deepEqual(names.attr.map(r=>r.label),['观察','胆量']);assert.deepEqual(names.clock.map(r=>r.label),['巡夜人警觉']);
 assert.deepEqual(names.clue.map(r=>r.key),['clue.ledger_half']);assert.deepEqual(names.value.map(r=>r.label),['幸运值']);
 assert.equal(variables['counter.observe'],2);assert.equal(variables['counter.alarm'],0);
});

test('原型三句：属性 · 观察 至少 3 / 已获得线索 · 半张货单 / 进度钟 · 巡夜人警觉 +1，存的仍是键',()=>{
 const h=component();
 add(h,'conditions','attr','counter.observe');
 const path=h.c.data.conditionRows[0].path;
 h.c.onAmount(val({path},2));h.c.onAmount(val({path},3));
 add(h,'conditions','clue','clue.ledger_half');
 add(h,'effects','clock','counter.alarm');
 assert.deepEqual(h.c.data.conditionRows.map(r=>r.text),['属性 · 观察 至少 3','已获得线索 · 半张货单']);
 assert.deepEqual(h.c.data.effectRows.map(r=>r.text),['进度钟 · 巡夜人警觉 +1']);
 const saved=h.events.at(-1).value;
 assert.deepEqual(saved.conditions,[{op:'GTE',var:'counter.observe',value:3},{op:'HAS_TAG',value:'clue.ledger_half'}]);
 assert.deepEqual(saved.effects,[{op:'TICK_CLOCK',var:'counter.alarm',value:1,max:6}]);
 assert.deepEqual(form.errors(saved,nodes,variables),[]);
 assert.doesNotMatch(visibleText(h.c),LATIN,'界面不能出现键名或英文');
 assert.equal(h.layers.at(-1).open,false,'选好名字后回到下一站');
});

test('全部满足 / 任一满足：任一满足 = 一条 OR，切回不丢句子',()=>{
 const h=component({conditions:[{op:'HAS_TAG',value:'clue.ledger_half'},{op:'HAS_TAG',value:'tag.night'}],effects:[]});
 assert.equal(h.c.data.showTopSeg,true);
 h.c.onTopMode(val({},'OR'));
 assert.deepEqual(h.events.at(-1).value.conditions,[{op:'OR',conditions:[{op:'HAS_TAG',value:'clue.ledger_half'},{op:'HAS_TAG',value:'tag.night'}]}]);
 assert.deepEqual(h.c.data.conditionRows.map(r=>r.text),['已获得线索 · 半张货单','有标签 · 夜行']);
 h.c.onTopMode(val({},'AND'));assert.equal(h.events.at(-1).value.conditions.length,2);
});

test('点一句就地展开：否定、换名字分段、至少 / 至多 / 正好、删除',()=>{
 const h=component({conditions:[{op:'HAS_TAG',value:'clue.ledger_half'},{op:'GTE',var:'counter.observe',value:3}],effects:[]});
 h.c.toggleRow(tap({path:'conditions.0',type:'leaf'}));
 assert.deepEqual(h.c.data.conditionRows[0].editor.neg.options.map(s=>s.label),['已获得','未获得']);
 h.c.onNegate(val({path:'conditions.0'},'1'));
 assert.deepEqual(h.events.at(-1).value.conditions[0],{op:'NOT',condition:{op:'HAS_TAG',value:'clue.ledger_half'}});
 assert.equal(h.c.data.conditionRows[0].text,'未获得线索 · 半张货单');
 h.c.toggleRow(tap({path:'conditions.1',type:'leaf'}));
 const ed=h.c.data.conditionRows[1].editor;
 assert.deepEqual(ed.names.options.map(n=>n.label),['观察','胆量']);assert.deepEqual(ed.cmp.options.map(s=>s.label),['至少','至多','正好']);
 h.c.onPickName(val({path:'conditions.1'},'counter.nerve'));h.c.onCompare(val({path:'conditions.1'},'2'));
 assert.equal(h.c.data.conditionRows[1].text,'属性 · 胆量 正好 3');
 h.c.onRemove(tap({path:'conditions.0'}));
 assert.deepEqual(h.events.at(-1).value.conditions,[{op:'EQ',var:'counter.nerve',value:3}]);
});

// 只剩一组「任一满足」时与顶层任一满足是同一份数据（一条 OR），所以这里先放一句别的。
test('一组条件：钻进去改全部 / 任一、组里加句子、删除这组',()=>{
 const h=component({conditions:[{op:'HAS_TAG',value:'clue.ledger_half'}],effects:[]});add(h,'conditions','group');
 assert.equal(h.c.data.conditionRows[1].text,'一组条件 · 全部满足 0 条');
 const seg=h.c.data.conditionRows.find(r=>r.type==='groupSeg');assert.ok(seg,'新加的一组直接展开');
 h.c.onGroupMode(val({path:seg.path},'OR'));
 add(h,seg.path,'trait','hurt');
 assert.deepEqual(h.events.at(-1).value.conditions,[{op:'HAS_TAG',value:'clue.ledger_half'},{op:'OR',conditions:[{op:'HAS_CONDITION',value:'hurt'}]}]);
 assert.ok(h.c.data.conditionRows.some(r=>r.text==='有特质 · 受了伤'&&r.depth===1));
 h.c.openAdd(tap({target:seg.path}));assert.ok(!h.c.data.layerRows.some(r=>r.kind==='group'),'组里不再套一组');h.c.closeLayer();
 h.c.onRemove(tap({path:h.c.data.conditionRows.find(r=>r.type==='groupDel').path}));
 assert.deepEqual(h.events.at(-1).value.conditions,[{op:'HAS_TAG',value:'clue.ledger_half'}]);
});

test('添加那一层：种类一列，点一种就地展开可搜索的名字；换名字直接是名单',()=>{
 const h=component({conditions:[],effects:[{op:'ADD_ITEM',itemId:'ilamp',quantity:1}]});
 h.c.openAdd(tap({target:'conditions'}));
 assert.equal(h.layers.at(-1).title,'添加条件');
 assert.deepEqual(h.c.data.layerRows.slice(0,3).map(r=>r.label),['已获得线索','装备着物品','有标签']);
 h.c.onLayerKind(tap({kind:'visit'}));h.c.onLayerQuery({detail:{value:'二层'}});
 assert.deepEqual(h.c.data.layerRows.find(r=>r.open).names.map(n=>n.label),['仓库二层']);
 h.c.onLayerName(tap({key:'node_b'}));
 assert.equal(h.c.data.conditionRows[0].text,'到过节点 · 仓库二层 至少 1 次');
 h.c.toggleRow(tap({path:'effects.0',type:'leaf'}));h.c.onAmount(val({path:'effects.0'},2));
 assert.equal(h.c.data.effectRows[0].text,'给物品 · 马灯 ×2');
 assert.deepEqual(form.errors(h.events.at(-1).value,nodes,variables),[]);
});

test('效果编辑排序、数值增加 / 减少 / 设为；旧的身份条件不显示代码',()=>{
 const h=component({conditions:[{op:'ROLE_IN',values:['NAVIGATOR']}],effects:[{op:'GRANT_XP',value:2},{op:'INC',var:'sys.luck',value:1}]});
 assert.equal(h.c.data.conditionRows[0].text,'不再支持的条件');
 h.c.toggleSort();h.c.onMoveUp(tap({path:'effects.1'}));
 assert.deepEqual(h.events.at(-1).value.effects.map(e=>e.op),['INC','GRANT_XP']);
 h.c.toggleSort();h.c.toggleRow(tap({path:'effects.0',type:'leaf'}));h.c.onVarOp(val({path:'effects.0'},'1'));
 assert.equal(h.c.data.effectRows[0].text,'数值 · 幸运值 −1');
 h.c.onVarOp(val({path:'effects.0'},'2'));assert.deepEqual(h.events.at(-1).value.effects[0],{op:'SET',var:'sys.luck',value:1});
 assert.doesNotMatch(visibleText(h.c),LATIN);
});

test('只读与卸载后不再写；回读同一份数据得到同样的句子',()=>{
 const h=component({conditions:[{op:'CHECK_RESULT',nodeId:'9007199254740993',result:'FAIL'}],effects:[]});
 assert.equal(h.c.data.conditionRows[0].text,'检定结果 · 旧码头仓库 未通过');
 h.detach();const count=h.events.length;h.c.onRemove(tap({path:'conditions.0'}));assert.equal(h.events.length,count);
 const again=component(structuredClone(h.c._draft));assert.equal(again.c.data.conditionRows[0].text,'检定结果 · 旧码头仓库 未通过');
});

test('模板：句子行、分段、步进器、添加层都接上 handler，用 L1 的 cy-list-row / cy-segmented / cy-stepper；不再有原生按钮、▾、代码输入框与撤销按钮',()=>{
 const view=fs.readFileSync(path.join(__dirname,'../../pages/publish/components/story-rule-editor/index.wxml'),'utf8');
 for(const name of ['toggleRow','onTopMode','onGroupMode','onNegate','onPickName','onCompare','onResult','onVarOp','onAmount','onRemove','openAdd','openRename','onLayerKind','onLayerName','toggleSort','onMoveUp'])
  assert.match(view,new RegExp('bind(?::press|:change|:action|tap)="'+name+'"'));
 const json=JSON.parse(fs.readFileSync(path.join(__dirname,'../../pages/publish/components/story-rule-editor/index.json'),'utf8'));
 for(const c of ['list-group','list-row','segmented','stepper']) assert.equal(json.usingComponents['cy-'+c],'/pages/publish/components/'+c+'/index');
 assert.match(view,/bindinput="onLayerQuery"/);
 assert.doesNotMatch(view,/<button|▾|代码|撤销未完成|cy-dropdown|textarea|JSON/);
});

test('注册表是名字真源：同键用注册表的名字，counter 里非属性非进度钟的算数值并补进可用变量',()=>{
 const merged=sentence.mergeRegistry(names,[{stateKey:'clue.ledger_half',label:'半张旧货单'},{stateKey:'tag.k2',label:'被盯上'},
  {stateKey:'counter.v1',label:'线人好感',initialValue:2},{stateKey:'counter.observe',label:'观察'},{stateKey:'clue.k9',label:''}]);
 assert.equal(merged.clue.find(r=>r.key==='clue.ledger_half').label,'半张旧货单');
 assert.ok(!merged.clue.some(r=>r.key==='clue.k9'),'没有名字的键不进名单');
 assert.deepEqual(merged.tag.map(r=>r.label),['夜行','被盯上']);
 assert.deepEqual(merged.value.map(r=>r.label),['幸运值','线人好感']);
 assert.equal(sentence.editorVariables({},merged)['counter.v1'],2);
 assert.equal(names.clue[0].label,'半张货单','不改原名单');
});

test('添加层就地新建：写了名单里没有的名字，第一行「新建「…」」，外层建好后交回键就加上这一句',()=>{
 const h=component();const created=[];
 h.c.triggerEvent=(name,event)=>{if(name==='create')created.push(event);else if(name==='change')h.events.push(event);else h.layers.push(event);};
 h.c.openAdd(tap({target:'conditions'}));h.c.onLayerKind(tap({kind:'clue'}));
 h.c.onLayerQuery({detail:{value:'半张货单'}});
 assert.ok(!h.c.data.layerRows.find(r=>r.open).names.some(n=>/新建/.test(n.label)),'同名已存在就不给新建');
 h.c.onLayerQuery({detail:{value:'暗号'}});
 const first=h.c.data.layerRows.find(r=>r.open).names[0];
 assert.equal(first.label,'新建「暗号」');
 h.c.onLayerName(tap({key:first.key}));h.c.onLayerName(tap({key:first.key}));
 assert.deepEqual(created,[{identity:'edge:x',kind:'clue',label:'暗号'}],'等外层回来前不重复发');
 h.c.data.names=sentence.mergeRegistry(names,[{stateKey:'clue.k3',label:'暗号'}]);
 h.c.created('clue.k3');
 assert.deepEqual(h.events.at(-1).value.conditions,[{op:'HAS_TAG',value:'clue.k3'}]);
 assert.equal(h.c.data.conditionRows[0].text,'已获得线索 · 暗号');
 assert.equal(h.layers.at(-1).open,false);
 h.c.openAdd(tap({target:'effects'}));h.c.onLayerKind(tap({kind:'xp'}));
 assert.equal(h.c.data.effectRows[0].text,'给探索值 +1','没有名单的种类点一下就加上');
});
