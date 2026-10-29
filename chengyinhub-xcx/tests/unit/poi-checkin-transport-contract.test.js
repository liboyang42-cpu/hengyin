'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequestClient } = require('../../utils/transport/request-client.js')
const sourcePath = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-roam-poi-detail/index.js')
const readSource = () => fs.readFileSync(sourcePath, 'utf8')

function mount({ source = readSource(), storage = new Map(), owner = 7, poiId = '21', wxOverrides = {}, realMedia = false } = {}) {
  let definition
  const requests = []; const locations = []; const events = []; const notices = []
  const images = []; const modals = []; const scans = []; const settings = []
  const identity = { owner }
  const client = createRequestClient({
    wxRequest(options) { requests.push(options); return { abort() {} } },
    getBaseUrl: () => 'https://fixture.invalid',
    getAuthorization: () => 'fixture-session-' + identity.owner,
    getUserID: () => identity.owner,
  })
  const app = { getUserID: () => identity.owner, sendRequest: options => client.send(options), chooseImage(callback) { images.push(callback) } }
  const wx = {
    getLocation(options) { locations.push(options) },
    getStorageSync(key) { return storage.get(key) },
    setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync(key) { storage.delete(key) },
    showToast(options) { notices.push(options.title) },
    showModal(options) { modals.push(options) },
    scanCode(options) { scans.push(options) },
    openSetting(options) { settings.push(options) },
    ...wxOverrides,
  }
  const media = { choices: [], uploads: [], operations: [], deliveries: 0 }
  if (realMedia) {
    // 执行真实 app.chooseImage、page-bound-operation 和 upload-client；只控制原生回调顺序。
    const root = path.resolve(__dirname, '../..'); const page = {}
    const { createPageBoundOperation } = require('../../utils/page-bound-operation.js')
    let mediaApp
    wx.chooseMedia = options => media.choices.push(options)
    wx.uploadFile = options => { media.uploads.push(options); return { abort() {} } }
    require('node:vm').runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
      App: value => { mediaApp = value }, wx, getCurrentPages: () => [page], console,
      require: id => id === './utils/loading.js' ? { show() {}, hide() {} }
        : id === './utils/toast.js' ? value => notices.push(value)
          : id === './utils/page-bound-operation.js' ? {
            createPageBoundOperation: options => createPageBoundOperation({ ...options, setInterval: () => 1, clearInterval() {} }),
          } : require(path.join(root, id)),
    }, { filename: 'app.js' })
    mediaApp.getUserID = () => identity.owner
    mediaApp.getAuthorization = () => 'fixture-session-' + identity.owner
    mediaApp.globalData.siteBaseUrl = 'https://fixture.invalid'
    mediaApp.showModal = options => modals.push(options)
    mediaApp.cropAll = paths => Promise.resolve(paths)
    app.chooseImage = (callback, count, options) => {
      const operation = mediaApp.chooseImage(urls => { media.deliveries += 1; callback(urls) }, count, options)
      media.operations.push(operation)
      return operation
    }
  }
  vm.runInNewContext(source, {
    Component(options) { definition = options }, getApp: () => app, wx,
    require(id) { return require(path.resolve(path.dirname(sourcePath), id)) },
  }, { filename: sourcePath })
  const instance = {
    data: { ...JSON.parse(JSON.stringify(definition.data)), poiId, merchantId: '' },
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const keys = key.split('.'); let cursor = this.data
        for (const part of keys.slice(0, -1)) cursor = cursor[part]
        cursor[keys.at(-1)] = value
      }
    },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    ...definition.methods,
  }
  const respond = (request, body, statusCode = 200) => {
    const response = { statusCode, data: body }
    request.success(response)
    request.complete(response)
  }
  const get = (flags = {}, statusCode = 200) => respond(requests.at(-1), {
    code: statusCode === 200 ? 200 : statusCode,
    data: { poiId: instance.data.poiId, status: 1, validationMethod: 5, completed: false, needRedeem: false, ...flags },
  }, statusCode)
  definition.lifetimes.attached.call(instance)
  assert.equal(requests[0].method, 'GET')
  get()
  return {
    instance, storage, requests, locations, events, notices, identity, respond, get, images, modals, scans, settings, definition, media,
    posts: () => requests.filter(request => request.method === 'POST' && /\/api\/city\/nodes\/\d+\/complete$/.test(request.url)),
    begin() { instance.completeNode(''); locations.at(-1).success({ latitude: 31.2, longitude: 121.5 }); return requests.at(-1) },
    detach() { definition.lifetimes.detached.call(instance) },
  }
}

