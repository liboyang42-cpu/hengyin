'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')
const { resolveBase } = require('../../scripts/ui-validity-gates')

test('U3/U4 缺少远端基线时 fail-closed 且绝不探测 HEAD^', () => {
  const calls = []
  const missingRefs = (_command, args) => {
    calls.push(args.join(' '))
    throw new Error('missing ref')
  }

  assert.throws(() => resolveBase('/tmp/shallow-clone', missingRefs), /无可用基线/)
  assert.equal(calls.some((call) => call.includes('HEAD^')), false)
  assert.deepEqual(calls, [
    'rev-parse --verify github/master^{commit}',
    'rev-parse --verify origin/master^{commit}',
  ])
})

test('U3/U4 只采用远端 ref 与 HEAD 的 merge-base', () => {
  const fakeGit = (_command, args) => {
    if (args[0] === 'rev-parse' && args[2] === 'github/master^{commit}') return Buffer.from('ok')
    if (args[0] === 'merge-base') return 'abc123\n'
    throw new Error('unexpected command')
  }

  assert.equal(resolveBase('/tmp/full-clone', fakeGit), 'abc123')
})

const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const childProcess = require('node:child_process')
const {
  baselineRenamePaths, gitShow, incrementalUiIssues, findU3Names, findU4Fields,
} = require('../../scripts/ui-validity-gates')

