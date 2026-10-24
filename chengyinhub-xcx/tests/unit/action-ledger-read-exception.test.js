const test = require('node:test')
const assert = require('node:assert/strict')
const { isWriteApi } = require('../../scripts/uiaudit/build-action-ledger.js')

// gate-ledger-read-contract:/api/publish/home(发布广场首页聚合,只读)走精确读例外判读;
// /publish/ 通用写规则不放宽 —— 同前缀/同动词的真实发布写口仍判写,没登记的 /api/publish/* 新端点也仍判写。
// 台账内容侧断言(消费者 writeApis/requiredRequests 不含该读口)待批次集成重生 action-ledger.json 后由 PR 正文 CY-HANDOFF 落入 action-ledger.test.js。
test('GET /api/publish/home 是读;真实发布写端点仍是写', () => {
  assert.equal(isWriteApi('/api/publish/home'), false)
  for (const api of ['/api/activity/publish', '/api/coupon/publish', '/api/template/publish',
    '/api/v1/community/posts/{param}/publish', '/api/activity/update_publish_status', '/api/official/publish',
    '/api/publish/submit', '/api/publish/home/save']) {
    assert.equal(isWriteApi(api), true, `${api} 必须仍判写`)
  }
})

test('核销回执 POST 只读，核销写入及未登记端点仍判写', () => {
  for (const api of ['/api/verify/seat/receipt', '/api/verify/citynode/receipt']) {
    assert.equal(isWriteApi(api), false, `${api} 只回读原事实`)
  }
  for (const api of ['/api/verify/citynode/redeem', '/api/verify/groupcode/issue',
    '/api/verify/groupcode/redeem', '/api/verify/seat/receipt/save',
    '/api/verify/citynode/receipt/update', '/api/verify/future/receipt']) {
    assert.equal(isWriteApi(api), true, `${api} 不得被精确读登记放行`)
  }
})
