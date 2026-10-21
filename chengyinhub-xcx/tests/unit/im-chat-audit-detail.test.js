// BL-87 审核结果详情:系统审核卡片打开「处理结果」时回读 GET /api/audit/{taskId}/detail。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { auditDetailUrl, shapeAuditDetail, shouldLoadAuditDetail, isRetryableAuditError } = require('../../subpackageB/pages/im/chat/audit-detail.js')

function loadChat() {
  let def
  const requests = []
  global.getApp = () => ({
    globalData: {}, sendRequest: o => requests.push(o), tips() {},
    getUserID: () => 1, getRequestErrorMessage: (r, f) => (r && r.msg) || f,
  })
  global.wx = { getStorageSync: () => '' }
  global.Page = c => { def = c }
  const mod = require.resolve('../../subpackageB/pages/im/chat/index.js')
  delete require.cache[mod]
  require(mod)
  const page = Object.assign({}, def)
  page.data = { ...JSON.parse(JSON.stringify(def.data)), myId: 1, type: 2 }
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.data.msgs = [{ id: 5, senderId: 0, msgType: 3,
    card: { title: '审核未通过', result: { taskId: 77, bizId: 9, outcome: '你提交的内容未通过审核', reason: '含广告', followUp: 'x' } } }]
  return { page, requests }
}

test('URL 只接受正整数 taskId', () => {
  assert.equal(auditDetailUrl(77), '/api/audit/77/detail')
  assert.equal(auditDetailUrl('0'), '')
  assert.equal(auditDetailUrl('../x'), '')
})

test('只映射申请人视图的状态与时间线动作', () => {
  const out = shapeAuditDetail({ task: { id: 77, status: 2 }, timeline: [
    { id: 2, action: 'REJECT', reasonText: ' 含广告 ', createTime: '2026-10-15 10:00:00' },
    { id: 1, action: 'SUBMIT', createTime: '2026-10-15 09:00:00' },
    { id: 3, action: 'REPORT' },
  ] })
  assert.equal(out.statusLabel, '审核未通过')
  assert.deepEqual(out.timeline.map(r => r.label), ['审核未通过', '提交审核'])
  assert.equal(out.timeline[0].reason, '含广告')
  assert.equal(shapeAuditDetail({ timeline: [] }), null)
  assert.equal(shapeAuditDetail({ task: { status: 9 } }).statusLabel, '')
})

test('点审核卡:打开半屏并 GET 详情,成功后展示状态', () => {
  const { page, requests } = loadChat()
  page.onCardTap({ currentTarget: { dataset: { msg: 5 } } })
  assert.equal(page.data.reviewResult.taskId, 77)
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/audit/77/detail')
  assert.equal(requests[0].method, 'GET')
  assert.equal(page.data.reviewDetail.state, 'loading')
  requests[0].success({ code: 200, data: { task: { id: 77, status: 2 }, timeline: [] } })
  assert.equal(page.data.reviewDetail.state, 'ready')
  assert.equal(page.data.reviewDetail.statusLabel, '审核未通过')
})

test('403 如实回显后端文案;关闭后迟到回包不再写入', () => {
  const { page, requests } = loadChat()
  page.onCardTap({ currentTarget: { dataset: { msg: 5 } } })
  requests[0].success({ code: 403, msg: '无权查看该审批记录' })
  assert.equal(page.data.reviewDetail.state, 'error')
  assert.equal(page.data.reviewDetail.error, '无权查看该审批记录')
  assert.equal(page.data.reviewDetail.retryable, false)
  page.retryReviewDetail()
  assert.equal(requests.length, 1, '403 不给重试,重试入口也不再发请求')
})

test('网络失败可重试;关闭后迟到回包不再写入', () => {
  const { page, requests } = loadChat()
  page.onCardTap({ currentTarget: { dataset: { msg: 5 } } })
  requests[0].fail({ errMsg: 'request:fail' })
  assert.equal(page.data.reviewDetail.state, 'error')
  assert.equal(page.data.reviewDetail.retryable, true)
  page.retryReviewDetail()
  assert.equal(requests.length, 2)
  page.closeReviewResult()
  requests[1].success({ code: 200, data: { task: { id: 77, status: 1 }, timeline: [] } })
  assert.equal(page.data.reviewDetail, null)
})

test('被关组局的组局者/成员卡片不请求详情(被举报方必 403);403/404 不可重试', () => {
  const { page, requests } = loadChat()
  page.data.msgs[0].card.title = '你参与的组局已关闭'
  page.onCardTap({ currentTarget: { dataset: { msg: 5 } } })
  assert.equal(page.data.reviewResult.taskId, 77)
  assert.equal(requests.length, 0)
  assert.equal(page.data.reviewDetail, null)
  assert.equal(shouldLoadAuditDetail({ title: '你的组局已关闭' }), false)
  assert.equal(shouldLoadAuditDetail({ title: '举报已处理' }), true)
  assert.equal(isRetryableAuditError({ code: 404 }), false)
  assert.equal(isRetryableAuditError({ code: 500 }), true)
})
