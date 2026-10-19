const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
let requests, uploads, uploadOptions, notices, member, confirm, modalCalls;
const app = { globalData: {}, getUserID: () => member, sendRequest: request => requests.push(request), chooseImage: (callback, count, options) => { uploads.push(callback); uploadOptions.push({ count, options }); } };
global.getApp = () => app;
global.wx = { showToast: o => notices.push(o.title), showModal: o => { modalCalls.push(o); if (confirm !== 'manual') o.success({ confirm }); }, getStorageSync() {} };
const editor = require('../../pages/publish/fabu/theme-runtime.js');
const rules = require('../../pages/publish/utils/publish/story-initial-state.js');
function setData(patch) { for (const [key,value] of Object.entries(patch)) { const keys=key.split('.');let target=this.data;keys.slice(0,-1).forEach(k => { target[k] = target[k] || {}; target=target[k]; });target[keys[keys.length-1]]=value; } }
function page(enabled=true) { return Object.assign({ data: Object.assign({}, JSON.parse(JSON.stringify(editor.data)), {runtimeEnabled:enabled,editScope:'FULL',medalEditable:true,formData:{chapters:[{name:'一'},{name:'二'}],journeyRules:''},popChapterIndex:0,popChapterAction:1}),setData },editor.methods); }
const event=(field,value,index=0)=>({currentTarget:{dataset:{field,index}},detail:{value}});
const item=(id='a')=>({id,kind:'ITEM',name:'水',image:'https://example.test/water.png'});
const raw=(extra={})=>JSON.stringify(Object.assign({schemaVersion:1,capacity:6,items:[item()],chapters:[],grants:[],events:[]},extra));
beforeEach(()=>{requests=[];uploads=[];uploadOptions=[];notices=[];member=7;confirm=true;modalCalls=[];});

test('capability is default off and a read-only enabled discovery, never state',()=>{
 const p=page(false);assert.equal(p.data.runtimeEnabled,false);p._loadRuntimeCapability();
 assert.equal(requests.length,1);assert.equal(requests[0].url,'/api/play/theme/capabilities');assert.equal(requests[0].method,'GET');
 requests[0].success({code:200,data:{enabled:true}});assert.equal(p.data.runtimeEnabled,true);
 p._loadRuntimeCapability();requests[1].fail();assert.equal(p.data.runtimeEnabled,false);assert.ok(requests.every(r=>!r.url.endsWith('/state')));
});
test('capability rejects stale, disposed, wrong member and truthy non-boolean responses',()=>{
 const p=page();p._loadRuntimeCapability();p._loadRuntimeCapability();requests[0].success({code:200,data:{enabled:true}});assert.equal(p.data.runtimeEnabled,false);
 requests[1].success({code:200,data:{enabled:'true'}});assert.equal(p.data.runtimeEnabled,false);
 p._loadRuntimeCapability();member=8;requests[2].success({code:200,data:{enabled:true}});assert.equal(p.data.runtimeEnabled,false);
 p._loadRuntimeCapability();p._disposeRuntimeEditor();requests[3].success({code:200,data:{enabled:true}});assert.equal(p.data.runtimeEnabled,false);
});
test('disabled or locked author cannot mutate or emit a new payload field',()=>{
 for(const mode of ['disabled','locked','loading']){const p=page(mode!=='disabled');p.data.formData.themeRuntimeConfig=raw();if(mode==='locked')p.data.editScope='WHITELIST';if(mode==='loading'){p.data.editingTopicId=3;p.data.editLoaded=false;}
 const before=JSON.stringify(p.data.formData);p.openChapterBackpack();p.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});p.clearThemeRuntime();assert.equal(JSON.stringify(p.data.formData),before);assert.equal(p._runtimePayload(),undefined);assert.equal(p.data.runtimeEditor.show,false);}
 assert.equal(requests.length,0);
});
test('omission, explicit clear and exact draft round-trip remain distinct',()=>{
 const p=page();assert.equal(p._runtimePayload(),undefined);p.data.formData.themeRuntimeConfig=null;assert.equal(p._runtimePayload(),undefined);
 p.data.formData.themeRuntimeConfig=raw({sceneMetadata:{authorOnly:'preserve'}});assert.deepEqual(JSON.parse(p._runtimePayload()).sceneMetadata,{authorOnly:'preserve'});
 confirm=false;p.clearThemeRuntime();assert.ok(p.data.formData.themeRuntimeConfig);confirm=true;p.clearThemeRuntime();assert.equal(p._runtimePayload(),'');
});
test('nested inventory cancel does not mutate its parent or stored draft',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw();const original=p.data.formData.themeRuntimeConfig;
 p._beginRuntimeChapter(0);p.openChapterBackpack();p.onRuntimeItem(event('name','取消的名字'));p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 p.openChapterBackpack();assert.equal(p.data.runtimeBag.items[0].name,'水');p.onRuntimeItem(event('name','暂存的名字'));assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,original);
 p._finishRuntimeChapter(false);assert.equal(p.data.formData.themeRuntimeConfig,original);
});
test('chapter confirmation commits staged grants and old no-theme chapter does not create config',()=>{
 const old=page();old._beginRuntimeChapter(0);old._finishRuntimeChapter(true);assert.equal(old.data.formData.themeRuntimeConfig,undefined);
 const p=page();p.data.formData.themeRuntimeConfig=raw();p._beginRuntimeChapter(0);p.openChapterBackpack();p.onRuntimeItem(event('timing',2));p.onRuntimeItem(event('quantity',3));p.closeRuntimeEditor();p._finishRuntimeChapter(true);
 assert.deepEqual(JSON.parse(p._runtimePayload()).chapters[0].grants,[{itemId:'a',timing:'END',quantity:3}]);
});
test('draft accepts missing image but publication rejects it',()=>{
 const p=page();p.openChapterBackpack();p.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});p.onRuntimeItem(event('name','待补图片'));
 assert.equal(p.closeRuntimeEditor(),true);assert.match(p._validateRuntimeConfig(),/图片/);assert.ok(p.data.formData.themeRuntimeConfig);
});
test('capacity, item count and reward quantity bounds are enforced',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw();p.openChapterBackpack();p.onRuntimePolicy(event('capacity',0));assert.equal(p.closeRuntimeEditor(),false);
 p.onRuntimePolicy(event('capacity',6));p.onRuntimeItem(event('timing',1));p.onRuntimeItem(event('quantity',100));assert.equal(p.closeRuntimeEditor(),false);p.onRuntimeItem(event('quantity',99));assert.equal(p.closeRuntimeEditor(),true);
 const full=page();full.data.formData.themeRuntimeConfig=raw({items:Array.from({length:200},(_,i)=>item('i'+i))});full.openChapterBackpack();full.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});assert.equal(full._runtimeConfig().items.length,200);
});
test('medal capability protects addition, removal and image mutation',()=>{
 const p=page();p.data.medalEditable=false;p.data.formData.themeRuntimeConfig=raw({items:[{id:'m',kind:'MEDAL',name:'存量章',image:'https://example.test/m.png'}]});p.openChapterBackpack();
 p.addRuntimeContent({currentTarget:{dataset:{kind:'MEDAL'}}});p.removeRuntimeItem(event('',0));p.onRuntimeItem(event('name','越权'));p.uploadRuntimeImage(event('',0));assert.equal(p._runtimeConfig().items.length,1);assert.equal(p._runtimeConfig().items[0].name,'存量章');assert.equal(uploads.length,0);
});
test('image callbacks bind stable item and editor epoch',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw({items:[item('a'),item('b')]});p.openChapterBackpack();p.uploadRuntimeImage(event('',0));p.removeRuntimeItem(event('',0));uploads[0](['https://example.test/late.png']);assert.equal(p._runtimeConfig().items[0].id,'b');assert.notEqual(p._runtimeConfig().items[0].image,'https://example.test/late.png');
 p.uploadRuntimeImage(event('',0));p.cancelRuntimeEditor();uploads[1](['https://example.test/closed.png']);assert.ok(!p.data.formData.themeRuntimeConfig.includes('closed'));
});
test('egg reward selection and references survive draft round-trip; invalid indices do not write',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw();p.openChapterEggs();p.addRuntimeEgg();p.onRuntimeEgg(event('name','补给'));p.onRuntimeEgg(event('content','下一站见'));p.addRuntimeEggReward(event('',0));
 const e=event('itemId',0);e.currentTarget.dataset.reward=0;p.onRuntimeEggReward(e);e.detail.value=999;p.onRuntimeEggReward(e);assert.equal(p._runtimeConfig().chapters[0].eggs[0].rewards[0].itemId,'a');p.closeRuntimeEditor();assert.equal(p._validateRuntimeConfig(),'');
 p.openChapterBackpack();p.removeRuntimeItem(event('',0));assert.equal(p._runtimeConfig().items.length,1);
});
test('opening insertion and chapter deletion remap indices without dropping unrelated fields',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw({chapters:[{index:0,hpCost:1,grants:[],eggs:[]},{index:1,hpCost:2,grants:[],eggs:[]}],extra:{x:1}});
 p._prependRuntimeChapter();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).chapters.map(x=>x.index),[1,2]);
 p._removeRuntimeChapter(1);const c=JSON.parse(p.data.formData.themeRuntimeConfig);assert.deepEqual(c.chapters.map(x=>x.index),[1]);assert.deepEqual(c.extra,{x:1});
});
test('reward editor stages old coupon and medal fields; cancel preserves the old values',()=>{
 const p=page();p.data.formData.completeRewardCouponId=4;p.data.formData.finishMedalName='旧章';p.openThemeRewards();p.onRuntimeCouponChange({detail:{couponId:9,couponName:'新券'}});p.onRuntimeMedalName({detail:{value:'新章'}});
 assert.equal(p.data.formData.completeRewardCouponId,4);p.cancelRuntimeEditor();assert.equal(p.data.formData.finishMedalName,'旧章');
 p.openThemeRewards();p.onRuntimeCouponChange({detail:{couponId:9,couponName:'新券'}});p.closeRuntimeEditor();assert.equal(p.data.formData.completeRewardCouponId,9);assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).chapters,[]);
});
test('nested reward back closes the child first and does not accidentally confirm the editor',()=>{
 const p=page();let backed=0;p.selectComponent=()=>({backSelector(){backed++;}});p.openThemeRewards();p.onRuntimeRewardOpen({detail:{open:true}});assert.equal(p.closeRuntimeEditor(),false);p.backRuntimeLayer();assert.equal(backed,1);assert.equal(p.data.runtimeEditor.show,true);
 p.onRuntimeRewardOpen({detail:{open:false}});p.backRuntimeLayer();assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,undefined);
});
test('malformed configuration is never replaced with defaults',()=>{
 for(const value of ['{bad','[]','{"items":{}}','{"schemaVersion":2}']){const p=page();p.data.formData.themeRuntimeConfig=value;p.openChapterBackpack();assert.equal(p.data.formData.themeRuntimeConfig,value);assert.equal(p.data.runtimeEditor.show,false);assert.ok(p._validateRuntimeConfig());}
});
test('runtime templates keep existing controls and gate new entries',()=>{
 const root=path.join(__dirname,'../../pages/publish/fabu');const markup=fs.readFileSync(path.join(root,'index.wxml'),'utf8');const detail=fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8');const runtime=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8');
 assert.match(markup,/runtimeEnabled && editScope !== 'WHITELIST'/);assert.match(markup,/onChapterEndingToggle/);assert.match(detail,/onCompleteRewardToggle/);assert.match(detail,/onBingoToggle/);assert.match(runtime,/bind:close="cancelRuntimeEditor"/);assert.match(runtime,/bindtap="closeRuntimeEditor"/);assert.match(runtime,/runtime-reward-selector/);
});

function component(relative) {
 let definition;global.Behavior=value=>value;global.Component=value=>{definition=value;};
 const file=require.resolve(relative);delete require.cache[file];require(file);
 const data=Object.assign({},definition.data);Object.entries(definition.properties||{}).forEach(([key,property])=>{data[key]=property.value;});
 const events=[];const instance=Object.assign({data:JSON.parse(JSON.stringify(data)),setData,triggerEvent:(name,detail)=>events.push({name,detail})},definition.methods);
 return {definition,instance,events};
}
<<<<<<< public
const event = (field,value,index=0) => ({currentTarget:{dataset:{field,index}},detail:{value}});
const p = page();
p.onRuntimeStatToggle({currentTarget:{dataset:{stat:'hp'}},detail:{value:true}});
assert.equal(rules.parse(p.data.formData.journeyRules).hp.enabled,true);
assert.equal(rules.parse(p.data.formData.journeyRules).checkEnabled,true);
assert.equal(rules.parse(p.data.formData.journeyRules).luck.enabled,false);
p.openChapterBackpack();p.addRuntimeContent({currentTarget:{dataset:{kind:'MEDAL'}}});assert.equal(p._runtimeConfig().items.length,0);p.addRuntimeContent({currentTarget:{dataset:{kind:'ITEM'}}});
p.onRuntimeItem(event('name','饮品'));p.onRuntimeItem(event('image','https://example.test/item.png')); p.onRuntimeItem(event('purpose',1));p.onRuntimeItem(event('delta',-3));p.onRuntimeItem(event('effect',1));p.onRuntimeItem(event('timing',1));
const c = editor.config(p.data.formData.themeRuntimeConfig);
assert.equal(c.items[0].stat,'HP');assert.equal(Number(c.items[0].delta),-3);assert.equal(c.items[0].mode,'ONCE');assert.equal(c.chapters[0].grants[0].timing,'START');assert.equal(p._validateRuntimeConfig(),'');
p.onRuntimeItem(event('purpose',0));assert.equal(editor.config(p.data.formData.themeRuntimeConfig).items[0].stat,undefined);
p.onRuntimePolicy(event('allowDiscard',false));assert.equal(editor.config(p.data.formData.themeRuntimeConfig).allowDiscard,false);
p.openChapterEggs();p.addRuntimeEgg();p.onRuntimeEgg(event('name','消息'));p.onRuntimeEgg(event('content','消息内容'));p.onRuntimeEgg(event('trigger',1));p.onRuntimeEgg(event('threshold',300));
assert.equal(editor.config(p.data.formData.themeRuntimeConfig).chapters[0].eggs[0].trigger,'STEPS');
p._removeRuntimeChapter(0);assert.equal(editor.config(p.data.formData.themeRuntimeConfig).chapters.length,0);
const bag = player.present({used:3,capacity:6,contents:[{id:'a',kind:'ITEM',stat:'HP',quantity:2},{id:'b',kind:'ITEM',name:'天气图',quantity:1},{id:'m',kind:'MEDAL',quantity:1}],events:[{id:'e',name:'消息',content:'内容'}],pending:{p:{quantity:1}}});
assert.equal(bag.used,3);assert.equal(bag.capacity,6);assert.equal(bag.items.length,2);assert.equal(bag.clues.length,2);assert.equal(bag.pendingRows[0].id,'p');
async function retry() {
 const requests=[];
 const p = {data:Object.assign({},player.data,{topicId:2,themeBag:{sessionId:4,version:7}}),setData(patch){Object.assign(this.data,patch);},_sessionParams:()=>({topicId:2}),refreshRouteState:()=>Promise.resolve(true)};
 Object.assign(p,player.methods(async(url,method,data)=>{if(method==='GET')return {code:200,data:{sessionId:4,version:8,capacity:6,contents:[]}};requests.push({...data});return requests.length===1?{netFail:true}:{code:200,data:{sessionId:4,version:8,capacity:6,contents:[]}};}));
 assert.equal(await p._themeCommand('USE','a'),false);assert.equal(p.data.themeBagUnknown,true);
 assert.equal(await p.retryThemeBagAction(),true);assert.equal(requests[0].actionId,requests[1].actionId);assert.equal(p._themePending,null);
=======
test('sheet stacking is opt-in and preserves old clean/dirty close events',()=>{
 const {instance:s,events}=component('../../components/cy/sheet/index.js');assert.equal(s.data.stacked,false);
 s.onClose();assert.deepEqual(events.map(e=>e.name),['requestclose','close']);events.length=0;s.data.dirty=true;s.onClose();assert.deepEqual(events.map(e=>e.name),['requestclose']);
 events.length=0;s.data.stacked=true;s.onClose();s.onMask();s.onBack();s._requestClose('drag');s.onDragStart({touches:[{clientY:1}]});assert.equal(events.length,0);assert.equal(s._drag,undefined);
});
test('reward selector reports open changes while keeping old change payload',()=>{
 const {instance:s,events}=component('../../pages/publish/components/reward-selector/index.js');s.openSelector();assert.equal(s.data.open,true);assert.equal(events[0].name,'openchange');assert.deepEqual(events[0].detail,{open:true});
 s.chooseCoupon({currentTarget:{dataset:{item:{id:4,name:'旧券'}}}});assert.equal(s.data.open,false);assert.ok(events.some(e=>e.name==='change'&&e.detail.couponId===4&&e.detail.couponName==='旧券'));
 assert.deepEqual(events[events.length-1],{name:'openchange',detail:{open:false}});assert.equal(s.data.allowCreate,false);
});
test('reward back unwinds date/create/selector one layer at a time and blocks submitting',()=>{
 const {instance:s}=component('../../pages/publish/components/reward-selector/index.js');s.data.open=true;s.data.creating=true;s.data.rangePickerShow=true;
 s.backSelector();assert.equal(s.data.rangePickerShow,false);assert.equal(s.data.creating,true);assert.equal(s.data.open,true);
 s.data.createDirty=true;confirm=false;s.backSelector();assert.equal(s.data.creating,true);confirm=true;s.backSelector();assert.equal(s.data.creating,false);assert.equal(s.data.open,true);
 s.data.isSubmitting=true;s.backSelector();assert.equal(s.data.open,true);s.data.isSubmitting=false;s.backSelector();assert.equal(s.data.open,false);
});
test('dropdown unchecked value is opt-in and does not change old selection handler',()=>{
 const {definition}=component('../../components/cy/dropdown/index.js');assert.equal(definition.properties.uncheckedValue.value,-1);
 const markup=fs.readFileSync(path.join(__dirname,'../../components/cy/dropdown/index.wxml'),'utf8');assert.match(markup,/index === value && index !== uncheckedValue/);
});

test('chapter, egg and nested reward upper bounds match the backend contract',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw();p.openChapterEggs();
 for(let i=0;i<101;i++)p.addRuntimeEgg();assert.equal(p._runtimeConfig().chapters[0].eggs.length,100);
 for(let i=0;i<21;i++)p.addRuntimeEggReward(event('',0));assert.equal(p._runtimeConfig().chapters[0].eggs[0].rewards.length,20);
 const c=editor.config(raw({chapters:[{index:200,hpCost:0,luckCost:0,grants:[],eggs:[]}]}));assert.match(editor.validate(c,rules.parse(''),false),/序号/);
});
test('item deletion protects scene, event and condition references too',()=>{
 for(const extra of [{events:[{grants:[{itemId:'a'}]}]},{scenes:[{grants:[{itemId:'a'}]}]},{endings:[{when:[{op:'ITEM_HELD',itemId:'a'}]}]}]){
  const p=page();p.data.formData.themeRuntimeConfig=raw(extra);p.openChapterBackpack();p.removeRuntimeItem(event('',0));assert.equal(p._runtimeConfig().items.length,1);
 }
});
test('no-theme disabled entry does not parse or modify old journey rules',()=>{
 const p=page(false);p.data.formData.journeyRules='{legacy invalid';assert.doesNotThrow(()=>p._syncRuntimeStats());assert.equal(p.data.formData.journeyRules,'{legacy invalid');assert.equal(notices.length,0);
});

test('empty backend capacity zero remains valid and out-of-range chapters do not open',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=raw({capacity:0,items:[]});assert.equal(p._validateRuntimeConfig(),'');
 p.data.popChapterIndex=200;p.openChapterBackpack();assert.equal(p.data.runtimeEditor.show,false);
});

test('same-member capability refresh does not discard a returning image picker edit',()=>{
 const p=page(false);p._loadRuntimeCapability();requests[0].success({code:200,data:{enabled:true}});p.data.formData.themeRuntimeConfig=raw();p.openChapterBackpack();p.uploadRuntimeImage(event('',0));
 p._loadRuntimeCapability();assert.equal(p.data.runtimeEnabled,true);uploads[0](['https://example.test/returned.png']);assert.equal(p._runtimeConfig().items[0].image,'https://example.test/returned.png');
 requests[1].success({code:200,data:{enabled:false}});assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.runtimeEnabled,false);assert.ok(!p.data.formData.themeRuntimeConfig.includes('returned'));
});
test('a changed member cannot mutate a capability granted to the previous member',()=>{
 const p=page(false);p._loadRuntimeCapability();requests[0].success({code:200,data:{enabled:true}});p.data.formData.themeRuntimeConfig=raw();member=8;
 p.openChapterBackpack();assert.equal(p.data.runtimeEditor.show,false);assert.equal(p._runtimePayload(),undefined);p._loadRuntimeCapability();assert.equal(p.data.runtimeEnabled,false);
});

