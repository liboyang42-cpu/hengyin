'use strict'
// 2026-11-03 第九轮 m1 第 9 项:地址一点开地图的接线、店铺分享码落地、微信头像与昵称填写
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.')
    let cursor = target
    for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]]
    cursor[parts.at(-1)] = value
  })
}

function loadProfileEdit(appOverrides) {
  let config
  const requests = []
  const app = Object.assign({
    globalData: {}, getUserID: () => 1, tips() {}, getImgUrl: (p) => p,
    sendRequest(req) { requests.push(req) },
  }, appOverrides || {})
  global.getApp = () => app
  global.Page = (value) => { config = value }
  global.wx = { showToast() {}, showLoading() {}, hideLoading() {}, navigateBack() {} }
  delete require.cache[require.resolve('../../pages/gerenziliao/gerenziliao.js')]
  require('../../pages/gerenziliao/gerenziliao.js')
  const page = Object.assign({}, config)
  page.data = JSON.parse(JSON.stringify(config.data))
  page.setData = (patch, cb) => { applyPatch(page.data, patch); if (cb) cb() }
  return { page, requests, app }
}

test('资料页:微信头像选择器回来的临时图走同一条裁剪、上传链路(paths 直传,不再开相册)', () => {
  let chooseArgs
  const { page } = loadProfileEdit({ chooseImage(cb, count, options) { chooseArgs = { cb, count, options } } })
  page.onChooseAvatar({ detail: { avatarUrl: 'wxfile://tmp_wechat_avatar.jpg' } })
  assert.deepEqual(chooseArgs.options.paths, ['wxfile://tmp_wechat_avatar.jpg'])
  assert.equal(chooseArgs.options.crop, true)
  assert.equal(chooseArgs.options.cropScale, '1:1')
  chooseArgs.options.onUploadStart(['wxfile://tmp_wechat_avatar.jpg'])
  assert.equal(page.data.avatarSrc, 'wxfile://tmp_wechat_avatar.jpg')
  chooseArgs = null
  page.onChooseAvatar({ detail: {} })
  assert.equal(chooseArgs, null, '取消选择不上传')
  page.uploadPic()
  assert.equal(chooseArgs.options.paths, undefined, '旧入口照常开相册')
})

test('资料页:头像两处都是 open-type="chooseAvatar",昵称输入框是 type="nickname"', () => {
  const wxml = read('pages/gerenziliao/gerenziliao.wxml')
  assert.equal((wxml.match(/open-type="chooseAvatar" bindchooseavatar="onChooseAvatar"/g) || []).length, 2)
  assert.doesNotMatch(wxml, /bindtap="uploadPic"/)
  assert.match(wxml, /<cy-field class="profile-name-field" input-type="nickname"/)
  assert.match(read('components/cy/field/index.wxml'), /class="fd__input" type="\{\{inputType\}\}"/)
})

test('cy-field:type=nickname 时失焦带回的微信昵称也算一次输入', () => {
  let def
  vm.runInNewContext(read('components/cy/field/index.js'), { Component: (d) => { def = d } })
  const events = []
  const comp = Object.assign({}, def.methods, {
    data: Object.assign({}, { inputType: 'nickname', value: '', showCount: false, disabled: false, readonly: false }),
    setData(p) { Object.assign(this.data, p) },
    triggerEvent(name, detail) { events.push([name, detail]) },
  })
  comp.onBlur({ detail: { value: '微信昵称' } })
  assert.equal(comp.data.value, '微信昵称')
  assert.deepEqual(events.map((e) => e[0]), ['input', 'blur'])
  comp.data.inputType = 'text'
  comp.onBlur({ detail: { value: '别的' } })
  assert.equal(comp.data.value, '微信昵称', '普通输入框失焦不改值')
})

