// 商家 AI 分身直接录音合同：授权 → 录音 → 试听 → 共享上传 → 发起音色复刻。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const upload = require('../../utils/transport/upload-client.js')

const PAGE = path.join(__dirname, '../../pages/merchant/decor/ai-npc/index.js')
const SOURCE = fs.readFileSync(PAGE, 'utf8')
const WXML = fs.readFileSync(path.join(__dirname, '../../pages/merchant/decor/ai-npc/index.wxml'), 'utf8')
const WXSS = fs.readFileSync(path.join(__dirname, '../../pages/merchant/decor/ai-npc/index.wxss'), 'utf8')

function setAtPath(target, rawPath, value) {
  const parts = rawPath.split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function loadPage(options = {}) {
  const state = { page: null, recorderStarts: [], recorderPauses: 0, recorderResumes: 0, recorderStops: 0, recorderOffs: 0, wxUploads: 0, requests: [] }
  const audio = {
    stop() {}, play() {}, destroy() {}, onEnded() {}, onStop() {}, onError() {},
  }
  const recorder = {
    onStop(fn) { state.onRecorderStop = fn },
    onError(fn) { state.onRecorderError = fn },
    offStop() { state.recorderOffs += 1 },
    offError() { state.recorderOffs += 1 },
    start(opts) { state.recorderStarts.push(opts) },
    pause() { state.recorderPauses += 1 },
    resume() { state.recorderResumes += 1 },
    stop() { state.recorderStops += 1 },
  }
  const client = upload.createUploadClient({
    wxUploadFile(opts) {
      state.wxUploads += 1
      if (options.onWxUpload) options.onWxUpload(opts)
      return { abort() {} }
    },
    getBaseUrl: () => 'https://base',
    getAuthorization: () => 'token',
    setTimer: () => 1,
    clearTimer: () => {},
  })
  const storage = options.storage || new Map()
  state.storage = storage
  state.userId = options.userId || '201'
  const app = { globalData: {}, getUserID: () => state.userId, getUploadClient: () => client, sendRequest() {} }
  const wx = {
    getStorageSync: (key) => { if (options.storageReadError) throw Error('storage read failed'); return storage.get(key) },
    setStorageSync: (key, value) => { if (options.storageWriteError) throw Error('storage write failed'); if (!options.dropStorageWrite) storage.set(key, value) },
    removeStorageSync: (key) => storage.delete(key),
    authorize: () => options.authorizePromise || (options.authorizeError ? Promise.reject(options.authorizeError) : Promise.resolve()),
    getRecorderManager: () => recorder,
    createInnerAudioContext: () => audio,
    getSystemInfoSync: () => ({}),
  }
  const localRequire = (id) => {
    if (id.endsWith('toast.js')) return function () {}
    if (id.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
    if (id.endsWith('pixel-avatar.js')) return {
      AVATAR_IDS: ['one'], isPixelAvatar() { return false }, parseCode() { return 'one' }, stringifyCode() { return 'px1:one' },
    }
    if (id.endsWith('pixel-avatar-data.js')) return { AVATARS: { one: { name: 'one' } } }
    if (id.endsWith('pixel-portrait.js')) return {}
    if (id.endsWith('transport/upload-client.js')) return upload
    throw new Error('unexpected require ' + id)
  }
  vm.runInNewContext(SOURCE, {
    getApp: () => app, Page(def) { state.page = def }, wx, require: localRequire, console,
    setTimeout, clearTimeout, setInterval, clearInterval, Promise, Object, Error, JSON, Math, Number, String, Array,
  }, { filename: PAGE })
  const page = Object.assign({}, state.page, { data: JSON.parse(JSON.stringify(state.page.data)) })
  page.setData = function (patch, cb) {
    Object.keys(patch || {}).forEach((key) => setAtPath(this.data, key, patch[key]))
    if (cb) cb()
  }
  page._request = (url, payload) => {
    state.requests.push({ url, payload })
    return Promise.resolve({ data: { voiceStatus: 2, voiceRequestId: payload && payload.voiceRequestId || page._voiceRequestId, voiceCurrent: true, voiceProfileId: 42, voiceGeneration: 1 } })
  }
  page._voiceProfileId = 42
  page._voiceGeneration = 1
  state.page = page
  return state
}

const tick = () => new Promise((resolve) => setImmediate(resolve))

test('录音面板是三句逐句录制的 Voice mode', () => {
  assert.match(WXML, /Voice mode/)
  assert.match(SOURCE, /你好，欢迎来到我的小店/)
  assert.match(SOURCE, /你可以告诉我想找什么商品/)
  assert.match(SOURCE, /如果还有价格、服务或者到店方面的问题/)
  assert.match(WXML, /bindtouchstart="onVoiceHoldStart"/)
  assert.match(WXML, /bindtouchend="onVoiceHoldEnd"/)
  assert.match(WXML, /npc-voice-progress/)
  assert.match(WXML, /npc-wave/)
  assert.match(WXSS, /\.npc-voice-hold/)
  assert.match(WXSS, /\.npc-voice-hold::after\s*\{\s*border:\s*0/)
  assert.doesNotMatch(WXML, /npc-recorder__status/)
  assert.doesNotMatch(WXML, /npc-voice-script/)
})

test('声音入口明确展示 UNKNOWN 和待确认重置，不误提示上传', () => {
  const expression = WXML.match(/value="\{\{([^"\n]*声音生成中[^"\n]*)\}\}"/)[1]
  for (const [voiceStatus, voiceUnknown, voiceResetPending, expected] of [
    [4, false, false, '声音结果待确认'], [0, true, false, '声音结果待确认'],
    [2, true, true, '声音清除待确认'], [0, false, false, '上传语音'],
    [1, false, false, '声音生成中'], [2, false, false, '声音可用'], [3, false, false, '生成失败 · 重试'],
  ]) assert.equal(vm.runInNewContext(expression, { voiceStatus, voiceUnknown, voiceResetPending }), expected)
})

test('未确认声音权利时不能启动录音', async () => {
  const h = loadPage()

  h.page.onVoiceHoldStart()
  await tick()

  assert.equal(h.recorderStarts.length, 0)
  assert.match(h.page.data.voiceError, /本人|授权/)
})

test('按住开始第一句，松开暂停并等待确认', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })

  h.page.onVoiceHoldStart()
  await tick()

  assert.equal(h.recorderStarts.length, 1)
  assert.equal(h.recorderStarts[0].format, 'mp3')
  assert.equal(h.recorderStarts[0].duration, 60000)
  assert.equal(h.page.data.voiceHolding, true)

  h.page.onVoiceHoldEnd()
  assert.equal(h.recorderPauses, 1)
  assert.equal(h.recorderStops, 0)
  assert.equal(h.page.data.voiceSentenceReady, true)
})

