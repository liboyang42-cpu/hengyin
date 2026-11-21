// 阅读面提速(2026-11-21 业主「动画和间距可以快一点、小一点,读得快的人会着急」)。
// 判三件事:
//   ① --cy-motion-read-* 与 JS 镜像 utils/reading-motion.js 一致,且都在上限内;
//   ② 故事流 / 章节门 / 登场人物 / 角色卡 / 结局 / 判定 / 勋章 / 碎片这些阅读面,没有一条一次性动效超出上限
//      (逐字类每字 ≤12ms、逐条错开 ≤40ms、转场 ≤350ms、自动前进 ≤600ms);
//   ③ 显形 / 解码途中点一下即走完(全文可见),刚滚过的抬手不算点,下一次滚动恢复动效;章节通关过场、勋章屏点一下直接落定。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const RM = require('../../utils/reading-motion.js')

const TOKENS = {}
for (const m of read('style/tokens.wxss').matchAll(/--(cy-motion-[\w-]+):\s*(\d+)ms;/g)) TOKENS[m[1]] = Number(m[2])

/** 一段 transition / animation 声明里出现的全部时长(ms):先把 var(--token[, 兜底]) 换成 token 值,calc 里的乘法按给定的 --i / --r / --d 上界算。 */
function durationsOf(decl, vars) {
  let s = decl.replace(/var\(--([\w-]+)(?:,\s*([^()]*))?\)/g, (all, name, fb) => {
    if (TOKENS[name] != null) return TOKENS[name] + 'ms'
    if (vars && vars[name] != null) return String(vars[name])
    return fb ? fb.trim() : '0ms'
  })
  s = s.replace(/calc\(([^()]*)\)/g, (all, expr) => {
    const ms = expr.replace(/(\d*\.?\d+)ms/g, '$1').replace(/(\d*\.?\d+)s\b/g, (x, n) => String(Number(n) * 1000))
    // eslint-disable-next-line no-new-func
    return Function('return (' + ms + ')')() + 'ms'
  })
  return [...s.matchAll(/(?<![\w.-])(\d*\.?\d+)(ms|s)\b/g)].map((m) => Number(m[1]) * (m[2] === 's' ? 1000 : 1))
}

