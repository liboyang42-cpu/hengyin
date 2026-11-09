const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function assertFinishContract(js, wxml) {
  // 探店日通关反馈:自动弹结算(allDone 上升沿)+ 真集章数据,不再用 total×12 假探索值糊 mode2
  assert.match(js, /_sawIncomplete && !this\._finCelebrated/)
  assert.match(js, /this\._finCelebrated = true;[\s\S]{0,120}openFinish\(\)/)
  assert.match(js, /finishStamps = isShopDay/)
  // 奖励行是恒空死 UI(后端对 FREE_COLLECT 不下发 themeReward),不许再出现
  assert.doesNotMatch(js, /finishRewardText/)
  assert.match(wxml, /finsheet__grid" wx:if="\{\{mode!=2\}\}"/)
  assert.match(wxml, /finsheet__grid" wx:if="\{\{mode==2\}\}"/)
  assert.match(wxml, /本期足迹/)
  const shopSummary = wxml.match(/<view class="finsheet__grid" wx:if="\{\{mode==2\}\}">([\s\S]*?)\n  <\/view>/)
  assert.ok(shopSummary, '探店日小结只在 mode2 展示')
  assert.match(shopSummary[1], /<text class="fst__v">\{\{doneCount\}\}\/\{\{total\}\}<\/text><text class="fst__l">到访完成<\/text>/)
  assert.doesNotMatch(shopSummary[1], /玩法完成/)
  assert.match(wxml, /<view class="finsheet__grid" wx:if="\{\{mode!=2\}\}">\s*<view[^\n]*>点亮坐标<\/text>/)
  assert.match(wxml, /finsheet__stamplist/)
  // banner 不得再对探店日说「旧址点亮/足迹卡」那套经典定向话
  assert.match(wxml, /mode==2 \? '本轮探索已完成'/)
}

test('探店日通关反馈契约(自动结算/集章/奖励行/banner 文案)', () => {
  assertFinishContract(read('pages/play/index.js'), read('pages/play/index.wxml'))
})

test('mutation 负控:摘掉 allDone 上升沿自动结算时契约变红', () => {
  const js = read('pages/play/index.js')
    .replace('else if (this._sawIncomplete && !this._finCelebrated) {', 'else if (false) {')
  assert.throws(() => assertFinishContract(js, read('pages/play/index.wxml')), assert.AssertionError)
})

test('mutation 负控:到访比例不得误标玩法完成或扩到 CITY', () => {
  const wxml = read('pages/play/index.wxml')
  for (const mutated of [
    wxml.replace('到访完成', '玩法完成'),
    wxml.replace('finsheet__grid" wx:if="{{mode==2}}"', 'finsheet__grid" wx:if="{{mode>=1}}"'),
  ]) assert.throws(() => assertFinishContract(read('pages/play/index.js'), mutated), assert.AssertionError)
})
