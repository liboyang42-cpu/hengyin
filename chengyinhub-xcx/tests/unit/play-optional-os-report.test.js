const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const pagePath = path.resolve(__dirname, '../../pages/play/index.js')

function harness() {
  let definition
  const requests = []
  global.Page = config => { definition = config }
  global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }) }
  global.getApp = () => ({ globalData: { features: {} }, sendRequest: request => requests.push(request) })
  delete require.cache[require.resolve(pagePath)]
  require(pagePath)
  const page = Object.assign({}, definition, {
    data: Object.assign({}, JSON.parse(JSON.stringify(definition.data)), {
      mode: 2, topicId: 73, allDone: true, doneCount: 2, total: 2,
      ending: { show: true, opener: '已完成的故事', fragments: [] },
    }),
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.')
        let target = this.data
        for (const part of parts.slice(0, -1)) target = target[part]
        target[parts[parts.length - 1]] = value
      }
    },
  })
  return { page, requests }
}
const settle = () => new Promise(resolve => setImmediate(resolve))

test('附加偏好报告的业务拒绝不能弹全局错误或改写已通关事实', async () => {
  const { page, requests } = harness()
  page.loadNewLifeOS()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].silentError, true)
  requests[0].success({ code: 500, msg: '完成主题后才能生成新生活操作系统' })
  await settle()
  assert.equal(page.data.newLifeOS.loading, false)
  assert.equal(page.data.newLifeOS.show, false)
  assert.equal(page.data.allDone, true)
  assert.equal(page.data.doneCount, 2)
  assert.equal(page.data.ending.opener, '已完成的故事')
})

test('附加报告成功仍展示服务器内容', async () => {
  const { page, requests } = harness()
  page.loadNewLifeOS()
  requests[0].success({ code: 200, data: { resultCards: [{ title: '已确认偏好' }], actions7Days: ['清柜子'] } })
  await settle()
  assert.equal(page.data.newLifeOS.show, true)
  assert.deepEqual(page.data.newLifeOS.resultCards, [{ title: '已确认偏好' }])
  assert.deepEqual(page.data.newLifeOS.actions7Days, ['清柜子'])
})
