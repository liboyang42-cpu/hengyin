// V6b 验收（CX-15 社区帖子）：点赞的回包只能改它所属的那条帖子，不能串到别的帖子。
// 广场列表 lickClick 按点击时的下标写回 list[index]；赞在途时列表因返回刷新（markListDirty → onShow 重拉）换了序，
// 晚到的回包会把 A 的赞数写到此刻排在那个下标的 B 上。对照组证明列表不变时回包照常落到 A。
// 标 todo 的是 master 上复现出的缺陷，修复后去掉 todo 即成回归用例。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const LIST = path.resolve(__dirname, '../../pages/square/list/index.js')
const CLIENT = path.resolve(__dirname, '../../utils/community-post-api.js')

function loadList() {
  const calls = []
  let definition
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 9, sendRequest() {}, tips() {} }
  global.getApp = () => app
  global.getCurrentPages = () => []
  global.Page = (config) => { definition = config }
  global.wx = { getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {}, showToast() {},
    getSystemInfoSync: () => ({ windowWidth: 375 }), nextTick: (fn) => fn() }
  // 只替换社区传输层：页面在模块加载时 createClient，这里把 request 换成记录调用、手动回包
  const real = require(CLIENT)
  require.cache[CLIENT].exports = Object.assign({}, real, {
    createClient: () => ({ request: (options) => { calls.push(options); return { abort() {} } } }),
  })
  delete require.cache[LIST]
  try { require(LIST) } finally { require.cache[CLIENT].exports = real }
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) {
      Object.keys(patch).forEach((key) => {
        const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.')
        let cursor = this.data
        for (let i = 0; i < parts.length - 1; i += 1) cursor = cursor[parts[i]]
        cursor[parts[parts.length - 1]] = patch[key]
      })
    },
  })
  return { page, calls }
}
const A = () => ({ id: 101, contents: '帖子A', isLiked: 0, likeCount: 3, version: 1 })
const B = () => ({ id: 202, contents: '帖子B', isLiked: 0, likeCount: 10, version: 1 })
const tapLike = (index) => ({ detail: { index, type: 1 }, currentTarget: { dataset: { index, type: 1 } } })

test('V6b CX-15：列表不变时，点 A 的赞只发 A 的 id，回包只改 A', () => {
  const { page, calls } = loadList()
  page.data.list = [A(), B()]
  page.lickClick(tapLike(0))
  assert.equal(calls.length, 1)
  assert.equal(calls[0].data.id, 101)
  calls[0].success({ code: 200, data: { isLiked: 1, likeCount: 4, version: 2 } })
  assert.deepEqual(page.data.list.map(p => [p.id, p.isLiked, p.likeCount]), [[101, 1, 4], [202, 0, 10]])
})

test('V6b CX-15 缺陷复现：赞在途时列表刷新换序，A 的晚到回包写到了 B 上', { todo: 'DEFECT pages/square/list/index.js:187-194 lickClick 按旧下标 list[index] 写回，不核对 id' }, () => {
  const { page, calls } = loadList()
  page.data.list = [A(), B()]
  page.lickClick(tapLike(0))
  page.data.list = [B(), A()] // 从详情返回后 onShow 重拉，B 排到了前面
  calls[0].success({ code: 200, data: { isLiked: 1, likeCount: 4, version: 2 } })
  const b = page.data.list.find(p => p.id === 202)
  assert.deepEqual([b.isLiked, b.likeCount], [0, 10], 'A 的点赞结果串到了 B')
})
