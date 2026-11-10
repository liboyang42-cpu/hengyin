'use strict'
// r2-visual(2026-11-10)逐项合同:图层弹层、订单卡、订单详情字段名、收集册、漫游护照、WXS 绑定门禁。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const strip = (s) => s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

test('a2-v3-18 图层弹层:没有「公开位置已模糊处理」,三行在一张分组卡里,无副说明', () => {
  const wxml = strip(read('pages/roam/index.wxml'))
  assert.doesNotMatch(wxml, /公开位置已模糊处理|entry-layer__note/)
  const block = wxml.slice(wxml.indexOf("entrySheet=='layers'}}\">"), wxml.indexOf("entrySheet=='activity' && entryActivity && entrySheetView=='team'"))
  assert.match(block, /<view class="entry-layer-group">[\s\S]*data-key="activities"[\s\S]*data-key="teams"[\s\S]*data-key="posts"[\s\S]*<\/view>/)
  assert.doesNotMatch(block, /封面图片节点|队长头像与剩余名额|位置做合并显示/)
  const wxss = read('pages/roam/index.wxss')
  assert.match(wxss, /\.entry-layer-group\{[^}]*background:var\(--cy-comp-sheet-player-card\)/)
})

test('a3-v3-22 订单卡:查看类动作是次级小药丸;横图封面按实际宽高切横版', () => {
  const D = 'subpackageMember/components/cy/scene-member-order-history/'
  const wxss = read(D + 'index.wxss')
  assert.match(wxss, /\.orderbut2\.orderbut-play \.orderbut__visual\{[^}]*height: 52rpx/)
  assert.match(wxss, /\.orderbox_li_con_tu--wide\{ width: 216rpx; height: 144rpx;\}/)
  const wxml = read(D + 'index.wxml')
  assert.equal((wxml.match(/bindload="onCoverLoad" data-id="\{\{item\.id\}\}"/g) || []).length, 2)
  // 行为:宽 ≥ 1.25 倍高才切横版,方图不切
  let cfg
  global.Component = (c) => { cfg = c }
  global.getApp = () => ({ globalData: {}, getPageSize: () => 20 })
  global.wx = new Proxy({}, { get: () => () => ({}) })
  try { require(path.join(root, D + 'index.js')) } catch (e) { /* 只取配置 */ }
  assert.ok(cfg && cfg.methods.onCoverLoad)
  const self = { data: { wideCover: {} }, setData(p) { Object.keys(p).forEach((k) => { this.data.wideCover[k.split('.')[1]] = p[k] }) } }
  cfg.methods.onCoverLoad.call(self, { currentTarget: { dataset: { id: 7 } }, detail: { width: 800, height: 800 } })
  assert.deepEqual(self.data.wideCover, {})
  cfg.methods.onCoverLoad.call(self, { currentTarget: { dataset: { id: 7 } }, detail: { width: 1600, height: 900 } })
  assert.deepEqual(self.data.wideCover, { 7: true })
})

test('a3-v3-02/03 订单详情字段名:二级灰 + label 字号', () => {
  const wxss = read('subpackageMember/components/cy/scene-member-order-detail/index.wxss')
  const rule = wxss.slice(wxss.indexOf('.orow-k {'), wxss.indexOf('}', wxss.indexOf('.orow-k {')))
  assert.match(rule, /font-size: var\(--cy-font-label\)/)
  assert.match(rule, /color: var\(--cy-color-text-secondary\)/)
})

