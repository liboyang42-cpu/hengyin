'use strict'

// 2026-08-20 商家卡/俱乐部卡重设计契约(Figma 定稿 → 小程序落地)。
// 锁的都是「不渲染比渲染难」的边界闸:verified=0 禁 0.0km、level=0 禁 Lv.0、
// null 与 0 区分、无服务整行隐藏、三态按钮。这些闸一旦松掉,界面不报错只说谎。

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

// ---------- 商家工作台项目卡(稿 3910:580) ----------

test('工作台项目卡:承接签按确认关系判,主办签按真实档期判,类目跟在状态后', () => {
  const { buildProjectCards } = require('../../utils/merchant-workbench.js')
  const cards = buildProjectCards({
    today: '2026-08-20',
    joinList: [
      { id: 1, topicId: 1, topicName: 'A', status: 1, mode: 2, startDate: '2026-08-19', endDate: '2026-08-21' },
      { id: 2, topicId: 2, topicName: 'B', status: 1, mode: 1, startDate: '2026-09-01' },
      { id: 3, topicId: 3, topicName: 'C' },
      { id: 4, topicId: 4, topicName: 'D', status: 0, startDate: '2026-08-19', endDate: '2026-08-21' },
    ],
    hostList: [{ id: 9, bizType: 'activity', titleText: 'X', startTime: '2026-08-01', endTime: '2026-08-02' }],
    todoByProject: [],
  })
  assert.equal(cards[0].statusLabel, '承接中')
  assert.equal(cards[0].catLabel, '自由探索')
  assert.equal(cards[1].statusLabel, '承接中', '未来档期的已确认关系仍是承接中')
  assert.equal(cards[1].catLabel, '城市定向')
  assert.equal(cards[2].statusLabel, '', '确认关系与档期未知不许编一个状态出来')
  assert.equal(cards[3].statusLabel, '', '未确认关系不能仅按进行中档期冒充承接中')
  assert.equal(cards[4].statusLabel, '已结束')
  assert.equal(cards[4].catLabel, '活动')
  const wxml = read('pages/merchant/index/index.wxml')
  assert.match(wxml, /rv-project-status[\s\S]*?rv-project-cat/, '类目签必须跟在状态签后面')
  assert.match(wxml, /rv-project-line1[\s\S]*?<\/view>[\s\S]*?rv-project-card-title/, '标题在签行下独占一行')
})

// ---------- 商家主页发起合作 ----------

test('商家版主页:无成就 tab / tab 无整条底线 / 白卡退场文字落底', () => {
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /tab\.key !== 'achievements'/, '商家版必须滤掉成就 tab')
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  // 2026-08-20 解冲突:master(#753)那侧按「用户定不要那条白线」改成一律关掉,
  // 不再按身份区分;本分支原来的 !isMerchantView 让位给它。
  assert.match(wxml, /hairline="\{\{false\}\}"/, 'tab 整条底线一律关掉')
  const wxss = read('subpackageMember/components/cy/profile/index.wxss')
  assert.match(wxss, /\.pc-merchant \.pc-card \{ background: transparent; border: none/)
  const tabsWxss = read('components/cy/tabs/index.wxss')
  // 同一功能两边并行实现,修饰类名以已合入 master 的 --noline 为准(本分支原叫 --flat)
  assert.match(tabsWxss, /\.cy-tabs--noline \{[^}]*border-bottom: none/)
})

test('商家合作页:帖子/俱乐部(2026-10-22 起不列商家),俱乐部使用商家合作标签', () => {
  const wxml = read('pages/merchant/relation/index.wxml')
  assert.doesNotMatch(wxml, /<cy-merchant-card|goMerchant/, '商家不得看到其他商家')
  assert.match(wxml, /<cy-post-card[\s\S]*?light="\{\{true\}\}"/)
  assert.match(wxml, /<cy-club-card club="\{\{item\}\}" audience="merchant"/)
  assert.doesNotMatch(wxml, /<cy-cell/, '旧 cy-cell 行不许残留')
  const json = JSON.parse(read('pages/merchant/relation/index.json'))
  assert.equal(json.usingComponents['cy-club-card'], '/components/cy/club-card/index')
  assert.equal(json.usingComponents['cy-cell'], undefined, '孤儿注册一并清掉')
})

test('商家主页:访客(非商家)看商家档案时底部有「发起合作」,主页内打开邀约弹窗', () => {
  const wxml = read('subpackageMember/components/cy/profile/index.wxml')
  // 2026-09-16 拍板 #13:带主题上下文的商家观看者(商家主办去邀商家承接)也渲染这颗 CTA。
  // 2026-10-27 owner 真机走查 M-19:自己看自己的主页(预览)不出「发起合作」⇒ 条件多一个 !viewingOwn
  assert.match(wxml, /isMerchantView && !isSelf && !viewingOwn && canInviteCoop\}\}" class="pc-coop-bar"/)
  assert.match(wxml, />发起合作</)
  const js = read('subpackageMember/components/cy/profile/index.js')
  assert.match(js, /coopInviteShow: true/)
  assert.match(wxml, /invite-type="\{\{0\}\}"/)
  const wxss = read('subpackageMember/components/cy/profile/index.wxss')
  assert.match(wxss, /\.pc-coop-bar\s*\{[^}]*env\(safe-area-inset-bottom\)/s)
})
