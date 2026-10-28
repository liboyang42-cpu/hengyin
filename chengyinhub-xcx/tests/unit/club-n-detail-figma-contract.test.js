'use strict'

// 俱乐部详情 N 系列对稿契约(Figma s7SEFaoJ3GQUIxJhdqcFUb)
//   N1b 47:2 帖子 · N3 20:137 概览 · N5 24:175 概览(普通会员) · N6 24:314 概览(未加入)
//   N2-A 282:350 活动空态 · N2-B 282:499 活动有活动 · 活动卡 281:360 · PostCard 211:270
// 断言一律钉结构(行首选择器 / 行首属性 / 元素与 wx:if 的相邻关系),不钉解释性注释。

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

// 取某个选择器的规则体(允许同名多条,拼起来一起看)
function rule(source, selector) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '')
  const bodies = []
  for (const m of clean.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    if (m[1].split(',').map((x) => x.trim()).includes(selector)) bodies.push(m[2])
  }
  assert.ok(bodies.length, `缺少规则 ${selector}`)
  return bodies.join('\n')
}

function assertContract(overrides = {}) {
  const wxml = overrides.wxml === undefined ? read('pages/club/detail/index.wxml') : overrides.wxml
  const wxss = overrides.wxss === undefined ? read('pages/club/detail/index.wxss') : overrides.wxss
  const js = overrides.js === undefined ? read('pages/club/detail/index.js') : overrides.js

  // —— Shell(六屏共同,Figma 93:9 / 93:18 / 93:20 / 93:22)——
  assert.match(rule(wxss, '.cover-wrap'), /height:\s*320rpx/, '封面 160px = 320rpx')
  assert.match(rule(wxss, '.profile-logo-wrap'), /width:\s*112rpx/, 'logo 56px = 112rpx')
  assert.match(rule(wxss, '.profile-logo-wrap'), /height:\s*112rpx/)
  // 拍板 2026-10-22:logo 进身份行做第一列,名字与 logo 垂直居中(取代原 pl70 让位 + 底对齐)
  assert.match(rule(wxss, '.profile-id'), /align-items:\s*center/, '名字与 logo 垂直居中')
  assert.match(wxml, /class="profile-id">\s*<view class="profile-logo-wrap">/, 'logo 是身份行第一列')
  assert.match(rule(wxss, '.profile-name'), /text-overflow:\s*ellipsis/, '俱乐部名单行省略')
  // 元信息是「图标 + 文字」chip,不是一行 · 拼接的纯文本
  assert.match(rule(wxss, '.profile-meta'), /display:\s*flex/)
  assert.match(rule(wxss, '.profile-meta-chip'), /display:\s*flex/)
  // 拍板 2026-10-22:元信息固定两行 ——「类型 · N 名成员」/ 入会方式,各带原 chip 图标。
  // 2026-10-27 P-17 曾合成一行;2026-10-28 业主(p108)「间距调错了,退回去」⇒ 回到两行,「退出」留在入会方式那行最右。
  assert.equal((wxml.match(/class="profile-meta-chip"/g) || []).length, 2, '元信息两行两枚 chip')
  assert.equal((wxml.match(/class="profile-meta-ico"/g) || []).length, 2, '每枚 chip 必须带图标')
  assert.match(rule(wxss, '.profile-meta'), /flex-direction:\s*column/, '两行固定换行,不随宽度乱折')
  assert.match(wxml, /class="profile-quit"[^>]*bindtap="onQuit"[^>]*>退出</, '「退出」在元信息行内')
  assert.match(rule(wxss, '.profile-quit'), /margin-left:\s*auto/, '「退出」靠最右')

  // —— 概览:简介直接铺开,标题下不要小标题 ——
  assert.match(wxml, /class="ov-intro"/)
  // 钉结构而非字面量:小标题的载体是 .overview-label,注释里出现「俱乐部简介」四个字不算回潮。
  assert.doesNotMatch(wxml, /class="overview-label"/, '全局规约:标题下不要小标题')

  // —— Facts 三宫格(23:4)——
  assert.match(rule(wxss, '.ov-facts'), /display:\s*flex/)
  assert.equal((wxml.match(/class="ov-fact"/g) || []).length, 3, '主理人 / 俱乐部类型 / 所在城市 三格')
  assert.match(wxml, /class="ov-fact-value"[\s\S]{0,200}class="ov-fact-label"/, '值在上、标签在下')
  // 2026-10-27 P-16 owner 更正(原型批注,取代 L05 「去掉灰底」的旧读法):三格灰色圆角块与
  //   「俱乐部勋章」灰条保持 master 原样;中间一格「等级」改为「俱乐部类型」,与头部元信息行同源。
  assert.match(rule(wxss, '.ov-fact'), /border-radius:\s*var\(--cy-radius-lg\)/, '三格保留圆角块')
  assert.match(rule(wxss, '.ov-fact'), /background:\s*var\(--cy-color-bg-surface-strong\)/, '三格保留灰底')
  assert.match(rule(wxss, '.ov-badge-entry'), /border-radius:\s*var\(--cy-radius-lg\)/, '俱乐部勋章保留圆角灰条')
  assert.match(rule(wxss, '.ov-badge-entry'), /background:\s*var\(--cy-color-bg-surface-strong\)/, '俱乐部勋章保留灰底')
  assert.match(wxml, /<text class="ov-fact-value">\{\{club\.clubTypeText \|\| '—'\}\}<\/text>\s*<text class="ov-fact-label">俱乐部类型<\/text>/, '中间一格是俱乐部类型')
  assert.doesNotMatch(wxml, /class="ov-fact-label">等级</, '三格里不再有「等级」')
  assert.match(js, /club\.metaLine = club\.clubTypeText \+/, '头部元信息行与第二格同用 clubTypeText')
  // 2026-10-27 P-19 owner:治理与安全 / 封禁申诉只给主理人和管理员
  assert.match(wxml, /class="overview-section governance-help-entry" wx:if="\{\{club\.isOwner \|\| club\.viewerIsAdmin\}\}"/, '治理与安全只给主理人和管理员')

  // —— MembersHead:「邀请成员 ›」只给主理人/管理员(N5 普通会员那版没有这一行链接)——
  // CU-C-61:载体从 text+aria-role 换成原生 <button open-type="share">,角色判据不变。
  assert.match(wxml, /<button[^>]*wx:if="\{\{club\.isOwner \|\| club\.viewerIsAdmin\}\}"[^>]*class="ov-head-link"/, '邀请成员不能开给普通成员')
  assert.match(wxml, /name="slide-arrow"/, '› 用 slide-arrow,不用 arrow-right(带横杠)')

  // —— 成员横向头像条 + 未加入时的 Locked 卡(23:18 / 25:2 / 25:5)——
  assert.match(wxml, /class="ov-strip"[^>]*wx:if="\{\{memberStrip\.length\}\}"/)
  assert.match(js, /function buildMemberStrip/)
  assert.match(js, /'\+' \+ rest/, '第 5 位是「+N」溢出位')
  assert.equal((wxml.match(/class="ov-locked"/g) || []).length, 2, '成员与榜单各一张 Locked 卡')
  assert.match(wxml, /加入后可看成员名单/)
  assert.match(wxml, /加入后可看贡献榜/)

  // —— 榜单:「综合 ▾」下拉取代 chip tabs,卡容器 + 折叠行 + 我那一行高亮 ——
  assert.doesNotMatch(wxml, /class="rank-tabs"/, '排序已从 chip tabs 收成榜头下拉')
  assert.match(wxml, /catchtap="onRankSortTap"/)
  assert.match(js, /onRankSortTap\(\)\s*\{/)
  assert.match(js, /function foldLeaderboard/)
  assert.match(wxml, /class="ov-lb-gap"[^>]*wx:if="\{\{item\.gap\}\}"/, '「⋯ 中间 N 人」折叠行')
  assert.match(rule(wxss, '.ov-lb'), /background:\s*var\(--cy-color-bg-elevated\)/, '榜单进一张 elevated 卡')
  assert.match(rule(wxss, '.ov-lb-row.is-me'), /background:/, '我那一行要压底色')

  // —— 稿没画但现码有:治理与安全,不许照稿删掉 ——
  // 2026-09-09「会员与会费」按用户裁决(会费暂时不做)随两个会费页一并删除,这条断言撤走。
  assert.doesNotMatch(wxml, /membership-overview-entry/, '会费整条已撤，不许留一个通向已删页的入口')
  assert.match(wxml, /class="overview-section governance-help-entry"/, '治理与安全必须保留')
  assert.match(wxml, /goClubAppeal/)
  assert.match(wxml, /reportClub"/)

  // —— 活动 tab ——
  // 2026-10-27 P-14 owner(真机走查 p90/p107):不分子 tab、不分「即将举行 / 已结束」两段,
  // 已结束的不在这里显示,直接一列活动卡。取代 282:622 / 282:660 两段式断言。
  assert.doesNotMatch(wxml, /endedTopics|ev-sec-head--second/, '已结束的活动不在俱乐部活动列表出现')
  assert.match(js, /function splitTopics[\s\S]{0,300}upcomingTopics: rows\.filter\(\(t\) => !t\.ended\)/)

  // —— 活动卡 N2 20:52:封面通栏 + 状态胶囊叠封面 + 标题/一行元信息 + 三列数字块 ——
  // 2026-09-09 从 281:360 的横卡(左封面 + 右四行)改成稿 N2 的竖卡。
  assert.match(wxml, /<template name="clubEventCard">/)
  // P-14:标签只写时间 +「已开始 / 未开始」(tagText)
  assert.match(wxml, /class="ev-coverslot"[\s\S]{0,300}class="ev-status ev-status--\{\{item\.statusTone\}\}">\{\{item\.tagText\}\}/,
    '状态胶囊叠在封面上，不再是右列第一行')
  assert.match(js, /statusText: '已开始'/)
  assert.match(js, /statusText: '未开始'/)
  assert.match(wxml, /class="ev-name"[\s\S]{0,200}class="ev-time"[\s\S]{0,400}class="ev-stats"/)
  // 三列数字块:算不出来的那一格整格不出,不用 0 冒充(「0 站」会被读成「这条路线没有节点」)
  for (const key of ['signupText', 'stationText', 'mileageText']) {
    assert.match(wxml, new RegExp('class="ev-stat" wx:if="\\{\\{item\\.' + key + '\\}\\}"'),
      key + ' 缺失时那一格必须整格不出')
  }
  assert.match(rule(wxss, '.ev-card'), /background:\s*var\(--cy-color-bg-elevated\)/)
  assert.match(rule(wxss, '.ev-status--live'), /var\(--cy-color-status-success\)/, '已开始 = success')
  assert.match(rule(wxss, '.ev-status--open'), /var\(--cy-color-status-warning\)/, '未开始 = warning')
  assert.match(js, /function topicStatusOf/)
  // 稿没画但现码有:出示团码留在卡内;P-14「举报活动」收进卡片「更多」
  assert.match(wxml, /class="event-group-code"/)
  assert.match(wxml, /class="ev-more" catchtap="onEventMore"/)
  assert.doesNotMatch(wxml, /class="event-report"/)

  // —— 活动空态 282:478:卡容器内 标题 / 说明 / 主按钮 / 归属提示 ——
  // 2026-09-06 裸文字空态收编:标题 / 说明 / 主按钮 / 归属提示 都由 cy-empty 承载(说明里带归属句,cta 只给主理人)
  assert.match(rule(wxss, '.ev-empty'), /background:\s*var\(--cy-color-bg-elevated\)/)   // 空态仍在卡容器内(282:478)
  // P-14:空态文案「暂时俱乐部还没有活动」
  assert.match(wxml, /<cy-empty wx:else class="ev-empty" title="暂时俱乐部还没有活动"[^>]*cta="\{\{club\.isOwner \|\| canManageActivities \? '发布主题' : ''\}\}" bind:cta="onCreateTeam"/)

  // —— 发帖入口 285:395:头像 + 昵称 / 提示语 / 工具栏三图标 ——
  assert.match(wxml, /class="post-composer-who"[\s\S]{0,200}class="post-composer-ph"[\s\S]{0,200}class="post-composer-tools"/)
  assert.equal((wxml.match(/class="post-composer-ico[ "]/g) || []).length, 3, '工具栏三枚图标')
}

test('俱乐部详情 N 系列按 Figma 落地:Shell 尺寸、概览三段、活动分段与活动卡', () => {
  assertContract()
})

test('负控:封面高度退回 240rpx 判红', () => {
  const wxss = read('pages/club/detail/index.wxss')
  const mutated = wxss.replace(/(\.cover-wrap\s*\{[\s\S]*?)height:\s*320rpx/, '$1height: 240rpx')
  assert.notEqual(mutated, wxss, '负控锚点失配:.cover-wrap 里已经没有 height: 320rpx')
  assert.throws(() => assertContract({ wxss: mutated }), /320rpx/)
})

test('负控:「邀请成员」放开给普通成员判红', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const mutated = wxml.replace('wx:if="{{club.isOwner || club.viewerIsAdmin}}" class="ov-head-link"', 'wx:if="{{true}}" class="ov-head-link"')
  assert.notEqual(mutated, wxml, '负控锚点失配:邀请成员的角色判据已改写')
  assert.throws(() => assertContract({ wxml: mutated }), /邀请成员/)
})

test('负控:照稿删掉「治理与安全」判红', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const mutated = wxml.replace('class="overview-section governance-help-entry"', 'class="overview-section governance-help-entry-removed"')
  assert.notEqual(mutated, wxml, '负控锚点失配:治理与安全区块 class 已改名')
  assert.throws(() => assertContract({ wxml: mutated }), /治理与安全/)
})

// 2026-10-27 P-14:原负控「不再分两段时判红」按 owner 决定翻转 —— 已结束段回潮时判红。
test('负控:已结束的活动重新出现在活动 tab 时判红', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const mutated = wxml.replace('<cy-empty wx:else class="ev-empty"', '<block wx:if="{{endedTopics.length}}"></block>\n        <cy-empty wx:else class="ev-empty"')
  assert.notEqual(mutated, wxml, '负控锚点失配:活动空态已改写')
  assert.throws(() => assertContract({ wxml: mutated }), /已结束的活动不在俱乐部活动列表出现/)
})