test('public result settings expose exactly the nine existing server fields and default to ending only', () => {
 const settings = editor.cardSettings({});
 assert.deepEqual(settings.fields.map(row => row.key), ['scene','ending','hp','luck','actions','medals','items','story','rescue']);
 assert.deepEqual(settings.fields.filter(row => row.enabled).map(row => row.key), ['ending']);
 assert.equal(settings.showUnlocks, false); assert.equal(settings.cover, '');
 assert.deepEqual(editor.cardSettings({card:{fields:[]}}).fields.filter(row => row.enabled), []);
});
test('opening and completing public result settings without changes preserves every raw form including omission', () => {
 const originals = [undefined, null, '', '{ "schemaVersion": 1, "card": null, "futureRoot": {"keep": 1} }',
  '{ "schemaVersion": 1, "card": {"fields":[], "futureOption":"keep"}, "scenes": [] }'];
 for (const original of originals) { const p=page(); p.data.formData.themeRuntimeConfig=original; p.openThemeResultCard();
  assert.equal(p.data.runtimeEditor.show,true); assert.equal(p.data.runtimeEditor.kind,'card'); assert.equal(p.closeRuntimeEditor(),true);
  assert.equal(p.data.formData.themeRuntimeConfig,original); assert.equal(p._runtimeChapterWorking,undefined); }
 assert.equal(requests.length,0); assert.equal(uploads.length,0);
});
test('public result cancel discards switches cover and unlock changes without touching reward or chapter state', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw({card:{fields:['ending']},rotation:'WEEKLY',rotationAnchor:0});
 p.data.formData.completeRewardCouponId=77; const before=JSON.stringify(p.data.formData); p.openThemeResultCard();
 p.onRuntimeCardField(event('hp',true)); p.onRuntimeCardUnlocks({detail:{value:true}}); p.uploadRuntimeCardCover(); uploads[0](['https://example.test/card.png']);
 assert.equal(JSON.stringify(p.data.formData),before); p.cancelRuntimeEditor(); assert.equal(JSON.stringify(p.data.formData),before);
 assert.equal(p._runtimeWorking,null); assert.equal(requests.length,0);
});
test('explicit empty result fields round-trip while changing unlock visibility never synthesizes missing fields', () => {
 const p=page(); p.openThemeResultCard(); p.onRuntimeCardField(event('ending',false)); assert.equal(p.closeRuntimeEditor(),true);
 assert.deepEqual(JSON.parse(p._runtimePayload()).card.fields,[]); p.openThemeResultCard(); assert.ok(p.data.runtimeResultCard.fields.every(row=>!row.enabled)); p.cancelRuntimeEditor();
 const q=page(); q.data.formData.themeRuntimeConfig=raw({card:{cover:'https://example.test/cover.png'}}); q.openThemeResultCard();
 q.onRuntimeCardUnlocks({detail:{value:true}}); q.closeRuntimeEditor(); const card=JSON.parse(q._runtimePayload()).card;
 assert.equal(card.showUnlocks,true); assert.equal(Object.hasOwn(card,'fields'),false); assert.equal(card.cover,'https://example.test/cover.png');
});
test('no-op switch events and reverted choices preserve original bytes and omitted default semantics', () => {
 for (const original of [undefined, '{ "schemaVersion": 1, "card": {"fields":["ending","ending"],"future":true} }']) {
  const p=page(); p.data.formData.themeRuntimeConfig=original; p.openThemeResultCard();
  assert.equal(p.onRuntimeCardField(event('ending',true)),false); assert.equal(p.onRuntimeCardUnlocks({detail:{value:false}}),false);
  p.onRuntimeCardField(event('scene',true)); p.onRuntimeCardField(event('scene',false)); p.onRuntimeCardUnlocks({detail:{value:true}}); p.onRuntimeCardUnlocks({detail:{value:false}});
  assert.equal(p.closeRuntimeEditor(),true); assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
});
test('public field mutation preserves unknown raw configuration and never exposes it as a selectable field', () => {
 const p=page(); const extra={scenes:[{id:'old',name:'既有幕',events:[{id:'keep'}]}],currentScene:'old',futureRoot:{secret:'AUTHOR_ONLY'},card:{fields:['ending'],futureOption:{secret:'CARD_PRIVATE'}}};
 p.data.formData.themeRuntimeConfig=raw(extra); p.openThemeResultCard(); assert.doesNotMatch(JSON.stringify(p.data.runtimeResultCard),/AUTHOR_ONLY|CARD_PRIVATE/);
 p.onRuntimeCardField(event('items',true)); p.closeRuntimeEditor(); const result=JSON.parse(p._runtimePayload());
 assert.deepEqual(result.scenes,extra.scenes); assert.deepEqual(result.futureRoot,extra.futureRoot); assert.deepEqual(result.card.futureOption,extra.card.futureOption);
 assert.deepEqual(result.card.fields,['ending','items']); assert.deepEqual(result.items,[item()]);
});
test('result switches reject unknown keys and nonboolean input without widening the public schema', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw(); p.openThemeResultCard(); const before=JSON.stringify(p._runtimeWorking);
 for (const key of ['ownerId','archive','track','nodeAnswers','__proto__']) assert.equal(p.onRuntimeCardField(event(key,true)),false);
 for (const value of ['true',1,null,{},[]]) { assert.equal(p.onRuntimeCardField(event('hp',value)),false); assert.equal(p.onRuntimeCardUnlocks({detail:{value}}),false); }
 assert.equal(JSON.stringify(p._runtimeWorking),before); p.cancelRuntimeEditor();
});
test('result cover uses the existing single-image upload protocol and clear only removes the draft reference', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw({card:{fields:['ending'],cover:'https://example.test/old.png'}}); const original=p.data.formData.themeRuntimeConfig;
 p.openThemeResultCard(); p.uploadRuntimeCardCover(); assert.deepEqual(uploadOptions[0],{count:1,options:{crop:true,cropScale:'16:9'}});
 uploads[0](['https://example.test/new.png']); assert.equal(p.data.runtimeResultCard.cover,'https://example.test/new.png'); assert.equal(p.data.formData.themeRuntimeConfig,original);
 assert.equal(p.clearRuntimeCardCover(),true); assert.equal(p.data.runtimeResultCard.cover,''); p.closeRuntimeEditor();
 const card=JSON.parse(p._runtimePayload()).card; assert.equal(Object.hasOwn(card,'cover'),false); assert.deepEqual(card.fields,['ending']);
 assert.equal(requests.length,0); assert.equal(modalCalls.length,0);
});
test('invalid or cancelled cover upload cannot persist non-HTTPS or oversized values', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw(); p.openThemeResultCard(); const before=JSON.stringify(p._runtimeWorking);
 for (const result of [undefined,[],['http://example.test/a.png'],['data:image/png;base64,x'],['https://'+'x'.repeat(500)],['https://bad host/a'],[17]]) {
  p.uploadRuntimeCardCover(); uploads[uploads.length-1](result); assert.equal(JSON.stringify(p._runtimeWorking),before);
 }
 assert.equal(p.closeRuntimeEditor(),true); assert.equal(p.data.formData.themeRuntimeConfig,raw());
});
test('newer cover selection wins and explicit clear cancels older upload completion', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw({card:{cover:'https://example.test/old.png'}}); p.openThemeResultCard();
 p.uploadRuntimeCardCover(); p.uploadRuntimeCardCover(); uploads[0](['https://example.test/stale.png']); assert.equal(p.data.runtimeResultCard.cover,'https://example.test/old.png');
 uploads[1](['https://example.test/latest.png']); assert.equal(p.data.runtimeResultCard.cover,'https://example.test/latest.png');
 p.uploadRuntimeCardCover(); p.clearRuntimeCardCover(); uploads[2](['https://example.test/after-clear.png']); assert.equal(p.data.runtimeResultCard.cover,''); p.cancelRuntimeEditor();
});
test('cover callbacks retire on account topic draft revision scope source permission and lifecycle changes', () => {
 for (const mode of ['owner','topic','draft','draftOwner','revision','scope','source','sourceObject','locked','disabled','loading','unload','cancel','reopen']) {
  member=7; const p=page(); p.data.editingTopicId='10'; p.data.editLoaded=true; p.data.draftUuid='draft-a'; p.data.draftMemberId=7; p.data.baseRevision=3;
  p.data.formData.themeRuntimeConfig=mode==='sourceObject'?JSON.parse(raw()):raw(); p.openThemeResultCard(); p.uploadRuntimeCardCover(); const callback=uploads[uploads.length-1];
  if(mode==='owner')member=8; if(mode==='topic')p.data.editingTopicId='11'; if(mode==='draft')p.data.draftUuid='draft-b'; if(mode==='draftOwner')p.data.draftMemberId=8;
  if(mode==='revision')p.data.baseRevision=4; if(mode==='scope')p.data.operationScope='MERCHANT'; if(mode==='source')p.data.formData.themeRuntimeConfig=raw({card:{fields:[]}});
  if(mode==='sourceObject')p.data.formData.themeRuntimeConfig.card={fields:[]}; if(mode==='locked')p.data.editScope='WHITELIST'; if(mode==='disabled')p.data.runtimeEnabled=false;
  if(mode==='loading')p.data.editLoading=true; if(mode==='unload')p._disposeRuntimeEditor(); if(mode==='cancel')p.cancelRuntimeEditor();
  if(mode==='reopen'){p.cancelRuntimeEditor();p.openThemeResultCard();}
  callback(['https://example.test/STALE.png']); assert.doesNotMatch(JSON.stringify(p.data.formData),/STALE/,mode); assert.doesNotMatch(JSON.stringify(p._runtimeWorking),/STALE/,mode);
 }
});
test('observed capability revocation wins over an image return and never commits staged result settings', () => {
 const p=page(false); p._loadRuntimeCapability(); requests[0].success({code:200,data:{enabled:true}}); p.data.formData.themeRuntimeConfig=raw();
 p.openThemeResultCard(); p.onRuntimeCardField(event('hp',true)); p.uploadRuntimeCardCover(); p._loadRuntimeCapability(); requests[1].success({code:200,data:{enabled:false}});
 uploads[0](['https://example.test/revoked.png']); assert.equal(p.data.runtimeEditor.show,false); assert.equal(p.data.runtimeEnabled,false); assert.equal(p.data.formData.themeRuntimeConfig,raw());
});
test('same-owner image return survives permitted capability refresh in the same result editor', () => {
 const p=page(false); p._loadRuntimeCapability(); requests[0].success({code:200,data:{enabled:true}}); p.openThemeResultCard(); p.uploadRuntimeCardCover();
 p._loadRuntimeCapability(); uploads[0](['https://example.test/returned.png']); requests[1].success({code:200,data:{enabled:true}});
 assert.equal(p.data.runtimeResultCard.cover,'https://example.test/returned.png'); assert.equal(p.closeRuntimeEditor(),true); assert.equal(JSON.parse(p._runtimePayload()).card.cover,'https://example.test/returned.png');
});
test('result Done refuses to overwrite a replaced draft and disabled or locked authors cannot open or modify it', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw(); p.openThemeResultCard(); p.onRuntimeCardField(event('hp',true)); const newer=raw({card:{fields:[]}}); p.data.formData.themeRuntimeConfig=newer;
 assert.equal(p.closeRuntimeEditor(),false); assert.equal(p.data.formData.themeRuntimeConfig,newer); assert.equal(p.data.runtimeEditor.show,false);
 for(const mode of ['disabled','locked','loading']) { const q=page(mode!=='disabled'); q.data.formData.themeRuntimeConfig=raw(); if(mode==='locked')q.data.editScope='WHITELIST'; if(mode==='loading'){q.data.editingTopicId='3';q.data.editLoaded=false;}
  q.openThemeResultCard(); q.onRuntimeCardField(event('hp',true)); q.onRuntimeCardUnlocks({detail:{value:true}}); q.uploadRuntimeCardCover(); q.clearRuntimeCardCover();
  assert.equal(q.data.runtimeEditor.show,false); assert.equal(q.data.formData.themeRuntimeConfig,raw()); assert.equal(q._runtimePayload(),undefined); }
 assert.equal(uploads.length,0);
});
test('result editor cannot open over another staged editor or mutate from an unrelated editor kind', () => {
 const p=page(); p.openChapterBackpack(); const working=JSON.stringify(p._runtimeWorking); assert.equal(p.openThemeResultCard(),false);
 assert.equal(p.onRuntimeCardField(event('hp',true)),false); assert.equal(p.onRuntimeCardUnlocks({detail:{value:true}}),false); assert.equal(p.uploadRuntimeCardCover(),false);
 assert.equal(p.data.runtimeEditor.kind,'bag'); assert.equal(JSON.stringify(p._runtimeWorking),working); p.cancelRuntimeEditor(); p.data.popChapter=true; assert.equal(p.openThemeResultCard(),false);
});
test('result configuration bounds match existing cover field-count and typed-boolean server contract', () => {
 for (const card of [[],17,{fields:null},{fields:['private']},{fields:Array(11).fill('ending')},{showUnlocks:1},{cover:''},{cover:'https://'+'a'.repeat(500)}]) {
  const p=page(); const original=raw({card}); p.data.formData.themeRuntimeConfig=original; p.openThemeResultCard();
  assert.equal(p.data.runtimeEditor.show,false); assert.equal(p.data.formData.themeRuntimeConfig,original); assert.ok(p._validateRuntimeConfig());
 }
 assert.equal(editor.validate(editor.config(raw({card:{cover:'https://'+'a'.repeat(492),fields:[],showUnlocks:false}})),rules.parse(''),false),'');
});
test('restored result settings read actual fields cover and personal known-ending visibility without a new request', () => {
 const p=page(); p.data.formData.themeRuntimeConfig=raw({card:{fields:['luck','rescue','story'],cover:'https://example.test/c.png',showUnlocks:true}});
 p.openThemeResultCard(); assert.deepEqual(p.data.runtimeResultCard.fields.filter(row=>row.enabled).map(row=>row.key),['luck','story','rescue']); assert.equal(p.data.runtimeResultCard.showUnlocks,true);
 assert.equal(p.data.runtimeResultCard.cover,'https://example.test/c.png'); assert.equal(requests.length,0); p.cancelRuntimeEditor();
});
test('real result settings entry and controls explain public projection and frozen existing-run semantics', () => {
 const root=path.join(__dirname,'../../pages/publish/fabu'), detail=fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8'), runtime=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8');
 assert.match(detail,/runtimeEnabled && editScope !== 'WHITELIST'/); assert.match(detail,/bindtap="openThemeResultCard"/);
 for(const method of ['onRuntimeCardField','onRuntimeCardUnlocks','uploadRuntimeCardCover','clearRuntimeCardCover'])assert.ok(runtime.includes('"'+method+'"'));
 assert.match(runtime,/结束状态与时间始终保留/); assert.match(runtime,/已开始或已结束的局沿用各自快照/); assert.match(runtime,/该玩家在本主题已知/); assert.match(runtime,/可关闭全部可选内容/);
 const p=page(); p.clearThemeRuntime(); assert.match(modalCalls[0].content,/场景、事件和公开结果/);
});
const sceneEvent=(id,extra={})=>({currentTarget:{dataset:Object.assign({id},extra)}});
const sceneName=(id,value)=>Object.assign(sceneEvent(id),{detail:{value}});
const sceneConfig=(extra={})=>raw(Object.assign({scenes:[{id:'alpha',name:'第一幕'},{id:'beta',name:'第二幕'}],currentScene:'alpha'},extra));
test('scene view uses the existing omitted defaults and never silently chooses a different currentScene', () => {
 const empty=editor.sceneSettings({}); assert.equal(empty.replayEnabled,false); assert.equal(empty.mode,'FIXED'); assert.deepEqual(empty.scenes,[]);
 const c=editor.sceneSettings(JSON.parse(sceneConfig())); assert.equal(c.scenes[0].current,true); assert.equal(c.scenes[1].current,false);
 for(const invalid of [{scenes:[{id:'alpha',name:'A'}]},{scenes:[],currentScene:'alpha'},{scenes:[{id:'alpha',name:'A'}],currentScene:'other'}])assert.throws(()=>editor.sceneSettings(invalid),/场景/);
});
test('scene open/no-op/cancel preserves raw absence null spacing weekly anchor and unknown values', () => {
 for(const original of [undefined,null,'','{ "schemaVersion": 1, "rotation":"WEEKLY", "rotationAnchor":0, "futureRoot":{"x":1} }',sceneConfig({futureRoot:{x:1}})]) {
  const p=page();p.data.formData.themeRuntimeConfig=original;p.openThemeScenes();assert.equal(p.data.runtimeEditor.show,true);assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,original);
  p.openThemeScenes();p.onRuntimeReplayToggle({detail:{value:true}});p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
 assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('scene replay and fixed/round-robin choices are explicit staged values with no server operation', () => {
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();const original=p.data.formData.themeRuntimeConfig;p.openThemeScenes();
 p.onRuntimeReplayToggle({detail:{value:true}});p.onRuntimeRotation({detail:{value:1}});assert.equal(p.data.formData.themeRuntimeConfig,original);assert.equal(p.data.runtimeEnabled,true);
 assert.equal(p.closeRuntimeEditor(),true);const c=JSON.parse(p._runtimePayload());assert.equal(c.replayEnabled,true);assert.equal(c.rotation,'ROUND_ROBIN');assert.equal(c.currentScene,'alpha');assert.equal(requests.length,0);
});
test('new scene has only stable id/name and inherits root behavior through missing overrides, never copied rewards', () => {
 const p=page();p.data.formData.themeRuntimeConfig=raw({events:[{id:'e',content:'主事件'}],grants:[{id:'g',itemId:'a'}],endings:[{id:'end'}]});const original=JSON.parse(p.data.formData.themeRuntimeConfig);
 p.openThemeScenes();assert.equal(p.addRuntimeScene(),true);const created=p._runtimeConfig().scenes[0];assert.deepEqual(Object.keys(created).sort(),['id','name']);assert.match(created.id,/^[a-z][a-z0-9_]{0,31}$/);assert.equal(p._runtimeConfig().currentScene,created.id);
 assert.equal(p.closeRuntimeEditor(),false);p.onRuntimeSceneName(sceneName(created.id,'新幕'));assert.equal(p.closeRuntimeEditor(),true);const c=JSON.parse(p._runtimePayload());
 assert.deepEqual(c.events,original.events);assert.deepEqual(c.grants,original.grants);assert.deepEqual(c.endings,original.endings);assert.deepEqual(c.scenes,[{id:created.id,name:'新幕'}]);assert.equal(Object.hasOwn(c,'replayEnabled'),false);
});
test('scene count and UTF-16 name boundaries match Java validation including supplementary characters', () => {
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig({scenes:Array.from({length:50},(_,i)=>({id:'s'+i,name:'场景'+i})),currentScene:'s0'});p.openThemeScenes();assert.equal(p.addRuntimeScene(),false);assert.equal(p._runtimeConfig().scenes.length,50);
 assert.equal(p.onRuntimeSceneName(sceneName('s0','😀'.repeat(20))),true);assert.equal(p._runtimeConfig().scenes[0].name.length,40);
 assert.equal(p.onRuntimeSceneName(sceneName('s0','😀'.repeat(20)+'a')),false);assert.equal(p._runtimeConfig().scenes[0].name.length,40);
 assert.equal(p.onRuntimeSceneName(sceneName('s0','汉'.repeat(40))),true);assert.equal(p.closeRuntimeEditor(),true);
 assert.throws(()=>editor.sceneSettings({scenes:Array.from({length:51},(_,i)=>({id:'s'+i,name:'A'})),currentScene:'s0'}),/50/);
});
test('scene identifiers remain unique and invalid/foreign identities cannot rename choose reorder or delete', () => {
 for(const list of [[{id:'alpha',name:'A'},{id:'alpha',name:'B'}],[{id:'01',name:'A'}],[{id:'Bad',name:'A'}],[{id:'a'.repeat(33),name:'A'}]])assert.throws(()=>editor.sceneSettings({scenes:list,currentScene:list[0].id}),/编号/);
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();const before=JSON.stringify(p._runtimeWorking);
 assert.equal(p.onRuntimeSceneName(sceneName('foreign','Changed')),false);assert.equal(p.chooseRuntimeScene(sceneEvent('foreign')),false);assert.equal(p.moveRuntimeScene(sceneEvent('foreign',{direction:'up'})),false);assert.equal(p.removeRuntimeScene(sceneEvent('foreign')),false);
 assert.equal(JSON.stringify(p._runtimeWorking),before);assert.equal(modalCalls.length,0);
 const q=page();q.openThemeScenes();for(let i=0;i<50;i++)q.addRuntimeScene();assert.equal(new Set(q._runtimeConfig().scenes.map(row=>row.id)).size,50);q.cancelRuntimeEditor();
});
test('scene reorder and stable-id rename preserve selected reference and every nested per-scene payload', () => {
 const a={id:'alpha',name:'A',grants:[],events:[{id:'event',content:'keep'}],endings:[],journeyStory:{extra:'keep'},nodeConfigs:{'7':{x:1}},future:{raw:['keep']}};
 const b={id:'beta',name:'B',grants:[{itemId:'a',quantity:1}]};const p=page();p.data.formData.themeRuntimeConfig=sceneConfig({scenes:[a,b],futureRoot:{keep:1}});p.openThemeScenes();
 assert.equal(p.moveRuntimeScene(sceneEvent('alpha',{direction:'down'})),true);assert.equal(p._runtimeConfig().currentScene,'alpha');assert.equal(p.onRuntimeSceneName(sceneName('alpha','新的 A')),true);
 assert.equal(p.closeRuntimeEditor(),true);const c=JSON.parse(p._runtimePayload());assert.deepEqual(c.scenes.map(row=>row.id),['beta','alpha']);assert.deepEqual(c.scenes[0],b);assert.deepEqual(c.scenes[1],Object.assign({},a,{name:'新的 A'}));assert.equal(c.currentScene,'alpha');assert.deepEqual(c.futureRoot,{keep:1});
});
test('current scene deletion requires explicit replacement and never silently points to a neighbor', () => {
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();assert.equal(p.removeRuntimeScene(sceneEvent('alpha')),false);assert.equal(modalCalls.length,0);assert.equal(p._runtimeConfig().currentScene,'alpha');
 assert.equal(p.chooseRuntimeScene(sceneEvent('beta')),true);assert.equal(p.removeRuntimeScene(sceneEvent('alpha')),true);assert.equal(modalCalls.length,1);assert.match(modalCalls[0].content,/全部已有配置/);
 assert.deepEqual(p._runtimeConfig().scenes.map(row=>row.id),['beta']);assert.equal(p._runtimeConfig().currentScene,'beta');assert.equal(p.removeRuntimeScene(sceneEvent('beta')),false);assert.equal(p._runtimeConfig().scenes.length,1);p.cancelRuntimeEditor();
});
test('scene delete Cancel preserves payload and repeated pending confirmation cannot duplicate deletion', () => {
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();const before=JSON.stringify(p._runtimeWorking);confirm=false;p.removeRuntimeScene(sceneEvent('beta'));assert.equal(JSON.stringify(p._runtimeWorking),before);
 confirm='manual';p.removeRuntimeScene(sceneEvent('beta'));assert.equal(p.removeRuntimeScene(sceneEvent('beta')),false);assert.equal(modalCalls.length,2);modalCalls[1].success({confirm:true});assert.deepEqual(p._runtimeConfig().scenes.map(row=>row.id),['alpha']);
 modalCalls[1].success({confirm:true});assert.equal(p._runtimeConfig().scenes.length,1);p.cancelRuntimeEditor();
});
test('stale scene delete confirmation cannot remove changed payload or newly selected current scene', () => {
 for(const mode of ['renamed','selected','payload']) {confirm='manual';const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.removeRuntimeScene(sceneEvent('beta'));const call=modalCalls[modalCalls.length-1];
  if(mode==='renamed')p.onRuntimeSceneName(sceneName('beta','新名称'));if(mode==='selected')p.chooseRuntimeScene(sceneEvent('beta'));if(mode==='payload')p._runtimeWorking.scenes[1].future={changed:1};
  call.success({confirm:true});assert.equal(p._runtimeConfig().scenes.length,2,mode);p.cancelRuntimeEditor(); }
});
test('scene confirmations and mutation controls retire on account topic draft permission source and lifecycle changes', () => {
 for(const mode of ['owner','topic','draft','revision','source','locked','disabled','loading','cancel','reopen','unload']) {
  member=7;confirm='manual';const p=page();p.data.editingTopicId='10';p.data.editLoaded=true;p.data.draftUuid='draft-a';p.data.baseRevision=1;p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.removeRuntimeScene(sceneEvent('beta'));const call=modalCalls[modalCalls.length-1];
  if(mode==='owner')member=8;if(mode==='topic')p.data.editingTopicId='11';if(mode==='draft')p.data.draftUuid='draft-b';if(mode==='revision')p.data.baseRevision=2;
  if(mode==='source')p.data.formData.themeRuntimeConfig=sceneConfig({replayEnabled:true});if(mode==='locked')p.data.editScope='WHITELIST';if(mode==='disabled')p.data.runtimeEnabled=false;if(mode==='loading')p.data.editLoading=true;
  if(mode==='cancel')p.cancelRuntimeEditor();if(mode==='reopen'){p.cancelRuntimeEditor();p.openThemeScenes();}if(mode==='unload')p._disposeRuntimeEditor();
  call.success({confirm:true});assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).scenes.length,2,mode);
  if(p._runtimeWorking)assert.equal(p._runtimeWorking.scenes.length,2,mode);
 }
});
test('scene modal failure releases pending intent and raw no-op reversals do not rewrite the draft', () => {
 confirm='manual';const original='{ "schemaVersion":1, "scenes":[{"id":"alpha","name":"A"},{"id":"beta","name":"B"}], "currentScene":"alpha", "futureRoot":{"raw":true} }';
 const p=page();p.data.formData.themeRuntimeConfig=original;p.openThemeScenes();p.removeRuntimeScene(sceneEvent('beta'));modalCalls[0].fail();assert.equal(p._runtimeSceneDelete,null);
 p.onRuntimeReplayToggle({detail:{value:true}});p.onRuntimeReplayToggle({detail:{value:false}});p.onRuntimeRotation({detail:{value:1}});p.onRuntimeRotation({detail:{value:0}});
 p.chooseRuntimeScene(sceneEvent('beta'));p.chooseRuntimeScene(sceneEvent('alpha'));p.moveRuntimeScene(sceneEvent('alpha',{direction:'down'}));p.moveRuntimeScene(sceneEvent('alpha',{direction:'up'}));
 assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,original);
});
test('WEEKLY and anchor remain unedited unless another available mode is explicitly selected', () => {
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig({rotation:'WEEKLY',rotationAnchor:0});p.openThemeScenes();assert.equal(p.data.runtimeScenes.rotationIndex,-1);assert.match(p.data.runtimeScenes.anchorText,/1970-01-01 08:00/);assert.match(p.data.runtimeScenes.anchorText,/毫秒值 0/);
 assert.equal(p.onRuntimeRotation({detail:{value:2}}),false);assert.equal(p._runtimeConfig().rotation,'WEEKLY');p.onRuntimeReplayToggle({detail:{value:true}});p.onRuntimeSceneName(sceneName('beta','第二幕新名称'));p.closeRuntimeEditor();
 let c=JSON.parse(p._runtimePayload());assert.equal(c.rotation,'WEEKLY');assert.equal(c.rotationAnchor,0);
 p.openThemeScenes();p.onRuntimeRotation({detail:{value:1}});p.closeRuntimeEditor();c=JSON.parse(p._runtimePayload());assert.equal(c.rotation,'ROUND_ROBIN');assert.equal(c.rotationAnchor,0);
});
test('weekly anchor display is Beijing-fixed across ordinary phone timezone settings', () => {
 const {execFileSync}=require('node:child_process');const file=path.resolve(__dirname,'../../pages/publish/fabu/theme-runtime.js');
 const script='global.getApp=()=>({});global.wx={};const e=require('+JSON.stringify(file)+');process.stdout.write(e.sceneSettings({rotation:"WEEKLY",rotationAnchor:1791208800000}).anchorText);';
 const outputs=['UTC','America/Los_Angeles','Asia/Shanghai'].map(TZ=>execFileSync(process.execPath,['-e',script],{env:Object.assign({},process.env,{TZ}),encoding:'utf8'}));
 assert.equal(new Set(outputs).size,1);assert.match(outputs[0],/北京时间/);assert.doesNotMatch(outputs[0],/NaN/);
});
test('unused non-weekly anchor data is preserved without interpreting or rewriting its unknown format', () => {
 for(const rotationAnchor of ['legacy', {future:'PRIVATE_OPAQUE'}, -1, 1.5]) {
  const p=page();p.data.formData.themeRuntimeConfig=sceneConfig({rotation:'FIXED',rotationAnchor});p.openThemeScenes();assert.equal(p.data.runtimeEditor.show,true);
  assert.match(p.data.runtimeScenes.anchorText,/原值保留/);assert.doesNotMatch(p.data.runtimeScenes.anchorText,/PRIVATE_OPAQUE/);
  p.onRuntimeReplayToggle({detail:{value:true}});p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p._runtimePayload()).rotationAnchor,rotationAnchor);
 }
});
test('unsafe weekly anchors unknown rotations and invalid name shapes fail closed without raw replacement', () => {
 for(const extra of [{rotation:'WEEKLY'},{rotation:'WEEKLY',rotationAnchor:'0'},{rotation:'WEEKLY',rotationAnchor:-1},{rotation:'WEEKLY',rotationAnchor:9007199254740992},{rotation:'FUTURE'},{scenes:[{id:'alpha',name:3}]}]) {
  const p=page();const original=sceneConfig(extra);p.data.formData.themeRuntimeConfig=original;p.openThemeScenes();assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.onRuntimeSceneName(sceneName('alpha','  '));assert.equal(p.closeRuntimeEditor(),false);p.cancelRuntimeEditor();
});
test('disabled, nested and changed-source scene edits cannot create configuration or overwrite newer drafts', () => {
 const disabled=page(false);disabled.openThemeScenes();disabled.addRuntimeScene();disabled.onRuntimeReplayToggle({detail:{value:true}});assert.equal(disabled.data.formData.themeRuntimeConfig,undefined);assert.equal(disabled.data.runtimeEditor.show,false);
 const nested=page();nested._beginRuntimeChapter(0);assert.equal(nested.openThemeScenes(),false);nested._finishRuntimeChapter(false);nested.openChapterBackpack();assert.equal(nested.openThemeScenes(),false);assert.equal(nested.addRuntimeScene(),false);
 const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.onRuntimeReplayToggle({detail:{value:true}});const newer=sceneConfig({futureRoot:'newer'});p.data.formData.themeRuntimeConfig=newer;
 assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.themeRuntimeConfig,newer);assert.equal(p.data.runtimeEditor.show,false);
});
test('scene template exposes existing staged modes, exact weekly boundary and personal/whole-team semantics', () => {
 const root=path.join(__dirname,'../../pages/publish/fabu'),detail=fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8'),runtime=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8');
 assert.match(detail,/bindtap="openThemeScenes"/);for(const method of ['onRuntimeReplayToggle','onRuntimeRotation','addRuntimeScene','onRuntimeSceneName','chooseRuntimeScene','moveRuntimeScene','removeRuntimeScene'])assert.ok(runtime.includes('"'+method+'"'));
 assert.match(runtime,/UTC 周一/);assert.match(runtime,/北京时间周一 08:00/);assert.match(runtime,/不随手机时区变化/);assert.match(runtime,/当前队长整队重开/);assert.match(runtime,/个人库存与奖励仍分别管理/);assert.match(runtime,/仍需按原流程保存与发布/);
 assert.doesNotMatch(runtime,/bindinput=".*[Aa]nchor|bindchange=".*[Aa]nchor/);assert.equal(requests.length,0);
});
test('required scene name matches Java trim for empty NUL and every ASCII control through U+0020', () => {
 const blank=['', '\u0000', '\u0001\u0008\t\n\v\f\r\u001f ', Array.from({length:33},(_,i)=>String.fromCharCode(i)).join('')];
 for(const name of blank) {
  assert.throws(()=>editor.sceneSettings({scenes:[{id:'alpha',name}],currentScene:'alpha'},true),/场景名称/);
  const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.onRuntimeSceneName(sceneName('alpha',name));
  assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.themeRuntimeConfig,sceneConfig());p.cancelRuntimeEditor();
 }
});
test('required scene name preserves Java-nontrimmed NBSP Unicode spaces mixed controls and UTF-16 emoji bounds', () => {
 const valid=['\u00a0','\u2003','\ufeff','\u0000A\u0000',' \u00a0 ', '\u0000😀\u001f','😀'.repeat(20)];
 for(const name of valid) {
  const c={scenes:[{id:'alpha',name}],currentScene:'alpha'};assert.equal(editor.sceneSettings(c,true).scenes[0].name,name);
  const p=page();p.data.formData.themeRuntimeConfig=sceneConfig();p.openThemeScenes();p.onRuntimeSceneName(sceneName('alpha',name));assert.equal(p.closeRuntimeEditor(),true);
  assert.equal(JSON.parse(p._runtimePayload()).scenes[0].name,name);
 }
 assert.throws(()=>editor.sceneSettings({scenes:[{id:'alpha',name:'😀'.repeat(20)+'a'}],currentScene:'alpha'},true),/场景名称/);
 assert.throws(()=>editor.sceneSettings({scenes:[{id:'alpha',name:'\u00a0'.repeat(41)}],currentScene:'alpha'},true),/场景名称/);
});

