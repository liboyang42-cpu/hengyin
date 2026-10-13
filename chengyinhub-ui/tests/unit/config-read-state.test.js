const test = require('node:test')
const assert = require('node:assert/strict')
const loadPage = require('./helpers/vue-options.cjs')

for (const [path, api, field] of [
  ['business/world/index.vue', 'listWorlds', 'worldList'],
  ['business/preset/route/index.vue', 'listPresetRoute', 'routeList'],
  ['business/preset/style/index.vue', 'listPresetStyle', 'styleList']
]) {
  test(`${field}: 拒绝和畸形数据明确失败，重读空结果恢复`, async () => {
    let result = null
    const { page, source } = loadPage(path, { [api]: async () => { if (result === null) throw Error('拒绝'); return result } })
    await page.getList()
    assert.equal(page.loadError, true)
    assert.equal(page.loading, false)
    result = {}; await page.getList(); assert.equal(page.loadError, true)
    result = { rows: [], total: 0 }; await page.getList()
    assert.equal(page.loadError, false)
    assert.equal(page[field].length, 0)
    assert.match(source, /版本/)
    assert.match(source, /尚未接/)
  })
}

for (const [path, api, field] of [
  ['system/category/index.vue', 'listCategory', 'categoryList'],
  ['system/dict/data.vue', 'listData', 'dataList'],
  ['system/banner/index.vue', 'listBanner', 'tbannerList'],
  ['system/notice/index.vue', 'listNotice', 'noticeList']
]) {
  test(`${field}: 失败不是空配置，重读真实记录后恢复`, async () => {
    let failed = true
    const { page, source } = loadPage(path, { [api]: async () => { if (failed) throw Error('拒绝'); return { rows: [{ id: 1 }], total: 1 } } })
    await page.getList()
    assert.equal(page.loadError, true)
    assert.equal(page.loading, false)
    failed = false; await page.getList()
    assert.equal(page.loadError, false)
    assert.equal(page[field][0].id, 1)
    assert.match(source, /尚未/)
  })
}

test('编辑隐藏类别不会默默改成显示', () => {
  const { page } = loadPage('system/category/index.vue')
  page.reset = () => {}; page.$nextTick = () => {}
  page.handleUpdate({ id: 1, status: 0 })
  assert.equal(page.form.status, 0)
})

test('字典类型读取失败可从原类型编号恢复，避免无限加载', async () => {
  let failed = true
  const { page } = loadPage('system/dict/data.vue', {
    getType: async () => { if (failed) throw Error('offline'); return { data: { dictType: 'sys_normal_disable' } } },
    listData: async () => ({ rows: [], total: 0 })
  })
  page.$route = { params: { dictId: 1 } }
  await page.getType(1)
  assert.equal(page.loadError, true)
  assert.equal(page.loading, false)
  failed = false; await page.retryList()
  assert.equal(page.queryParams.dictType, 'sys_normal_disable')
  assert.equal(page.loadError, false)
})
