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
  h.vm.pictures = 'https://admin.example/profile/upload/2026/10/25/0123456789abcdef0123456789abcdef.jpg,https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/fedcba9876543210fedcba9876543210.png?Expires=1&Signature=x'
  const first = h.vm.save(); await h.vm.save()
  assert.equal(calls.length, 1)
  assert.equal(calls[0].poi.coverImg, '/profile/upload/2026/10/25/0123456789abcdef0123456789abcdef.jpg')
  assert.equal(calls[0].poi.photoRequired, false)
  assert.deepEqual(JSON.parse(calls[0].poi.galleryJson), ['/profile/upload/2026/10/25/0123456789abcdef0123456789abcdef.jpg', 'https://chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/fedcba9876543210fedcba9876543210.png'])
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

test('official editor round-trips labeled public quotes and refuses quotes without source link', async () => {
  const calls = []
  const h = mount({ saveOfficialPoi: payload => { calls.push(payload); return Promise.resolve({ code: 200 }) },
    officialPoiDraft: () => Promise.resolve({ data: { poi: { id: 42, name: '多伦', coverImg: '/profile/upload/2026/10/25/0123456789abcdef0123456789abcdef.jpg', quotesJson: JSON.stringify([{ text: '环境很安静', source: '去哪儿', author: '少年鲍勃', date: '2024-05-07', url: 'https://touch.travel.qunar.com/comment/57019655' }]) } } }) })
  await h.vm.load()
  assert.equal(h.vm.quotes.length, 1); assert.equal(h.vm.quotes[0].author, '少年鲍勃')
  h.vm.pictures = '/profile/upload/2026/10/25/0123456789abcdef0123456789abcdef.jpg'
  h.vm.quotes.push({ text: '没有出处的一句', source: '', author: '', date: '', url: '' })
  await h.vm.save(); assert.equal(calls.length, 0); assert.match(h.vm.error, /来源/)
  h.vm.quotes.pop(); await h.vm.save()
  assert.deepEqual(JSON.parse(calls[0].poi.quotesJson), [{ text: '环境很安静', source: '去哪儿', author: '少年鲍勃', date: '2024-05-07', url: 'https://touch.travel.qunar.com/comment/57019655' }])
})