test('Long owner/POI/coupon IDs survive UNKNOWN, reopen and positive recovery exactly', () => {
  const owner = '9223372036854775807', poiId = '9007199254740993'
  const h = mount({ owner, poiId })
  assert.equal(h.instance.data.state, 'ready')
  const post = h.begin()
  assert.ok(post.url.endsWith('/' + poiId + '/complete'))
  post.fail({ errMsg: 'timeout' }); post.complete({})
  const key = 'cy_roam_poi_checkin_pending:v1:' + owner + ':' + poiId
  const pending = h.storage.get(key)
  assert.equal(pending.ownerId, owner)
  assert.equal(pending.poiId, poiId)
  const reopened = mount({ owner, poiId, storage: h.storage })
  assert.equal(reopened.instance.data.checkinPending, true)
  assert.equal(reopened.storage.get(key).attemptId, pending.attemptId)
  reopened.instance.reverifyOnSite(); reopened.get()
  const retry = reopened.begin()
  reopened.respond(retry, { code: 200, data: { poiId, needRedeem: true, completed: false, couponId: '9007199254740995' } })
  assert.equal(reopened.storage.size, 0)
  assert.equal(reopened.events.filter(e => e.name === 'completed').length, 1)
  assert.equal(reopened.events.at(-1).detail.recovered, true)
})

test('review ownership handles numeric and string IDs and never marks another owner mine', () => {
  for (const owner of [7, '7', '9223372036854775807']) {
    const h = mount({ owner })
    h.instance.loadReviews()
    h.respond(h.requests.at(-1), { code: 200, data: { rows: [
      { id: '31', memberId: String(owner) }, { id: '32', memberId: '8' },
    ] } })
    assert.equal(h.instance.data.reviews[0].mine, true)
    assert.equal(h.instance.data.reviews[1].mine, false)
  }
})

function assertLocationSingleUse(source) {
  const h = mount({ source })
  const post = h.begin(); const location = h.locations[0]
  h.respond(post, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.size, 0)
  assert.equal(h.events.filter(event => event.name === 'completed').length, 1)
  location.success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 1, '同一 native success 只能消费一次，清锁后也不得再次 POST')
  location.fail({ errMsg: 'getLocation:fail auth deny' })
  assert.equal(h.instance.data.interactionState, 'idle', '已消费的 native fail 不得覆盖成功 UI')
}

test('真实 request-client：定位 success 在成功清锁后仍只能消费一次', () => assertLocationSingleUse())

test('真实 request-client：定位 fail 先到后迟来的 success 不得发送，显式重试可发送', () => {
  const h = mount()
  h.instance.completeNode('')
  h.locations[0].fail({ errMsg: 'getLocation:fail system error' })
  h.locations[0].success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 0)
  h.instance.recoverInteraction()
  h.locations[1].success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 1)
})

function assertReceiptUnknown(data, source) {
  const h = mount({ source }); const post = h.begin()
  h.respond(post, { code: 200, data })
  assert.equal(h.storage.size, 1, '矛盾或异对象回执不能清原 pending')
  assert.equal(h.instance.data.checkinPending, true)
  assert.equal(h.instance.data.interactionState, 'unknown')
  assert.equal(h.events.length, 0, '未知回执不能发完成事件或开码')
  assert.equal(h.notices.includes('打卡成功'), false)
  h.instance.recoverInteraction(); h.get()
  assert.equal(h.posts().length, 1, '只能 GET 确认，不能重放 POST')
}

