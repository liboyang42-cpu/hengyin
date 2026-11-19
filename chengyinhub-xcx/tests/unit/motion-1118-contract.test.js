'use strict'
/* motion-1118 契约(2026-11-18)
 *
 * ① 双端共用动效 token:docs/design/motion-tokens.json 是双端对照表,本仓 style/tokens.wxss 的
 *    --cy-motion-* / --cy-ease-* 必须与之逐值相等(iOS 那边由 tools/check_native_motion_tokens.py 对同一份拷贝判红)。
 * ② 骨架屏 → 内容/空态/错误态:<cy-skeleton wx:if> 的同级 wx:elif / wx:else 分支(非 <block>)
 *    挂载时必须带 cy-fade-in(或 page-motion 的 --pm-* 入场),不能「啪」地换上 —— 对标 iOS 系统 App
 *    占位 → 内容的交叉淡入。<block> 不渲染节点、挂不上 class,不在本条范围(见 docs/motion-audit-1118.md)。
 * ③ cy-tabs icon 档的过渡只读 token,不写字面时长。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const SKIP = new Set(['node_modules', 'miniprogram_npm', 'tests', 'docs', 'images', '.git', 'scripts'])

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name)) continue
    const full = path.join(dir, e.name)
    if (e.isDirectory()) walk(full, out)
    else if (e.name.endsWith('.wxml')) out.push(full)
  }
  return out
}

const TOK = /<!--[\s\S]*?-->|<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g
const VOID = new Set(['input', 'image', 'icon', 'import', 'include'])
const FADED = /cy-fade-in|cy-rise-in|cy-fly-in|cy-refresh-float/
const CHECKED = new Set(['cy-empty', 'cy-error', 'cy-inline-error', 'cy-state-shell', 'view', 'scroll-view'])

function unfadedBranches(source) {
  const toks = []
  let m
  TOK.lastIndex = 0
  while ((m = TOK.exec(source))) {
    if (m[0].startsWith('<!--')) continue
    toks.push({ close: m[1], tag: m[2], attrs: m[3] || '', self: m[4] === '/' || VOID.has(m[2]) })
  }
  let d = 0
  for (const t of toks) {
    if (t.close) { d -= 1; t.depth = d } else { t.depth = d; if (!t.self) d += 1 }
  }
  const skipSubtree = (j) => {
    const u = toks[j]
    if (u.self) return j + 1
    let k = j + 1
    while (k < toks.length && !(toks[k].close && toks[k].depth === u.depth)) k += 1
    return k + 1
  }
  const bad = []
  toks.forEach((t, i) => {
    if (t.tag !== 'cy-skeleton' || !/\bwx:if=/.test(t.attrs)) return
    let j = skipSubtree(i)
    while (j < toks.length) {
      const u = toks[j]
      if (u.close || u.depth !== t.depth || !/\bwx:(elif=|else\b)/.test(u.attrs)) break
      const cls = /\bclass="([^"]*)"/.exec(u.attrs)
      const style = /\bstyle="([^"]*)"/.exec(u.attrs)
      if (CHECKED.has(u.tag) && !(cls && FADED.test(cls[1])) && !(style && /--pm-/.test(style[1]))) {
        bad.push(`<${u.tag} ${/wx:elif="[^"]*"|wx:else/.exec(u.attrs)[0]}>`)
      }
      const isElse = /\bwx:else\b/.test(u.attrs) && !/wx:elif/.test(u.attrs)
      j = skipSubtree(j)
      if (isElse) break
    }
  })
  return bad
}

test('motion-1118 ②:骨架屏换下来的分支一律淡入,不啪地换上', () => {
  const offenders = []
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!src.includes('<cy-skeleton')) continue
    for (const b of unfadedBranches(src)) offenders.push(`${path.relative(ROOT, file)} ${b}`)
  }
  assert.deepEqual(offenders, [], '骨架屏同级分支缺 cy-fade-in:\n' + offenders.join('\n'))
})

test('motion-1118 ②:检查器自身能抓到缺口(负控)', () => {
  const bad = unfadedBranches('<view><cy-skeleton wx:if="{{a}}" /><cy-error wx:elif="{{b}}" /><view wx:else class="x"><text>1</text></view></view>')
  assert.equal(bad.length, 2)
  const ok = unfadedBranches('<cy-skeleton wx:if="{{a}}" /><cy-error wx:elif="{{b}}" class="cy-fade-in" /><block wx:else><view /></block>')
  assert.equal(ok.length, 0)
})

function pageSet() {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const set = new Set(app.pages)
  for (const sub of app.subpackages || app.subPackages || []) for (const p of sub.pages) set.add(sub.root.replace(/\/$/, '') + '/' + p)
  return set
}

test('motion-1118 ②:隔离组件用了 cy-fade-in 就得自己 @import fade-in.wxss', () => {
  const missing = []
  const pages = pageSet()
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!/class="[^"]*cy-fade-in/.test(src)) continue
    const dir = path.dirname(file)
    if (pages.has(path.relative(ROOT, file).replace(/\.wxml$/, '').split(path.sep).join('/'))) continue // 页面吃 app.wxss 全局类
    const js = [path.join(dir, path.basename(file, '.wxml') + '.js'), path.join(dir, path.basename(file, '.wxml') + '.json')]
      .filter((f) => fs.existsSync(f)).map((f) => fs.readFileSync(f, 'utf8')).join('\n')
    if (!/Component\s*\(/.test(js)) continue // 页面吃 app.wxss 全局类
    if (/addGlobalClass:\s*true|apply-shared|"addGlobalClass":\s*true/.test(js)) continue
    const wxss = path.join(dir, path.basename(file, '.wxml') + '.wxss')
    const css = fs.existsSync(wxss) ? fs.readFileSync(wxss, 'utf8') : ''
    if (!/fade-in\.wxss|style\/components\.wxss/.test(css)) missing.push(path.relative(ROOT, file))
  }
  assert.deepEqual(missing, [])
})

test('motion-1118 ①:tokens.wxss 与双端对照表 docs/design/motion-tokens.json 逐值一致', () => {
  const spec = JSON.parse(fs.readFileSync(path.join(REPO, 'docs/design/motion-tokens.json'), 'utf8'))
  const css = fs.readFileSync(path.join(ROOT, 'style/tokens.wxss'), 'utf8')
  const read = (v) => {
    const m = new RegExp(v.replace(/[-]/g, '\\-') + ':\\s*([^;]+);').exec(css)
    assert.ok(m, `tokens.wxss 缺 ${v}`)
    return m[1].trim()
  }
  for (const [k, ms] of Object.entries(spec.durationMs)) {
    assert.equal(read(spec.cssVar[k]), `${ms}ms`, k)
  }
  const easeVar = { ...spec.cssVar, standard: spec.cssVar.standardEase }
  for (const [k, pts] of Object.entries(spec.easing)) {
    const got = read(easeVar[k]).replace(/\s+/g, '')
    const nums = /cubic-bezier\(([^)]*)\)/.exec(got)[1].split(',').map(Number)
    assert.deepEqual(nums, pts, k)
  }
})

test('motion-1118 ③:cy-tabs icon 档与 loading-mask 转圈不写字面时长', () => {
  const tabs = fs.readFileSync(path.join(ROOT, 'components/cy/tabs/index.wxss'), 'utf8')
  const icon = tabs.split('\n').filter((l) => l.includes('.cy-tabs--icon') && /transition:/.test(l))
  assert.ok(icon.length >= 3)
  for (const l of icon) assert.ok(!/transition:[^;]*\b\d*\.?\d+m?s\b/.test(l), l)
  const lm = fs.readFileSync(path.join(ROOT, 'components/cy/loading-mask/index.wxss'), 'utf8')
  assert.match(lm, /lm-spin var\(--cy-motion-spinner-cycle\)/)
})

/* ================= v2(2026-11-19)================= */

