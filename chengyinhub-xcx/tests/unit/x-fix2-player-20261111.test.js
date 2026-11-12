// x-fix2(2026-11-11 验收问题单 ACCEPT-ISSUES-20261111 玩家侧)回归
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

test('P-1 已截止主题:头图按钮只讲「已截止」,不再给报名/自玩/解锁入口', () => {
  const m = require('../../pages/topic/utils/topic-motion.js')
  const now = '2026-11-11 12:00:00'
  assert.deepEqual(m.buildHeroCta({ lifecycle: 0, endDate: '2026-10-13' }, { now }), { kind: 'closed', text: '已截止' })
  assert.deepEqual(m.buildHeroCta({ selfPlay: 1, endDate: '2026-12-01', saleEndAt: '2026-11-01 00:00:00' }, { now, priceText: '¥9' }), { kind: 'closed', text: '已截止' })
  assert.equal(m.buildHeroCta({ endDate: '2026-12-01' }, { now }).kind, 'signup', '未截止照旧报名')
  assert.equal(m.buildHeroCta({ endDate: '2026-10-13' }, { now, isJoin: 1 }).kind, 'joined', '已购仍去票夹')
  assert.equal(m.buildHeroCta({ lifecycle: 1, endDate: '2026-10-13' }, { now }).kind, 'locked')
  assert.equal(m.topicSaleClosed({}, now), false, '没有截止事实不算截止')
  const wxml = read('pages/topic/index/index.wxml')
  assert.match(wxml, /class="hero-cta \{\{heroCta\.kind === 'locked' \|\| heroCta\.kind === 'closed' \? 'is-off'/)
  assert.match(wxml, /topic-story-paywall__cta" wx:if="\{\{heroCta\.kind !== 'closed'\}\}"/)
  assert.match(read('pages/topic/index/index.js'), /if \(kind === 'closed'\) return toast\('主题已截止'\)/)
})

test('P-2 确认订单/购买弹层:不再把门票写成「通行证」;没有可买票种时不落到自玩结账', () => {
  const { checkoutView } = require('../../pages/topic/utils/checkout-view.js')
  const v = checkoutView({ info: { selfPlayPrice: 39 }, quoteState: 'ready', quote: { payAmount: 39 } })
  assert.equal(v.single.title, '自玩门票')
  assert.deepEqual(v.bill.map((r) => r.k), ['自玩门票 × 1'])
  const m = require('../../pages/topic/utils/topic-motion.js')
  assert.equal(m.buyOptions({ selfPlay: 1, selfPlayPrice: 9 })[0].name, '自玩门票')
  assert.deepEqual(m.buyOptions({ selfPlay: 0, activityList: [] }), [], '城市定向无场次:没有票种')
  const js = read('pages/topic/index/index.js')
  assert.match(js, /if \(!opt\) return; if \(opt\.kind === 'pass'\)/)
  const visible = noComments(read('pages/topic/index/index.wxml'))
  assert.doesNotMatch(visible, /自玩通行证|主题通行证/)
  assert.doesNotMatch(read('pages/topic/components/cy/pass-reveal/index.js'), /value: '自玩通行证'/)
})

test('L-1 收集册(勋章/邮票/藏品)玩家侧一律深色:不再挂 theme-light 逃生口,状态栏白字', () => {
  for (const p of ['badge-wall', 'stamp-album', 'object-cards']) {
    const base = 'subpackageP3/pages/' + p + '/index/index'
    assert.doesNotMatch(noComments(read(base + '.wxml')), /theme-light/, p)
    assert.doesNotMatch(read(base + '.wxss'), /^\.theme-light /m, p)
    const json = JSON.parse(read(base + '.json'))
    assert.equal(json.navigationBarTextStyle, 'white', p)
    assert.notEqual(String(json.backgroundColor).toLowerCase(), '#ffffff', p)
  }
  assert.doesNotMatch(read('subpackageP3/components/album-aura/index.wxss'), /#FAFBFD/i)
  assert.doesNotMatch(read('subpackageP3/components/album-intro/index.wxss'), /#FAFBFD/i)
  assert.doesNotMatch(read('subpackageP3/pages/object-cards/index/index.wxss'), /#FAFBFD/i)
})

test('M-2 勋章墙:单枚图片失败只降级这一枚(徽记/星标),重新载入时清空', () => {
  const wxml = read('subpackageP3/pages/badge-wall/index/index.wxml')
  assert.match(wxml, /wx:if="\{\{item\.iconUrl && !iconBroken\[item\._idx\]\}\}"[^>]*binderror="onIconError"/)
  assert.match(wxml, /<cy-icon wx:else name="star" size="64" \/>/)
  const js = read('subpackageP3/pages/badge-wall/index/index.js')
  assert.match(js, /badges: list, iconBroken: \{\},/)
  // 行为:把 onIconError 拿出来跑一次
  const src = js.match(/  onIconError\(e\) \{[\s\S]*?\n  \},/)[0]
  const fn = new Function('return {' + src + '}')().onIconError
  const page = { data: { iconBroken: {} }, setData(d) { for (const k of Object.keys(d)) { const [, i] = k.split('.'); this.data.iconBroken[i] = d[k] } } }
  fn.call(page, { currentTarget: { dataset: { idx: 3 } } })
  assert.deepEqual(page.data.iconBroken, { 3: true })
  fn.call(page, { currentTarget: { dataset: {} } })
  assert.deepEqual(page.data.iconBroken, { 3: true })
})

test('P-3/P-4 游玩工具菜单:我的权益按商家计数;各站奖励有图标和箭头', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<text class="dt">我的权益<\/text><text class="ds">\{\{merchantCount\}\} 家 · 已核销 \{\{merchantDoneCount\}\} 家<\/text>/)
  assert.doesNotMatch(wxml, /\{\{total\}\} 家 · 已核销/)
  assert.match(wxml, /aria-label="各站奖励"[^>]*><view class="dic pic pic--gift"><\/view><view class="dcopy"><text class="dt">各站奖励<\/text><\/view><view class="darr"><\/view><\/view>/)
  const js = read('pages/play/index.js')
  assert.match(js, /const merchantDoneCount = new Set\(visibleNodes\.filter\(\(node\) => Number\(node\.merchantId\) > 0 && node\.done\)/)
  assert.match(js, /merchantCount, merchantDoneCount, seatTiles/)
})

test('P-5/P-7 站点卡:营业态缺失时不留孤零零的圆点;有角标时章节名下移', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<block wx:elif="\{\{hero\.node\.openText\}\}">\s*<view class="fx-tile__dot"/)
  assert.match(wxml, /class="fx-tile__tags \{\{item\.crowded \|\| item\.statusText \? 'fx-tile__tags--below' : ''\}\}"/)
  assert.match(read('pages/play/index.wxss'), /\.fx-tile__tags--below\{ top:68rpx; \}/)
})

test('P-6 卡包首屏与故事开场都有返回', () => {
  const wxml = read('pages/play/index.wxml')
  const backs = wxml.match(/<view class="play-back"[^>]*bindtap="goBackDetail"[^>]*aria-label="返回"/g) || []
  assert.equal(backs.length, 2)
  assert.match(wxml, /class="play-back" wx:if="\{\{!packOpen && !isPreview\}\}"/)
  assert.match(read('pages/play/index.js'), /goBackDetail\(\) \{/)
})

test('P-8 地点卡大字「开放」只放时段,说明文字不挤进数字位', () => {
  const { sheetFacts } = require('../../pages/play/utils/play-stage-pops.js')
  assert.equal(sheetFacts({ businessTime: '10:00-22:00' }, '').hoursText, '10–22')
  assert.equal(sheetFacts({ businessTime: '09:30 至 21:30' }, '').hoursText, '09:30–21:30')
  assert.equal(sheetFacts({ businessTime: '公共区域观察；以现场通行和开放时间为准' }, '').hoursText, '')
  assert.equal(sheetFacts({ businessTime: '00:00-23:59' }, '').hoursText, '')
})

test('L-3 章节号 / 章节印字形取样失败时退回内置点阵数字与「章」字点阵,不再只剩两圈橙点', () => {
  const src = read('pages/play/components/chapter-flip/index.js')
  const block = src.match(/const DIGITS = [\s\S]*?\nfunction digitPoints[\s\S]*?\n}\n/)[0]
  const digitPoints = new Function(block + '; return digitPoints;')()
  const pts = digitPoints('01', 330, 260, 11)
  assert.ok(pts.length > 20, '两位数字应落出足够多的点')
  for (const p of pts) { assert.ok(p.x >= 0 && p.x < 330 && p.y >= 0 && p.y < 260); assert.equal(p.x % 11, 0); assert.ok(p.row >= 0 && p.row < 1) }
  const xs = pts.map((p) => p.x)
  assert.ok(Math.min(...xs) > 0 && Math.max(...xs) < 330 - 11, '居中,不贴边')
  assert.deepEqual(digitPoints('', 330, 260, 11), [])
  assert.match(src, /if \(!grid\.length && dots\) grid = digitPoints\(text, w, h, cell\);/)
  assert.match(src, /\} catch \(e\) \{ mask = null; \}/)
  assert.match(src, /if \(!glyph\.length\) glyph = glyphPoints\(SEAL_GLYPH, box, SEAL\.cell\);/)
  const gblock = src.match(/const SEAL_GLYPH = [\s\S]*?\nfunction glyphPoints[\s\S]*?\n}\n/)[0]
  const g = new Function(gblock + '; return glyphPoints(SEAL_GLYPH, 100, 7);')()
  assert.ok(g.length > 40)
  for (const p of g) assert.ok(p.x >= 0 && p.x < 100 && p.y >= 0 && p.y < 100)
})

