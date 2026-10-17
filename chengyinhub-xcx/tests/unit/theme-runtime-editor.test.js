const assert = require('node:assert/strict');
global.getApp = () => ({ globalData: {} });
global.wx = {showToast(){}};
const editor = require('../../pages/publish/fabu/theme-runtime.js');
const player = require('../../pages/play/utils/theme-backpack.js');
const rules = require('../../pages/publish/utils/publish/story-initial-state.js');
function page() {
 const p = { data: Object.assign({}, JSON.parse(JSON.stringify(editor.data)), { formData: { chapters: [{name:'一'},{name:'二'}], journeyRules: '', themeRuntimeConfig: '' }, popChapterIndex:0,popChapterAction:1 }),
 setData(patch) { Object.entries(patch).forEach(([key,value]) => {const keys=key.split('.');let target=this.data;keys.slice(0,-1).forEach(k=>target=target[k]);target[keys.at(-1)]=value;}); } };
 return Object.assign(p, editor.methods);
}
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
}
const unfinished = {data:{chapter:{id:5},nodes:[{chapterId:5,done:false}],themeBag:{chapterIndexes:[2],chapterBindings:[{chapterId:5,index:2}]}},refreshThemeBackpack(){throw new Error('未完成本章不能先结束');}};
Object.assign(unfinished,player.methods(()=>{throw new Error('不能写入结束');}));
assert.equal(unfinished._themeChapterIndex(),2);unfinished._endThemeChapter().then(ok=>assert.equal(ok,true));
retry().then(()=>console.log('theme runtime editor/player checks passed')).catch(e=>{console.error(e);process.exitCode=1;});
