const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const ROOT = path.resolve(__dirname, '../..');
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8');
const { decorateFeedCards } = require('../../utils/index/feed-card.js');

test('feed 图片只按真实数量渲染：单图铺满，四张及以上取四张', () => {
  const [single, gallery] = decorateFeedCards([
    { imgArr: 'one.jpg' },
    { imgArr: '1.jpg,2.jpg;3.jpg,4.jpg,5.jpg' },
  ], (src) => `cdn/${src}`);

  assert.deepEqual(single.feedImages, ['cdn/one.jpg']);
  assert.equal(single.feedGridMode, '1');
  assert.deepEqual(gallery.feedImages, ['cdn/1.jpg', 'cdn/2.jpg', 'cdn/3.jpg', 'cdn/4.jpg']);
  assert.equal(gallery.feedGridMode, '4');
  assert.equal(gallery.moreCount, 1);
});

test('feed 左上显示真实主题类型，左下显示最多两个真实标签，二者不串位', () => {
  const [city, explore, unknown] = decorateFeedCards([
    {
      productType: 1,
      sysCategoryList: [
        { categoryName: '亲子' },
        { categoryName: '解谜' },
        { categoryName: '不应展示' },
      ],
    },
    { productType: '2', sysCategoryList: [{ categoryName: '探店' }] },
    { productType: 99, sysCategoryList: [{ categoryName: '夜游' }] },
  ], (src) => src);

  assert.equal(city.feedTypeLabel, '城市定向');
  assert.deepEqual(city.feedTags, ['亲子', '解谜']);
  assert.equal(explore.feedTypeLabel, '自由探索');
  assert.deepEqual(explore.feedTags, ['探店']);
  assert.equal(unknown.feedTypeLabel, '', '未知类型不得拿第一个标签冒充');
  assert.deepEqual(unknown.feedTags, ['夜游']);
});

test('附近活动与 feed 共用封面归一化，imgUrl 也会成为可展示 cover', () => {
  const [item] = decorateFeedCards([{ imgUrl: 'activity.jpg' }], (src) => `cdn/${src}`);
  assert.equal(item.cover, 'cdn/activity.jpg');
  assert.deepEqual(item.feedImages, ['cdn/activity.jpg']);
});

test('编辑精选紧跟 banner，标题明确运营来源', () => {
  const wxml = read('pages/index/index.wxml');
  const hero = wxml.indexOf('id="sec-hero"');
  const reco = wxml.indexOf('id="sec-reco"');
  const nearby = wxml.indexOf('id="sec-nearby"');

  assert.ok(hero >= 0 && reco > hero && nearby > reco, '区块顺序必须是 banner → 编辑精选 → 附近活动');
  assert.match(wxml, /id="sec-reco"[\s\S]*?<text class="v3-sec-title">编辑精选<\/text>/);
  assert.doesNotMatch(wxml, />为你专属推荐主题<\/text>/);
});

// 2026-10-28 p104/p70 业主:推荐卡改成整张封面卡(取代 Figma 单图 / 四宫格两个节点),左上类型、左下标签仍消费独立字段。
test('feed 卡是整张封面卡，渐暗压字，高度与玩法卡一致', () => {
  const tokens = read('style/tokens.wxss');
  const wxss = read('pages/index/index.wxss');
  assert.match(tokens, /--cy-color-home-feed-surface:\s*#F0F0EE/);
  assert.doesNotMatch(wxss, /--cy-home-/);
  assert.match(wxss, /\.v2-fcover\s*\{[^}]*position:\s*absolute/s);
  assert.match(wxss, /\.v2-fcard::after\s*\{[^}]*linear-gradient/s);
  assert.doesNotMatch(wxss, /\.v2-fgrid/);
});

test('封面卡左上类型与左下标签消费独立字段', () => {
  const wxml = read('pages/index/index.wxml');
  assert.equal((wxml.match(/class="v2-feyebrow ep1"[^>]*>\{\{item\.feedTypeLabel\}\}/g) || []).length, 2);
  assert.equal((wxml.match(/wx:for="\{\{item\.feedTags\}\}"/g) || []).length, 2);
  assert.doesNotMatch(wxml, /v2-feyebrow[^>]*>\{\{item\.sysCategoryList\[0\]/);
});

test('附近活动和即将上线都以真实图片作为卡片主视觉', () => {
  const wxml = read('pages/index/index.wxml');
  assert.match(wxml, /class="v3-nearby-bg"[^>]*src="\{\{item\.cover/);
  assert.match(wxml, /class="v3-upreview-bg"[^>]*src="\{\{item\.cover/);
  assert.match(wxml, /class="v3-upreview-mask"/);
});

test('保留设计fixture的位图资产，运行首页不再注入设计假数据', () => {
  const source = read('pages/index/index.js');
  assert.doesNotMatch(source, /buildIndexUiMock|_applyIndexUiMock/);
  const mock = read('tests/fixtures/home-design-preview-source.txt').match(/function buildIndexUiMock\(\) \{([\s\S]*?)\n\}\n\nPage\(/);
  assert.ok(mock, '设计fixture必须保留供离线设计验证');
  assert.doesNotMatch(mock[1], /figma_cat\d+\.png/, '这些 .png 实际是 SVG，微信 image 会渲染失败');
  assert.match(mock[1], /index-figma\/nearby-card\.jpg/);
  assert.match(mock[1], /index-figma\/reco-hero\.jpg/);
});

test('负控：封面卡退回四宫格时契约必须判红', () => {
  const wxss = read('pages/index/index.wxss');
  const mutated = wxss + '\n.v2-fgrid { width: 316rpx; }\n';
  assert.throws(() => assert.doesNotMatch(mutated, /\.v2-fgrid/), assert.AssertionError);
});
