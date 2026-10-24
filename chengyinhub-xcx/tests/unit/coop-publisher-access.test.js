const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { readPublisherAccess } = require('../../subpackageMember/utils/coop-publisher-access.js');
function setup(context) {
  let user = 10;
  const requests = [], results = [];
  readPublisherAccess({ getUserID: () => user, sendRequest: request => requests.push(request) }, context, allowed => results.push(allowed));
  return { requests, results, switchUser() { user = 20; } };
}
test('非发布者即使HTTP成功也不能显示发起入口，参与方不查询扩展范围', () => {
  const s = setup({ topicId: 3, operationScope: 'MERCHANT' });
  assert.equal(s.requests[0].url, '/api/topic/invite-chapters');
  assert.deepEqual(s.requests[0].data, { id: 3, scope: 'MERCHANT' });
  s.requests[0].success({ code: 500, msg: '主题不存在或无权查看' });
  assert.deepEqual(s.results, [false]);
  const own = setup({});
  assert.equal(own.requests[0].data.is_my, 1);
  own.requests[0].success({ code: 200, data: { rows: [] } });
  assert.deepEqual(own.results, [false]);
});
test('发布者章节或本人发布列表回读可以显示入口，账号切换不复用旧授权', () => {
  const s = setup({ topicId: 3 });
  s.requests[0].success({ code: 200, data: { chapters: [{ id: 7 }] } });
  assert.deepEqual(s.results, [true]);
  const own = setup({});
  own.requests[0].success({ code: 200, data: { rows: [{ id: 3 }] } });
  assert.deepEqual(own.results, [true]);
  const switched = setup({ topicId: 3 }); switched.switchUser();
  switched.requests[0].success({ code: 200, data: { chapters: [{ id: 7 }] } });
  assert.deepEqual(switched.results, []);
});
test('商家和俱乐部宿主的按钮及点击都要求发布者回读', () => {
  // 拍板 2026-10-22:俱乐部主页撤掉了邀约入口,发布者回读只剩商家主页这个宿主。
  const clubJs = fs.readFileSync(path.resolve(__dirname, '../..', 'pages/club/detail/index.js'), 'utf8');
  assert.doesNotMatch(clubJs, /readPublisherAccess|canInviteCoop/);
  for (const file of ['subpackageMember/components/cy/profile/index']) {
    const js = fs.readFileSync(path.resolve(__dirname, '../..', file + '.js'), 'utf8');
    const wxml = fs.readFileSync(path.resolve(__dirname, '../..', file + '.wxml'), 'utf8');
    assert.match(js, /readPublisherAccess\(app,/);
    assert.match(js, /if \(!this\.data\.canInviteCoop \|\| this\._coopPublisherMemberId !== String\(app\.getUserID\(\)/);
    assert.match(wxml, /wx:if="[^"\n]*canInviteCoop/);
  }
});
