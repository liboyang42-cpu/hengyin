const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function assertClubStateNav(source) {
  const nav = source.indexOf('<cy-nav-bar')
  const firstState = source.indexOf('<!-- 不存在/加载失败 -->')
  assert.ok(nav >= 0 && nav < firstState, 'club nav must render before error/loading/success states')
  assert.match(source, /<cy-nav-bar[^>]*custom-back[^>]*bind:back="goBack"/, 'club nav must use the page fallback')
  assert.match(source, /wx:if="\{\{notFound\}\}"/, 'club error state must stay reachable')
  assert.match(source, /wx:elif="\{\{!detailLoaded\}\}"/, 'club loading state must stay reachable')
  assert.match(source, /wx:elif="\{\{club\}\}"/, 'club success state must stay reachable')
}

function assertTemplateStateNav(source) {
  const nav = source.indexOf('<cy-nav-bar')
  const loading = source.indexOf('wx:if="{{ loading }}"')
  assert.ok(nav >= 0 && nav < loading, 'template nav must render before loading/error/success states')
  const navTag = source.match(/<cy-nav-bar[^>]*>/)
  assert.ok(navTag, 'template must render cy-nav-bar')
  // 导航统一(2026-07-29):导航条只剩返回出口,标题下沉为页面自己的大标题。
  // 这里锁两件事:①nav 上不许再挂 title(否则与 hero 大标题重复成双标题)
  //             ②标题必须真的还在页面上(否则这一页会静默变成无题页)
  assert.doesNotMatch(
    navTag[0],
    /\stitle=/,
    'template nav must not carry a title any more — 标题已下沉为 hero 大标题,nav 再挂就是双标题',
  )
  assert.match(navTag[0], /\soverlay\b/, 'template nav stays overlay on the hero cover')
  assert.match(navTag[0], /pill="\{\{!!coverUrl\}\}"/, 'pill 只在有封面时启用，无封面回落素箭头')
  assert.match(navTag[0], /custom-back[^>]*bind:back="goBack"/, 'template nav needs the page-owned back fallback')
  // 2026-07-31 Xbox 重设计:出血 hero 换成「小方封面 + 标题 + 发行方」的紧凑吸顶头。
  // 这条断言锁的东西没变 —— 标题必须真的还在页面上(nav 卸下 title 后不能连标题一起消失),
  // 只是承接它的节点从 .xb-hero-title 换成了 .xb-head-title。
  assert.match(
    source,
    /class="xb-head-title[^"]*">\{\{ info\.title \|\| '玩法' \}\}</,
    'template 标题下沉后必须由紧凑头大标题承接，不能连标题一起消失',
  )
  assert.match(source, /class="xb-state"[^>]*padding-top:\{\{statusBarHeight \+ navBarHeight \+ 24\}\}px/, 'loading/error state needs explicit safe top padding')
  assert.match(source, /wx:if="\{\{ loading \}\}"/, 'template loading state must stay reachable')
  assert.match(source, /wx:if="\{\{ loadError && !loading \}\}"/, 'template error state must stay reachable')
  assert.match(source, /wx:if="\{\{ !loading && !loadError \}\}"/, 'template success state must stay reachable')

  // 2026-07-29 ADA(WT3a):成功态 .xb-scroll 不挂行内 padding-top,显式走 --bleed 变体。
  // 2026-07-31 Xbox 重设计后这两条依然成立,但「谁来让开固定顶栏」换了机制:
  // 出血 hero 时代是封面自己垫在导航下;现在是滚动区内第一个等高 spacer + 紧凑头 sticky 到
  // 顶栏下缘。所以这里补一条:spacer 必须在,否则紧凑头会被 fixed 顶栏压住(静默失效)。
  assert.match(source, /class="xb-scroll xb-scroll--bleed"/, 'template success scroll must declare the bleed variant explicitly')
  assert.doesNotMatch(
    source,
    /class="xb-scroll[^"]*" style="padding-top:/,
    '成功态不能再挂行内 padding-top,让位由滚动区内的 spacer 负责',
  )
  assert.match(
    source,
    /<view style="height: \{\{statusBarHeight \+ navBarHeight\}\}px;"><\/view>/,
    '紧凑头前必须有等高 spacer 让开 fixed 顶栏，否则首屏标题被顶栏压住',
  )
  // 2026-08-04 按参考图(Xbox 63)重排:头部是**首屏大头、随页滚动**,不再吸顶;
  // 主动作也从固定底栏搬进页面流。原来钉死「xb-head 带 sticky top」「CTA 带 wx:if」的写法
  // 是在钉那一版的实现形态,不是在钉可达性本身,故按新形态重写 —— 立意不变:
  // 成功态必须有主动作,且主动作不许压在正文上。
  assert.match(source, /class="xb-cta"/, '成功态必须有主动作(CTA)')
  const successScroll = source.indexOf('class="xb-scroll xb-scroll--bleed"')
  assert.ok(successScroll >= 0 && source.indexOf('class="xb-cta"') > successScroll,
    'CTA 必须落在成功态滚动区内(loading/error 没有可点的主动作)')
  const ctaBtn = source.match(/<cy-btn[^>]*class="xb-cta-btn[^"]*"[^>]*>/)
  assert.ok(ctaBtn, 'CTA 主按钮必须存在')
  assert.doesNotMatch(ctaBtn[0], /--cy-btn-h\s*:/, 'CTA 主按钮必须消费全站 --cy-btn-h')
  assert.match(source, /class="xb-scroll-pad"/, '正文末尾必须留收尾留白 + 安全区')
}

