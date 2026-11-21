'use strict'
// 站点 / 站点玩法配图兜底链(utils/station-image.js,2026-11-21)。
const test = require('node:test')
const assert = require('node:assert/strict')
const si = require('../../utils/station-image.js')

const base = { stationImg: 'https://x/s.jpg', chapterImgArr: 'https://x/c1.jpg,https://x/c2.jpg', topicCover: 'https://x/t.jpg' }

test('站点链:站点图 → 章节首图 → 路线封面 → 纹理底', () => {
  assert.deepEqual(si.resolveStationImage(base), { src: 'https://x/s.jpg', source: 'station' })
  assert.deepEqual(si.resolveStationImage({ ...base, stationImg: '' }), { src: 'https://x/c1.jpg', source: 'chapter' })
  assert.deepEqual(si.resolveStationImage({ ...base, stationImg: null, chapterImgArr: null }), { src: 'https://x/t.jpg', source: 'route' })
  assert.deepEqual(si.resolveStationImage({}), { src: '', source: 'texture' })
  assert.deepEqual(si.resolveStationImage(null), { src: '', source: 'texture' })
})

test('玩法链:玩法封面 → 同一条站点链', () => {
  assert.deepEqual(si.resolveGameImage({ ...base, gameCover: 'https://x/g.jpg' }), { src: 'https://x/g.jpg', source: 'game' })
  assert.deepEqual(si.resolveGameImage({ ...base, gameCover: '' }), { src: 'https://x/s.jpg', source: 'station' })
  assert.deepEqual(si.resolveGameImage({ gameCover: null, chapterImgArr: '["https://x/c.jpg"]' }), { src: 'https://x/c.jpg', source: 'chapter' })
  assert.deepEqual(si.resolveGameImage({ topicCover: 'https://x/t.jpg' }), { src: 'https://x/t.jpg', source: 'route' })
  assert.deepEqual(si.resolveGameImage({}), { src: '', source: 'texture' })
})

test('img_arr 三种形态:逗号 / 分号串、JSON 数组串、数组', () => {
  assert.deepEqual(si.parseImageList('a.jpg, b.jpg;c.jpg'), ['a.jpg', 'b.jpg', 'c.jpg'])
  assert.deepEqual(si.parseImageList('["a.jpg","b.jpg"]'), ['a.jpg', 'b.jpg'])
  assert.deepEqual(si.parseImageList(['a.jpg', 'b.jpg,c.jpg']), ['a.jpg', 'b.jpg', 'c.jpg'])
})

test('坏 img_arr 不抛、不产出假地址,退到下一级', () => {
  for (const bad of ['', '   ', ',,;', '[]', '[', '["a.jpg",', 'null', 42, {}, [null, 3, ''], true]) {
    assert.doesNotThrow(() => si.parseImageList(bad))
  }
  assert.deepEqual(si.parseImageList('["a.jpg",'), ['a.jpg'])
  assert.deepEqual(si.parseImageList('[]'), [])
  assert.deepEqual(si.parseImageList('null'), [])
  assert.deepEqual(si.parseImageList({ url: 'a.jpg' }), [])
  assert.deepEqual(si.resolveStationImage({ chapterImgArr: '[]', topicCover: 'https://x/t.jpg' }), { src: 'https://x/t.jpg', source: 'route' })
  assert.deepEqual(si.resolveStationImage({ chapterImgArr: { a: 1 } }), { src: '', source: 'texture' })
})

test('chapterImagesById:chapterId / id 都认,候选按 img_arr → cover → 故事块首图排,非数组返回空表', () => {
  assert.deepEqual(si.chapterImagesById([{ chapterId: 1, imgArr: 'a.jpg' }, { id: 2, cover: 'b.jpg' }, null, { imgArr: 'x' }]), { 1: ['a.jpg'], 2: ['b.jpg'] })
  assert.deepEqual(si.chapterImagesById([{ chapterId: 3, imgArr: 'media-asset:8', cover: 'c.jpg', blocks: [{ type: 'image', url: 'd.jpg' }] }]), { 3: ['media-asset:8', 'c.jpg', 'd.jpg'] })
  assert.equal(si.resolveStationImage({ chapterImgArr: si.chapterImagesById([{ chapterId: 3, imgArr: 'media-asset:8', cover: 'c.jpg' }])[3] }).src, 'c.jpg')
  assert.deepEqual(si.chapterImagesById(null), {})
  assert.deepEqual(si.chapterImagesById('x'), {})
})
