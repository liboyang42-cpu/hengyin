const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
function mount(){
  let definition;const requests=[];
  global.wx={getStorageSync:()=>'',getAccountInfoSync:()=>({miniProgram:{envVersion:'release'}})};
  global.getApp=()=>({globalData:{features:{}},getUserID:()=>'',getUserType:()=>1,getPageSize:()=>10,
    getImgUrl:value=>value||'',sendRequest:request=>{requests.push(request);return {abort(){}};},
    getUsageConsent:()=>({ensure:()=>Promise.resolve()})});
  global.Page=value=>{definition=value;};
  const file=path.resolve(__dirname,'../../pages/index/index.js');
  delete require.cache[require.resolve(file)];require(file);
  const page=Object.assign({},definition,{data:structuredClone(definition.data),
    setData(patch,done){for(const [key,value]of Object.entries(patch)){
      const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let target=this.data;
      for(const part of parts.slice(0,-1))target=target[part]||(target[part]={});target[parts.at(-1)]=value;
    }if(done)done();}});
  return {page,requests};
}
const vectors=[
  [{selfPlay:1,selfPlayPrice:'29.90',minAmout:0},'29.90'],
  [{selfPlay:'1',selfPlayPrice:0,minAmout:49},0],
  [{minAmout:'18.50'},'18.50'],
  [{selfPlay:0,selfPlayPrice:29.9,minAmout:12},12],
  [{selfPlay:1,selfPlayPrice:null,minAmout:0},null],
  [{selfPlay:1,selfPlayPrice:'',minAmout:0},null],
  [{selfPlay:1,selfPlayPrice:false,minAmout:0},null],
  [{selfPlay:1,selfPlayPrice:'invalid',minAmout:0},null],
  [{selfPlay:1,selfPlayPrice:-1,minAmout:0},null],
  [{},null],
];
test('home topic feed uses the same authoritative self-play price as detail, with free zero retained',()=>{
  const {page,requests}=mount();page.loadBottomTopicList(1,true);
  const rows=vectors.map(([fields],i)=>({id:i+1,name:'主题'+i,...fields}));
  requests[0].success({code:200,data:{rows,total:rows.length}});
  assert.deepEqual(page.data.bottomTopicList.map(row=>row.homePrice),vectors.map(row=>row[1]));
  assert.equal(page.data.bottomTopicList[0].minAmout,0);assert.equal(rows[0].selfPlayPrice,'29.90');
});
test('recommendation and nearby topic consumers use the same amount, without inventing a nearby price',()=>{
  for(const kind of ['recommendedTopicList','nearbyTopicList']){
    const {page,requests}=mount();if(kind==='nearbyTopicList')page._nearbyThemeOwner='|';
    page.getListData(kind,kind==='nearbyTopicList'?'/api/roam/hangout/nearby':'/api/topic/list',{});
    const rows=[{id:1,name:'主题',selfPlay:1,selfPlayPrice:'29.90',minAmout:0},{id:2,name:'无报价'}];
    requests[0].success({code:200,data:kind==='nearbyTopicList'?{items:rows}:{rows}});
    assert.equal(page.data[kind][0].homePrice,'29.90');assert.equal(page.data[kind][1].homePrice,null);
  }
});
test('topic pagination applies the price branch and activity ticket amounts remain untouched',()=>{
  const {page,requests}=mount();page.loadBottomTopicList(1);
  requests[0].success({code:200,data:{rows:[{id:1,selfPlay:1,selfPlayPrice:'29.90',minAmout:0}],total:1}});
  assert.equal(page.data.bottomTopicList[0].homePrice,'29.90');
  page.loadBottomActivityList(1,true);
  requests.at(-1).success({code:200,data:{rows:[{id:2,minAmout:7,selfPlay:1,selfPlayPrice:99}],total:1}});
  assert.equal(page.data.bottomActivityList[0].minAmout,7);
  assert.equal(page.data.bottomActivityList[0].homePrice,undefined);
});
test('discover topic price filters share the display amount, preserve unknowns, and keep activity prices',()=>{
  const search=require('../../utils/discover-search.js');
  const rows=vectors.map(([fields],i)=>({id:i+1,...fields}));
  assert.deepEqual(rows.map(row=>search.rowPrice(row,'topic')),vectors.map(([,value])=>value==null?null:Number(value)));
  const paid=search.filterRows('topic',rows,{minPrice:20});
  assert.deepEqual(paid.map(row=>row.id),[1,5,6,7,8,9,10]);
  const cheap=search.filterRows('topic',rows,{maxPrice:15});
  assert.deepEqual(cheap.map(row=>row.id),[2,4,5,6,7,8,9,10]);
  assert.equal(search.rowPrice({selfPlay:1,selfPlayPrice:99,minAmout:7},'activity'),7);
});
test('topic markup distinguishes unknown from a free price and keeps the activity branch',()=>{
  const source=fs.readFileSync(path.resolve(__dirname,'../../pages/index/index.wxml'),'utf8');
  assert.match(source,/wx:if="\{\{item.homePrice != null\}\}"[^>]*>\{\{money.amount\(item.homePrice\)\}\} 起/);
  assert.match(source,/wx:else class="v2-status-v price">—/);
  assert.equal((source.match(/money.amount\(item.minAmout\)/g)||[]).length,1);
});
