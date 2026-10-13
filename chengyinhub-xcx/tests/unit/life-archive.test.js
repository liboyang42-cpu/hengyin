const test = require('node:test')
const assert = require('node:assert/strict')
const archive = require('../../utils/life-archive.js')

const nodes = [
  { name: '上海城市规划展示馆', done: true, lat: 31.23, lng: 121.47 },
  { name: '新生儿登记', done: true, lat: 0, lng: 0, previewKit: { type: 'profile', done: true, answers: { name: '阿猫', job: 'F' },
    questions: [{ key: 'name', label: '你叫什么' }, { key: 'job', label: '职业', options: [{ key: 'F', label: '会计' }] }] } },
  { name: '拍招牌', done: true, previewKit: { type: 'photocheck', title: '拍下招牌', lastUrl: 'wxfile://a.jpg' } },
  { name: '留句话', done: true, previewKit: { type: 'note', title: '留句话', mine: '天是灰的' } },
  { name: '人民广场地铁站', done: false, lat: 31.23, lng: 121.47 },
]

test('试玩档案:建档答案回写选项文案、照片、留言、只列走到过的有坐标站点', () => {
  const a = archive.fromPreview(nodes)
  assert.deepEqual(a.identity, [{ label: '你叫什么', value: '阿猫' }, { label: '职业', value: '会计' }])
  assert.deepEqual(a.photos, [{ url: 'wxfile://a.jpg', title: '拍下招牌' }])
  assert.deepEqual(a.notes, [{ title: '留句话', text: '天是灰的' }])
  assert.deepEqual(a.stations, ['上海城市规划展示馆'])
  assert.equal(archive.isEmpty(a), false)
})

test('真玩家档案:服务端给什么用什么,空值行丢掉;全空时 isEmpty', () => {
  const a = archive.fromServer({ identity: [{ label: '名字', value: '' }], photos: [], notes: [] }, [])
  assert.equal(archive.isEmpty(a), true)
})