const broadcastRow = (id='broadcast_a', extra={}) => Object.assign({id,content:'留意沿途提示',when:[{op:'GTE',var:'sys.actions',value:2}]},extra);
const broadcastTap = id => ({currentTarget:{dataset:{id}}});
function composeBroadcast(p, {content='本局提示', trigger=4, comparison=3, value='2', itemIndex=0}={}) {
 p.onRuntimeBroadcastDraft(event('content',content));
 if(trigger !== null) p.onRuntimeBroadcastDraft(event('triggerIndex',trigger));
 if(trigger === 0 || trigger === 1) p.onRuntimeBroadcastDraft(event('itemIndex',itemIndex));
 else if(comparison !== null) p.onRuntimeBroadcastDraft(event('comparisonIndex',comparison));
 p.onRuntimeBroadcastDraft(event('value',value));
>>>>>>> private-github/featu
}
function broadcastPage(extra={}) {const p=page();p.data.formData.themeRuntimeConfig=raw(extra);p.openThemeBroadcasts();return p;}

test('broadcast open cancel and no-op preserve raw absence null arrays formatting and unknown config',()=>{
 for(const rawValue of [undefined,null,'',' { "schemaVersion":1,"events":null,"future":{"x":1} } ',raw({events:[],future:{x:1}}),raw({events:[broadcastRow()]})]){
  const p=page();p.data.formData.themeRuntimeConfig=rawValue;p.openThemeBroadcasts();assert.equal(p.data.runtimeEditor.show,true);assert.equal(p.data.formData.themeRuntimeConfig,rawValue);
  assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,rawValue);
  p.openThemeBroadcasts();composeBroadcast(p);p.addRuntimeBroadcast();p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,rawValue);
 }
 assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('broadcast creation requires explicit trigger comparator item and integer without default unconditional event',()=>{
 const p=broadcastPage();composeBroadcast(p,{trigger:null});assert.equal(p.addRuntimeBroadcast(),false);assert.equal(p._runtimeConfig().events.length,0);
 p.clearRuntimeBroadcastDraft();composeBroadcast(p,{comparison:null});assert.equal(p.addRuntimeBroadcast(),false);
 for(const value of ['', ' ', '1.5', '1e2', '+1', 'NaN', '1000000', '-1000000']){p.clearRuntimeBroadcastDraft();composeBroadcast(p,{value});assert.equal(p.addRuntimeBroadcast(),false,value);}
 p.clearRuntimeBroadcastDraft();composeBroadcast(p,{trigger:0,itemIndex:999});assert.equal(p.addRuntimeBroadcast(),false);
 const empty=broadcastPage({items:[]});composeBroadcast(empty,{trigger:1});assert.equal(empty.addRuntimeBroadcast(),false);
 assert.equal(p._runtimeConfig().events.length,0);assert.equal(requests.length,0);
});
test('all five supported broadcast conditions create only exact existing server fields',()=>{
 for(let trigger=0;trigger<5;trigger++){
  const p=broadcastPage(),before=p.data.formData.themeRuntimeConfig;composeBroadcast(p,{trigger,value:trigger<2?'999':'-999999'});assert.equal(p.addRuntimeBroadcast(),true);
  const row=p._runtimeConfig().events[0];assert.deepEqual(Object.keys(row),['id','content','when']);assert.match(row.id,/^[a-z][a-z0-9_]{0,31}$/);
  assert.deepEqual(row.when,[trigger<2?{op:['ITEM_HELD','ITEM_ACQUIRED'][trigger],itemId:'a',quantity:999}:{op:'GTE',var:['sys.hp','sys.luck','sys.actions'][trigger-2],value:-999999}]);
  assert.equal(p.data.formData.themeRuntimeConfig,before);assert.equal(p.closeRuntimeEditor(),true);assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).events,[row]);
 }
 assert.equal(requests.length,0);
});
test('broadcast numeric comparators retain exact server operations and inclusive bounds',()=>{
 for(const [comparison,op] of ['EQ','NE','GT','GTE','LT','LTE'].entries()) for(const value of ['-999999','0','999999']){
  const p=broadcastPage();composeBroadcast(p,{comparison,value});assert.equal(p.addRuntimeBroadcast(),true);assert.equal(p._runtimeConfig().events[0].when[0].op,op);assert.equal(p._runtimeConfig().events[0].when[0].value,Number(value));
 }
 for(const value of ['0','-1','1000']){const p=broadcastPage();composeBroadcast(p,{trigger:0,value});assert.equal(p.addRuntimeBroadcast(),false);}
 const p=broadcastPage();composeBroadcast(p,{trigger:1,value:'1'});assert.equal(p.addRuntimeBroadcast(),true);
});
test('broadcast content matches Java trim and 500 UTF-16 units without normalization',()=>{
 for(const content of ['', '\0', Array.from({length:33},(_,i)=>String.fromCharCode(i)).join('')]){
  const p=broadcastPage();composeBroadcast(p,{content});assert.equal(p.addRuntimeBroadcast(),false);
 }
 for(const content of ['\u00a0','\u2003','\ufeff','\0正文\u001f','😀'.repeat(250),'字'.repeat(500)]){
  const p=broadcastPage();composeBroadcast(p,{content});assert.equal(p.addRuntimeBroadcast(),true);assert.equal(p._runtimeConfig().events[0].content,content);
 }
 const p=broadcastPage();assert.equal(p.onRuntimeBroadcastDraft(event('content','😀'.repeat(250)+'a')),false);assert.equal(p.data.runtimeBroadcastDraft.content,'');
 assert.equal(p.onRuntimeBroadcastDraft(event('content','字'.repeat(501))),false);
});
test('broadcast existing content validation blocks control-only Done and supports literal NBSP',()=>{
 const p=broadcastPage({events:[broadcastRow()]});const e=broadcastTap('broadcast_a');e.detail={value:'\0'};p.onRuntimeBroadcastContent(e);assert.equal(p.closeRuntimeEditor(),false);
 e.detail.value='\u00a0';p.onRuntimeBroadcastContent(e);assert.equal(p.closeRuntimeEditor(),true);assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).events[0].content,'\u00a0');
});
test('broadcast composer never silently disappears on Done and switching trigger clears incompatible selections',()=>{
 const p=broadcastPage();composeBroadcast(p);assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.runtimeEditor.show,true);
 p.onRuntimeBroadcastDraft(event('triggerIndex',0));assert.equal(p.data.runtimeBroadcastDraft.comparisonIndex,-1);assert.equal(p.data.runtimeBroadcastDraft.value,'');assert.equal(p.data.runtimeBroadcastDraft.itemIndex,-1);
 p.clearRuntimeBroadcastDraft();assert.equal(p.closeRuntimeEditor(),true);
});
test('advanced broadcasts retain nested effects grants conditions and unknown payload through text edits',()=>{
 const advanced=broadcastRow('advanced',{when:[{op:'NODE_COMPLETED',nodeId:42},{op:'GTE',var:'counter.clues',value:3}],grants:[{itemId:'a',quantity:1}],effects:[{var:'counter.seen',op:'INC',value:1}],future:{nested:[{x:1}]}});
 const scenes=[{id:'s',name:'独立幕',events:[broadcastRow('advanced',{content:'独立内容'})]}];
 const p=broadcastPage({events:[advanced],scenes,currentScene:'s',futureRoot:{x:1}}),before=p.data.formData.themeRuntimeConfig;
 assert.equal(p.data.runtimeBroadcasts.events[0].canRemove,false);assert.equal(p.removeRuntimeBroadcast(broadcastTap('advanced')),false);assert.equal(modalCalls.length,0);
 const e=broadcastTap('advanced');e.detail={value:'修改主广播文字'};p.onRuntimeBroadcastContent(e);assert.equal(p.closeRuntimeEditor(),true);
 const saved=JSON.parse(p.data.formData.themeRuntimeConfig);assert.deepEqual(saved.events,[Object.assign({},advanced,{content:'修改主广播文字'})]);assert.deepEqual(saved.scenes,scenes);assert.deepEqual(saved.futureRoot,{x:1});assert.notEqual(p.data.formData.themeRuntimeConfig,before);
});
test('root broadcast creation preserves scene override missing null and empty events distinctly',()=>{
 const scenes=[{id:'inherit',name:'继承'},{id:'empty',name:'独立空列表',events:[]},{id:'nil',name:'独立空值',events:null},{id:'own',name:'独立事件',events:[broadcastRow()]}];
 const p=broadcastPage({scenes,currentScene:'inherit'});assert.equal(p.data.runtimeBroadcasts.scopedOverrides,3);composeBroadcast(p);p.addRuntimeBroadcast();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).scenes,scenes);
});
test('advanced unconditional and extra-condition broadcasts cannot be removed or silently simplified',()=>{
 for(const extra of [{when:[]},{when:null},{when:[{op:'GTE',var:'sys.actions',value:1,extra:true}]},{grants:[{itemId:'a',quantity:1}]},{effects:[{var:'counter.a',op:'INC',value:1}]},{future:true}]){
  const row=broadcastRow('advanced',extra),p=broadcastPage({events:[row]});assert.equal(p.data.runtimeBroadcasts.events[0].canRemove,false);assert.equal(p.removeRuntimeBroadcast(broadcastTap(row.id)),false);assert.deepEqual(p._runtimeConfig().events,[row]);
 }
 assert.equal(modalCalls.length,0);
});
test('broadcast IDs remain unique with a fixed clock including deleted rows and 100 event boundary',()=>{
 const realNow=Date.now;Date.now=()=>1;
 try{const p=broadcastPage();composeBroadcast(p);p.addRuntimeBroadcast();const first=p._runtimeConfig().events[0].id;p.removeRuntimeBroadcast(broadcastTap(first));composeBroadcast(p);p.addRuntimeBroadcast();assert.notEqual(p._runtimeConfig().events[0].id,first);
  const full=broadcastPage({events:Array.from({length:100},(_,i)=>broadcastRow('b'+i))});composeBroadcast(full);assert.equal(full.addRuntimeBroadcast(),false);assert.equal(full._runtimeConfig().events.length,100);
 }finally{Date.now=realNow;}
});
test('broadcast delete confirmation binds stable target and preserves unrelated event ordering',()=>{
 confirm=false;const p=broadcastPage({events:[broadcastRow('a'),broadcastRow('b'),broadcastRow('c')]});p.removeRuntimeBroadcast(broadcastTap('b'));assert.equal(p._runtimeConfig().events.length,3);
 confirm=true;p.removeRuntimeBroadcast(broadcastTap('b'));assert.deepEqual(p._runtimeConfig().events.map(r=>r.id),['a','c']);assert.equal(p.closeRuntimeEditor(),true);assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).events.map(r=>r.id),['a','c']);
});
test('broadcast late delete cannot erase changed content or a reopened editor and repeated tap is single intent',()=>{
 confirm='manual';const p=broadcastPage({events:[broadcastRow()]});p.removeRuntimeBroadcast(broadcastTap('broadcast_a'));assert.equal(p.removeRuntimeBroadcast(broadcastTap('broadcast_a')),false);assert.equal(modalCalls.length,1);
 const e=broadcastTap('broadcast_a');e.detail={value:'新文字'};p.onRuntimeBroadcastContent(e);modalCalls[0].success({confirm:true});assert.equal(p._runtimeConfig().events[0].content,'新文字');
 p.removeRuntimeBroadcast(broadcastTap('broadcast_a'));p.cancelRuntimeEditor();p.openThemeBroadcasts();modalCalls[1].success({confirm:true});assert.equal(p._runtimeConfig().events.length,1);
});
test('broadcast mutations and pending deletion reject changed account topic draft revision permission source and disposal',()=>{
 const changes=[p=>{member=8;},p=>{p.data.editingTopicId=9;p.data.editLoaded=true;},p=>{p.data.draftUuid='other';},p=>{p.data.draftMemberId=99;},p=>{p.data.baseRevision=2;},p=>{p.data.operationScope='another';},p=>{p.data.editScope='WHITELIST';},p=>{p.data.runtimeEnabled=false;},p=>{p.data.formData.themeRuntimeConfig=raw({future:true});},p=>p._disposeRuntimeEditor()];
 for(const change of changes){member=7;confirm='manual';const p=broadcastPage({events:[broadcastRow()]});p.removeRuntimeBroadcast(broadcastTap('broadcast_a'));const callback=modalCalls[modalCalls.length-1];change(p);const before=JSON.stringify(p.data.formData);callback.success({confirm:true});assert.equal(p.onRuntimeBroadcastDraft(event('content','过期')),false);assert.equal(p.addRuntimeBroadcast(),false);assert.equal(JSON.stringify(p.data.formData),before);}
});
test('broadcast no-op text revert and add-then-remove preserve original raw bytes',()=>{
 const p=broadcastPage({events:[broadcastRow()],future:{x:1}}),original=p.data.formData.themeRuntimeConfig,e=broadcastTap('broadcast_a');e.detail={value:'暂改'};p.onRuntimeBroadcastContent(e);e.detail.value='留意沿途提示';p.onRuntimeBroadcastContent(e);p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 p.openThemeBroadcasts();composeBroadcast(p);p.addRuntimeBroadcast();p.removeRuntimeBroadcast(broadcastTap(p._runtimeConfig().events[1].id));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
});
test('broadcast corrupt identities text and condition counts fail closed while unknown condition payload is retained',()=>{
 for(const events of [[broadcastRow('A')],[broadcastRow('same'),broadcastRow('same')],[broadcastRow('b',{content:12})],[broadcastRow('b',{content:'字'.repeat(501)})],[broadcastRow('b',{when:Array.from({length:31},()=>({op:'EQ',var:'sys.hp',value:1}))})],Array.from({length:101},(_,i)=>broadcastRow('b'+i))]){
  const p=page();p.data.formData.themeRuntimeConfig=raw({events});const original=p.data.formData.themeRuntimeConfig;p.openThemeBroadcasts();assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
 const p=broadcastPage({events:[broadcastRow('b',{when:[{op:'future',private:{keep:1}}]})]});assert.equal(p.closeRuntimeEditor(),true);
});
test('broadcast invalid UI field and picker values cannot write arbitrary configuration',()=>{
 const p=broadcastPage(),before=JSON.stringify(p._runtimeConfig());
 for(const [field,value] of [['future','x'],['triggerIndex',5],['triggerIndex',-1],['triggerIndex','1.5'],['comparisonIndex',6],['itemIndex',99],['value',42],['content',null]])assert.equal(p.onRuntimeBroadcastDraft(event(field,value)),false);
 assert.equal(JSON.stringify(p._runtimeConfig()),before);assert.equal(p.onRuntimeBroadcastContent({currentTarget:{dataset:{id:'absent'}},detail:{value:'x'}}),false);
});
test('broadcast capability and nested editor boundary preserve disabled and existing draft contexts',()=>{
 for(const mode of ['off','locked','nested','chapter']){
  const p=page(mode!=='off');p.data.formData.themeRuntimeConfig=raw();const original=p.data.formData.themeRuntimeConfig;
  if(mode==='locked')p.data.editScope='WHITELIST';if(mode==='nested')p.openThemeScenes();if(mode==='chapter')p._beginRuntimeChapter(0);
  assert.equal(p.openThemeBroadcasts(),false);assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
});
test('broadcast template provides explicit staged creation and read-only advanced payload without sending actions',()=>{
 const template=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8'),entry=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/topic-detail-sheet.wxml'),'utf8');
 assert.match(entry,/bindtap="openThemeBroadcasts"/);assert.match(template,/bindtap="addRuntimeBroadcast"/);assert.match(template,/data-id="\{\{item.id\}\}" bindtap="removeRuntimeBroadcast"/);assert.match(template,/wx:if="\{\{item.canRemove\}\}"/);assert.match(template,/不默认触发/);assert.match(template,/已有独立配置（含空列表）/);assert.match(template,/已开局快照不变/);assert.match(template,/这里不发送或模拟广播/);
 assert.equal(requests.length,0);
});
test('broadcast editor refuses unsafe existing Long references instead of rounding preserved payload',()=>{
 const p=page();p.data.formData.themeRuntimeConfig='{"schemaVersion":1,"events":[{"id":"legacy","content":"旧事件","when":[{"op":"NODE_COMPLETED","nodeId":9007199254740993}]}]}';
 const original=p.data.formData.themeRuntimeConfig;assert.equal(p.openThemeBroadcasts(),false);assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,original);assert.match(notices.at(-1),/不能安全读取/);
});