/* ④ 审计 L1:<cy-skeleton wx:if> 的 <block> 兄弟分支不出节点,淡入挂在分支里每个直接子元素上(不动结构)。
 *    嵌套 <block> 递归看它的子元素;原生组件、template/include 等挂不上 class 的跳过;
 *    子元素自己已有入场(cy-fade-in / rise / fly、page-motion 的 --pm- 行内变量、本页 wxss 里该类自带 animation)也算。 */
const NOCLASS = new Set(['template', 'import', 'include', 'slot', 'wxs', 'map', 'canvas', 'video', 'camera', 'live-player',
  'live-pusher', 'web-view', 'cover-view', 'cover-image', 'textarea', 'editor', 'input', 'text'])

function tokenize(source) {
  const toks = []
  let m
  TOK.lastIndex = 0
  while ((m = TOK.exec(source))) {
    if (m[0].startsWith('<!--')) continue
    toks.push({ close: m[1], tag: m[2], attrs: m[3] || '', self: m[4] === '/' || VOID.has(m[2]) })
  }
  let d = 0
  for (const t of toks) {
    if (t.close) { d -= 1; t.depth = d } else { t.depth = d; if (!t.self) d += 1 }
  }
  return toks
}

function unfadedBlockChildren(source, css) {
  const toks = tokenize(source)
  const skip = (j) => {
    const u = toks[j]
    if (u.self) return j + 1
    let k = j + 1
    while (k < toks.length && !(toks[k].close && toks[k].depth === u.depth)) k += 1
    return k + 1
  }
  const ownAnimation = (cls) => cls.replace(/\{\{[^}]*\}\}/g, ' ').split(/\s+/).filter(Boolean)
    .some((c) => new RegExp('\\.' + c.replace(/-/g, '\\-') + '\\b[^{}]*\\{[^}]*animation\\s*:', 'm').test(css || ''))
  const bad = []
  const visit = (bj) => {
    const b = toks[bj]
    let k = bj + 1
    while (k < toks.length && !(toks[k].close && toks[k].depth === b.depth)) {
      const u = toks[k]
      if (!u.close && u.depth === b.depth + 1) {
        if (u.tag === 'block') visit(k)
        else if (!NOCLASS.has(u.tag)) {
          const cls = (/\bclass="([^"]*)"/.exec(u.attrs) || [])[1] || ''
          const style = (/\bstyle="([^"]*)"/.exec(u.attrs) || [])[1] || ''
          if (!FADED.test(cls) && !/--pm-|animation/.test(style) && !ownAnimation(cls)) bad.push(`<${u.tag}${cls ? ` class="${cls}"` : ''}>`)
        }
        k = skip(k)
      } else k += 1
    }
  }
  toks.forEach((t, i) => {
    if (t.tag !== 'cy-skeleton' || !/\bwx:if=/.test(t.attrs)) return
    let j = skip(i)
    while (j < toks.length) {
      const u = toks[j]
      if (u.close || u.depth !== t.depth || !/\bwx:(elif=|else\b)/.test(u.attrs)) break
      if (u.tag === 'block') visit(j)
      const isElse = /\bwx:else\b/.test(u.attrs) && !/wx:elif/.test(u.attrs)
      j = skip(j)
      if (isElse) break
    }
  })
  return bad
}

