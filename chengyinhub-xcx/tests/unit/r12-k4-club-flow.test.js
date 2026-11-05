// 第十二轮 k4-club-flow:j2 第二轮 N-01(带条款邀约可补发)、N-02(商家看得出要接受站点)、N-03(名册)、N-04(开局拒绝原因位置)、N-05
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const { attachMerchantGameEntries } = require('../../pages/merchant/utils/game-session-merchant.js')
const { buildInboxPreview } = require('../../pages/merchant/utils/inbox-preview.js')

test('N-02:场次里有站待接受邀请的卡标「待接受邀请」、排在最前,时间到分钟', () => {
  const host = { key: 'host-1', role: 'host', bizType: 'topic', id: 5, ownerId: 5, title: '主办主题' }
  const cards = attachMerchantGameEntries([host], [
    { activityId: 8, topicId: 900, activityName: '已就绪的场', stationCount: 1, invitedCount: 0, startAt: '2026-11-05 09:00:00' },
    { activityId: 9, topicId: 901, activityName: '沪上慢走 · 第一场', stationCount: 1, invitedCount: 1, startAt: '2026-11-05 11:14:37' },
  ], {})
  assert.deepEqual(cards.map((c) => c.title), ['沪上慢走 · 第一场', '主办主题', '已就绪的场'])
  assert.equal(cards[0].statusLabel, '待接受邀请')
  assert.equal(cards[0].statusTone, 'fix')
  assert.equal(cards[0].meta, '2026-11-05 11:14')
  assert.equal(cards[2].statusLabel, '')
})

test('N-02:「我的消息」里的站点邀请按现在排序、时间原样、点开直达本站页', () => {
  const now = Date.parse('2026-11-05T10:00:00+08:00')
  const items = buildInboxPreview({ events: [
    { type: 'income', label: '收入到账', content: '活动分润 +¥1', time: '11-05 09:30' },
    { type: 'stationInvite', label: '站点邀请', content: '「第一场」有 1 个站点待接受邀请', time: '11-05 11:14', gameActivityId: 9 },
  ] }, { now })
  assert.equal(items[0].title, '站点邀请')
  assert.equal(items[0].time, '11-05 11:14')
  assert.equal(items[0].gameActivityId, 9)
  assert.equal(items[1].gameActivityId, 0)
  const js = read('pages/merchant/index/index.js')
  assert.match(js, /item\.kind === 'event' && item\.gameActivityId\) \{[\s\S]{0,200}game-node\/index\?activityId=' \+ item\.gameActivityId/)
})

test('N-01:招商弹窗「已确认」里没发邀约的商家有「发邀约」,标签说清卡在哪一步', () => {
  const wxml = read('pages/topic/components/project-host/index.wxml')
  assert.match(wxml, /wx:if="\{\{ item\.inviteState === 'none' && !item\.isSelf \}\}" catchtap="emit" data-act="sendTermsInviteRow" data-id="\{\{ item\.registrationId \}\}"[^>]*>发邀约<\/view>/)
  const js = read('pages/topic/merchantinfo/merchantinfo.js')
  assert.match(js, /sendTermsInviteRow: 1,/)
  assert.match(js, /m\.inviteState === 'none' \? '未发邀约' : \(m\.inviteState === 'sent' \? '邀约待接受' : '已中标'\)/)
  const coopWxml = read('pages/coop/list/index.wxml')
  assert.match(coopWxml, /wx:elif="\{\{item\.audit===1 && item\.inviteState==='none'\}\}"[\s\S]{0,80}catchtap="goTermsInvite"/)
})

test('N-01:俱乐部「现在要做」在有站没合作单时说「发邀约」,写站名,点开进招商弹窗', () => {
  const js = read('pages/club/detail/index.js')
  assert.match(js, /contract: \{ label: '发邀约', hint: '' \}/)
  assert.match(js, /uncontractedStations\.join\('、'\) \+ ' 还没有合作单，不能开售'/)
  assert.match(js, /stage === 'recruit' \|\| stage === 'price' \|\| stage === 'contract'\) && this\._stageTopic/)
})

test('N-03:名册行点开就是报名与退款详情;不再显示下线的「募集中」;有人报名时不列空模板票', () => {
  const wxml = read('pages/club/enroll/index.wxml')
  assert.match(wxml, /<view class="reg-l" bindtap="goCheckinDetail" data-regid="\{\{r\.id\}\}"/)
  assert.doesNotMatch(wxml, /teamStatusText/)
  assert.match(wxml, /wx:for-item="tk" wx:if="\{\{!tk\.hideEmpty\}\}"/)
  const js = read('pages/club/enroll/index.js')
  assert.match(js, /tk\.hideEmpty = anySignup && !\(tk\.signups > 0\)/)
})