function rulePage(extra={}) {const p=page();p.data.formData.themeRuntimeConfig=raw(extra);p.openThemeRuntimeRules();return p;}
const ruleToggle=value=>({detail:{value}});
test('runtime rules open no-op and cancel preserve raw missing null empty sources and existing values',()=>{
 const values=[undefined,null,'',' { "schemaVersion":1, "future":{"a":1} } ',raw({actionSources:null,rescueLimit:0,hpName:''}),raw({actionSources:[],actionLimit:1}),raw({actionSources:['DRAW','NODE','NODE'],rescueLimit:10,luckName:'运势'})];
 for(const value of values){const p=page();p.data.formData.themeRuntimeConfig=value;p.openThemeRuntimeRules();assert.equal(p.data.runtimeEditor.show,true);assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,value);p.openThemeRuntimeRules();p.onRuntimeRuleName(event('hpName','耐力'));p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,value);}
 assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('runtime rules never create default action budget or rescue count and enabled blank budget blocks Done',()=>{
 const p=rulePage(),original=p.data.formData.themeRuntimeConfig;assert.equal(p.data.runtimeRules.budgetEnabled,false);assert.equal(p.data.runtimeRules.budgetValue,'');assert.equal(p.data.runtimeRules.rescueValue,'');
 p.onRuntimeBudgetToggle(ruleToggle(true));assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.themeRuntimeConfig,original);assert.equal(p.data.runtimeEditor.show,true);
 p.onRuntimeBudgetToggle(ruleToggle(false));p.onRuntimeRuleName(event('hpName','耐力'));assert.equal(p.closeRuntimeEditor(),true);const saved=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal(saved.hpName,'耐力');assert.equal('actionLimit' in saved,false);assert.equal('rescueLimit' in saved,false);assert.equal('actionSources' in saved,false);
});
test('runtime action limit validates exact 1 to 99999 integer bounds with no coercive defaults',()=>{
 for(const value of ['', ' ', '0', '-1', '100000', '1.2', '1e2', '+1', 'NaN']){const p=rulePage();p.onRuntimeBudgetToggle(ruleToggle(true));p.onRuntimeRuleInteger(event('budgetValue',value));assert.equal(p.closeRuntimeEditor(),false,value);assert.equal('actionLimit' in JSON.parse(p.data.formData.themeRuntimeConfig),false);}
 for(const value of ['1','99999']){const p=rulePage();p.onRuntimeBudgetToggle(ruleToggle(true));p.onRuntimeRuleInteger(event('budgetValue',value));assert.equal(p.closeRuntimeEditor(),true);assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).actionLimit,Number(value));}
});
test('runtime action budget explicit disable removes only limit and toggle revert keeps original raw',()=>{
 const p=rulePage({actionLimit:27,actionSources:['DRAW'],future:{keep:true}}),before=p.data.formData.themeRuntimeConfig;
 p.onRuntimeBudgetToggle(ruleToggle(false));p.onRuntimeBudgetToggle(ruleToggle(true));assert.equal(p.data.runtimeRules.budgetValue,'27');p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
 p.openThemeRuntimeRules();p.onRuntimeBudgetToggle(ruleToggle(false));p.closeRuntimeEditor();const saved=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal('actionLimit' in saved,false);assert.deepEqual(saved.actionSources,['DRAW']);assert.deepEqual(saved.future,{keep:true});
});
test('runtime rescue bounds distinguish missing explicit zero and removal without changing recovery rules',()=>{
 for(const value of ['0','1','10']){const p=rulePage();p.data.formData.journeyRules=rules.serialize(rules.parse(''));const original=p.data.formData.journeyRules;p.onRuntimeRuleInteger(event('rescueValue',value));assert.equal(p.closeRuntimeEditor(),true);assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).rescueLimit,Number(value));assert.equal(p.data.formData.journeyRules,original);p.openThemeRuntimeRules();p.onRuntimeRuleInteger(event('rescueValue',''));p.closeRuntimeEditor();assert.equal('rescueLimit' in JSON.parse(p.data.formData.themeRuntimeConfig),false);}
 for(const value of ['-1','11','0.5','1e1',' ','+1']){const p=rulePage();p.onRuntimeRuleInteger(event('rescueValue',value));assert.equal(p.closeRuntimeEditor(),false,value);}
});
test('runtime names match optional Java text semantics and exact 20 UTF-16 units',()=>{
 for(const value of ['', '\0', '\u00a0', ' '.repeat(20), '😀'.repeat(10), '名'.repeat(20)]){const p=rulePage();p.onRuntimeRuleName(event('hpName',value));p.onRuntimeRuleName(event('luckName',value));assert.equal(p.closeRuntimeEditor(),true);const saved=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal(saved.hpName,value);assert.equal(saved.luckName,value);}
 const p=rulePage();assert.equal(p.onRuntimeRuleName(event('hpName','😀'.repeat(10)+'a')),false);assert.equal(p.onRuntimeRuleName(event('luckName','名'.repeat(21))),false);assert.equal(p.data.runtimeRules.hpNameConfigured,false);
});
test('runtime custom names clear explicitly while an empty configured name is preserved distinctly',()=>{
 const p=rulePage({hpName:'体力',luckName:''});p.clearRuntimeRuleName(event('hpName',''));p.closeRuntimeEditor();const saved=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal('hpName' in saved,false);assert.equal(saved.luckName,'');
 p.openThemeRuntimeRules();p.clearRuntimeRuleName(event('luckName',''));p.closeRuntimeEditor();assert.equal('luckName' in JSON.parse(p.data.formData.themeRuntimeConfig),false);
});
test('runtime action sources distinguish omitted all sources from explicit empty and null none',()=>{
 const p=rulePage();assert.ok(p.data.runtimeRules.sources.every(row=>row.enabled));for(const key of ['NODE','CHOICE','DRAW'])p.onRuntimeActionSource(event(key,false));p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).actionSources,[]);
 for(const source of [[],null]){const q=rulePage({actionSources:source});assert.ok(q.data.runtimeRules.sources.every(row=>!row.enabled));q.onRuntimeRuleName(event('hpName','耐力'));q.closeRuntimeEditor();assert.deepEqual(JSON.parse(q.data.formData.themeRuntimeConfig).actionSources,source);}
});
test('runtime source selection writes only existing enums and restores original order duplicates on effective no-op',()=>{
 for(const sources of [undefined,['DRAW','NODE','NODE'],['CHOICE','NODE','DRAW']]){const p=rulePage(sources===undefined?{}:{actionSources:sources}),before=p.data.formData.themeRuntimeConfig;const value=p.data.runtimeRules.sources.find(r=>r.key==='CHOICE').enabled;p.onRuntimeActionSource(event('CHOICE',!value));p.onRuntimeActionSource(event('CHOICE',value));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);}
 const p=rulePage({actionSources:[]});p.onRuntimeActionSource(event('DRAW',true));p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).actionSources,['DRAW']);
});
test('runtime rule edits preserve advanced events scenes endings grants card and unknown nested fields',()=>{
 const extra={events:[broadcastRow('evt',{effects:[{var:'counter.a',op:'INC',value:1}],grants:[{itemId:'a',quantity:1}]})],endings:[{id:'done',title:'原结局',fallback:true,success:true}],grants:[{id:'g',source:'ENDING',itemId:'a',quantity:1,endingId:'done'}],scenes:[{id:'s',name:'原场景',events:[],endings:[]}],currentScene:'s',rotation:'WEEKLY',rotationAnchor:0,card:{fields:[]},future:{x:[1,{kept:true}]}};
 const p=rulePage(extra),before=p.data.formData.themeRuntimeConfig;p.onRuntimeRuleName(event('luckName','机运'));p.onRuntimeBudgetToggle(ruleToggle(true));p.onRuntimeRuleInteger(event('budgetValue','8'));p.onRuntimeRuleInteger(event('rescueValue','2'));assert.equal(p.data.formData.themeRuntimeConfig,before);assert.equal(p.closeRuntimeEditor(),true);const saved=JSON.parse(p.data.formData.themeRuntimeConfig);for(const [key,value]of Object.entries(extra))assert.deepEqual(saved[key],value,key);assert.equal(saved.luckName,'机运');assert.equal(saved.actionLimit,8);assert.equal(saved.rescueLimit,2);
});
test('runtime rule all changes are draft-local until Done and cancel retires the staged values',()=>{
 const p=rulePage({actionLimit:9,rescueLimit:0,hpName:'原名'}),before=p.data.formData.themeRuntimeConfig;p.onRuntimeRuleName(event('hpName','新名'));p.onRuntimeRuleInteger(event('budgetValue','3'));p.onRuntimeRuleInteger(event('rescueValue','2'));p.onRuntimeActionSource(event('NODE',false));assert.equal(p.data.formData.themeRuntimeConfig,before);assert.equal(p._runtimeConfig().hpName,'原名');p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);p.openThemeRuntimeRules();assert.equal(p.data.runtimeRules.hpName,'原名');assert.equal(p.data.runtimeRules.budgetValue,'9');assert.equal(p.data.runtimeRules.rescueValue,'0');assert.equal(requests.length,0);
});
test('runtime rule mutations and Done cannot overwrite changed owner topic draft revision permission source or disposed page',()=>{
 const changes=[p=>{member=8;},p=>{p.data.editingTopicId=9;p.data.editLoaded=true;},p=>{p.data.draftUuid='new';},p=>{p.data.draftMemberId=4;},p=>{p.data.baseRevision=8;},p=>{p.data.operationScope='new';},p=>{p.data.editScope='WHITELIST';},p=>{p.data.runtimeEnabled=false;},p=>{p.data.formData.themeRuntimeConfig=raw({hpName:'更近版本'});},p=>p._disposeRuntimeEditor()];
 for(const change of changes){member=7;const p=rulePage();p.onRuntimeRuleName(event('hpName','过期'));change(p);const before=JSON.stringify(p.data.formData);assert.equal(p.onRuntimeRuleInteger(event('rescueValue','3')),false);assert.equal(p.onRuntimeBudgetToggle(ruleToggle(true)),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(JSON.stringify(p.data.formData),before);}
});
test('runtime rules reject invalid existing known field shapes and unsafe preserved integers without modifying raw',()=>{
 for(const extra of [{hpName:null},{luckName:12},{hpName:'名'.repeat(21)},{actionLimit:null},{actionLimit:0},{actionLimit:100000},{actionLimit:'3'},{rescueLimit:-1},{rescueLimit:11},{rescueLimit:1.5},{actionSources:['OTHER']},{actionSources:['NODE','NODE','NODE','NODE']},{actionSources:{}},{future:{value:Number.MAX_SAFE_INTEGER+1}}]){const p=page();p.data.formData.themeRuntimeConfig=raw(extra);const before=p.data.formData.themeRuntimeConfig;assert.equal(p.openThemeRuntimeRules(),false);assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,before);}
});
test('runtime rules reject arbitrary fields non-string integers and truthy non-boolean switch inputs',()=>{
 const p=rulePage(),before=JSON.stringify(p.data.runtimeRules);assert.equal(p.onRuntimeRuleName(event('capacity','9')),false);assert.equal(p.clearRuntimeRuleName(event('events','')),false);assert.equal(p.onRuntimeRuleInteger(event('actionLimit','9')),false);assert.equal(p.onRuntimeRuleInteger(event('rescueValue',3)),false);assert.equal(p.onRuntimeBudgetToggle(ruleToggle('true')),false);assert.equal(p.onRuntimeActionSource(event('OTHER',true)),false);assert.equal(p.onRuntimeActionSource(event('NODE',1)),false);assert.equal(JSON.stringify(p.data.runtimeRules),before);
});
test('runtime rules are unavailable in disabled locked nested loading and chapter editing contexts',()=>{
 for(const mode of ['off','locked','nested','chapter','loading']){const p=page(mode!=='off');p.data.formData.themeRuntimeConfig=raw();if(mode==='locked')p.data.editScope='WHITELIST';if(mode==='nested')p.openThemeBroadcasts();if(mode==='chapter')p._beginRuntimeChapter(0);if(mode==='loading'){p.data.editingTopicId=2;p.data.editLoading=true;}const before=p.data.formData.themeRuntimeConfig;assert.equal(p.openThemeRuntimeRules(),false);assert.equal(p.data.formData.themeRuntimeConfig,before);}
});
test('runtime rule names budget rescue and source changes reverted before Done preserve raw formatting',()=>{
 const p=page();p.data.formData.themeRuntimeConfig=' { "schemaVersion":1, "hpName":"耐力", "actionLimit":9, "rescueLimit":0, "actionSources":["DRAW","NODE"] } ';const before=p.data.formData.themeRuntimeConfig;p.openThemeRuntimeRules();p.clearRuntimeRuleName(event('hpName',''));p.onRuntimeRuleName(event('hpName','耐力'));p.onRuntimeRuleInteger(event('budgetValue','009'));p.onRuntimeRuleInteger(event('rescueValue','00'));p.onRuntimeActionSource(event('CHOICE',true));p.onRuntimeActionSource(event('CHOICE',false));assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,before);
});
test('runtime rule validation shares exact field bounds with publish validation',()=>{
 for(const extra of [{actionLimit:0},{rescueLimit:11},{hpName:'x'.repeat(21)},{actionSources:['OTHER']}]){const p=page();p.data.formData.themeRuntimeConfig=raw(extra);assert.notEqual(p._validateRuntimeConfig(),'');}
 const p=page();p.data.formData.themeRuntimeConfig=raw({hpName:'',luckName:'\0',actionLimit:99999,actionSources:null,rescueLimit:10});assert.equal(p._validateRuntimeConfig(),'');
});
test('runtime rules template names existing fields explicit staging and no default activation',()=>{
 const template=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8'),entry=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/topic-detail-sheet.wxml'),'utf8');assert.match(entry,/bindtap="openThemeRuntimeRules"/);assert.match(template,/不会自动填入默认上限/);assert.match(template,/留空移除这项配置/);assert.match(template,/全部关闭会明确保存空列表/);assert.match(template,/恢复量仍由主题已有的力竭休整配置决定/);assert.match(template,/这里不会开启线上能力、发放奖励或改变结局章/);assert.equal(requests.length,0);
});

