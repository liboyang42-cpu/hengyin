'use strict'
// x-design1 D13:结算页没有海报时用统一的主题兜底图,不写「暂无海报」
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const read = (p) => fs.readFileSync(path.resolve(__dirname, '../..', p), 'utf8')

test('D13:baoming 结算页无海报分支用 cy-cover-fb,没有文字', () => {
  const wxml = read('pages/activity/baoming/baoming.wxml')
  assert.doesNotMatch(wxml, /暂无海报/)
  assert.match(wxml, /<view wx:else class="activity-poster-empty cy-cover-fb"[^>]*><image class="cy-cover-fb__art" src="\/images\/cover-route\.svg"/)
  assert.doesNotMatch(read('pages/activity/baoming/baoming.wxss'), /activity-poster-empty \{[^}]*background/)
})

test('D13:全仓 wxml 不再出现「暂无海报」', () => {
  const walk = (d, o = []) => { for (const n of fs.readdirSync(path.resolve(__dirname, '../..', d), { withFileTypes: true })) { if (n.name === 'node_modules' || n.name === 'tests') continue; const r = d + '/' + n.name; n.isDirectory() ? walk(r, o) : r.endsWith('.wxml') && o.push(r) } return o }
  assert.deepEqual(walk('.').filter((f) => /暂无海报/.test(read(f).replace(/<!--[\s\S]*?-->/g, ''))), [])
})
