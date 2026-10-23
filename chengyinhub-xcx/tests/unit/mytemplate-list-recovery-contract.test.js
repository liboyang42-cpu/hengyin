'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const MODULE = path.join(ROOT, 'subpackageMember/mytemplate/mytemplate.js')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function loadPage() {
  const requests = []
  let definition
  global.getApp = () => ({
    getUserID: () => '7',
    getAuthorization: () => 'Bearer unit-fixture',
    getRequestErrorMessage: (response, fallback) => (response && (response.msg || response.errMsg)) || fallback,
    getUserRole: () => 'user',
    getUserType: () => 'user',
    sendRequest: (options) => requests.push(options),
  })
  global.Page = (config) => { definition = config }
  global.wx = {
    getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release' } }),
    navigateTo() {},
    showModal() {},
    showToast() {},
    stopPullDownRefresh() {},
    setNavigationBarColor() {}, setBackgroundColor() {},
  }
  delete require.cache[require.resolve(MODULE)]
  require(MODULE)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.assign(this.data, patch)
      if (done) done.call(this)
    },
  })
  return { page, requests }
}

function response(rows, total) {
  return { code: 200, data: { rows, total } }
}

test('加载更多失败保留现有玩法与页码，重试仍请求同一页', () => {
  const h = loadPage()
  h.page.getList()
  h.requests[0].success(response([{ id: 1, title: '第一关' }], 2))
  h.requests[0].complete()
  assert.equal(h.page.data.page, 1)
  assert.equal(h.page.data.list.length, 1)

  h.page.loadMore()
  assert.equal(h.requests[1].data.pageNum, 2)
  assert.equal(h.requests[1].hideLoading, true)
  assert.equal(h.requests[1].silentError, true)
  assert.equal(h.page.data.loadingMore, true)
  h.requests[1].fail({ errMsg: '网络异常，请重试' })
  h.requests[1].complete()
  assert.equal(h.page.data.page, 1, '失败时不能越过第 2 页')
  assert.deepEqual(h.page.data.list.map((item) => item.id), [1], '已显示内容不能被清空')
  assert.equal(h.page.data.errorMsg, '', '加载更多不能切成整屏错误')
  assert.match(h.page.data.loadMoreError, /网络/)

  h.page.retryLoadMore()
  assert.equal(h.requests[2].data.pageNum, 2)
  h.requests[2].success(response([{ id: 2, title: '第二关' }], 2))
  h.requests[2].complete()
  assert.equal(h.page.data.page, 2)
  assert.deepEqual(h.page.data.list.map((item) => item.id), [1, 2])
  assert.equal(h.page.data.loadMoreError, '')
})

test('有内容时刷新失败转为局部提示，不把旧列表伪装成空态', () => {
  const h = loadPage()
  h.page.data.list = [{ id: 7, title: '已保留的玩法' }]
  h.page.data.hasMore = false
  h.page.refreshList()
  h.requests[0].fail({ msg: '服务暂不可用' })
  h.requests[0].complete()
  assert.deepEqual(h.page.data.list.map((item) => item.id), [7])
  assert.equal(h.page.data.errorMsg, '')
})

test('列表区分首屏错误、刷新错误、加载更多错误和在途态', () => {
  const wxml = read('subpackageMember/mytemplate/mytemplate.wxml').replace(/<!--[\s\S]*?-->/g, '')
  const json = JSON.parse(read('subpackageMember/mytemplate/mytemplate.json'))
  assert.equal(json.usingComponents['cy-inline-error'], '/components/cy/inline-error/index')
  assert.match(wxml, /<cy-error\b[^>]*wx:if="\{\{!loading && errorMsg && list\.length === 0\}\}"[^>]*bind:retry="refreshList"/s)
  assert.doesNotMatch(wxml, /<cy-inline-error\b[^>]*staleError/s)
  assert.match(wxml, /<cy-inline-error\b[^>]*wx:if="\{\{loadMoreError\}\}"[^>]*action="重试"[^>]*bind:action="retryLoadMore"/s)
  assert.match(wxml, /wx:elif="\{\{loadingMore\}\}"[^>]*aria-live="polite"[^>]*>正在加载更多…<\/view>/s)
  assert.match(wxml, /wx:elif="\{\{hasMore && list\.length > 0\}\}"[^>]*aria-role="button"[^>]*bindtap="loadMore"/s)
})

