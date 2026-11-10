// qx-a2(2026-11-09 走查 V3)视觉系统:弹层底、扫码图标、开关绿、底栏图标对、图标居中、帖文字号。
// 每条都带负控:把改动撤回去,同一条断言必须判红。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '')
const rule = (css, sel) => {
  const src = strip(css)
  const i = src.indexOf(sel + '{') >= 0 ? src.indexOf(sel + '{') : src.indexOf(sel + ' {')
  assert.ok(i >= 0, '找不到规则 ' + sel)
  return src.slice(i, src.indexOf('}', i) + 1)
}

test('V3-18 玩家弹层底 = 抬升灰 #1C1C1E 94%,抓手提亮;手写黑底弹层改读同一 token', () => {
  const tokens = read('style/tokens.wxss')
  // 2026-11-10 r2-visual:业主仍看不清 → 再抬到 #2C2C2E(.98) + 抓手 #8E8E93(见 r2-visual-tokens.test.js)
  assert.match(tokens, /--cy-comp-sheet-player-glass:\s*rgba\(44, 44, 46, \.98\);/)
  assert.match(tokens, /--cy-color-sheet-handle:\s*#8E8E93;/)
  assert.doesNotMatch(tokens, /--cy-color-sheet-handle:\s*#5A5A5A/, '深端抓手不许留旧灰')

  const proto = read('style/proto-sheet.wxss')
  assert.match(rule(proto, '.psheet'), /background:var\(--cy-comp-sheet-player-glass\);\s*backdrop-filter:blur\(var\(--cy-comp-sheet-blur\)\)/)
  assert.match(rule(proto, '.psheet__grab'), /background:var\(--cy-color-sheet-handle\)/)

  const play = read('pages/play/index.wxss')
  for (const sel of ['.sheet', '.pbar--tools', '.gp2', '.chpick']) {
    const body = rule(play, sel)
    assert.match(body, /background:var\(--cy-comp-sheet-player-glass\)/, sel + ' 必须读弹层 token')
    assert.doesNotMatch(body, /background:#(000|000000|0A0A0A)\b/i, sel + ' 不许回到纯黑')
  }
  for (const sel of ['.gp2__grab', '.chpick__grab', '.sheet-grab']) {
    assert.match(rule(play, sel), /background:var\(--cy-color-sheet-handle\)/, sel)
  }
  assert.match(read('components/cy/sheet/index.wxss'), /background: var\(--cy-comp-sheet-handle-bg, var\(--cy-color-sheet-handle\)\)/)

  // 负控:退回纯黑必须判红
  const regressed = proto.replace('background:var(--cy-comp-sheet-player-glass);', 'background:#000;')
  assert.doesNotMatch(rule(regressed, '.psheet'), /background:var\(--cy-comp-sheet-player-glass\)/)
})

test('V3-19 扫码图标跟宿主文字色走(mask + currentColor),不再烤死 #1A1A1A', () => {
  const icons = read('style/proto-icons.wxss')
  const line = icons.split('\n').find((l) => l.startsWith('.pic--scan{'))
  assert.ok(line, '缺 .pic--scan')
  assert.match(line, /background-color:currentColor;/)
  assert.match(line, /-webkit-mask:url\("data:image\/svg\+xml,/)
  assert.doesNotMatch(line, /1A1A1A/i)
  assert.doesNotMatch(line, /background-image:/)
  // 生成器同口径,重跑不会把它烤回去
  const gen = read('scripts/gen-proto-icons.py')
  assert.match(gen, /\('scan',\s*'IC\.scan',\s*'currentColor'/)
  assert.match(gen, /masked = color == 'currentColor'/)
  // 唯一使用处在暗色抽屉行,行的 color 是灰
  assert.match(read('pages/play/index.wxml'), /class="dic pic pic--scan"/)
  assert.match(rule(read('style/proto-sheet.wxss'), '.dic'), /color:#8A93A0/)
})

test('V3-20 所有开关开态 = 开关绿 token(#0E9E73),滑块白', () => {
  const tokens = read('style/tokens.wxss')
  assert.match(tokens, /--cy-comp-switch-on:\s*#0E9E73;/)
  assert.match(read('components/cy/switch/index.js'), /const DEFAULT_COLOR = '#0E9E73'/)
  const proto = read('style/proto-sheet.wxss')
  assert.match(rule(proto, '.dsw.is-on'), /background:var\(--cy-comp-switch-on\)/)
  assert.match(rule(proto, '.dsw.is-on .dsw-i'), /background:#FFFFFF/)
  const roam = read('pages/roam/index.wxss')
  assert.match(rule(roam, '.entry-layer__switch.is-on'), /background:var\(--cy-comp-switch-on\)/)
  assert.match(rule(roam, '.entry-layer__switch.is-on view'), /background:#FFFFFF/)
  assert.match(rule(read('pages/club/apply/index.wxss'), '.cap.on .sw'), /background: var\(--cy-comp-switch-on\)/)
  // 原生 switch 只走 cy-switch,且没有调用方改色
  const wxmls = []
  const walk = (dir) => { for (const n of fs.readdirSync(dir)) { const p = path.join(dir, n); const st = fs.statSync(p); if (st.isDirectory()) { if (!/node_modules|tests/.test(n)) walk(p) } else if (n.endsWith('.wxml')) wxmls.push(p) } }
  walk(root)
  for (const f of wxmls) {
    const s = fs.readFileSync(f, 'utf8')
    if (!f.endsWith(path.join('cy', 'switch', 'index.wxml'))) assert.doesNotMatch(s, /<switch\b/, f + ' 不许用裸 <switch>')
    assert.doesNotMatch(s, /<cy-switch[^>]*\scolor=/, f + ' 不许给开关改色')
  }
})

test('V3-25 底栏:玩家/商家每个 tab 一对同族 SVG,选中取填充版', () => {
  const js = read('components/tabBar/index.js')
  const pairs = [...js.matchAll(/iconPath: "([^"]+)", selectedIconPath: "([^"]+)"/g)].map((m) => [m[1], m[2]])
  assert.strictEqual(pairs.length, 10, '玩家 5 + 商家 5')
  for (const [on, sel] of pairs) {
    assert.match(on, /^\/images\/icon_tab_[a-z]+\.svg$/)
    assert.strictEqual(sel, on.replace('.svg', '_on.svg'), '选中必须是同名填充版')
    const o = read(on.slice(1)); const f = read(sel.slice(1))
    assert.match(o, /viewBox="0 0 24 24"/); assert.match(f, /viewBox="0 0 24 24"/)
    assert.match(o, /stroke-width="1\.8"/, on + ' 描边版 1.8')
    assert.match(f, /fill="#000000"/, sel + ' 必须有填充形')
  }
  const wxml = read('components/tabBar/index.wxml')
  assert.match(wxml, /src="\{\{index === active \? item\.selectedIconPath : item\.iconPath\}\}"/)
  // 负控:回到「黑白态一律未选资源」那条判定,选中就拿不到填充版
  assert.doesNotMatch(wxml, /\(monochrome \|\| mode === 'merchant'\) \|\| index !== active/)
  assert.match(rule(read('components/tabBar/index.wxss'), '.tabbar .list .ul .li .tabbar__icon'), /opacity: 0\.6;/)
})

test('V3-30 图标居中:偏心 ≥1.5% 的 glyph 改 viewBox 起点,生成器同步记账', () => {
  const icons = read('components/cy/icon/icons.wxss')
  const vb = (name) => {
    const line = icons.split('\n').find((l) => l.startsWith('.cyi--' + name + ' {'))
    assert.ok(line, name)
    return decodeURIComponent(line.match(/viewBox%3D%22([^%]+(?:%20[^%]+){3})%22/)[1])
  }
  const expected = {
    calendar: '0 -1 24 24', comment: '0 1.05 24 24', 'turn-right': '0 0.75 24 24', gps: '0.45 0.45 24 24',
    'poi-shop': '1 -0.5 24 24', 'tab-club': '0 0.5 24 24', heart: '0 0.5 24 24', 'heart-filled': '0 0.5 24 24',
  }
  for (const [n, v] of Object.entries(expected)) assert.strictEqual(vb(n), v, n)
  // 成对方向箭头是有意贴边,不动
  assert.strictEqual(vb('back'), '0 0 24 24'); assert.strictEqual(vb('chevron-right'), '0 0 24 24')
  const proto = read('style/proto-icons.wxss')
  assert.match(proto.split('\n').find((l) => l.startsWith('.pic--stamp{')), /viewBox='0 2\.8 24 24'/)
  assert.match(read('scripts/ds-build-icons.py'), /'comment': \(0, 1\.05\)/)
  assert.match(read('scripts/gen-proto-icons.py'), /'stamp': \(0, 2\.8\)/)
})

test('V3-23/24/32 帖文卡:正文/昵称 15pt、元信息 13pt、一套字族;分享与前三个同一动作盒', () => {
  const wxss = read('components/cy/post-card/index.wxss')
  const card = rule(wxss, '.post-card')
  assert.match(card, /font-family: -apple-system/)
  assert.match(card, /--pc-type-body: calc\(30rpx \* var\(--cy-type-scale\)\)/)
  assert.match(card, /--pc-type-meta: calc\(26rpx \* var\(--cy-type-scale\)\)/)
  assert.match(rule(wxss, '.post-card__nick'), /font-size: var\(--pc-type-body\);[\s\S]*font-weight: 600;/)
  assert.match(rule(wxss, '.post-card__text'), /font-size: var\(--pc-type-body\);[\s\S]*line-height: 1\.4;/)
  assert.match(rule(wxss, '.post-card__meta'), /font-size: var\(--pc-type-meta\);/)
  assert.match(rule(wxss, '.post-card__action-text'), /font-size: var\(--pc-type-meta\);/)
  const share = rule(wxss, '.post-card__share')
  const action = rule(wxss, '.post-card__action')
  assert.match(share, /padding: 0 18rpx;/); assert.match(action, /padding: 0 18rpx;/)
  assert.match(share, /margin: 0;/); assert.match(share, /justify-content: flex-start;/)
  assert.match(rule(wxss, '.post-card__share-icon'), /width: 44rpx; height: 44rpx;/)
  // Threads 顺序:赞 → 评论 → 转发 → 分享
  const wxml = read('components/cy/post-card/index.wxml')
  const order = ['post-card__action--like', 'emitComment', 'post-card__action--repost', 'class="post-card__share"'].map((k) => wxml.indexOf(k))
  assert.ok(order.every((v, i) => v > 0 && (i === 0 || v > order[i - 1])), '动作行顺序必须是 赞/评论/转发/分享')
})

test('V3-23/24/32 帖文详情与列表卡同字号;转发(分享)跟在动作后面靠左', () => {
  const wxss = read('pages/square/detail/index.wxss')
  const root = rule(wxss, '.detail.theme-dark')
  assert.match(root, /--pc-type-body: calc\(30rpx \* var\(--cy-type-scale\)\)/)
  assert.match(root, /--pc-type-meta: calc\(26rpx \* var\(--cy-type-scale\)\)/)
  assert.match(rule(wxss, '.detail.theme-dark .ph-name'), /font-size: var\(--pc-type-body\); font-weight: 600;/)
  assert.match(rule(wxss, '.detail.theme-dark .post-text'), /font-size: var\(--pc-type-body\); line-height: 1\.4;/)
  assert.match(rule(wxss, '.detail.theme-dark .ph-time'), /font-size: var\(--pc-type-meta\)/)
  assert.doesNotMatch(wxss, /act-share--end/, '转发不许再 margin-left:auto 甩到最右')
  const wxml = read('pages/square/detail/index.wxml')
  const left = wxml.slice(wxml.indexOf('class="act-left'), wxml.indexOf('</button>', wxml.indexOf('class="act-share"')))
  assert.ok(left.indexOf('act--like') >= 0 && left.indexOf('act--comment') > left.indexOf('act--like') && left.indexOf('class="act-share"') > left.indexOf('act--favorite'),
    '转发必须与赞/评论/收藏同组、排在最后')
})
