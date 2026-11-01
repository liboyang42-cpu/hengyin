const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// 2026-11-01 业主:商家页只在开通后才进得来,工作台不再有「开通前置检查」。
// 回归钉:工作台既不请求前置检查接口,也不渲染这一块(后端接口 BL-85 仍在,见 batch5-api-contract)。
const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

test('商家工作台不再请求也不渲染开通前置检查', () => {
  assert.doesNotMatch(read('pages/merchant/index/index.js'), /merchant\/ops\/prerequisites|loadPrerequisites|goPrerequisite/)
  assert.doesNotMatch(read('pages/merchant/index/index.wxml'), /开通前置检查|prerequisites/)
  assert.equal(fs.existsSync(path.join(ROOT, 'pages/merchant/utils/merchant-prerequisites.js')), false)
})
