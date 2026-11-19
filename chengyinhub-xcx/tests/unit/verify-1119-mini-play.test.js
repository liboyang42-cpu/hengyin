const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('CG-ALL-26 站点距离走 geo.distanceText,不再输出 km / m', () => {
  const js = read('pages/play/index.js')
  assert.doesNotMatch(js, /toFixed\(1\) \+ 'km' : Math\.round\(dm\) \+ 'm'/)
  assert.match(js, /return geo\.distanceText\(dm\);/)
  const geo = require(path.join(ROOT, 'utils/geo.js'))
  assert.equal(geo.distanceText(320), '320 米')
  assert.equal(geo.distanceText(1530), '1.5 公里')
})

test('CG-ALL-24 玩法标题与主题数据条 keep-all + break-word,不再 anywhere / break-all 拆词', () => {
  const play = read('pages/play/index.wxss')
  assert.match(play, /\.fx-story__kitq\{[^}]*word-break:keep-all; overflow-wrap:break-word;/)
  assert.match(play, /\.st-intro__t\{[^}]*word-break:keep-all; overflow-wrap:break-word;/)
  const topic = read('pages/topic/index/index.wxss')
  assert.match(topic, /\.topic \.statbar__value \{[^}]*word-break: keep-all; overflow-wrap: break-word;/)
})

test('CG-ALL-25 章节门开着时弹窗压在门(130)之上并用近乎不透遮罩', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /chapterFull && chapterGate\.show \? 'play--gate' : ''/)
  const css = read('pages/play/index.wxss')
  const mask = css.match(/\.play--gate \.mask,[^{]*\{([^}]*)\}/)
  assert.ok(mask, '遮罩规则存在')
  assert.match(mask[1], /z-index:131/)
  assert.match(mask[1], /rgba\(8,8,8,\.96\)/)
  const sheet = css.match(/\.play--gate \.sheet,[^{]*\{([^}]*)\}/)
  assert.match(sheet[1], /z-index:132/)
  for (const sel of ['.gp2mask', '.finmask', '.chpick-mask']) assert.ok(mask[0].includes('.play--gate ' + sel), sel)
  for (const sel of ['.gp2', '.finsheet', '.chpick']) assert.ok(sheet[0].includes('.play--gate ' + sel), sel)
})

// 从页面源码里按花括号配平取出一个方法,包成对象方法真调(不加载整页)
function methodFrom(rel, name) {
  const src = read(rel)
  const start = src.indexOf('\n  ' + name + '(')
  assert.ok(start > 0, name + ' 存在')
  let i = src.indexOf('{', start), depth = 0
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) break
  }
  return new Function('return ({' + src.slice(start, i + 1) + '})')()[name]
}

test('FE-G3 自由探索(mode 2)工具抽屉「我的权益」关抽屉回格子层,不跳卡包页', () => {
  const fn = methodFrom('pages/play/index.js', 'openRightsFromTools')
  const calls = []
  const page = (mode, heroShow) => ({
    data: { mode, hero: { show: heroShow } },
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
    goCouponWallet() { calls.push('wallet') },
    closeChapStory() { calls.push('story') },
    closeHero() { calls.push('hero') },
  })
  const p2 = page(2, true)
  fn.call(p2)
  assert.equal(p2.data.sessionToolsOpen, false)
  assert.deepEqual(calls, ['story', 'hero'])
  calls.length = 0
  const p1 = page(1, false)
  fn.call(p1)
  assert.deepEqual(calls, ['wallet'], '城市定向仍去卡包')
})

test('FE-G1 无玩法店的店卡标 perkOnly,故事流底栏同口径', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /perkOnly: Number\(this\.data\.mode\) === 2 && Number\(node\.merchantId\) > 0 && !node\.hasGame && !node\.hasAdvanced,/)
  const wxml = read('pages/play/index.wxml')
  const bar = wxml.match(/<cy-btn variant="primary" style="flex:1" bindtap="heroEnter">\{\{([^}]+)\}\}<\/cy-btn>/)
  const vm = require('node:vm')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: true, done: false, playName: '' } } }), '我已到店')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: true, done: true, playName: '' } } }), '查看权益')
  assert.equal(vm.runInNewContext(bar[1], { hero: { node: { perkOnly: false, done: false, playName: 'X' } } }), '开始 · X')
})
