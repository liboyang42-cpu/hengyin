'use strict'
// 1119 验证单 B 道（MC-* / OR-*）源码合同：逐条钉住业主 2026-11-17 批准的小程序改动。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..', '..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('MC-004 / MC-043:俱乐部结算直连账本只给主理人,余额未知不再单摆「—」', () => {
  const wxml = read('pages/club/settlement/index.wxml')
  assert.match(wxml, /<cy-club-direct-ledger class="section" wx:if="\{\{summary && summary\.isOwner && clubId\}\}"/)
  assert.doesNotMatch(wxml, /: '—'\}\}<\/text>/)
})

test('MC-005:俱乐部报名页场次净额改名「实收减退款」', () => {
  const wxml = read('pages/club/enroll/index.wxml')
  assert.match(wxml, /实收减退款 \{\{m\.netText\}\}/)
  assert.doesNotMatch(wxml, /净额 \{\{m\.netText\}\}/)
})

test('MC-015:核销行金额未成立按 MC-011=B 显示 ¥0.00,不再回落「待定」', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /NO_CASH_SETTLEMENT' \? null : '¥0\.00'/)
  assert.doesNotMatch(js, /: '待定'\)/)
})

test('MC-019:台账三张列表 pageNum 递增触底加载', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /onReachBottom\(\) \{/)
  assert.match(js, /const pageNum = this\._pageState\[key\]\.pageNum \+ 1;/)
  assert.match(js, /this\._setPageState\('redemptions', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('entries', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('batches', 1, res\.data\)/)
  assert.match(read('pages/merchant/ledger/index.wxml'), /wx:if="\{\{loadingMore\}\}"[^>]*>正在加载更多…/)
})

test('MC-020:核销记录超出首批 1000 条不静默截断,计数标「+」并触底续读', () => {
  const vmod = require('vm')
  const requests = []
  let def = null
  const ctx = {
    getApp: () => ({ globalData: {}, sendRequest: (o) => requests.push(o) }),
    Page: (d) => { def = d },
    require: (rel) => {
      if (rel.endsWith('page-motion.js')) return { enter() {} }
      if (rel.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
      if (rel.endsWith('toast.js')) return () => {}
      if (rel.startsWith('./')) return require(path.join(ROOT, 'pages/merchant/verify-records', rel))
      return require(path.join(ROOT, 'pages/merchant/verify-records', rel))
    },
    wx: { getWindowInfo: () => ({}), stopPullDownRefresh() {} },
    JSON, Number, Array, Object, String, Math,
  }
  vmod.runInNewContext(read('pages/merchant/verify-records/index.js'), ctx)
  const page = Object.assign({ data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data, p) } }, def)
  page._merchantAccess = { canReadVerifyRecords: true }
  page.loadList()
  const rows = (n, base) => Array.from({ length: n }, (_, i) => ({ recordId: String(base + i), occurredAt: '2026-11-02 10:00:00' }))
  for (let p = 1; p <= 20; p += 1) {
    requests[p - 1].success({ code: 200, data: { total: 1030, rows: rows(50, p * 100) } })
  }
  assert.equal(requests.length, 20)
  assert.match(page.data.tabs[0].label, /1000\+$/)
  page.onReachBottom()
  assert.equal(JSON.parse(requests[20].data).pageNum, 21)
  requests[20].success({ code: 200, data: { total: 1030, rows: rows(30, 9000) } })
  assert.match(page.data.tabs[0].label, /1030$/)
  page.onReachBottom()
  assert.equal(requests.length, 21, '读完不再续读')
})

