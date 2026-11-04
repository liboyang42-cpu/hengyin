const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../..')

// 第十轮 f3 j5 L4:资料被拒时原因只在保存区说一次;重发同样内容没用,只有网络失败给「重试保存」。
test('个人资料保存:不自动弹 toast,重试只给网络失败', () => {
  const js = fs.readFileSync(path.join(root, 'pages/gerenziliao/gerenziliao.js'), 'utf8')
  const req = js.slice(js.indexOf("url: '/api/user/update'"), js.indexOf("url: '/api/user/update'") + 400)
  assert.match(req, /autoErrorToast: false/)
  assert.match(js, /saveError: '网络没连上，请检查后重试', saveRetryable: true/)
  const wxml = fs.readFileSync(path.join(root, 'pages/gerenziliao/gerenziliao.wxml'), 'utf8')
  assert.match(wxml, /title="资料没有保存" sub="\{\{saveError\}\}"\s+action="\{\{saveRetryable \? '重试保存' : ''\}\}"/)
})
