// 2026-10-24 owner(Figma ku2oaN9ag1XUMC9lKNnrcr node 2807:12251):空态/错误态插画由「空白纸」
// no_data.svg 换成两层插画 —— empty_state_bg.svg(背景)+ empty_state_figure.svg(人物)。
// 画框 260.16×250:背景 x=0.08 y=0 260×249.85;人物 x=55.08 y=35 150.375×180。
// no_data.svg 继续只做缺封面/缺图占位(那些用法由 no-blank-cover / qr-voucher 等契约守)。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { createHash } = require('node:crypto')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const stripComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

const FRAME = { w: 260.16, h: 250 }
const LAYERS = {
  bg: { x: 0.08, y: 0, w: 260, h: 249.85, src: '/images/empty_state_bg.svg' },
  figure: { x: 55.08, y: 35, w: 150.375, h: 180, src: '/images/empty_state_figure.svg' },
}
// owner 原件只许原样引用,不许重画/改色/重导出
const ORIGINAL_SHA256 = {
  'images/empty_state_bg.svg': 'c847a9a2da331ec4439e26b9605ee5327399b587374aa152bf5d88a3d10a9371',
  'images/empty_state_figure.svg': 'ee50443c36b3416c4c179237a93cfd985b985eba2b2bacc64bf3a93682261592',
}

function rule(wxss, selector) {
  const esc = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const m = wxss.match(new RegExp(`(?:^|\\n)${esc}\\s*\\{([^}]*)\\}`))
  assert.ok(m, `找不到 ${selector} 规则`)
  return m[1]
}
function decl(body, prop) {
  const m = body.match(new RegExp(`(?:^|[;\\s])${prop}:\\s*([^;]+);`))
  return m ? m[1].trim() : null
}
const pct = (v) => Number(String(v).replace(/%$/, ''))

function assertArtGeometry(wxss, prefix, sizes) {
  const box = rule(wxss, `.${prefix}-art`)
  assert.equal(decl(box, 'position'), 'relative')
  assert.equal(decl(box, 'background'), null, '插画盒子不许加底色,暗色主题下靠插画自身颜色')
  assert.equal(decl(box, 'background-color'), null)
  assert.equal(decl(box, 'opacity'), null, '插画不许再压透明度')
  for (const [sel, width] of Object.entries(sizes)) {
    const body = sel === '' ? box : rule(wxss, `${sel} .${prefix}-art`)
    assert.equal(decl(body, 'width'), `${width}rpx`, `${sel || '默认'} 档宽度`)
    const h = parseFloat(decl(body, 'height'))
    assert.ok(Math.abs(h - width * FRAME.h / FRAME.w) < 0.01, `${sel || '默认'} 档高度必须锁 260.16:250 比例,实际 ${h}`)
  }
  for (const [name, L] of Object.entries(LAYERS)) {
    const body = rule(wxss, `.${prefix}-art__${name}`)
    assert.equal(decl(body, 'position'), 'absolute')
    const want = { left: L.x / FRAME.w * 100, top: L.y / FRAME.h * 100, width: L.w / FRAME.w * 100, height: L.h / FRAME.h * 100 }
    for (const [prop, v] of Object.entries(want)) {
      const got = decl(body, prop)
      assert.ok(got !== null, `${name} 缺 ${prop}`)
      assert.ok(Math.abs(pct(got) - v) < 0.01, `${name} ${prop} 应为 ${v.toFixed(2)}%(Figma),实际 ${got}`)
    }
  }
}

function assertArtMarkup(wxml, cond, prefix) {
  const src = stripComments(wxml)
  const block = src.match(new RegExp(`<view wx:(?:el)?if="\\{\\{${cond}\\}\\}" class="${prefix}-art" aria-hidden="true">([\\s\\S]*?)</view>`))
  assert.ok(block, `${prefix} 必须有两层插画分支`)
  const imgs = [...block[1].matchAll(/<image\b[^>]*\/>/g)].map((m) => m[0])
  assert.equal(imgs.length, 2, '必须恰好两层')
  ;[['bg', imgs[0]], ['figure', imgs[1]]].forEach(([name, tag]) => {
    assert.match(tag, new RegExp(`class="${prefix}-art__${name}"`), `第 ${name} 层顺序/类名不对(背景在下,人物在上)`)
    assert.ok(tag.includes(`src="${LAYERS[name].src}"`), `${name} 层必须引用 owner 原件`)
    assert.match(tag, /mode="(aspectFit|scaleToFill)"/, '框比 = 图比,只能 aspectFit/scaleToFill')
  })
  // 插画分支必须排在通用位图分支之前,否则 'empty-state' 会被当成图片路径
  assert.ok(src.indexOf(`class="${prefix}-art"`) < src.indexOf(`class="${prefix}-ic"`))
}

