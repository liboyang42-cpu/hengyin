'use strict'
// 1119 验证单 B 道（MC-* / OR-*）源码合同：逐条钉住业主 2026-11-17 批准的小程序改动。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..', '..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('MC-004 / MC-043:俱乐部结算直连账本只给主理人,余额未知不再单摆「—」', () => {
  const wxml = read('pages/club/settlement/index.wxml')
  assert.match(wxml, /<cy-club-direct-ledger class="section" wx:if="\{\{summary && summary\.isOwner && clubId\}\}"/)
  assert.doesNotMatch(wxml, /: '—'\}\}<\/text>/)
})

test('MC-005:俱乐部报名页场次净额改名「实收减退款」', () => {
  const wxml = read('pages/club/enroll/index.wxml')
  assert.match(wxml, /实收减退款 \{\{m\.netText\}\}/)
  assert.doesNotMatch(wxml, /净额 \{\{m\.netText\}\}/)
})

test('MC-015:核销行金额未成立按 MC-011=B 显示 ¥0.00,不再回落「待定」', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /NO_CASH_SETTLEMENT' \? null : '¥0\.00'/)
  assert.doesNotMatch(js, /: '待定'\)/)
})

test('MC-019:台账三张列表 pageNum 递增触底加载', () => {
  const js = read('pages/merchant/ledger/index.js')
  assert.match(js, /onReachBottom\(\) \{/)
  assert.match(js, /const pageNum = this\._pageState\[key\]\.pageNum \+ 1;/)
  assert.match(js, /this\._setPageState\('redemptions', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('entries', 1, res\.data\)/)
  assert.match(js, /this\._setPageState\('batches', 1, res\.data\)/)
  assert.match(read('pages/merchant/ledger/index.wxml'), /wx:if="\{\{loadingMore\}\}"[^>]*>正在加载更多…/)
})