test('三句确认间复用同一录音会话，最后才保存', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })

  h.page.onVoiceHoldStart()
  await tick()
  h.page.onVoiceHoldEnd()
  h.page.confirmVoiceSentence()
  assert.equal(h.page.data.voiceSentenceIndex, 1)

  h.page.onVoiceHoldStart()
  h.page.onVoiceHoldEnd()
  h.page.confirmVoiceSentence()
  h.page.onVoiceHoldStart()
  h.page.onVoiceHoldEnd()
  h.page.confirmVoiceSentence()

  assert.equal(h.recorderStarts.length, 1)
  assert.equal(h.recorderResumes, 2)
  assert.equal(h.recorderPauses, 3)
  assert.equal(h.recorderStops, 1)
})

test('录音被系统提前结束时不能绕过三句确认', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()

  h.onRecorderStop({ tempFilePath: '/tmp/incomplete.mp3', duration: 20000, fileSize: 1024 })

  assert.equal(h.page.data.voiceFile, null)
  assert.equal(h.page.data.voiceSentenceConfirmed, 0)
  assert.match(h.page.data.voiceError, /三句|重新录制/)
})

test('录音不足 10 秒时明确拒绝', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()
  h.page.data.voiceSentenceConfirmed = 3

  h.onRecorderStop({ tempFilePath: '/tmp/short.mp3', duration: 9000, fileSize: 1000 })

  assert.equal(h.page.data.voiceFile, null)
  assert.match(h.page.data.voiceError, /10 秒/)
})

test('合法录音上传一次并携带声音授权发起复刻', async () => {
  const h = loadPage({
    onWxUpload(opts) { opts.success({ data: JSON.stringify({ code: 200, url: 'https://cdn/voice.mp3' }) }) },
  })
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()
  h.page.data.voiceSentenceConfirmed = 3
  h.onRecorderStop({ tempFilePath: '/tmp/voice.mp3', duration: 20000, fileSize: 1024 * 1024 })

  assert.equal(h.page.data.voiceFile.duration, 20)
  h.page.uploadVoice()
  await tick()

  assert.equal(h.wxUploads, 1)
  assert.equal(h.requests[0].url, '/api/merchant/npc/voice/enroll')
  assert.equal(h.requests[0].payload.voiceSample, 'https://cdn/voice.mp3')
  assert.equal(h.requests[0].payload.voiceConsent, true)
})

