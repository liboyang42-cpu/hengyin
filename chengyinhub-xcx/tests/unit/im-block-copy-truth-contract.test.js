// 6-16 拉黑确认文案不得指向不存在的入口。
//
// 病:文案写「随时可以在设置里解除」,但全仓没有任何地方调用 /api/im/unblock,
// 设置页也没有黑名单入口 —— 用户按文案去找,永远找不到。
// 合同:文案只说当前真实做得到的事(对方不知情 + 无法自助解除),不承诺入口。
'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const { getDangerAction } = require('../../utils/danger-actions.js')

function actionText(key) {
  const action = getDangerAction(key, {})
  return [action.title, action.content, action.confirmText, action.done.title, action.done.text]
    .concat(action.consequences.map((item) => item.text))
    .filter(Boolean)
    .join('\n')
}

test('拉黑文案不再承诺「设置里解除」', () => {
  const all = actionText('im.block')

  assert.doesNotMatch(all, /设置里解除|随时可以在设置/, '没有这个入口,不许写')
  assert.match(all, /对方不会收到拉黑提示/, '真实后果要保留')
  // RV-p6 #3:「需要时请联系客服」同样做不到 —— 客服端只有 adminUnblock,只删平台级拉黑(memberId=0),
  // 解不了「我拉黑了谁」。
  // BL-61:会话「更多」里已有「解除拉黑」(POST /api/im/unblock),文案只指向这个真实入口;设置页仍无黑名单。
  assert.match(all, /可在本会话「更多」里解除拉黑/, '要如实指向真实的解除入口')
  assert.doesNotMatch(all, /拉黑后暂不支持解除/, '已有解除入口,旧说法不再成立')
  assert.doesNotMatch(all, /联系客服/, '客服也解不了用户之间的拉黑,不许把人引过去')
})

test('解除入口只在私信会话页:文案说「本会话「更多」里」必须真有调用方', () => {
  const files = []
  const walk = (dir) => {
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name)
      const stat = fs.statSync(full)
      if (stat.isDirectory()) walk(full)
      else if (name.endsWith('.js') || name.endsWith('.wxml')) files.push(full)
    }
  }
  walk(path.join(ROOT, 'pages'))
  walk(path.join(ROOT, 'subpackageB'))
  const unblockCallers = files.filter((f) => /api\/im\/unblock/.test(fs.readFileSync(f, 'utf8')))
    .map((f) => path.relative(ROOT, f).split(path.sep).join('/'))
  assert.deepEqual(unblockCallers, ['subpackageB/pages/im/chat/index.js'], '解除入口应只在会话页「更多」里;别处新增入口需同步改文案')
})
