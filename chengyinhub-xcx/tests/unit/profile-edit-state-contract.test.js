'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js') // node:vm + 沙箱里装真 utils/toast.js·loading.js

const ROOT = path.resolve(__dirname, '../..')
const read = (relative) => fs.readFileSync(path.join(ROOT, relative), 'utf8')

function applyPatch(target, patch) {
  Object.entries(patch).forEach(([key, value]) => {
    const parts = key.split('.')
    let cursor = target
    for (let index = 0; index < parts.length - 1; index += 1) cursor = cursor[parts[index]]
    cursor[parts.at(-1)] = value
  })
}

function loadPage() {
  let definition
  const requests = []
  const reviewReads = []
  const toasts = []
  const backs = []
  const timers = []
  const clearedTimers = new Set()
  const images = []
  const masks = []
  const session = { owner: 7, token: 'unit-session-a', nickname: '', avatar: '' }
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => session.owner,
    getAuthorization: () => session.token,
    setNickname(value) { session.nickname = value }, setAvatar(value) { session.avatar = value },
    tips() {},
    chooseImage(callback) { images.push(callback) },
    // W10b #25 头像审核回执是独立的只读旁路,另记一处,不打乱资料读写请求的顺序断言。
    sendRequest(options) { (options.url === '/api/user/profile/review' ? reviewReads : requests).push(options) },
  }
  vm.runInNewContext(read('pages/gerenziliao/gerenziliao.js'), {
    getApp: () => app,
    // 有上一页:全页返回合同(#806)按 getCurrentPages().length 选 navigateBack 还是根栈落点
    getCurrentPages: () => [{}, {}],
    Page(value) { definition = value },
    require(request) {
      if (request === '../../utils/response-shape.js') return require('../../utils/response-shape.js')
      if (request === '../../utils/form-state.js') return require('../../utils/form-state.js')
      if (request === './nickname-check.js') return require('../../pages/gerenziliao/nickname-check.js')
      if (request === '../../utils/profile-review.js') return require('../../utils/profile-review.js')
      // 2026-10-27 L08 G-5:资料保存成功广播身份变化(纯内存模块,用真身)
      if (request === '../../utils/identity-sync.js') return require('../../utils/identity-sync.js')
      throw new Error(`unexpected require: ${request}`)
    },
    wx: {
      hideLoading() { masks.push('hide') },
      showLoading() { masks.push('show') },
      showToast(options) { toasts.push(options) },
      navigateBack(options) { backs.push(options || {}) },
    },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length },
    clearTimeout(id) { clearedTimers.add(id) },
    console,
  }, { filename: 'pages/gerenziliao/gerenziliao.js' })
  const page = Object.assign({}, definition)
  page.data = JSON.parse(JSON.stringify(definition.data))
  page.setData = function (patch, callback) {
    applyPatch(this.data, patch)
    if (callback) callback()
  }
  return { page, requests, reviewReads, toasts, backs, timers, clearedTimers, session, images, masks }
}

function assertStateContract(wxml, jsonText) {
  const json = JSON.parse(jsonText)
  const components = json.usingComponents || {}
  for (const name of ['cy-skeleton', 'cy-state-shell', 'cy-inline-error', 'cy-progress-status']) {
    assert.ok(components[name], `个人资料页必须注册 ${name}`)
  }
  assert.match(wxml, /<cy-skeleton\b[^>]*wx:if="\{\{loading && !loaded\}\}"[^>]*type="form-section"/,
    '首载不能先把未知资料渲染成“未填写”')
  assert.match(wxml, /<cy-state-shell\b[^>]*wx:elif="\{\{loadError && !loaded\}\}"[^>]*kind="\{\{loadErrorKind\}\}"[^>]*bind:primary="retryLoad"/,
    '首载失败必须有语义状态与恢复动作')
  assert.doesNotMatch(wxml, /<cy-inline-error\b[^>]*loadError/,
    '刷新失败必须保留最后确认的资料并局部重试')
  assert.match(wxml, /<cy-progress-status\b[^>]*wx:if="\{\{saving \|\| saveSucceeded\}\}"/,
    '保存中与成功必须留下可读进度/回执')
  assert.match(wxml, /<cy-inline-error\b[^>]*wx:if="\{\{saveError\}\}"[^>]*bind:action="saveInfo"/,
    '保存失败必须在表单内留下重试入口')
  assert.match(wxml, /<cy-btn\b[^>]*loading="\{\{saving\}\}"[^>]*disabled="\{\{!canSave \|\| saving\}\}"[^>]*bindtap="saveInfo"/,
    '保存按钮必须绑定 pending 与防重入状态')
}

