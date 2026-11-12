'use strict'
// R4-1:wcsc 把声明值内注释里的分号当声明结束,整页样式编译失败;CI 不跑 wcsc,故静态拦截。
const test = require('node:test')
const assert = require('node:assert')
const path = require('path')
const lint = require('../../scripts/wxss-comment-in-value-lint')

test('selftest:R4-1 夹具(值内注释含分号)判红,正常注释判绿', () => {
  assert.strictEqual(lint.findCommentsInValue(lint.FIXTURE_BAD).length, 1)
  assert.strictEqual(lint.findCommentsInValue(lint.FIXTURE_OK).length, 0)
  assert.ok(lint.selftest())
})

test('全仓 .wxss 无「声明值内注释含分号」', () => {
  const bad = lint.scanAll(path.resolve(__dirname, '../..'))
  assert.deepStrictEqual(bad, [])
})
