'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const tick = () => new Promise(resolve => setImmediate(resolve))
function mount() {
  let definition, owner = '7', cropResolve, cropReject
  const saves = [], notices = []
  const app = { globalData: {}, getUserID: () => owner,
    cropAll: () => new Promise((resolve, reject) => { cropResolve = resolve; cropReject = reject }) }
  global.getApp = () => app
  global.Page = d => { definition = d }
  global.wx = { getStorageSync() {}, saveFile: o => saves.push(o), showToast: o => notices.push(o.title) }
  const file = require.resolve('../../pages/roam/index.js')
  delete require.cache[file]; require(file)
  const page = { ...definition, data: JSON.parse(JSON.stringify(definition.data)), _roamSid: '21', _clientSessionKey: 'original',
    setData(patch) { for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = this.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    } } }
  return { page, saves, notices, owner: value => { owner = value }, crop: () => cropResolve(['/tmp/crop.jpg']), reject: () => cropReject(new Error('cancel')) }
}
for (const phase of ['crop', 'save']) for (const change of ['owner', 'session', 'visit', 'unload', 'generation']) {
  test('late photo after ' + change + ' at ' + phase + ' never enters current Page', async () => {
    const h = mount(); h.page._acceptPhoto('/tmp/raw.jpg'); await tick()
    if (phase === 'save') { h.crop(); await tick() }
    if (change === 'owner') h.owner('8')
    if (change === 'session') h.page._clientSessionKey = 'next'
    if (change === 'visit') h.page.data.visit = { active: true, poi: { id: 99 }, photos: [] }
    if (change === 'unload') h.page._unloaded = true
    if (change === 'generation') h.page._roamWriteGeneration = 1
    if (phase === 'crop') h.crop()
    else h.saves[0].success({ savedFilePath: 'wxfile://saved.jpg' })
    await tick()
    assert.equal((h.page._sessionPhotos || []).length, 0)
    assert.equal(h.page.data.visit.photos.length, 0)
    assert.equal(h.notices.length, 0)
    if (phase === 'crop') assert.equal(h.saves.length, 0)
  })
}
test('save exception retains temporary photo and a later retry can persist', async () => {
  const h = mount()
  wx.saveFile = () => { throw new Error('storage unavailable') }
  h.page._acceptPhoto('/tmp/raw.jpg'); await tick(); h.crop(); await tick()
  assert.equal(h.page._sessionPhotos[0].persisted, false)
  assert.equal(h.page._sessionPhotos[0].path, '/tmp/crop.jpg')
  wx.saveFile = o => o.success({ savedFilePath: 'wxfile://saved.jpg' })
  h.page._acceptPhoto('/tmp/raw.jpg'); await tick(); h.crop(); await tick()
  assert.equal(h.page._sessionPhotos.at(-1).persisted, true)
})

test('crop cancellation settles without adding photos or reporting success', async () => {
  const h = mount(); h.page._acceptPhoto('/tmp/raw.jpg'); await tick(); h.reject(); await tick()
  assert.equal(h.saves.length, 0); assert.equal((h.page._sessionPhotos || []).length, 0)
})

test('current visit receives its saved photo while current roam retains captured address', async () => {
  const h = mount()
  h.page.data.visit = { active: true, poi: { id: 31 }, photos: [] }
  h.page._acceptPhoto('/tmp/raw.jpg'); await tick(); h.crop(); await tick()
  h.saves[0].success({ savedFilePath: 'wxfile://visit.jpg' }); await tick()
  assert.equal(h.page.data.visit.photos[0].path, 'wxfile://visit.jpg')
  assert.equal((h.page._sessionPhotos || []).length, 0)
  h.page.data.visit = { active: false, poi: {}, photos: [] }
  h.page.data.currentAddress = '拍摄地点'
  h.page._acceptPhoto('/tmp/raw.jpg'); await tick(); h.crop(); await tick()
  h.page.data.currentAddress = '后来地点'
  h.saves[1].success({ savedFilePath: 'wxfile://roam.jpg' }); await tick()
  assert.equal(h.page._sessionPhotos[0].name, '拍摄地点')
})