test('个人资料首载、stale 刷新和保存回执采用统一状态组件', () => {
  assertStateContract(
    read('pages/gerenziliao/gerenziliao.wxml'),
    read('pages/gerenziliao/gerenziliao.json'),
  )
})

test('个人资料未知值不会伪装为空；失败可重试且保留最后确认内容', () => {
  const first = loadPage()
  assert.equal(first.page.data.loading, true)
  assert.equal(first.page.data.loaded, false)
  first.page.getUserData()
  first.page.getUserData()
  assert.equal(first.requests.length, 1, '资料恢复动作同步连点不能并发请求并产生乱序覆盖')
  first.requests[0].fail()
  assert.equal(first.page.data.loading, false)
  assert.equal(first.page.data.loaded, false)
  assert.equal(first.page.data.loadErrorKind, 'network')
  first.page.retryLoad()
  assert.equal(first.requests.length, 2)

  const stale = loadPage()
  stale.page.data.loaded = true
  stale.page.data.userInfo.name = '已确认昵称'
  stale.page.getUserData()
  assert.equal(stale.page.data.refreshing, true)
  stale.requests[0].success({ code: 500, msg: '服务繁忙' })
  assert.equal(stale.page.data.refreshing, false)
  assert.equal(stale.page.data.userInfo.name, '已确认昵称')
  assert.equal(stale.page.data.loadErrorKind, 'data')
})

test('保存防重复提交；失败保留草稿，成功留下回执再返回', () => {
  const h = loadPage()
  h.page.data.loaded = true
  h.page.data.canSave = true
  h.page.data.userInfo.name = '城市玩家'
  h.page.data.userInfo.introduction = '夜行路线'
  h.page.saveInfo()
  h.page.saveInfo()
  assert.equal(h.requests.length, 1)
  assert.equal(h.page.data.saving, true)
  h.requests[0].fail()
  assert.equal(h.page.data.saving, false)
  assert.match(h.page.data.saveError, /网络/)
  assert.equal(h.page.data.userInfo.introduction, '夜行路线')

  h.page.saveInfo()
  h.requests[1].success({ code: 200 })
  assert.equal(h.page.data.saving, false)
  assert.equal(h.page.data.saveSucceeded, true)
  assert.equal(h.page.data.saveError, '')
  assert.equal(h.timers.length, 1)
  h.timers[0].callback()
  assert.equal(h.backs.length, 1)
})

test('个人资料保存成功后主动离页会取消延迟返回，不能从下一页再多退一层', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    loaded: true,
    canSave: true,
    userInfo: Object.assign({}, h.page.data.userInfo, { name: '城市玩家' }),
  })
  h.page.saveInfo()
  h.requests[0].success({ code: 200 })
  assert.equal(h.timers.length, 1)

  h.page.onUnload()
  h.timers.forEach((timer, index) => {
    if (!h.clearedTimers.has(index + 1)) timer.callback()
  })

  assert.deepEqual(h.backs, [])
})

test('个人资料页卸载后，迟到的资料请求不得再改写已离开的页面', () => {
  const h = loadPage()
  h.page.getUserData()
  const request = h.requests[0]
  h.page.onUnload()
  let postUnloadWrites = 0
  const originalSetData = h.page.setData
  h.page.setData = function (patch, callback) {
    postUnloadWrites += 1
    originalSetData.call(this, patch, callback)
  }

  request.success({ code: 200, data: { name: '迟到资料' } })
  request.fail({ msg: '迟到失败' })

  assert.equal(postUnloadWrites, 0)
})

test('个人资料页卸载后，迟到的保存回执不得再展示成功或安排返回', () => {
  const h = loadPage()
  Object.assign(h.page.data, {
    loaded: true,
    canSave: true,
    userInfo: Object.assign({}, h.page.data.userInfo, { name: '城市玩家' }),
  })
  h.page.saveInfo()
  const request = h.requests[0]
  h.page.onUnload()
  let postUnloadWrites = 0
  const originalSetData = h.page.setData
  h.page.setData = function (patch, callback) {
    postUnloadWrites += 1
    originalSetData.call(this, patch, callback)
  }

  request.success({ code: 200 })
  request.fail({ msg: '迟到失败' })

  assert.equal(postUnloadWrites, 0)
  assert.deepEqual(h.toasts, [])
  assert.deepEqual(h.timers, [])
})