for (const couponId of [null, undefined, 0, -1, true, '088', 'x', [], {}]) {
  test('真实 request-client：待核销须有严格正 couponId '+JSON.stringify(couponId), () => {
    assertReceiptUnknown({ needRedeem: true, couponId, alreadyClaimed: true })
  })
}
for (const identity of [{ poiId: 99 }, { poiId: null }, { poiId: '021' }, { poiId: true }, { id: 99 }, { poiId: 21, id: 22 }]) {
  test('真实 request-client：显式回执标识必须匹配原 POI '+JSON.stringify(identity), () => {
    assertReceiptUnknown({ ...identity, needRedeem: false, couponId: null })
  })
}
for (const data of [
  { needRedeem: false, couponId: null },
  { poiId: 21, needRedeem: false, couponId: null },
  { poiId: '21', id: 21, needRedeem: true, couponId: '88', alreadyClaimed: true },
]) {
  test('真实 request-client：合法完成或待核销回执保持原行为 '+JSON.stringify(data), () => {
    const h = mount(); h.respond(h.begin(), { code: 200, data })
    assert.equal(h.storage.size, 0)
    assert.equal(h.instance.data.checkinPending, false)
    assert.equal(h.instance.data.node.completed, !data.needRedeem)
    assert.equal(h.events.filter(event => event.name === 'completed').length, 1)
    assert.equal(h.events.some(event => event.name === 'open'), data.needRedeem)
  })
}
for (const statusCode of [202, 410, 500, 503]) {
  test('真实 request-client：HTTP '+statusCode+' 即便含明确拒绝文本也不能释放', () => {
    const h = mount(); h.respond(h.begin(), { code: 500, msg: '答案不正确' }, statusCode)
    assert.equal(h.storage.size, 1)
    assert.equal(h.instance.data.interactionState, 'unknown')
  })
}

test('真实 request-client：仅 HTTP200 的明确写前拒绝允许重新作答', () => {
  const h = mount(); h.respond(h.begin(), { code: 500, msg: '答案不正确' })
  assert.equal(h.storage.size, 0)
  assert.equal(h.instance.data.interactionActionText, '重新作答')
  h.instance.completeNode('新答案'); h.locations.at(-1).success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 2)
  assert.equal(h.posts()[1].data.answer, '新答案')
})
for (const failure of ['timeout', 'empty']) {
  test('真实 request-client：'+failure+' 冷重开仍只 GET；负查询不能证明未写', () => {
    const h = mount(); const post = h.begin()
    if (failure === 'timeout') post.fail({ errMsg: 'request:fail timeout' })
    else h.respond(post, { code: 200, data: null })
    h.detach()
    const reopened = mount({ storage: h.storage })
    reopened.instance.completeNode('')
    assert.equal(reopened.posts().length, 0)
    assert.equal(reopened.locations.length, 0)
    assert.equal(reopened.storage.size, 1)
    reopened.instance.recoverInteraction(); reopened.get({ completed: true, needRedeem: false })
    assert.equal(reopened.storage.size, 0)
    assert.equal(reopened.events.at(-1).detail.recovered, true)
    assert.equal(reopened.notices.includes('打卡成功'), false)
  })
}

test('真实 request-client：落盘失败不发送，保留可见恢复入口', () => {
  const h = mount({ wxOverrides: { setStorageSync() { throw Error('disk') } } })
  h.begin()
  assert.equal(h.posts().length, 0)
  assert.match(h.instance.data.interactionText, /存储/)
})

test('真实 request-client：换 POI 后迟回包只清原 key，保留新据点代次', () => {
  const h = mount(); const old = h.begin(); const oldKey = [...h.storage.keys()][0]
  h.instance.data.poiId = '22'; h.instance._load(); h.get()
  h.begin()
  h.respond(old, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.has(oldKey), false)
  assert.equal(h.storage.size, 1)
  assert.equal([...h.storage.values()][0].poiId, 22)
  assert.equal(h.instance.data.node.completed, false)
})

test('真实 request-client：换 owner 后传输层抑制旧成功，新身份 key 保留', () => {
  const h = mount(); const old = h.begin()
  h.identity.owner = 8; h.instance._load(); h.get(); h.begin()
  h.respond(old, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.size, 2)
  assert.equal(h.events.length, 0)
  assert.equal(h.instance.data.checkinPending, true)
})

test('真实 request-client 负控：删除 native 消费、券 ID 或 POI 校验分别必红', () => {
  const source = readSource()
  const cases = [
    [source.replaceAll(' || locationSettled', ''), assertLocationSingleUse],
    [source.replace('&& matchingPoi && validCoupon', '&& matchingPoi'), mutant => assertReceiptUnknown({ needRedeem: true, couponId: null, alreadyClaimed: true }, mutant)],
    [source.replace('&& matchingPoi && validCoupon', '&& validCoupon'), mutant => assertReceiptUnknown({ poiId: 99, needRedeem: false, couponId: null }, mutant)],
  ]
  for (const [mutant, verify] of cases) {
    assert.notEqual(mutant, source)
    assert.throws(() => verify(mutant), { name: 'AssertionError' })
    assert.doesNotThrow(() => verify(source))
  }
})