test('a4-v3-34 收集册入口:WXS 跟手倾斜挂在三叠上,无灰底,换 3D 图标', () => {
  const D = 'subpackageMember/components/cy/profile/'
  const wxml = read(D + 'index.wxml')
  assert.match(wxml, /^<wxs src="\.\/pile-tilt\.wxs" module="tilt" \/>/)
  assert.equal((wxml.match(/class="pc-pile" data-reduced="\{\{reducedMotion\}\}" bindtouchstart="\{\{tilt\.start\}\}" bindtouchmove="\{\{tilt\.move\}\}" bindtouchend="\{\{tilt\.end\}\}" bindtouchcancel="\{\{tilt\.end\}\}"/g) || []).length, 3)
  for (const n of ['medal-3d', 'stamp-3d', 'object-3d']) {
    assert.ok(fs.existsSync(path.join(root, 'subpackageMember/images/album/' + n + '.png')), n)
    assert.match(wxml, new RegExp('/subpackageMember/images/album/' + n + '\\.png'))
  }
  assert.match(read('subpackageMember/images/album/SOURCES.md'), /MIT/)
  const wxss = read(D + 'index.wxss')
  assert.match(wxss, /\.pc-player \.pc-album \{[^}]*background: transparent; border: 0;/)
  // WXS 行为:跟手转、减少动态效果不转、松手复位
  const src = read(D + 'pile-tilt.wxs')
  const mod = { exports: {} }
  new Function('module', src)(mod)
  const styles = []
  const el = { setStyle: (s) => styles.push(s) }
  const state = {}
  const inst = (reduced) => ({ getDataset: () => ({ reduced }), getState: () => state, selectComponent: () => el })
  mod.exports.start({ instance: inst(false), touches: [{ pageX: 100, pageY: 100 }] })
  mod.exports.move({ instance: inst(false), touches: [{ pageX: 140, pageY: 80 }] })
  assert.ok(styles.some((s) => /rotateX\(8deg\) rotateY\(16deg\)/.test(s.transform || '')), JSON.stringify(styles))
  mod.exports.end({ instance: inst(false) })
  assert.deepEqual(styles[styles.length - 1], {})
  styles.length = 0
  mod.exports.start({ instance: inst(true), touches: [{ pageX: 100, pageY: 100 }] })
  mod.exports.move({ instance: inst(true), touches: [{ pageX: 160, pageY: 100 }] })
  assert.equal(styles.filter((s) => s.transform).length, 0, '减少动态效果时不倾斜')
})

test('V3-34 台账/绑定门禁:本文件声明过的 wxs 模块调用打 wxs 标记,未声明的仍按表达式处理', () => {
  const S = require(path.join(root, 'scripts/lib/xcx-scan.js'))
  const ok = S.findEventBindings('<wxs src="./a.wxs" module="tilt" /><view bindtouchmove="{{tilt.move}}" bindtap="goWall"></view>')
  assert.deepEqual(ok[0].wxs, { module: 'tilt', fn: 'move' })
  assert.equal(ok[1].wxs, undefined)
  const bad = S.findEventBindings('<view bindtouchmove="{{tilt.move}}"></view>')
  assert.equal(bad[0].wxs, undefined, '未声明的模块不放行')
  const ledger = read('scripts/uiaudit/build-action-ledger.js')
  assert.match(ledger, /\(binding\.wxs \? \[\] : eventHandlerNames\(binding\.value\)\)/)
})

test('a4-v3-27 收集册打开过场:只在从「我的」进入时播,减少动态效果不播,只动 transform/opacity', () => {
  assert.match(read('subpackageP3/components/album-head/index.wxml'), /^<album-intro \/>$/m)
  const prof = read('subpackageMember/components/cy/profile/index.js')
  for (const r of ['badge-wall', 'stamp-album', 'object-cards']) assert.match(prof, new RegExp(r + "/index/index\\?from=profile'"))
  const wxss = read('subpackageP3/components/album-intro/index.wxss')
  assert.match(wxss, /@media \(prefers-reduced-motion: reduce\) \{ \.ai \{ display: none; \} \}/)
  assert.doesNotMatch(wxss, /transition:[^;]*(width|height|left|top)\b/)
  let cfg
  global.Component = (c) => { cfg = c }
  const P = path.join(root, 'subpackageP3/components/album-intro/index.js')
  const run = (from, reduced) => {
    delete require.cache[P]
    delete require.cache[path.join(root, 'utils/motion-preference.js')]
    global.wx = { getStorageSync: (k) => (reduced && /motion/i.test(k) ? true : ''), getSystemInfoSync: () => ({}) }
    global.getCurrentPages = () => [{ options: { from } }]
    require(P)
    const self = { data: {}, setData(p) { Object.assign(this.data, p) } }
    cfg.lifetimes.attached.call(self)
    ;(self._t || []).forEach(clearTimeout)
    return self.data.show === true
  }
  assert.equal(run('profile', false), true)
  assert.equal(run('', false), false, '段间切换不重播')
  assert.equal(run('profile', true), false, '减少动态效果不播')
})

test('a4-v3-36 漫游护照三卡统一 3D 图标;官方活动仍用原照片', () => {
  const wxml = read('subpackageRoam/components/roam-photo/index.wxml')
  assert.match(wxml, /name === 'event'[^>]*src="\/subpackageRoam\/assets\/photos\/event\.webp"/)
  assert.match(wxml, /name === 'history' \|\| name === 'stamp' \|\| name === 'goals'/)
  for (const n of ['history', 'stamp', 'goals']) assert.ok(fs.existsSync(path.join(root, 'subpackageRoam/assets/icons/' + n + '.png')))
  assert.match(read('pages/roam/index.js'), /key: 'goals'[^}]*photo: 'goals'/)
  assert.match(read('subpackageRoam/components/roam-photo/index.wxss'), /@media \(prefers-reduced-motion: reduce\)/)
})