test('录完后撤回声音授权不能提交', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()
  h.page.data.voiceSentenceConfirmed = 3
  h.onRecorderStop({ tempFilePath: '/tmp/voice.mp3', duration: 20000, fileSize: 1024 })
  h.page.onVoiceConsentChange({ detail: { checked: false } })

  h.page.uploadVoice()

  assert.equal(h.wxUploads, 0)
  assert.equal(h.requests.length, 0)
  assert.match(h.page.data.voiceError, /本人|授权/)
})

test('页面卸载时解绑全局录音监听', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()

  h.page.onUnload()

  assert.equal(h.recorderOffs, 2)
})

test('录音权限被拒绝时不假装开始', async () => {
  const h = loadPage({ authorizeError: new Error('deny') })
  h.page.onVoiceConsentChange({ detail: { checked: true } })

  h.page.onVoiceHoldStart()
  await tick()

  assert.equal(h.recorderStarts.length, 0)
  assert.equal(h.page.data.voiceHolding, false)
  assert.match(h.page.data.voiceError, /录音权限/)
})

test('等待录音授权时切后台不会晚到启动麦克风', async () => {
  let grant
  const h = loadPage({ authorizePromise: new Promise((resolve) => { grant = resolve }) })
  h.page.onVoiceConsentChange({ detail: { checked: true } })

  h.page.onVoiceHoldStart()
  h.page.onHide()
  grant()
  await tick()

  assert.equal(h.recorderStarts.length, 0)
  assert.equal(h.page.data.voiceHolding, false)
})

test('后续句录音出错时完整回到第一句', async () => {
  const h = loadPage()
  h.page.onVoiceConsentChange({ detail: { checked: true } })
  h.page.onVoiceHoldStart()
  await tick()
  h.page.onVoiceHoldEnd()
  h.page.confirmVoiceSentence()
  h.page.onVoiceHoldStart()

  h.onRecorderError(new Error('record failed'))

  assert.equal(h.page.data.voiceSentenceIndex, 0)
  assert.equal(h.page.data.voiceSentenceConfirmed, 0)
  assert.equal(h.page.data.voiceSentenceText, h.page.data.voiceSentences[0])
  assert.equal(h.page.data.voiceSessionActive, false)
})

test('录音回调拒绝缺失、空、负数与非有限文件大小', () => {
  for (const fileSize of [undefined, 0, -1, NaN, Infinity]) {
    const h = loadPage()
    h.page.voiceRecorder()
    h.page.data.voiceSentenceConfirmed = 3
    h.onRecorderStop({ tempFilePath: '/tmp/voice.mp3', duration: 12000, fileSize })
    assert.equal(h.page.data.voiceFile, null)
    assert.ok(h.page.data.voiceError)
  }
})


test('原录音提交未知时读到旧样本不能解锁再次生成', async () => {
  const { page } = loadPage()
  page._voicePendingSample = 'https://sample/current.mp3'
  page.data.voiceUnknown = true
  page._request = () => Promise.resolve({ data: { voiceStatus: 2, voiceSample: 'https://sample/old.mp3' } })
  await page.refreshVoice()
  assert.equal(page.data.voiceUnknown, true)
  page._request = () => Promise.resolve({ data: { voiceStatus: 2, voiceSample: 'https://sample/current.mp3' } })
  await page.refreshVoice()
  assert.equal(page.data.voiceUnknown, false)
})

test('缺声音状态或状态读取失败不伪装未录制', async () => {
  const { page } = loadPage()
  page._request = () => Promise.resolve({ data: {} })
  await page.refreshVoice()
  assert.equal(page.data.voiceUnknown, true)
  page._request = () => Promise.reject(Error('offline'))
  await page.refreshVoice()
  assert.equal(page.data.voiceUnknown, true)
})


