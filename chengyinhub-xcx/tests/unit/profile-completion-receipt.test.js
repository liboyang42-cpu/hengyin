const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')

const PROFILE = path.resolve(__dirname, '../../subpackageMember/components/cy/profile/index.js')

function mountProfile() {
  const requests = []
  const toasts = []
  let definition
  global.getApp = () => ({
    globalData: { user_id: 9, statusBarHeight: 20, navBarHeight: 44, features: {} },
    sendRequest(options) { requests.push(options) },
    getUserID: () => 9,
    isDevEnv: () => false,
  })
  const persisted = {};
  global.wx = {
    showLoading() {}, hideLoading() {},
    showToast(options) { toasts.push(options.title) },
    getStorageSync: key => persisted[key] || '',
    setStorageSync(key, value) { persisted[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete persisted[key]; },
  }
  global.Component = (config) => { definition = config }
  delete require.cache[require.resolve(PROFILE)]
  require(PROFILE)
  const component = Object.assign({}, definition.methods)
  component.data = JSON.parse(JSON.stringify(definition.data))
  component.setData = (patch) => Object.assign(component.data, patch)
  component.closeScene = () => {}
  return { component, requests, toasts }
}

test('profile 选章核销把首次 completionEarnedXp 显示一次,重放不重复', () => {
  const { component, requests, toasts } = mountProfile()
  component.data.chapterSheet = { code: 'C', items: [] }
  component.onChapterPick({ currentTarget: { dataset: { id: 31 } } })
  requests[0].success({ code: 200, msg: '核销成功', data: { chapterId: 31, redemptionId: 66, completionEarnedXp: 36 } })
  assert.equal(toasts.at(-1), '核销成功 · 玩家通关 +36 探索值已到账')

  component.onChapterPick({ currentTarget: { dataset: { id: 31 } } })
  requests[1].success({ code: 200, msg: '核销成功', data: { chapterId: 31, redemptionId: 66 } })
  assert.equal(toasts.at(-1), '核销成功')
})

test('profile 明确点选历史章节后只展示已核销回执', () => {
  const { component, requests, toasts } = mountProfile();
  component.data.chapterSheet = {code: 'C', items: [{id:31, replayed:true, redemptionId:66}]};
  component.onChapterPick({ currentTarget: { dataset: { id: 31 } } });
  assert.deepEqual(requests[0].data, {code:'C', chapterId:31});
  requests[0].success({code:200, data:{chapterId:31, replayed:true, redemptionId:66, completionEarnedXp:36}});
  assert.equal(toasts.at(-1), '已核销：该章节已核销，请勿重复提供权益');
  assert.equal(toasts.some(t => t.includes('探索值')), false);
});
