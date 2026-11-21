/* 2026-11-21 主人走查「玩法要顺、主题要一致自然」—— 单个玩法组件那一批的回归(fork B 那半)。
 * 每条对应走查里的一个具体问题:猜数字单位落在左下角、连线只有一排像单选框的圆圈从不画线、
 * 逐字猜词/找东西/停表/分类/九宫格/计步/盲品的说明行、入场延时拖住首屏、
 * 内嵌时 --bg 透明导致反色字消失、各玩法自带的一套主按钮。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const kit = (name, ext) => read(`pages/playkit/components/playkit-${name}/index.${ext}`)
const stripComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

function loadComponent(rel) {
  const abs = path.join(ROOT, rel)
  const localRequire = createRequire(abs)
  const prev = global.Behavior
  global.Behavior = (b) => b
  let def = null
  try {
    vm.runInNewContext(fs.readFileSync(abs, 'utf8'), {
      Component: (c) => { def = c }, Behavior: (b) => b, getApp: () => ({ globalData: {} }), wx: {},
      require: (name) => localRequire(name), console, setTimeout, clearTimeout,
    }, { filename: abs })
  } finally { global.Behavior = prev }
  return def
}
function mount(rel, extra) {
  const def = loadComponent(rel)
  const inst = Object.assign({}, def.methods, {
    data: JSON.parse(JSON.stringify(def.data || {})),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
    triggerEvent() {},
    selectComponent() { return null },
  }, extra || {})
  for (const [k, spec] of Object.entries(def.properties || {})) if (!(k in inst.data)) inst.data[k] = JSON.parse(JSON.stringify(spec.value === undefined ? null : spec.value))
  return { def, inst }
}

test('猜数字:单位「颗」贴在中间那个数后面,不再单独一行落在左下角', () => {
  const w = stripComments(kit('estimate', 'wxml'))
  assert.match(w, /<view class="es__lane es__lane--mid"><view class="es__num">\{\{curText\}\}<text class="es__unit" wx:if="\{\{unit\}\}">\{\{unit\}\}<\/text><\/view><\/view>/)
  assert.equal((w.match(/es__unit/g) || []).length, 1, '单位只出现一次,且在数字里')
})

test('连线:锚点代替空心圈;线按真实位置画;减弱动效不播画线过渡', () => {
  const w = stripComments(kit('match', 'wxml'))
  const s = kit('match', 'wxss')
  assert.doesNotMatch(w, /ma__dot/, '不再有像单选框的空心圈')
  assert.match(w, /class="ma__anchor ma__anchor--l[^"]*" data-key="l:\{\{item\.id\}\}"/)
  assert.match(w, /class="ma__anchor ma__anchor--r[^"]*" data-key="r:\{\{item\.id\}\}"/)
  assert.match(w, /<view class="ma__line [^"]*"\s+wx:for="\{\{lines\}\}"/, '连线层逐条渲染')
  assert.match(s, /\.ma__line \{[\s\S]*?transition: transform var\(--cy-motion-fade-swap\)/, '画线过渡走 token(≤200ms)')
  assert.match(s, /\.ma--reduced \.ma__line, \.ma--reduced \.ma__anchor \{ transition: none; \}/)
})

test('连线:几何纯函数 —— 两个锚点中心之间的长度与角度,相对画布原点', () => {
  const { inst } = mount('pages/playkit/components/playkit-match/index.js')
  const origin = { left: 10, top: 100, width: 300, height: 400 }
  const flat = inst._lineFor({ left: 100, top: 150, width: 8, height: 8 }, { left: 200, top: 150, width: 8, height: 8 }, origin)
  assert.deepEqual(JSON.parse(JSON.stringify(flat)), { x: 94, y: 54, len: 100, deg: 0 })
  const down = inst._lineFor({ left: 0, top: 0, width: 0, height: 0 }, { left: 30, top: 40, width: 0, height: 0 }, { left: 0, top: 0 })
  assert.equal(down.len, 50)
  assert.equal(down.deg, 53.1)
  assert.equal(inst._lineFor(null, {}, origin), null, '量不到就不画,不崩')
})

test('连线:配对后量锚点画线;量不到(选择器没结果)就清空,不崩', () => {
  const rect = (l, t) => ({ left: l, top: t, width: 8, height: 8 })
  let answer = null
  const query = { select() { return query }, selectAll() { return query }, boundingClientRect() { return query }, exec(cb) { cb(answer) } }
  const { def, inst } = mount('pages/playkit/components/playkit-match/index.js', { createSelectorQuery: () => query })
  inst.data.reducedMotion = true
  inst.data.left = [{ id: 'a', label: '老张咖啡' }]; inst.data.right = [{ id: 'b', label: '手冲耶加' }]
  answer = [{ left: 0, top: 0 }, [Object.assign(rect(100, 50), { dataset: { key: 'l:a' } }), Object.assign(rect(200, 90), { dataset: { key: 'r:b' } })]]
  def.observers['show, left, right'].call(inst, true, inst.data.left, inst.data.right)
  inst.onTapLeft({ currentTarget: { dataset: { id: 'a' } } })
  inst.onTapRight({ currentTarget: { dataset: { id: 'b' } } })
  assert.equal(inst.data.lines.length, 1, '连上一对就画出一条线')
  assert.equal(inst.data.lines[0].fresh, false, '减弱动效:直接画全,不走从 0 画出')
  answer = [null, []]
  inst._measureLines('')
  assert.deepEqual(JSON.parse(JSON.stringify(inst.data.lines)), [])
})

test('说明行删掉:逐字猜词 / 找东西 / 停表 / 分类 / 九宫格 / 计步 / 盲品', () => {
  const gone = [
    ['hangman', 'wxml', /点格子，用系统键盘输入/],
    ['hidden', 'wxml', /点一下你觉得藏着的地方|这里会是你上传的那张图/],
    ['stopwatch', 'wxml', /本机测量读数|切到后台会中断本机计时|数字不会放出来|容差/],
    ['stopwatch', 'js', /把手机拿到面前，随时可以开始|点屏幕任意处停'/],
    ['classify', 'wxml', /点下面的分类，把这张卡放进去|分完才能提交/],
    ['bingo', 'wxml', /每一格都要到店扫码或把那个游戏玩完才会亮/],
    ['bingo', 'js', /GPS 可以伪造|静态码/],
    ['walk', 'wxml', /先设目标，再同步微信运动/],
    ['blindtaste', 'wxml', /按住按钮就闭眼，松手就是睁眼/],
    ['compare', 'wxml', /这一题过了/],
    ['sort', 'wxml', /这一题过了/],
  ]
  for (const [name, ext, re] of gone) assert.doesNotMatch(stripComments(kit(name, ext)), re, `${name}.${ext}`)
})

test('首屏立刻出内容:这一批玩法的入场延时 ≤ .1s、时长 ≤ .2s', () => {
  const names = ['estimate', 'match', 'hangman', 'coinflip', 'stopwatch', 'walk', 'hidden', 'sort', 'classify', 'pricepair', 'predict',
    'bingo', 'typein', 'note', 'profile', 'photocheck', 'objectcard', 'blindtaste', 'dailysign', 'journey-check', 'scan', 'accusation', 'compare']
  for (const name of names) {
    const w = stripComments(kit(name, 'wxml'))
    for (const m of w.matchAll(/--pe-d:\s*([0-9.]+)s/g)) assert.ok(Number(m[1]) <= 0.1, `${name} --pe-d:${m[1]}s`)
    for (const m of w.matchAll(/--pe-dur:\s*([0-9.]+)s/g)) assert.ok(Number(m[1]) <= 0.2, `${name} --pe-dur:${m[1]}s`)
    for (const tag of w.match(/<[a-z-]+[^<>]*class="[^"]*\bpe\b[^"]*"[^<>]*>/g) || []) {
      assert.match(tag, /--pe-dur:\s*\.2s/, `${name}: 入场零件要写明 ≤200ms 时长,不吃各动效类自带的 .5~1.4s`)
    }
  }
})

test('内嵌时 --bg 透明:实心底上的字不再取 --bg(选中项 / 结局按钮 / 箭头不再没字)', () => {
  assert.doesNotMatch(kit('profile', 'wxss'), /\.pf__opt\.is-on \{[^}]*color:var\(--bg\)/)
  assert.doesNotMatch(kit('blindtaste', 'wxss'), /\.bt__step-no \{[^}]*color: var\(--bg\)/)
  const ac = kit('accusation', 'wxss')
  assert.doesNotMatch(ac, /color: var\(--lk-bg, var\(--bg\)\)/)
  assert.match(ac, /\.ac__end-btn \{[^}]*background: var\(--lk-a, #FFF\); color: var\(--lk-bg, #111114\)/, '结局按钮与 .g-btn 同色')
})

test('一种主按钮:停表 / 计步改用共享 .g-btn,不再各有一套黑胶囊 / 琥珀方块', () => {
  assert.match(kit('stopwatch', 'wxml'), /class="g-btn pe pe--up"[^>]*bindtap="onStart"/)
  assert.match(kit('stopwatch', 'wxml'), /class="g-btn" bindtap="onStop"/)
  assert.doesNotMatch(kit('stopwatch', 'wxss'), /\.sw__btn/)
  assert.match(kit('walk', 'wxml'), /class="g-btn \{\{syncing \? 'g-btn--disabled' : ''\}\}" bindtap="onCta"/)
  assert.doesNotMatch(kit('walk', 'wxss'), /\.wk__cta/)
})
