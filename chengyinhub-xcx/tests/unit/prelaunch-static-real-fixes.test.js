'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '../..')
const { extractSheetProps } = require('../../scripts/cy-sheet-attr-lint')
const { parseAllowlist } = require('../../../ci/silent-error-gate')
const { findU5Issues } = require('../../scripts/ui-validity-gates')

test('compact runtime properties stay declared while unknown props remain absent', () => {
  const source = fs.readFileSync(path.join(root, 'components/cy/theme-records/index.js'), 'utf8')
  const props = extractSheetProps(source, null, 0)
  assert.deepEqual(props, ['title', 'historyOpen', 'rows', 'more', 'busy', 'error', 'readFailure', 'cardSession', 'card', 'replayAllowed', 'replayBusy', 'replayUnconfirmed'])
  assert.equal(props.includes('unknown'), false)
  assert.deepEqual(extractSheetProps("Component({properties:{known:{type:String,value:'{'},count:Number}})", null, 0), ['known', 'count'])
  assert.equal(extractSheetProps('Component({properties:{known:{type:String}}})', null, 0).includes('type'), false)
})

test('main-package shared helpers and original subpackage callers use the same implementation', () => {
  assert.equal(require('../../utils/ar-math'), require('../../pages/play/components/playkit-scan/ar/ar-math'))
  assert.equal(require('../../utils/play-run-session'), require('../../pages/play/utils/play-run-session'))
  assert.equal(require('../../utils/play-run-session').pausedRunStorageKey({ memberId: 42, topicId: 7 }), 'play_paused_run_v2:m42:t7')
})

test('shared silent-error registry preserves exact counts and rejects malformed reasons', () => {
  const source = fs.readFileSync(path.join(root, 'scripts/ui-silent-error-allowlist.js'), 'utf8')
  function load(text) {
    const context = { module: { exports: {} }, __dirname: path.join(root, 'scripts'),
      require(name) { return name === 'node:fs' ? { readFileSync: () => text } : name === 'node:path' ? path : { parseAllowlist } } }
    vm.runInNewContext(source, context)
    return context.module.exports
  }
  const allow = load('chengyinhub-xcx/pages/demo/index.js 1 # 页面使用可见错误组件承接失败并提供重试\n')
  const sources = [{ file: 'pages/demo/index.js', source: 'Page({load(){app.sendRequest({silentError:true})}})' }]
  assert.equal(findU5Issues(sources, allow).issues.length, 0)
  assert.match(findU5Issues([{ ...sources[0], source: sources[0].source + ';x({silentError:true})' }], allow).issues.join(), /调用数变化/)
  assert.match(findU5Issues([{ ...sources[0], file: 'pages/unknown/index.js' }], allow).issues.join(), /未进白名单/)
  assert.throws(() => load('chengyinhub-xcx/pages/demo/index.js 1\n'), /没写理由/)
})

test('canonical read failure remains blank while operations and permission errors keep their renderer', () => {
  const filename = path.join(root, 'components/cy/error/index.js')
  let definition, pageReads = 0
  const context = { require: require('node:module').createRequire(filename), Component(value) { definition = value },
    getCurrentPages() { pageReads++; return [{}] } }
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), context)
  function state(loadFailure) {
    return { data: { loadFailure, autoBack: true, _sheet: false }, setData(patch) { Object.assign(this.data, patch) } }
  }
  const read = state(true)
  definition.methods._openAutoBack.call(read)
  assert.equal(read.data._sheet, false)
  assert.equal(pageReads, 0)
  const operation = state(false)
  definition.methods._openAutoBack.call(operation)
  assert.equal(operation.data._sheet, true)
  assert.equal(pageReads, 1)
})