test('M-2 勋章墙加载失败:按全站口径留空白遮罩,但收集册页头照常在;藏品页 ✦ 钮在深底上是浅色线条', () => {
  const wxml = read('subpackageP3/pages/badge-wall/index/index.wxml')
  assert.match(wxml, /<view class="bw-list" wx:if="\{\{viewMode === 'list' && \(loadFail \|\| partialFail\) && !wallAuthRequired\}\}">\s*<album-head active="medal" \/>/)
  const oc = read('subpackageP3/pages/object-cards/index/index.wxss')
  assert.match(oc, /\.alb-light \.oc-gather::before \{[^}]*stroke%3D%22%23F2F2F4%22/)
})

test('P-9 旅程手记:不再有说明句,左格不塞节点名;P-10 里程碑文案不再「距 1 个还差 1 个」', () => {
  const wxml = read('pages/play/index.wxml')
  assert.doesNotMatch(wxml, /这一趟自动记下来的|nextChapterText/)
  assert.match(wxml, /<view class="sheet-meta" wx:if="\{\{!journalMulti\}\}">\{\{chapter\.name\}\}<\/view>/)
  const js = read('pages/play/index.js')
  assert.doesNotMatch(js, /走完本章坐标|nextChapterText/)
  assert.match(js, /seg\.kvK = parts\.length > 1 \? parts\[0\] : \(seg\.label === n\.name \? '未到' : parts\[0\] \|\| ''\);/)
  assert.match(read('pages/play/index.wxss'), /\.jkv__k\{[^}]*white-space:nowrap; overflow:hidden; text-overflow:ellipsis;/)
  const { buildMilestone } = require('../../pages/play/utils/play-milestone.js')
  assert.equal(buildMilestone(0).title, '第一个主题还没完成')
  assert.doesNotMatch(wxml, /距 \{\{milestone\.next\.value\}\} 个还差/)
  assert.match(wxml, /'下一档 ' \+ milestone\.next\.value \+ ' 个主题'/)
})