const attributeRules = (extra={}) => JSON.stringify({schemaVersion:1,stateEnabled:true,attributes:[
 {key:'relation.trust',label:'信任',initial:5}, {key:'counter.progress',label:'进度'}, {key:'clue.done',label:'发现'}, {key:'tag.role',label:'身份',initial:1},
],...extra});
function attributeEventPage(extra={},journeyRules=attributeRules()) {const p=page();p.data.formData.journeyRules=journeyRules;p.data.formData.themeRuntimeConfig=raw(extra);p.openThemeBroadcasts();return p;}
function effectDraft(p,{state=1,op=1,value='2'}={}) {
 if(state!==null)p.onRuntimeBroadcastEffectDraft(event('effectStateIndex',state));
 if(op!==null)p.onRuntimeBroadcastEffectDraft(event('effectOpIndex',op));
 p.onRuntimeBroadcastEffectDraft(event('effectValue',value));
}
test('declared attribute event choices use only validated clue/relation/counter keys, never tag or arbitrary system facts',()=>{
 const p=attributeEventPage();assert.deepEqual(p.data.runtimeBroadcastTriggers.slice(5).map(x=>x.key),['relation.trust','counter.progress','clue.done']);
 assert.deepEqual(p.data.runtimeBroadcastAttributes.keys.map(x=>x.key),['relation.trust','counter.progress','clue.done']);
 assert.ok(p.data.runtimeBroadcastAttributes.keys[0].label.includes('信任'));
 for(const attrs of [null,[{key:'sys.hp',label:'bad'}],[{key:'counter.a',label:'A'},{key:'counter.a',label:'B'}],Array.from({length:33},(_,i)=>({key:'counter.a'+i,label:'A'}))]){
  const bad=attributeEventPage({},JSON.stringify({schemaVersion:1,attributes:attrs}));assert.equal(bad.data.runtimeBroadcastTriggers.length,5);assert.equal(bad.data.runtimeBroadcastAttributes.keys.length,0);assert.ok(bad.data.runtimeBroadcastAttributes.error);
 }
});
test('each declared variable and comparison serializes exact existing event condition, without enabling state or adding effects',()=>{
 for(let index=5;index<8;index++) for(let comparison=0;comparison<6;comparison++){
  const jr=attributeRules({stateEnabled:false}),p=attributeEventPage({},jr);composeBroadcast(p,{trigger:index,comparison,value:'-999999'});assert.equal(p.addRuntimeBroadcast(),true);
  const row=p._runtimeConfig().events[0];assert.deepEqual(row.when,[{op:['EQ','NE','GT','GTE','LT','LTE'][comparison],var:['relation.trust','counter.progress','clue.done'][index-5],value:-999999}]);
  assert.equal(row.effects,undefined);assert.equal(p.data.formData.journeyRules,jr);assert.equal(p.data.runtimeBroadcastAttributes.enabled,false);assert.equal(p.closeRuntimeEditor(),true);
 }
 assert.equal(requests.length,0);
});
test('effect creation requires explicit key operation and integer; no partial selection becomes an effect',()=>{
 for(const input of [{state:null},{op:null},{value:''},{value:' '},{value:'1.5'},{value:'1e2'},{value:'+1'},{value:'1000'},{value:'-1000'}]){
  const p=attributeEventPage();effectDraft(p,input);assert.equal(p.addRuntimeBroadcastEffect(),false);assert.equal(p.data.runtimeBroadcastDraft.effects.length,0);
 }
 const p=attributeEventPage();composeBroadcast(p,{trigger:5});effectDraft(p,{value:''});assert.equal(p.addRuntimeBroadcast(),false);assert.equal(p._runtimeConfig().events.length,0);p.clearRuntimeBroadcastEffectDraft();assert.equal(p.addRuntimeBroadcast(),true);assert.equal(p._runtimeConfig().events[0].effects,undefined);
});
test('SET/INC effects preserve order and inclusive bounds and strip all presentation fields from payload',()=>{
 const p=attributeEventPage(),before=p.data.formData.themeRuntimeConfig;composeBroadcast(p,{trigger:5,value:'5'});
 for(const [op,value] of [[0,'-999'],[1,'999'],[0,'0']]){effectDraft(p,{op,value});assert.equal(p.addRuntimeBroadcastEffect(),true);}
 assert.equal(p.addRuntimeBroadcast(),true);assert.deepEqual(p._runtimeConfig().events[0].effects,[{var:'counter.progress',op:'SET',value:-999},{var:'counter.progress',op:'INC',value:999},{var:'counter.progress',op:'SET',value:0}]);
 assert.equal(p.data.formData.themeRuntimeConfig,before);assert.equal(p.closeRuntimeEditor(),true);assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('new effects stop at 20 and stable IDs prevent stale removal from touching re-added entries',()=>{
 const p=attributeEventPage();for(let i=0;i<20;i++){effectDraft(p);assert.equal(p.addRuntimeBroadcastEffect(),true);}effectDraft(p);assert.equal(p.addRuntimeBroadcastEffect(),false);
 const id=p.data.runtimeBroadcastDraft.effects[0].id;assert.equal(p.removeRuntimeBroadcastEffect(broadcastTap(id)),true);assert.equal(p.addRuntimeBroadcastEffect(),true);assert.equal(p.removeRuntimeBroadcastEffect(broadcastTap(id)),false);assert.equal(p.data.runtimeBroadcastDraft.effects.length,20);
 assert.notEqual(p.data.runtimeBroadcastDraft.effects.at(-1).id,id);
});
test('existing custom/advanced effect or grant rows remain text-only and verbatim except explicit content edit',()=>{
 const rows=[broadcastRow('custom',{when:[{op:'GTE',var:'relation.trust',value:5}]}),broadcastRow('effects',{effects:[{var:'counter.progress',op:'INC',value:3}],grants:[{itemId:'a',quantity:1}],future:{raw:[1]}})];
 const p=attributeEventPage({events:rows});assert.ok(p.data.runtimeBroadcasts.events.every(row=>!row.canRemove));
 assert.match(p.data.runtimeBroadcasts.events[0].conditionLabel,/信任/);
 for(const row of rows)assert.equal(p.removeRuntimeBroadcast(broadcastTap(row.id)),false);
 const e=broadcastTap('effects');e.detail={value:'明确改文字'};p.onRuntimeBroadcastContent(e);p.closeRuntimeEditor();
 assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).events,[rows[0],{...rows[1],content:'明确改文字'}]);
});
test('new attribute event can be removed during this editor only; after reopening it is preserved as an advanced row',()=>{
 const p=attributeEventPage();composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();const id=p._runtimeConfig().events[0].id;
 assert.equal(p.data.runtimeBroadcasts.events[0].canRemove,true);confirm=false;p.removeRuntimeBroadcast(broadcastTap(id));assert.equal(p._runtimeConfig().events.length,1);
 p.closeRuntimeEditor();p.openThemeBroadcasts();assert.equal(p.data.runtimeBroadcasts.events[0].canRemove,false);assert.equal(p.removeRuntimeBroadcast(broadcastTap(id)),false);
});
test('new effect event add/remove and composition clear/cancel preserve original raw configuration',()=>{
 const p=attributeEventPage(),before=p.data.formData.themeRuntimeConfig;composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();p.removeRuntimeBroadcast(broadcastTap(p._runtimeConfig().events[0].id));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
 p.openThemeBroadcasts();effectDraft(p);p.addRuntimeBroadcastEffect();assert.equal(p.closeRuntimeEditor(),false);p.clearRuntimeBroadcastDraft();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
 p.openThemeBroadcasts();composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
});
test('changed declaration labels, ordering, keys or enable switch retire all attribute mutations and Done',()=>{
 for(const change of [r=>r.attributes.reverse(),r=>r.attributes[0].label='另一个名字',r=>r.attributes[0].key='relation.other',r=>r.attributes.splice(0,1),r=>r.stateEnabled=false]){
  const p=attributeEventPage(),before=p.data.formData.themeRuntimeConfig;composeBroadcast(p,{trigger:5});effectDraft(p);const r=JSON.parse(p.data.formData.journeyRules);change(r);p.data.formData.journeyRules=JSON.stringify(r);
  assert.equal(p.addRuntimeBroadcastEffect(),false);assert.equal(p.addRuntimeBroadcast(),false);assert.equal(p.onRuntimeBroadcastEffectDraft(event('effectValue','1')),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.themeRuntimeConfig,before);
 }
});
test('new effect handlers enforce owner revision permission and disposal fences',()=>{
 for(const change of [p=>member=8,p=>p.data.baseRevision=5,p=>p.data.editScope='WHITELIST',p=>p._disposeRuntimeEditor()]){
  member=7;const p=attributeEventPage();effectDraft(p);p.addRuntimeBroadcastEffect();const id=p.data.runtimeBroadcastDraft.effects[0].id;change(p);
  assert.equal(p.onRuntimeBroadcastEffectDraft(event('effectValue','5')),false);assert.equal(p.addRuntimeBroadcastEffect(),false);assert.equal(p.removeRuntimeBroadcastEffect(broadcastTap(id)),false);assert.equal(p.clearRuntimeBroadcastEffectDraft(),false);
 }
});
test('delayed removal of a new effect event cannot survive source change or reopen and IDs are not recycled',()=>{
 confirm='manual';const p=attributeEventPage();composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();const id=p._runtimeConfig().events[0].id;p.removeRuntimeBroadcast(broadcastTap(id));const pending=modalCalls.at(-1);
 p.data.formData.journeyRules=attributeRules({stateEnabled:false});pending.success({confirm:true});assert.equal(p._runtimeConfig().events.length,1);
 p.cancelRuntimeEditor();p.openThemeBroadcasts();composeBroadcast(p,{trigger:5});p.addRuntimeBroadcast();pending.success({confirm:true});assert.equal(p._runtimeConfig().events.length,1);
});
test('root attribute events preserve per-scene absence and explicit empty or independent event overrides',()=>{
 const scenes=[{id:'inherit',name:'沿用'},{id:'empty',name:'无事件',events:[]},{id:'own',name:'独立',events:[broadcastRow('scene',{effects:[{var:'counter.progress',op:'SET',value:1}]})]}];
 const p=attributeEventPage({scenes,currentScene:'inherit'});assert.equal(p.data.runtimeBroadcasts.scopedOverrides,2);composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).scenes,scenes);
});
test('tampered presentation choices and unsupported tag/sys effects cannot create unauthorized payload shapes',()=>{
 const p=attributeEventPage();p.data.runtimeBroadcastAttributes.keys.push({key:'tag.a',label:'fake'});assert.equal(p.onRuntimeBroadcastEffectDraft(event('effectStateIndex',3)),false);
 for(const [field,value] of [['effectOpIndex',2],['effectStateIndex',-1],['effectValue',0],['effectValue','1'.repeat(17)],['effects',[]]])assert.equal(p.onRuntimeBroadcastEffectDraft(event(field,value)),false);
 composeBroadcast(p,{trigger:5});for(const variable of ['tag.a','sys.hp','counter.undeclared']){p.data.runtimeBroadcastDraft.effects=[{var:variable,op:'SET',value:1}];assert.equal(p.addRuntimeBroadcast(),false);}
 assert.equal(p._runtimeConfig().events.length,0);
});
test('attribute event markup makes explicit operations and pending composition available without live execution',()=>{
 const text=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8');
 for(const field of ['effectStateIndex','effectOpIndex','effectValue'])assert.ok(text.includes('data-field="'+field+'"'));
 for(const action of ['addRuntimeBroadcastEffect','removeRuntimeBroadcastEffect','clearRuntimeBroadcastEffectDraft'])assert.ok(text.includes('bindtap="'+action+'"'));
 assert.match(text,/tag 不属于本事件接口支持的变量/);assert.match(text,/按列表顺序执行/);assert.match(text,/这里不模拟执行/);
});
test('new attribute event add/remove retains exact null, absent and empty event representations',()=>{
 for(const source of [undefined,null,'', ' { "schemaVersion":1,"events":null } ', ' {"schemaVersion":1} ', ' {"schemaVersion":1,"events":[]} ']){
  const p=page();p.data.formData.journeyRules=attributeRules();p.data.formData.themeRuntimeConfig=source;p.openThemeBroadcasts();composeBroadcast(p,{trigger:5});effectDraft(p);p.addRuntimeBroadcastEffect();p.addRuntimeBroadcast();p.removeRuntimeBroadcast(broadcastTap(p._runtimeConfig().events[0].id));assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,source);
 }
});

const nodeGrant=(id='node_a',extra={})=>({id,source:'NODE',nodeId:7,itemId:'a',quantity:1,...extra});
function nodeGrantPage(extra={},options={}){
 const p=page();Object.assign(p.data,{editingTopicId:12,editLoaded:true});p.data.formData.chapters=[{name:'一章',nodes:[{id:7,name:'起点'},{id:8,name:'终点'},{_localId:'new',name:'新站点'}]}];
 p.data.formData.themeRuntimeConfig=raw(extra);if(options.setup)options.setup(p);p.openThemeNodeGrants();return p;
}
function composeNodeGrant(p,{node=0,itemIndex=0,quantity='1'}={}){
 if(node!==null)p.onRuntimeNodeGrantDraft(event('nodeIndex',node));if(itemIndex!==null)p.onRuntimeNodeGrantDraft(event('itemIndex',itemIndex));p.onRuntimeNodeGrantDraft(event('quantity',quantity));
}
test('saved-node grant flow creates exact NODE shape only after explicit node item and quantity, without an API call',()=>{
 const p=nodeGrantPage(),before=p.data.formData.themeRuntimeConfig;assert.equal(p.data.runtimeGrantNodes.length,2);assert.equal(p.data.runtimeGrantUnsaved,true);composeNodeGrant(p,{quantity:'99'});
 assert.equal(p.saveRuntimeNodeGrant(),true);const g=p._runtimeConfig().grants[0];assert.deepEqual(g,{id:g.id,source:'NODE',nodeId:7,itemId:'a',quantity:99});assert.match(g.id,/^[a-z][a-z0-9_]{0,31}$/);
 assert.equal(p.data.formData.themeRuntimeConfig,before);assert.equal(p.closeRuntimeEditor(),true);assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants,[g]);assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('new/unsaved/unrepresentable nodes cannot be selected and duplicate saved node IDs fail closed',()=>{
 const fresh=nodeGrantPage({}, {setup:p=>{p.data.editingTopicId='';p.data.editLoaded=false;}});assert.deepEqual(fresh.data.runtimeGrantNodes,[]);composeNodeGrant(fresh);assert.equal(fresh.saveRuntimeNodeGrant(),false);
 const p=nodeGrantPage({}, {setup:p=>p.data.formData.chapters=[{nodes:[{id:'7',name:'有效'},{id:9007199254740992},{id:'9007199254740993'},{id:'7e1'},{id:0},{id:-1},{id:'temp_1'}]}]});assert.deepEqual(p.data.runtimeGrantNodes.map(n=>n.id),[7]);
 const duplicate=nodeGrantPage({}, {setup:p=>p.data.formData.chapters=[{nodes:[{id:7},{id:'7'}]}]});assert.equal(duplicate.data.runtimeEditor.show,false);assert.match(notices.at(-1),/重复/);
 const huge=nodeGrantPage({}, {setup:p=>p.data.formData.chapters=[{nodes:Array.from({length:501},(_,i)=>({id:i+1}))}]});assert.equal(huge.data.runtimeEditor.show,false);assert.match(notices.at(-1),/500/);
});
test('node grant quantity and picker inputs reject missing fractional exponent and out-of-range values',()=>{
 for(const draft of [{node:null},{itemIndex:null},{quantity:''},{quantity:'0'},{quantity:'100'},{quantity:'1.5'},{quantity:'1e1'},{quantity:'-1'},{quantity:'+1'}]){const p=nodeGrantPage();composeNodeGrant(p,draft);assert.equal(p.saveRuntimeNodeGrant(),false);assert.equal(p._runtimeConfig().grants.length,0);}
 const p=nodeGrantPage();for(const [field,value]of [['nodeIndex',2],['itemIndex',1],['quantity',3],['quantity','1'.repeat(9)],['source','DRAW'],['editingId','other']])assert.equal(p.onRuntimeNodeGrantDraft(event(field,value)),false);
});
test('existing strictly simple rows edit node material quantity in place with stable ID and ordering',()=>{
 const p=nodeGrantPage({items:[item(),item('b')],grants:[nodeGrant('before'),nodeGrant('edit'),nodeGrant('after')]});assert.equal(p.editRuntimeNodeGrant(broadcastTap('edit')),true);composeNodeGrant(p,{node:1,itemIndex:1,quantity:'2'});assert.equal(p.saveRuntimeNodeGrant(),true);p.closeRuntimeEditor();
 assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants,[nodeGrant('before'),nodeGrant('edit',{nodeId:8,itemId:'b',quantity:2}),nodeGrant('after')]);
 p.openThemeNodeGrants();assert.equal(p.editRuntimeNodeGrant(broadcastTap('edit')),true);assert.equal(p.data.runtimeNodeGrantDraft.quantity,'2');assert.equal(requests.length,0);
});
test('unchanged edit and edit-then-revert preserve raw field order and exact original bytes',()=>{
 const original=' {"schemaVersion":1,"capacity":6,"items":[{"id":"a","kind":"ITEM","name":"水","image":"https://example.test/water.png"}],"grants":[{"quantity":1,"itemId":"a","nodeId":7,"source":"NODE","id":"n"}]} ';
 const p=nodeGrantPage({}, {setup:p=>p.data.formData.themeRuntimeConfig=original});p.editRuntimeNodeGrant(broadcastTap('n'));p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 p.openThemeNodeGrants();p.editRuntimeNodeGrant(broadcastTap('n'));composeNodeGrant(p,{quantity:'2'});p.saveRuntimeNodeGrant();p.editRuntimeNodeGrant(broadcastTap('n'));composeNodeGrant(p,{quantity:'1'});p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
});
test('pending row editing must be committed or cancelled; cancel and sheet dismissal never alter raw draft',()=>{
 const p=nodeGrantPage({grants:[nodeGrant()]}),before=p.data.formData.themeRuntimeConfig;p.editRuntimeNodeGrant(broadcastTap('node_a'));p.onRuntimeNodeGrantDraft(event('quantity','2'));
 assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.editRuntimeNodeGrant(broadcastTap('node_a')),false);p.clearRuntimeNodeGrantDraft();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
 p.openThemeNodeGrants();p.editRuntimeNodeGrant(broadcastTap('node_a'));composeNodeGrant(p,{quantity:'3'});p.saveRuntimeNodeGrant();p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
});
test('conditions extras other sources unknown references and nonnumeric node IDs remain read-only and verbatim',()=>{
 const grants=[nodeGrant('when',{when:[{op:'ITEM_HELD',itemId:'a',quantity:1,future:true}]}),nodeGrant('extra',{future:{keep:[1]}}),nodeGrant('missing',{nodeId:9}),nodeGrant('string',{nodeId:'7'}),nodeGrant('baditem',{itemId:'missing'}),{id:'end',source:'ENDING',itemId:'a',quantity:1}, {id:'choice',source:'CHOICE',nodeId:7,itemId:'a',quantity:1,step:'x',option:'y'}];
 const p=nodeGrantPage({grants}),before=p.data.formData.themeRuntimeConfig;assert.equal(p.data.runtimeNodeGrants.protectedCount,2);assert.ok(p.data.runtimeNodeGrants.rows.every(row=>!row.editable));
 for(const row of grants){assert.equal(p.editRuntimeNodeGrant(broadcastTap(row.id)),false);assert.equal(p.removeRuntimeNodeGrant(broadcastTap(row.id)),false);}
 p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);
});
test('explicit simple-row removal preserves advanced rows and confirms only staged future change',()=>{
 const complex=nodeGrant('complex',{when:[{op:'GTE',var:'sys.hp',value:1}]});const p=nodeGrantPage({grants:[nodeGrant(),complex]});confirm=false;p.removeRuntimeNodeGrant(broadcastTap('node_a'));assert.equal(p._runtimeConfig().grants.length,2);
 confirm=true;p.removeRuntimeNodeGrant(broadcastTap('node_a'));assert.match(modalCalls.at(-1).content,/个人库存不变/);assert.equal(p._runtimeConfig().grants.length,1);p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants,[complex]);
});
test('late simple-row removal cannot erase updated or currently edited data, or cross close/reopen',()=>{
 confirm='manual';const p=nodeGrantPage({grants:[nodeGrant()]});p.removeRuntimeNodeGrant(broadcastTap('node_a'));const first=modalCalls.at(-1);p.editRuntimeNodeGrant(broadcastTap('node_a'));first.success({confirm:true});assert.equal(p._runtimeConfig().grants.length,1);
 p.clearRuntimeNodeGrantDraft();p.removeRuntimeNodeGrant(broadcastTap('node_a'));const second=modalCalls.at(-1);p.editRuntimeNodeGrant(broadcastTap('node_a'));composeNodeGrant(p,{quantity:'2'});p.saveRuntimeNodeGrant();second.success({confirm:true});assert.equal(p._runtimeConfig().grants[0].quantity,2);
 p.removeRuntimeNodeGrant(broadcastTap('node_a'));const third=modalCalls.at(-1);p.cancelRuntimeEditor();p.openThemeNodeGrants();third.success({confirm:true});assert.equal(p._runtimeConfig().grants.length,1);
});
test('node/catalog/rules/owner/topic/revision/permission/load changes invalidate grant editing and delayed removal',()=>{
 const changes=[p=>p.data.formData.chapters[0].nodes.reverse(),p=>p.data.formData.chapters[0].nodes[0].id=9,p=>p.data.formData.themeRuntimeConfig=raw({items:[item('other')]}),p=>p.data.formData.journeyRules='{}',p=>member=8,p=>p.data.editingTopicId=13,p=>p.data.baseRevision=2,p=>p.data.draftUuid='new',p=>p.data.editScope='WHITELIST',p=>p._editLoadToken=9];
 for(const change of changes){member=7;confirm='manual';const p=nodeGrantPage({grants:[nodeGrant()]});p.removeRuntimeNodeGrant(broadcastTap('node_a'));const pending=modalCalls.at(-1);change(p);const before=JSON.stringify(p.data.formData);pending.success({confirm:true});assert.equal(p.saveRuntimeNodeGrant(),false);assert.equal(p.editRuntimeNodeGrant(broadcastTap('node_a')),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(JSON.stringify(p.data.formData),before);}
});
test('new grant IDs never recycle after deletion even with a fixed clock and late duplicate confirmation',()=>{
 const p=nodeGrantPage();const time=Date.now;Date.now=()=>77;try{composeNodeGrant(p);p.saveRuntimeNodeGrant();const first=p._runtimeConfig().grants[0].id;p.removeRuntimeNodeGrant(broadcastTap(first));const old=modalCalls.at(-1);composeNodeGrant(p);p.saveRuntimeNodeGrant();const second=p._runtimeConfig().grants[0].id;assert.notEqual(first,second);old.success({confirm:true});assert.equal(p._runtimeConfig().grants[0].id,second);}finally{Date.now=time;}
});
test('root/scoped grants and no-op absent/null/[] distinctions remain unchanged',()=>{
 const scenes=[{id:'inherit',name:'沿用'},{id:'empty',name:'空',grants:[]},{id:'own',name:'独立',grants:[nodeGrant('scene')]}];const p=nodeGrantPage({scenes,currentScene:'inherit'});assert.equal(p.data.runtimeNodeGrants.scopedOverrides,2);composeNodeGrant(p);p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).scenes,scenes);
 for(const grants of [undefined,null,[]]){const p=nodeGrantPage({grants}),before=p.data.formData.themeRuntimeConfig;composeNodeGrant(p);p.saveRuntimeNodeGrant();p.removeRuntimeNodeGrant(broadcastTap(p._runtimeConfig().grants[0].id));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,before);}
});
test('300 root grants include other sources; duplicate or corrupt grant IDs block new management',()=>{
 const grants=Array.from({length:300},(_,i)=>({id:'g'+i,source:'ENDING',itemId:'a',quantity:1}));const p=nodeGrantPage({grants});composeNodeGrant(p);assert.equal(p.saveRuntimeNodeGrant(),false);assert.equal(p._runtimeConfig().grants.length,300);
 for(const bad of [[nodeGrant(),nodeGrant()],[nodeGrant('bad\n')],grants.concat(nodeGrant())]){const p=nodeGrantPage({grants:bad});assert.equal(p.data.runtimeEditor.show,false);}
});
test('stat-affecting catalog items require existing enabled check attributes; plain items and medals do not activate them',()=>{
 const stat={...item(),stat:'HP',delta:1};const p=nodeGrantPage({items:[stat]});composeNodeGrant(p);assert.equal(p.saveRuntimeNodeGrant(),false);assert.equal(p.data.formData.journeyRules,'');
 const active=nodeGrantPage({items:[stat]},{setup:p=>p.data.formData.journeyRules='{"schemaVersion":1,"checkEnabled":true,"hp":{"enabled":true}}'});composeNodeGrant(active);assert.equal(active.saveRuntimeNodeGrant(),true);
 const medal=nodeGrantPage({items:[{...item(),kind:'MEDAL'}]});composeNodeGrant(medal);assert.equal(medal.saveRuntimeNodeGrant(),true);assert.equal(medal.data.formData.journeyRules,'');
});
test('selector presentation mutation cannot manufacture a saved node; capability/load/disposal gates remain enforced',()=>{
 const p=nodeGrantPage();p.data.runtimeGrantNodes.push({id:999,label:'fake'});assert.equal(p.onRuntimeNodeGrantDraft(event('nodeIndex',2)),false);p._disposeRuntimeEditor();assert.equal(p.saveRuntimeNodeGrant(),false);
 for(const setup of [p=>p.data.runtimeEnabled=false,p=>p.data.editScope='WHITELIST',p=>p.data.editLoaded=false]){const p=nodeGrantPage({}, {setup});assert.equal(p.data.runtimeEditor.show,false);}
});
test('node-grant markup includes complete explicit create/edit/remove/cancel flow and save-first/capacity explanations',()=>{
 const root=path.join(__dirname,'../../pages/publish/fabu');const markup=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8'),detail=fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8');assert.match(detail,/bindtap="openThemeNodeGrants"/);
 for(const name of ['saveRuntimeNodeGrant','clearRuntimeNodeGrantDraft','editRuntimeNodeGrant','removeRuntimeNodeGrant'])assert.ok(markup.includes('bindtap="'+name+'"'));
 assert.match(markup,/可信站点完成确认记录/);assert.match(markup,/原保存流程/);assert.match(markup,/数量超过容量时无法一次领取/);assert.match(markup,/含不支持的条件、扩展字段/);
});
test('500 saved nodes and maximum safe node ID are accepted, oversized draft source fails closed',()=>{
 const p=nodeGrantPage({}, {setup:p=>p.data.formData.chapters=[{name:'章',nodes:Array.from({length:500},(_,i)=>({id:i===499?Number.MAX_SAFE_INTEGER:i+1,name:'站'}))}]});assert.equal(p.data.runtimeGrantNodes.length,500);composeNodeGrant(p,{node:499});assert.equal(p.saveRuntimeNodeGrant(),true);assert.equal(p._runtimeConfig().grants[0].nodeId,Number.MAX_SAFE_INTEGER);
 const huge=nodeGrantPage({}, {setup:p=>p.data.formData.chapters=[{name:'x'.repeat(1000001),nodes:[{id:7}]}]});assert.equal(huge.data.runtimeEditor.show,false);assert.match(notices.at(-1),/草稿配置无法确认/);
});