function unknownVisit(options) {
  const h = mount(options)
  const original = h.begin()
  original.fail({ errMsg: 'request:fail timeout' })
  return { ...h, original, marker: () => [...h.storage.values()].find(value => value && value.attemptId) }
}
function prepareReverification(h, flags = {}) {
  const count = h.posts().length
  h.instance.reverifyOnSite()
  assert.equal(h.requests.at(-1).method, 'GET')
  h.get(flags)
  assert.equal(h.posts().length, count, '读完当前任务不能自动 POST')
  assert.equal(h.instance.data.reverifyReady, true)
}

test('现场重核验：首次未送达，负查询保旧标记；显式新答案和新定位成功', () => {
  const h = unknownVisit(); const attempt = h.marker().attemptId
  h.instance.recoverInteraction(); h.get()
  assert.equal(h.instance.data.checkinPending, true)
  assert.match(h.instance.data.pendingStatusText, /不能据此/)
  prepareReverification(h, { validationMethod: 1 })
  h.instance.startInteract()
  assert.equal(h.posts().length, 1)
  h.modals.at(-1).success({ confirm: true, content: '新的现场答案' })
  h.locations.at(-1).success({ latitude: 32.8, longitude: 119.7 })
  const post = h.posts().at(-1)
  assert.equal(post.data.answer, '新的现场答案')
  assert.equal(post.data.lat, 32.8)
  assert.equal(post.data.lng, 119.7)
  assert.equal(h.marker().attemptId, attempt)
  assert.deepEqual(Object.keys(h.marker()).sort(), ['attemptId', 'ownerId', 'poiId', 'uncertain', 'v'])
  h.respond(post, { code: 200, data: { poiId: 21, completed: true, needRedeem: false } })
  assert.equal(h.storage.size, 0)
  assert.equal(h.events.filter(e => e.name === 'completed').length, 1)
  assert.equal(h.events[0].detail.recovered, true)
  assert.equal(h.notices.includes('打卡成功'), false)
})

for (const needRedeem of [false, true]) test('现场重核验：先读已完成/待核销即收口，无定位无写 '+needRedeem, () => {
  const h = unknownVisit()
  h.instance.reverifyOnSite(); h.get({ completed: !needRedeem, needRedeem, couponId: needRedeem ? 88 : null })
  assert.equal(h.storage.size, 0)
  assert.equal(h.instance.data.reverifyReady, false)
  h.instance.startInteract()
  assert.equal(h.locations.length, 1)
  assert.equal(h.posts().length, 1)
  assert.equal(h.events.filter(e => e.name === 'completed').length, 1)
})

for (const failure of ['reject', 'timeout', 'http', 'empty']) test('现场重核验：新调用 '+failure+' 保留旧未知，可再显式核验', () => {
  const h = unknownVisit(); const attempt = h.marker().attemptId
  prepareReverification(h)
  h.instance.startInteract(); h.locations.at(-1).success({ latitude: 30, longitude: 120 })
  const post = h.posts().at(-1)
  if (failure === 'timeout') post.fail({})
  else if (failure === 'http') h.respond(post, { code: 500, msg: '答案不正确' }, 503)
  else if (failure === 'empty') h.respond(post, { code: 200, data: null })
  else h.respond(post, { code: 500, msg: '答案不正确' })
  assert.equal(h.marker().attemptId, attempt)
  assert.equal(h.instance.data.checkinPending, true)
  assert.equal(h.events.length, 0)
  prepareReverification(h)
})

for (const flags of [{ status: 0 }, { validationMethod: 99 }, { completed: true, needRedeem: true }, { completed: 0 }]) test('现场重核验：下线或畸形当前合同不开放写 '+JSON.stringify(flags), () => {
  const h = unknownVisit()
  h.instance.reverifyOnSite(); h.get(flags)
  h.instance.startInteract()
  assert.equal(h.posts().length, 1)
  assert.equal(h.locations.length, 1)
  assert.equal(h.instance.data.reverifyReady, false)
  assert.equal(h.storage.size, 1)
})

