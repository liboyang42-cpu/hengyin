const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'pages/privacy/index.js')

function loadPage(recordConsent, options = {}) {
  const sandbox = { records: [], resolutions: [], toasts: [], modals: [], backs: 0 }
  const owner = options.owner || (() => '1001')
  const token = options.token || (() => 'normal-session')
  global.getApp = () => ({
    hasPendingPrivacyAuthorization: () => options.pending !== false,
    getUserID: owner,
    getAuthorization: token,
    declineUsageConsent() {},
    agreeUsageConsent: (event, checked) => {
      if (!checked) return Promise.reject(new Error('service checkbox missing'));
      if (!sandbox.resolutions.length) sandbox.resolutions.push({ event: 'agree', buttonId: event.detail.buttonId });
      const payload = { docType: 'privacy_policy', scene: 'app_launch', eventType: 'AGREE' };
      sandbox.records.push(payload); return recordConsent(payload);
    },
    recordConsent: (payload) => { sandbox.records.push(payload); return recordConsent(payload) },
    resolvePrivacyAuthorization: (payload) => sandbox.resolutions.push(payload),
  })
  global.wx = {
    showToast: (options) => sandbox.toasts.push(options.title),
    showModal: (options) => sandbox.modals.push(options),
    getLocation() { assert.fail('设置首页定位不能直接采集位置') },
    navigateBack: () => { sandbox.backs += 1 },
    stopLocationUpdate() {},
  }
  global.Page = (config) => { sandbox.def = config }
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const page = Object.assign({}, sandbox.def, {
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback() },
  })
  page.data = JSON.parse(JSON.stringify(sandbox.def.data))
  return { page, sandbox }
}

const settle = () => new Promise((resolve) => setImmediate(resolve))

test('同意记录失败保留持久错误与重试上下文，不用易逝 toast', async () => {
  let shouldFail = true
  const { page, sandbox } = loadPage(() => shouldFail ? Promise.reject(new Error('offline')) : Promise.resolve())
  page.data.serviceAgreed = true
  page.agreePrivacy({ detail: { buttonId: 'agree-button' } })
  await settle()
  assert.equal(page.data.submitting, false)
  assert.equal(page._failedAction, 'agree')
  assert.match(page.data.submitError, /同意记录失败/)
  assert.equal(sandbox.toasts.length, 0)
  assert.equal(sandbox.backs, 0)

  shouldFail = false
  page.retrySubmit()
  await settle()
  assert.equal(sandbox.records.length, 2)
  assert.deepEqual(sandbox.resolutions, [{ event: 'agree', buttonId: 'agree-button' }])
  assert.equal(sandbox.backs, 1)
})

test('撤回记录失败保持页面与错误，重试成功后清除错误', async () => {
  let shouldFail = true
  const { page, sandbox } = loadPage(() => shouldFail ? Promise.reject(new Error('offline')) : Promise.resolve())
  page.withdrawRoamLocationConsent()
  await settle()
  assert.equal(page._failedAction, 'revoke')
  assert.match(page.data.submitError, /本机.*已停.*撤回记录待确认/)
  assert.equal(sandbox.toasts.length, 0)

  shouldFail = false
  page.retrySubmit()
  await settle()
  assert.equal(page.data.submitError, '')
  assert.equal(page._failedAction, '')
  assert.deepEqual(sandbox.records.map((item) => item.eventType), ['REVOKE', 'REVOKE'])
  assert.deepEqual(sandbox.toasts, ['已撤回漫游定位同意'])
})

test('WXML 提供持久 inline error 与真实重试，按钮暴露 busy/disabled', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/privacy/index.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/privacy/index.json'), 'utf8'))
  assert.match(wxml, /<cy-inline-error[^>]*wx:if="\{\{submitError\}\}"[^>]*action="重试"[^>]*bind:action="retrySubmit"/)
  assert.match(wxml, /aria-disabled="\{\{submitting\}\}"/)
  assert.equal(json.usingComponents['cy-inline-error'], '/components/cy/inline-error/index')
})

// 2026-10-20 用户定稿(#293):进入弹窗 = 一句话 + 一行勾选(两份文件入口在勾选行内);设置态 = 一张分组列表,
// 每项一行标题 + 动作。首页城市定位的用途说明在开启时的单独同意弹窗里(见下方 enableHomeCityLocation 用例)。
test('首次隐私闸保留一句话与一行勾选，设置页每项保留独立选择', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/privacy/index.wxml'), 'utf8')
  const initial = wxml.match(/<block wx:if="{{pendingAuthorization}}">([\s\S]*?)<\/block>/)[1]
  assert.ok(initial.includes('class="privacy-lead"'))
  assert.ok(initial.includes('catchtap="openService"'))
  assert.ok(initial.includes('catchtap="openPrivacyContract"'))
  assert.ok(initial.includes('bindchange="toggleService"'))
  assert.equal(initial.includes('privacy-body'), false, '首次闸保留 #293 已确认的一句话')
  assert.ok(wxml.includes('首页城市自动定位'))
  assert.ok(!wxml.includes('仅用于推荐附近活动'), '城市用途不得误述为精确附近排序')
  for (const handler of ['enableHomeCityLocation', 'disableHomeCityLocation', 'disablePresenceSharing', 'withdrawRoamLocationConsent']) {
    assert.ok(wxml.includes('bindtap="' + handler + '"'), handler + ' 独立选择入口不能因精简文案被删除')
  }
})

