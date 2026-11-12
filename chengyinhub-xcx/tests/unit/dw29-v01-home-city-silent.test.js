'use strict'
const { test, beforeEach } = require('node:test')
const assert = require('node:assert/strict')
let store = {}, config, member, token, requests, locations, modals, writes, recorded, app, toasts, loading
global.getApp = () => app
global.Page = value => { config = value }
global.wx = {
  getStorageSync: key => store[key] || '', setStorageSync: (key, value) => { store[key] = value }, hideTabBar() {}, getLocation: value => locations.push(value),
  getFuzzyLocation() { assert.fail('首页不能再使用模糊定位') },
  showModal: value => modals.push(value), showLoading() { loading = true }, hideLoading() { loading = false },
  showToast(value) { toasts.push(value.title) }, setNavigationBarColor() {},
}
function consent(patch) {
  return Object.assign({ docType:'privacy_policy', scene:'home_city_location', docVersion:'v2.1',
    eventType:'AGREE', requestId:'home-consent-original' }, patch)
}
beforeEach(() => {
  store={}; member='1001'; token='normal-session'; requests=[]; locations=[]; modals=[]; writes=[]; toasts=[]; loading=false
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
  value.setData=(patch,done)=>{Object.assign(value.data,patch);if (patch && patch.locationPrompt) modals.push({ title: patch.locationPrompt.title, content: patch.locationPrompt.content, success: (r) => (r && r.confirm ? value.onLocationPromptAgree() : value.onLocationPromptDecline()) }) /* x-fix6 首页定位改为 feed 顶部卡片:locationPrompt 等同原弹窗,同意/暂不走卡片按钮处理器 */;if(done)done()}
  return value
}
function latest(p,row=consent(),opts) {
  p.getHomeCityLocation(opts)
  const r=requests.find(x=>x.url==='/api/compliance/consents/latest')
  r.success({code:200,data:row});return r
}
const COLD_TOAST='暂时无法自动识别城市，请手动选择'
test('V-01 冷启动/onShow 自动定位失败不弹城市提示，没有记住的城市就固定上海市',()=>{
  const p=page();latest(p);locations[0].success({latitude:31.23,longitude:121.47})
  requests.find(x=>x.url==='/api/map/reverse-geocode').success({code:200,data:{city:'',manualInputRequired:true,reason:'MAP_UNAVAILABLE'}})
  assert.deepEqual(toasts,[]);assert.equal(p.data.city,'上海市');assert.equal(p.data.cityManual,false)
  assert.equal(store.home_city_last,undefined,'默认城市不冒充定位结果写入记忆')
  for(const fail of [l=>l.fail({errMsg:'x'}),l=>l.fail({errMsg:'getLocation:fail auth deny'})]){
    requests=[];locations=[];latest(p);fail(locations[0])
  }
  requests=[];p.getHomeCityLocation();requests[0].fail()
  assert.deepEqual(toasts,[]);assert.equal(modals.length,0)
})
test('V-01 自动定位失败保留上次选择的城市',()=>{
  let p=page();p.onManualCityChange({detail:{value:1}});assert.equal(store.home_city_last,'上海市')
  p=page();latest(p);assert.equal(p.data.city,'上海市')
  locations[0].fail({errMsg:'x'});assert.equal(p.data.city,'上海市');assert.equal(p.data.cityManual,false)
  assert.deepEqual(toasts,[])
})
test('V-01 定位成功后记住城市',()=>{
  const p=page();latest(p);locations[0].success({latitude:31.23,longitude:121.47})
  requests.find(x=>x.url==='/api/map/reverse-geocode').success({code:200,data:{city:'杭州市'}})
  assert.equal(store.home_city_last,'杭州市')
})
test('V-01 用户主动点定位失败才提示',()=>{
  const p=page();p.onTapHomeCity()
  requests.find(x=>x.url==='/api/compliance/consents/latest').success({code:200,data:consent()})
  locations[0].success({latitude:31.23,longitude:121.47})
  requests.find(x=>x.url==='/api/map/reverse-geocode').success({code:200,data:{city:'',manualInputRequired:true,reason:'MAP_UNAVAILABLE'}})
  assert.deepEqual(toasts,[COLD_TOAST])
})
test('V-01 首页城市文字可点击重新定位',()=>{
  const wxml=require('fs').readFileSync(require('path').join(__dirname,'../../pages/index/index.wxml'),'utf8')
  assert.match(wxml,/<block wx:if="\{\{city\}\}">\s*<view wx:if="\{\{!cityPicker\}\}" class="v3-hero-mem-loc" catchtap="onTapHomeCity"[^>]*hover-class="cy-pressed"[^>]*>· \{\{city\}\}<\/view>/)
})

test('V-01 各类自动定位失败与未登录都默认上海市',()=>{
  const cases=[
    p=>{latest(p);locations[0].fail({errMsg:'getLocation:fail auth deny'})},
    p=>{latest(p);locations[0].fail({errMsg:'x'})},
    p=>{p.getHomeCityLocation();requests[0].fail()},
    p=>{latest(p);locations[0].success({latitude:31.23,longitude:121.47});requests.find(x=>x.url==='/api/map/reverse-geocode').fail()},
    p=>{token='';p.getHomeCityLocation()},
  ]
  for(const run of cases){
    store={};requests=[];locations=[];token='normal-session'
    const p=page();run(p)
    assert.equal(p.data.city,'上海市');assert.equal(p.data.cityManual,false)
  }
  assert.deepEqual(toasts,[]);assert.equal(modals.length,0)
  const homeCity=require('../../utils/index/home-city-location.js')
  assert.equal(homeCity.DEFAULT_CITY,'上海市');assert.ok(homeCity.CITY_OPTIONS.includes(homeCity.DEFAULT_CITY))
})
test('V-01 未登录有记住的城市时沿用记住的城市',()=>{
  store={home_city_last:'杭州市'};token=''
  const p=page();p.getHomeCityLocation();assert.equal(p.data.city,'杭州市')
})

// Round5 #7:点「暂不定位」后本机记住,之后启动不再自动弹;只有点城市才再问,同意后清掉记忆
test('Round5 #7 拒绝定位后不再自动询问,点城市才再问', () => {
  wx.removeStorageSync = key => { delete store[key] }
  const noConsent = { docType: 'privacy_policy', scene: 'home_city_location', eventType: 'NONE' }
  let p = page(); latest(p, noConsent)
  assert.equal(modals.length, 1, '首次自动询问')
  modals[0].success({ confirm: false, cancel: true })
  assert.equal(store.home_city_location_declined, 1)
  assert.equal(p.data.city, '上海市')
  // 再次启动 / onShow:不发同意查询、不弹窗
  requests = []; modals = []
  p = page(); p.getHomeCityLocation()
  assert.equal(requests.length, 0); assert.equal(modals.length, 0)
  assert.equal(p.data.city, '上海市')
  // 用户点城市:照常询问;同意后记忆清掉
  p.onTapHomeCity()
  requests.find(x => x.url === '/api/compliance/consents/latest').success({ code: 200, data: noConsent })
  assert.equal(modals.length, 1)
  modals[0].success({ confirm: true })
  assert.equal(store.home_city_location_declined, undefined)
})

// Round5 #8:游客点首页城市 → 现有选城下拉(只改本地展示,不定位不上传);登录后回到点城市重新定位
test('Round5 #8 游客首页城市用选城下拉,选中即改并记住;登录后恢复点城市定位', () => {
  token = ''
  const p = page(); p.getHomeCityLocation()
  assert.equal(p.data.cityPicker, true)
  assert.equal(p.data.city, '上海市'); assert.equal(p.data.cityPickerIndex, 1)
  p.onManualCityChange({ detail: { value: 4 } })
  assert.equal(p.data.city, '杭州市'); assert.equal(p.data.cityPickerIndex, 4); assert.equal(store.home_city_last, '杭州市')
  assert.equal(requests.length, 0, '游客选城不发任何请求'); assert.equal(locations.length, 0)
  token = 'normal-session'; p.getHomeCityLocation()
  assert.equal(p.data.cityPicker, false)
  const wxml = require('fs').readFileSync(require('path').join(__dirname, '../../pages/index/index.wxml'), 'utf8')
  assert.match(wxml, /<view wx:if="\{\{!cityPicker\}\}"[^\n]*<\/view>\s*<cy-dropdown wx:else mode="selector" range="\{\{manualCityOptions\}\}" value="\{\{cityPickerIndex\}\}" panel-width="\{\{160\}\}" bind:change="onManualCityChange"[^>]*>\s*<text class="v3-hero-mem-loc">· \{\{city\}\}<\/text>/)
  // 没有昵称的游客那一行不渲染,所以另有一行只放选城下拉
  assert.match(wxml, /<view wx:if="\{\{userInfo\.nickname\}\}" class="v3-hero-mem flex-ac">[\s\S]*?<\/view>\s*<!--[^>]*-->\s*<view wx:elif="\{\{city && cityPicker\}\}" class="v3-hero-guest-city flex-ac">\s*<cy-dropdown mode="selector" range="\{\{manualCityOptions\}\}" value="\{\{cityPickerIndex\}\}" panel-width="\{\{160\}\}" bind:change="onManualCityChange"/)
})