test('motion-1118 ④(L1):骨架屏的 <block> 内容分支,直接子元素一律淡入', () => {
  const offenders = []
  let blocks = 0
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!src.includes('<cy-skeleton')) continue
    const wxss = file.replace(/\.wxml$/, '.wxss')
    const css = fs.existsSync(wxss) ? fs.readFileSync(wxss, 'utf8') : ''
    blocks += (src.match(/<block wx:(elif|else)/g) || []).length
    for (const b of unfadedBlockChildren(src, css)) offenders.push(`${path.relative(ROOT, file)} ${b}`)
  }
  assert.ok(blocks > 0)
  assert.deepEqual(offenders, [], '<block> 分支里的直接子元素缺 cy-fade-in:\n' + offenders.join('\n'))
})

test('motion-1118 ④(L1):检查器自身能抓到缺口(负控)', () => {
  const src = '<cy-skeleton wx:if="{{a}}" /><block wx:else><view class="x">1</view><block wx:if="{{b}}"><view /></block><map /><view class="cy-fade-in" /></block>'
  assert.deepEqual(unfadedBlockChildren(src, ''), ['<view class="x">', '<view>'])
  assert.deepEqual(unfadedBlockChildren(src, '.x { animation: y 1s; }'), ['<view>'])
})

/* ⑤ 图片加载淡入:占位色 + bindload 淡入(style/img-fade.wxss + utils/img-fade.js)。 */
const IMG_FADE_FILES = [
  'pages/topic/index/index.wxml',                                      // 路线详情:封面屏 + 头图
  'subpackageMember/signup/index.wxml',                                // 票夹卡封面
  'subpackageA/pages/myproject/index.wxml',                            // 我的项目缩略图
  'subpackageMember/components/cy/scene-member-order-history/index.wxml', // 我的订单缩略图(order/order 的卡片组件)
  'subpackageP3/pages/growthcenter/index/index.wxml',                  // 成长中心头像
]

