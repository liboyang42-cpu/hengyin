// BL-205：官方主办页发布检查的 blockers / risks 是服务端文案数组，两条相同文案时 wx:key="*this" 会重复，
// 改为按下标作 key。sec.presets 是本地常量且互不相同，保持不动。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const wxml = fs.readFileSync(path.join(__dirname, '../..', 'pages/activity/official-organizer/index.wxml'), 'utf8')

for (const list of ['check.blockers', 'check.risks']) {
  test(`BL-205 ${list} 按下标作 key`, () => {
    const tags = wxml.match(new RegExp(`<text[^>]*wx:for="\\{\\{${list.replace('.', '\\.')}\\}\\}"[^>]*>`, 'g')) || []
    assert.equal(tags.length, 1)
    assert.doesNotMatch(tags[0], /wx:key="\*this"/)
    assert.match(tags[0], /wx:key="index"/)
  })
}

test('BL-205 sec.presets 本地常量 key 不动', () => {
  assert.match(wxml, /wx:for="\{\{sec\.presets\}\}" wx:for-item="preset" wx:key="\*this"/)
})
