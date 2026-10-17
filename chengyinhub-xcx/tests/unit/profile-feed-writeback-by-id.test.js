'use strict'

// BL-195：个人主页动态点赞/收藏的成功回调必须按发起时的 item.id 在当前列表里定位后生成新对象回写，
// 不能按发起时的 index 原地改 list[index] —— 翻页或切 tab 重载后返回会写到别的动态上。
const assert = require('node:assert/strict')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const PROFILE = 'subpackageMember/components/cy/profile/index.js'

global.wx = global.wx || {
  showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {},
  getStorageSync() { return '' }, setStorageSync() {},
  stopPullDownRefresh() {}, startPullDownRefresh() {},
  navigateTo() {}, navigateBack() {}, reLaunch() {}, switchTab() {},
  setNavigationBarTitle() {}, previewImage() {}, vibrateShort() {}, pageScrollTo() {},
}

function setByPath(target, keyPath, value) {
  const parts = keyPath.replace(/\[(\d+)\]/g, '.$1').split('.')
  let cursor = target
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {}
    cursor = cursor[part]
  })
  cursor[parts[parts.length - 1]] = value
}

function mountProfile() {
  const requests = []
  let definition = null
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => '9',
    getUserRole: () => 'player',
    getUserType: () => 1,
    isDevEnv: () => false,
    getPageSize: () => 10,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest(request) { requests.push(request) },
    tips() {},
  }
  global.getApp = () => app
  global.Component = (def) => { definition = def }
  delete require.cache[require.resolve(path.join(ROOT, PROFILE))]
  require(path.join(ROOT, PROFILE))

  const component = Object.assign({}, definition.methods, {
    data: JSON.parse(JSON.stringify(definition.data)),
  })
  component.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => setByPath(component.data, key, value))
    if (callback) callback()
  }
  component.answer = async (url, response) => {
    const request = requests.filter((r) => r.url === url).pop()
    assert.ok(request, '缺少请求 ' + url)
    request.success(response)
    await Promise.resolve(); await Promise.resolve()
  }
  return component
}

const tap = (index, type) => ({ currentTarget: { dataset: { index: index, type: type } } })

test('个人主页点赞：翻页/重载后返回只回写同 id 那条，不原地改旧对象', async () => {
  const profile = mountProfile()
  const original = { id: 11, isLiked: 0, likeCount: 3 }
  profile.data.list = [original, { id: 12, isLiked: 0, likeCount: 5 }]
  profile.lickClick(tap(0, 1))
  profile.data.list = [{ id: 20, isLiked: 0, likeCount: 1 }, { id: 11, isLiked: 0, likeCount: 3 }]
  await profile.answer('/api/creativesquare/like', { code: '200' })
  assert.deepEqual(profile.data.list[0], { id: 20, isLiked: 0, likeCount: 1 }, '不得点亮别人的动态')
  assert.deepEqual(profile.data.list[1], { id: 11, isLiked: 1, likeCount: 4 })
  assert.deepEqual(original, { id: 11, isLiked: 0, likeCount: 3 }, '不得原地改发起时的对象')

  const gone = mountProfile()
  gone.data.list = [{ id: 11, isLiked: 0, likeCount: 3 }]
  gone.lickClick(tap(0, 1))
  gone.data.list = [{ id: 30, isLiked: 0, likeCount: 0 }]
  await gone.answer('/api/creativesquare/like', { code: '200' })
  assert.deepEqual(gone.data.list, [{ id: 30, isLiked: 0, likeCount: 0 }], '目标已不在列表里时不回写')
})

test('个人主页收藏：翻页/重载后返回只回写同 id 那条，找不到不动', async () => {
  const profile = mountProfile()
  const original = { id: 11, isBookmarked: 0 }
  profile.data.list = [original, { id: 12, isBookmarked: 0 }]
  profile.favoriteClick(tap(0))
  profile.data.list = [{ id: 20, isBookmarked: 0 }, { id: 11, isBookmarked: 0 }]
  await profile.answer('/api/creativesquare/bookmark', { code: '200' })
  assert.deepEqual(profile.data.list, [{ id: 20, isBookmarked: 0 }, { id: 11, isBookmarked: 1 }])
  assert.equal(original.isBookmarked, 0, '不得原地改发起时的对象')

  const gone = mountProfile()
  gone.data.list = [{ id: 11, isBookmarked: 0 }]
  gone.favoriteClick(tap(0))
  gone.data.list = [{ id: 30, isBookmarked: 0 }]
  await gone.answer('/api/creativesquare/bookmark', { code: '200' })
  assert.deepEqual(gone.data.list, [{ id: 30, isBookmarked: 0 }], '目标已不在列表里时不回写')
})