test('现场重核验：410和查询失败保留标记，不自动写', () => {
  const h = unknownVisit(); h.instance.reverifyOnSite(); h.get({}, 410)
  assert.equal(h.instance.data.reverifyChecking, false)
  assert.equal(h.storage.size, 1)
  h.instance.reverifyOnSite(); h.requests.at(-1).fail({})
  assert.equal(h.instance.data.reverifyChecking, false)
  assert.equal(h.posts().length, 1)
})

test('现场重核验：旧 pending 无 payload 冷重入，仍可按当前扫码条件核验', () => {
  const storage = new Map([['cy_roam_poi_checkin_pending:v1:7:21', { v: 1, ownerId: 7, poiId: 21, attemptId: 'legacy' }]])
  const h = mount({ storage })
  prepareReverification(h, { validationMethod: 4 })
  h.instance.startInteract(); h.scans.at(-1).success({ result: '现场新码' })
  h.locations.at(-1).success({ latitude: 31, longitude: 121 })
  assert.equal(h.posts().at(-1).data.code, '现场新码')
  assert.equal([...storage.values()][0].attemptId, 'legacy')
})

test('现场重核验：当前照片条件重新拍照，不复用或持久化旧照片', () => {
  const h = unknownVisit(); const key = [...h.storage.keys()][0]
  h.storage.set(key + ':photo', { templateId: 9, url: 'https://old.invalid/photo' })
  prepareReverification(h, { validationMethod: 3, templateId: 9, photoRequired: true, lat: 31.2, lng: 121.5, questionA: '当前选项' })
  assert.equal(h.instance.data.challengePhoto, '')
  assert.equal(h.storage.has(key + ':photo'), false)
  h.instance.startInteract(); h.locations.at(-1).success({ latitude: 31.2, longitude: 121.5 })
  h.images.at(-1)(['https://fresh.invalid/photo'])
  assert.equal(h.instance.data.choiceSheetShow, true)
  h.instance.onChoiceSheetSelect({ detail: { index: 0 } })
  h.locations.at(-1).success({ latitude: 31.21, longitude: 121.51 })
  assert.equal(h.posts().at(-1).data.photoUrl, 'https://fresh.invalid/photo')
  assert.equal(h.posts().at(-1).data.answer, 'A')
  assert.equal(h.storage.has(key + ':photo'), false)
  assert.equal(JSON.stringify([...h.storage.values()]).includes('fresh.invalid'), false)
})

for (const method of [1, 3, 4]) test('现场重核验：取消当前互动保留旧未知 '+method, () => {
  const h = unknownVisit()
  prepareReverification(h, { validationMethod: method, questionA: '新选项' })
  h.instance.startInteract()
  if (method === 1) h.modals.at(-1).success({ confirm: false })
  if (method === 3) h.instance.onChoiceSheetCancel()
  if (method === 4) h.scans.at(-1).fail({ errMsg: 'scanCode:fail cancel' })
  assert.equal(h.storage.size, 1)
  assert.equal(h.instance.data.reverifyReady, false)
  assert.equal(h.posts().length, 1)
})

test('现场重核验：权限拒绝后设置恢复仍用本次新输入和新定位', () => {
  const h = unknownVisit(); prepareReverification(h, { validationMethod: 1 })
  h.instance.startInteract(); h.modals.at(-1).success({ confirm: true, content: '新输入' })
  h.locations.at(-1).fail({ errMsg: 'getLocation:fail auth deny' })
  assert.equal(h.instance.data.interactionState, 'location-permission')
  h.instance.recoverInteraction(); h.settings.at(-1).success({ authSetting: { 'scope.userLocation': true } })
  h.locations.at(-1).success({ latitude: 33, longitude: 120 })
  assert.equal(h.posts().at(-1).data.answer, '新输入')
  assert.equal(h.posts().at(-1).data.lat, 33)
  assert.equal(h.storage.size, 1)
})

for (const change of ['owner', 'poi', 'detach', 'query']) test('现场重核验：'+change+' 使旧互动/定位失效', () => {
  const h = unknownVisit(); prepareReverification(h)
  h.instance.startInteract(); const location = h.locations.at(-1)
  if (change === 'owner') { h.identity.owner = 8; h.instance._load(); h.get() }
  if (change === 'poi') { h.instance.data.poiId = '22'; h.instance._load(); h.get() }
  if (change === 'detach') h.detach()
  if (change === 'query') { h.instance.retry(); h.get() }
  location.success({ latitude: 30, longitude: 120 })
  assert.equal(h.posts().length, 1)
  assert.equal(h.storage.size, 1)
})

