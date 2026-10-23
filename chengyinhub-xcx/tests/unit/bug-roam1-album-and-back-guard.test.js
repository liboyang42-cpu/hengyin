'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ALBUM_SAVE, classifyAlbumSaveError, normalizeAlbumOutcome } = require('../../utils/album-save-outcome.js')

test('P0-58 存相册结果建模成值:取消 / 权限拒绝 / 失败可区分', () => {
  assert.equal(classifyAlbumSaveError({ errMsg: 'saveImageToPhotosAlbum:fail cancel' }), ALBUM_SAVE.CANCELLED)
  assert.equal(classifyAlbumSaveError({ errMsg: 'saveImageToPhotosAlbum:fail auth deny' }), ALBUM_SAVE.DENIED)
  assert.equal(classifyAlbumSaveError({ errMsg: 'saveImageToPhotosAlbum:fail authorize no response' }), ALBUM_SAVE.DENIED)
  assert.equal(classifyAlbumSaveError({ errMsg: 'saveImageToPhotosAlbum:fail io error' }), ALBUM_SAVE.FAILED)
  assert.equal(classifyAlbumSaveError(null), ALBUM_SAVE.FAILED)
  assert.equal(normalizeAlbumOutcome(true), ALBUM_SAVE.SAVED)
  assert.equal(normalizeAlbumOutcome(false), ALBUM_SAVE.FAILED)
  assert.equal(normalizeAlbumOutcome('cancelled'), ALBUM_SAVE.CANCELLED)
  assert.equal(normalizeAlbumOutcome('whatever'), ALBUM_SAVE.FAILED, '未知值不得当成功')
})

test('P0-58 漫游页导出把取消带给调用方，且取消不弹「保存失败」', async () => {
  let config
  global.Page = (x) => { config = x }
  global.getApp = () => ({ getUserID: () => '9', globalData: { user_id: '9', features: {} }, isDevEnv: () => false, sendRequest() {} })
  const toasts = []
  global.wx = {
    getStorageSync() {}, setStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }), getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast: (o) => toasts.push(o.title), showLoading() {}, hideLoading() {},
    saveImageToPhotosAlbum: ({ fail }) => fail({ errMsg: 'saveImageToPhotosAlbum:fail cancel' }),
  }
  const source = '../../pages/roam/index.js'
  delete require.cache[require.resolve(source)]
  require(source)
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)), setData() {} })
  const result = await page._exportAndSave('生成卡片', () => Promise.resolve('/tmp/card.png'), () => true)
  assert.equal(result.saved, false)
  assert.equal(result.outcome, ALBUM_SAVE.CANCELLED)
  assert.equal(toasts.includes('保存失败'), false)

  global.wx.saveImageToPhotosAlbum = ({ fail }) => fail({ errMsg: 'saveImageToPhotosAlbum:fail io error' })
  const failed = await page._exportAndSave('生成卡片', () => Promise.resolve('/tmp/card.png'), () => true)
  assert.equal(failed.outcome, ALBUM_SAVE.FAILED)
  assert.equal(toasts.includes('保存失败'), true)
})

test('P0-50 漫游地点详情的答题半屏有物理返回守卫，返回等同取消', () => {
  const dir = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-roam-poi-detail')
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8')
  const guard = wxml.match(/<page-container\b[^>]*>/)
  assert.ok(guard, '答题半屏必须挂 page-container 返回守卫')
  assert.match(guard[0], /show="\{\{choiceSheetShow\}\}"/)
  assert.match(guard[0], /bind:afterleave="onChoiceSheetCancel"/)
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8')
  // 选中提交先把 choiceSheetShow 置 false,随后的 afterleave 必须被守卫忽略,不能再记一次取消
  assert.match(js, /onChoiceSheetCancel\(\) \{\n\s*if \(!this\.data\.choiceSheetShow/)
})
