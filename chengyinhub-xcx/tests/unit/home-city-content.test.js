const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
function mount(saved = '') {
  const requests = [], positions = [], modals = [], nav = [], disk = { home_city_last: saved }; let definition;
  global.wx = { getStorageSync: k => disk[k], setStorageSync: (k,v) => { disk[k] = v; }, removeStorageSync:k=>delete disk[k],
    showToast() {}, showLoading() {}, hideLoading() {}, showModal:o=>modals.push(o), getLocation:o=>positions.push(o),
    navigateTo:o=>nav.push(o.url), getAccountInfoSync:()=>({miniProgram:{envVersion:'release'}}) };
  const app = { globalData:{features:{}}, getUserID:()=>'', getUserType:()=>1, getPageSize:()=>10,
    getImgUrl:v=>v||'', getUsageConsent:()=>({ensure:()=>Promise.resolve()}), sendRequest:r=>{
      requests.push(r); const control={aborted:false,abort(){this.aborted=true;}};r.control=control;return control; } };
  global.getApp = ()=>app; global.getCurrentPages=()=>[];global.Page=d=>{definition=d;};
  const file=path.resolve(__dirname,'../../pages/index/index.js');delete require.cache[require.resolve(file)];require(file);
  const page=Object.assign({},definition,{data:structuredClone(definition.data),setData(patch,done){
    for(const [k,v]of Object.entries(patch)){const bits=k.replace(/\[(\d+)\]/g,'.$1').split('.');let target=this.data;
      for(const bit of bits.slice(0,-1))target=target[bit]||(target[bit]={});target[bits.at(-1)]=v;}done?.();}});
  return {page,requests,positions,modals,nav,disk};
}
function reply(request,rows,code=200){request.success({code,data:request.method==='GET'?{items:rows}:{rows}});request.complete?.();}
const topic=id=>({id,name:'Topic '+id,addressName:'真实城市地点',isRecommend:1});
test('cold default is Shanghai content only; saved other city wins without a position',()=>{
  const a=mount();assert.equal(a.page.data.city,'上海市');a.page.loadCityEvents();assert.equal(a.requests[0].data.city_keyword,'上海市');assert.deepEqual(a.positions,[]);
  const b=mount('杭州市');assert.equal(b.page.data.city,'杭州市');b.page.getHomeCityLocation();assert.deepEqual(b.positions,[]);
});
for(const count of [0,3])test(`local editorial supply ${count}: only real curated supply is returned`,()=>{
  const h=mount();h.page.loadRecommendedTopics();assert.equal(h.requests[0].data.is_recommend,1);assert.equal(h.requests[0].data.city_keyword,'上海市');
  reply(h.requests[0],Array.from({length:count},(_,i)=>topic(i+1)));
  if(!count){assert.equal(h.requests.length,2);assert.equal(h.requests[1].data.is_recommend,1);reply(h.requests[1],[]);}
  else assert.equal(h.requests.length,1);
  assert.equal(h.page.data.recommendedTopicList.length,count);assert.equal(h.page.data.listErr.recommendedTopicList,false);
});
test('city first, genuine other-city curated supplement, dedup, source place/cover/price retained',()=>{
  const h=mount();h.page.loadRecommendedTopics();reply(h.requests[0],[{...topic(2),addressName:'上海市',imgUrl:'real.jpg',selfPlay:1,selfPlayPrice:29.9,minAmout:0},topic(1)]);
  assert.equal(h.requests[1].data.city_keyword,undefined);assert.equal(h.requests[1].data.is_recommend,1);
  reply(h.requests[1],[topic(1),{...topic(3),addressName:'杭州市'},{...topic(4),addressName:'北京市'}]);
  assert.deepEqual(h.page.data.recommendedTopicList.map(x=>x.id),[2,1,3,4]);assert.equal(h.page.data.recommendedTopicList[0].homePrice,29.9);
  assert.equal(h.page.data.recoCards[0].cityLabel,'上海市');assert.equal(h.page.data.recoCards[2].cityLabel,'杭州市');
});
test('local failure is error, not global noneditorial fallback; retry restarts current city',()=>{
  const h=mount();h.page.loadRecommendedTopics();reply(h.requests[0],[],500);assert.equal(h.requests.length,1);assert.equal(h.page.data.listErr.recommendedTopicList,true);
  h.page.retryList({currentTarget:{dataset:{list:'recommendedTopicList'}}});assert.equal(h.requests[1].data.city_keyword,'上海市');assert.equal(h.requests[1].data.is_recommend,1);
});
test('city events without GPS are city-scoped activity POSTs with honest empty/error/retry states',()=>{
  const h=mount('杭州市');h.page.loadCityEvents();const req=h.requests[0];assert.equal(req.url,'/api/activity/list');assert.equal(req.method,'POST');assert.equal(req.data.is_my,2);assert.equal(req.data.city_keyword,'杭州市');
  assert.equal(req.data.lat,undefined);reply(req,[]);assert.equal(h.page.data.listLoaded.nearbyActivityList,true);assert.equal(h.page.data.listErr.nearbyActivityList,false);assert.deepEqual(h.positions,[]);
  h.page.loadCityEvents();reply(h.requests.at(-1),[],500);assert.equal(h.page.data.listErr.nearbyActivityList,true);
  h.page.retryList({currentTarget:{dataset:{list:'nearbyActivityList'}}});assert.equal(h.requests.at(-1).data.city_keyword,'杭州市');
});
test('manual city invalidates old content and an in-flight GPS city result',()=>{
  const h=mount();h.page.loadRecommendedTopics();const old=h.requests[0];h.page._locationEpoch=4;
  const scope={epoch:4,memberId:'',authorization:'',consentRequestId:'normal-consent'};h.page.reverseGeocode(0,0,scope);const geocode=h.requests.at(-1);
  h.page.onManualCityChange({detail:{value:4}});assert.equal(h.page.data.city,'杭州市');assert.equal(old.control.aborted,true);
  reply(old,[topic(99)]);geocode.success({code:200,data:{city:'北京市'}});assert.equal(h.page.data.city,'杭州市');assert.deepEqual(h.page.data.recommendedTopicList,[]);
  assert.ok(h.requests.some(r=>r.data?.city_keyword==='杭州市'&&r.url==='/api/activity/list'));
});
test('GPS feed uses only real activity kind for events and preserves the existing topic rail',async()=>{
  const h=mount();h.page.locateNearbyThemes();h.modals[0].success({confirm:true});await Promise.resolve();await Promise.resolve();
  assert.equal(h.positions.length,1);assert.equal(h.positions[0].type,'gcj02');h.positions[0].success({latitude:31.23,longitude:121.47});
  const geo=h.requests.filter(r=>r.url==='/api/roam/hangout/nearby');assert.equal(geo.length,2);
  const rows=[{kind:'topic',...topic(10),picUrl:'topic.jpg'},{kind:'activity',id:20,name:'Real event',picUrl:'event.jpg',distance:0},{kind:'hangout',id:30,name:'old layer'}];
  geo.forEach(r=>reply(r,rows));assert.deepEqual(h.page.data.nearbyTopicList.map(x=>x.id),[10]);assert.deepEqual(h.page.data.nearbyActivityList.map(x=>x.id),[20]);
  assert.equal(h.page.data.nearbyActivityList[0].cover,'event.jpg');assert.equal(h.page.data.nearbyActivityList[0].distance,'0.00km');assert.equal(h.page.data.nearbyActivityList[0].minAmout,undefined);
});
test('declining nearby location keeps city events and performs no GPS request',()=>{
  const h=mount();h.page.loadCityEvents();reply(h.requests[0],[{id:2,name:'City event',minAmout:0}]);h.page.locateNearbyThemes();h.modals[0].success({confirm:false});
  assert.equal(h.page.data.nearbyEventsLocated,false);assert.equal(h.page.data.nearbyActivityList.length,1);assert.deepEqual(h.positions,[]);
});
test('both kinds retain actual detail navigation and markup never claims city content has a radius',()=>{
  const h=mount();h.page.goTopic({currentTarget:{dataset:{id:11}}});assert.equal(h.nav[0],'/pages/topic/index/index?id=11');
  const text=fs.readFileSync(path.resolve(__dirname,'../../pages/index/index.wxml'),'utf8');assert.match(text,/nearbyEventsLocated \? '附近活动' : city \+ '活动'/);assert.match(text,/nearbyActivityList[\s\S]*?bindtap="goActivity"/);
});
test('supplement failure preserves actual local supply with an explicit retry error',()=>{
  const h=mount();h.page.loadRecommendedTopics();reply(h.requests[0],[topic(1),topic(2)]);reply(h.requests[1],[],500);
  assert.deepEqual(h.page.data.recommendedTopicList.map(x=>x.id),[1,2]);assert.equal(h.page.data.listErr.recommendedTopicList,true);
  const text=fs.readFileSync(path.resolve(__dirname,'../../pages/index/index.wxml'),'utf8');assert.match(text,/listErr\.recommendedTopicList && recoHero/);
});
test('refresh keeps a foreground GPS query, and changing city aborts its old event response',()=>{
  const h=mount();h.page._nearbyThemeOwner='|';h.page._nearbyThemeEpoch=1;h.page.data.nearbyEventsLocated=true;
  const params={lat:31.23,lng:121.47,radius:3000};
  h.page.getListData('nearbyActivityList','/api/roam/hangout/nearby',params,rows=>rows.filter(x=>x.kind==='activity'));
  const old=h.requests[0];h.page.loadCityEvents();assert.equal(old.control.aborted,true);assert.equal(h.requests[1].method,'GET');assert.deepEqual(h.requests[1].data,params);
  const last=h.requests[1];h.page.selectHomeCity('成都市');assert.equal(last.control.aborted,true);reply(last,[{kind:'activity',id:99,name:'Old point'}]);
  assert.equal(h.page.data.nearbyEventsLocated,false);assert.deepEqual(h.page.data.nearbyActivityList,[]);
  const current=h.requests.at(-1);assert.equal(current.url,'/api/activity/list');assert.equal(current.data.city_keyword,'成都市');assert.equal(current.data.lat,undefined);
});
test('returning under another session clears its old GPS query before any refresh request',()=>{
  const h=mount();Object.assign(global.wx,{hideTabBar(){},setNavigationBarColor(){},setBackgroundColor(){}});
  h.page.consumeDoorScene=()=>false;h.page.loadOperatorAccess=()=>{};h.page.getUserData=()=>{};
  h.page._homeLocationHidden=true;h.page._nearbyThemeOwner='previous-user|previous-session';h.page.data.nearbyEventsLocated=true;
  h.page._listReq={nearbyActivityList:{url:'/api/roam/hangout/nearby',params:{lat:31.23,lng:121.47,radius:3000}}};
  h.page.onShow();assert.equal(h.page.data.nearbyEventsLocated,false);assert.ok(!h.requests.some(r=>r.url==='/api/roam/hangout/nearby'));
  assert.ok(h.requests.some(r=>r.url==='/api/activity/list'&&r.data.city_keyword==='上海市'));
  clearTimeout(h.page._onShowRefreshTimer);
});
test('leaving the nearby scope aborts its event request and removes retained coordinates',()=>{
  const h=mount();h.page._nearbyThemeOwner='|';h.page._nearbyThemeEpoch=1;h.page.data.nearbyEventsLocated=true;
  h.page.getListData('nearbyActivityList','/api/roam/hangout/nearby',{lat:31.23,lng:121.47,radius:3000},rows=>rows);
  const old=h.requests[0];h.page.clearNearbyThemes();assert.equal(old.control.aborted,true);
  assert.equal(h.page._listReq.nearbyActivityList,undefined);assert.equal(h.page._listControls.nearbyActivityList,undefined);
  reply(old,[{id:99,name:'Old location result'}]);assert.deepEqual(h.page.data.nearbyActivityList,[]);
});