test('负控：加载更多失败分支不得再覆写 list', () => {
  const source = read('subpackageMember/mytemplate/mytemplate.js')
  const handler = source.slice(source.indexOf('\n  handleListError(isLoadMore, message)'), source.indexOf('// 删除模板API调用'))
  const loadMoreBranch = handler.slice(handler.indexOf('if (isLoadMore)'), handler.indexOf('if (this.data.list.length'))
  assert.match(source, /loadingMore/)
  assert.match(loadMoreBranch, /loadMoreError/)
  assert.doesNotMatch(loadMoreBranch, /list\s*:/)
})

test('个人审核与广场状态分开，旧 publishStatus 不能决定上下架', () => {
  const { page } = loadPage()
  const approved = { id: 7, draftStatus: 1, status: 1, checkStatus: 1, publishStatus: 1 }
  const personal = page.decorateItem(approved)
  assert.equal(personal._canSubmitMarket, true)
  assert.equal(personal._canToggleMarket, false)
  const pending = page.decorateItem({ ...approved, marketTemplateId: 70, marketStatus: 0 })
  assert.equal(pending._canSubmitMarket, false)
  assert.equal(pending._marketActionText, '撤回广场审核')
  assert.equal(pending._marketPublishTarget, 0)
  const delisted = page.decorateItem({ ...approved, marketTemplateId: 70, marketStatus: 1, marketPublishStatus: 0 })
  assert.equal(delisted._marketActionText, '上架广场')
  assert.equal(delisted._marketPublishTarget, 1)
  for (const marketStatus of ['LISTED', 0.5, null, true]) {
    const unknown = page.decorateItem({ ...approved, marketTemplateId: 70, marketStatus })
    assert.equal(unknown._canSubmitMarket, false)
    assert.equal(unknown._canToggleMarket, false)
  }
})

test('提交广场只发一次，成功后回读列表，不在本地翻转个人状态', () => {
  const h = loadPage()
  const row = h.page.decorateItem({ id: 7, draftStatus: 1, status: 1, checkStatus: 1, publishStatus: 0 })
  h.page.data.list = [row]
  const event = { currentTarget: { dataset: { id: 7 } } }
  h.page.templateStatus(event)
  h.page.templateStatus(event)
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].url, '/api/template/7/market-submit')
  h.requests[0].success({ code: 200 })
  assert.equal(h.requests[1].url, '/api/template/my-list')
  assert.equal(row.publishStatus, 0)
})

test('广场撤回按稳定 ID 定位并发送目标状态，失败后可以重试', () => {
  const h = loadPage()
  h.page.data.list = [h.page.decorateItem({ id: 8, draftStatus: 1, status: 1, checkStatus: 1,
    marketTemplateId: 80, marketStatus: 0, publishStatus: 0 })]
  const event = { currentTarget: { dataset: { id: 8, index: 99 } } }
  h.page.templateStatus(event)
  assert.equal(h.requests[0].url, '/api/template/updateLibraryStatus')
  assert.deepEqual(h.requests[0].data, { template_id: 8, publish_status: 0 })
  h.requests[0].fail({ errMsg: 'network' })
  h.page.templateStatus(event)
  assert.equal(h.requests.length, 2)
})

test('商家列表操作保留作用域，个人列表操作不额外切换身份', () => {
  for (const scope of ['', 'MERCHANT']) {
    const h = loadPage()
    h.page.data.operationScope = scope
    h.page.data.list = [h.page.decorateItem({ id: 7, draftStatus: 1, status: 1, checkStatus: 1 })]
    h.page.templateStatus({ currentTarget: { dataset: { id: 7 } } })
    assert.equal(h.requests[0].data.scope || '', scope)
    h.requests[0].fail({})
    h.page.data.list = [h.page.decorateItem({ id: 8, draftStatus: 1, status: 1, checkStatus: 1,
      marketTemplateId: 80, marketStatus: 0 })]
    h.page.templateStatus({ currentTarget: { dataset: { id: 8 } } })
    assert.equal(h.requests[1].data.scope || '', scope)
    h.page.deleteTemplate(8, 0)
    assert.equal(h.requests[2].data.scope || '', scope)
  }
})

test('删除成功按稳定模板ID移除，期间刷新换序不误移其它行', () => {
  const h = loadPage()
  h.page.data.list = [{ id: 8, title: '本人模板' }, { id: 9, title: '保留模板' }]
  h.page.deleteTemplate(8, 0)
  const refreshed = [{ id: 9, title: '刷新后保留', marketStatus: 1 }, { id: 8, title: '待删除' }]
  h.page.data.list = refreshed
  h.requests[0].success({ code: 200 })
  assert.deepEqual(h.page.data.list, [refreshed[0]])
  assert.deepEqual(refreshed.map(row => row.id), [9, 8], '回包不原地改共享的刷新列表')
  assert.equal(h.requests[0].data.template_id, 8)
})

