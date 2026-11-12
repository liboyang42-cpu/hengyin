const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// x-fix3 A-1：我的节点玩法卡片不能把库里的字面 "null" 当简介印出来。
const ROOT = path.resolve(__dirname, '../..')
const JS = path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.js')

function loadPage() {
  let config
  global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => '', sendRequest() {} })
  global.Page = c => { config = c }
  global.wx = { getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } } } }
  delete require.cache[require.resolve(JS)]
  require(JS)
  return config
}

test('字面 null/undefined 简介按空处理，正常简介原样保留', () => {
  const page = loadPage()
  assert.equal(page.decorateItem({ id: 1, title: '答题', description: 'null' }).description, '')
  assert.equal(page.decorateItem({ id: 2, title: '答题', description: ' undefined ' }).description, '')
  assert.equal(page.decorateItem({ id: 3, title: '答题', description: null }).description, '')
  assert.equal(page.decorateItem({ id: 4, title: '答题', description: '找出 3 处线索' }).description, '找出 3 处线索')
})

test('简介为空时不渲染简介行', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.wxml'), 'utf8')
  assert.match(wxml, /<view class="item-desc" wx:if="\{\{item\.description\}\}">\{\{item\.description\}\}<\/view>/)
})
