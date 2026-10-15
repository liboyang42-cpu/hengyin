'use strict'
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
let config, member, token, requests, locations, modals, writes, recorded, app, toasts, loading
global.getApp = () => app
global.Page = value => { config = value }
global.wx = {
  getStorageSync: () => '', hideTabBar() {}, getLocation: value => locations.push(value),
  getFuzzyLocation() { assert.fail('首页不能再使用模糊定位') },
  showModal: value => modals.push(value), showLoading() { loading = true }, hideLoading() { loading = false },
  showToast(value) { toasts.push(value.title) }, setNavigationBarColor() {},
}
function consent(patch) {
  return Object.assign({ docType:'privacy_policy', scene:'home_city_location', docVersion:'v2.1',
    eventType:'AGREE', requestId:'home-consent-original' }, patch)
}
beforeEach(() => {
  member='1001'; token='normal-session'; requests=[]; locations=[]; modals=[]; writes=[]; toasts=[]; loading=false
  recorded=() => Promise.resolve({requestId:'home-consent-original',response:{code:200,data:consent()}})
  app={ globalData:{}, getUserID:()=>member, getUserType:()=>0, getImgUrl:value=>value,
    sendRequest:request=>{requests.push(request);return {abort(){}}},
    recordConsent:input=>{writes.push(input);return recorded()} }
  Object.defineProperty(app.globalData,'authorization',{get:()=>token})
  wx.getLocation=value=>locations.push(value)
  delete require.cache[require.resolve('../../utils/loading.js')]
  delete require.cache[require.resolve('../../pages/index/index.js')]
  require('../../pages/index/index.js')
})
function page() {
  const value=Object.assign({},config,{data:JSON.parse(JSON.stringify(config.data))})
  value.setData=(patch,done)=>{Object.assign(value.data,patch);if(done)done()}
  return value
}
function latest(p,row=consent()) {
  p.getHomeCityLocation()
  const r=requests.find(x=>x.url==='/api/compliance/consents/latest')
  assert.ok(r);assert.deepEqual(JSON.parse(r.data),{docType:'privacy_policy',scene:'home_city_location'})
  r.success({code:200,data:row});return r
}
const flush=()=>new Promise(resolve=>setImmediate(resolve))
test('当前账号有效单独同意后自动取精确位置',()=>{
  latest(page());assert.equal(locations.length,1);assert.equal(locations[0].type,'gcj02')
  assert.equal(modals.length,0);assert.equal(writes.length,0)
})
test('首次弹窗拒绝不定位不写AGREE',()=>{
  latest(page(),null);assert.equal(locations.length,0);assert.equal(modals.length,1)
  assert.match(modals[0].content,/精确位置/);assert.match(modals[0].content,/服务端/)
  modals[0].success({confirm:false});assert.equal(locations.length,0);assert.equal(writes.length,0)
  assert.deepEqual(toasts,[])
})
test('明确同意且真实回执匹配后才定位',async()=>{
  latest(page(),null);modals[0].success({confirm:true});assert.equal(locations.length,0);await flush()
  assert.deepEqual(writes,[{docType:'privacy_policy',scene:'home_city_location',eventType:'AGREE'}])
  assert.equal(locations.length,1)
})
test('同意写失败和错场景回执不得定位',async()=>{
  for(const result of [()=>Promise.reject(new Error('offline')),
    ()=>Promise.resolve({response:{code:200,data:consent({scene:'roam_location'})}})]) {
    recorded=result;requests=[];latest(page(),null);modals.at(-1).success({confirm:true});await flush()
    assert.equal(locations.length,0)
  }
})
test('旧版本、撤回、错场景均不能直接自动定位',()=>{
  for(const row of [consent({docVersion:'old'}),consent({eventType:'REVOKE'}),consent({scene:'roam_location'})]) {
    requests=[];latest(page(),row);assert.equal(locations.length,0)
  }
})
test('匿名或演示会话不读取同意、不触发定位',()=>{
  member='';page().getHomeCityLocation();member='1001';token='dev-token';page().getHomeCityLocation()
  assert.equal(requests.length,0);assert.equal(locations.length,0);assert.equal(modals.length,0)
})
test('换账号、换会话、卸载后的迟到授权回执不能采集',()=>{
  for(const change of [()=>{member='1002'},()=>{token='different'},p=>p.onUnload()]) {
    member='1001';token='normal-session';requests=[]
    const p=page();p.getHomeCityLocation();change(p)
    requests[0].success({code:200,data:consent()});assert.equal(locations.length,0)
  }
})
test('同意写回时页面已卸载则不能继续定位',async()=>{
  const p=page();latest(p,null);modals[0].success({confirm:true});p.onUnload();await flush()
  assert.equal(locations.length,0)
})
test('坐标仅换城市，不进页面data、附近排序或账号资料',()=>{
  const p=page();latest(p);locations[0].success({latitude:31.230416,longitude:121.473701})
  const r=requests.find(x=>x.url==='/api/map/reverse-geocode');assert.ok(r)
  assert.deepEqual(r.data,{latitude:31.230416,longitude:121.473701});assert.equal(p.data.locationInfo,undefined)
  r.success({code:200,data:{city:'上海市'}});assert.equal(p.data.city,'上海市')
  assert.equal(requests.some(x=>x.url==='/api/user/update'||x.url==='/api/activity/list'),false)
})
test('无效坐标、换账号后迟到城市结果不写入当前页面',()=>{
  const p=page();latest(p);locations[0].success({latitude:NaN,longitude:121})
  assert.equal(requests.some(x=>x.url==='/api/map/reverse-geocode'),false)
  locations[0].success({latitude:31.23,longitude:121.47})
  const r=requests.find(x=>x.url==='/api/map/reverse-geocode');member='1002'
  r.success({code:200,data:{city:'上海市'}});assert.equal(p.data.city,'')
})
test('首页刷新即使残留旧坐标也不上传给附近排序',()=>{
  const p=page();p.data.locationInfo={latitude:31.23,longitude:121.47}
  for(const key of ['getUserData','loadContinueExplore','loadBottomTopicList','loadBottomActivityList'])p[key]=()=>{}
  const lists=[];p.getListData=(...args)=>lists.push(args);p.loadPageData()
  const near=lists.find(x=>x[0]==='nearbyActivityList');assert.ok(near)
  assert.equal(near[2].sort_type,2);assert.equal('latitude' in near[2],false);assert.equal('longitude' in near[2],false)
})
test('定位API或授权读取失败给简短反馈，不采集或泄露原始错误',()=>{
  const p=page();latest(p);assert.equal(loading,true)
  locations[0].fail({errMsg:'private-platform-detail'});assert.equal(loading,false)
  assert.equal(toasts.at(-1),'暂时无法定位城市，可继续浏览')
  assert.equal(requests.some(x=>x.url==='/api/map/reverse-geocode'),false)
  requests=[];locations=[];p.getHomeCityLocation();requests[0].fail()
  assert.equal(toasts.at(-1),'暂时无法定位城市，可继续浏览')
  requests=[];p.getHomeCityLocation();requests[0].success({code:500})
  assert.equal(locations.length,0)
  requests=[];delete wx.getLocation;latest(page())
  assert.equal(locations.length,0);assert.equal(loading,false)
  assert.equal(toasts.every(text=>text==='暂时无法定位城市，可继续浏览'),true)
})
test('隐藏首页后迟到的同意回执不得开始精确定位',()=>{
  const p=page();p.getHomeCityLocation();p.onHide()
  requests[0].success({code:200,data:consent()})
  assert.equal(locations.length,0);assert.equal(modals.length,0)
})
test('隐藏首页立即收loading，迟到GPS不得继续逆地理请求',()=>{
  const p=page();latest(p);assert.equal(loading,true);p.onHide()
  assert.equal(loading,false)
  locations[0].success({latitude:31.23,longitude:121.47})
  assert.equal(requests.some(x=>x.url==='/api/map/reverse-geocode'),false)
  assert.deepEqual(toasts,[])
})
test('回到首页重新读取该账号同意，不复用隐藏前的取位回执',async()=>{
  const p=page();p.data.isAppReady=true
  p.getUserData=()=>{};p.loadContinueExplore=()=>{}
  p.onHide();p.onShow()
  await new Promise(resolve=>setTimeout(resolve,120))
  const r=requests.find(x=>x.url==='/api/compliance/consents/latest')
  assert.ok(r);assert.equal(locations.length,0)
  r.success({code:200,data:consent()});assert.equal(locations.length,1)
  p.onHide();assert.equal(loading,false)
})
test('回到首页时onLoad已发起定位，不重复触发同意读取',async()=>{
  const p=page();p.data.isAppReady=true
  p.getUserData=()=>{};p.loadContinueExplore=()=>{}
  p.onHide();p.onShow();p.getHomeCityLocation()
  await new Promise(resolve=>setTimeout(resolve,120))
  assert.equal(requests.filter(x=>x.url==='/api/compliance/consents/latest').length,1)
  p.onHide()
})
test('首页用途已撤回时不自动弹同意，也不取位置',()=>{
  latest(page(),consent({eventType:'REVOKE'}))
  assert.equal(locations.length,0);assert.equal(modals.length,0)
})
