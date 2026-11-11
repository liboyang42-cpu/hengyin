// P-13(2026-11-02 走查修复):自由探索不带队,到开场时间直接进,不显示「队伍集合中 · 等队长开始行程」。
// 是否自由探索由服务端 team-progress 的 freeEntry 决定,客户端只照读,不自行猜玩法。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const ROOT = path.resolve(__dirname, '../..')
const js = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')

test('集合屏在 freeEntry 时不显示', () => {
  const m = wxml.match(/<view class="leadgather" wx:if="\{\{([^}]*)\}\}">/)
  assert.ok(m, '找不到集合屏')
  assert.match(m[1], /!lead\.freeEntry/)
})

test('team-progress 的 freeEntry 只认服务端的 true', () => {
  assert.match(js, /freeEntry: d\.freeEntry === true/)
})

test('P-13/C-12:自由探索进行中不显示带队条，结束时显示结果并让出顶部空位', () => {
  const context = { module: { exports: {} } }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'utils/wxs/hud-slots.wxs'), 'utf8'), context)
  const banner = wxml.match(/<view class="leadbar glass" wx:if="\{\{([^}]*)\}\}">/)[1]
  const spacer = wxml.match(/class="fx"[\s\S]*?style="padding-top:\{\{([^}]*)\}\}px"/)[1]
  const top = wxml.match(/class="play-topstack"[^>]*style="top:\{\{([^}]*)\}\}px"/)[1]
  const cases = [
    [{ exists: true, freeEntry: true, status: 2, ended: false }, false, 0, 56],
    [{ exists: true, freeEntry: true, status: 3, ended: true }, true, 64, 0],
    [{ exists: true, freeEntry: false, status: 2, ended: false }, true, 64, 56],
    [{ exists: true, freeEntry: false, status: 3, ended: true }, true, 64, 56],
    [{ exists: false, freeEntry: true, status: 3, ended: true }, false, 0, 56],
  ]
  for (const [lead, visible, space, offset] of cases) {
    const input = { hud: context.module.exports, couponToast: '', screen: '', leadLoadError: false, lead, chrome: { contentTop: 94 } }
    assert.equal(vm.runInNewContext(banner, input), visible, JSON.stringify(lead))
    assert.equal(vm.runInNewContext(spacer, input), 94 + space, JSON.stringify(lead))
    assert.equal(vm.runInNewContext(top, input), 94 + offset, JSON.stringify(lead))
  }
})

test('P-28:游玩工具在页面层,自由探索卡包页有入口打开同一份菜单', () => {
  const chfullStart = wxml.indexOf('<view class="chfull')
  const layer = wxml.indexOf('<view class="tools-layer" wx:if="{{sessionToolsOpen}}">')
  assert.ok(layer > chfullStart, '缺工具层')
  // 工具层不能再嵌在故事流 chfull 里(自由探索不渲染 chfull)
  const before = wxml.slice(chfullStart, layer)
  const opens = (before.match(/<view\b(?![^>]*\/>)/g) || []).length
  const closes = (before.match(/<\/view>/g) || []).length
  assert.equal(opens, closes, '工具层仍在 chfull 内')
  assert.match(wxml, /class="fx-lhd__more"[^>]*bindtap="toggleSessionTools"/)
  assert.match(wxml, /aria-label="入场码"/)
})

test('P-28 第二轮(业主 2026-11-02):自由探索不带队时菜单不出「队伍位置」,其余领队行不动', () => {
  const row = wxml.match(/<view class="drow" wx:if="\{\{([^}]*)\}\}" bindtap="leadBroadcastFromTools"[^>]*aria-label="队伍位置"/)
  assert.ok(row, '找不到「队伍位置」行')
  assert.match(row[1], /lead\.exists/)
  assert.match(row[1], /!lead\.freeEntry/)
  // 「切换章节」:自由探索照旧出;2026-11-11 f-play V3-08 城市定向多章也出(走完后回看换章)
  assert.match(wxml, /<view class="drow" wx:if="\{\{mode==2 \|\| chapterCards\.length > 1\}\}" bindtap="openStackOrChaptersFromTools"/)
})