test('删除成功前目标已不在当前列表，不按旧索引移除新内容', () => {
  const h = loadPage()
  h.page.data.list = [{ id: 8, title: '待删除' }]
  h.page.deleteTemplate(8, 0)
  h.page.data.list = [{ id: 10, title: '重新筛选的新内容', marketStatus: 0 }]
  h.requests[0].success({ code: 200 })
  assert.deepEqual(h.page.data.list, [{ id: 10, title: '重新筛选的新内容', marketStatus: 0 }])
})

test('删除成功兼容数字与字符串ID且保留其它模板，正常顺序仍有效', () => {
  const h = loadPage()
  h.page.data.list = [{ id: '8', title: '待删除' }, { id: 18, title: '保留' }]
  h.page.deleteTemplate(8, 0)
  h.requests[0].success({ code: '200' })
  assert.deepEqual(h.page.data.list, [{ id: 18, title: '保留' }])
})

test('模板删除业务拒绝或网络失败保留全部原记录', () => {
  for (const failure of ['business', 'network']) {
    const h = loadPage()
    const rows = [{ id: 8, title: '本人模板' }, { id: 9, title: '保留模板' }]
    h.page.data.list = rows
    h.page.deleteTemplate(8, 0)
    if (failure === 'business') h.requests[0].success({ code: 403, msg: '不可删除' })
    else h.requests[0].fail({ errMsg: 'network' })
    assert.deepEqual(h.page.data.list, rows)
  }
})

test('列表初始化scope不送渲染，原MERCHANT及普通请求合同保留', () => {
  for (const scope of ['MERCHANT','my','']) {
    const h=loadPage(), patches=[], original=h.page.setData
    h.page.setData=function(p){patches.push(p);return original.call(this,p)}
    h.page.onLoad({scope})
    assert.equal(h.page.data.operationScope,scope==='MERCHANT'?'MERCHANT':'')
    assert.equal(patches.some(p=>Object.hasOwn(p,'operationScope')),false)
    assert.equal(h.requests[0].data.scope,scope==='MERCHANT'?'MERCHANT':'')
  }
})

test('开发版我的模板不预填演示数据，真实空和失败仍是原结果', () => {
  for(const failure of [false,true]){
    const h=loadPage();global.wx.getAccountInfoSync=()=>({miniProgram:{envVersion:'develop'}});
    h.page.getList();assert.equal(h.page.data.list.length,0);
    if(failure)h.requests[0].fail({errMsg:'真实网络故障'});else h.requests[0].success(response([],0));
    h.requests[0].complete();assert.equal(h.page.data.list.length,0);assert.equal(h.page.data.total,0);
    if(failure)assert.equal(h.page.data.errorMsg,'真实网络故障');else assert.equal(h.page.data.errorMsg,'');
  }
});


test('删除原ID单飞并按真实移除条数减总数，成功回读阻止旧列表复活', () => {
  const h=loadPage();h.page.data.list=[{id:7},{id:8}];h.page.data.total=2;
  h.page.getList();const stale=h.requests[0];h.page.deleteTemplate(7,0);h.page.deleteTemplate(7,0);
  assert.equal(h.requests.filter(r=>r.url==='/api/template/delete').length,1);
  h.requests[1].success({code:200});h.requests[1].complete();
  assert.equal(h.page.data.total,1);assert.deepEqual(h.page.data.list.map(r=>r.id),[8]);
  assert.equal(h.requests[2].url,'/api/template/my-list');
  stale.success(response([{id:7},{id:8}],2));stale.complete();assert.deepEqual(h.page.data.list.map(r=>r.id),[8]);
  h.requests[2].success(response([{id:8}],1));h.requests[2].complete();assert.equal(h.page.data.total,1);
});
test('删除失败不摘卡且解除单飞，卸载后的回包不修改列表', () => {
  const h=loadPage();h.page.data.list=[{id:7}];h.page.data.total=1;
  h.page.deleteTemplate(7,0);h.requests[0].fail({});h.requests[0].complete();assert.equal(h.page.data.total,1);
  h.page.deleteTemplate(7,0);assert.equal(h.requests.length,2);h.page.onUnload();h.requests[1].success({code:200});
  assert.equal(h.page.data.list[0].id,7);assert.equal(h.page.data.total,1);
});