test('负控:去掉首载骨架或保存 pending 接线时状态契约必须判红', () => {
  const source = read('pages/gerenziliao/gerenziliao.wxml')
  const json = read('pages/gerenziliao/gerenziliao.json')
  const noSkeleton = source.replace(/<cy-skeleton\b[^>]*\/>/, '<view wx:if="{{loading}}">加载中</view>')
  assert.ok(noSkeleton !== source, '首载骨架负控锚点失效')
  assert.throws(() => assertStateContract(noSkeleton, json), /首载不能/)

  const noPending = source.replace(' loading="{{saving}}"', '')
  assert.ok(noPending !== source, '保存 pending 负控锚点失效')
  assert.throws(() => assertStateContract(noPending, json), /保存按钮/)
})

test('只改签名时不重提服务器原昵称，其余资料载荷保持原样', () => {
  const h = loadPage()
  const profile = { name: '原昵称', nickname: '显示别名', avatar: 'avatar', introduction: '旧签名', website: 'website', caseIntroducton: '项目', casePics: 'pics', wechat: 'qr' }
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: profile })
  h.page.data.userInfo.introduction = '新签名'
  h.page.saveInfo()
  const payload = JSON.parse(h.requests[1].data)
  assert.equal(Object.hasOwn(payload, 'name'), false)
  assert.deepEqual(payload, { avatar: 'avatar', introduction: '新签名', website: 'website', caseIntroducton: '项目', casePics: 'pics', wechat: 'qr', tagIds: '' })
})

test('实际改昵称仍提交name，409保留草稿并解除保存锁', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: '原昵称' } })
  h.page.data.userInfo.name = '新昵称'
  h.page.saveInfo()
  assert.equal(JSON.parse(h.requests[1].data).name, '新昵称')
  h.requests[1].success({ code: 409, msg: '昵称已被占用' })
  assert.equal(h.page.data.userInfo.name, '新昵称')
  assert.equal(h.page.data.saving, false)
  assert.equal(h.page.data.saveSucceeded, false)
  assert.match(h.page.data.saveError, /昵称已被占用/)
})

test('正常资料刷新更新原始name快照，失败刷新不得覆盖快照', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: '第一次昵称', nickname: '无关别名' } })
  assert.equal(h.page._loadedProfileName, '第一次昵称')
  h.page.getUserData()
  h.requests[1].success({ code: 200, data: { name: '刷新昵称' } })
  assert.equal(h.page._loadedProfileName, '刷新昵称')
  h.page.getUserData()
  h.requests[2].success({ code: 500, data: { name: '失败昵称' } })
  assert.equal(h.page._loadedProfileName, '刷新昵称')
  h.page.getUserData()
  h.requests[3].fail()
  assert.equal(h.page._loadedProfileName, '刷新昵称')
  h.page.data.userInfo.introduction = '新签名'
  h.page.saveInfo()
  assert.equal(Object.hasOwn(JSON.parse(h.requests[4].data), 'name'), false)
})

test('profile identity change clears old private draft before the new owner can save', () => {
  const h = loadPage()
  h.page.onLoad({})
  h.requests[0].success({ code: 200, data: { name: 'owner-a', phone: '13800000000', avatar: 'avatar-a', introduction: 'private-a' } })
  h.page.data.tempName = 'dirty-a'
  h.page.data.tempWechat = 'private-qr-a'
  if (h.page.onHide) h.page.onHide()
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.onShow()
  assert.equal(h.page.data.userInfo.name, '')
  assert.equal(h.page.data.userInfo.phone, '')
  assert.equal(h.page.data.tempName, '')
  assert.equal(h.page.data.tempWechat, '')
  assert.equal(h.page.data.canSave, false)
  h.page.saveInfo()
  assert.equal(h.requests.filter(x => x.url === '/api/user/update').length, 0)
  assert.equal(h.requests.length, 2, 'new owner gets one fresh read')
  h.requests[1].success({ code: 200, data: { name: 'owner-b' } })
  assert.equal(h.page.data.userInfo.name, 'owner-b')
})

