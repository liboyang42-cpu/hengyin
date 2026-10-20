const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'components/cy/scene-roam-stamp-album/index.js')

function loadAlbum() {
  const sandbox = { requests: [], previews: [] }
  global.getApp = () => ({ sendRequest: (options) => sandbox.requests.push(options) })
  global.wx = { previewImage: (o) => sandbox.previews.push(o) }
  global.Behavior = (def) => def
  global.Component = (def) => { sandbox.def = def }
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const comp = Object.assign({}, sandbox.def.methods, {
    data: JSON.parse(JSON.stringify(sandbox.def.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() },
    triggerEvent() {},
  })
  return { comp, sandbox }
}

test('BL-05: 收到的邮票详情只用接口已有字段，作者匿名不编造', () => {
  const { comp, sandbox } = loadAlbum()
  comp.data.tab = 'received'
  comp.load()
  sandbox.requests[0].success({ code: 200, data: { total: 1, list: [
    { id: 9, picUrl: ' https://img/9.jpg ', caption: '桥下那只猫', kind: 1, createTime: '2026-10-01 08:05:00', memberId: 42 },
  ] } })
  comp.openDetail({ currentTarget: { dataset: { index: 0 } } })
  assert.deepEqual(comp.data.detail, {
    picUrl: 'https://img/9.jpg', caption: '桥下那只猫', kindLabel: '盖销票', timeText: '2026.10.01 08:05',
  })
  comp.previewDetail()
  assert.deepEqual(sandbox.previews[0], { current: 'https://img/9.jpg', urls: ['https://img/9.jpg'] })
  comp.closeDetail()
  assert.equal(comp.data.detail, null)
})

test('BL-05: 「我的」tab 不开详情；未知 kind/时间不显示对应行', () => {
  const { comp } = loadAlbum()
  comp.data.items = [{ id: 1, picUrl: 'a', caption: '', kind: 7, createTime: null }]
  comp.openDetail({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(comp.data.detail, null)
  comp.data.tab = 'received'
  comp.openDetail({ currentTarget: { dataset: { index: 0 } } })
  assert.equal(comp.data.detail.kindLabel, '')
  assert.equal(comp.data.detail.timeText, '')
})

test('BL-05: 详情用 cy-sheet（标题+关闭），只有收到的格子可点', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/scene-roam-stamp-album/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-sheet show="\{\{!!detail\}\}" title="收到的邮票"[^>]*closable[^>]*bind:close="closeDetail"/)
  assert.match(wxml, /wx:if="\{\{tab === 'received'\}\}"[^>]*bindtap="openDetail"/)
  assert.doesNotMatch(wxml, /memberId|nickName/)
})
