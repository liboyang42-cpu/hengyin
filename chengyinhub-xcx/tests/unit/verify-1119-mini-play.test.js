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
