'use strict'
// BL-41 漫游护照「官方活动」大卡显示可参与的漫游官方活动(/api/official/active-for-roam)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const { activeRoamEventCard, applyEventCard } = require('../../utils/roam-official-active.js')

test('BL-41: 只用 id/title/coverImg，空/坏数据不改卡片', () => {
  assert.equal(activeRoamEventCard(null), null)
  assert.equal(activeRoamEventCard({}), null)
  assert.equal(activeRoamEventCard([{ id: 1, title: ' ' }, { title: 'x' }]), null)
  assert.deepEqual(activeRoamEventCard([{ id: 7, title: '外滩夜游', coverImg: 'https://a/b.jpg' }]),
    { sub: '可参与：外滩夜游', cover: 'https://a/b.jpg', eventId: '7' })
  assert.deepEqual(activeRoamEventCard([{ id: 7, title: 'A', coverImg: null }, { id: 8, title: 'B' }]),
    { sub: '可参与：A 等 2 场', cover: '', eventId: '' })
})

test('BL-41: 只改 event 大卡，无封面保留原值', () => {
  const plays = [{ key: 'event', title: '官方活动', sub: '限时任务与奖励', cover: '' }, { key: 'discover', sub: 'x', cover: '' }]
  const next = applyEventCard(plays, { sub: '可参与：A', cover: '', eventId: '' })
  assert.deepEqual(next[0], { key: 'event', title: '官方活动', sub: '可参与：A', cover: '' })
  assert.equal(next[1], plays[1])
  assert.equal(applyEventCard(plays, null), plays)
})

test('BL-41: 页面接线——护照 tab 拉取、失败静默、单场直达详情', () => {
  const js = read('pages/roam/index.js')
  assert.match(js, /if \(introTab === 'passport'\) \{ this\._loadIntroPassport\(\); this\._loadActiveRoamEvents\(\); \}/)
  assert.match(js, /req\('\/api\/official\/active-for-roam', 'GET', \{\}, \{ silentError: true \}\)/)
  assert.match(js, /if \(this\._activeRoamToken !== token \|\| !apiOk\(res\)\) return;/)
  assert.match(js, /this\.setData\(\{ 'passport\.plays': applyEventCard\(passport\.plays, card\) \}\);/)
  assert.match(js, /if \(key === 'event' && this\._activeRoamEventId\) wx\.navigateTo\(\{ url: '\/pages\/activity\/official-detail\/index\?id=' \+ this\._activeRoamEventId \}\);/)
})
