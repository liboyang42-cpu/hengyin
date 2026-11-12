'use strict'
// x-design1 D16:发牌错开,同一时刻最多 4 张在半空;包面主题名沿用 x-fix7 P-6
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')
const js = read('pages/play/index.js')
const num = (name) => Number(js.match(new RegExp('const ' + name + ' = (\\d+(?:\\.\\d+)?);'))[1])

test('D16:发牌间隔 ≥ 单张飞行时长的 25%,同一时刻最多 4 张在飞', () => {
  const stagger = num('DEAL_STAGGER'), flight = num('DEAL_FLIGHT')
  assert.ok(stagger >= flight * 0.25, `stagger ${stagger} 太小`)
  assert.ok(Math.ceil(flight / stagger) <= 4, '同时在飞的张数超过 4')
})

test('D16:发牌总时长仍由 (n-1)*DEAL_STAGGER + DEAL_FLIGHT 驱动 packOpen,不提前拍落点', () => {
  assert.match(js, /const done = \(n - 1\) \* DEAL_STAGGER \+ DEAL_FLIGHT;/)
})

test('D16:x-fix7 P-6 的包面主题名仍在(只许主题名一个文字节点)', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<text class="fx-pack__t">\{\{topicName \|\| chapter\.name \|\| modeLabel\}\}<\/text>/)
})
