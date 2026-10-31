// L08(2026-10-27 真机走查):我的页 P-47 / P-48 / P-49 / P-50 / P-53 / P-56 / M-14 / M-16 / M-19 / M-25 / M-31
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const WXML = read('subpackageMember/components/cy/profile/index.wxml')
const JS = read('subpackageMember/components/cy/profile/index.js')
const WXSS = read('subpackageMember/components/cy/profile/index.wxss')

test('P-47 / M-14:设置钮玩家深色下为白色;我的订单 / 我的资产只留箭头,不写「查看详情」', () => {
  assert.match(WXSS, /\.pc-player \.pc-nav-ico \{ filter: brightness\(0\) invert\(1\); \}/)
  const projects = WXML.slice(WXML.indexOf('TAB · 项目'), WXML.indexOf('TAB · 推文'))
  assert.doesNotMatch(projects, /<text>查看详情<\/text>/)
  assert.match(projects, /bindtap="goOrder"[^>]*><cy-icon name="arrow-right"/)
  // 2026-10-30 业主:「我的资产」标题旁不要箭头(卡片本身可点进账户收益)
  assert.doesNotMatch(projects, /aria-label="查看资产详情"/)
  assert.match(projects, /class="pc-asset" bindtap="goEarnings"/)
})

// 2026-10-28 p91 业主「卡片太窄了又,稍微高一点」:400×400 → 500×440,渐变不动
test('P-48:订单卡不写票数、不放按钮,只显示主题信息;卡片 500×440', () => {
  assert.doesNotMatch(WXML, /item\.ticketText|item\.actionText|pc-ord-btn/)
  assert.match(WXML, /\{\{item\.kindText \|\| item\.timeText\}\}/)
  assert.match(WXSS, /\.pc-ord-card \{\n\s*position: relative; display: inline-block; flex-shrink: 0; width: 500rpx; height: 440rpx;/)
})

test('P-49:余额没有就是 0,不再显示「—」', () => {
  assert.match(WXML, /\{\{money\.amount\(balanceText == null \? 0 : balanceText\)\}\}/)
  assert.doesNotMatch(WXML, /pc-asset__value--empty/)
})

test('P-50:玩家视角不出 BIZ 徽章', () => {
  assert.match(WXML, /<roleBadge role="\{\{!isMerchantView && userInfo\.role === 'merchant' \? '' : \(userInfo\.role \|\| ''\)\}\}" \/>/)
})

test('P-53:探索与更多只留我的优惠券 + 邀请好友,同当前权益的列表行', () => {
  const sec = WXML.slice(WXML.indexOf('>探索与更多<'), WXML.indexOf('>当前权益<'))
  assert.match(sec, /<view class="pc-benefits" wx:if="\{\{!isMerchantView\}\}">/)
  assert.equal((sec.match(/class="pc-benefit"/g) || []).length, 2)
  assert.match(sec, /bindtap="goCoupon"[\s\S]*我的优惠券/)
  assert.match(sec, /bindtap="goInvite"[\s\S]*邀请好友/)
  assert.doesNotMatch(sec, /goBecomeTalent|goComplaint|goEarnings|pc-entries/)
})

test('M-16 / M-19:本人商家视角去掉核销旁的「···」;自己看自己的主页不出关注与发起合作', () => {
  assert.match(WXML, /<view class="pc-more" wx:if="\{\{!isSelf && !viewingOwn\}\}"/)
  assert.match(WXML, /wx:if="\{\{\(!isSelf && !viewingOwn\) \|\| \(isSelf && isMerchantView\)\}\}" bindtap="onPrimaryCta"/)
  assert.match(WXML, /wx:if="\{\{isMerchantView && !isSelf && !viewingOwn && canInviteCoop\}\}" class="pc-coop-bar"/)
  assert.match(JS, /viewingOwn: !!String\(app\.getUserID\(\) \|\| ''\) && String\(app\.getUserID\(\) \|\| ''\) === userId/)
})

test('M-25:商家视角不出我的订单与票夹,我的项目 = 承接进度,订单入口改去我的项目', () => {
  assert.match(WXML, /<block wx:if="\{\{!isMerchantView && \(orderPreviewList\.length \|\| orderErr\)\}\}">/)
  assert.match(WXML, /class="pc-primary pc-ticket" wx:if="\{\{isSelf && !isMerchantView\}\}" bindtap="goSignup"/)
  assert.match(JS, /req\('\/api\/merchant\/chapter-application\/mine', \{\}\)/)
  assert.match(JS, /goOrder: function \(\) \{ if \(this\.data\.isMerchantView\) \{ this\.goProject\(\); return; \}/)
  assert.match(JS, /'\/subpackageA\/pages\/myproject\/index' \+ \(this\.data\.isMerchantView \? '\?scope=MERCHANT' : ''\)/)
})

test('M-31:入驻审核通过后品牌中心不再出「入驻资料」', () => {
  assert.match(read('pages/merchant/decor/index.wxml'), /<view class="dc-card" wx:if="\{\{!merchantAccess\.active\}\}">\s*<cy-cell wx:if="\{\{merchantAccess\.canWriteProfile\}\}" arrow-icon="chevron-right" title="入驻资料"/)
})

test('P-56:手机号已绑定也有修改图标,走微信手机号授权换绑', () => {
  const wxml = read('pages/gerenziliao/gerenziliao.wxml')
  // 2026-10-30 P-2:手机号是分组列表里整行可点的一行(原生 button 承载 getPhoneNumber),行尾 ›
  assert.match(wxml, /<button class="rv-row rv-row--btn" open-type="getPhoneNumber" bindgetphonenumber="getPhoneNumber" aria-label="\{\{userInfo\.phone \? '更换手机号' : '绑定手机号'\}\}"/)
})
