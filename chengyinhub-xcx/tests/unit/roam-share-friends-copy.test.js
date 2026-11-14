const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { channelsForVisibility } = require('../../utils/roam-route-privacy.js')

// 3-24:「仅好友」文案承诺「微信转发给好友」,可渠道只有朋友圈(存图+引导)与保存,没有转发钮。
test('删除分享辅助说明，好友范围的实际渠道仍只有朋友圈与保存', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/roam/index.wxml'), 'utf8')
  assert.doesNotMatch(wxml, /class="sh-vis__hint"/)
  assert.deepEqual(channelsForVisibility('friends'), ['朋友圈', '保存'])
  assert.match(wxml, /data-v="friends"/)

})
