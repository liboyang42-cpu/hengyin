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
  const app = { globalData: {}, requestSeparateConsent: () => options.consentDenied ? Promise.reject(new Error('已拒绝声音克隆')) : Promise.resolve(), getUserID: () => '7', getUploadClient: () => client, sendRequest() {} }
  const wx = {
    authorize: () => options.authorizePromise || (options.authorizeError ? Promise.reject(options.authorizeError) : Promise.resolve()),
    getRecorderManager: () => recorder,
    createInnerAudioContext: () => audio,
    getSystemInfoSync: () => ({}),
  }
  require('../helpers/voice-enrollment-environment.cjs').install(app, wx);
  const localRequire = (id) => {
    if (id.endsWith('voice-enrollment-journal.js')) return require('../../utils/voice-enrollment-journal.js');
    if (id.endsWith('modal.js')) return { show() {} };
    if (id.endsWith('toast.js')) return function () {}
    if (id.endsWith('modal.js')) return { show() {} }
    if (id.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} }
    if (id.endsWith('pixel-avatar.js')) return {
      AVATAR_IDS: ['one'], isPixelAvatar() { return false }, parseCode() { return 'one' }, stringifyCode() { return 'px1:one' },
    }
    if (id.endsWith('pixel-avatar-data.js')) return { AVATARS: { one: { name: 'one' } } }
    if (id.endsWith('pixel-portrait.js')) return {}
    if (id.endsWith('transport/upload-client.js')) return upload
    if (id.endsWith('profile-review.js')) return require('../../utils/profile-review.js')
    // 2026-10-27 M-28/G-7:漫游落点只显示到路名(纯函数,无副作用)
    if (id.endsWith('address-short.js')) return require('../../pages/merchant/utils/address-short.js')
    throw new Error('unexpected require ' + id)
  }
  vm.runInNewContext(SOURCE, {
    getApp: () => app, Page(def) { state.page = def }, wx, require: localRequire, console,
    setTimeout, clearTimeout, setInterval, clearInterval, Promise, Object, Error, JSON, Math, Number, String, Array,
  }, { filename: PAGE })
  const page = Object.assign({}, state.page, { data: JSON.parse(JSON.stringify(state.page.data)) })
  page._photoMember = page._voiceMember = '7';
  page.setData = function (patch, cb) {
    Object.keys(patch || {}).forEach((key) => setAtPath(this.data, key, patch[key]))
    if (cb) cb()
  }
  page._request = (url, payload) => {
    state.requests.push({ url, payload })
    return Promise.resolve({ data: { voiceStatus: 2, voiceSample: 'media-asset:73',
      voiceRequestId: payload && payload.voiceRequestId, voiceIntentId: 17, voiceIntentState: 'READY', voiceSelected: true } })
  }
  state.page = page
  return state
}

const tick = () => new Promise((resolve) => setImmediate(resolve))

test('录音面板是三句逐句录制的「录制声音」卡(L09:中文标签、录制与确认同一版式)', () => {
  assert.doesNotMatch(WXML, /Voice mode/)
  assert.match(WXML, /<text class="npc-voice-mode__title">录制声音<\/text>/)
  assert.doesNotMatch(WXML, /确认录音/, '录完确认不再换面板标题')
  assert.match(WXML, /<view class="npc-voice-mode [^"]*">[\s\S]*?<view wx:else class="npc-voice-complete">[\s\S]*?<\/view>\s*<button wx:if="\{\{voiceFile\}\}" class="npc-voice-hold"/, '确认态留在同一张录音卡里')
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
    onWxUpload(opts) { opts.success({ data: JSON.stringify({ code: 200, assetId: 73, url: 'https://cdn/voice.mp3' }) }) },
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
  assert.equal(h.requests[0].payload.voiceSample, 'media-asset:73')
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
  // 2026-10-27 M-28:被拒后给「去设置开启麦克风」直达按钮(wx.authorize 不会再弹第二次)
  assert.equal(h.page.data.voiceNeedSetting, true)
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


test('拒绝第三方声音克隆单独同意保留合法录音且不上传、不发起复刻', async () => {
  const h = loadPage({consentDenied:true});
  h.page.onVoiceConsentChange({detail:{checked:true}}); h.page.onVoiceHoldStart(); await tick();
  h.page.data.voiceSentenceConfirmed = 3;
  h.onRecorderStop({tempFilePath:'/tmp/voice.mp3',duration:20000,fileSize:1024});
  h.page.uploadVoice(); await tick();
  assert.equal(h.wxUploads,0); assert.equal(h.requests.length,0); assert.ok(h.page.data.voiceFile);
  assert.equal(h.page.data.voiceBusy,false); assert.match(h.page.data.voiceError,/拒绝/);
});

test('声音查询绑定接收真实tap事件，同时保留内部清除guard且只读不派发', async () => {
  const h = loadPage();
  await assert.doesNotReject(h.page.refreshVoice({ type: 'tap', detail: {}, currentTarget: { dataset: {} } }));
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, '/api/merchant/npc/voice/status');
  const before = JSON.stringify(h.page.data);
  await assert.doesNotReject(h.page.refreshVoice(() => false));
  assert.equal(h.requests.length, 1, '失效清除代次不得查询或重新派发');
  assert.equal(JSON.stringify(h.page.data), before);
  assert.equal(h.wxUploads, 0);
});
