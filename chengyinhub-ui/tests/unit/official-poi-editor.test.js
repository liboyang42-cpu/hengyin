const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function mount(api) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/roampoi/official-editor.vue'), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import ImageUpload .+$/m, 'const ImageUpload = {};')
    .replace(/^import \{ officialPoiDraft, saveOfficialPoi \} .+$/m, 'const { officialPoiDraft, saveOfficialPoi } = api;')
  const component = new Function('api', script.replace('export default', 'return'))(api)
  const events = []
  const vm = { ...component.data(), visible: true, poiId: 42, $emit: name => events.push(name), $modal: { msgSuccess() {} } }
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm)
  return { vm, events }
}

test('official editor preserves full gallery, blocks double submits and retains failed draft for retry', async () => {
  const calls = []; let reject, resolve
  const h = mount({ saveOfficialPoi: payload => { calls.push(payload); return new Promise((yes, no) => { resolve = yes; reject = no }) } })
  h.vm.poi = { id: 42, name: '街角艺术馆', photoRequired: false }; h.vm.game.questionName = '入口出现哪一个字？'
  h.vm.pictures = '/profile/curated/entrance.jpg,/profile/curated/detail.png'
  const first = h.vm.save(); await h.vm.save()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].poi.coverImg, 'curated/entrance.jpg')
  assert.equal(calls[0].poi.photoRequired, false)
  assert.deepEqual(JSON.parse(calls[0].poi.galleryJson), ['curated/entrance.jpg', 'curated/detail.png'])
  reject(new Error('测试后端暂时不可用')); await first
  assert.equal(h.vm.game.questionName, '入口出现哪一个字？'); assert.equal(h.vm.saving, false)
  assert.deepEqual(h.events, []); assert.match(h.vm.error, /暂时不可用/)
  const retry = h.vm.save(); resolve({ code: 200 }); await retry
  assert.deepEqual(h.events, ['saved', 'close'])
})

test('official editor requires photos and rejects stale detail responses after closing', async () => {
  let resolve; let writes = 0
  const h = mount({ saveOfficialPoi: () => { writes++ }, officialPoiDraft: () => new Promise(yes => { resolve = yes }) })
  await h.vm.save(); assert.equal(writes, 0); assert.match(h.vm.error, /照片/)
  const loading = h.vm.load(); h.vm.visible = false; h.vm.sequence++
  resolve({ data: { poi: { id: 42, name: '过期资料' } } }); await loading
  assert.equal(h.vm.poi.name, '')
})