test('MC-021:合作中心列表非空时刷新失败轻提示,不再无声', () => {
  const js = read('pages/merchant/coop-center/index.js')
  assert.match(js, /_toastRefreshFailed\(kind\) \{/)
  assert.equal((js.match(/this\._toastRefreshFailed\('routes'\)/g) || []).length, 2)
  assert.equal((js.match(/this\._toastRefreshFailed\('events'\)/g) || []).length, 2)
})

test('MC-029:俱乐部详情切后台/卸载时冲刷待发的移出成员请求,且只发一次', () => {
  const js = read('pages/club/detail/index.js')
  assert.match(js, /onHide\(\) \{[^\n]*const pending = this\._pendingRemoval; this\._pendingRemoval = null; if \(typeof pending === 'function'\) pending\(\);/)
  assert.match(js, /onUnload\(\) \{[^\n]*if \(typeof pendingRemoval === 'function'\) pendingRemoval\(\);/)
  const rm = js.slice(js.indexOf('  removeMember(e) {'), js.indexOf('  _doRemoveMember('))
  assert.match(rm, /if \(done\) return;\s*done = true;/)
  assert.match(rm, /onUndo\(\) \{ done = true;/)
})

test('MC-024:商家首页章节回退名不再拼原始编号', () => {
  assert.doesNotMatch(read('pages/merchant/index/index.js'), /name: '章节 ' \+ id/)
})

test('MC-036:模板页发布能力卡失败不再吞掉,置 capabilityError,点发布补拉仍失败给提示', () => {
  const js = read('pages/template/index.js')
  assert.doesNotMatch(js, /fail\(\) \{ \/\* 能力卡失败不阻断页面 \*\/ \}/)
  assert.match(js, /that\.setData\(\{ capabilityError: true \}\);/)
  assert.match(js, /if \(!this\._pubLoaded\) this\.getPublishHome\(true\);/)
})

test('MC-037:自由探索有下限时滑杆下显示地板行', () => {
  assert.match(read('pages/topic/pricing/index.wxml'), /<view class="pg-range" wx:if="\{\{!isFreeExplore \|\| floorPositive\}\}">/)
  assert.match(read('pages/topic/pricing/index.js'), /floorPositive: floor > 0,/)
})

test('MC-038:俱乐部主办方视角 17 页根节点浅色并归商家域;申请页仍是玩家深色', () => {
  const lint = require(path.join(ROOT, 'scripts/ui-theme-unit-lint.js'))
  const pages = ['badges', 'checkin-detail', 'create', 'customer-detail', 'customers', 'edit', 'enroll', 'event-ops',
    'governance', 'group-code', 'join-requests', 'notify', 'payout-receiver', 'roles', 'settlement', 'topic-detail', 'workbench']
  for (const p of pages) {
    const route = `pages/club/${p}/index`
    const themes = lint.topLevelThemeClasses(read(route + '.wxml'))
    assert.equal(themes.has('theme-merchant'), true, route)
    assert.equal(themes.has('theme-dark'), false, route)
    assert.equal(lint.classifyPageDomain(route), 'merchant', route)
    const json = JSON.parse(read(route + '.json'))
    assert.equal(json.navigationBarTextStyle, 'black', route)
    assert.equal(json.backgroundColor, '#F3F4F4', route)
  }
  assert.equal(lint.classifyPageDomain('pages/club/apply/index'), 'player')
  assert.equal(lint.topLevelThemeClasses(read('pages/club/apply/index.wxml')).has('theme-dark'), true)
})

test('MC-042 / MC-044 / MC-045:俱乐部页去重复说明、去原始编号与权威纪元', () => {
  assert.doesNotMatch(read('pages/club/checkin-detail/index.wxml'), /class="ckd-note">已退款/)
  assert.doesNotMatch(read('pages/club/event-ops/index.wxml'), /主题 #/)
  assert.match(read('pages/club/event-ops/index.wxml'), /\{\{editingTopicName\}\}<text>已锁定<\/text>/)
  assert.doesNotMatch(read('pages/club/roles/index.js'), /'场次 #'/)
  assert.doesNotMatch(read('pages/club/topic-detail/director.js'), /'章节 #'|权威 '/)
  assert.doesNotMatch(read('pages/club/detail/index.js'), /'主题 #' \+ item\.topicId/)
  assert.doesNotMatch(read('pages/club/detail/index.wxml'), /期次 #/)
  assert.doesNotMatch(read('pages/merchant/reviews/index.wxml'), /回复结果待确认：\{\{replyRequestId\}\}/)
  assert.doesNotMatch(read('pages/merchant/customer/detail/index.wxml'), /备注 #\{\{correctsNoteId\}\}/)
})

test('MC-058:编辑系列时默认带队人按 leadOptions 反查下拉位置', () => {
  const js = read('pages/club/event-ops/index.js')
  assert.match(js, /const leadIndex = that\.data\.leadOptions\.findIndex\(item => item\.memberId === series\.leadMemberId\)/)
  assert.match(js, /leadIndex: Math\.max\(0, leadIndex\),/)
})

test('MC-046:按清单删除辅助说明', () => {
  assert.doesNotMatch(read('pages/club/components/cy/scene-club-edit/index.wxml'), /只能改展示信息/)
  assert.doesNotMatch(read('pages/merchant/reviews/index.wxml'), /举报已提交审核/)
  for (const f of ['pages/merchant/marketing/index.wxml', 'pages/merchant/marketing/index.js', 'pages/merchant/predict/index.wxml',
    'pages/merchant/decor/index.js', 'subpackageMember/components/cy/scene-route-content/index.wxml',
    'pages/merchant/verify-records/index.wxml', 'pages/merchant/crm-campaigns/index.wxml']) {
    if (fs.existsSync(path.join(ROOT, f))) assert.doesNotMatch(read(f), /请联系店主开通/, f)
  }
  assert.doesNotMatch(read('pages/merchant/decor/perks/index.wxml'), /· 仅自己可见/)
})

test('MC-047 / MC-048 / MC-049:删审核时效句与退款截止句,占位括号改全角', () => {
  assert.doesNotMatch(read('pages/merchant/apply/index.wxml'), /个工作日完成审核/)
  assert.doesNotMatch(read('pages/club/topic-detail/index.wxml'), /退款截止为集合前 24 小时/)
  const detail = read('pages/club/detail/index.wxml')
  assert.match(detail, /俱乐部风格（选填）/)
  assert.match(detail, /时长（分钟）/)
  assert.doesNotMatch(detail, /\(选填\)|\(分钟\)/)
})

test('MC-050 / MC-059:管理员保存不发运营配置,地址不随城市写回', () => {
  const edit = read('pages/club/components/cy/scene-club-edit/index.js')
  assert.doesNotMatch(edit, /address: d\.city/)
  assert.doesNotMatch(read('pages/club/create/index.js'), /address: d\.city/)
  assert.match(edit, /if \(d\.isOwner\) \{\s*payload\.operationConfigUpdated = true;/)
  assert.match(edit, /if \(d\.joinPolicySupported\) payload\.joinPolicy = d\.joinPolicy;\s*\}/)
})

test('MC-052:俱乐部申请承接带 clubId,后端按其归属校验', () => {
  assert.match(read('pages/club/detail/index.js'), /url: '\/api\/coop\/pool\/apply'[\s\S]{0,80}jsonBody\(\{ topicId: topicId, clubId: that\.data\.clubId, message/)
})

test('MC-055:俱乐部详情/名册/编辑优先用服务端 viewerCanGovern,缺省沿用旧判据', () => {
  assert.match(read('pages/club/detail/index.js'), /if \(c && typeof c\.viewerCanGovern === 'boolean'\) return c\.viewerCanGovern;\s*return !!\(c && \(c\.isOwner \|\| c\.viewerIsAdmin\)\);/)
  assert.match(read('pages/club/enroll/index.js'), /typeof club\.viewerCanGovern === 'boolean' \? club\.viewerCanGovern : \(club\.isOwner \|\| club\.viewerIsAdmin\)/)
  assert.match(read('pages/club/components/cy/scene-club-edit/index.js'), /typeof c\.viewerCanGovern === 'boolean' \? !c\.viewerCanGovern :/)
})

test('MC-056:「当前岗位没有客户查看权限」也判为无权限', () => {
  for (const f of ['pages/club/customers/index.js', 'pages/club/customer-detail/index.js']) {
    const src = read(f)
    const m = src.match(/return code === 403 \|\| (\/[^\n]+?\/)\.test/)
    assert.ok(m, f)
    const re = eval(m[1]) // eslint-disable-line no-eval
    assert.ok(re.test('当前岗位没有客户查看权限'), f)
    assert.ok(!re.test('客户名单暂时不可用'), f)
  }
})

test('MC-057:客户列表按页请求,触底续读,服务端不分页时去重即停', () => {
  const js = read('pages/club/customers/index.js')
  assert.match(js, /pageNum, pageSize: PAGE_SIZE \}\)/)
  assert.match(js, /onReachBottom\(\) \{/)
  assert.match(js, /const fresh = shaped\.items\.filter\(\(item\) => !seen\.has\(item\.memberId\)\);/)
})

test('MC-063:客户累计金额与成员价优先展示服务端格式化文本', () => {
  assert.match(read('pages/club/customers/view-model.js'), /text\(raw\.summary\.paidAmountText\) \|\| `¥\$\{trimMoney\(amount\)\}`/)
  assert.match(read('pages/club/detail/index.js'), /'成员价 ' \+ c\.memberDiscountPriceText\.trim\(\)/)
})

test('OR-020:自玩结算未勾选同意时同意行高亮,不再单独出提示文字行', () => {
  const wxml = read('pages/topic/components/cy/self-play-checkout/index.wxml')
  assert.doesNotMatch(wxml, /ck__hint/)
  assert.match(wxml, /<cy-consent-check checked="\{\{consentChecked\}\}" alert="\{\{!!consentHint && !consentChecked\}\}"/)
  assert.match(read('components/cy/consent-check/index.wxml'), /cck--alert/)
  assert.match(read('components/cy/consent-check/index.js'), /alert: \{ type: Boolean, value: false \}/)
})

test('OR-001:通行证/商品详情/通行证列表三页不再整页登录闸,写动作走 ensureLoginFor', () => {
  for (const p of ['membership', 'pass', 'passes']) {
    assert.doesNotMatch(read(`subpackageMember/market/${p}/index.wxml`), /cy-access-gate/, p)
    assert.doesNotMatch(read(`subpackageMember/market/${p}/index.json`), /cy-access-gate/, p)
  }
  assert.match(read('subpackageMember/market/pass-order/index.wxml'), /cy-access-gate kind="login"/)
  assert.match(read('subpackageMember/market/membership/index.js'), /onBuy\(\) \{\s*if \(app\.ensureLoginFor/)
})

test('OR-006 / OR-007 / OR-008 / OR-010 / OR-017 / OR-048:通行证订单详情', () => {
  const js = read('subpackageMember/market/pass-order/index.js')
  const wxml = read('subpackageMember/market/pass-order/index.wxml')
  assert.match(js, /if \(!st \|\| st === 'PENDING'\) cyToast\('已提交退款申请'\)/)
  assert.match(js, /subscribe\.request\(\['refund'\]\)/)
  assert.match(js, /dangerKey: 'pass\.order\.cancel'/)
  assert.match(js, /'\/cancel', method: 'POST'/)
  assert.match(js, /r\.state === 'closed'\) cyToast\(pp\.WORDS\.CLOSED\)/)
  assert.doesNotMatch(wxml, /\{\{row\.key\}\}/)
  assert.match(wxml, /复制订单号/)
  assert.match(wxml, /row\.refundDetail\.kind === 'refunded'/)
  assert.match(wxml, /联系城瘾小助手/)
  assert.match(read('utils/danger-actions.js'), /'pass\.order\.cancel': \{/)
})

test('OR-018 / OR-026 / OR-047 / OR-048:通行证视图词表与定稿文案', () => {
  const pp = require(path.join(ROOT, 'subpackageMember/utils/platform-pass.js'))
  assert.equal(pp.WORDS.PENDING, '待付款')
  assert.equal(pp.WORDS.PAY_CANCELLED, '已取消本次付款')
  assert.equal(pp.WORDS.CONFIRMING, '付款结果确认中')
  const row = pp.orderRow({ orderNo: 'PPO9', planCode: 'MONTH', validDays: 30, priceFen: 3900, status: 'REFUNDED', paidTime: '2026-11-01 10:00:00',
    refund: { reviewStatus: 'APPROVED', refundedFen: 3900, decidedTime: '2026-11-03 10:00:00' } })
  assert.equal(row.name, '城瘾30天通行证')
  assert.equal(row.sub, '30天 · 2026.11.01')
  assert.deepEqual(row.refundDetail, { kind: 'refunded', amount: '¥39', time: '2026.11.03' })
  const rej = pp.orderRow({ orderNo: 'PPO8', planCode: 'MONTH', status: 'PAID', refund: { reviewStatus: 'REJECTED', decision: '已使用超过 3 个主题' } })
  assert.equal(rej.refundDetail.reason, '已使用超过 3 个主题')
  const ov = { enabled: true, plans: [{ planCode: 'MONTH', priceFen: 3900, validDays: 30 }], channels: { xpay: { available: true } } }
  assert.equal(pp.passView(ov, '', 'ios', true).buyLabel, '开通通行证 ¥39')
  assert.equal(pp.passView(ov, '', 'ios', true).plans[0].price, '首发价 ¥39')
  const wxml = read('subpackageMember/market/membership/index.wxml')
  assert.match(wxml, /title="城瘾30天通行证"/)
  assert.doesNotMatch(wxml, /持证 ¥0/)
})

test('OR-019 / OR-024:续买放底栏,开通按钮上方固定同意须知', () => {
  const wxml = read('subpackageMember/market/membership/index.wxml')
  const foot = wxml.slice(wxml.indexOf('<view class="pp__foot"'))
  assert.match(foot, /<cy-consent-check[^>]*text="已阅读并同意购买须知：不自动续费、退款为整单"/)
  assert.ok(foot.indexOf('cy-consent-check') < foot.indexOf('bindtap="onBuy"'))
  assert.doesNotMatch(wxml, /pp__renew/)
  assert.match(read('subpackageMember/market/membership/index.js'), /if \(!this\.data\.agreed\) \{ this\.setData\(\{ agreeAlert: true \}\); return \}/)
})

test('OR-009:商品购买区分支付结果,取消直接提示,按单号回查', () => {
  const js = read('subpackageMember/market/pass/index.js')
  assert.match(js, /success: \(\) => this\._verify\(order\.orderNo, 0\)/)
  assert.match(js, /data: \{ limit: 20, orderNo \}/)
  assert.doesNotMatch(js, /complete: \(\) => this\._verify/)
})

test('OR-021 / OR-029~035 / OR-044 / OR-045:我的订单列表', () => {
  const dir = 'subpackageMember/components/cy/scene-member-order-history/'
  const js = read(dir + 'index.js')
  const wxml = read(dir + 'index.wxml')
  const wxss = read(dir + 'index.wxss')
  assert.match(wxml, /money\.amount\(item\.registrationStatus==1 \? item\.pendingPayAmount : item\.actualPaidAmount\)/)
  assert.match(wxml, /wx:if="\{\{item\.registrationStatus==1 \? item\.pendingPayAmount !== null : true\}\}"/)
  assert.match(js, /hasMore: Number\.isFinite\(total\) && pageSize > 0 && rows\.length >= pageSize && that\._allRows\.length < total,/)
  assert.match(js, /if \(that\.data\.isPaying \|\| that\.data\.verifyingPayment\) return/)
  assert.match(js, /const list = filterOrderTab\(this\._allRows, key\)/)
  const paid = js.slice(js.indexOf('    _updateOrderPaid(id, index) {'))
  assert.match(paid, /this\.getList\(false, true\)/)
  assert.match(js, /toast\(payFailText\(res\), \{ duration: 2000 \}\)/)
  assert.match(wxml, /wx:elif="\{\{item\.cmsTopic!=null\}\}"/)
  assert.match(wxml, />已下架</)
  assert.doesNotMatch(wxml, /已支付订单不受影响/)
  assert.doesNotMatch(js, /\.\.\.'\)/)
  assert.equal((wxss.match(/\.orderbox_li_bot_left \.moh-amount\{/g) || []).length, 1)
  assert.doesNotMatch(wxml, /<text class="order-mode-tag">活动<\/text>/)
})

test('OR-036:订单详情未登录时结果面板给「登录」并重读同一单', () => {
  const dir = 'subpackageMember/components/cy/scene-member-order-detail/'
  assert.match(read(dir + 'index.wxml'), /primary-text="\{\{loginRequired \? '登录' : ''\}\}" bind:primary="onLoginRetry"/)
  assert.match(read(dir + 'index.js'), /retryLogin\(this, app, function \(\) \{ this\.setData\(\{ loginRequired: false \}\); this\.getData\(orderId\); \}\);/)
})

test('OR-022:退款三档文案单一真源 utils/refund-tiers.js,主题页 refund-rule 只转出,报名结算页引用', () => {
  const rule = require(path.join(ROOT, 'utils/refund-tiers.js'))
  assert.equal(require(path.join(ROOT, 'pages/topic/utils/refund-rule.js')), rule)
  assert.equal(rule.refundRuleText({ price: 1, mode: 1 }), '集合前 24 小时以上全额退；24 小时内退 50%；开始后不退')
  assert.deepEqual(rule.refundRuleTiers({ price: 0 }), [])
  assert.doesNotMatch(read('pages/activity/baoming/baoming.wxml'), /<text>全额退<\/text>/)
})

test('OR-003:自玩建单带报价签名,价变 409 重新报价', () => {
  const js = read('pages/topic/index/index.js')
  assert.match(js, /if \(quoted && quoted\.quoteSign\) body\.quoteSign = quoted\.quoteSign;/)
  assert.match(js, /if \(res && res\.code == 409 && body\.quoteSign\) \{\s*that\._selfPlayReqId = null;\s*that\._refreshCheckoutQuote\(\);/)
})

test('OR-028 / OR-046:结算层可玩期读报价 validTo、须知入口、已购态与不补零价格', () => {
  const { checkoutView } = require(path.join(ROOT, 'pages/topic/utils/checkout-view.js'))
  const v = checkoutView({ info: { selfPlayPrice: 29.9 }, quoteState: 'ready', quote: { payAmount: 29.9, validToText: '12/31' }, win: { untilText: '01/09' } })
  assert.equal(v.single.price, '¥29.9')
  assert.equal(v.payLabel, '支付 ¥29.9')
  assert.equal(v.single.line, '可玩至 12/31')
  assert.equal(v.notice.label, '购买须知与退款规则')
  const fallback = checkoutView({ info: { selfPlayPrice: 30 }, quoteState: 'ready', quote: { payAmount: 30 }, win: { untilText: '01/09' } })
  assert.equal(fallback.single.line, '可玩至 01/09', '报价没给 validTo 时回落本地计算')
  const owned = checkoutView({ info: { selfPlayPrice: 30, isSignUp: 1 }, quoteState: 'ready', quote: { payAmount: 30 } })
  assert.equal(owned.single.title, '已购买本主题')
  assert.equal(owned.payLabel, '继续游玩')
  assert.match(read('pages/topic/components/cy/self-play-checkout/index.js'), /if \(v\.purchased\) \{ this\.triggerEvent\('play'\); return; \}/)
  assert.match(read('pages/topic/index/index.wxml'), /bind:notice="onCheckoutNotice" bind:play="onCheckoutPlay"/)
})

test('OR-014 / OR-038:退出账号走 clearSession、重置身份广播、游客不出退出按钮、设备级偏好保留', () => {
  const js = read('pages/shezhi/shezhi.js')
  assert.match(js, /app\.getSession\(\)\.clearSession\(\);/)
  assert.match(js, /sync\.resetIdentity\(\)/)
  assert.match(read('pages/shezhi/shezhi.wxml'), /<view class="sz-logout" wx:if="\{\{hasAccount\}\}">/)
  const { DEVICE_LEVEL_KEYS } = require(path.join(ROOT, 'utils/roam-player-memory.js'))
  for (const key of ['cy_usage_choice_v1', 'play_reduced_motion', 'roam_reduced_motion']) assert.ok(DEVICE_LEVEL_KEYS.has(key), key)
  const sync = require(path.join(ROOT, 'utils/identity-sync.js'))
  const seen = []
  const off = sync.onIdentityChange((evt) => seen.push(evt.scope))
  sync.resetIdentity()
  off()
  assert.deepEqual(seen, ['session-cleared'])
})

test('OR-014:游玩轨迹与主页背景按账号分键', () => {
  assert.match(read('pages/play/utils/play-trail.js'), /STORE_KEY \+ ':' \+ id/)
  for (const f of ['pages/gerenziliao/gerenziliao.js', 'subpackageMember/components/cy/profile/index.js']) {
    const src = read(f)
    assert.match(src, /'member_hero_bg:' \+ id/, f)
    assert.doesNotMatch(src, /StorageSync\('member_hero_bg'/, f)
  }
})

test('OR-015:注销进入冷静期后清本机会话(服务端已 revokeAll)', () => {
  const js = read('utils/deregister-flow.js')
  assert.equal((js.match(/clearLocalSessionAfterPending\(\);/g) || []).length, 2)
  assert.match(js, /app\.getSession\(\)\.clearSession\(\)/)
})

test('OR-023:系统会话统一称「城瘾小助手」,只读提示「城瘾小助手不接收回复」', () => {
  const chat = read('subpackageB/pages/im/chat/index.js') + read('subpackageB/pages/im/chat/index.wxml')
  assert.doesNotMatch(chat, /'系统通知|>系统通知<|"系统通知"/)
  assert.match(chat, /城瘾小助手不接收回复/)
  assert.match(read('subpackageB/pages/im/list/index.js'), /c\.type == 2 \? '城瘾小助手'/)
  assert.match(read('pages/merchant/utils/inbox-preview.js'), /type == 2 \? '城瘾小助手'/)
})

test('OR-037:写判定词表补 confirm/orders/logout,通行证写接口显式 write:true', () => {
  const src = read('utils/transport/request-client.js')
  const m = src.match(/var WRITE_TOKENS = \[([\s\S]*?)\];/)
  for (const t of ['confirm', 'orders', 'logout']) assert.match(m[1], new RegExp(`'${t}'`), t)
  const pay = read('subpackageMember/utils/platform-pass-pay.js')
  assert.equal((pay.match(/method: 'POST', write: true/g) || []).length, 3)
})

test('OR-040:关于页有「隐私保护指引」入口,协议页显示版本号', () => {
  const about = read('pages/shezhi/about/index.wxml')
  assert.match(about, /title="隐私保护指引" description="" bind:tap="openPrivacyContract"/)
  assert.match(read('pages/shezhi/about/index.js'), /wx\.openPrivacyContract\(/)
  assert.match(read('pages/agreement/index.wxml'), /版本 \{\{doc\.version\}\} · 生效日期：\{\{doc\.updatedAt\}\}/)
})

test('MC-044:台账退款行不露退款单号/编号', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.doesNotMatch(js, /'#' \+ row\.refundId/)
  assert.doesNotMatch(js, /row\.refundNoText \|\|/)
})
