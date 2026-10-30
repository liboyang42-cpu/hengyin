// L08(2026-10-27 真机走查 G-5 / M-08 / M-10 / M-11 / M-13 / M-17 / M-27):头像、名称单一来源 + 保存后全局同步
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function loadSync(app) {
  global.getApp = () => app
  delete require.cache[require.resolve('../../utils/identity-sync.js')]
  const mod = require('../../utils/identity-sync.js')
  mod._resetForTest()
  return mod
}

test('publishIdentity:白名单字段 + 非空才广播;epoch 递增并写入 globalData;玩家头像昵称回写会话', () => {
  const calls = []
  const app = { globalData: {}, setAvatar: (v) => calls.push(['avatar', v]), setNickname: (v) => calls.push(['nickname', v]) }
  const sync = loadSync(app)
  const events = []
  const off = sync.onIdentityChange((e) => events.push(e))
  assert.equal(sync.publishIdentity('merchant', { logo: '', foo: 'x' }), 0, '空 Logo / 非白名单字段不广播')
  assert.equal(sync.publishIdentity('merchant', { logo: 'https://x/l.png', phone: '1' }), 1)
  assert.deepEqual(events[0].patch, { logo: 'https://x/l.png' })
  assert.equal(app.globalData.identityEpoch, 1)
  assert.deepEqual(sync.latestIdentity('merchant'), { logo: 'https://x/l.png' })
  sync.publishIdentity('member', { nickname: '小林', avatar: 'a.png' })
  assert.deepEqual(calls, [['avatar', 'a.png'], ['nickname', '小林']])
  off()
  sync.publishIdentity('merchant', { coverImage: 'c.jpg' })
  assert.equal(events.length, 2, '取消订阅后不再收到')
  assert.equal(sync.identityEpoch(), 3)
})

test('resolveSelfIdentity:商家视角只认商家名 / Logo,不借会员昵称与个人头像', () => {
  const sync = loadSync({ globalData: {} })
  assert.deepEqual(sync.resolveSelfIdentity(true, { name: '星巴克咖啡厅', logo: '' }, { nickname: '城市运动装备馆', avatar: 'me.png' }),
    { name: '星巴克咖啡厅', avatar: '', initial: '星' })
  assert.equal(sync.resolveSelfIdentity(false, { name: '店' }, { nickname: '小林' }).name, '小林')
  assert.equal(sync.resolveSelfIdentity(false, null, {}).avatar, '/images/d_profile.png')
})

test('写入方:品牌中心 Logo/封面/店名保存成功广播;个人资料保存成功广播;封面显式 image_5_3;Logo 圆形取景', () => {
  const decor = read('pages/merchant/decor/index.js')
  assert.equal((decor.match(/identitySync\.publishIdentity\('merchant', stablePatch\)/g) || []).length, 2, 'saveDecor + saveBrand 两条保存都广播')
  assert.match(decor, /cropShape: key === 'logo' \? 'circle' : ''/)
  assert.match(decor, /bizType: key === 'logo' \? 'image_1_1' : 'image_5_3'/)
  const profileEdit = read('pages/gerenziliao/gerenziliao.js')
  assert.match(profileEdit, /identitySync\.publishIdentity\('member', \{ nickname: data\.name, avatar: data\.avatar \}\)/)
  const app = read('app.js')
  assert.match(app, /cropAll: function \(paths, ratio, shape\)/)
  assert.match(app, /that\.cropAll\(paths, ratio, options\.cropShape\)/)
  const crop = read('pages/crop/index.js')
  assert.match(crop, /round: !!\(d && d\.shape === 'circle'\)/)
  // 2026-10-30 P-5(M-09):框外整圈压暗,圆形取景就是圆洞(同一遮罩加 --round)
  assert.match(read('pages/crop/index.wxml'), /<view class="crop__mask \{\{round \? 'crop__mask--round' : ''\}\}"><\/view>/)
})

test('消费方:我的页按视角取名称与头像,商家背景读店铺封面;商家首页头像无描边并订阅广播', () => {
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(wxml, /<view class="pc-gamer-name">\{\{_displayName\}\}<\/view>/)
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /identitySync\.onIdentityChange\(this\.onIdentityChanged\.bind\(this\)\)/)
  assert.match(js, /loadSelfMerchant: function[\s\S]*?req\('\/api\/merchant\/info'/)
  assert.match(js, /that\._applySelfMerchant\(next\)/)
  assert.match(js, /heroImg: \(next && next\.coverImage\) \|\| this\.data\.heroImg/, 'M-17:背景 = 店铺封面')
  const wxss = read('subpackageMember/components/cy/profile/index.wxss')
  assert.match(wxss, /\.pc-merchant \.pc-avatar \{ border: none; \}/)
  const home = read('pages/merchant/index/index.wxml')
  assert.doesNotMatch(home, /rv-hero-avatar"[^>]*bordered/, 'M-27:商家首页头像不要描边')
  const homeJs = read('pages/merchant/index/index.js')
  assert.match(homeJs, /identitySync\.onIdentityChange\(/)
  assert.match(homeJs, /const displayName = info\.name \|\| '商家';/, '名称只认商家名')
})