/** 取 file 里选择器命中 re 的整条规则的 transition / animation 声明(不含 infinite 环境循环)。 */
function motionDecls(file, re) {
  const css = read(file).replace(/\/\*[\s\S]*?\*\//g, '')
  const out = []
  for (const m of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const sel = m[1].trim()
    if (!re.test(sel)) continue
    for (const d of m[2].matchAll(/(transition(?:-duration|-delay)?|animation(?:-duration|-delay)?)\s*:\s*([^;]+)/g)) {
      if (/infinite/.test(d[2])) continue
      out.push({ file, sel, decl: d[2].trim() })
    }
  }
  return out
}

test('① 阅读面 token 与 JS 镜像一致,且在上限内', () => {
  const pairs = {
    'cy-motion-read-fade': RM.READ_FADE_MS,
    'cy-motion-read-rise': RM.READ_RISE_MS,
    'cy-motion-read-stagger': RM.READ_STAGGER_MS,
    'cy-motion-read-char': RM.READ_CHAR_MS,
    'cy-motion-read-scatter': RM.READ_SCATTER_MS,
    'cy-motion-read-sweep': RM.READ_SWEEP_MS,
    'cy-motion-read-transition': RM.READ_TRANSITION_MS,
  }
  for (const [tok, js] of Object.entries(pairs)) assert.equal(TOKENS[tok], js, tok + ' 与 reading-motion.js 漂移')
  assert.ok(RM.READ_FADE_MS >= 120 && RM.READ_FADE_MS <= RM.CAPS.fadeMs)
  assert.ok(RM.READ_RISE_MS <= RM.CAPS.fadeMs)
  assert.ok(RM.READ_STAGGER_MS <= RM.CAPS.staggerMs)
  assert.ok(RM.READ_CHAR_MS <= RM.CAPS.charMs)
  assert.ok(RM.READ_TRANSITION_MS <= RM.CAPS.transitionMs)
  assert.ok(RM.READ_SWEEP_MS <= RM.CAPS.transitionMs && RM.READ_SCATTER_MS <= RM.CAPS.transitionMs)
  assert.ok(RM.READ_DECODE_TICK_MS * 6 <= RM.CAPS.fadeMs, '解码 6 拍整段 ≤ 一个淡入')
  assert.deepEqual(RM.CAPS, { fadeMs: 180, staggerMs: 40, charMs: 12, transitionMs: 350, waitMs: 600 })
})

test('② 故事正文显形与十二种文字动效只读阅读档,逐字 ≤12ms/字、整段 ≤350ms', () => {
  const css = read('pages/play/index.wxss')
  const st = css.match(/\n\.st-line\{[^}]*\}/)[0]
  assert.match(st, /transition:opacity var\(--cy-motion-read-fade\) ease, transform var\(--cy-motion-read-rise\)/)
  assert.doesNotMatch(st, /\d\.\d+s|\d+ms/, '.st-line 不再写字面时长')
  const fx = css.slice(css.indexOf('.tfx__c{'), css.indexOf('.chapter-video {'))
  assert.doesNotMatch(fx.replace(/\/\*[\s\S]*?\*\//g, ''), /(transition|animation)[^;}]*\b\d*\.?\d+(m?s)\b(?![^;}]*var)/, '逐字 / 整段动效不写字面时长(0s 打字机除外)')
  assert.match(fx, /\.tfx--type \.tfx__c\{ opacity:0; transition:opacity 0s linear calc\(var\(--i\) \* var\(--cy-motion-read-char\)\); \}/)
  // 一段 40 个字:逐字类最后一个字也在 40 × 12 + 180 内落定;随机类(聚星 / 信号)整段 ≤ scatter + rise
  for (const d of motionDecls('pages/play/index.wxss', /\.tfx--(type|ink|drop|wind) \.tfx__c$/)) {
    for (const ms of durationsOf(d.decl, { i: 40 })) assert.ok(ms <= 40 * RM.CAPS.charMs + RM.CAPS.fadeMs, d.sel + ' ' + ms)
    for (const ms of durationsOf(d.decl, { i: 1 })) assert.ok(ms <= RM.CAPS.charMs + RM.CAPS.fadeMs, d.sel + ' 单字 ' + ms)
  }
  for (const d of motionDecls('pages/play/index.wxss', /\.tfx--(gather|signal) \.tfx__c$|\.chfull__para\.tfx--\w+$|^\.st-line$/)) {
    for (const ms of durationsOf(d.decl, { r: 1, d: '0s' })) assert.ok(ms <= RM.CAPS.transitionMs, d.sel + ' ' + ms)
  }
  // 同批逐条错开:JS 写进 --d 的值
  assert.equal(require('../../pages/play/utils/play-look.js').READ_STAGGER_S * 1000, RM.READ_STAGGER_MS)
  assert.match(read('pages/play/index.js'), /const d = \(order\+\+\) \* playLook\.READ_STAGGER_S;/)
  // 作者预览与玩家页逐条同速
  const lp = read('pages/publish/components/look-preview/index.wxss')
  assert.match(lp, /\.tfx--type \.tfx__c\{ opacity:0; transition:opacity 0s linear calc\(var\(--i\) \* var\(--cy-motion-read-char\)\); \}/)
  assert.doesNotMatch(lp, /calc\(var\(--i\) \* 70ms\)|2\.6s|1\.3s/)
})

test('② 章节门 / 登场人物 / 角色卡 / 结局 / 判定 / 勋章 / 碎片:一次性动效都 ≤350ms(庆祝档 ≤600ms)', () => {
  const surfaces = [
    ['pages/play/index.wxss', /^\.(pv-ending|st-boot|st-intro|st-stats|life-arc)|^\.pv-ending__|^\.flyscore$/],
    ['pages/play/play-stage.wxss', /pv-ending/],
    ['pages/play/play-overlays.wxss', /^\.(pov|cc-in|cc-fig|sc-rank)$/],
    ['pages/play/cast-play.wxss', /^\.(cstart|cbill|ccard|ccard-mask|chfull__choice-o)/],
    ['pages/play/components/chapter-flip/index.wxss', /^\.cf/],
    ['pages/play/components/look-transition/index.wxss', /lt/],
    ['pages/play/components/play-verdict/index.wxss', /pv__(fill|body)/],
    ['pages/play/components/medal-screen/index.wxss', /^\.(md__rays|hexw|hex|fade)$/],
    ['pages/play/components/finish-medals/index.wxss', /ms__card/],
    ['pages/play/components/fragment/index.wxss', /frag/],
  ]
  let n = 0
  for (const [file, re] of surfaces) {
    for (const d of motionDecls(file, re)) {
      const cap = /flyscore/.test(d.sel) ? TOKENS['cy-motion-celebrate'] : RM.CAPS.transitionMs
      for (const ms of durationsOf(d.decl, { d: 4 })) { n++; assert.ok(ms <= cap, file + ' ' + d.sel + ' → ' + ms + 'ms > ' + cap) }
    }
  }
  assert.ok(n > 40, '确实量到了阅读面上的动效(' + n + ')')
})

test('② 计时器:章节门 / 转场 / 答对 / 勋章 / 结局「关机」/ 开场淡出都在上限内', () => {
  const flip = read('pages/play/components/chapter-flip/index.js')
  assert.ok(Number(flip.match(/const CARD_AT_MS = (\d+);/)[1]) <= RM.CAPS.waitMs, '通关卡立起 ≤600(原 3000)')
  assert.ok(Number(flip.match(/const DUST_MS = (\d+);/)[1]) <= RM.CAPS.waitMs)
  assert.match(flip, /this\._at\(\(\) => this\.setData\(\{ cta: true \}\), fromEnd \? RM\.READ_TRANSITION_MS : RM\.READ_RISE_MS\);/)
  assert.match(flip, /this\._at\(\(\) => this\.triggerEvent\('continue'\), RM\.READ_TRANSITION_MS\);/)
  assert.match(flip, /this\._at\(go, RM\.READ_TRANSITION_MS\);/)
  const fin = flip.match(/_finale\(\) \{[\s\S]*?\n    \},/)[0]
  const spin = [...fin.matchAll(/_turnTo\([^,]+, (\d+),|_explode\(\), (\d+)\)|\}\), (\d+)\);/g)].map((m) => Number(m[1] || m[2] || m[3]))
  assert.ok(spin.reduce((a, b) => a + b, 0) <= 1200, '通关过场转 + 炸 ≤1.2s(原 ≈2.9s):' + spin)

  const lt = read('pages/play/components/look-transition/index.js')
  const ms = (k) => Number(lt.match(new RegExp('const ' + k + ' = (\\d+);'))[1])
  assert.ok(ms('PRE_MS') + ms('COVER_MS') + ms('REVEAL_MS') <= RM.CAPS.transitionMs, '章节转场整段 ≤350ms')
  assert.equal(read('pages/publish/components/look-transition/index.js'), lt)

  assert.ok(require('../../pages/play/utils/answer-right-moment.js').RIGHT_AUTO_MS <= RM.CAPS.waitMs)
  const md = read('pages/play/components/medal-screen/index.js')
  assert.match(md, /Math\.min\(600, 180 \+ \(list \|\| \[\]\)\.length \* 40\)/)
  const page = read('pages/play/index.js')
  assert.match(page, /setTimeout\(go, RM\.READ_RISE_MS\)/, '开场页淡出后即进正文(原 400)')
  assert.match(page, /setTimeout\(hide, RM\.READ_RISE_MS\)/)
  assert.match(page, /setTimeout\(done, RM\.READ_SWEEP_MS \+ RM\.READ_RISE_MS - 60\)/, '结局「关机」≤600(原 820)')
  assert.ok(RM.READ_SWEEP_MS + RM.READ_RISE_MS - 60 <= RM.CAPS.waitMs)
  assert.match(page, /\['', 30 \+ 2 \* RM\.READ_RISE_MS \+ RM\.READ_TRANSITION_MS\]/, '开机黑场 ≈0.7s 自清(原 2s;不拦点)')
  assert.match(read('pages/play/index.wxss'), /\.st-boot\{[^}]*pointer-events:none;/)
  assert.match(read('pages/play/components/fragment/index.js'), /const FLY_MS = 320;/)
})

