const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function assertOrderDetailContract(js, wxml) {
  // 探店日订单必须有自己的字面与集合信息,不得再被标成「经典定向场次」
  assert.match(js, /'探店日'/)
  assert.match(js, /'进入游玩'/)
  assert.match(js, /info\.sessionTimeText/)
  assert.match(js, /info\.meetingPointText/)
  assert.match(js, /openMeetingPoint\(\)/)
  /* F12:订单页不得跳 is_join=1 的主题详情(那个参数会把那一页的底部 CTA 整条藏掉)。
     2026-09-11 按稿 578:2298 删掉快捷入口卡之后,唯一那处 is_join=1 的跳转(goActivity)
     跟着没了 —— 这条契约从「goRoute 别那么跳」升级成**整个组件里一处都不许有**。 */
  assert.doesNotMatch(js, /_leave\('\/pages\/topic\/index\/index\?is_join/)
  // 订单页通往票夹只留一个出口,别再长回两份逐字相同的实现
  assert.doesNotMatch(js, /goRoute\(\)/)
  assert.doesNotMatch(js, /goPlay\(\)/)
  assert.match(js, /goSignInfo\(\) \{/)
  /* 2026-09-11 按稿 578:2298 重排之后,场次时间与日期时间合成了一个 whenText ——
     同一个事实原来在头图、「日期」「时间」「场次」四处各拼一遍。
     这条契约要证的性质没变:探店日的场次时间槽必须真的上到界面,而且来源仍是 sessionTimeText。 */
  assert.match(js, /info\.sessionTimeText \|\| \[eventDate, eventTime\]/)
  assert.match(wxml, /\{\{whenText\}\}/)
  assert.match(wxml, /bindtap="openMeetingPoint"/)
  assert.match(wxml, /meetingPointDisplayText/)
  // 稿 578:2329:集合点那一行的值是蓝色的「外滩源 · 查看」
  assert.match(wxml, /orow-v--link'\}\}">\{\{info\.meetingPointDisplayText\}\}/)
  assert.match(js, /坐标待补充/)
}

function assertWalletContract(wxml, wxss, json) {
  /* 2026-09-18 UI-17 返修(用户二次验收):票夹卡就是一张完整票图,票名/时间/状态/入口
     不再叠在卡面。原「必须能回答这是哪场、几点」的可见文字兑现方式随之取消,
     但语义不丢:票名与状态(待使用/已核验/已取消/待支付)仍在 aria-label 里,读屏可达;
     点击分区仍由 cta 文案分流(见下)。
     ⚠️ wTitle 在缺 cmsTopic/cmsActivity 时给的是「路线信息待同步 / 活动信息待同步」,
        不是空串 —— 2026-08-19 A39 实拍拍到过整片空黑,那会被读成「坏了」。 */
  /* 2026-10-22 业主:票夹「和 HTML 一模一样」(v13)—— 叠放卡组,卡上写票名、类型 · 剩几天、底部入口,
     照片在卡的下半。票名与状态仍保留在 aria-label 里。 */
  assert.match(wxml, /class="wc"/)
  assert.match(wxml, /src="\{\{item\.wImg\}\}"/)
  assert.match(wxml, /class="wc__b">\{\{item\.wTitle\}\}/)
  /* 2026-10-28 业主:点卡翻面出示入场码,卡上不写状态;读屏只读「出示入场码 / 翻回正面」+ 票名 */
  assert.match(wxml, /aria-label="\{\{flipped && index === currentSwiperTicket \? '翻回正面' : '出示入场码'\}\}，\{\{item\.wTitle\}\}"/,
    '票名必须以 aria-label 保留(读屏等价物)')
  /* 2026-10-22 业主:票夹标题放回顶栏居中。仍是悬浮(overlay)导航 —— 不长出实底导航条。 */
  assert.match(wxml, /<cy-nav-bar overlay pill pillDark tint="light" title=""\s*\/>/,
    '全屏主题图上只保留悬浮导航(返回),不得重新长出实底导航条')
  assert.match(wxml, /class="wl-nav__b">票夹</, 'v13:顶栏居中写「票夹」+「N 张可用」')
  assert.match(wxss, /\.signup \.ztbg\s*\{[^}]*pointer-events:\s*none/s)
  assert.match(wxss, /\.davidwu\s*\{[^}]*position:\s*relative;[^}]*z-index:\s*1/s,
    '加载/失败/空态必须浮在主题背景之上')
  assert.match(wxss, /\.deck\s*\{[^}]*width:\s*577rpx;[^}]*height:\s*865rpx/s, 'v13 卡组 300×450')
  assert.match(wxss, /\.team-entry\s*\{[^}]*position:\s*absolute;[^}]*bottom:\s*env\(safe-area-inset-bottom\)/s,
    '队伍入口不得参与票卡居中列的 flex 分配')
  const shortScreen = wxss.slice(wxss.indexOf('@media (max-height: 700px)'))
  assert.match(shortScreen, /\.signup\s*\{[^}]*height:\s*auto;[^}]*min-height:\s*100vh;[^}]*overflow-y:\s*auto/s)
  assert.match(shortScreen, /\.team-entry\s*\{[^}]*position:\s*static/s,
    '短屏容不下固定票卡和队伍入口时必须允许纵向滚动')
  /* 2026-10-28 业主:票夹只放能用的票,进游戏走底部「开始游戏」,卡面不再有 cta / 状态 / 「去支付」 */
  assert.match(wxml, /bindtap="goTicket" data-index="\{\{currentSwiperTicket\}\}" data-go="1"/)
  assert.match(wxml, /<text>开始游戏<\/text>/)
  assert.doesNotMatch(wxml, /去支付|查看详情|wl-tab/, '票夹不放待支付的单,也没有可用/已用/已过期切换')
  assert.equal(json.usingComponents?.['cy-icon'], '/components/cy/icon/index')
}

