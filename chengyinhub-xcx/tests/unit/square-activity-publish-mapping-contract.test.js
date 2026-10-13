// R10-09 发帖关联映射:真实 activity-picker emit → 真实 square/list 发布请求。
// 自玩完成发送 TOPIC 引用；活动发送 ACTIVITY 引用；附件须真实上传回执登记，清除/失败/迟回不得退化直发。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const LIST_PAGE = '../../pages/square/list/index.js'
const PICKER = '../../pages/square/components/activity-picker/index.js'

let pageConfig
let pickerDef
let requests

function setByPath(target, rawPath, value) {
  const parts = rawPath.split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

beforeEach(() => {
  pageConfig = null
  pickerDef = null
  requests = []
  const app = {
    globalData: { statusBarHeight: 20, user_id: 1, features: {} },
    getUserID: () => 1,
    getAvatar: () => '',
    getPageSize: () => 10,
    getUserRole: () => '',
    getUserType: () => 0,
    isDevEnv: () => false,
    sendRequest(opts) { requests.push(opts) },
    getUploadClient: () => ({
      uploadAll(paths, opts) {
        // 路线预览图上传:直接成功,让 picker 走真实 finish→emitSelect
        assert.equal(opts.bizType, 'COMMUNITY_POST')
        opts.onDone({ ok: true, results: [opts.mapResult(uploadReceipt('https://img.example/route.png'))] })
        return { abort() {}, destroy() {} }
      },
    }),
    tips() {},
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  }
  global.getApp = () => app
  const store = new Map()
  global.wx = {
    getStorageSync: key => store.get(key),
    setStorageSync: (key, value) => store.set(key, structuredClone(value)),
    removeStorageSync: key => store.delete(key),
    showModal: options => options.success({ confirm: true }),
    hideTabBar() {},
    getWindowInfo: () => ({ statusBarHeight: 20, windowHeight: 800 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20, windowHeight: 800 }),
    getMenuButtonBoundingClientRect: () => ({ top: 24, height: 32, bottom: 56 }),
    navigateTo() {},
    showToast() {},
    hideToast() {},
    showLoading() {},
    hideLoading() {},
    createSelectorQuery: () => ({ select: () => ({ fields: () => ({ exec() {} }) }), exec() {} }),
  }
  global.Page = (config) => { pageConfig = config }
  global.Component = (definition) => { pickerDef = definition }
})

function loadListPage() {
  delete require.cache[require.resolve(LIST_PAGE)]
  require(LIST_PAGE)
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.setData = function (patch, callback) {
    Object.keys(patch || {}).forEach((key) => setByPath(this.data, key, patch[key]))
    if (callback) callback()
  }
  return page
}

function loadPicker() {
  delete require.cache[require.resolve(PICKER)]
  require(PICKER)
  const vm = Object.assign({}, pickerDef.methods)
  vm.data = JSON.parse(JSON.stringify(pickerDef.data))
  vm.setData = function (patch, callback) {
    Object.keys(patch || {}).forEach((key) => setByPath(this.data, key, patch[key]))
    if (callback) callback()
  }
  vm._emitted = null
  vm.triggerEvent = function (name, detail) { if (name === 'select') vm._emitted = detail }
  return vm
}

const SELF_PLAY_ITEM = {
  activityId: null, topicId: 990062, dataType: 2, dataId: 990062,
  name: '自玩主题', cover: 'https://cdn/self.png', total: 2, doneCount: 2, completed: true,
}
const ACTIVITY_ITEM = {
  activityId: 501, topicId: 990063, dataType: 1, dataId: 501,
  name: '活动场次', cover: 'https://cdn/act.png', total: 3, doneCount: 3, completed: true,
}

/** 真实 picker:fetchList 成功 → onSelect → route-ready 截图上传 → 真实 emitSelect。 */
async function pickerEmitFor(item) {
  const vm = loadPicker()
  vm.fetchList()
  assert.equal(requests.length, 1, 'picker 必须请求 my-completed')
  requests[0].success({ code: '200', data: [item] })
  vm.selectComponent = () => ({ snapshotRoute: () => Promise.resolve('/tmp/route.png') })
  vm.onSelect({ currentTarget: { dataset: { index: 0 } } })
  vm.onRouteReady()
  await new Promise((resolve) => setImmediate(resolve))
  if (vm._timer) clearTimeout(vm._timer)
  assert.ok(vm._emitted, 'picker 必须真实 emit select')
  return vm._emitted
}

function primeSubmit(page) {
  page.data.valCont = '发一条测试帖'
  page.data.picList = []
  page.data.fileList = []
  page.data.address = ''
  page.data.longitude = ''
  page.data.latitude = ''
  page.data.switch1Checked = false
}

const tick = () => new Promise(resolve => setImmediate(resolve))
function uploadReceipt(url) { return { url, uploadReceipt: 'fixture-upload-receipt', byteSize: 32, mimeType: 'image/png' } }
async function awaitCreate() {
  await tick()
  let req = requests.at(-1)
  if (req.url === '/api/v1/community/guidelines/active') {
    req.success({code:200,data:{id:3,contentJson:{rules:[{title:'真实规范'}]}}})
    await tick(); req = requests.at(-1)
    assert.equal(req.url, '/api/v1/community/guidelines/ack')
    req.success({code:200,data:{}})
    await tick(); req = requests.at(-1)
  }
  if (req.url === '/api/v1/community/media/register') {
    assert.equal(req.method, 'POST')
    assert.equal(req.data.uploadReceipt, 'fixture-upload-receipt')
    assert.equal(req.data.byteSize, 32)
    assert.equal(req.data.mimeType, 'image/png')
    req.success({code:200,data:{id:91}})
    await tick(); req = requests.at(-1)
  }
  assert.equal(req.url, '/api/v1/community/posts')
  assert.equal(req.method, 'POST')
  assert.ok(req.data.clientRequestId)
  return req
}
function assertReference(body, type, id) {
  assert.deepEqual(body.references.map(row => [row.referenceType, row.referenceId]), [[type, id]])
  assert.equal(body.data_type, undefined)
  assert.equal(body.data_id, undefined)
}

test('自玩完成:真实 picker emit 到规范发布必须保留 TOPIC 关联和已登记路线图片', async () => {
  const page = loadListPage()
  const emitted = await pickerEmitFor(SELF_PLAY_ITEM)
  page.onActivitySelect({ detail: emitted })
  primeSubmit(page)
  page.submitFormData()
  const create = await awaitCreate()
  assertReference(create.data, 'TOPIC', 990062)
  assert.deepEqual(create.data.mediaIds, [91])
  const register = requests.find(r => r.url === '/api/v1/community/media/register')
  assert.equal(register.data.objectKey, 'https://img.example/route.png')
  assert.equal(requests[0].url, '/api/play/my-completed')
  const draft = { post: { ...create.data, id: 77, authorId: 1, version: 0, lifecycle: 'DRAFT' },
    viewerIsAuthor: true, viewerCanComment: true, viewerFollowing: false,
    media: [{id:91,mediaType:'IMAGE',objectKey:'https://img.example/route.png'}], references:create.data.references, topicCodes:[], mentions:[] }
  create.success({code:200,data:draft}); await tick()
  const publish = requests.at(-1)
  assert.equal(publish.url, '/api/v1/community/posts/77/publish')
  assert.equal(publish.data.expectedVersion, 0)
  publish.success({code:200,data:{...draft,post:{...draft.post,version:1,lifecycle:'CHECKING'}}}); await tick()
  assert.equal(page.data.submitError, '')
  assert.equal(page.data.submitting, false)
})

test('正式活动:保留 ACTIVITY 及 activityId', async () => {
  const page = loadListPage()
  page.onActivitySelect({ detail: { activity: ACTIVITY_ITEM, routePreviewImg: '' } })
  primeSubmit(page); page.submitFormData()
  assertReference((await awaitCreate()).data, 'ACTIVITY', 501)
})

test('清除选择:发布为无关联,不残留上一条引用或图片', async () => {
  const page = loadListPage()
  page.onActivitySelect({ detail: { activity: SELF_PLAY_ITEM, routePreviewImg: '' } })
  page.clearActivity(); primeSubmit(page); page.submitFormData()
  const body = (await awaitCreate()).data
  assert.deepEqual(body.references, [])
  assert.deepEqual(body.mediaIds, [])
})

test('兼容旧结构(无 dataId):回退到 activityId,不丢活动关联', async () => {
  const page = loadListPage()
  const legacy = { activityId: 601, topicId: 990064, dataType: 1, name: '旧活动' }
  page.onActivitySelect({ detail: { activity: legacy, routePreviewImg: '' } })
  primeSubmit(page); page.submitFormData()
  assertReference((await awaitCreate()).data, 'ACTIVITY', 601)
})

test('失败恢复:发布失败保留选择,重试仍带同一完整自玩关联 wire', async () => {
  const page = loadListPage()
  page.onActivitySelect({ detail: { activity: SELF_PLAY_ITEM, routePreviewImg: '' } })
  primeSubmit(page); page.submitFormData()
  const first = await awaitCreate()
  first.fail({ errMsg: 'network fail' }); await tick()
  assert.equal(page.data.submitting, false)
  assert.equal(page.data.selectedActivity.topicId, 990062, '失败不得丢选择')
  page.retrySubmit()
  const retry = await awaitCreate()
  assert.notEqual(retry, first)
  assert.deepEqual(retry.data, first.data, '未知结果后连意图键与完整引用必须保持不变')
  assertReference(retry.data, 'TOPIC', 990062)
})

test('未知结果后改变关联必须核旧wire，新关联与图片保留且不换键', async () => {
  const page = loadListPage(), dialogs=[]
  global.wx.showModal=options=>{if(options.title==='先核对上次提交')dialogs.push(options);else options.success({confirm:true})}
  page.onActivitySelect({ detail: { activity: SELF_PLAY_ITEM, routePreviewImg: '' } })
  primeSubmit(page); page.submitFormData();const first = await awaitCreate();first.fail({});await tick()
  page.onActivitySelect({ detail: { activity: ACTIVITY_ITEM, routePreviewImg: 'https://img.example/new.png', routePreviewReceipt: uploadReceipt('https://img.example/new.png') } })
  const count=requests.length;page.retrySubmit();await tick();assert.equal(requests.length,count)
  assert.equal(page.data.selectedActivity.activityId,ACTIVITY_ITEM.activityId);dialogs[0].success({confirm:false});await tick();assert.equal(requests.length,count)
  page.retrySubmit();await tick();dialogs[1].success({confirm:true});await tick();const replay=requests.at(-1)
  assert.deepEqual(replay.data,first.data);assertReference(replay.data,'TOPIC',990062);assert.equal(replay.data.clientRequestId,first.data.clientRequestId)
  assert.equal(requests.filter(r=>r.url==='/api/v1/community/media/register').length,0,'核旧wire不得登记新关联预览图片')
  assert.equal(page.data.selectedActivity.activityId,ACTIVITY_ITEM.activityId)
  assert.equal(page.data.selectedActivity.routePreviewImg,'https://img.example/new.png')
})

test('发布在途防重复提交:第二次点击不发重复请求', async () => {
  const page = loadListPage()
  page.onActivitySelect({ detail: { activity: SELF_PLAY_ITEM, routePreviewImg: '' } })
  primeSubmit(page); page.submitFormData(); page.submitFormData()
  await tick()
  assert.equal(requests.length, 1, '规范确认在途也不得重复请求')
  await awaitCreate()
  assert.equal(requests.filter(r => r.url === '/api/v1/community/posts').length, 1)
})
