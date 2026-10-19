const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const src = path.resolve(__dirname, '../../src')

function interceptors() {
  const hooks = {}
  const service = { interceptors: {
    request: { use() {} }, response: { use(success, error) { hooks.success = success; hooks.error = error } }
  } }
  const code = fs.readFileSync(path.join(src, 'utils/request.js'), 'utf8')
    .replace(/^import[^\n]*\n/gm, '')
    .replace(/export default service/, '')
    .replace(/export /g, '')
  new Function('axios', 'errorCode', 'Message', 'Notification', 'MessageBox', 'store', 'console', code)(
    { defaults: { headers: {} }, create: () => service }, {}, () => {}, { error() {} },
    { confirm: () => Promise.reject(new Error('stay')) }, { dispatch() {} }, { log() {}, warn() {} }
  )
  return hooks
}
function response(code, enabled) {
  return { data: { code, msg: 'server message', errorCode: 'AUDIT_SNAPSHOT_STALE' },
    request: {}, config: { preserveBusinessError: enabled } }
}

test('audit opt-in preserves AjaxResult code and errorCode for conflict, validation, service, auth and permission errors', async () => {
  const hooks = interceptors()
  for (const code of [400, 409, 500, 601, 401, 403]) {
    await assert.rejects(hooks.success(response(code, true)), error => error instanceof Error
      && error.code === code && error.errorCode === 'AUDIT_SNAPSHOT_STALE')
  }
})

test('other request callers retain their legacy rejected value and successful responses are unchanged', async () => {
  const hooks = interceptors()
  for (const code of [400, 409, 601, 403]) {
    await hooks.success(response(code, false)).then(() => assert.fail('expected rejection'), error => assert.equal(error, 'error'))
  }
  await assert.rejects(hooks.success(response(500, false)), error => error instanceof Error && error.code === undefined)
  const ok = response(200, true); assert.equal(hooks.success(ok), ok.data)
})

test('HTTP failure classification is preserved only for the explicit audit opt-in; network failures remain uncertain', async () => {
  const hooks = interceptors()
  const error = Object.assign(new Error('Request failed with status code 409'), {
    config: { preserveBusinessError: true }, response: { status: 409, data: { errorCode: 'AUDIT_STATE_CONFLICT' } }
  })
  await assert.rejects(hooks.error(error), value => value === error && value.code === 409 && value.errorCode === 'AUDIT_STATE_CONFLICT')
  const offline = Object.assign(new Error('Network Error'), { config: { preserveBusinessError: true } })
  await assert.rejects(hooks.error(offline), value => value === offline && value.code === undefined)
  const legacy = Object.assign(new Error('Request failed with status code 403'), { config: {}, response: { status: 403 } })
  await assert.rejects(hooks.error(legacy), value => value.code === undefined)
})

test('only reviewer API opts in; original payload and existing admin route pass through unchanged', async () => {
  const calls = []
  const code = fs.readFileSync(path.join(src, 'api/business/auditWorkbench.js'), 'utf8')
    .replace(/^import[^\n]*\n/gm, '').replace(/export /g, '')
  const api = new Function('request', code + '\nreturn { reviewAuditTask, getAuditTaskDetail, listAuditTasks };')(options => { calls.push(options); return Promise.resolve({ code: 200 }) })
  const payload = { taskId: 31, expectedVersion: 2, status: 1, reasonText: null }
  await api.reviewAuditTask(payload); await api.getAuditTaskDetail(31); await api.listAuditTasks({ bizType: 'CONTENT_CAPTURE_REVIEW' })
  assert.equal(calls[0].url, '/business/audit/review'); assert.equal(calls[0].data, payload)
  assert.equal(calls[0].preserveBusinessError, true); assert.equal(calls[1].preserveBusinessError, undefined)
  assert.equal(calls[1].url, '/business/audit/31/detail'); assert.equal(calls[2].params.bizType, 'CONTENT_CAPTURE_REVIEW')
})
