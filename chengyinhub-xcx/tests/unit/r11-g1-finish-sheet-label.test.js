/* R11 g1(j4 F8):一站没做就收尾时,完成页不说「本章完成」、不摆一个巨大的 0 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.wxml'), 'utf8')
const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8')

test('结果标签:通关 / 本章真完成 / 其余一律「已结束」', () => {
  assert.ok(wxml.includes("{{allDone || topicCompleted ? '已通关' : (finishChapterDone ? '本章完成' : '已结束')}}"))
  assert.ok(!/: '本章完成'\}\}/.test(wxml), '不许再无条件写「本章完成」')
})

test('点亮坐标为 0 时整格不渲染', () => {
  assert.match(wxml, /<view class="fst" wx:if="\{\{doneCount > 0\}\}">[^\n]*点亮坐标/)
})

test('finishChapterDone 按本章节点全部完成计算', () => {
  const start = js.indexOf('const finishChapterId')
  const end = js.indexOf('\n', js.indexOf('const finishChapterDone'))
  assert.ok(start > 0 && end > start)
  // 用页面里同一段语句在样本上跑一遍
  const compute = new Function('data', js.slice(start, end).replace(/this\.data/g, 'data') + '\nreturn finishChapterDone;')
  assert.equal(compute({ chapter: { id: 1 }, nodes: [{ chapterId: 1, done: false }, { chapterId: 2, done: true }] }), false)
  assert.equal(compute({ chapter: { id: 1 }, nodes: [{ chapterId: 1, done: true }, { chapterId: 2, done: false }] }), true)
  assert.equal(compute({ chapter: { id: 1 }, nodes: [] }), false)
  assert.equal(compute({ chapter: null, nodes: [{ chapterId: 1, done: true }] }), false)
})
