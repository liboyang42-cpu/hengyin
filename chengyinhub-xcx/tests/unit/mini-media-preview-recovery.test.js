'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')

// 装载真实 Page 与真实预览 helper；其余依赖不参与这些无生命周期的点击动作。
function mount(relativePath) {
  const previews = [], toasts = []
  const wx = { previewImage(options) { previews.push(JSON.parse(JSON.stringify(options))) } }
  const helperModule = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, 'utils/media-preview.js'), 'utf8'),
    { module: helperModule, wx })
  const noop = new Proxy(function () { return [] }, { get() { return noop } })
  let page
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, relativePath), 'utf8'), {
    wx, getApp: () => ({}), Page(value) { page = value },
    require(request) {
      if (request.endsWith('/media-preview.js')) return helperModule.exports
      if (request.endsWith('/toast.js')) return (message) => toasts.push(message)
      return noop
    },
  }, { filename: relativePath })
  return { page, previews, toasts }
}
const cases = [
  ['执照', 'pages/merchant/decor/index.js', 'previewLicense', (p, v) => { p.data.m = { businessLicense: v } }],
  ['品牌图', 'pages/merchant/decor/index.js', 'previewBrandImages', (p, v) => { p.data.brandImages = v }],
  ['活动封面', 'pages/publish/activity/index.js', 'onCoverPreview', (p, v) => { p.data.formData = { imgUrl: v } }],
]
for (const [label, file, action, set] of cases) {
  test(label + '：非法图片不传入原生预览', () => {
    const h = mount(file)
    for (const value of [123, { bad: true }, null, '']) {
      set(h.page, action === 'previewBrandImages' ? [value] : value)
      h.page[action]()
    }
    assert.deepEqual(h.previews, [])
  })
  test(label + '：正常图片带明确 current', () => {
    const h = mount(file)
    const url = 'https://cdn.example.com/cover.png'
    set(h.page, action === 'previewBrandImages' ? [url] : url)
    h.page[action]()
    assert.deepEqual(h.previews, [{ current: url, urls: [url] }])
    assert.deepEqual(h.toasts, [])
  })
}
test('品牌图：混合列表过滤空值和非字符串，current 回退到首张有效图', () => {
  const h = mount('pages/merchant/decor/index.js')
  h.page.data.brandImages = [null, '', 123, 'https://cdn.example.com/a.png', 'https://cdn.example.com/b.png']
  h.page.previewBrandImages()
  assert.deepEqual(h.previews, [{
    current: 'https://cdn.example.com/a.png',
    urls: ['https://cdn.example.com/a.png', 'https://cdn.example.com/b.png'],
  }])
})
test('无图时保留商家原有提示，活动封面继续静默', () => {
  const h = mount('pages/merchant/decor/index.js')
  h.page.data.m = { businessLicense: '' }
  h.page.data.brandImages = []
  h.page.previewLicense()
  h.page.previewBrandImages()
  assert.deepEqual(h.previews, [])
  assert.deepEqual(h.toasts, ['入驻时提交，如需更新请联系平台', '入驻时提交，如需更新请联系平台'])
  const activity = mount('pages/publish/activity/index.js')
  activity.page.data.formData = { imgUrl: '' }
  activity.page.onCoverPreview()
  assert.deepEqual(activity.previews, [])
  assert.deepEqual(activity.toasts, [])
})
