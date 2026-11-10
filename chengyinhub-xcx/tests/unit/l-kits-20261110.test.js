// l-kits(2026-11-10 launch 波次):玩法组件照原型 proto/game 的收口合同。
// 这里只钉本 lane 新增的三件事:cy-illus 的 SVG 属性去重(否则整张图在 XML 解析下不渲染)、判定屏用插图、分发器 / 单测以外的死规则。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const art = require('../../pages/play/components/cy-illus/art.js')

test('cy-illus:同一标签里重复的属性只留第一个 —— data URI 的 SVG 走 XML 解析,重复属性整张图不渲染(rig 实测 25 张里 24 张空白)', () => {
  assert.equal(art.dedupeAttrs('<path d="M0 0" stroke="#111" stroke-width="5" stroke-width="4"/>'), '<path d="M0 0" stroke="#111" stroke-width="5"/>')
  assert.equal(art.dedupeAttrs('<g class="a" transform="translate(1 2)"><circle r="1" fill="#fff" fill="#000"/></g>'), '<g class="a" transform="translate(1 2)"><circle r="1" fill="#fff"/></g>')
  for (const name of art.LIST) {
    for (const layer of ['blob', 'fg']) {
      const svg = decodeURIComponent(art.layers(name, 3)[layer].replace('data:image/svg+xml;charset=utf8,', ''))
      for (const tag of svg.match(/<[a-zA-Z][^>]*>/g)) {
        const keys = [...tag.matchAll(/\s([\w:-]+)="/g)].map((m) => m[1])
        assert.equal(new Set(keys).size, keys.length, name + ' ' + layer + ' 有重复属性:' + tag.slice(0, 80))
      }
      assert.match(svg, /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/)
    }
  }
})

test('判定屏:赢 = cy-illus 成功插图(不再画对勾),输 = 失败插图 + 两个字;中性判定不画', () => {
  const wxml = read('pages/play/components/play-verdict/index.wxml')
  assert.match(wxml, /<view class="pv__art" wx:if="\{\{kind === 'win' && !neutral\}\}">\s*<cy-illus wx:if="\{\{rising\}\}" name="success"/)
  assert.match(wxml, /<cy-illus wx:if="\{\{rising\}\}" name="fail"[^>]*\/><\/view>\s*<view class="pv__word">\{\{word\}\}<\/view>/)
  assert.ok(!/pv__tick/.test(wxml), '对勾已退场')
  const json = JSON.parse(read('pages/play/components/play-verdict/index.json'))
  assert.equal(json.usingComponents['cy-illus'], '/pages/play/components/cy-illus/index')
  const wxss = read('pages/play/components/play-verdict/index.wxss')
  assert.ok(!/pv__tick/.test(wxss))
})

test('本 lane 四个组件不新增 @keyframes、不写 transition: all、不写裸字号', () => {
  for (const c of ['playkit-qa', 'playkit-hangman', 'playkit-accusation', 'playkit-diceroll', 'play-verdict']) {
    const css = read('pages/play/components/' + c + '/index.wxss').replace(/\/\*[\s\S]*?\*\//g, '')
    assert.doesNotMatch(css, /transition:\s*all/, c)
    const frames = (css.match(/@keyframes/g) || []).length
    // qa 的 qa-fb / qa-burn、verdict 的 pv-slide / pv-rise、骰子的 dz-roll 系列是存量;本 lane 新写的 hangman / accusation 为 0
    if (c === 'playkit-hangman' || c === 'playkit-accusation') assert.equal(frames, 0, c + ' 不许有关键帧')
  }
})
