'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const helperSource = fs.readFileSync(path.join(ROOT, 'utils/motion-preference.js'), 'utf8')
function storage(mode, initial) {
  if (mode === 'absent') return undefined
  if (mode === 'missing-method') return {}
  const values = { play_reduced_motion: initial, roam_reduced_motion: initial }
  const writes = []
  return { values, writes, getStorageSync: key => values[key], setStorageSync(key, value) {
    writes.push(key)
    if (mode === 'primary-fail' || (mode === 'legacy-fail' && key === 'roam_reduced_motion')) throw new Error('storage failed')
    values[key] = value
  } }
}
function helper(wx) {
  const module = { exports: {} }
  vm.runInNewContext(helperSource, { module, wx })
  return module.exports
}
function mount(file, wx) {
  const toasts = [], cleared = [], patches = []
  const motion = helper(wx)
  const noop = new Proxy(function () { return [] }, { get() { return noop } })
  let page
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), {
    wx, Page(value) { page = value }, getApp: () => ({ globalData: {} }),
    clearInterval(id) { cleared.push(id) },
    require(request) {
      if (request.endsWith('/motion-preference.js')) return motion
      if (request.endsWith('/toast.js')) return message => toasts.push(message)
      return noop
    },
  }, { filename: file })
  page.setData = patch => { patches.push(patch); Object.assign(page.data, patch) }
  return { page, toasts, cleared, patches }
}
for (const initial of [false, true]) {
  for (const mode of ['absent', 'missing-method', 'primary-fail', 'legacy-fail', 'normal']) {
    const failed = ['absent', 'missing-method', 'primary-fail'].includes(mode)
    test(`helper ${mode}, old=${initial}`, () => {
      const wx = storage(mode, initial)
      const h = helper(wx)
      assert.equal(h.writeReducedMotion(null, !initial), failed ? null : !initial)
      if (wx && wx.values) {
        assert.equal(wx.values.play_reduced_motion, failed ? initial : !initial)
        assert.deepEqual(wx.writes, mode === 'primary-fail' ? ['play_reduced_motion'] : ['play_reduced_motion', 'roam_reduced_motion'])
        assert.equal(h.readReducedMotion(null), failed ? initial : !initial)
      }
    })
    for (const file of ['pages/play/index.js', 'pages/roam/index.js']) {
      test(`${file} real toggle ${mode}, old=${initial}`, () => {
        const wx = storage(mode, initial)
        const h = mount(file, wx)
        const target = { scale: 16, center: { lat: 30, lng: 120 } }
        Object.assign(h.page, { data: { reducedMotion: initial, mapScale: 12 }, _zoomAnim: 7, _zoomLock: true, _zoomTarget: target })
        h.page.toggleReducedMotion()
        assert.equal(h.page.data.reducedMotion, failed ? initial : !initial)
        assert.deepEqual(h.toasts, [failed ? '设置未保存，请重试' : (!initial ? '已减少动态效果' : '已恢复动态效果')])
        const stop = !failed && !initial && file.includes('/roam/')
        assert.deepEqual(h.cleared, stop ? [7] : [])
        assert.equal(h.page._zoomAnim, stop ? null : 7)
        assert.equal(h.page._zoomLock, stop ? false : true)
        assert.equal(h.page._zoomTarget, stop ? null : target)
        assert.equal(h.page.data.mapScale, stop ? 16 : 12)
        if (failed) assert.deepEqual(h.patches, [])
        if (wx && wx.values) assert.equal(wx.values.play_reduced_motion, failed ? initial : !initial)
      })
    }
  }
}