test('订单票券页:探店日字面/场次/集合点/主行动契约', () => {
  assertOrderDetailContract(
    read('subpackageMember/components/cy/scene-member-order-detail/index.js'),
    read('subpackageMember/components/cy/scene-member-order-detail/index.wxml'))
})

test('票夹卡:一张完整票图,票名/状态只走 aria-label,CTA 文案照实写', () => {
  assertWalletContract(
    read('subpackageMember/signup/index.wxml'),
    read('subpackageMember/signup/index.wxss'),
    JSON.parse(read('subpackageMember/signup/index.json')))
})

/* 负控:把那条 is_join=1 的跳转塞回任何一个方法里,契约都必须变红。
   2026-09-11 起注入点不再是 goRoute(它已随快捷入口卡一起删掉)—— 改成往
   goSignInfo 里塞,证的是「整个组件一处都不许有」而不是「某个方法不许有」。 */
test('mutation 负控:订单页任何一处跳回 is_join 都要变红', () => {
  const js = read('subpackageMember/components/cy/scene-member-order-detail/index.js')
    .replace(/goSignInfo\(\) \{[\s\S]*?\},/, "goSignInfo() {\n      this._leave('/pages/topic/index/index?is_join=1&id=1');\n    },")
  assert.throws(() => assertOrderDetailContract(
    js, read('subpackageMember/components/cy/scene-member-order-detail/index.wxml')), assert.AssertionError)
})

/* 负控:快捷入口卡长回来(两行文案来自后端、点了去票夹)也要变红 —— 它是这一轮按稿删的。 */
test('mutation 负控:快捷入口卡长回来要变红', () => {
  const js = read('subpackageMember/components/cy/scene-member-order-detail/index.js')
    .replace(/goSignInfo\(\) \{/, "goRoute() { this.goSignInfo(); },\n\n    goSignInfo() {")
  assert.throws(() => assertOrderDetailContract(
    js, read('subpackageMember/components/cy/scene-member-order-detail/index.wxml')), assert.AssertionError)
})