test('motion-1118 ⑤:img-fade 只用 transition、读 token、尊重减少动效,全局与隔离组件都能用到', () => {
  const css = fs.readFileSync(path.join(ROOT, 'style/img-fade.wxss'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  assert.doesNotMatch(css, /@keyframes|animation\s*:/)
  assert.match(css, /\.cy-img-fade\s*\{[^}]*opacity:\s*0;[^}]*transition:\s*opacity var\(--cy-motion-fade-swap\) var\(--cy-ease-out\)/)
  assert.match(css, /\.cy-img-fade\.cy-img-fade--on\s*\{\s*opacity:\s*1;/)
  assert.match(css, /\.cy-img-ph\s*\{\s*background-color:\s*var\(--cy-color-skeleton-base\)/)
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.cy-img-fade\s*\{\s*transition:\s*none/)
  assert.doesNotMatch(css, /\d+m?s\b/, '时长只读 token')
  assert.match(fs.readFileSync(path.join(ROOT, 'style/components.wxss'), 'utf8'), /@import '\.\/img-fade\.wxss';/)
  // 隔离组件用了就得自己 @import
  const pages = pageSet()
  const missing = []
  for (const file of walk(ROOT)) {
    const src = fs.readFileSync(file, 'utf8')
    if (!/class="[^"]*cy-img-(fade|ph)/.test(src)) continue
    if (pages.has(path.relative(ROOT, file).replace(/\.wxml$/, '').split(path.sep).join('/'))) continue
    const base = file.replace(/\.wxml$/, '')
    const json = fs.existsSync(base + '.json') ? fs.readFileSync(base + '.json', 'utf8') : ''
    const js = fs.existsSync(base + '.js') ? fs.readFileSync(base + '.js', 'utf8') : ''
    if (!/Component\s*\(/.test(js) || /addGlobalClass|apply-shared/.test(js + json)) continue
    const own = fs.existsSync(base + '.wxss') ? fs.readFileSync(base + '.wxss', 'utf8') : ''
    if (!/img-fade\.wxss/.test(own)) missing.push(path.relative(ROOT, file))
  }
  assert.deepEqual(missing, [])
})

test('motion-1118 ⑤:onImgFadeLoad 只在 bindload 标记,同 key 只 setData 一次,脏 key 不进 setData 路径', () => {
  const { onImgFadeLoad, fadeKey } = require('../../utils/img-fade.js')
  const calls = []
  const ctx = { data: { imgOn: {} }, setData(p) { calls.push(p); for (const k of Object.keys(p)) this.data.imgOn[k.slice(6)] = p[k] } }
  const ev = (k) => ({ currentTarget: { dataset: { fadeKey: k } } })
  onImgFadeLoad.call(ctx, ev('h0'))
  onImgFadeLoad.call(ctx, ev('h0'))
  onImgFadeLoad.call(ctx, ev(''))
  onImgFadeLoad.call(ctx, ev('a.b'))
  onImgFadeLoad.call(ctx, {})
  assert.deepEqual(calls, [{ 'imgOn.h0': true }])
  assert.equal(fadeKey(ev(12)), '12')
  assert.equal(fadeKey(ev('x[1]')), '')
})

test('motion-1118 ⑤:列出的页面里远程图都在占位色上淡入,失败不标记(占位留着)', () => {
  for (const rel of IMG_FADE_FILES) {
    const src = fs.readFileSync(path.join(ROOT, rel), 'utf8').replace(/<!--[\s\S]*?-->/g, '')
    const toks = tokenize(src)
    const faded = []
    toks.forEach((t, i) => {
      if (t.tag !== 'image' || !/\bclass="[^"]*cy-img-fade/.test(t.attrs)) return
      faded.push(t)
      assert.match(t.attrs, /\bbindload="(onImgFadeLoad|onCoverLoad)"/, `${rel} ${t.attrs}`)
      assert.doesNotMatch(t.attrs, /binderror="(onImgFadeLoad|onCoverLoad)"/, rel)
      assert.match(t.attrs, /cy-img-fade--on/, rel)
      // 占位色在两层内的外框上(封面屏 Ken Burns:图 → .kb(淡入层)→ .slide1(占位))
      let found = false
      let depth = t.depth
      for (let k = i - 1, hops = 0; k >= 0 && hops < 2; k -= 1) {
        const u = toks[k]
        if (u.close || u.self || u.depth >= depth) continue
        depth = u.depth
        hops += 1
        if (/\bclass="[^"]*cy-img-ph/.test(u.attrs)) { found = true; break }
      }
      assert.ok(found, `${rel} 的 <image ${t.attrs.trim().slice(0, 60)}…> 外框缺 cy-img-ph`)
    })
    assert.ok(faded.length >= 1, rel)
    // 远程图(src 里有 {{ }})没漏:装饰小图标、静态兜底图除外
    for (const t of toks) {
      if (t.tag !== 'image' || !/\bsrc="[^"]*\{\{/.test(t.attrs)) continue
      if (/cy-img-fade|story-mini__c|bm-ic|image-item|moh-ico|wbg|pic"|cy-cover-fb__art/.test(t.attrs)) continue
      if (/class="kb__im/.test(t.attrs)) continue // 在 .kb 淡入层里
      if (rel === 'subpackageA/pages/myproject/index.wxml' && /^\s*src="\{\{item\}\}" mode="aspectFit"/.test(t.attrs)) continue // 申诉附件:本机刚选的临时图,不走网络
      if (rel === 'pages/topic/index/index.wxml') continue // 详情正文里的图在骨架之后、首屏以外,本轮只管首屏封面与头图
      assert.fail(`${rel} 远程图没走淡入:<image ${t.attrs.trim().slice(0, 80)}>`)
    }
  }
  const js = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
  for (const p of ['pages/topic/index/index.js', 'subpackageMember/signup/index.js', 'subpackageA/pages/myproject/index.js', 'subpackageP3/pages/growthcenter/index/index.js']) {
    assert.match(js(p), /onImgFadeLoad\(e\) \{ imgFade\.onImgFadeLoad\.call\(this, e\) \}/, p)
    assert.match(js(p), /imgOn: \{\}/, p)
  }
  // 订单卡:宽图换框与淡入同一次 setData
  assert.match(js('subpackageMember/components/cy/scene-member-order-history/index.js'), /this\.setData\(\{ \['imgOn\.' \+ id\]: true, \['wideCover\.' \+ id\]: true \}\)/)
})

test('motion-1118 ⑤:路线详情头图与票卡封面——占位色先于照片出现,版式不随照片到达变化', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.wxml'), 'utf8')
  assert.match(wxml, /<view class="slide1 screen cy-fade-in \{\{coverImages\.length \|\| info\.imgUrl \? 'cy-img-ph' : ''\}\}"/)
  assert.match(wxml, /<view class="kb cy-img-fade \{\{imgOn\.kb \? 'cy-img-fade--on' : ''\}\}"/)
  assert.match(wxml, /<swiper class="bgt-swiper dt__trk cy-img-ph"/)
  const css = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.wxss'), 'utf8')
  assert.match(css, /\.topic \.det1\.dt \{[^}]*height: 90vh;/, '头图区定高,照片到达不推版式')
  assert.match(css, /\.topic \.det1\.dt \.bgt-swiper \{ position: absolute;/)
  assert.match(css, /\.topic--reduced \.cy-img-fade \{ transition: none; \}/)
  const sw = fs.readFileSync(path.join(ROOT, 'subpackageMember/signup/index.wxml'), 'utf8')
  assert.match(sw, /<view class="wc__im cy-img-ph" wx:if="\{\{item\.wImg\}\}"><image [^>]*class="cy-img-fade \{\{imgOn\[item\.wKey\]/)
  const scss = fs.readFileSync(path.join(ROOT, 'subpackageMember/signup/index.wxss'), 'utf8')
  assert.match(scss, /\.wc__im \{ position: absolute;[^}]*top: 288rpx; bottom: 27rpx;/, '封面框定位与卡同体,随卡一起滑入')
  assert.match(scss, /\.signup--reduced \.cy-img-fade \{ transition: none; \}/)
})

/* ⑥ 成长中心积分:新回包改写已显示的数时从旧值数过去,不跳。 */
test('motion-1118 ⑥:countFrom 从屏上的值数到新值,起点与新值同一次 setData,减少动效直接落地', () => {
  const modPath = require.resolve('../../utils/page-motion.js')
  const prefPath = require.resolve('../../utils/motion-preference.js')
  delete require.cache[modPath]
  const realTimeout = global.setTimeout
  const queue = []
  global.setTimeout = (fn, ms) => { queue.push([ms, fn]); return queue.length }
  try {
    global.wx = { getStorageSync: () => false }
    delete require.cache[prefPath]
    const pm = require('../../utils/page-motion.js')
    const page = { data: { gsK: null }, setData(p) { Object.assign(this.data, p) } }
    const start = pm.countFrom(page, 'gsK', '31', '50')
    assert.ok(Math.abs(start - 0.62) < 1e-9)
    queue.sort((a, b) => a[0] - b[0]).forEach(([, fn]) => fn())
    assert.equal(page.data.gsK, null, '数完置 null,显示真实值')
    assert.deepEqual(pm.countFrom(page, 'gsK', '—', '50'), null)
    assert.deepEqual(pm.countFrom(page, 'gsK', '50', '50'), null)
    const reducedPage = { data: { reducedMotion: true }, setData() {} }
    assert.deepEqual(pm.countFrom(reducedPage, 'gsK', '31', '50'), null)
  } finally {
    global.setTimeout = realTimeout
    delete global.wx
    delete require.cache[modPath]
    delete require.cache[prefPath]
  }
  const gc = fs.readFileSync(path.join(ROOT, 'subpackageP3/pages/growthcenter/index/index.js'), 'utf8')
  assert.match(gc, /else if \(scoreText !== this\.data\.scoreText\) \{[^\n]*\n\s*this\.setData\(\{ unit, me, scoreText, profile, gsK: pageMotion\.countFrom\(this, 'gsK', this\.data\.scoreText, scoreText\) \}\)/)
})
