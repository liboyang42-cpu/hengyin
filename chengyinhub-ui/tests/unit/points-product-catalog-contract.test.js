const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

const legacyListSource = read('src/views/business/product/index.vue')
const legacyEditSource = read('src/views/business/product/legacy-edit.vue')
const catalogSource = read('src/views/business/config/points-product/index.vue')
const editorSource = read('src/views/business/product/AddProduct.vue')
const apiSource = read('src/api/business/product.js')
const routerSource = read('src/router/index.js')

function loadCatalog(dependencies) {
  const script = catalogSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listProduct, updateProductPublishStatus \} from '@\/api\/business\/product'$/m, 'const { listProduct, updateProductPublishStatus } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies)
}

function createVm(component) {
  const vm = Object.assign(component.data(), {
    $modal: { confirm: () => Promise.resolve(), msgSuccess: () => {} },
    $router: { push: () => {} },
    $route: { query: {} },
    $refs: { queryForm: { resetFields: () => {} } }
  })
  for (const name of ['getList', 'handleQuery', 'handlePagination', 'resetQuery', 'clearFilter', 'changePublishStatus', 'hydrateQuery']) {
    vm[name] = component.methods[name].bind(vm)
  }
  return vm
}

test('积分商城商品使用规范目录路由，两个旧入口只做兼容跳转', () => {
  assert.match(routerSource, /path:\s*'\/config\/points-product'/)
  assert.match(routerSource, /permissions:\s*\['business:product:list'\]/)
  assert.match(routerSource, /name:\s*'pointsProductCatalog'/)
  assert.match(routerSource, /path:\s*'create'/)
  assert.match(routerSource, /path:\s*':id\/edit'/)
  assert.match(legacyListSource, /\$router\.replace\(/)
  assert.match(legacyListSource, /path:\s*'\/config\/points-product'/)
  assert.match(legacyEditSource, /\$router\.replace\(/)
  assert.match(legacyEditSource, /config\/points-product\/\$\{id\}\/edit/)
  assert.match(catalogSource, /this\.hydrateQuery\(this\.\$route\.query\)/)
  assert.doesNotMatch(legacyListSource, /listProduct|delProduct|<el-table\b/)
})

test('旧商品目录的筛选和分页参数被规范目录接管', () => {
  const component = loadCatalog({
    listProduct: () => Promise.resolve({ rows: [], total: 0 }),
    updateProductPublishStatus: () => Promise.resolve()
  })
  const vm = createVm(component)

  vm.hydrateQuery({ productName: '城市漫游卡', publishStatus: '2', pageNum: '3', pageSize: '50' })

  assert.deepEqual(vm.queryParams, {
    pageNum: 3,
    pageSize: 50,
    productName: '城市漫游卡',
    publishStatus: 2,
    delFlag: 0
  })

  vm.hydrateQuery({ productName: ' ', publishStatus: '9', pageNum: '0', pageSize: '-1' })
  assert.deepEqual(vm.queryParams, {
    pageNum: 1,
    pageSize: 20,
    productName: null,
    publishStatus: null,
    delFlag: 0
  })
})

test('商品目录分页并阻断旧列表请求覆盖新筛选结果', async () => {
  const pending = []
  const component = loadCatalog({
    listProduct: query => new Promise(resolve => pending.push({ query, resolve })),
    updateProductPublishStatus: () => Promise.resolve()
  })
  const vm = createVm(component)

  const first = vm.getList()
  vm.queryParams.productName = '城市漫游卡'
  const second = vm.getList()

  assert.deepEqual(pending[0].query, {
    pageNum: 1,
    pageSize: 20,
    productName: null,
    publishStatus: null,
    delFlag: 0
  })
  assert.equal(pending[1].query.productName, '城市漫游卡')

  pending[0].resolve({ rows: [{ id: 1 }], total: 1 })
  await first
  assert.deepEqual(vm.productList, [])
  assert.equal(vm.loading, true)

  pending[1].resolve({ rows: [{ id: 2 }], total: 1 })
  await second
  assert.deepEqual(vm.productList, [{ id: 2 }])
  assert.equal(vm.total, 1)
  assert.equal(vm.loading, false)
})

test('目录不提供物理删除，状态变更只请求专用无 SKU 接口', async () => {
  const writes = []
  const component = loadCatalog({
    listProduct: () => Promise.resolve({ rows: [], total: 0 }),
    updateProductPublishStatus: payload => {
      writes.push(payload)
      return Promise.resolve()
    }
  })
  const vm = createVm(component)
  vm.getList = () => Promise.resolve()

  await vm.changePublishStatus({ id: 7, productName: '城市漫游卡', publishStatus: 2 }, 0)

  assert.deepEqual(writes, [{ id: 7, publishStatus: 0 }])
  assert.doesNotMatch(catalogSource, /delProduct/)
  assert.match(apiSource, /url:\s*'\/business\/product\/publish-status'/)
  assert.match(apiSource, /method:\s*'put'/)
})

test('商品编辑页把上下架收回目录，并从规范目录返回', () => {
  assert.match(editorSource, /CyPageHeader/)
  assert.match(editorSource, /this\.\$route\.params/)
  assert.match(editorSource, /path:\s*'\/config\/points-product'/)
  assert.doesNotMatch(editorSource, /<DictRadio\b/)
  assert.match(catalogSource, /STATUS_PENDING/)
})

// P8-D7 4.3: 商家下拉曾绑 @change="countryChange"，但该页 methods 里没有这个函数，
// 运营切换商家会抛 "countryChange is not a function"。商家下拉只绑定 merchantId，无联动需求。
test('商品编辑页模板事件都指向已实现的处理函数，不绑未定义的 countryChange', () => {
  const template = editorSource.match(/<template>([\s\S]*?)<\/template>/)[1]
  const handlers = [...template.matchAll(/@(?:change|input)="([A-Za-z_$][\w$]*)"/g)].map(match => match[1])

  assert.ok(handlers.length > 0, '至少要扫到一个模板事件处理函数')
  for (const name of handlers) {
    assert.match(editorSource, new RegExp(`\\b${name}\\s*\\(`), `模板绑定了未实现的处理函数 ${name}`)
  }
  assert.doesNotMatch(editorSource, /countryChange/, '不得再残留生成器模板遗留的 countryChange')
})
