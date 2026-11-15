const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

function load(file, api) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/roampoi/' + file), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ ([^}]+) \} from '@\/api\/business\/seedContent'$/m, 'const { $1 } = api;')
  return { source, component: new Function('api', script.replace('export default', 'return'))(api) }
}

function mount(component, props) {
  const vm = { ...component.data(), ...props, $emit() {}, $modal: { msgSuccess() {} }, $refs: {} }
  for (const [name, getter] of Object.entries(component.computed || {})) Object.defineProperty(vm, name, { get: getter.bind(vm) })
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm)
  return vm
}

test('bulk import previews first and only commits a clean preview of the same file', async () => {
  const calls = []
  const report = (dryRun, errors) => ({ data: { dryRun, committed: !dryRun, total: 2, newCount: 1, duplicateCount: 1, errorCount: errors, rows: [] } })
  let errors = 1
  const { component } = load('seed-import-dialog.vue', {
    importOfficialPlaces: (file, params) => { calls.push(params); return Promise.resolve(report(params.dryRun, params.dryRun ? errors : 0)) },
    importOfficialRoutes: () => assert.fail('wrong endpoint')
  })
  const vm = mount(component, { visible: true, kind: 'places' })
  const file = { name: 'p.csv' }
  vm.pick({ target: { files: [file] } })
  vm.cityCode = ' 330100 '
  await vm.preview()
  assert.deepEqual(calls[0], { dryRun: true, cityCode: '330100', dedupeMeters: 100 })
  assert.equal(vm.canCommit, false, 'rows with errors block the import')
  await vm.commit()
  assert.equal(calls.length, 1)
  errors = 0
  await vm.preview()
  assert.equal(vm.canCommit, true)
  vm.pick({ target: { files: [{ name: 'other.csv' }] } })
  assert.equal(vm.canCommit, false, 'a new file needs its own preview')
  vm.pick({ target: { files: [file] } })
  await vm.preview()
  await vm.commit()
  assert.equal(calls.at(-1).dryRun, false)
  assert.equal(vm.report.committed, true)
  assert.equal(vm.canCommit, false, 'cannot commit the same file twice')
})

test('route import uses the route endpoint without place-only fields', async () => {
  const calls = []
  const { component } = load('seed-import-dialog.vue', {
    importOfficialRoutes: (file, params) => { calls.push(params); return Promise.resolve({ data: { total: 0, newCount: 0, errorCount: 0, rows: [] } }) },
    importOfficialPlaces: () => assert.fail('wrong endpoint')
  })
  const vm = mount(component, { visible: true, kind: 'routes' })
  vm.pick({ target: { files: [{ name: 'r.csv' }] } })
  await vm.preview()
  assert.deepEqual(calls, [{ dryRun: true }])
  assert.equal(vm.canCommit, false, 'nothing new to import')
})

test('density report sends the chosen area and shows unreadable sources as unreadable, not zero', async () => {
  const sent = []
  const { component, source } = load('density-report-dialog.vue', {
    densityAreas: () => Promise.resolve({ data: [] }),
    densityReport: query => { sent.push(query); return Promise.resolve({ data: { area: {}, metrics: [] } }) }
  })
  const vm = mount(component, { visible: true })
  await vm.loadAreas()
  assert.equal(vm.mode, 'box', 'falls back to a drawn area when no CITY board exists')
  vm.mode = 'polygon'
  vm.polygonText = '30.0,120.0\n30.3，120.0\n30.0 120.45'
  vm.cityKeyword = ' 杭州 '
  await vm.run()
  assert.deepEqual(sent[0], { cityKeyword: '杭州', polygon: [[30, 120], [30.3, 120], [30, 120.45]] })
  vm.polygonText = '30.0;abc'
  await vm.run()
  assert.equal(sent.length, 1)
  assert.match(vm.error, /格式应为/)
  vm.mode = 'board'
  await vm.run()
  assert.match(vm.error, /选择棋盘/)
  assert.equal(vm.stateText({ state: 'UNAVAILABLE' }), '读不到')
  assert.equal(vm.stateText({ state: 'BELOW', target: 5, count: 2 }), '还差 3')
  assert.equal(vm.targetText({ target: 20, targetMax: 30 }), '20–30')
  assert.match(source, /读不到/)
})