const checkSettings=require('../../pages/publish/fabu/journey-check-settings');
function checkPolicyPage(rawRules) {const p=page();p.data.formData.journeyRules=rawRules;p.data.formData.themeRuntimeConfig=raw();p.openJourneyCheckSettings();return p;}
function checkNumber(p,path,value){return p.onJourneyCheckNumber(event(path,value));}
const validSmallRules=()=>({schemaVersion:1,hp:{max:3,init:3},luck:{max:0,init:0},recovery:{stationEndIfHurt:2,stageRestHpFloor:3,stageRestLuckFloor:0,exhaustRestHp:3}});
test('check policy projection preserves omitted/null/explicit representations and Java nullable defaults',()=>{
 const raw=' {"schemaVersion":1,"checkEnabled":null,"tiers":{"easy":{"dc":null}},"recovery":null,"modCap":null} ';
 const draft=checkSettings.project(raw);assert.equal(draft.enabledMode,'null');assert.equal(draft.fields[0].mode,'null');assert.equal(draft.fields.find(x=>x.path==='modCap').mode,'null');assert.equal(draft.fields[1].mode,'missing');assert.equal(checkSettings.apply(raw,draft),raw);
 const normalized=checkSettings.normalize(raw);assert.equal(normalized.checkEnabled,false);assert.deepEqual(Object.values(normalized.values),[10,13,16,1,2,3,6,1,2,6,1,5]);
});
test('check panel open/no-op/cancel preserves exact rule and theme config bytes without default activation',()=>{
 for(const rulesRaw of [undefined,null,'','  ',' { } ',' {"schemaVersion":1,"checkEnabled":false,"recovery":{"exhaustTag":"tag.old","future":[1]},"future":{"keep":1}} ']){
  const p=checkPolicyPage(rulesRaw),theme=p.data.formData.themeRuntimeConfig;p.closeRuntimeEditor();assert.equal(p.data.formData.journeyRules,rulesRaw);assert.equal(p.data.formData.themeRuntimeConfig,theme);
  p.openJourneyCheckSettings();checkNumber(p,'tiers.easy.dc','9');p.cancelRuntimeEditor();assert.equal(p.data.formData.journeyRules,rulesRaw);assert.equal(p.data.formData.themeRuntimeConfig,theme);
 }
 assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('explicit numeric edits change only selected known paths and preserve advanced rules attributes thoughts and exhaustTag',()=>{
 const rulesRaw={schemaVersion:1,stateEnabled:false,hp:{enabled:false,future:{x:1}},attributes:[{key:'counter.a',label:'A',future:1}],thoughts:null,recovery:{exhaustTag:'tag.old',future:{x:[2]}},tiers:{easy:{future:3}},future:{node:{secret:4}}};
 const p=checkPolicyPage(JSON.stringify(rulesRaw)),theme=p.data.formData.themeRuntimeConfig;checkNumber(p,'tiers.easy.dc','9');checkNumber(p,'recovery.exhaustRestHp','4');assert.equal(p.closeRuntimeEditor(),true);
 assert.deepEqual(JSON.parse(p.data.formData.journeyRules),{...rulesRaw,tiers:{easy:{future:3,dc:9}},recovery:{...rulesRaw.recovery,exhaustRestHp:4}});assert.equal(p.data.formData.themeRuntimeConfig,theme);assert.equal(requests.length,0);
});
test('checkEnabled has explicit missing/false/true/current-null choices and never changes stateEnabled',()=>{
 for(const [pick,expected]of [[0,undefined],[1,false],[2,true],[3,null]]){const p=checkPolicyPage('{"schemaVersion":1,"checkEnabled":null,"stateEnabled":false}');p.onJourneyCheckEnabled({detail:{value:pick}});p.closeRuntimeEditor();const value=JSON.parse(p.data.formData.journeyRules);assert.equal(value.checkEnabled,expected);assert.equal(value.stateEnabled,false);}
 const p=checkPolicyPage('');assert.equal(p.onJourneyCheckEnabled({detail:{value:3}}),false);p.onJourneyCheckEnabled({detail:{value:2}});p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.journeyRules),{checkEnabled:true,schemaVersion:1});
});
test('numeric input requires complete integers and server field bounds, with no coupled auto-correction',()=>{
 const bads=[['tiers.easy.dc','4'],['tiers.hard.dc','26'],['tiers.easy.damage','-1'],['tiers.hard.damage','6'],['modCap','11'],['fumbleExtraDamage','4'],['recovery.stationEndIfHurt','11']];
 for(const [field,value]of bads){const p=checkPolicyPage('{}');checkNumber(p,field,value);assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.journeyRules,'{}');assert.ok(p.data.runtimeJourneyChecks.error);}
 for(const value of ['1.5','1e1','+1',' ','NaN','2147483648']){const p=checkPolicyPage('{}');checkNumber(p,'modCap',value);assert.equal(p.closeRuntimeEditor(),false);}
 const p=checkPolicyPage('{}');checkNumber(p,'tiers.easy.dc','13');assert.equal(p.closeRuntimeEditor(),false);assert.match(p.data.runtimeJourneyChecks.error,/简单 < 普通 < 困难/);
});
test('all difficulty and penalty bounds are inclusive and do not impose damage ordering',()=>{
 const p=checkPolicyPage('{}');for(const [key,value]of [['tiers.easy.dc','5'],['tiers.medium.dc','6'],['tiers.hard.dc','25'],['tiers.easy.damage','5'],['tiers.medium.damage','0'],['tiers.hard.damage','0'],['modCap','0'],['fumbleExtraDamage','3']])checkNumber(p,key,value);
 assert.equal(p.closeRuntimeEditor(),true);const saved=JSON.parse(p.data.formData.journeyRules);assert.equal(saved.checkEnabled,undefined);assert.equal(saved.tiers.easy.damage,5);assert.equal(saved.tiers.hard.dc,25);
});
test('invalid configured recovery can be repaired while defaults over reduced maxima stay invalid until explicitly corrected',()=>{
 const bad={...validSmallRules(),recovery:{stationEndIfHurt:2,stageRestHpFloor:8,stageRestLuckFloor:0,exhaustRestHp:3}};const p=checkPolicyPage(JSON.stringify(bad));assert.match(p.data.runtimeJourneyChecks.error,/stageRestHpFloor/);checkNumber(p,'recovery.stageRestHpFloor','3');assert.equal(p.closeRuntimeEditor(),true);
 const q=checkPolicyPage('{"schemaVersion":1,"hp":{"max":3,"init":3},"luck":{"max":0,"init":0}}');assert.ok(q.data.runtimeJourneyChecks.error);checkNumber(q,'recovery.stageRestHpFloor','3');checkNumber(q,'recovery.stageRestLuckFloor','0');checkNumber(q,'recovery.exhaustRestHp','3');assert.equal(q.closeRuntimeEditor(),true);
});
test('explicit restore-default removes only that field, retains unknown siblings and does not bypass max coupling',()=>{
 const source={schemaVersion:1,recovery:{exhaustRestHp:null,future:2},tiers:{easy:{dc:9,future:1}},modCap:7};const p=checkPolicyPage(JSON.stringify(source));p.clearJourneyCheckNumber(event('tiers.easy.dc'));p.clearJourneyCheckNumber(event('recovery.exhaustRestHp'));p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.journeyRules),{...source,recovery:{future:2},tiers:{easy:{future:1}}});
 const q=checkPolicyPage(JSON.stringify(validSmallRules()));q.clearJourneyCheckNumber(event('recovery.stageRestHpFloor'));assert.equal(q.closeRuntimeEditor(),false);assert.match(q.data.runtimeJourneyChecks.error,/stageRestHpFloor/);
});
test('edit then revert keeps raw bytes and preserves null rather than materializing a numeric default',()=>{
 const source=' { "schemaVersion":1,"modCap":7,"tiers":null,"checkEnabled":null } ';const p=checkPolicyPage(source);checkNumber(p,'modCap','8');checkNumber(p,'modCap','7');p.onJourneyCheckEnabled({detail:{value:2}});p.onJourneyCheckEnabled({detail:{value:3}});p.closeRuntimeEditor();assert.equal(p.data.formData.journeyRules,source);
});
test('later HP/LUCK reductions reject stale open edits and are rechecked by original runtime payload assembly',()=>{
 for(const change of [r=>r.hp.max=2,r=>r.luck.max=0]){const root={schemaVersion:1,hp:{max:10,init:1},luck:{max:3,init:0},recovery:{stationEndIfHurt:2,stageRestHpFloor:6,stageRestLuckFloor:1,exhaustRestHp:5}},p=checkPolicyPage(JSON.stringify(root));checkNumber(p,'modCap','4');change(root);p.data.formData.journeyRules=JSON.stringify(root);const before=p.data.formData.journeyRules;assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.journeyRules,before);assert.throws(()=>p._runtimePayload(),/recovery\./);}
});
test('policy callbacks respect owner topic revision catalog source permission and disposal guards',()=>{
 for(const change of [p=>member=8,p=>p.data.editingTopicId=13,p=>p.data.baseRevision=4,p=>p.data.formData.themeRuntimeConfig=raw({capacity:8}),p=>p.data.editScope='WHITELIST',p=>p._disposeRuntimeEditor()]){member=7;const p=checkPolicyPage('{}');change(p);assert.equal(p.onJourneyCheckEnabled({detail:{value:2}}),false);assert.equal(checkNumber(p,'modCap','1'),false);assert.equal(p.clearJourneyCheckNumber(event('modCap')),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.journeyRules,'{}');}
});
test('policy structures and nullable-default types match the server; malformed input never becomes a clean default',()=>{
 for(const raw of ['{bad','null','[]','0','\u00a0',JSON.stringify({tiers:[]}),JSON.stringify({checkEnabled:'true'}),JSON.stringify({hp:{max:'10'}})])assert.throws(()=>checkSettings.project(raw));
 for(const raw of [JSON.stringify({modCap:false}),JSON.stringify({tiers:{easy:{dc:'10'}}}),JSON.stringify({recovery:{stageRestHpFloor:1.5}})]){assert.ok(checkSettings.project(raw).error);assert.throws(()=>checkSettings.normalize(raw));}
 assert.equal(checkSettings.normalize('\u3000').checkEnabled,false);assert.equal(checkSettings.normalize('{"hp":null,"luck":null,"tiers":null,"recovery":null,"checkEnabled":null}').hpMax,10);
});
test('unknown and presentation-tampered policy fields cannot bypass limits or write unrelated rules',()=>{
 const p=checkPolicyPage('{}');assert.equal(checkNumber(p,'stateEnabled','true'),false);assert.equal(checkNumber(p,'modCap',4),false);assert.equal(checkNumber(p,'modCap','1'.repeat(17)),false);assert.equal(p.onJourneyCheckEnabled({detail:{value:999}}),false);
 const d=checkSettings.project('{}');d.fields[0].max=999;d.fields[0].mode='value';d.fields[0].value='100';assert.throws(()=>checkSettings.apply('{}',d));d.fields[0].path='future';assert.throws(()=>checkSettings.apply('{}',d));
 const missing=checkSettings.project('{}');missing.fields[0].value='5';assert.throws(()=>checkSettings.apply('{}',missing));
});
test('policy serialization retains explicit unsupported versions for server rejection and bounds UTF-8 output',()=>{
 const d=checkSettings.project('{"schemaVersion":2}');d.fields.find(x=>x.path==='modCap').mode='value';d.fields.find(x=>x.path==='modCap').value='5';assert.equal(JSON.parse(checkSettings.apply('{"schemaVersion":2}',d)).schemaVersion,2);
 const large=JSON.stringify({schemaVersion:1,future:'中'.repeat(5500)}),draft=checkSettings.project(large);draft.enabledMode='true';assert.throws(()=>checkSettings.apply(large,draft),/16 KiB/);
});
test('policy markup describes staged configuration, explicit enabling, server defaults and max coupling',()=>{
 const root=path.join(__dirname,'../../pages/publish/fabu'),detail=fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8'),text=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8');assert.match(detail,/bindtap="openJourneyCheckSettings"/);assert.match(text,/bind:change="onJourneyCheckEnabled"/);assert.match(text,/bindinput="onJourneyCheckNumber"/);assert.match(text,/bindtap="clearJourneyCheckNumber"/);assert.match(text,/修改数字不会自动开启检定/);assert.match(text,/原有 null/);assert.match(text,/调整上限后须重新核对/);
});
test('unsafe unknown integer tokens make policy editing read-only while no-op and cancel retain exact bytes',()=>{
 for(const token of ['9007199254740993','-9007199254740993','9007199254740992']){
  const source=' {"schemaVersion":1,"future":{"nested":[{"id":'+token+'}]}} ',p=checkPolicyPage(source);
  assert.equal(p.data.runtimeJourneyChecks.readOnly,true);assert.match(p.data.runtimeJourneyChecks.error,/不能无损保留/);
  assert.equal(checkNumber(p,'modCap','5'),false);assert.equal(p.onJourneyCheckEnabled({detail:{value:2}}),false);assert.equal(p.clearJourneyCheckNumber(event('modCap')),false);
  assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.journeyRules,source);p.openJourneyCheckSettings();p.cancelRuntimeEditor();assert.equal(p.data.formData.journeyRules,source);
  const forged=checkSettings.project(source);forged.fields.find(x=>x.path==='modCap').mode='value';forged.fields.find(x=>x.path==='modCap').value='5';forged.readOnly=false;assert.throws(()=>checkSettings.apply(source,forged),/只读/);
 }
});
test('lossy fractional, overflowing, underflowing and noncanonical numeric tokens fail closed before edited serialization',()=>{
 for(const token of ['9007199254740991.1','0.123456789012345678901234','1e400','1e-4000','1.0','1E-7','-0']){
  const source='{"schemaVersion":1,"extension":[{"v":'+token+'}]}',d=checkSettings.project(source);assert.equal(d.readOnly,true,token);assert.equal(checkSettings.apply(source,d),source);
  d.enabledMode='true';assert.throws(()=>checkSettings.apply(source,d),/只读/,token);
 }
});
test('canonical safe numeric extensions round-trip unchanged and digit text including escaped strings is never scanned as a number',()=>{
 const root={schemaVersion:1,future:{max:Number.MAX_SAFE_INTEGER,decimal:0.125,tiny:1e-7,text:'quote "9007199254740993" and slash \\1.000',list:['1e400','9007199254740991.1']}};
 const source=JSON.stringify(root),d=checkSettings.project(source);assert.equal(d.readOnly,false);d.enabledMode='true';const saved=JSON.parse(checkSettings.apply(source,d));assert.deepEqual(saved.future,root.future);assert.equal(saved.checkEnabled,true);
 const objectDraft=checkSettings.project(root);assert.equal(objectDraft.readOnly,false);objectDraft.enabledMode='true';assert.deepEqual(JSON.parse(checkSettings.apply(root,objectDraft)).future,root.future);
 for(const value of [Infinity,-0,Number.MAX_SAFE_INTEGER+1]){const object={schemaVersion:1,future:{v:value}};const invalid=checkSettings.project(object);assert.equal(invalid.readOnly,true);assert.equal(checkSettings.apply(object,invalid),object);invalid.enabledMode='true';assert.throws(()=>checkSettings.apply(object,invalid),/只读/);}
});
test('read-only numeric preservation is wired to visible policy inputs and hides destructive default controls',()=>{
 const markup=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8');assert.match(markup,/disabled="\{\{runtimeJourneyChecks\.readOnly\}\}"/);assert.match(markup,/wx:if="\{\{!runtimeJourneyChecks\.readOnly\}\}"[^>]*bindtap="clearJourneyCheckNumber"/);assert.match(markup,/（只读）/);
});

