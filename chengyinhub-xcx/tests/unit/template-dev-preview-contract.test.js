const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const mockData = require('../../pages/club/utils/mockData');

const INTERACTION_TITLES = [
  '今晚的暗号', '老板的秘密题', '拍下这一刻', '收集这枚风味印章',
  '新品盲测局', '今日搭配任务', '集合点亮', '小队竞速点亮',
  '街角谜题', '城市取景任务', '城市接力棒', '今日角色任务',
];

const TOPIC_NAMES = [
  '风味巡游', '不惑之年', '预制人生', '高温末日逃生', '上海像一部旧电影',
];

// 2026-10-28:模板页货架移进 pages/templatedetail/shelf(业主定稿原型 v21)。旧页的装饰器 / 预览注入 / 半屏中转
// 断言随旧页退役；实验预览不进复制与详情、接口失败不注入演示、真实空态等不变量在
// template-shelf-redesign-20261028.test.js 重钉。这里只留开发数据本身的两条。

test('开发数据包含已新增的 12 个商家/俱乐部交互模板', () => {
  const titles = mockData.getTemplates().map((item) => item.title);
  INTERACTION_TITLES.forEach((title) => {
    assert.equal(titles.includes(title), true, `缺少交互模板:${title}`);
  });
  const interactionPreviews = mockData.getTemplates().filter((item) => INTERACTION_TITLES.includes(item.title));
  assert.equal(interactionPreviews.every((item) => item.previewOnly === true), true,
    '未落库的交互模板必须明确标记为开发预览');

  const home = mockData.getHomeData();
  const visibleTitles = new Set([
    ...home.bannerList, ...home.recommendList, ...home.latestList,
  ].map((item) => item.title));
  INTERACTION_TITLES.forEach((title) => {
    assert.equal(visibleTitles.has(title), true, `开发首页切片中找不到交互模板:${title}`);
  });
});

function assertUnknownTemplateFallbackUsesDsIcon(markup) {
  // 2026-08-26:列表统一成 .cml-pic 之后,「没有专属资源」的兜底不再是 cy-icon 图标位,
  // 而是明确失败态(no_data + 「封面暂不可用」)。守的不变量没变:不许伪造资产。
  assert.match(markup, /class="cml-pic cover-error"[\s\S]*?<text class="cover-error-text">封面暂不可用<\/text>/,
    '没有专属资源的模板必须落到明确失败态，不能留空白');
  assert.doesNotMatch(markup, /\{\{\s*item\._initial\s*\}\}/,
    '未知模板不得用首字冒充图形资产');
  assert.doesNotMatch(markup, /linear-gradient\([^)]*item\./,
    '未知模板不得用 CSS 渐变冒充内容图');
}

test('开发数据包含 5 个上海单人自由探索主题，且保持 EXPERIMENTAL 预览状态', () => {
  const topics = mockData.getTopicTemplates();
  assert.deepEqual(topics.map((item) => item.name), TOPIC_NAMES);
  topics.forEach((item) => {
    assert.equal(item.templateStatus, 'EXPERIMENTAL');
    assert.equal(item.previewOnly, true);
    assert.equal(item.productType, 2);
    assert.equal(item.chapterCount, 1);
    assert.equal(item.gameCount, 1);
    assert.equal(item.locationCount, 4);
    // 封面已搬去服务端静态目录(主包 2MB 硬限);母版留在仓库 docs/assets/template-covers/。
    // 2026-08-25 换轨:实拍照 → ci/render_template_covers.py 产出的自绘场景 key art。
    assert.match(item.imgUrl,
      /^https:\/\/www\.chengyinhub\.com\/prod-api\/profile\/template-covers\/t\d{2}-[a-z-]+\.webp$/);
    const stem = item.imgUrl.split('/').pop();
    assert.equal(
      fs.existsSync(path.join(ROOT, '../docs/assets/template-covers/final', stem)),
      true, `线上封面必须在仓库留有母版:${item.imgUrl}`);
  });
});
