'use strict'

// 打磨第 3 轮:商家结算/营销/确认终价/商家报名的静态按压统一走 cy-pressed(原 .6/.7/.72/.88/.9/.92 与 scale .99/.97)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const PAGES = ['pages/merchant/ledger/index', 'pages/merchant/marketing/index', 'pages/topic/pricing/index', 'pages/topic/merchantapply/index']

for (const page of PAGES) {
  test(`${page}:静态 hover-class 只用 cy-pressed`, () => {
    const wxml = fs.readFileSync(path.join(ROOT, page + '.wxml'), 'utf8')
    const values = [...wxml.matchAll(/hover-class="([^"{]*)"/g)].map((m) => m[1])
    assert.ok(values.length > 0)
    assert.deepEqual(values.filter((v) => v !== 'cy-pressed'), [])
  })
}