// CTA 进入页面流后,「正文被遮」的防线从「让位够不够」变成「它压根不许是固定层」。
function assertTemplateCtaDoesNotCoverContent(wxss) {
  const cta = wxss.match(/\.xb-cta \{[^}]*\}/s)
  assert.ok(cta, 'templatedetail 必须有 CTA 的样式')
  assert.doesNotMatch(cta[0], /position:\s*(fixed|sticky)/,
    'CTA 必须在页面流里，不能是固定/吸底层 —— 固定层会压住正文最后一段(沉浸详情踩过的坑)')

  const pad = wxss.match(/\.xb-scroll-pad \{[^}]*\}/s)
  assert.ok(pad, 'templatedetail 必须有 scroll-pad')
  assert.match(pad[0], /env\(safe-area-inset-bottom\)/, 'scroll-pad 必须把安全区一起让掉')
}

test('P0 club/template keep state-safe navigation and recoverable standalone back', () => {
  assertClubStateNav(read('pages/club/detail/index.wxml'))
  assertTemplateStateNav(read('pages/templatedetail/templatedetail.wxml'))
  assertTemplateCtaDoesNotCoverContent(read('pages/templatedetail/templatedetail.wxss'))
})

test('negative control: a nav moved below the club state branch is rejected', () => {
  const source = read('pages/club/detail/index.wxml')
  const mutated = source.replace('<cy-nav-bar', '<!-- removed cy-nav-bar')
  assert.throws(() => assertClubStateNav(mutated))
})

test('negative control: 把 title 挂回 template 导航条(双标题)必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  const mutated = source.replace('<cy-nav-bar overlay', '<cy-nav-bar title="{{info.title || \'玩法详情\'}}" overlay')
  assert.notEqual(mutated, source, '变异锚点失效(源码已改动?)')
  assert.throws(() => assertTemplateStateNav(mutated), assert.AssertionError)
})

test('negative control: template 标题下沉后紧凑头大标题被删掉必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  const mutated = source.replace('class="xb-head-title ep2">{{ info.title || \'玩法\' }}<', 'class="xb-head-title ep2"><')
  assert.notEqual(mutated, source, '变异锚点失效(源码已改动?)')
  assert.throws(() => assertTemplateStateNav(mutated), assert.AssertionError)
})

test('negative control: 拿掉紧凑头的顶栏让位 spacer(标题被压)必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  const mutated = source.replace('<view style="height: {{statusBarHeight + navBarHeight}}px;"></view>', '')
  assert.notEqual(mutated, source, '变异锚点失效(源码已改动?)')
  assert.throws(() => assertTemplateStateNav(mutated), assert.AssertionError)
})

// spacer 已经让过一次位,行内 padding-top 会让成功态二次让位(顶部空出一整条)。
test('negative control: template 成功态把 padding-top 加回来(与 spacer 二次让位)必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  const mutated = source.replace(
    'class="xb-scroll xb-scroll--bleed"',
    'class="xb-scroll xb-scroll--bleed" style="padding-top:{{statusBarHeight + navBarHeight}}px;"',
  )
  assert.notEqual(mutated, source, '变异锚点失效(源码已改动?)')
  assert.throws(
    () => assertTemplateStateNav(mutated),
    (e) => e instanceof assert.AssertionError && /padding-top/.test(e.message),
    '加回 padding-top 必须由「让位归 spacer 管」这道闸判红',
  )
})

test('negative control: 删掉 template 主动作 CTA 必须判红', () => {
  const source = read('pages/templatedetail/templatedetail.wxml')
  const mutated = source.replace(/<view class="xb-cta">[\s\S]*?<\/view>/, '')
  assert.notEqual(mutated, source, '变异锚点失效(源码已改动?)')
  assert.throws(
    () => assertTemplateStateNav(mutated),
    (e) => e instanceof assert.AssertionError && /成功态必须有主动作/.test(e.message),
    '删掉 CTA 必须由「成功态要有主动作」这道闸判红',
  )
})

test('negative control: CTA 改回固定底栏(压住正文)必须判红', () => {
  const wxss = read('pages/templatedetail/templatedetail.wxss')
  const mutated = wxss.replace('.xb-cta {\n  display: flex;', '.xb-cta {\n  position: fixed;\n  display: flex;')
  assert.notEqual(mutated, wxss, '变异锚点失效(源码已改动?)')
  assert.throws(
    () => assertTemplateCtaDoesNotCoverContent(mutated),
    (e) => e instanceof assert.AssertionError && /不能是固定\/吸底层/.test(e.message),
    '固定层必须由「CTA 不许压正文」这道闸判红，而不是被别的断言顺手接住',
  )
})

test('negative control: scroll-pad 不让安全区(末段被 home indicator 吃掉)必须判红', () => {
  const wxss = read('pages/templatedetail/templatedetail.wxss')
  const mutated = wxss.replace(
    /\.xb-scroll-pad \{[^}]*\}/s,
    '.xb-scroll-pad {\n  height: 80rpx;\n}',
  )
  assert.notEqual(mutated, wxss, '变异锚点失效(源码已改动?)')
  assert.throws(
    () => assertTemplateCtaDoesNotCoverContent(mutated),
    (e) => e instanceof assert.AssertionError && /安全区/.test(e.message),
    '不让安全区必须由「scroll-pad 要让掉安全区」这道闸判红',
  )
})
