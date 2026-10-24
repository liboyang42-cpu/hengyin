'use strict'
// C2 举报队伍:队伍详情是主包页,举报 sheet 放在 subpackageMember 并经 componentPlaceholder 分包异步化,主包不新增组件文件
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const SHEET = 'subpackageMember/components/cy/team-report-sheet/index'

test('C2: 举报队伍请求体与入口', () => {
  const src = read(SHEET + '.js')
  assert.match(src, /url: '\/api\/team\/report'/)
  assert.match(src, /JSON\.stringify\(\{ teamId, reason \}\)/)
  assert.match(read('pages/team/detail/index.wxml'), /bindtap="openReport"/)
  assert.match(read('pages/team/detail/index.wxml'), /<team-report-sheet show="\{\{reportShow\}\}" team-id="\{\{reportTeamId\}\}"/)
})

test('C2: 举报回调必须是箭头函数（方法简写里的 this 不是组件）', () => {
  const src = read(SHEET + '.js')
  assert.match(src, /success: \(res\) => \{/)
  assert.doesNotMatch(src, /\bsuccess\(res\) \{/)
})

test('C2: 举报 sheet 不进主包——分包组件 + componentPlaceholder', () => {
  const page = JSON.parse(read('pages/team/detail/index.json'))
  assert.equal(page.usingComponents['team-report-sheet'], '/' + SHEET)
  assert.equal(page.componentPlaceholder['team-report-sheet'], 'view')
  const app = JSON.parse(read('app.json'))
  assert.ok(app.subPackages.some((p) => p.root === 'subpackageMember'))
  assert.equal(fs.existsSync(path.join(root, 'pages/team/report-sheet')), false)
})

test('C2: 举报 sheet 失败落内联可见态（不是只 toast）', () => {
  const src = read(SHEET + '.js')
  const wxml = read(SHEET + '.wxml')
  assert.match(src, /stage: 'fail', failedText:/)
  assert.match(wxml, /stage === 'fail'/)
  assert.match(wxml, /why="\{\{failedText\}\}"/)
})