test('登记响应丢失后退出重开只查持久化原 requestId，不再次生成', async () => {
  const h = loadPage()
  h.page.data.voiceFile = { path: '/tmp/voice.mp3', size: 1000, url: 'https://files/original.wav' }
  h.page.data.voiceConsent = true
  h.page._request = (url, payload) => { h.requests.push({ url, payload }); return Promise.reject(Error('lost response')) }
  h.page.uploadVoice()
  await tick()
  const id = h.storage.get('merchantNpcVoiceRequest:201').requestId
  assert.ok(id)
  assert.equal(h.requests.filter(r => r.url.endsWith('/enroll')).length, 1)
  h.page.onUnload()
  const restarted = loadPage({ storage: h.storage })
  restarted.page.data.hasProfile = true
  restarted.page._request = (url, payload) => {
    restarted.requests.push({ url, payload })
    return Promise.resolve({ data: { voiceStatus: 4, voiceRequestId: id, voiceCurrent: true, voiceUnknown: true, voiceProfileId: 42, voiceGeneration: 1 } })
  }
  restarted.page.openVoice()
  await tick()
  assert.equal(restarted.requests[0].payload.voiceRequestId, id)
  assert.equal(restarted.page.data.voiceUnknown, true)
  restarted.page.data.voiceFile = { url: 'https://files/another.wav' }
  restarted.page.data.voiceConsent = true
  restarted.page.uploadVoice()
  assert.equal(restarted.requests.length, 1)
  assert.equal([...h.storage.values()].some(v => String(v).includes('https://')), false)
})

test('同样本旧请求编号和已重置的原请求均不能解除未知态', async () => {
  const h = loadPage()
  h.page._voiceRequestId = 'current'
  h.page.data.voiceUnknown = true
  h.page._request = () => Promise.resolve({ data: { voiceStatus: 2, voiceSample: 'https://same.wav', voiceRequestId: 'old', voiceCurrent: true } })
  await h.page.refreshVoice()
  assert.equal(h.page.data.voiceUnknown, true)
  h.page._request = () => Promise.resolve({ data: { voiceStatus: 2, voiceRequestId: 'current', voiceCurrent: false } })
  await h.page.refreshVoice()
  assert.equal(h.page.data.voiceUnknown, true)
})

test('账号切换丢弃旧账号延迟回调，并且不查询旧请求编号', async () => {
  const storage = new Map([['merchantNpcVoiceRequest:201', 'a-original']])
  const h = loadPage({ storage })
  h.page.restoreVoiceRequest()
  let finish
  h.page._request = (url, payload) => { h.requests.push({ url, payload }); return new Promise(resolve => { finish = resolve }) }
  const pending = h.page.refreshVoice()
  h.userId = '202'
  h.page.restoreVoiceRequest()
  finish({ data: { voiceStatus: 2, voiceSample: 'https://private-a.wav', voiceRequestId: 'a-original', voiceCurrent: true } })
  await pending
  assert.equal(h.page.data.voiceStatus, 0)
  assert.equal(h.page.data.voiceSample, '')
  assert.equal(h.page._voiceRequestId, '')
  h.page._request = (url, payload) => { h.requests.push({ url, payload }); return Promise.resolve({ data: { voiceStatus: 0 } }) }
  await h.page.refreshVoice()
  assert.equal(h.requests[1].payload.voiceRequestId, undefined)
  assert.equal(storage.get('merchantNpcVoiceRequest:201'), 'a-original')
})

test('供应商登记前账号变化取消后续提交', async () => {
  const h = loadPage({ onWxUpload(opts) { h.uploadOptions = opts } })
  h.page.data.voiceFile = { path: '/tmp/voice.mp3', size: 1000 }
  h.page.data.voiceConsent = true
  h.page.uploadVoice()
  h.userId = '202'
  h.uploadOptions.success({ data: JSON.stringify({ code: 200, url: 'https://files/original.wav' }) })
  await tick()
  assert.equal(h.requests.length, 0)
  assert.equal(h.storage.size, 0)
})

test('重录先保存后端 reset，随后清除原请求元数据', async () => {
  const h = loadPage()
  h.page.data.voiceStatus = 2
  h.page._voiceRequestId = 'original'
  h.storage.set('merchantNpcVoiceRequest:201', 'original')
  await h.page.resetVoiceRecording()
  assert.equal(h.requests[0].url, '/api/merchant/npc/voice/reset')
  assert.equal(h.page.data.voiceStatus, 0)
  assert.equal(h.storage.has('merchantNpcVoiceRequest:201'), false)
})