const choiceHelper=require('../../pages/publish/fabu/journey-choice-grants');
const branchConfig=()=>({schemaVersion:1,branch:{enabled:true,startStepId:'s1',steps:[{id:'s1',title:'门前',options:[{id:'left',label:'走左边',nextStepId:'end'},{id:'right',label:'走右边',nextStepId:'end'}]},{id:'end',terminal:true,outcomeCode:'done',options:[]}]}});
const choiceGrant=(id='choice_a',extra={})=>({id,source:'CHOICE',nodeId:7,step:'s1',option:'left',itemId:'a',quantity:1,...extra});
function choicePage(extra={},setup=()=>{}){const p=page();p.data.editingTopicId=12;p.data.editLoaded=true;p.data.formData.chapters=[{name:'一',nodes:[{id:7,name:'分支站点',templateId:3,templateInfo:{id:3,advancedConfigJson:JSON.stringify(branchConfig())}}]}];p.data.formData.themeRuntimeConfig=raw(extra);setup(p);p.openThemeChoiceGrants();return p;}
function composeChoice(p,{node=0,step=0,option=0,material=0,quantity='1'}={}){for(const [key,value]of Object.entries({nodeIndex:node,stepIndex:step,optionIndex:option,itemIndex:material,quantity}))p.onRuntimeChoiceGrantDraft(event(key,value));}
test('choice flow requires explicit saved branch node, step, option, material and quantity before staging',()=>{
 const p=choicePage(),original=p.data.formData.themeRuntimeConfig;assert.equal(p.data.runtimeEditor.kind,'choiceGrants');assert.equal(p.saveRuntimeChoiceGrant(),false);
 p.onRuntimeChoiceGrantDraft(event('nodeIndex',0));assert.equal(p.data.runtimeChoiceSteps.length,1);p.onRuntimeChoiceGrantDraft(event('stepIndex',0));assert.equal(p.data.runtimeChoiceOptions.length,2);assert.equal(p.saveRuntimeChoiceGrant(),false);
 composeChoice(p,{option:1,quantity:'2'});assert.equal(p.saveRuntimeChoiceGrant(),true);assert.equal(p.data.formData.themeRuntimeConfig,original);assert.equal(p.closeRuntimeEditor(),true);
 const grant=JSON.parse(p.data.formData.themeRuntimeConfig).grants[0];assert.match(grant.id,/^q[a-z0-9_]+$/);assert.deepEqual({...grant,id:'choice_a'},choiceGrant('choice_a',{option:'right',quantity:2}));assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('choice no-op cancel and create-remove roundtrip preserve exact raw omitted null and empty grants',()=>{
 for(const grants of [undefined,null,[]]){const p=choicePage({grants},p=>p.data.formData.themeRuntimeConfig=' '+p.data.formData.themeRuntimeConfig+' '),original=p.data.formData.themeRuntimeConfig;
  assert.equal(p.closeRuntimeEditor(),true);assert.equal(p.data.formData.themeRuntimeConfig,original);p.openThemeChoiceGrants();composeChoice(p);p.saveRuntimeChoiceGrant();p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
  p.openThemeChoiceGrants();composeChoice(p);p.saveRuntimeChoiceGrant();p.removeRuntimeChoiceGrant(broadcastTap(p._runtimeConfig().grants[0].id));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 }
});
test('choice edited serialization writes grants only without materializing other missing or null fields',()=>{
 const source={schemaVersion:1,capacity:6,items:[item()],chapters:null,events:null,future:{fraction:0.125,text:'9007199254740993'},scenes:[]};const p=choicePage({},p=>p.data.formData.themeRuntimeConfig=JSON.stringify(source));composeChoice(p);p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();const after=JSON.parse(p.data.formData.themeRuntimeConfig);delete after.grants;assert.deepEqual(after,source);
});
test('choice simple-row edit keeps stable identity and original field order; unchanged edit returns exact bytes',()=>{
 const row={quantity:1,itemId:'a',option:'left',step:'s1',nodeId:7,source:'CHOICE',id:'old'};const p=choicePage({grants:[row]},p=>p.data.formData.themeRuntimeConfig=' '+p.data.formData.themeRuntimeConfig+' '),original=p.data.formData.themeRuntimeConfig;
 p.editRuntimeChoiceGrant(broadcastTap('old'));p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,original);
 p.openThemeChoiceGrants();p.editRuntimeChoiceGrant(broadcastTap('old'));p.onRuntimeChoiceGrantDraft(event('optionIndex',1));p.onRuntimeChoiceGrantDraft(event('quantity','3'));p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();const changed=JSON.parse(p.data.formData.themeRuntimeConfig).grants[0];assert.deepEqual(Object.keys(changed),Object.keys(row));assert.deepEqual(changed,{...row,option:'right',quantity:3});
});
test('advanced conditional extra-field nonbranch and unknown choice rows stay verbatim read-only',()=>{
 const advanced=[choiceGrant('condition',{when:[{op:'ITEM_HELD',itemId:'a',quantity:1,future:true}]}),choiceGrant('extra',{future:{x:[1]}}),choiceGrant('unknown',{option:'missing'}),choiceGrant('qa',{step:'qa'}),choiceGrant('string',{nodeId:'7'}),choiceGrant('missing',{itemId:'gone'})],p=choicePage({grants:[...advanced,nodeGrant()]});
 assert.equal(p.data.runtimeChoiceGrants.protectedCount,1);assert.ok(p.data.runtimeChoiceGrants.rows.every(r=>!r.editable));for(const row of advanced){assert.equal(p.editRuntimeChoiceGrant(broadcastTap(row.id)),false);assert.equal(p.removeRuntimeChoiceGrant(broadcastTap(row.id)),false);}
 composeChoice(p);p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants.slice(0,-1),[...advanced,nodeGrant()]);
});
test('choice removal only affects staged simple row and rejects cancellation row edits and active editing',()=>{
 const p=choicePage({grants:[choiceGrant()]});confirm=false;p.removeRuntimeChoiceGrant(broadcastTap('choice_a'));assert.equal(p._runtimeConfig().grants.length,1);confirm='manual';p.removeRuntimeChoiceGrant(broadcastTap('choice_a'));const pending=modalCalls.at(-1);p.editRuntimeChoiceGrant(broadcastTap('choice_a'));pending.success({confirm:true});assert.equal(p._runtimeConfig().grants.length,1);
 p.clearRuntimeChoiceGrantDraft();p.removeRuntimeChoiceGrant(broadcastTap('choice_a'));const pending2=modalCalls.at(-1);p.editRuntimeChoiceGrant(broadcastTap('choice_a'));p.onRuntimeChoiceGrantDraft(event('quantity','2'));p.saveRuntimeChoiceGrant();pending2.success({confirm:true});assert.equal(p._runtimeConfig().grants[0].quantity,2);
 p.removeRuntimeChoiceGrant(broadcastTap('choice_a'));assert.match(modalCalls.at(-1).content,/个人库存不变/);modalCalls.at(-1).success({confirm:true});p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants,[]);
});
test('choice IDs do not recycle and late confirmation cannot cross close-reopen ABA',()=>{
 const p=choicePage();const now=Date.now;Date.now=()=>88;try{composeChoice(p);p.saveRuntimeChoiceGrant();const first=p._runtimeConfig().grants[0].id;p.removeRuntimeChoiceGrant(broadcastTap(first));const callback=modalCalls.at(-1);composeChoice(p);p.saveRuntimeChoiceGrant();const second=p._runtimeConfig().grants[0].id;assert.notEqual(first,second);callback.success({confirm:true});assert.equal(p._runtimeConfig().grants[0].id,second);p.closeRuntimeEditor();
  p.openThemeChoiceGrants();confirm='manual';p.removeRuntimeChoiceGrant(broadcastTap(second));const delayed=modalCalls.at(-1);p.cancelRuntimeEditor();p.openThemeChoiceGrants();delayed.success({confirm:true});assert.equal(p._runtimeConfig().grants[0].id,second);
 }finally{Date.now=now;}
});
test('choice source owner load draft revision catalog rules and permission changes retire every mutation and delayed removal',()=>{
 const changes=[p=>member=8,p=>p.data.editingTopicId=13,p=>p.data.draftUuid='new',p=>p.data.baseRevision=2,p=>p._editLoadToken=4,p=>p.data.editScope='WHITELIST',p=>p.data.formData.journeyRules='{}',p=>p.data.formData.themeRuntimeConfig=raw(),p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify({}),p=>p.data.formData.chapters[0].nodes[0].templateId=4,p=>p.data.formData.chapters[0].nodes[0].templateInfo.id=4,p=>p._disposeRuntimeEditor()];
 for(const change of changes){member=7;confirm='manual';const p=choicePage({grants:[choiceGrant()]});p.removeRuntimeChoiceGrant(broadcastTap('choice_a'));const pending=modalCalls.at(-1);change(p);const before=JSON.stringify(p.data.formData);pending.success({confirm:true});assert.equal(p.saveRuntimeChoiceGrant(),false);assert.equal(p.onRuntimeChoiceGrantDraft(event('quantity','2')),false);assert.equal(p.editRuntimeChoiceGrant(broadcastTap('choice_a')),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(JSON.stringify(p.data.formData),before);}
});
test('choice presentation mutation cannot forge a saved step or option and selection changes clear dependent choices',()=>{
 const p=choicePage();p.data.runtimeChoiceNodes[0].steps[0].options.push({id:'forged',label:'fake'});composeChoice(p);assert.equal(p.onRuntimeChoiceGrantDraft(event('optionIndex',2)),false);assert.equal(p.onRuntimeChoiceGrantDraft(event('stepIndex',99)),false);p.data.runtimeChoiceGrantDraft.optionIndex=2;assert.equal(p.saveRuntimeChoiceGrant(),false);
 const q=choicePage({},p=>p.data.formData.chapters[0].nodes.push({...p.data.formData.chapters[0].nodes[0],id:8}));composeChoice(q);q.onRuntimeChoiceGrantDraft(event('nodeIndex',1));assert.equal(q.data.runtimeChoiceGrantDraft.stepIndex,-1);assert.equal(q.data.runtimeChoiceGrantDraft.optionIndex,-1);assert.equal(q.data.runtimeChoiceOptions.length,0);
});
test('choice branch projection excludes disabled malformed duplicate padded IDs invalid references and unreachable steps',()=>{
 const changes=[b=>b.branch.enabled=false,b=>b.schemaVersion=2,b=>delete b.schemaVersion,b=>b.branch.steps.push({...b.branch.steps[0]}),b=>b.branch.steps[0].options.push({...b.branch.steps[0].options[0]}),b=>b.branch.steps[0].id=' s1 ',b=>b.branch.steps[0].options[0].id='left\n',b=>b.branch.steps[0].options[0].nextStepId='missing',b=>b.branch.startStepId='missing',b=>b.branch.steps[1].outcomeCode='',b=>b.branch.steps[0].options[0].label='\u0000',b=>b.branch.steps[0].options[0].label='😀'.repeat(41)];
 for(const change of changes){const b=branchConfig();change(b);const p=choicePage({},p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify(b));assert.equal(p.data.runtimeChoiceNodes.length,0);}
 const b=branchConfig();b.branch.steps.push({id:'unreachable',options:[{id:'x',label:'X',nextStepId:'end'}]});b.branch.steps[0].options[0].label='\u00a0';const p=choicePage({},p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify(b));assert.equal(p.data.runtimeChoiceNodes[0].steps.length,1);
});
test('choice saved node and current loaded template contract are required, with positive safe unique IDs',()=>{
 for(const setup of [p=>p.data.editingTopicId=null,p=>p.data.formData.chapters[0].nodes[0].id=0,p=>p.data.formData.chapters[0].nodes[0].id=Number.MAX_SAFE_INTEGER+1,p=>delete p.data.formData.chapters[0].nodes[0].templateInfo]){const p=choicePage({},setup);assert.equal(p.data.runtimeChoiceNodes.length,0);}
 const p=choicePage({},p=>p.data.formData.chapters[0].nodes.push({...p.data.formData.chapters[0].nodes[0]}));assert.equal(p.data.runtimeEditor.show,false);
 for(const setup of [n=>n.templateId=0,n=>delete n.templateId,n=>delete n.templateInfo.id,n=>n.templateInfo.id=4,n=>n.templateInfo.id=Number.MAX_SAFE_INTEGER+1,n=>{n.templateId=0;n.advancedConfigJson=n.templateInfo.advancedConfigJson;delete n.templateInfo;}]){const p=choicePage({},p=>setup(p.data.formData.chapters[0].nodes[0]));assert.equal(p.data.runtimeChoiceNodes.length,0);assert.equal(p.saveRuntimeChoiceGrant(),false);}
 const q=choicePage({},p=>{const n=p.data.formData.chapters[0].nodes[0];n.templateId='3';n.id=Number.MAX_SAFE_INTEGER;});assert.equal(q.data.runtimeChoiceNodes[0].id,Number.MAX_SAFE_INTEGER);
});
test('choice root scene inheritance and independent node overrides remain explicit and untouched',()=>{
 const scene={id:'override',name:'独立',nodeConfigs:{'7':branchConfig()}};const p=choicePage({scenes:[scene],currentScene:'override',grants:[choiceGrant()]});assert.equal(p.data.runtimeChoiceNodes.length,0);assert.match(p.data.runtimeChoiceBlocked[0].reason,/独立节点玩法/);assert.equal(p.data.runtimeChoiceGrants.rows[0].editable,false);
 for(const nodeConfigs of [null,[],0,'malformed'])assert.equal(choicePage({scenes:[{...scene,nodeConfigs}],currentScene:'override'}).data.runtimeEditor.show,false);
 for(const grants of [null,[],[choiceGrant('scene')]]){const scenes=[{id:'root',name:'沿用'},{...scene,grants}],q=choicePage({scenes,currentScene:'root'});assert.equal(q.data.runtimeChoiceNodes.length,1);composeChoice(q);q.saveRuntimeChoiceGrant();q.closeRuntimeEditor();assert.deepEqual(JSON.parse(q.data.formData.themeRuntimeConfig).scenes,scenes);}
});
test('choice source bounds and complete-only 300 grant management fail closed without dropping imported content',()=>{
 const p=choicePage({grants:Array.from({length:300},(_,i)=>({id:'g'+i,source:'ENDING',itemId:'a',quantity:1}))});composeChoice(p);assert.equal(p.saveRuntimeChoiceGrant(),false);assert.equal(p._runtimeConfig().grants.length,300);
 for(const grants of [[choiceGrant(),choiceGrant()],[choiceGrant('bad\n')]])assert.equal(choicePage({grants}).data.runtimeEditor.show,false);
 assert.equal(choicePage({items:[item(),item()]}).data.runtimeEditor.show,false);
 const oversized=branchConfig();oversized.future='中'.repeat(22000);const blocked=choicePage({},p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify(oversized));assert.equal(blocked.data.runtimeChoiceNodes.length,0);assert.match(blocked.data.runtimeChoiceBlocked[0].reason,/64 KiB/);
 const q=choicePage({},p=>p.data.formData.chapters[0].nodes=Array.from({length:501},(_,i)=>({id:i+1})));assert.equal(q.data.runtimeEditor.show,false);
 const huge=choicePage({},p=>p.data.formData.chapters[0].name='x'.repeat(1000001));assert.equal(huge.data.runtimeEditor.show,false);
});
test('choice branch exact 50 steps and 12 options bound is accepted; larger or 2000-plus projected selections are excluded',()=>{
 const b=branchConfig();b.branch.steps[0].options=Array.from({length:12},(_,i)=>({id:'o'+i,label:'选'+i,nextStepId:'end'}));for(let i=2;i<50;i++)b.branch.steps.push({id:'s'+i,terminal:true,outcomeCode:'done',options:[]});
 const p=choicePage({},p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify(b));assert.equal(p.data.runtimeChoiceNodes[0].steps[0].options.length,12);b.branch.steps.push({id:'s50',terminal:true,outcomeCode:'done',options:[]});assert.equal(choicePage({},p=>p.data.formData.chapters[0].nodes[0].templateInfo.advancedConfigJson=JSON.stringify(b)).data.runtimeChoiceNodes.length,0);
 const many=choicePage({},p=>{const n=p.data.formData.chapters[0].nodes[0],config=branchConfig();config.branch.steps[0].options=Array.from({length:12},(_,i)=>({id:'o'+i,label:'选'+i,nextStepId:'end'}));n.templateInfo.advancedConfigJson=JSON.stringify(config);p.data.formData.chapters[0].nodes=Array.from({length:200},(_,i)=>({...n,id:i+1}));});assert.equal(many.data.runtimeChoiceNodes.length,166);assert.equal(many.data.runtimeChoiceBlocked.length,34);
});
test('choice numeric safety rejects unknown nested unsafe or noncanonical tokens and does not scan quoted strings',()=>{
 for(const token of ['9007199254740993','0.123456789012345678901234','1e400','1.0','-0']){const raw='{"schemaVersion":1,"items":[],"future":{"v":'+token+'}}',p=choicePage({},p=>p.data.formData.themeRuntimeConfig=raw);assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,raw);assert.throws(()=>choiceHelper.serialize(raw,[]),/无损/);}
 const p=choicePage({future:{text:'"9007199254740993" \\1.0',number:Number.MAX_SAFE_INTEGER}});composeChoice(p);p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).future.number,Number.MAX_SAFE_INTEGER);
});
test('choice stat material requires existing enabled attributes and final quantity is strictly explicit 1 to 99',()=>{
 const p=choicePage({items:[{...item(),stat:'HP',delta:1}]});composeChoice(p);assert.equal(p.saveRuntimeChoiceGrant(),false);assert.equal(p.data.formData.journeyRules,'');
 for(const quantity of ['','0','100','1.5','1e1','+1',' 1']){const q=choicePage();composeChoice(q,{quantity});assert.equal(q.saveRuntimeChoiceGrant(),false);}
 const q=choicePage({items:[{...item(),kind:'MEDAL'}]});composeChoice(q,{quantity:'99'});assert.equal(q.saveRuntimeChoiceGrant(),true);
});
test('choice markup exposes full staged management and clear boundaries without a simulated send',()=>{
 const root=path.join(__dirname,'../../pages/publish/fabu'),markup=fs.readFileSync(path.join(root,'runtime-editor.wxml'),'utf8');assert.match(fs.readFileSync(path.join(root,'topic-detail-sheet.wxml'),'utf8'),/bindtap="openThemeChoiceGrants"/);for(const name of ['saveRuntimeChoiceGrant','editRuntimeChoiceGrant','removeRuntimeChoiceGrant','clearRuntimeChoiceGrantDraft'])assert.ok(markup.includes('bindtap="'+name+'"'));assert.match(markup,/CHOICE/);assert.match(markup,/本局冻结的分支步骤/);assert.match(markup,/当前所选范围的简单分支选项关联/);
});

const scopeHelper=require('../../pages/publish/fabu/journey-grant-scopes');
const sceneScope=(p,index)=>p.onRuntimeGrantScope({detail:{value:index}});
const sceneRow=(grants)=>({id:'scene_a',name:'场景甲',...(grants===undefined?{}:{grants})});
function scopedNodePage(grants,sceneGrants,setup=()=>{}){return nodeGrantPage({grants,scenes:[sceneRow(sceneGrants),{id:'scene_b',name:'场景乙',grants:[nodeGrant('other',{quantity:3})],events:[],future:{keep:1}}],currentScene:'scene_a'},{setup});}
test('scene scope open switch and no-op preserve raw absence null and empty distinctions without materialization',()=>{
 for(const grants of [undefined,null,[]]){const p=scopedNodePage([nodeGrant()],grants),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);assert.equal(p.data.runtimeGrantScope.inherited,grants===undefined);assert.equal(p.data.runtimeGrantScope.canRestore,grants!==undefined);sceneScope(p,2);sceneScope(p,0);p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);}
});
test('inherited scene lists are read-only until explicit confirmed copy of every current root row',()=>{
 const root=[nodeGrant(),choiceGrant('advanced',{when:[{op:'GTE',var:'sys.hp',value:1}],future:{keep:[1]}})],p=scopedNodePage(root,undefined),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);composeNodeGrant(p);assert.equal(p.saveRuntimeNodeGrant(),false);assert.equal(p.editRuntimeNodeGrant(broadcastTap('node_a')),false);assert.equal(p.removeRuntimeNodeGrant(broadcastTap('node_a')),false);assert.equal(p._runtimeConfig().scenes[0].grants,undefined);
 confirm=false;p.createIndependentGrantScope();assert.equal(p._runtimeConfig().scenes[0].grants,undefined);confirm=true;p.createIndependentGrantScope();assert.match(modalCalls.at(-1).content,/完整复制/);assert.deepEqual(p._runtimeConfig().scenes[0].grants,root);assert.equal(p.data.runtimeGrantScope.inherited,false);assert.equal(p.data.formData.themeRuntimeConfig,raw);p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants,root);
});
test('scene NODE edit creates only scoped grants and preserves root other scenes and advanced payloads',()=>{
 const root=[nodeGrant()],own=[nodeGrant('same',{quantity:2}),choiceGrant('advanced',{when:[]})],p=scopedNodePage(root,own),before=JSON.parse(p.data.formData.themeRuntimeConfig);sceneScope(p,1);p.editRuntimeNodeGrant(broadcastTap('same'));p.onRuntimeNodeGrantDraft(event('quantity','4'));p.saveRuntimeNodeGrant();p.closeRuntimeEditor();const after=JSON.parse(p.data.formData.themeRuntimeConfig);assert.deepEqual(after,{...before,scenes:[{...before.scenes[0],grants:[{...own[0],quantity:4},own[1]]},before.scenes[1]]});
});
test('scope switch discards unfinished composition and active edit while retaining explicitly staged changes per scope',()=>{
 const p=scopedNodePage([nodeGrant()],[]);composeNodeGrant(p,{quantity:'2'});sceneScope(p,1);assert.deepEqual(p.data.runtimeNodeGrantDraft,emptyNodeGrantDraftForTest());assert.equal(p._runtimeConfig().grants.length,1);
 composeNodeGrant(p,{quantity:'3'});p.saveRuntimeNodeGrant();sceneScope(p,0);p.editRuntimeNodeGrant(broadcastTap('node_a'));p.onRuntimeNodeGrantDraft(event('quantity','8'));sceneScope(p,1);assert.equal(p._runtimeConfig().grants[0].quantity,1);assert.equal(p._runtimeConfig().scenes[0].grants[0].quantity,3);p.cancelRuntimeEditor();assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).scenes[0].grants.length,0);
});
function emptyNodeGrantDraftForTest(){return {editingId:'',nodeIndex:-1,itemIndex:-1,quantity:''};}
test('scope switching retires pending removal even when two scenes use the same grant ID',()=>{
 confirm='manual';const p=scopedNodePage([nodeGrant('same')],[nodeGrant('same',{quantity:2})]);p.removeRuntimeNodeGrant(broadcastTap('same'));const rootDelete=modalCalls.at(-1);sceneScope(p,1);rootDelete.success({confirm:true});assert.equal(p._runtimeConfig().grants.length,1);assert.equal(p._runtimeConfig().scenes[0].grants.length,1);
 p.removeRuntimeNodeGrant(broadcastTap('same'));const sceneDelete=modalCalls.at(-1);sceneScope(p,0);sceneScope(p,1);sceneDelete.success({confirm:true});assert.equal(p._runtimeConfig().scenes[0].grants.length,1);
});
test('scope copy and restore confirmations cannot cross scope owner source close or row changes',()=>{
 for(const change of [p=>sceneScope(p,2),p=>member=8,p=>p.data.baseRevision=4,p=>p.data.formData.chapters[0].nodes.reverse(),p=>p.data.formData.themeRuntimeConfig=raw(),p=>p.cancelRuntimeEditor()]){member=7;confirm='manual';const p=scopedNodePage([nodeGrant()],undefined);sceneScope(p,1);p.createIndependentGrantScope();const pending=modalCalls.at(-1);change(p);const before=JSON.stringify(p.data.formData);pending.success({confirm:true});assert.equal(JSON.stringify(p.data.formData),before);if(p.data.runtimeEditor.show)assert.equal(p._runtimeConfig().scenes[0].grants,undefined);}
});
test('only empty or null independent scope can restore inheritance with explicit confirmation and cancel keeps bytes',()=>{
 for(const value of [null,[]]){const p=scopedNodePage([nodeGrant()],value),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);confirm=false;p.restoreInheritedGrantScope();assert.deepEqual(p._runtimeConfig().scenes[0].grants,value);confirm=true;p.restoreInheritedGrantScope();assert.equal(p.data.runtimeGrantScope.inherited,true);assert.equal(p._runtimeConfig().scenes[0].grants,undefined);p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);p.openThemeNodeGrants();sceneScope(p,1);p.restoreInheritedGrantScope();p.closeRuntimeEditor();assert.ok(!Object.hasOwn(JSON.parse(p.data.formData.themeRuntimeConfig).scenes[0],'grants'));}
 const protectedPage=scopedNodePage([], [nodeGrant('advanced',{future:{x:1}})]);sceneScope(protectedPage,1);assert.equal(protectedPage.restoreInheritedGrantScope(),false);assert.equal(protectedPage.removeRuntimeNodeGrant(broadcastTap('advanced')),false);
});
test('scene copy restore and scoped add-remove no-op retain original null and omitted bytes',()=>{
 for(const root of [undefined,null,[]]){const p=scopedNodePage(root,undefined),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);p.createIndependentGrantScope();p.restoreInheritedGrantScope();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);}
 for(const own of [null,[]]){const p=scopedNodePage([],own),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);composeNodeGrant(p);p.saveRuntimeNodeGrant();const id=p._runtimeConfig().scenes[0].grants[0].id;p.removeRuntimeNodeGrant(broadcastTap(id));p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);}
});
test('scope serialization does not materialize unrelated root defaults or replace any scene fields',()=>{
 const raw=' {"schemaVersion":1,"items":[],"future":{"a":1},"scenes":[{"id":"s","name":"S","events":null,"nodeConfigs":{},"future":{"x":2}}],"currentScene":"s"} ',initial=editor.config(raw);const copied=scopeHelper.copyRoot(initial,'s'),output=JSON.parse(scopeHelper.serialize(raw,initial,copied));assert.deepEqual(output,{schemaVersion:1,items:[],future:{a:1},scenes:[{id:'s',name:'S',events:null,nodeConfigs:{},future:{x:2},grants:[]}],currentScene:'s'});assert.equal(scopeHelper.serialize(raw,initial,scopeHelper.restore(copied,'s')),raw);
});
test('scope selection uses private scene identities and rejects duplicates missing or reordered references',()=>{
 const p=scopedNodePage([],[]);p.data.runtimeGrantScope.options[1].id='scene_b';sceneScope(p,1);assert.equal(p._runtimeGrantScopeId,'scene_a');assert.equal(sceneScope(p,99),false);
 for(const scenes of [[sceneRow([]),sceneRow([])],[{id:'bad\n',name:'坏'}],Array.from({length:51},(_,i)=>({id:'s'+i,name:'S'}))])assert.equal(nodeGrantPage({scenes,currentScene:scenes[0].id}).data.runtimeEditor.show,false);
 const initial=editor.config(raw({scenes:[sceneRow([]),{id:'scene_b',name:'乙'}],currentScene:'scene_a'})),next=JSON.parse(JSON.stringify(initial));next.scenes.reverse();assert.throws(()=>scopeHelper.serialize(raw(initial),initial,next),/场景目录/);
});
test('scene CHOICE uses actual scene node override and can select an override on a node without a template',()=>{
 const override=branchConfig();override.branch.startStepId='scene_step';override.branch.steps[0].id='scene_step';override.branch.steps[0].options[0].id='scene_option';
 const scenes=[{id:'s',name:'场景',grants:[],nodeConfigs:{'7':override}}],p=choicePage({scenes,currentScene:'s'},p=>{delete p.data.formData.chapters[0].nodes[0].templateInfo;p.data.formData.chapters[0].nodes[0].templateId=0;});assert.equal(p.data.runtimeChoiceNodes.length,0);sceneScope(p,1);assert.equal(p.data.runtimeChoiceNodes[0].steps[0].id,'scene_step');composeChoice(p);p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();const config=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal(config.grants.length,0);assert.equal(config.scenes[0].grants[0].step,'scene_step');assert.equal(config.scenes[0].grants[0].option,'scene_option');assert.deepEqual(config.scenes[0].nodeConfigs,scenes[0].nodeConfigs);
});
test('scene CHOICE falls back only to matching saved template and does not leak override options across scopes',()=>{
 const override=branchConfig();override.branch.steps[0].options[0].id='other';const p=choicePage({scenes:[{id:'s',name:'场景',grants:[],nodeConfigs:{'7':override}},{id:'t',name:'场景乙',grants:[]}],currentScene:'s'});sceneScope(p,1);composeChoice(p);assert.equal(p.data.runtimeChoiceOptions[0].id,'other');sceneScope(p,2);assert.deepEqual(p.data.runtimeChoiceGrantDraft,choiceHelper.emptyDraft());composeChoice(p);assert.equal(p.data.runtimeChoiceOptions[0].id,'left');p.saveRuntimeChoiceGrant();sceneScope(p,1);assert.equal(p._runtimeConfig().scenes[0].grants.length,0);assert.equal(p._runtimeConfig().scenes[1].grants[0].option,'left');
});
test('scene CHOICE inherited copy is explicit and shared IDs remain scope isolated after edits and deletes',()=>{
 const row=choiceGrant('shared'),p=choicePage({grants:[row],scenes:[sceneRow(undefined),{id:'scene_b',name:'乙',grants:[{...row,quantity:3}]}],currentScene:'scene_a'});sceneScope(p,1);assert.equal(p.editRuntimeChoiceGrant(broadcastTap('shared')),false);p.createIndependentGrantScope();p.editRuntimeChoiceGrant(broadcastTap('shared'));p.onRuntimeChoiceGrantDraft(event('quantity','2'));p.saveRuntimeChoiceGrant();confirm='manual';p.removeRuntimeChoiceGrant(broadcastTap('shared'));const pending=modalCalls.at(-1);sceneScope(p,2);pending.success({confirm:true});p.closeRuntimeEditor();const c=JSON.parse(p.data.formData.themeRuntimeConfig);assert.equal(c.grants[0].quantity,1);assert.equal(c.scenes[0].grants[0].quantity,2);assert.equal(c.scenes[1].grants[0].quantity,3);
});
test('scoped creation does not reuse a grant ID already present in another scope or after deleted staged scope rows',()=>{
 const now=Date.now;Date.now=()=>99;try{const existing='n'+(99).toString(36)+'_1',p=scopedNodePage([], [nodeGrant(existing)]);composeNodeGrant(p);p.saveRuntimeNodeGrant();assert.notEqual(p._runtimeConfig().grants[0].id,existing);const first=p._runtimeConfig().grants[0].id;p.removeRuntimeNodeGrant(broadcastTap(first));sceneScope(p,1);composeNodeGrant(p);p.saveRuntimeNodeGrant();assert.ok(p._runtimeConfig().scenes[0].grants.every(g=>g.id!==first));}finally{Date.now=now;}
});
test('scoped raw-number safety applies to NODE and CHOICE and malformed override cannot borrow another source',()=>{
 const bad='{"schemaVersion":1,"items":[],"future":{"v":9007199254740993}}';const p=nodeGrantPage({}, {setup:p=>p.data.formData.themeRuntimeConfig=bad});assert.equal(p.data.runtimeEditor.show,false);assert.equal(p.data.formData.themeRuntimeConfig,bad);
 const q=choicePage({scenes:[{id:'s',name:'场景',grants:[],nodeConfigs:{'7':{schemaVersion:1}}}],currentScene:'s'});sceneScope(q,1);assert.equal(q.data.runtimeChoiceNodes.length,0);
});
test('scope markup labels inherited independent null empty and explicit confirmation with no automatic live action',()=>{
 const text=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8');for(const name of ['createIndependentGrantScope','restoreInheritedGrantScope'])assert.ok(text.includes('bindtap="'+name+'"'));assert.match(text,/bind:change="onRuntimeGrantScope"/);assert.match(text,/切换范围会清空尚未暂存/);assert.match(text,/高级或未知条目保持只读/);assert.match(text,/runtimeGrantScope.editable && item.editable/);assert.equal(requests.length,0);
});

