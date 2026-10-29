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
  value.setData=(patch,done)=>{Object.assign(value.data,patch);if(done)done()}
  return value
}
function latest(p,row=consent(),opts) {
  p.getHomeCityLocation(opts)
  const r=requests.find(x=>x.url==='/api/compliance/consents/latest')
  r.success({code:200,data:row});return r
}
const COLD_TOAST='暂时无法自动识别城市，请手动选择'
test('V-01 冷启动/onShow 自动定位失败不弹城市提示，露出手动选择',()=>{
  const p=page();latest(p);locations[0].success({latitude:31.23,longitude:121.47})
  requests.find(x=>x.url==='/api/map/reverse-geocode').success({code:200,data:{city:'',manualInputRequired:true,reason:'MAP_UNAVAILABLE'}})
  assert.deepEqual(toasts,[]);assert.equal(p.data.cityManual,true)
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
  assert.match(wxml,/class="v3-hero-mem-loc" catchtap="onTapHomeCity">· \{\{city\}\}/)
})
