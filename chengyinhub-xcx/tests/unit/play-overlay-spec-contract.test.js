'use strict'

// 游玩页弹窗/全屏形态以 docs/游玩页-弹窗与全屏.md 为准。
// 本测试只钉住「文档还在、源码还是那几类层」,防止导航下方的高弹窗被改成盖住微信导航的整屏层。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('游玩页弹窗与全屏说明存在,且和现行 wxml 同一套层名', () => {
  const doc = read('docs/游玩页-弹窗与全屏.md')
  const wxml = read('pages/play/index.wxml')
  const wxss = read('pages/play/index.wxss')

  assert.match(doc, /gp2--sheet/)
  assert.match(doc, /finsheet/)
  assert.match(doc, /导航下方全高/)

  // 全屏
  // 2026-09-10 'journal' 从全屏名单里撤出:旅程手记按用户裁决照原型 journalScreen
  //   重做成半屏(原型那一屏就是 sheet),不再是 inset:0 的整屏幕布。
  // 2026-09-22 起始页 .play-intro 下线(只有漫游保留开始主题屏),层名单随之去掉它
  for (const token of ['chfull', 'voucher', 'woo', 'ending', 'leadgather', 'cy-story-sheet', 'cy-privacy-gate']) {
    assert.match(wxml, new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `全屏层缺失: ${token}`)
    assert.match(doc, new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `文档未写全屏层: ${token}`)
  }

  // 半屏
  for (const token of ['class="sheet"', 'gp2--sheet', 'finsheet', 'chpick', 'cy-advanced-game', 'cy-playkit']) {
    assert.match(wxml, new RegExp(token.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), `半屏层缺失: ${token}`)
  }

  // 长内容占满导航下方的可用高度，三类页内弹层使用同一个实测上限。
  for (const selector of ['gp2', 'finsheet', 'sheet']) {
    const block = wxss.match(new RegExp(`\\.${selector}\\s*\\{([^}]+)\\}`))[1]
    assert.match(block, /height:\s*var\(--cy-psheet-max-h,\s*var\(--cy-comp-sheet-max-height\)\)/)
    assert.match(block, /max-height:\s*var\(--cy-psheet-max-h,\s*var\(--cy-comp-sheet-max-height\)\)/)
    assert.doesNotMatch(block, /(?:height|max-height):\s*(?:86vh|88%)/)
  }
  // 手记仍复用本页弹层，不能另开 inset:0 的整屏幕布。
  assert.doesNotMatch(wxss, /\.journal\{/)
  assert.match(wxss, /\.woo\{[\s\S]*?inset:0/)
  assert.match(wxss, /\.voucher\{[\s\S]*?inset:0/)
  assert.match(wxss, /\.chfull\{[\s\S]*?inset:0/)
})

test('已删的起飞过场/任务启动页/全屏暂停罩/卡堆半屏不得回潮', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /起飞过场[\s\S]*已按用户裁决删除/)
  assert.match(wxml, /任务启动页\(p02\)已删除/)
  assert.match(wxml, /卡堆半屏已删除/)
  assert.match(wxml, /不再另起一层全屏罩/)
  assert.doesNotMatch(wxml, /class="cy-play-pause"/)
  assert.doesNotMatch(wxml, /screen=='gameStart'/)
})