test('same owner token renewal keeps the normal draft and successful save', () => {
  const h = loadPage()
  h.page.onLoad({})
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.data.userInfo.name = 'edited-a'
  h.page.data.userInfo.introduction = 'unsaved-draft'
  if (h.page.onHide) h.page.onHide()
  h.session.token = 'unit-session-a-renewed'
  h.page.onShow()
  assert.equal(h.page.data.userInfo.introduction, 'unsaved-draft')
  assert.equal(h.requests.length, 1)
  h.page.saveInfo()
  h.requests[1].success({ code: 200 })
  assert.equal(h.session.nickname, 'edited-a')
  assert.equal(h.page.data.saveSucceeded, true)
})

test('old saved-page timer cannot navigate a new identity or a later lifecycle', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.saveInfo()
  h.requests[1].success({ code: 200 })
  assert.equal(h.timers.length, 1)
  if (h.page.onHide) h.page.onHide()
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.onShow()
  h.timers[0].callback()
  assert.deepEqual(h.backs, [])
})

test('old image-picker callbacks are rejected after an observed A to B to A change', () => {
  const h = loadPage()
  h.page.onLoad({})
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.uploadPic()
  h.page.uploadWechat()
  h.page.uploadCasePics()
  if (h.page.onHide) h.page.onHide()
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.onShow()
  if (h.page.onHide) h.page.onHide()
  Object.assign(h.session, { owner: 7, token: 'unit-session-a-new-login' })
  h.page.onShow()
  h.images[0](['old-avatar-a'])
  h.images[1](['old-qr-a'])
  h.images[2](['old-case-a'])
  assert.equal(h.page.data.userInfo.avatar, '')
  assert.equal(h.page.data.tempWechat, '')
  assert.equal(h.page.data.tempCasePics.length, 0)
})

test('normal same-owner image picker survives hide and applies only after return', () => {
  const h = loadPage()
  h.page.onLoad({})
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.uploadPic()
  if (h.page.onHide) h.page.onHide()
  h.session.token = 'unit-session-a-renewed'
  h.images[0](['new-avatar-a'])
  assert.equal(h.page.data.userInfo.avatar, '')
  h.page.onShow()
  assert.equal(h.page.data.userInfo.avatar, 'new-avatar-a')
})

test('hidden save settles its original response once without automatically sending a second write', () => {
  const h = loadPage()
  h.page.onLoad({})
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.data.userInfo.name = 'edited-a'
  h.page.saveInfo()
  if (h.page.onHide) h.page.onHide()
  h.requests[1].success({ code: 200 })
  assert.equal(h.page.data.saving, true)
  assert.equal(h.session.nickname, '')
  h.page.onShow()
  assert.equal(h.page.data.saving, false)
  assert.equal(h.session.nickname, 'edited-a')
  assert.equal(h.requests.filter(x => x.url === '/api/user/update').length, 1)
})

test('identity changes before onShow cannot dispatch the previous owner draft', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: 'owner-a', phone: '13800000000' } })
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.saveInfo()
  assert.equal(h.requests.length, 1)
  assert.equal(h.page.data.userInfo.phone, '')
  assert.equal(h.page.data.canSave, false)
})

test('same-owner hide/show invalidates only the old navigation and pending read', () => {
  const h = loadPage()
  h.page.getUserData()
  const oldRead = h.requests[0]
  h.page.onHide()
  h.page.onShow()
  assert.equal(h.requests.length, 2)
  h.requests[1].success({ code: 200, data: { name: 'new-visible-read' } })
  oldRead.success({ code: 200, data: { name: 'old-hidden-read' } })
  assert.equal(h.page.data.userInfo.name, 'new-visible-read')
  h.page.saveInfo()
  h.requests[2].success({ code: 200 })
  h.page.onHide()
  h.page.onShow()
  const currentTimer = h.page._profileReturnTimer
  h.timers[0].callback()
  assert.deepEqual(h.backs, [])
  assert.equal(h.page._profileReturnTimer, currentTimer, 'old timer cannot clear the new lifecycle handle')
  assert.equal(h.timers.length, 2, 'saved receipt resumes its return without another save')
  h.timers[1].callback()
  assert.equal(h.backs.length, 1)
})

