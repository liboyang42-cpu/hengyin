const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const src = path.resolve(__dirname, '../../src')
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = async () => { for (let n = 0; n < 8; n++) await Promise.resolve() }
function setup() {
  let epoch = 0, token = 'fixture-a', id = 4, role = 'reviewer'
  const dialogs = [], notices = [], messages = [], calls = [], hooks = {}, logoutRequest = deferred()
  const location = { href: '/business/audit' }
  const context = () => { const captured = epoch; return { isCurrent: () => epoch === captured, captureCurrent: context } }
  const commit = (key, value) => { epoch++; if (key === 'SET_TOKEN') token = value }
  const userSource = fs.readFileSync(path.join(src, 'store/modules/user.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export default user/, 'return user')
  const user = new Function('getToken', 'logout', 'removeToken', userSource)(() => token, value => { calls.push(['logout', value]); return logoutRequest.promise }, () => { token = '' })
  const store = { getters: { get token() { return token }, get id() { return id } }, dispatch(name, options) { calls.push(['dispatch', name]); return user.actions[name]({ commit, state: { get token() { return token } } }, options) } }
  const service = { interceptors: { request: { use(success) { hooks.request = success } }, response: { use(success, error) { hooks.success = success; hooks.error = error } } } }
  const requestSource = fs.readFileSync(path.join(src, 'utils/request.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export default service/, '').replace(/export /g, '')
  new Function('axios', 'errorCode', 'Message', 'Notification', 'MessageBox', 'store', 'console', 'getToken', 'cache', 'location', requestSource)(
    { defaults: { headers: {} }, create: () => service }, {}, value => messages.push(value), { error: value => notices.push(value) },
    { confirm: () => { const dialog = deferred(); dialogs.push(dialog); return dialog.promise } }, store, { log() {}, warn() {} }, () => token,
    { session: { getJSON: () => null, setJSON() {} } }, location)
  const change = (key, value) => { epoch++; if (key === 'token') token = value; if (key === 'id') id = value; if (key === 'role') role = value }
  return { hooks, dialogs, notices, messages, calls, logoutRequest, location, context, change,
    state: () => ({ token, id, role }), store }
}
const response = (code, auditContext) => ({ data: { code, msg: 'server failure' }, request: {}, config: { preserveBusinessError: true, auditContext } })

for (const key of ['token', 'id', 'role']) test(`old ${key} ABA response cannot present an audit relogin dialog`, async () => {
  const h = setup(), context = h.context(), original = h.state()[key]
  h.change(key, key === 'id' ? 9 : 'fixture-b'); h.change(key, original)
  await assert.rejects(h.hooks.success(response(401, context)))
  assert.equal(h.dialogs.length, 0); assert.equal(h.calls.length, 0); assert.equal(h.location.href, '/business/audit')
})

for (const key of ['token', 'id', 'role']) test(`old ${key} ABA while dialog is open cannot log out the new context`, async () => {
  const h = setup(), original = h.state()[key]
  await assert.rejects(h.hooks.success(response(401, h.context())))
  assert.equal(h.dialogs.length, 1)
  h.change(key, key === 'id' ? 9 : 'fixture-b'); h.change(key, original)
  h.dialogs[0].resolve(); await flush()
  assert.equal(h.calls.length, 0); assert.equal(h.location.href, '/business/audit'); assert.equal(h.state().token, 'fixture-a')
})

test('retired audit requests are stopped before HTTP setup and before generic HTTP error notifications', async () => {
  const h = setup(), context = h.context(); h.change('role', 'viewer')
  const config = { method: 'post', url: '/business/audit/review', headers: {}, data: { taskId: 31 }, auditContext: context }
  await assert.rejects(Promise.resolve().then(() => h.hooks.request(config)))
  assert.equal(config.headers.Authorization, undefined)
  const error = Object.assign(new Error('Request failed with status code 403'), { config, response: { status: 403 } })
  await assert.rejects(h.hooks.error(error)); assert.equal(h.messages.length, 0); assert.equal(h.notices.length, 0)
})

test('current audit 401 still opens confirmation, logs out, and redirects after successful logout', async () => {
  const h = setup(); await assert.rejects(h.hooks.success(response(401, h.context())))
  h.dialogs[0].resolve(); await flush(); assert.deepEqual(h.calls, [['dispatch', 'LogOut'], ['logout', 'fixture-a']])
  h.logoutRequest.resolve(); await flush(); assert.equal(h.state().token, ''); assert.equal(h.location.href, '/index')
})

test('context change while logout HTTP is pending cannot clear a newly authenticated session', async () => {
  const h = setup(); await assert.rejects(h.hooks.success(response(401, h.context())))
  h.dialogs[0].resolve(); await flush(); h.change('token', 'fixture-new-session'); h.change('id', 9)
  h.logoutRequest.resolve(); await flush()
  assert.equal(h.state().token, 'fixture-new-session'); assert.equal(h.state().id, 9); assert.equal(h.location.href, '/business/audit')
})

test('post-logout redirect checks a new context fence, including role ABA between completion and redirect', async () => {
  const h = setup(), capture = h.context()
  const original = capture.captureCurrent
  capture.captureCurrent = () => {
    const afterLogout = original()
    // Queue the account event after the action resolves but before the interceptor continuation.
    Promise.resolve().then(() => { h.change('role', 'viewer'); h.change('role', 'reviewer') })
    return afterLogout
  }
  await assert.rejects(h.hooks.success(response(401, capture))); h.dialogs[0].resolve(); await flush()
  h.logoutRequest.resolve(); await flush(); assert.equal(h.location.href, '/business/audit')
})

test('unrelated request 401 and ordinary LogOut preserve the existing confirmation and redirect contract', async () => {
  const h = setup(); await assert.rejects(h.hooks.success(response(401)))
  assert.equal(h.dialogs.length, 1); h.dialogs[0].resolve(); await flush(); h.logoutRequest.resolve(); await flush()
  assert.equal(h.location.href, '/index'); assert.equal(h.state().token, '')
})

test('context hooks remain local Axios options and never enter URL, params, headers, or review DTO', async () => {
  const calls = [], context = setup().context()
  const code = fs.readFileSync(path.join(src, 'api/business/auditWorkbench.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export /g, '')
  const api = new Function('request', code + '\nreturn { listAuditTasks, getAuditTaskDetail, reviewAuditTask, submitTopicProjectReview, submitTopicSaleReview };')(value => { calls.push(value); return Promise.resolve() })
  const dto = { taskId: 31, expectedVersion: 2, status: 1 }
  await api.listAuditTasks({ bizType: 'CONTENT_CAPTURE_REVIEW' }, context); await api.getAuditTaskDetail(31, context)
  await api.reviewAuditTask(dto, context); await api.submitTopicProjectReview(14, { requestedTarget: 'PRICING' }, context); await api.submitTopicSaleReview(14, context)
  for (const call of calls) {
    assert.equal(call.auditContext, context)
    for (const key of ['url', 'params', 'headers', 'data']) assert.doesNotMatch(JSON.stringify(call[key]) || '', /auditContext|isCurrent|captureCurrent|fixture/)
  }
  assert.equal(calls[2].data, dto)
})

function loginApi(request) {
  const code = fs.readFileSync(path.join(src, 'api/login.js'), 'utf8').replace(/^import[^\n]*\n/gm, '').replace(/export /g, '')
  return new Function('request', code + '\nreturn { logout };')(request)
}

test('audit logout binds its outgoing Authorization to the confirmed session and never adopts a later credential', async () => {
  const h = setup(), context = h.context(), configs = []
  const api = loginApi(config => { configs.push(config); return Promise.resolve() })
  await api.logout('fixture-a', context)
  assert.equal(configs[0].headers.Authorization, 'Bearer fixture-a'); assert.equal(configs[0].headers.isToken, false)
  h.change('token', 'fixture-b')
  await assert.rejects(h.hooks.request(configs[0])); assert.equal(configs[0].headers.Authorization, 'Bearer fixture-a')
  // Once prepared, even a current-token difference cannot overwrite the explicit captured Authorization.
  const bound = { ...configs[0], auditContext: { isCurrent: () => true } }
  const sent = await h.hooks.request(bound); assert.equal(sent.headers.Authorization, 'Bearer fixture-a')
  assert.doesNotMatch(JSON.stringify(sent.headers), /auditContext|isCurrent|captureCurrent|fixture-b/)
})

test('ordinary logout still uses the current request-interceptor credential and no audit callbacks', async () => {
  const h = setup(), configs = []; const api = loginApi(config => { configs.push(config); return Promise.resolve() })
  await api.logout('ignored-legacy-argument'); assert.equal(configs[0].auditContext, undefined)
  configs[0].headers = {}; h.change('token', 'fixture-current')
  assert.equal((await h.hooks.request(configs[0])).headers.Authorization, 'Bearer fixture-current')
})

for (const key of ['token', 'id', 'role']) test(`${key} ABA during logout request cannot clear the session or redirect`, async () => {
  const h = setup(), original = h.state()[key]
  await assert.rejects(h.hooks.success(response(401, h.context()))); h.dialogs[0].resolve(); await flush()
  h.change(key, key === 'id' ? 9 : 'fixture-b'); h.change(key, original)
  h.logoutRequest.resolve(); await flush()
  assert.equal(h.state().token, 'fixture-a'); assert.equal(h.location.href, '/business/audit')
})

test('logout request rejected after retirement is handled without a redirect or unhandled rejection', async () => {
  const h = setup(); await assert.rejects(h.hooks.success(response(401, h.context())))
  h.dialogs[0].resolve(); await flush(); h.change('token', 'fixture-new')
  h.logoutRequest.reject(Object.assign(new Error('context retired'), { errorCode: 'AUDIT_CONTEXT_RETIRED' })); await flush()
  assert.equal(h.state().token, 'fixture-new'); assert.equal(h.location.href, '/business/audit')
})
