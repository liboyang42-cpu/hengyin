'use strict'
// 2026-10-28 业主:勋章墙改珐琅徽章(参考 x.com/danywander/status/2097314310783390104)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')

const dir = path.resolve(__dirname, '../../subpackageP3/pages/badge-wall/index')
const GLYPHS = require(path.join(dir, 'glyphs.js'))
const { PLATE } = require(path.join(dir, 'enamel-pin.js'))

test('每枚徽记都有自己的珐琅底板,未知徽记落 DEFAULT', () => {
  for (const key of GLYPHS.keys) assert.ok(PLATE[key], key + ' 缺底板')
  assert.ok(PLATE.DEFAULT)
})

test('列表与详情用珐琅图,星星占位不再出现', () => {
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  assert.match(wxml, /class="bw-medal__pin" src="\{\{pins\[item\.pinId\]\}\}"/)
  assert.match(wxml, /class="bw-sh-pin" src="\{\{pins\[sheet\.pinId\]\}\}"/)
  assert.match(wxml, /<canvas type="2d" id="pinCanvas"/)
  assert.doesNotMatch(wxml, /bw-medal__rim|star-filled/)
})

test('未解锁的珐琅线稿不再压灰,上传图仍按原样压灰', () => {
  const wxss = fs.readFileSync(path.join(dir, 'index.wxss'), 'utf8')
  assert.match(wxss, /\.bw-medal\.is-locked \.bw-medal__pin \{ filter: none; \}/)
  assert.match(wxss, /\.bw-medal\.is-locked \.bw-medal__img \{ filter: grayscale\(1\)/)
})