test('重置响应丢失后重开用当前版本确认已清除，不再次登记', async () => {
  const record = { requestId: 'original', profileId: '42', generation: 1, resetPending: false }
  const storage = new Map([['merchantNpcVoiceRequest:201', record]])
  const h = loadPage({ storage })
  h.page.restoreVoiceRequest()
  h.page.data.voiceStatus = 2
  h.page.data.voiceUnknown = false
  h.page._request = (url, payload) => { h.requests.push({ url, payload }); return Promise.reject(Error('reset response lost')) }
  await h.page.resetVoiceRecording()
  assert.equal(storage.get('merchantNpcVoiceRequest:201').resetPending, true)
  assert.equal(h.requests[0].payload.id, '42')
  assert.equal(h.requests[0].payload.voiceGeneration, 1)
  h.page.onUnload()
  const next = loadPage({ storage })
  next.page.restoreVoiceRequest()
  next.page._request = (url, payload) => { next.requests.push({ url, payload }); return Promise.resolve({ data: { voiceStatus: 0, voiceProfileId: 42, voiceGeneration: 2, voiceRequestId: null } }) }
  await next.page.refreshVoice()
  assert.equal(next.requests[0].payload.voiceRequestId, undefined)
  assert.equal(next.page.data.voiceUnknown, false)
  assert.equal(next.page.data.voiceResetPending, false)
  assert.equal(storage.size, 0)
  assert.equal(next.requests.filter(r => r.url.endsWith('/enroll')).length, 0)
})

test('重置读到新音色或新形象时保留原意图，不清除其他任务', async () => {
  for (const current of [{ voiceStatus: 2, voiceProfileId: 42, voiceGeneration: 3, voiceRequestId: 'new' },
    { voiceStatus: 0, voiceProfileId: 43, voiceGeneration: 2, voiceRequestId: null }]) {
    const record = { requestId: 'original', profileId: '42', generation: 1, resetPending: true }
    const storage = new Map([['merchantNpcVoiceRequest:201', record]])
    const h = loadPage({ storage })
    h.page.restoreVoiceRequest()
    h.page._request = () => Promise.resolve({ data: current })
    await h.page.refreshVoice()
    assert.equal(h.page.data.voiceUnknown, true)
    assert.equal(h.page.data.voiceResetPending, true)
    assert.equal(storage.get('merchantNpcVoiceRequest:201').requestId, 'original')
  }
})

test('原重置未确认时显式继续清除仍携带原版本，不能清除新版本', async () => {
  const record = { requestId: 'original', profileId: '42', generation: 1, resetPending: true }
  const h = loadPage({ storage: new Map([['merchantNpcVoiceRequest:201', record]]) })
  h.page.restoreVoiceRequest()
  h.page._request = (url, payload) => { h.requests.push({ url, payload }); return Promise.reject(Error('version changed')) }
  await h.page.resetVoiceRecording()
  assert.equal(h.requests[0].payload.voiceGeneration, 1)
  assert.equal(h.requests[0].payload.voiceRequestId, 'original')
  assert.equal(h.page.data.voiceUnknown, true)
})

test('存储读取失败或记录损坏阻止登记，查询当前状态不能解除阻止', async () => {
  for (const options of [{ storageReadError: true }, { storage: new Map([['merchantNpcVoiceRequest:201', { requestId: 'bad id' }]]) }]) {
    const h = loadPage(options)
    assert.doesNotThrow(() => h.page.restoreVoiceRequest())
    await h.page.refreshVoice()
    h.page.data.voiceConsent = true
    h.page.data.voiceFile = { url: 'https://files/test.wav', size: 100 }
    h.page.uploadVoice()
    assert.equal(h.page.data.voiceUnknown, true)
    assert.equal(h.requests.filter(r => r.url.endsWith('/enroll')).length, 0)
  }
})

test('存储写失败或写后回读不一致不会提交供应商登记', async () => {
  for (const options of [{ storageWriteError: true }, { dropStorageWrite: true }]) {
    const h = loadPage(options)
    h.page.data.voiceConsent = true
    h.page.data.voiceFile = { url: 'https://files/test.wav', size: 100 }
    h.page.uploadVoice()
    await tick()
    assert.equal(h.page.data.voiceUnknown, true)
    assert.equal(h.requests.length, 0)
    assert.equal(h.page.data.voiceFile.url, 'https://files/test.wav')
  }
})

test('当前声音来自其他授权人员时不采用其原请求作为登记恢复记录', async () => {
  const h = loadPage()
  h.page.restoreVoiceRequest()
  h.page._request = () => Promise.resolve({ data: { voiceStatus: 1, voiceRequestId: 'other-actor', voiceSample: 'https://sample/other.wav', voiceProfileId: 42, voiceGeneration: 1 } })
  await h.page.refreshVoice()
  assert.equal(h.page._voiceRequestId, '')
  assert.equal(h.storage.size, 0)
  h.page.onHide()
})