test('scene override values require actual objects while bound template encoded JSON stays valid',()=>{
 for(const value of [JSON.stringify(branchConfig()),null,[],0,true]){const p=choicePage({scenes:[{id:'s',name:'场景',grants:[],nodeConfigs:{'7':value}}],currentScene:'s'}),raw=p.data.formData.themeRuntimeConfig;sceneScope(p,1);assert.equal(p.data.runtimeChoiceNodes.length,0);assert.match(p.data.runtimeChoiceBlocked[0].reason,/必须为 JSON 对象/);assert.equal(p.saveRuntimeChoiceGrant(),false);p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);}
 const valid=choicePage({scenes:[{id:'s',name:'场景',grants:[],nodeConfigs:{'7':branchConfig()}}],currentScene:'s'});sceneScope(valid,1);assert.equal(valid.data.runtimeChoiceNodes.length,1);assert.equal(choicePage().data.runtimeChoiceNodes.length,1);
});

const conditionHelper=require('../../pages/publish/fabu/journey-grant-conditions');
const conditionRules=()=>JSON.stringify({schemaVersion:1,stateEnabled:true,attributes:[{key:'counter.score',label:'得分',initial:0},{key:'tag.ready',label:'标记',initial:0,min:0,max:1}]});
function conditionPage(extra={},setup=()=>{}){return nodeGrantPage(extra,{setup:p=>{p.data.formData.journeyRules=conditionRules();setup(p);}});}
function composeCondition(p,{source='counter.score',op='GTE',itemIndex=0,nodeIndex=1,value='3'}={}){p.onRuntimeGrantConditionDraft(event('sourceIndex',p.data.runtimeGrantConditionSources.findIndex(row=>row.key===source)));const kind=p.data.runtimeGrantConditionKind;if(kind==='item')p.onRuntimeGrantConditionDraft(event('itemIndex',itemIndex));else if(kind==='node')p.onRuntimeGrantConditionDraft(event('nodeIndex',nodeIndex));else p.onRuntimeGrantConditionDraft(event('comparisonIndex',p.data.runtimeGrantConditionComparisons.findIndex(row=>row.op===op)));if(kind!=='node')p.onRuntimeGrantConditionDraft(event('value',value));return p.saveRuntimeGrantCondition();}
test('grant prerequisite composer stages explicit AND item node and declared-state conditions without any live action',()=>{
 const p=conditionPage(),raw=p.data.formData.themeRuntimeConfig;composeNodeGrant(p);assert.equal(composeCondition(p,{source:'ITEM_HELD',value:'2'}),true);assert.equal(composeCondition(p,{source:'NODE_COMPLETED'}),true);assert.equal(composeCondition(p),true);assert.equal(p.data.formData.themeRuntimeConfig,raw);assert.equal(p.saveRuntimeNodeGrant(),true);assert.equal(p.closeRuntimeEditor(),true);
 assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0].when,[{op:'ITEM_HELD',itemId:'a',quantity:2},{op:'NODE_COMPLETED',nodeId:8},{op:'GTE',var:'counter.score',value:3}]);assert.equal(requests.length,0);assert.equal(uploads.length,0);
});
test('a grant without explicit prerequisites keeps when omitted and cancelled condition editing does not alter the grant',()=>{
 const p=conditionPage();composeNodeGrant(p);p.onRuntimeGrantConditionDraft(event('sourceIndex',0));assert.equal(p.saveRuntimeNodeGrant(),false);p.clearRuntimeGrantConditionDraft();p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.ok(!Object.hasOwn(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0],'when'));
 p.openThemeNodeGrants();const raw=p.data.formData.themeRuntimeConfig;composeCondition(p);assert.equal(p.closeRuntimeEditor(),false);p.cancelRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);
});
test('supported conditional NODE and CHOICE rows support full edit and removal with stable IDs',()=>{
 const when=[{op:'ITEM_ACQUIRED',itemId:'a',quantity:1}],p=conditionPage({grants:[nodeGrant('conditional',{when})]});assert.equal(p.data.runtimeNodeGrants.rows[0].editable,true);p.editRuntimeNodeGrant(broadcastTap('conditional'));p.editRuntimeGrantCondition(broadcastTap(p.data.runtimeGrantWhenRows[0].id));p.onRuntimeGrantConditionDraft(event('value','2'));p.saveRuntimeGrantCondition();p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0],nodeGrant('conditional',{when:[{op:'ITEM_ACQUIRED',itemId:'a',quantity:2}]}));
 const q=choicePage({grants:[choiceGrant('conditional',{when})]});assert.equal(q.data.runtimeChoiceGrants.rows[0].editable,true);q.editRuntimeChoiceGrant(broadcastTap('conditional'));q.removeRuntimeGrantCondition(broadcastTap(q.data.runtimeGrantWhenRows[0].id));q.saveRuntimeChoiceGrant();q.removeRuntimeChoiceGrant(broadcastTap('conditional'));q.closeRuntimeEditor();assert.deepEqual(JSON.parse(q.data.formData.themeRuntimeConfig).grants,[]);
});
test('untouched absent null and empty when stay exact through unchanged row edits and unrelated quantity edits',()=>{
 for(const when of [undefined,null,[]]){const p=conditionPage({grants:[nodeGrant('g',{...(when===undefined?{}:{when})})]},p=>p.data.formData.themeRuntimeConfig=' '+p.data.formData.themeRuntimeConfig+' '),raw=p.data.formData.themeRuntimeConfig;p.editRuntimeNodeGrant(broadcastTap('g'));p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);p.openThemeNodeGrants();p.editRuntimeNodeGrant(broadcastTap('g'));p.onRuntimeNodeGrantDraft(event('quantity','2'));p.saveRuntimeNodeGrant();p.closeRuntimeEditor();const g=JSON.parse(p.data.formData.themeRuntimeConfig).grants[0];assert.equal(Object.hasOwn(g,'when'),when!==undefined);assert.deepEqual(g.when,when);}
});
test('unchanged prerequisite edit preserves condition field order and raw bytes while changed same-shape values keep order',()=>{
 const when=[{value:3,var:'counter.score',op:'GTE'}],p=conditionPage({grants:[nodeGrant('g',{when})]},p=>p.data.formData.themeRuntimeConfig=' '+p.data.formData.themeRuntimeConfig+' '),raw=p.data.formData.themeRuntimeConfig;p.editRuntimeNodeGrant(broadcastTap('g'));p.editRuntimeGrantCondition(broadcastTap(p.data.runtimeGrantWhenRows[0].id));p.saveRuntimeGrantCondition();p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);
 p.openThemeNodeGrants();p.editRuntimeNodeGrant(broadcastTap('g'));p.editRuntimeGrantCondition(broadcastTap(p.data.runtimeGrantWhenRows[0].id));p.onRuntimeGrantConditionDraft(event('value','4'));p.saveRuntimeGrantCondition();p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.deepEqual(Object.keys(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0].when[0]),Object.keys(when[0]));
});
test('prerequisite type replacement drops only old recognized condition fields and explicit removal omits when',()=>{
 const p=conditionPage({grants:[nodeGrant('g',{when:[{op:'GTE',var:'counter.score',value:3}]})]});p.editRuntimeNodeGrant(broadcastTap('g'));p.editRuntimeGrantCondition(broadcastTap(p.data.runtimeGrantWhenRows[0].id));assert.equal(composeCondition(p,{source:'ITEM_HELD',value:'1'}),true);p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.deepEqual(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0].when,[{op:'ITEM_HELD',itemId:'a',quantity:1}]);
 p.openThemeNodeGrants();p.editRuntimeNodeGrant(broadcastTap('g'));p.removeRuntimeGrantCondition(broadcastTap(p.data.runtimeGrantWhenRows[0].id));p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.ok(!Object.hasOwn(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0],'when'));
});
test('all exact six comparisons and item acquired versus held remain distinct with strict inclusive bounds',()=>{
 for(const op of ['EQ','NE','GT','GTE','LT','LTE']){const p=conditionPage();assert.equal(composeCondition(p,{op,value:'-999999'}),true);assert.equal(p._runtimeGrantWhenRows[0].condition.op,op);}
 for(const source of ['ITEM_HELD','ITEM_ACQUIRED']){const p=conditionPage();assert.equal(composeCondition(p,{source,value:'999'}),true);assert.equal(p._runtimeGrantWhenRows[0].condition.op,source);}
 for(const [source,value]of [['ITEM_HELD','0'],['ITEM_HELD','1000'],['counter.score','1000000'],['counter.score','-1000000'],['counter.score','1.5'],['counter.score','1e2'],['counter.score','+1'],['counter.score',' ']]){const p=conditionPage();assert.equal(composeCondition(p,{source,value}),false);assert.equal(p._runtimeGrantWhenRows.length,0);}
});
test('exact 30 AND conditions are allowed and 31st is refused without changing the stored rules',()=>{
 const p=conditionPage();for(let i=0;i<30;i++)assert.equal(composeCondition(p,{value:String(i)}),true);assert.equal(composeCondition(p,{value:'30'}),false);assert.equal(p._runtimeGrantWhenRows.length,30);p.clearRuntimeGrantConditionDraft();composeNodeGrant(p);p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(JSON.parse(p.data.formData.themeRuntimeConfig).grants[0].when.length,30);
});
test('unsupported and extended condition shapes or references remain verbatim read-only',()=>{
 const values=[[{op:'HAS_TAG',value:'tag.ready'}],[{op:'GTE',var:'counter.unknown',value:1}],[{op:'GTE',var:'sys.outcome.7',value:1}],[{op:'ITEM_HELD',itemId:'missing',quantity:1}],[{op:'NODE_COMPLETED',nodeId:9}],[{op:'NODE_COMPLETED',nodeId:'7'}],[{op:'GTE',var:'counter.score',value:1,extra:true}],Array.from({length:31},()=>({op:'NODE_COMPLETED',nodeId:7})),false];
 for(const when of values){const p=conditionPage({grants:[nodeGrant('g',{when})]}),raw=p.data.formData.themeRuntimeConfig;assert.equal(p.data.runtimeNodeGrants.rows[0].editable,false);assert.equal(p.editRuntimeNodeGrant(broadcastTap('g')),false);assert.equal(p.removeRuntimeNodeGrant(broadcastTap('g')),false);p.closeRuntimeEditor();assert.equal(p.data.formData.themeRuntimeConfig,raw);}
});
test('private source choices and condition rows cannot be forged through presentation data',()=>{
 const p=conditionPage();assert.ok(!p.data.runtimeGrantConditionSources.some(s=>s.key==='tag.ready'));p.data.runtimeGrantConditionSources.push({key:'sys.secret',kind:'state'});assert.equal(p.onRuntimeGrantConditionDraft(event('sourceIndex',p.data.runtimeGrantConditionSources.length-1)),false);p.data.runtimeGrantConditionNodes.push({id:999});assert.equal(p.onRuntimeGrantConditionDraft(event('nodeIndex',p.data.runtimeGrantConditionNodes.length-1)),false);p.data.runtimeGrantConditionComparisons[0].op='BAD';assert.equal(composeCondition(p,{source:'sys.hp',op:'NE',value:'0'}),true);p.data.runtimeGrantWhenRows[0].condition={op:'HAS_TAG',value:'tag.bad'};composeNodeGrant(p);p.saveRuntimeNodeGrant();assert.equal(p._runtimeConfig().grants[0].when[0].op,'NE');
});
test('declared attribute initialization is never activated and malformed declarations do not expose unregistered keys',()=>{
 const p=conditionPage({},p=>p.data.formData.journeyRules=conditionRules().replace('"stateEnabled":true','"stateEnabled":false'));assert.equal(p.data.runtimeGrantConditionContext.enabled,false);composeCondition(p);composeNodeGrant(p);p.saveRuntimeNodeGrant();p.closeRuntimeEditor();assert.equal(JSON.parse(p.data.formData.journeyRules).stateEnabled,false);
 const q=conditionPage({},p=>p.data.formData.journeyRules=JSON.stringify({schemaVersion:1,attributes:[{key:'counter.bad',label:'',initial:0}]}));assert.ok(q.data.runtimeGrantConditionContext.error);assert.ok(!q.data.runtimeGrantConditionSources.some(x=>x.key==='counter.bad'));
});
test('scope switching cancels condition drafts and private condition IDs never recycle across scope or reopen',()=>{
 const p=conditionPage({scenes:[sceneRow([])],currentScene:'scene_a'});composeCondition(p);const old=p.data.runtimeGrantWhenRows[0].id;sceneScope(p,1);assert.equal(p.data.runtimeGrantWhenRows.length,0);composeCondition(p);assert.notEqual(p.data.runtimeGrantWhenRows[0].id,old);assert.equal(p.removeRuntimeGrantCondition(broadcastTap(old)),false);p.cancelRuntimeEditor();p.openThemeNodeGrants();composeCondition(p);assert.notEqual(p.data.runtimeGrantWhenRows[0].id,old);
});
test('conditional grant source declaration owner revision and load changes retire edits and delayed grant removal',()=>{
 for(const change of [p=>member=8,p=>p._editLoadToken=3,p=>p.data.baseRevision=3,p=>p.data.formData.journeyRules='{}',p=>p.data.formData.chapters[0].nodes.reverse(),p=>p.data.editScope='WHITELIST']){member=7;confirm='manual';const p=conditionPage({grants:[nodeGrant('g',{when:[{op:'GTE',var:'counter.score',value:1}]})]});p.removeRuntimeNodeGrant(broadcastTap('g'));const pending=modalCalls.at(-1);change(p);const before=p.data.formData.themeRuntimeConfig;pending.success({confirm:true});assert.equal(p.onRuntimeGrantConditionDraft(event('value','1')),false);assert.equal(p.saveRuntimeGrantCondition(),false);assert.equal(p.editRuntimeNodeGrant(broadcastTap('g')),false);assert.equal(p.closeRuntimeEditor(),false);assert.equal(p.data.formData.themeRuntimeConfig,before);}
});
test('condition-only pending composition cannot be overwritten by another grant edit or silently committed by Done',()=>{
 const p=conditionPage({grants:[nodeGrant()]});composeCondition(p);assert.equal(p.editRuntimeNodeGrant(broadcastTap('node_a')),false);assert.equal(p.closeRuntimeEditor(),false);p.clearRuntimeNodeGrantDraft();assert.equal(p.editRuntimeNodeGrant(broadcastTap('node_a')),true);p.onRuntimeGrantConditionDraft(event('sourceIndex',0));assert.equal(p.saveRuntimeNodeGrant(),false);p.clearRuntimeGrantConditionDraft();p.saveRuntimeNodeGrant();assert.equal(p.closeRuntimeEditor(),true);
});
test('conditional CHOICE association stages only the selected scene while root and other advanced rules stay exact',()=>{
 const scenes=[sceneRow([]),{id:'scene_b',name:'乙',grants:[choiceGrant('advanced',{future:{x:1}})]}],p=choicePage({scenes,currentScene:'scene_a'},p=>p.data.formData.journeyRules=conditionRules()),before=JSON.parse(p.data.formData.themeRuntimeConfig);sceneScope(p,1);composeChoice(p);composeCondition(p,{source:'NODE_COMPLETED',nodeIndex:0});composeCondition(p,{source:'ITEM_ACQUIRED',value:'2'});p.saveRuntimeChoiceGrant();p.closeRuntimeEditor();const c=JSON.parse(p.data.formData.themeRuntimeConfig);assert.deepEqual(c.grants,before.grants);assert.deepEqual(c.scenes[1],before.scenes[1]);assert.deepEqual(c.scenes[0].grants[0].when,[{op:'NODE_COMPLETED',nodeId:7},{op:'ITEM_ACQUIRED',itemId:'a',quantity:2}]);
});
test('prerequisite markup exposes full optional AND composition and signal-time no-deferred-retry semantics',()=>{
 const text=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8');for(const name of ['saveRuntimeGrantCondition','editRuntimeGrantCondition','removeRuntimeGrantCondition','clearRuntimeGrantConditionDraft'])assert.ok(text.includes('bindtap="'+name+'"'));assert.match(text,/不因之后状态变化补发/);assert.match(text,/同一信号不会重试/);assert.match(text,/全部满足/);assert.match(text,/无额外前提/);
});


test('merged legacy Bingo editor remains available without enabling runtime and cancels edits',()=>{
 const p=page(false);p.data.bingoRows=[{label:'原格',couponId:0,feedbackText:''}];
 assert.equal(p.openThemeBingo(),true);assert.equal(p.data.runtimeEditor.kind,'bingo');
 p.openRuntimeBingoCell({currentTarget:{dataset:{index:0}}});p.data.bingoRows[0].label='取消的格';
 p.backRuntimeLayer();assert.equal(p.data.runtimeEditor.kind,'bingo');p.cancelRuntimeEditor();
 assert.equal(p.data.bingoRows[0].label,'原格');assert.equal(p.data.formData.themeRuntimeConfig,undefined);
 p.openThemeBingo();p.data.bingoRows[0].label='保留的格';assert.equal(p.closeRuntimeEditor(),true);
 assert.equal(p.data.bingoRows[0].label,'保留的格');assert.equal(p.data.formData.themeRuntimeConfig,undefined);
 p.data.editScope='WHITELIST';assert.equal(p.openThemeBingo(),false);
});
test('merged Bingo nested reward back and subtitle editor preserve master interactions',()=>{
 const p=page(false);p.data.bingoRows=[{label:'格'}];p.openThemeBingo();p.openRuntimeBingoCell({currentTarget:{dataset:{index:0}}});
 let selected,backed=0;p.selectComponent=id=>{selected=id;return {backSelector(){backed++;}};};
 p.onRuntimeRewardOpen({detail:{open:true}});assert.equal(p.closeRuntimeEditor(),false);p.backRuntimeLayer();
 assert.equal(selected,'#runtime-bingo-reward-selector');assert.equal(backed,1);assert.equal(p.data.runtimeEditor.show,true);
 p.toggleSubtitleEditing();assert.equal(p.data.subtitleEditing,true);p.toggleSubtitleEditing();assert.equal(p.data.subtitleEditing,false);
});
test('merged publish keeps explicit Bingo enable state and chapter-ending handlers',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/index.js'),'utf8');
 assert.match(source,/bingoEnabled: bingo.enabled/);assert.match(source,/enabled: this.data.bingoEnabled/);
 for(const handler of ['onChapterEndingToggle','onChapterEndingKind','onChapterEndingPick','onBingoToggle']) assert.match(source,new RegExp(handler+'\\('));
 assert.doesNotMatch(source,/themeRuntimeConfig: this\.\_runtimePayload\(\)/);
 const markup=fs.readFileSync(path.join(__dirname,'../../pages/publish/fabu/runtime-editor.wxml'),'utf8');
 assert.match(markup,/stacked="{{runtimeRewardOpen}}"/);assert.match(markup,/runtime-bingo-reward-selector/);
});


// Run the real AI-restoration method, including its early-return guard and setData patch.
function aiRewardDraftPage(initialRewardOn = false, blank = true) {
 const source = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
 const start = source.indexOf('  applyAiDraft(options) {');
 const end = source.indexOf('  isBlankTopicDraft()', start);
 assert.ok(start > 0 && end > start, 'extract the actual applyAiDraft method');
 const methods = Function('proEditorPolicy', 'proEditorMaterials', 'aiDraftStorageKey', 'wx',
  'return ({' + source.slice(start, end) + '});')({}, {}, () => null, {});
 return Object.assign({data: {completeRewardOn: initialRewardOn, formData: {}}, setData,
  isBlankTopicDraft: () => blank, _parseImgArr: () => [], updateAllStatistics() {}}, methods);
}
test('AI draft existing completion reward restores selector visibility together with its checked switch', () => {
 const p = aiRewardDraftPage(false);
 p.applyAiDraft({from: 'ai', formData: {name: 'Recovered reward', completeRewardCouponId: 12, chapters: []}});
 assert.equal(p.data.formData.completeRewardCouponId, 12);
 assert.equal(p.data.completeRewardOn, true);
 assert.equal(p.data.completeRewardOn, !!p.data.formData.completeRewardCouponId);
 const markup = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/topic-detail-sheet.wxml'), 'utf8');
 assert.match(markup, /checked="\{\{!!formData\.completeRewardCouponId\}\}"/);
 assert.match(markup, /<block wx:if="\{\{completeRewardOn\}\}">/);
});
test('AI draft without a completion reward clears stale selector state for zero null and omission', () => {
 for (const reward of [{completeRewardCouponId: 0}, {completeRewardCouponId: null}, {}]) {
  const p = aiRewardDraftPage(true);
  p.applyAiDraft({from: 'ai', formData: {name: 'No reward', chapters: [], ...reward}});
  assert.equal(p.data.formData.completeRewardCouponId, 0);
  assert.equal(p.data.completeRewardOn, false);
  assert.equal(p.data.completeRewardOn, !!p.data.formData.completeRewardCouponId);
 }
});
test('AI reward restoration never overwrites a nonblank draft in either reward direction', () => {
 for (const initialReward of [0, 12]) {
  const p = aiRewardDraftPage(!!initialReward, false);
  p.data.formData = {name: 'Existing edit', completeRewardCouponId: initialReward};
  const before = JSON.stringify(p.data);
  p.applyAiDraft({from: 'ai', formData: {name: 'Incoming draft', completeRewardCouponId: initialReward ? 0 : 12, chapters: []}});
  assert.equal(JSON.stringify(p.data), before);
 }
});
