// P1-084(2026-10-24):会重复的动态字符串列表不得用 wx:key="*this"(同文两行 key 撞车,增删时节点错位复用)。
// 修法:utils/wxs/list-key.wxs 把每项包成 { key:'k'+下标, text },模板 wx:key="key"、读 item.text。
// 静态装饰列表(星级/刻度/拼图块)不在此列,刻意不动。
// 负控:把任一处改回 wx:for="{{shownHints}}" wx:key="*this" → 「已修列表不得回退 *this」用例变红。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function loadWxs(rel) {
  const module = { exports: {} }
  vm.runInNewContext(read(rel), { module, getRegExp: (s, f) => new RegExp(s, f) })
  return module.exports
}

// 文件 → 这些动态数组表达式(作者/服务端文本,可以同文重复)
const FIXED = {
  'pages/play/index.wxml': ['item.lines', 'hero.node.howto', 'shownHints', 'newLifeOS.actions7Days', 'newLifeOS.actions30Days'],
  'pages/play/components/playkit-dailysign/index.wxml': ['lines'],
  'components/cy/theme-records/index.wxml': ['card.exhibits'],
  'components/cy/scene-settings-deregister/index.wxml': ['blockers'],
}

function forTags(src) {
  return src.match(/<[a-zA-Z-]+\b[^>]*\bwx:for="[^"]*"[^>]*>/g) || []
}

test('list-key.wxs:同文重复也给出唯一且按下标稳定的 key', () => {
  const lk = loadWxs('utils/wxs/list-key.wxs')
  // vm 里的对象跨 realm,先 JSON 往返再比
  const rows = JSON.parse(JSON.stringify(lk.rows(['拍一张合照', '拍一张合照', '……'])))
  assert.deepEqual(rows.map((r) => r.key), ['k0', 'k1', 'k2'])
  assert.deepEqual(rows.map((r) => r.text), ['拍一张合照', '拍一张合照', '……'])
  assert.equal(new Set(rows.map((r) => r.key)).size, rows.length)
  assert.equal(lk.rows(null).length, 0)
  assert.equal(lk.rows(undefined).length, 0)
})

test('已修的动态字符串列表不得回退到 wx:key="*this"', () => {
  for (const [rel, exprs] of Object.entries(FIXED)) {
    const tags = forTags(read(rel))
    for (const expr of exprs) {
      const esc = expr.replace(/\./g, '\\.')
      const bare = tags.filter((t) => new RegExp('wx:for="\\{\\{\\s*' + esc + '\\s*\\}\\}"').test(t))
      const keyed = tags.filter((t) => new RegExp('wx:for="\\{\\{lk\\.rows\\(' + esc + '\\)\\}\\}"').test(t))
      for (const t of bare) assert.ok(!/wx:key="\*this"/.test(t), rel + ' 的 ' + expr + ' 回退成了 *this:' + t)
      assert.ok(keyed.length >= 1, rel + ' 的 ' + expr + ' 应走 lk.rows()')
      for (const t of keyed) assert.ok(/wx:key="key"/.test(t), rel + ' 的 ' + expr + ' 应以 key 字段为 wx:key:' + t)
    }
  }
})

test('引用 lk.rows 的模板都声明了 list-key.wxs 模块,且路径真实存在', () => {
  for (const rel of Object.keys(FIXED)) {
    const src = read(rel)
    const m = src.match(/<wxs src="([^"]+list-key\.wxs)" module="lk" \/>/)
    assert.ok(m, rel + ' 缺 <wxs module="lk">')
    assert.ok(fs.existsSync(path.join(ROOT, path.dirname(rel), m[1])), rel + ' 的 wxs 路径不存在:' + m[1])
    assert.ok(!/\{\{\s*(item|line)\s*\}\}/.test(src.split('\n').filter((l) => /lk\.rows\(/.test(l)).join('\n')),
      rel + ' 走 lk.rows 的行仍在读裸 item/line(应读 .text)')
  }
})
