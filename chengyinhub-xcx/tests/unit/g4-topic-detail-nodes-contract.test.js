process.env.TZ = 'UTC';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const stripWxmlComments = (source) => source.replace(/<!--[\s\S]*?-->/g, '');

/* 玩家公开主题页的三条修复(2026-09-24 走查 G4):
   CU-C-38 分享卡只写「主题详情」 / CU-C-65 站点字段裸插值印出字面 null /
   CU-C-66 详情写一站、路线节点列出两站(快照过期)。
   这里跑的是页面里那两个真正被调用的整形函数,不是照着源码抄一遍断言。 */

let pageConfig;
global.getApp = () => ({
  globalData: {}, getUserID: () => 1,
  sendRequest: () => ({ abort() {} }), tips: () => {},
});
global.wx = new Proxy({}, { get: () => () => {} });
global.Page = (config) => { pageConfig = config; };
require('../../pages/topic/index/index.js');

function makePage(overrides) {
  const page = Object.assign({}, pageConfig);
  page.data = Object.assign(JSON.parse(JSON.stringify(pageConfig.data)), overrides || {});
  page.setData = (patch) => {
    Object.keys(patch).forEach((key) => { page.data[key] = patch[key]; });
  };
  return page;
}

test('CU-C-38:分享卡标题写主题名,不再写死成泛化的「主题详情」', () => {
  const page = makePage();
  page.data.id = 900030;
  page.data.info = { name: 'E2E 探店日一期' };
  const share = page.onShareAppMessage();
  assert.equal(share.title, 'E2E 探店日一期');
  assert.equal(share.path, '/pages/topic/index/index?id=900030', 'path 不动');
});

test('CU-C-38:主题还没读到时退回泛化文案,不编一个标题也不给空标题', () => {
  const page = makePage();
  page.data.id = 900030;
  page.data.info = null;
  assert.equal(page.onShareAppMessage().title, '主题详情');
  page.data.info = { name: '   ' };
  assert.equal(page.onShareAppMessage().title, '主题详情');
});

test('CU-C-65:站点级缺值归一成空串,不把 null 渲染给玩家', () => {
  const page = makePage();
  const chapters = page.processChaptersList([{
    name: '第1章',
    nodes: [
      {
        id: 1, name: null, address: null, businessTime: null,
        registrationMerchantList: [{ mmsMerchant: { name: null } }],
      },
      {
        id: 2, name: '隔离书店站', address: '书店路 1 号', businessTime: '10:00',
        registrationMerchantList: [],
      },
    ],
  }]);
  const nodes = chapters[0].nodes;
  assert.equal(nodes[0].businessTime, '', 'businessTime 缺失 → 空串(商家提交点位不带这一列,该列恒 NULL)');
  assert.equal(nodes[0].address, '', 'address 缺失 → 空串');
  assert.equal(nodes[0].name, '', 'name 缺失 → 空串');
  assert.equal(nodes[0].registrationMerchantList[0].mmsMerchant.name, '', '商家名缺失 → 空串');
  assert.equal(nodes[1].businessTime, '10:00', '有值的原样保留');
  assert.equal(nodes[1].name, '隔离书店站', '有值的原样保留');
});

test('CU-C-65:字符串 "null" 也按缺值处理(历史数据里真有这种值)', () => {
  const page = makePage();
  const chapters = page.processChaptersList([{
    name: '第1章',
    nodes: [{ id: 1, name: 'null', address: ' null ', businessTime: 'null', registrationMerchantList: [] }],
  }]);
  const node = chapters[0].nodes[0];
  assert.equal(node.name, '');
  assert.equal(node.address, '');
  assert.equal(node.businessTime, '');
});

test('CU-C-65:站点卡片那几格挂 wx:if,缺值整行不出', () => {
  const wxml = stripWxmlComments(read('pages/topic/index/index.wxml'));
  assert.match(wxml, /<view class="david_hp_con_time" wx:if="\{\{ node\.businessTime \}\}">/, '营业时间缺值整行不出');
  assert.match(wxml, /<view class="item-add flex-ac" wx:if="\{\{ node\.address \}\}">/, '地址缺值时图标+地址一起收起');
  assert.match(wxml, /<text class="node-card-name" wx:if="\{\{node\.registrationMerchantList\[0\]\.mmsMerchant\.name\}\}">/,
    '商家名缺值不印空名');
});

const playerPageConfig = pageConfig;
require('../../pages/topic/merchantinfo/merchantinfo.js');
const merchantPageConfig = pageConfig;
pageConfig = playerPageConfig;

for (const [label, config] of [['玩家', playerPageConfig], ['商家', merchantPageConfig]]) {
  test(label + ': 整条路线总数不能被首章/商家单站可见子集覆盖', () => {
    const includes = config.buildContentIncludes({
      totalChapterCount: 2, locationCount: 8,
      chaptersList: [{ nodes: [{ id: 1 }] }],
    });
    assert.ok(includes.includes('8 个站点'));
    assert.ok(!includes.includes('1 个站点'));
    assert.ok(includes.includes('2 个章节'));
  });
  test(label + ': 权威零不回退到可见节点数', () => {
    const includes = config.buildContentIncludes({ locationCount: 0, chaptersList: [{ nodes: [{ id: 1 }] }] });
    assert.ok(!includes.some(text => text.includes('个站点')));
  });
  test(label + ': 空 teaser 仍使用完整总站点数且不增加隐藏节点', () => {
    const data = { locationCount: 3, chaptersList: [] };
    const before = JSON.stringify(data);
    assert.ok(config.buildContentIncludes(data).includes('3 个站点'));
    assert.equal(JSON.stringify(data), before);
  });
  test(label + ': 缺失总数不从子集推测', () => {
    for (const count of [undefined, null, 'invalid']) {
      const includes = config.buildContentIncludes({ locationCount: count, chaptersList: [{ nodes: [{ id: 1 }] }] });
      assert.ok(!includes.some(text => text.includes('个站点')));
    }
  });
}

test('CU-C-66:章节级「N 个节点」同样按本章真实节点数', () => {
  const page = makePage();
  const chapters = page.processChaptersList([{
    name: '第1章', locationCount: 1, nodes: [{ id: 1 }, { id: 2 }],
  }]);
  assert.equal(chapters[0].metaPlace, '2 个节点');
});

test('CU-C-66:商家版(merchantinfo)同一口径,两端不各算一套', () => {
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(js, /const stations = Number\(d\.locationCount\)/, '商家版也使用服务端整条路线总数');
  assert.doesNotMatch(js, /const liveStations/);
  assert.match(js, /const nodeCount = \(chapter\.nodes \|\| \[\]\)\.length/, '商家版章节节点数同源');
});