test('现场重核验：双击刷新、开始和native success都只消费一次', () => {
  const h = unknownVisit(); const before = h.requests.length
  h.instance.reverifyOnSite(); h.instance.reverifyOnSite()
  assert.equal(h.requests.length, before + 1)
  h.get(); h.instance.startInteract(); h.instance.startInteract()
  const loc = h.locations.at(-1)
  assert.equal(h.locations.length, 2)
  loc.success({ latitude: 30, longitude: 120 }); loc.success({ latitude: 30, longitude: 120 })
  assert.equal(h.posts().length, 2)
})

for (const phase of ['before', 'location', 'post', 'after']) test('现场重核验：旧正向响应 '+phase+' 到达不重复完成', () => {
  const h = unknownVisit()
  if (phase !== 'before') prepareReverification(h)
  if (phase === 'location' || phase === 'post' || phase === 'after') h.instance.startInteract()
  const location = h.locations.at(-1)
  if (phase === 'post' || phase === 'after') location.success({ latitude: 32, longitude: 120 })
  if (phase === 'after') h.respond(h.posts().at(-1), { code: 200, data: { needRedeem: false } })
  h.respond(h.original, { code: 200, data: { needRedeem: false } })
  if (phase === 'location') location.success({ latitude: 32, longitude: 120 })
  if (phase === 'post') h.respond(h.posts().at(-1), { code: 200, data: { needRedeem: false } })
  assert.equal(h.storage.size, 0)
  assert.ok(h.events.filter(e => e.name === 'completed').length <= 1)
  assert.equal(h.notices.includes('打卡成功'), false)
  assert.equal(h.posts().length, phase === 'post' || phase === 'after' ? 2 : 1)
})

test('现场重核验：旧拒绝迟到不能清除原标记或取消新流程', () => {
  const h = unknownVisit(); prepareReverification(h)
  h.respond(h.original, { code: 500, msg: '答案不正确' })
  assert.equal(h.storage.size, 1)
  assert.equal(h.instance.data.reverifyReady, true)
})

for (const failure of ['read', 'write', 'photoRemove']) test('现场重核验：存储 '+failure+' 异常不发送', () => {
  const h = unknownVisit(); prepareReverification(h)
  // 存储方法由 VM 的 wx 使用；用同一 Map 的故障注入覆盖读写/缓存清理。
  if (failure === 'read') h.storage.get = () => { throw Error('disk') }
  if (failure === 'write') h.storage.set = () => { throw Error('disk') }
  if (failure === 'photoRemove') {
    h.instance.retry(); h.get(); h.storage.delete = () => { throw Error('disk') }
    h.instance.reverifyOnSite(); h.get()
  } else {
    h.instance.startInteract()
    if (failure === 'write') h.locations.at(-1).success({ latitude: 30, longitude: 120 })
  }
  assert.equal(h.posts().length, 1)
  assert.equal(h.instance.data.checkinPending, true)
  assert.match(h.instance.data.interactionText, /存储/)
})

function beginRealPoiCamera(source) {
  const h = unknownVisit({ realMedia: true, source })
  prepareReverification(h, { validationMethod: 3, photoRequired: true, lat: 31.2, lng: 121.5, questionA: '现场选项' })
  h.instance.startInteract()
  h.locations.at(-1).success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.media.choices.length, 1)
  return h
}
function uploadRealPhoto(h) {
  h.media.uploads.at(-1).success({ data: JSON.stringify({ code: 200, url: 'https://fixture.invalid/new-camera.jpg' }) })
}
function showPoi(h) { h.definition.pageLifetimes.show.call(h.instance) }

