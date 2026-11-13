const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const pops = require('../../pages/play/utils/play-stage-pops.js')
const { keepNumerals } = require('../../pages/play/utils/keep-numerals.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('站点卡:步行超过 60 分钟不报分钟,只留距离', () => {
  assert.equal(pops.sheetFacts({}, '1.2km').walkText, '15')
  assert.equal(pops.sheetFacts({}, '4.8km').walkText, '60')
  assert.equal(pops.sheetFacts({}, '4.9km').walkText, '')
  const far = pops.sheetFacts({}, '1066.6km')
  assert.equal(far.walkText, '')
  assert.equal(far.distText, '1066.6km')
})

test('开始弹窗:玩家只看站名与任务,不带制作用标签', () => {
  assert.equal(pops.playerTitle('问答：老街的门牌'), '老街的门牌')
  assert.equal(pops.playerTitle('第04关 · 老街'), '老街')
  assert.equal(pops.playerTitle('老街的门牌'), '老街的门牌')
  const wxml = read('pages/play/play-stage.wxml')
  assert.doesNotMatch(wxml, /绑定地点<|模板 · \{\{/)
})

test('汉字数字不断行', () => {
  const out = keepNumerals('建于二〇〇年')
  assert.notEqual(out, '建于二〇〇年')
  assert.equal(out.replace(/⁠/g, ''), '建于二〇〇年')
  assert.equal(keepNumerals('一个人'), '一个人')
})

test('本局档案:站名逐行成列表', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /wx:for="\{\{previewEnding\.archive\.stations\}\}"/)
  assert.doesNotMatch(wxml, /走过 · \{\{/)
})
