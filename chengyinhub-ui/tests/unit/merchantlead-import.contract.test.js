const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/merchantlead/index.vue'), 'utf8')

function loadComponent() {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listMerchantLead, getMerchantLead, addMerchantLead, updateMerchantLead, delMerchantLead \} from .+$/m,
      'const { listMerchantLead, getMerchantLead, addMerchantLead, updateMerchantLead, delMerchantLead } = dependencies')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {}')
    .replace(/^import \{ getToken \} from .+$/m, 'const { getToken } = dependencies')
  const dependencies = {
    listMerchantLead: () => Promise.resolve({ rows: [], total: 0 }),
    getMerchantLead: () => Promise.resolve({ data: {} }),
    addMerchantLead: () => Promise.resolve(),
    updateMerchantLead: () => Promise.resolve(),
    delMerchantLead: () => Promise.resolve(),
    getToken: () => 'test-token'
  }
  return new Function('dependencies', 'process', script.replace(/export\s+default\s*/, 'return '))(
    dependencies, { env: { VUE_APP_BASE_API: '/api-root' } }
  )
}

function createVm(component) {
  const messages = []
  let cleared = 0
  let submitted = 0
  const vm = Object.assign(component.data(), {
    $refs: { upload: { clearFiles: () => { cleared++ }, submit: () => { submitted++ } } },
    $modal: {
      msgError: message => messages.push({ type: 'error', message }),
      msgWarning: message => messages.push({ type: 'warning', message }),
      msgSuccess: message => messages.push({ type: 'success', message })
    },
    $nextTick: callback => callback(),
    getListCalls: 0,
    getList() { this.getListCalls++; return Promise.resolve() }
  })
  for (const name of ['handleImport', 'handleFileSuccess', 'handleFileError', 'handleFileUploadProgress',
    'submitFileForm', 'handleImportResultPagination']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return { vm, messages, cleared: () => cleared, submitted: () => submitted }
}

test('部分成功回包保留每条失败的物理行号和原因，并刷新已导入数据', () => {
  const component = loadComponent()
  const { vm, messages, cleared } = createVm(component)
  vm.upload.open = true
  vm.upload.isUploading = true

  vm.handleFileSuccess({
    code: 200,
    data: {
      totalCount: 3,
      successCount: 1,
      failureCount: 2,
      failures: [
        { rowNumber: 4, reason: '商家名称不能为空' },
        { rowNumber: 8, reason: '经度必须在范围内' }
      ]
    }
  })

  assert.equal(vm.upload.open, false)
  assert.equal(vm.upload.isUploading, false)
  assert.equal(vm.importResultOpen, true)
  assert.deepEqual(vm.importResult.failures.map(item => [item.rowNumber, item.reason]), [
    [4, '商家名称不能为空'],
    [8, '经度必须在范围内']
  ])
  assert.equal(component.computed.pagedImportFailures.call(vm).length, 2)
  assert.equal(vm.getListCalls, 1)
  assert.equal(cleared(), 1)
  assert.equal(messages.at(-1).type, 'warning')
})

test('业务错误和传输错误都会解除上传锁并允许原对话框重试', () => {
  const component = loadComponent()
  const { vm, messages, cleared } = createVm(component)
  vm.upload.open = true
  vm.upload.isUploading = true

  vm.handleFileSuccess({ code: 500, msg: '文件表头错误' })
  assert.equal(vm.upload.isUploading, false)
  assert.equal(vm.upload.open, true)
  assert.equal(messages.at(-1).type, 'error')

  vm.upload.isUploading = true
  vm.handleFileError()
  assert.equal(vm.upload.isUploading, false)
  assert.equal(vm.upload.open, true)
  assert.equal(cleared(), 2)
  assert.equal(messages.at(-1).type, 'error')
})

test('重新打开导入会清空旧失败结果，提交动作只触发一次上传', () => {
  const component = loadComponent()
  const { vm, cleared, submitted } = createVm(component)
  vm.importResult = { totalCount: 1, successCount: 0, failureCount: 1, failures: [{ rowNumber: 2, reason: '旧错误' }] }

  vm.handleImport()
  vm.submitFileForm()

  assert.equal(vm.upload.open, true)
  assert.equal(vm.importResult.failureCount, 0)
  assert.deepEqual(vm.importResult.failures, [])
  assert.equal(cleared(), 1)
  assert.equal(submitted(), 1)
})
