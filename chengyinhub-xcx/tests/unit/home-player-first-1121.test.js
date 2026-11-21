// 1121 N1:首页先给玩家看玩的入口;问候只在玩家起过自己的昵称时带名字。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const { homeGreeting, isPlaceholderNickname } = require('../../utils/index/home-greeting.js')
const { buildHomeActionBanners } = require('../../utils/index/home-action-banner.js')

test('轮播第一张是报名活动,成为俱乐部 / 成为商家排在末尾', () => {
  const ids = buildHomeActionBanners().map((b) => b.id)
  assert.equal(ids[0], 'home-action-activity')
  assert.deepEqual(ids.slice(-2).sort(), ['home-action-club', 'home-action-merchant'])
})

test('问候:自己的昵称带名字,游客与默认昵称只说「你好」', () => {
  assert.equal(homeGreeting({ nickname: '阿城' }), '你好，阿城')
  assert.equal(homeGreeting({ nickname: '  阿城 ' }), '你好，阿城')
  for (const nick of ['', null, undefined, '城瘾用户', '微信用户', '旅人', '用户123']) {
    assert.equal(homeGreeting({ nickname: nick }), '你好', String(nick))
  }
  assert.equal(homeGreeting(null), '你好')
  assert.equal(isPlaceholderNickname('用户甲'), false, '只把系统生成的「用户+数字」当默认')
})

test('首页 wxml 用计算后的问候,不再写死「旅人」与感叹号', () => {
  const wxml = read('pages/index/index.wxml')
  assert.match(wxml, /<text class="v3-hero-hi">\{\{heroGreeting\}\}<\/text>/)
  assert.doesNotMatch(wxml, /旅人|城瘾用户/)
  const js = read('pages/index/index.js')
  // 每处换 userInfo 都同步问候:初始 data、登出清空、改昵称事件、拉到资料
  assert.ok((js.match(/heroGreeting: homeGreeting\(/g) || []).length >= 4)
})
