const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

// BL-190：路线内容「取消点赞」成功回调必须按 item.id 移除，不能用点击时的 index splice。
const ROOT = path.resolve(__dirname, '../..');

function mount(items) {
  let definition;
  const requests = [];
  const toasts = [];
  const toast = msg => toasts.push(msg);
  toast.success = msg => toasts.push('ok:' + msg);
  vm.runInNewContext(
    fs.readFileSync(path.join(ROOT, 'components/cy/scene-route-content/index.js'), 'utf8'),
    {
      Component: value => { definition = value; },
      getApp: () => ({ globalData: {}, sendRequest(request) { requests.push(request); } }),
      require: request => {
        if (request.includes('utils/modal.js')) return { show: options => options.success({ confirm: true }) };
        if (request.includes('utils/toast.js')) return toast;
        if (request.includes('merchant-access-policy')) return { inactiveAccess: () => ({ active: false }), normalizeMerchantAccess: raw => raw };
        return {};
      },
      wx: {},
      console,
    },
  );
  const comp = { data: { items: items.slice(), state: 'ready' } };
  comp.setData = function setData(patch) { Object.assign(this.data, patch); };
  const tap = index => definition.methods.topicUnlike.call(comp, { currentTarget: { dataset: { index } } });
  return { comp, requests, toasts, tap };
}

test('两条取消点赞先后返回时删掉的是正确的两条', () => {
  const { comp, requests, tap } = mount([{ id: 1 }, { id: 2 }, { id: 3 }]);
  tap(0);
  tap(1);
  assert.deepEqual(JSON.parse(JSON.stringify(requests.map(r => r.data))), [{ id: 1, type: 0 }, { id: 2, type: 0 }]);
  requests[0].success({ code: 200 });
  requests[1].success({ code: 200 });
  assert.deepEqual(comp.data.items.map(i => i.id), [3]);
  assert.equal(comp.data.state, 'ready');
});

test('请求期间列表被替换、目标已不在时不动列表；删光后为空态', () => {
  const { comp, requests, toasts, tap } = mount([{ id: 1 }]);
  tap(0);
  comp.data.items = [{ id: 7 }, { id: 8 }];
  requests[0].success({ code: 200 });
  assert.deepEqual(comp.data.items.map(i => i.id), [7, 8]);
  assert.ok(toasts.includes('ok:取消点赞成功'));

  const second = mount([{ id: 5 }]);
  second.tap(0);
  second.requests[0].success({ code: '200' });
  assert.equal(second.comp.data.items.length, 0);
  assert.equal(second.comp.data.state, 'empty');
});