function fixture(t, move = true) {
  const repoRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-ui-rename-'))
  t.after(() => fs.rmSync(repoRoot, { recursive: true, force: true }))
  const xcxRoot = path.join(repoRoot, 'chengyinhub-xcx')
  const oldDir = path.join(xcxRoot, 'components/cy/card')
  const sharedDir = path.join(xcxRoot, 'components/cy/shared')
  fs.mkdirSync(oldDir, { recursive: true })
  fs.mkdirSync(sharedDir, { recursive: true })
  const oldJs = path.join(oldDir, 'index.js')
  const oldWxml = path.join(oldDir, 'index.wxml')
  const stableJs = Array.from({ length: 30 }, (_, i) => `const stable${i} = ${i};`).join('\n')
  const stableWxml = Array.from({ length: 30 }, (_, i) => `<view>stable ${i}</view>`).join('\n')
  fs.writeFileSync(oldJs, `Page({ goOld() {}, goBound() {}, load() { this.setData({ legacyDead: true }) } });\n${stableJs}\n`)
  fs.writeFileSync(oldWxml, `<include src="../shared/part.wxml" />\n${stableWxml}\n`)
  fs.writeFileSync(path.join(sharedDir, 'part.wxml'), '<button bindtap="goBound">bound</button>')
  const git = (...args) => childProcess.execFileSync('git', args, { cwd: repoRoot, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  git('init', '-q')
  git('add', '.')
  git('-c', 'user.name=fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'baseline')
  const base = git('rev-parse', 'HEAD').trim()
  let currentDir = oldDir
  if (move) {
    currentDir = path.join(xcxRoot, 'subpackageMember/components/cy/card')
    fs.mkdirSync(path.dirname(currentDir), { recursive: true })
    fs.renameSync(oldDir, currentDir)
    fs.writeFileSync(path.join(currentDir, 'index.wxml'), `<include src="../../../../components/cy/shared/part.wxml" />\n${stableWxml}\n`)
    git('add', '-A')
  }
  return { repoRoot, xcxRoot, base, oldJs, oldWxml, currentDir, currentJs: path.join(currentDir, 'index.js'), git, options: { repoRoot, xcxRoot } }
}

test('Git真实rename继承旧U3/U4债务，旧目录include仍提供绑定', (t) => {
  const f = fixture(t)
  const rename = baselineRenamePaths(f.base, undefined, f.repoRoot)
  assert.equal(rename.get('chengyinhub-xcx/subpackageMember/components/cy/card/index.js'), 'chengyinhub-xcx/components/cy/card/index.js')
  assert.deepEqual(incrementalUiIssues([f.currentJs], f.base, findU3Names, f.options), [])
  assert.deepEqual(incrementalUiIssues([f.currentJs], f.base, findU4Fields, f.options), [])
})

test('真实rename后新增孤儿handler和死字段仍红，不能借迁移豁免', (t) => {
  const f = fixture(t)
  const source = fs.readFileSync(f.currentJs, 'utf8').replace('goOld() {}', 'goOld() {}, goFresh() {}')
    .replace('legacyDead: true', 'legacyDead: true, freshDead: true')
  fs.writeFileSync(f.currentJs, source)
  f.git('add', '-A')
  assert.deepEqual(incrementalUiIssues([f.currentJs], f.base, findU3Names, f.options).map(x => x.name), ['goFresh'])
  assert.deepEqual(incrementalUiIssues([f.currentJs], f.base, findU4Fields, f.options).map(x => x.name), ['freshDead'])
})

test('迁移后删除原绑定仍红，基线include不能按新目录错读', (t) => {
  const f = fixture(t)
  const wxml = path.join(f.currentDir, 'index.wxml')
  fs.writeFileSync(wxml, fs.readFileSync(wxml, 'utf8').replace(/<include[^>]*>/, '<view>binding removed</view>'))
  f.git('add', '-A')
  assert.deepEqual(incrementalUiIssues([f.currentJs], f.base, findU3Names, f.options).map(x => x.name), ['goBound'])
})

test('真正新增文件含handler与deadfield必须全扫判红', (t) => {
  const f = fixture(t)
  const dir = path.join(f.xcxRoot, 'pages/fresh')
  fs.mkdirSync(dir, { recursive: true })
  const file = path.join(dir, 'index.js')
  fs.writeFileSync(file, 'Page({ goFresh() {}, load() { this.setData({ freshDead: true }) } })')
  fs.writeFileSync(path.join(dir, 'index.wxml'), '<view>fresh</view>')
  assert.deepEqual(incrementalUiIssues([file], f.base, findU3Names, f.options).map(x => x.name), ['goFresh'])
  assert.deepEqual(incrementalUiIssues([file], f.base, findU4Fields, f.options).map(x => x.name), ['freshDead'])
})

test('复制存量文件不是rename，原债务不能替新文件免检', (t) => {
  const f = fixture(t, false)
  const dir = path.join(f.xcxRoot, 'components/cy/copy')
  fs.mkdirSync(dir, { recursive: true })
  const file = path.join(dir, 'index.js')
  fs.copyFileSync(f.oldJs, file)
  fs.copyFileSync(f.oldWxml, path.join(dir, 'index.wxml'))
  f.git('add', '-A')
  assert.equal(baselineRenamePaths(f.base, undefined, f.repoRoot).has('chengyinhub-xcx/components/cy/copy/index.js'), false)
  assert.deepEqual(incrementalUiIssues([file], f.base, findU3Names, f.options).map(x => x.name), ['goOld'])
  assert.deepEqual(incrementalUiIssues([file], f.base, findU4Fields, f.options).map(x => x.name), ['legacyDead'])
})

test('真实rename旧基线对象读取失败必须抛错，不降级新增文件', (t) => {
  const f = fixture(t)
  const failingRead = (command, args, options) => {
    if (args[0] === 'show' && args[1] === `${f.base}:chengyinhub-xcx/components/cy/card/index.js`) throw new Error('fixture missing blob')
    return childProcess.execFileSync(command, args, options)
  }
  assert.throws(() => gitShow(f.base, 'chengyinhub-xcx/subpackageMember/components/cy/card/index.js', failingRead, f.options), /拒绝静默降级/)
})

test('rename清单读取失败或格式损坏必须抛错，不吞成空映射', () => {
  assert.throws(() => baselineRenamePaths('base', () => { throw new Error('diff failed') }), /拒绝静默降级/)
  assert.throws(() => baselineRenamePaths('base', () => 'R100\0old\0'), /格式错误/)
})