test('negative control:失败分支必须写 submitError/failedAction，不能只 showToast', () => {
  const source = fs.readFileSync(MODULE, 'utf8')
  assert.match(source, /this\._failedAction\s*=\s*'agree'/)
  assert.match(source, /this\._failedAction\s*=\s*'revoke'/)
  assert.match(source, /retrySubmit\(\)/)
})

function homeReceipt(eventType, patch) {
  return {requestId:'home-settings-001',response:{code:200,data:Object.assign({
    requestId:'home-settings-001',docType:'privacy_policy',docVersion:'v2.1',
    scene:'home_city_location',eventType,
  },patch)}}
}
test('关闭首页自动定位只撤回该用途，不改变漫游权限',async()=>{
  const {page,sandbox}=loadPage(()=>Promise.resolve(homeReceipt('REVOKE')),{pending:false})
  const tracker=require('../../utils/location/bg-tracker.js'), revision=tracker.revision('roam')
  page.disableHomeCityLocation();await settle()
  assert.deepEqual(sandbox.records,[{docType:'privacy_policy',scene:'home_city_location',eventType:'REVOKE'}])
  assert.equal(tracker.revision('roam'),revision);assert.equal(sandbox.modals.length,0)
  assert.deepEqual(sandbox.toasts,['已关闭首页城市自动定位']);assert.equal(page.data.homeCityBusy,false)
})
test('重新开启必须再次明确同意，并等服务端匹配留痕回执',async()=>{
  const {page,sandbox}=loadPage(()=>Promise.resolve(homeReceipt('AGREE')),{pending:false})
  page.enableHomeCityLocation();assert.equal(sandbox.records.length,0)
  assert.match(sandbox.modals[0].content,/精确位置/);assert.match(sandbox.modals[0].content,/发送给城瘾以确定首页城市/)
  sandbox.modals[0].success({confirm:true});await settle()
  assert.deepEqual(sandbox.records,[{docType:'privacy_policy',scene:'home_city_location',eventType:'AGREE'}])
  assert.deepEqual(sandbox.toasts,['已开启首页城市自动定位'])
})
test('取消重新开启不写同意，失败和错回执不假报成功',async()=>{
  const {page,sandbox}=loadPage(()=>Promise.reject(new Error('offline')),{pending:false})
  page.enableHomeCityLocation();sandbox.modals[0].success({confirm:false});await settle()
  assert.equal(sandbox.records.length,0);assert.equal(page.data.homeCityBusy,false)
  page.disableHomeCityLocation();await settle()
  assert.match(page.data.homeCityError,/未保存/);assert.equal(sandbox.toasts.length,0)
  for (const receipt of [homeReceipt('REVOKE',{scene:'roam_location'}),
    {response:{code:200,data:{docType:'privacy_policy',docVersion:'v2.1',scene:'home_city_location',eventType:'REVOKE'}}}]) {
    const bad=loadPage(()=>Promise.resolve(receipt),{pending:false})
    bad.page.disableHomeCityLocation();await settle()
    assert.match(bad.page.data.homeCityError,/未保存/);assert.equal(bad.sandbox.toasts.length,0)
  }
})
test('换账号或离开设置后迟到留痕不能给新界面显示成功',async()=>{
  let member='1001',resolveRecord
  const {page,sandbox}=loadPage(()=>new Promise(resolve=>{resolveRecord=resolve}),{pending:false,owner:()=>member})
  page.disableHomeCityLocation();await settle();member='1002'
  resolveRecord(homeReceipt('REVOKE'));await settle()
  assert.equal(sandbox.toasts.length,0);assert.equal(page.data.homeCityBusy,false)
  const hidden=loadPage(()=>Promise.resolve(homeReceipt('REVOKE')),{pending:false})
  hidden.page.disableHomeCityLocation();hidden.page.onHide();await settle()
  assert.equal(hidden.sandbox.toasts.length,0)
})
test('匿名、展示会话或初次协议闸不得设置首页定位同意',async()=>{
  for(const options of [{pending:false,owner:()=>''},{pending:false,token:()=> 'dev-token'},{pending:true}]) {
    const {page,sandbox}=loadPage(()=>Promise.resolve(homeReceipt('REVOKE')),options)
    page.onLoad();page.disableHomeCityLocation();page.enableHomeCityLocation();await settle()
    assert.equal(sandbox.records.length,0);assert.equal(sandbox.modals.length,0)
  }
})
