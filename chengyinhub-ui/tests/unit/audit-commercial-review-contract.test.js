const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const viewPath = path.resolve(__dirname, '../../src/views/business/audit/index.vue')
const apiPath = path.resolve(__dirname, '../../src/api/business/auditWorkbench.js')

test('统一审核台识别立项审与开售审，不混入既有 TOPIC 自动上架类型', () => {
  const source = fs.readFileSync(viewPath, 'utf8')
  assert.match(source, /value:\s*'TOPIC_PROJECT_REVIEW'/)
  assert.match(source, /value:\s*'TOPIC_SALE_REVIEW'/)
  assert.match(source, /立项审核/)
  assert.match(source, /开售审核/)
})

test('商业提审写入口同时具备权限指令与两个 phase-specific API', () => {
  const view = fs.readFileSync(viewPath, 'utf8')
  const api = fs.readFileSync(apiPath, 'utf8')
  assert.match(view, /business:audit:submit/)
  assert.match(view, /submitTopicProjectReview/)
  assert.match(view, /submitTopicSaleReview/)
  assert.match(api, /\/business\/audit\/topic\/.*\/project-submit/)
  assert.match(api, /\/business\/audit\/topic\/.*\/sale-submit/)
})