test('app.chooseImage:给 paths 时不开相册,直接裁剪', () => {
  const src = read('app.js')
  assert.match(src, /if \(Array\.isArray\(options\.paths\)\) \{ usePaths\(/)
})

test('店铺码 scene sh.<会员ID>:识别、进待处理、首页转商家主页', () => {
  const door = require('../../utils/game-door-entry.js')
  assert.equal(door.parseShopScene('sh.201'), '201')
  assert.equal(door.parseShopScene(encodeURIComponent('sh.201')), '201')
  assert.equal(door.parseShopScene('sh.x'), null)
  assert.equal(door.parseShopScene('tp.201'), null)
  assert.equal(door.captureDoorScene({ scene: 1047, query: { scene: 'sh.201' } }, null), 'sh.201')
  assert.equal(door.captureDoorScene({ scene: 1001, query: { scene: 'sh.201' } }, null), null)
  const index = read('pages/index/index.js')
  assert.match(index, /const shopId = !activityId && \(parseShopScene\(options && options\.scene\) \|\| parseShopScene\(app\.pendingDoorScene\)\);/)
  // l-misc:队伍码排在店铺码之后,店铺码落点不变
  assert.match(index, /shopId \? '\/pages\/userinfo\/userinfo\?userId=' \+ shopId \+ '&tab=about' :/)
})

test('商家主页:分享按钮开通用分享层(店铺卡 + 店铺码),好友分享落商家主页', () => {
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(wxml, /bindtap="openShopShare"[^>]*aria-label="分享店铺"/)
  assert.match(wxml, /<cy-share-card wx:if="\{\{subjectIsMerchant\}\}" show="\{\{shopShareShow\}\}"/)
  const card = read('components/cy/share-card/index.js')
  assert.match(card, /url: '\/api\/merchant\/share-code', method: 'POST', hideLoading: true, data: \{ memberId: id \}/)
  assert.match(read('components/cy/share-card/index.wxml'), /open-type="share" data-share-kind="card"/)
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /res\.target\.dataset\.shareKind === 'card'/)
})

test('附近商家与漫游发现:商家卡带坐标并打开「导航」', () => {
  assert.match(read('pages/coop/nearby/index.wxml'), /latitude="\{\{item\.latitude\}\}" longitude="\{\{item\.longitude\}\}" nav="\{\{true\}\}"/)
  assert.match(read('pages/play/components/scene-roam-discover/index.wxml'), /latitude="\{\{item\.lat\}\}" longitude="\{\{item\.lng\}\}" nav="\{\{true\}\}"/)
  const card = read('components/cy/merchant-card/index.wxml')
  assert.match(card, /wx:if="\{\{nav && _geo\}\}" class="mc__nav" catchtap="onAddress"/)
  assert.match(card, /wx:if="\{\{address && _geo\}\}" class="photo-card__detail cy-addr-tap" catchtap="onAddress"/)
})

test('商家卡:有坐标时地址与导航调 wx.openLocation;没坐标不给点', () => {
  let def
  const opened = []
  const sandbox = {
    Component: (d) => { def = d },
    require: (p) => require(path.resolve(ROOT, 'components/cy/merchant-card', p)),
    isFinite,
  }
  global.wx = { openLocation: (o) => opened.push(o) }
  vm.runInNewContext(read('components/cy/merchant-card/index.js'), Object.assign(sandbox, { wx: global.wx }))
  const comp = Object.assign({}, def.methods, {
    data: { latitude: '31.2', longitude: '121.4', name: '店', address: '路 1 号', _geo: false },
    setData(p) { Object.assign(this.data, p) },
  })
  def.observers['latitude, longitude'].call(comp, '31.2', '121.4')
  assert.equal(comp.data._geo, true)
  comp.onAddress()
  return Promise.resolve().then(() => {
    assert.equal(opened.length, 1)
    assert.equal(opened[0].latitude, 31.2)
    def.observers['latitude, longitude'].call(comp, null, null)
    assert.equal(comp.data._geo, false)
  })
})

test('钱包里的券:门店地址可点,有坐标出「导航」', () => {
  const wxml = read('subpackageMember/components/cy/scene-game-coupon-wallet/index.wxml')
  assert.match(wxml, /class="wallet-card__addr cy-addr-tap" catchtap="openShop"/)
  assert.match(wxml, /wx:if="\{\{item\._shopNav\}\}" class="wallet-card__nav cy-addr-tap" catchtap="openShop"/)
})

test('编辑器改地点:选点器从原来那个点打开(pickLocation 透传 GCJ-02 坐标)', () => {
  const calls = []
  const lm = require('../../utils/location/location-manager.js')
  lm.pickLocation({ latitude: '31.2', longitude: '121.4', onPick() {} }, { wx: { chooseLocation: (o) => calls.push(o) }, toast() {} })
  lm.pickLocation({ latitude: '', longitude: '', onPick() {} }, { wx: { chooseLocation: (o) => calls.push(o) }, toast() {} })
  assert.equal(calls[0].latitude, 31.2)
  assert.equal(calls[0].longitude, 121.4)
  assert.equal('latitude' in calls[1], false, '没选过点就从当前位置开')
})

test('点地址开地图:各页面都走 utils/open-address.js', () => {
  const files = [
    'pages/searchmap/index.js', 'subpackageA/pages/myproject/index.js', 'pages/activity/baoming/baoming.js',
    'pages/club/detail/index.js', 'pages/club/topic-story/index.js', 'pages/index/index.js',
    'pages/topic/merchantinfo/merchantinfo.js', 'pages/topic/merchantapply/index.js',
    'pages/topic/components/project-join/index.js', 'pages/topic/components/project-host/index.js',
    'pages/merchant/apply/index.js', 'pages/merchant/decor/index.js', 'pages/merchant/citynode/create/index.js',
    'pages/publish/simple/index.js',
    'pages/topic/components/cy/chapter-node-form/index.js', 'pages/play/components/playkit-dailysign/index.js',
    'subpackageMember/components/cy/scene-member-order-history/index.js', 'subpackageMember/components/cy/scene-game-coupon-wallet/index.js',
    'subpackageRoam/nearby/index.js', 'subpackageRoam/official-route/index.js',
    'subpackageB/pages/im/chat/index.js', 'components/cy/merchant-card/index.js',
  ]
  files.forEach((f) => assert.match(read(f), /require\('[./]+utils\/open-address\.js'\)/, f))
})