test('P-11 首页城市定位确认是一句陈述,不再是长问句(合规要点:精确位置 / 发送给城瘾以确定首页城市 / 不用于附近排序 保留)', () => {
  for (const f of ['pages/index/index.js', 'pages/privacy/index.js']) {
    const s = read(f)
    assert.match(s, /content: '精确位置会发送给城瘾以确定首页城市，之后自动定位，不用于附近排序。'/, f)
    assert.doesNotMatch(s, /附近排序吗？/, f)
  }
})

test('P-13/P-15/P-16/P-18/ME-4 玩家侧小项', () => {
  // P-13 通行证未开放 / 商家:不摆筛选条
  assert.match(read('subpackageMember/market/library/index.wxml'), /<view class="lb-kinds" wx:if="\{\{state !== 'off' && state !== 'merchant'\}\}"/)
  // P-15 投诉建议有自己的图标,不再和 AI 对话记录重复
  const sz = read('pages/shezhi/shezhi.wxml')
  const icon = (id) => (sz.match(new RegExp('id="' + id + '"[^>]*icon="([^"]+)"')) || [])[1]
  assert.equal(icon('settings-feedback'), '/pages/shezhi/images/icon_lc_megaphone.svg')
  assert.notEqual(icon('settings-feedback'), icon('settings-ai-chat-records'))
  assert.ok(fs.existsSync(path.join(ROOT, 'pages/shezhi/images/icon_lc_megaphone.svg')))
  // P-16 选中的筛选胶囊在浅色地图上有落影
  assert.match(read('subpackageRoam/nearby/index.wxss'), /\.mt-chip\.is-on \{ background: #fff; color: #000; font-weight: 600; box-shadow: 0 4rpx 16rpx/)
  // P-18 没封面的票卡放插画,不留空
  const sw = read('subpackageMember/signup/index.wxml')
  assert.match(sw, /<view class="wc__im wc__im--illus" wx:else aria-hidden="true"><cy-illus /)
  assert.equal(JSON.parse(read('subpackageMember/signup/index.json')).usingComponents['cy-illus'], '/subpackageMember/components/cy-illus/index')
  // ME-4 底栏玻璃不透明度够,不渲染模糊时字也不透上来
  const tb = read('components/tabBar/index.wxss')
  assert.match(tb, /--tabbar-glass: rgba\(255, 255, 255, 0\.78\)/)
  assert.match(tb, /--tabbar-glass: rgba\(28, 28, 30, 0\.82\)/)
})

test('P-12 主题详情数据条:开放时间只在连字符后折行,不再 break-all 把「10.13」劈开', () => {
  const { buildTopicStatBar } = require('../../pages/topic/utils/topic-detail-facts.js')
  const open = buildTopicStatBar({ showStartDate: '10.11', showEndDate: '10.13' }).find((c) => c.key === 'open')
  assert.equal(open.value, '10.11-​10.13')
  const css = read('pages/topic/index/index.wxss')
  assert.match(css, /\.topic \.statbar__value \{ max-width: 100%; white-space: normal; word-break: keep-all; overflow-wrap: anywhere;/)
  assert.doesNotMatch(css, /\.topic \.statbar__value \{[^}]*break-all/)
})

test('L-2 游玩页加载遮罩(z200 纯黑底 + 暗骨架)自带返回键;开机黑场是约 2 秒自清的过渡帧', () => {
  const w = read('pages/play/index.wxml')
  const m = w.match(/<view class="play-loading"[\s\S]*?<cy-skeleton type="detail" \/>/)
  assert.ok(m, 'play-loading 块')
  assert.match(m[0], /class="play-back play-back--load" wx:if="\{\{!isPreview\}\}"[^>]*bindtap="goBackDetail"/)
  assert.match(read('pages/play/index.wxss'), /\.play-back--load\{ z-index:201; \}/)
  // 开机黑场必须自清:最后一拍把 bootStage 置空
  assert.match(read('pages/play/index.js'), /\['fade', 1100\], \['', 2000\]/)
})