test('deferred phone or save response is cleared when the visible owner changes', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  h.page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'unit-phone-code' } })
  h.page.saveInfo()
  h.page.onHide()
  h.requests[1].success({ code: 200, data: '13800000000' })
  h.requests[2].success({ code: 200 })
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.onShow()
  assert.equal(h.page.data.userInfo.phone, '')
  assert.equal(h.page.data.saving, false)
  assert.equal(h.page.data.saveSucceeded, false)
  assert.equal(h.session.nickname, '')
  assert.equal(h.timers.length, 0)
  assert.equal(h.requests.filter(x => x.url === '/api/user/update').length, 1)
})

test('phone loading is released once on hide and an old response cannot close the next owner mask', () => {
  const h = loadPage()
  h.page.getUserData()
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  const event = { detail: { errMsg: 'getPhoneNumber:ok', code: 'unit-phone-code' } }
  h.page.getPhoneNumber(event)
  h.page.getPhoneNumber(event)
  assert.equal(h.requests.length, 2, 'the in-flight owned mask keeps phone binding single-flight')
  h.page.onHide()
  assert.deepEqual(h.masks, ['show', 'hide'])
  Object.assign(h.session, { owner: 8, token: 'unit-session-b' })
  h.page.onShow()
  h.page.getPhoneNumber(event)
  h.requests[1].success({ code: 200, data: '13800000000' })
  assert.deepEqual(h.masks, ['show', 'hide', 'show'])
  h.requests[3].success({ code: 200, data: '13900000000' })
  assert.deepEqual(h.masks, ['show', 'hide', 'show', 'hide'])
  assert.equal(h.page.data.userInfo.phone, '13900000000')
})

test('a private read without a matching loading show must not dismiss phone binding loading', () => {
  const h = loadPage()
  h.page.getUserData()
  h.page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'unit-phone-code' } })
  h.requests[0].success({ code: 200, data: { name: 'owner-a' } })
  assert.deepEqual(h.masks, ['show'])
  h.requests[1].fail()
  assert.deepEqual(h.masks, ['show', 'hide'])
})

for (const transition of ['owner', 'hide']) {
  test(`昵称查重和推荐在 ${transition} 后不写回旧账号`, () => {
    for (const kind of ['check', 'suggest']) {
      const h = loadPage()
      h.page.getUserData()
      h.requests[0].success({ code: 200, data: { name: '原昵称', phone: '' } })
      h.page.openPop4()
      h.page.data.tempName = '新昵称'
      h.page._nicknameSeq = 3
      if (kind === 'check') h.page.requestNicknameCheck('新昵称', 3)
      else h.page.loadNicknameSuggestions('新昵称', false)
      const pending = h.requests.at(-1)
      if (transition === 'owner') {
        h.session.owner = 8
        h.session.token = 'unit-session-b'
      } else h.page.onHide()
      pending.success({ code: 200, data: { nickname: '旧账号推荐', available: false, cursor: 1 } })
      assert.equal(h.requests.length, 2, '旧回复不继续触发下一轮推荐')
      assert.equal(h.page.data.nicknameSuggestions.length, 0)
      if (transition === 'owner') {
        assert.equal(h.page.data.userInfo.name, '')
        assert.equal(h.page.data.nicknameState, '')
        assert.equal(h.page._savedName, undefined)
      }
    }
  })
}

test('W10b #25:资料读成功后只读拉一次头像审核回执,按回执显示;换了未保存的新头像即撤掉旧回执', () => {
  const h = loadPage()
  h.page.onLoad()
  h.requests[0].success({ code: 200, data: { name: 'owner-a', avatar: 'media-asset:9' } })
  assert.equal(h.reviewReads.length, 1)
  assert.equal(h.reviewReads[0].method, 'GET')
  // 2026-10-30 业主定稿 P-2:审核中新头像直接显示、不出提示;只有未通过才出一行(文字照 profile-review 原样)
  h.reviewReads[0].success({ code: 200, data: { available: true, avatar: { versionNo: 2, state: 'PENDING', live: true } } })
  assert.equal(h.page.data.avatarReviewText, '')
  h.page.loadProfileReview(h.page.profileContext())
  h.reviewReads[1].success({ code: 200, data: { available: true, avatar: { versionNo: 2, state: 'REJECTED', live: true } } })
  assert.match(h.page.data.avatarReviewText, /未通过图片安全复核/)
  h.page.uploadPic()
  h.images[0](['media-asset:10'])
  assert.equal(h.page.data.avatarReviewText, '')
})