test('③ 点一下即走完:解码中的段直接还原全文,刚滚过的抬手不算点,下一次滚动恢复动效', () => {
  const realSetTimeout = global.setTimeout, realClear = global.clearTimeout
  const pending = []
  global.setTimeout = (fn, ms) => { pending.push({ fn, ms }); return pending.length }
  global.clearTimeout = (id) => { if (pending[id - 1]) pending[id - 1].fn = null }
  try {
    const api = require('../../pages/play/utils/play-look.js').methods()
    const page = Object.assign(Object.create(api), {
      data: { readSkip: false, reducedMotion: false, chapterParas: [{ text: '你好,世界', chars: [{ i: 0, ch: '你' }, { i: 1, ch: '好,' }, { i: 2, ch: '世' }, { i: 3, ch: '界' }] }] },
      setData(patch) {
        for (const [k, v] of Object.entries(patch)) {
          const m = /^chapterParas\[(\d+)\]\.chars$/.exec(k)
          if (m) this.data.chapterParas[Number(m[1])].chars = v
          else this.data[k] = v
        }
      },
    })
    page._decodeLine(0)
    assert.ok(page.data.chapterParas[0].chars.some((c) => c.raw), '解码开始:有字还是乱码')
    assert.equal(pending[0].ms, RM.READ_DECODE_TICK_MS)

    page._storyAt = Date.now()            // 刚滚过:这次抬手是拖动的结尾,不算点
    page.skipReveal()
    assert.equal(page.data.readSkip, false)

    page._storyAt = Date.now() - 1000
    page.skipReveal()
    assert.equal(page.data.readSkip, true, '点一下:在屏的段去掉过渡,直接落到终态')
    assert.deepEqual(page.data.chapterParas[0].chars.map((c) => c.ch), ['你', '好,', '世', '界'], '全文可见')
    assert.ok(page.data.chapterParas[0].chars.every((c) => !c.raw))
    assert.ok(pending.every((p) => p.fn === null), '解码计时器全部停掉,不会再把乱码写回来')

    page._resumeReveal()
    assert.equal(page.data.readSkip, false, '再滚:新滑进来的段照常显形')
  } finally {
    global.setTimeout = realSetTimeout; global.clearTimeout = realClear
  }

  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="chfull \{\{readSkip \? 'is-read-skip' : ''\}\}/)
  assert.match(wxml, /<view class="fx-story \{\{readSkip \? 'is-read-skip' : ''\}\}/)
  assert.match(wxml, /bindscroll="onChapterScroll" bindtouchend="skipReveal"/)
  assert.match(wxml, /bindscroll="onStoryScroll" bindtouchend="skipReveal"/)
  const css = read('pages/play/index.wxss')
  assert.match(css, /\.is-read-skip \.st-line, \.is-read-skip \.tfx__c, \.is-read-skip \.chfull__para\.tfx\{ transition:none; \}/)
  assert.ok(css.indexOf('.is-read-skip .st-line') > css.indexOf('.chfull__para.tfx--scan{'), '跳过规则排在各动效之后,同特异度靠顺序胜出')
  assert.match(read('pages/play/index.js'), /this\._storyAt = now; this\._resumeReveal\(\);/)
  // 显形中的段从不拦点:正文 / 逐字层都不设 pointer-events:none
  for (const d of [css.match(/\n\.st-line\{[^}]*\}/)[0], css.match(/\.tfx__c\{[^}]*\}/)[0]]) assert.doesNotMatch(d, /pointer-events/)
  // 减弱动态仍是立即
  assert.match(css, /\.play--reduced-motion \.st-line\{ transition:none; opacity:1;/)
  assert.match(css, /\.play--reduced-motion \.tfx__c\{ transition:none; opacity:1;/)
})

test('③ 章节通关过场 / 勋章屏:途中点一下直接落定', () => {
  let def
  const saved = { Component: global.Component, wx: global.wx }
  global.Component = (d) => { def = d }
  global.wx = { getWindowInfo: () => ({}), createOffscreenCanvas: () => ({ getContext: () => ({}) }) }
  try {
    delete require.cache[require.resolve('../../pages/play/components/chapter-flip/index.js')]
    require('../../pages/play/components/chapter-flip/index.js')
    const calls = []
    const ctx = Object.assign(Object.create(def.methods), {
      data: { mode: 'finale', cta: false, reducedMotion: false, info: { title: '通关' } },
      _stage: 'finale', _scene: { show: true }, _turn: { dur: 600 }, _timers: [setTimeout(() => calls.push('late'), 0)],
      setData(p) { Object.assign(this.data, p) },
      _assembleLogo(instant) { calls.push('logo:' + instant) },
      _decode() { calls.push('decode') },
    })
    ctx.onTap()
    assert.equal(ctx.data.cta, true, '按钮立刻可点')
    assert.equal(ctx.data.quiet, false, '文字落定')
    assert.equal(ctx._turn, null)
    assert.deepEqual(calls.slice(0, 2), ['logo:true', 'decode'])
    ctx.onTap()   // 卡已立起:再点不重复
    assert.equal(calls.filter((c) => c === 'decode').length, 1)
    def.lifetimes && def.lifetimes.detached && def.lifetimes.detached.call(Object.assign(ctx, { _gone: true }))
  } finally {
    global.Component = saved.Component; global.wx = saved.wx
  }

  global.Component = (d) => { def = d }
  try {
    delete require.cache[require.resolve('../../pages/play/components/medal-screen/index.js')]
    require('../../pages/play/components/medal-screen/index.js')
    const md = Object.assign(Object.create(def.methods), {
      data: { on: false, dropped: false, stats: [{ v: 12 }, { v: '3/3' }], shown: [] },
      setData(p) { Object.assign(this.data, p) },
      _next() { throw new Error('还没落定时点一下不该切下一枚') },
    })
    md.onTap()
    assert.equal(md.data.on, true)
    assert.equal(md.data.dropped, true)
    assert.deepEqual(md.data.shown, ['12', '3/3'], '读数直接给终值')
  } finally {
    global.Component = saved.Component
  }
})
