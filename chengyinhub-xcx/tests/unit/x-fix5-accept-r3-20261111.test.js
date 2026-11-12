// x-fix5(2026-11-11 验收第三轮 ACCEPT-ISSUES-R3-20261111)回归
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('R3-2 整屏玩法 / 相册 / 判定 / 检定宿主都挂外观类,look-tokens 给这些宿主同一组 --pk-embed-*', () => {
  const wxml = read('pages/play/index.wxml')
  for (const tag of ['<cy-playkit id="playKit"', '<cy-play-verdict ', '<cy-playkit-journey-check class="pk-lookhost {{lookClass}}" show="{{journeyCheck.show && journeyCheck.present', '<cy-playkit-album ']) {
    const i = wxml.indexOf(tag)
    assert.ok(i >= 0, tag)
    assert.match(wxml.slice(i, i + 200), /class="pk-lookhost \{\{lookClass\}\}"/, tag)
  }
  for (const f of ['pages/play/look-tokens.wxss', 'pages/publish/fabu/look-tokens.wxss']) {
    const t = read(f)
    assert.match(t, /\.pk-lookhost\[class\*="look--"\] \{ --pk-embed-bg:var\(--lk-bg\);/)
    assert.match(t, /\.pk-lookhost\[class\*="look--"\] \{ --alb-bg:var\(--lk-bg\);/)
  }
})

test('R3-2 相册查看器 / 抽卡 / 弹球 / 抛硬币 / 主按钮读外观 token,无外观时回落原色', () => {
  const alb = read('pages/play/components/playkit-album/index.wxss')
  assert.match(alb, /background:var\(--alb-bg, #F4F4F6\)/)
  assert.doesNotMatch(alb, /background:#1C1C1E; color:#FFFFFF/)
  const rj = read('pages/play/components/playkit-random/index.js')
  assert.match(rj, /const THEMES = \['var\(--pk-embed-bg, #0b6adc\)', 'var\(--lk-card, #d99a16\)', 'var\(--lk-pop, #c5372f\)'\]/)
  const bl = read('pages/play/components/playkit-ballshake/index.wxss')
  assert.match(bl, /background: var\(--pk-embed-bg, #0a0a0b\)/)
  assert.match(read('pages/play/components/playkit-coinflip/index.wxss'), /var\(--pk-card-bg, var\(--pk-embed-bg, #000\)\)/)
  assert.match(read('pages/play/style/play-surface.wxss'), /background: var\(--lk-a, #FFF\);/)
})

test('R3-1 开始准备被拒时把后端原因原话回给队长', () => {
  const src = read('pages/club/topic-detail/director.js')
  assert.match(src, /\(action === 'START' \|\| action === 'PREPARE'\) && reason\) return reason/)
})

test('R3-3 导演台头部日期取本场 startDate,没有时才用主题起止', () => {
  const src = read('pages/club/topic-detail/index.js')
  assert.match(src, /dateRange: this\.headerDateRange\(raw\)/)
  const m = src.match(/headerDateRange\(raw\) \{[\s\S]*?\n  \},/)[0]
  const ctx = { _directorActivityId: 9, formatRange(a, b) { return (a ? 'A' + a : '') + (b ? 'B' + b : '') } }
  const fn = new Function('positiveId', 'return {' + m + '}.headerDateRange')(Number)
  const raw = { startDate: 't0', endDate: 't1', activityList: [{ id: 9, startDate: 's9' }, { id: 8, startDate: 's8' }] }
  assert.equal(fn.call(ctx, raw), 'As9')
  ctx._directorActivityId = 0
  assert.equal(fn.call(ctx, raw), 'At0Bt1')
  ctx._directorActivityId = 7
  assert.equal(fn.call(ctx, raw), 'At0Bt1')
})

test('R3-5 旅程手记按章分组:多章时每章第一站带章标题', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /seg\.chapterHead = chapterTitle\[cid\] \|\| ''/)
  assert.match(js, /journalMulti: multi,/)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="sheet-meta" wx:if="\{\{!journalMulti\}\}">\{\{chapter\.name\}\}<\/view>/)
  assert.match(wxml, /<view class="sheet-meta" wx:if="\{\{item\.chapterHead\}\}">/)
})

test('R3-6 通关后工具菜单不再给「开始这一局」,三行图标各不相同', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /class="drow drow--hold" wx:if="\{\{mode!=2 && !allDone\}\}"/)
  assert.match(wxml, /pic--pause' : 'pic--play'/)
  assert.match(wxml, /aria-label="本局读数">\s*<view class="dic pic pic--info">/)
})

test('R3-7 还没有这一局时不再白打案件册请求', () => {
  const src = read('pages/play/utils/play-topbar.js')
  assert.match(src, /_probeCasebook\(routeState\) \{/)
  assert.match(src, /routeState !== undefined && !\(routeState && routeState\.sessionId\)\) return/)
  assert.match(read('pages/play/index.js'), /that\._probeCasebook\(d\.routeState\)/)
})

test('R3-8 用时未知写「—」,不写「未知」', () => {
  const v = require(path.join(ROOT, 'subpackageJourney/utils/journey-view.js'))
  const r = v.reportView({ runState: 'ENDED', completionKind: 'LEGITIMATE_COMPLETION' })
  assert.equal(r.elapsedText, '—')
  assert.match(read('subpackageJourney/pages/report/index.wxml'), /report\.elapsedText === '—'/)
})

test('R3-9 通关卡文案区折行、不压按钮', () => {
  const w = read('pages/play/components/chapter-flip/index.wxss')
  assert.match(w, /\.cf--finale \.cf__t, \.cf--finale \.cf__d, \.cf--finale \.cf__k \{ max-width: 100%; white-space: normal; word-break: break-all; \}/)
  assert.match(w, /\.cf--finale \.cf__stats \{ flex-wrap: wrap;/)
  assert.match(w, /bottom: calc\(190rpx \+ env\(safe-area-inset-bottom\)\)/)
})

test('P-9 没到过的站不写「这一页尚未书写」说明句', () => {
  assert.doesNotMatch(read('pages/play/index.js'), /这一页尚未书写/)
})

test('N-1 / N-13 故事流收尾区与内嵌玩法块底部在 Home 条之上再留 32rpx', () => {
  const w = read('pages/play/index.wxss')
  assert.match(w, /\.chfull__end\{ padding:42rpx 46rpx calc\(env\(safe-area-inset-bottom\) \+ 32rpx\); \}/)
  assert.match(w, /padding-bottom:calc\(var\(--cy-space-6\) \+ env\(safe-area-inset-bottom\) \+ 32rpx\)/)
})

test('N-2 只有一种可买票时自动选中', () => {
  assert.match(read('pages/topic/components/cy/session-picker/index.js'), /sellable\.length === 1 && rows\.length === 1/)
})

test('N-17 通过入会申请确认标题只写名字,申请时刻进正文', () => {
  const src = read('pages/club/join-requests/index.js')
  assert.match(src, /title: '通过「' \+ name \+ '」的入会申请'/)
  assert.match(src, /content: timeLine \+ '通过后/)
  assert.doesNotMatch(src, /baseName \+ ' · ' \+ row\.requestTimeText \+ ' 申请' : baseName\n/)
})

test('A-3 / N-19 编辑器标题改多行自适应框折行;票种编辑器展开后滚到可见处', () => {
  const wxml = read('pages/publish/fabu/index.wxml')
  assert.match(wxml, /<textarea\s+class="pd-title-input pd-title-input--wrap"[\s\S]*?auto-height/)
  const js = read('pages/publish/fabu/index.js')
  assert.match(js, /_revealTicketEditor\(\) \{/)
  assert.equal((js.match(/this\._revealTicketEditor\(\)/g) || []).length, 2)
})

test('ME-4 底栏实底提前到 12%', () => {
  const w = read('components/tabBar/index.wxss')
<<<<<<< HEAD
  assert.match(w, /rgba\(255, 255, 255, 0\) 0%, #FFFFFF 6%, #FFFFFF 100%\)/)   // x-fix6 把浅色渐隐再收到 6% 且全不透明
=======
  assert.match(w, /#FFFFFF 6%, #FFFFFF 100%\)/ /* x-fix6 ME-4 */)
>>>>>>> e302d126 (test: x-fix6 同步断言与 UI 基线重锚（首页定位卡、底栏、通关卡），台账计数 +2)
  assert.match(w, /var\(--cy-color-bg-page\) 12%, var\(--cy-color-bg-page\) 100%\)/)
})