for (const order of ['show-before-native', 'show-during-upload', 'show-after-upload']) {
  test('真实 chooseImage：'+order+' 保留同账号同地点本次照片与互动', () => {
    const h = beginRealPoiCamera()
    try {
      const token = h.instance._interactionToken
      if (order === 'show-before-native') showPoi(h)
      h.media.choices[0].success({ tempFiles: [{ tempFilePath: '/fixture/new-camera.jpg' }] })
      assert.equal(h.media.uploads.length, 1)
      if (order === 'show-during-upload') showPoi(h)
      uploadRealPhoto(h)
      if (order === 'show-after-upload') showPoi(h)
      assert.equal(h.instance._interactionToken, token + 1, '上传成功只续开本次答题，不被 show 重置')
      assert.equal(h.instance.data.challengePhoto, 'https://fixture.invalid/new-camera.jpg')
      assert.equal(h.instance.data.choiceSheetShow, true)
      assert.equal(h.media.deliveries, 1)
      assert.equal(h.posts().length, 1, '原生返回/上传成功/show 均不能自行提交答案')
      assert.equal(h.storage.size, 1)
      h.instance.onChoiceSheetSelect({ detail: { index: 0 } })
      h.locations.at(-1).success({ latitude: 31.21, longitude: 121.51 })
      assert.equal(h.posts().length, 2)
      assert.equal(h.posts().at(-1).data.photoUrl, 'https://fixture.invalid/new-camera.jpg')
    } finally { h.detach(); h.media.operations.forEach(operation => operation.abort()) }
  })
}

for (const order of ['cancel-before-show', 'show-before-cancel', 'upload-failure']) {
  test('真实 chooseImage：'+order+' 不回 success；show 或显式查询恢复旧未知', () => {
    const h = beginRealPoiCamera()
    try {
      if (order === 'show-before-cancel') showPoi(h)
      if (order === 'upload-failure') {
        h.media.choices[0].success({ tempFiles: [{ tempFilePath: '/fixture/new-camera.jpg' }] })
        h.media.uploads[0].fail({ errMsg: 'uploadFile:fail network' })
      } else h.media.choices[0].fail({ errMsg: 'chooseMedia:fail cancel' })
      assert.equal(h.media.operations[0].isFinished(), true)
      assert.equal(h.media.deliveries, 0, '真实取消/失败不调用页面成功回调，不能用空数组模拟')
      if (order === 'show-before-cancel') h.instance.retry()
      else showPoi(h)
      assert.equal(h.requests.at(-1).method, 'GET')
      h.get()
      assert.equal(h.instance.data.reverifyReady, false)
      assert.equal(h.instance.data.checkinPending, true)
      assert.equal(h.storage.size, 1)
      assert.equal(h.posts().length, 1)
      prepareReverification(h)
    } finally { h.detach(); h.media.operations.forEach(operation => operation.abort()) }
  })
}

for (const change of ['query', 'owner', 'poi', 'detach']) {
  test('真实 chooseImage：上传跨 show 时 '+change+' 仍中止旧操作且不清 pending', () => {
    const h = beginRealPoiCamera()
    try {
      h.media.choices[0].success({ tempFiles: [{ tempFilePath: '/fixture/new-camera.jpg' }] })
      showPoi(h)
      if (change === 'query') h.instance.retry()
      if (change === 'owner') { h.identity.owner = 8; showPoi(h) }
      if (change === 'poi') { h.instance.data.poiId = '22'; h.instance._load() }
      if (change === 'detach') h.detach()
      assert.equal(h.media.operations[0].isAborted(), true)
      uploadRealPhoto(h)
      assert.equal(h.media.deliveries, 0)
      assert.equal(h.instance.data.challengePhoto, '')
      assert.equal(h.posts().length, 1)
      assert.equal(h.storage.size, 1)
    } finally { h.detach(); h.media.operations.forEach(operation => operation.abort()) }
  })
}

test('真实 chooseImage：普通照片核验的裁剪/上传跨 show 保留新定位流程', async () => {
  const h = unknownVisit({ realMedia: true })
  try {
    prepareReverification(h, { validationMethod: 2 })
    h.instance.startInteract()
    h.media.choices[0].success({ tempFiles: [{ tempFilePath: '/fixture/current-photo.jpg' }] })
    showPoi(h)
    await Promise.resolve()
    assert.equal(h.media.uploads.length, 1)
    uploadRealPhoto(h)
    showPoi(h)
    assert.equal(h.instance.data.completing, true)
    h.locations.at(-1).success({ latitude: 31.3, longitude: 121.6 })
    assert.equal(h.posts().length, 2)
    assert.equal(h.posts().at(-1).data.photoUrl, 'https://fixture.invalid/new-camera.jpg')
    assert.equal(h.posts().at(-1).data.lat, 31.3)
  } finally { h.detach(); h.media.operations.forEach(operation => operation.abort()) }
})