function resolveEmpty(props) {
  const abs = path.join(ROOT, 'components/cy/empty/index.js')
  const prev = [global.Component, global.Behavior]
  let opts = null
  global.Component = (o) => { opts = o }
  global.Behavior = (o) => o
  try { delete require.cache[require.resolve(abs)]; require(abs) } finally { [global.Component, global.Behavior] = prev }
  const state = {}
  const { kind = '', icon = '', title = '', sub = '' } = props
  opts.observers['kind, icon, title, sub'].call({ setData: (p) => Object.assign(state, p) }, kind, icon, title, sub)
  return state
}

test('owner 的两张 Figma 原件存在且字节未改', () => {
  for (const [rel, sha] of Object.entries(ORIGINAL_SHA256)) {
    assert.equal(createHash('sha256').update(fs.readFileSync(path.join(ROOT, rel))).digest('hex'), sha, `${rel} 被改动`)
  }
  assert.ok(fs.existsSync(path.join(ROOT, 'images/no_data.svg')), 'no_data.svg 仍是缺图占位,不得删除')
})

test('kind="empty" 默认走两层插画;其它 kind / 不传 kind 不受影响', () => {
  const empty = resolveEmpty({ kind: 'empty' })
  assert.equal(empty._art, true)
  assert.equal(empty._icon, 'empty-state')
  assert.equal(resolveEmpty({ icon: 'empty-state' })._art, true, '显式 icon="empty-state" 也渲染两层插画')
  assert.equal(resolveEmpty({ kind: 'offline' })._art, false)
  assert.equal(resolveEmpty({})._art, false, '不传 kind 仍无插画(向后兼容)')
  assert.equal(resolveEmpty({ kind: 'empty', icon: '/images/x.png' })._art, false, '显式位图优先')
})

test('cy-empty 渲染两层插画,偏移与尺寸逐项对齐 Figma', () => {
  assertArtMarkup(read('components/cy/empty/index.wxml'), '_art', 'cy-empty')
  assertArtGeometry(read('components/cy/empty/index.wxss'), 'cy-empty',
    { '': 360, '.cy-empty--lg': 520, '.cy-empty--sm': 240 })
})

test('cy-error 用 icon="empty-state" 时渲染同一张两层插画', () => {
  assertArtMarkup(read('components/cy/error/index.wxml'), "icon === 'empty-state'", 'cy-error')
  assertArtGeometry(read('components/cy/error/index.wxss'), 'cy-error', { '': 360, '.cy-error--lg': 520 })
})

test('负控:人物层偏移漂移(left 21.17% → 25%)必须判红', () => {
  const wxss = read('components/cy/empty/index.wxss')
  const mutated = wxss.replace(/(\.cy-empty-art__figure \{[^}]*left:\s*)21\.17%/, '$125%')
  assert.notEqual(mutated, wxss, '负控锚点失效')
  assert.throws(() => assertArtGeometry(mutated, 'cy-empty', { '': 360 }), /figure left/)
})

test('负控:两层顺序颠倒(人物压在背景下)必须判红', () => {
  const wxml = read('components/cy/empty/index.wxml')
  const mutated = wxml.replace(/(<image class="cy-empty-art__bg"[^>]*\/>)(\s*)(<image class="cy-empty-art__figure"[^>]*\/>)/, '$3$2$1')
  assert.notEqual(mutated, wxml, '负控锚点失效')
  assert.throws(() => assertArtMarkup(mutated, '_art', 'cy-empty'), /顺序/)
})

function wxmlFiles(dir = ROOT, out = []) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.name === 'node_modules' || ent.name === 'miniprogram_npm' || ent.name.startsWith('.')) continue
    const abs = path.join(dir, ent.name)
    if (ent.isDirectory()) wxmlFiles(abs, out)
    else if (ent.name.endsWith('.wxml')) out.push(path.relative(ROOT, abs))
  }
  return out
}

test('cy-empty / cy-error 状态调用点不再盖 no_data.svg(空白纸)', () => {
  const hits = []
  for (const rel of wxmlFiles()) {
    if (rel.startsWith('tests/')) continue
    const src = stripComments(read(rel))
    for (const m of src.matchAll(/<cy-(?:empty|error)\b[^>]*\bicon="\/images\/no_data\.svg"[^>]*>/g)) hits.push(rel)
  }
  assert.deepEqual(hits, [])
})

test('缺封面/缺图占位继续用 no_data.svg(不随空态换图)', () => {
  ;[
    'components/cy/qr-voucher/index.wxml',
    'subpackageB/pages/im/chat/index.wxml',
    'pages/club/topic-detail/index.wxml',
    'pages/activity/list/index.wxml',
    'pages/topic/merchantinfo/merchantinfo.wxml',
    // 2026-10-30 模板详情头图不再用缺图图标:模板本身没封面时用它第一个分类的 3D 图标做封面(业主「不能只是占位」)
    'components/cy/scene-roam-task-list/index.wxml',
  ].forEach((rel) => assert.ok(read(rel).includes('src="/images/no_data.svg"'), `${rel} 的缺图占位被换掉`))
  assert.match(read('pages/templatedetail/shelf/index.js'), /coverErrorSrc:\s*'\/images\/no_data\.svg'/)
})
