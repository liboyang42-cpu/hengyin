'use strict'

// F0412:路线卡只存 topicId,展示按当前资格现拉。拉不到(下架/已删/仅成员可见)时只说「下架或暂不公开」,
// 不回显发送时的标题,也不放行跳转 —— 卡片本身不授任何游玩权益。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('路线卡失效态不展示原标题,文案覆盖下架与不公开两种情况', () => {
  const wxml = read('subpackageB/pages/im/chat/index.wxml')
  const fallback = wxml.slice(wxml.indexOf("topicCache[item.card.topicId] === null"))
  const block = fallback.slice(0, fallback.indexOf('</block>'))
  assert.match(block, /该路线已下架或暂不公开/)
  assert.doesNotMatch(block, /item\.content|item\.card\.name/, '失效卡不得回显发送时的摘要/标题')
})

test('失效路线卡点击被拦下,不跳主题详情', () => {
  const js = read('subpackageB/pages/im/chat/index.js')
  assert.match(js, /if \(this\.data\.topicCache\[id\] === null\) \{ app\.tips && app\.tips\('该路线已下架或暂不公开'\); return; \}/)
  assert.match(js, /url: '\/api\/topic\/info-to-user'/, '展示字段按当前身份现拉,不读消息里的旧摘要')
})
