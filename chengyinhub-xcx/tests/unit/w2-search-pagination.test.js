const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const search = require('../../utils/discover-search.js');

function harness() {
  let definition;
  const requests = [];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../pages/search2/result/index.js'), 'utf8'), {
    Page: value => { definition = value; },
    getApp: () => ({ globalData: {}, getImgUrl: value => value, sendRequest: value => requests.push(value) }),
    require: name => name.includes('discover-search') ? search : name.includes('toast') ? () => {} : {},
    setTimeout: () => 1, clearTimeout() {},
  });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(value) { Object.assign(this.data, value); },
  });
  return { page, requests };
}
function reply(request, rows, total) {
  request.success({ code: 200, data: total === undefined ? rows : { rows, total } });
}

test('an empty filtered first page continues to a matching second page before concluding empty', () => {
  const { page, requests } = harness();
  page._filters = { minPrice: 100, maxPrice: 200 };
  page.runSearch();
  reply(requests[0], Array.from({ length: 12 }, (_, id) => ({ id: id + 1, minAmout: 10 })), 13);
  assert.equal(requests[4].data.pageNum, 2);
  assert.equal(page.data.searchLoading, true);
  reply(requests[4], [{ id: 13, name: '第二页匹配', minAmout: 150 }], 13);
  reply(requests[1], [], 0); reply(requests[2], []); reply(requests[3], []);
  assert.equal(page.data.visibleResults[0].title, '第二页匹配');
  assert.equal(page.data.hasMore, false);
});

test('load more keeps other categories, deduplicates moved rows, and uses unfiltered total', () => {
  const { page, requests } = harness();
  page.runSearch();
  reply(requests[0], [{ id: 1, name: '主题' }], 13);
  reply(requests[1], [], 0); reply(requests[2], [{ id: 7, name: '俱乐部' }]); reply(requests[3], []);
  assert.equal(page.data.hasMore, true);
  page.loadMore();
  assert.equal(requests.length, 5);
  assert.equal(requests[4].data.pageNum, 2);
  reply(requests[4], [{ id: 1, name: '重复主题' }, { id: 2, name: '新主题' }], 13);
  assert.deepEqual(Array.from(page.data.visibleResults, row => row.title), ['主题', '新主题', '俱乐部']);
});

test('an older query cannot mutate the new query cursor or launch more old pages', () => {
  const { page, requests } = harness();
  page.data.keyword = '旧'; page.runSearch();
  page.data.keyword = '新'; page.runSearch();
  reply(requests[0], [{ id: 1, name: '迟到' }], 120);
  reply(requests[4], [{ id: 2, name: '新查询' }], 1);
  reply(requests[5], [], 0); reply(requests[6], []); reply(requests[7], []);
  assert.equal(requests.length, 8);
  assert.equal(page.data.visibleResults[0].title, '新查询');
  assert.equal(page.data.hasMore, false);
});

test('malformed pagination metadata is an error, never a confirmed empty result', () => {
  const { page, requests } = harness();
  page.runSearch();
  reply(requests[0], []);
  reply(requests[1], [], 0); reply(requests[2], []); reply(requests[3], []);
  assert.match(page.data.searchError, /没有完成/);
});


test('non-finite, fractional and string totals cannot start an unbounded pagination chain', () => {
  for (const total of [Infinity, 1.5, '12', -1]) {
    const { page, requests } = harness();
    page.runSearch(); reply(requests[0], [], total);
    reply(requests[1], [], 0); reply(requests[2], []); reply(requests[3], []);
    assert.equal(requests.length, 4);
    assert.match(page.data.searchError, /没有完成/);
  }
});
