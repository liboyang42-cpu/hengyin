const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadMediaPreview() {
  const source = read('src/components/CyMediaPreview/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script.replace(/export\s+default\s*/, 'return ');
  return new Function(body)();
}

test('CyMediaPreview 只呈现页面已解析的图片数组，不猜分隔格式或业务请求', () => {
  const component = loadMediaPreview();
  const vm = { sources: [' first.jpg ', '', null, 'second.jpg'] };

  assert.ok(component.props.sources, '页面必须显式提供已解析的媒体来源');
  assert.equal(component.methods && component.methods.getList, undefined, '组件不应请求页面数据');
  assert.equal(component.methods && component.methods.parseImages, undefined, '组件不应猜测页面的图片分隔格式');
  assert.deepEqual(component.computed.validSources.call(vm), ['first.jpg', 'second.jpg']);
  assert.equal(component.computed.primarySource.call({ validSources: ['first.jpg'] }), 'first.jpg');
  assert.equal(component.computed.hasSources.call({ validSources: [] }), false);
});

test('CyMediaPreview 复用 Element 图片灯箱，只在多图时显示数量提示', () => {
  const source = read('src/components/CyMediaPreview/index.vue');

  assert.match(source, /<button[\s\S]*?type="button"/);
  assert.match(source, /@click="openPreview"/);
  assert.match(source, /@keydown\.enter\.prevent="openPreview"/);
  assert.match(source, /@keydown\.space\.prevent="openPreview"/);
  assert.match(source, /<el-image[\s\S]*?@click\.stop/);
  assert.match(source, /:preview-src-list="validSources"/);
  assert.match(source, /v-if="validSources\.length > 1"/);
  assert.match(source, /\+\{\{ validSources\.length - 1 \}\}/);
  assert.match(source, /slot="error"/);

  const template = source.match(/<template>([\s\S]*?)<\/template>/)[1];
  assert.match(compiler.compile(template).render, /stopPropagation\(\)/, '图片点击必须在内层阻止冒泡，避免再次调用外层打开逻辑');
});

test('CyMediaPreview 通过 Element 图片实例打开灯箱，缺少实例时安全忽略', () => {
  const component = loadMediaPreview();
  let opened = 0;

  component.methods.openPreview.call({ $refs: { image: { clickHandler: () => { opened += 1 } } } });
  component.methods.openPreview.call({ $refs: {} });

  assert.equal(opened, 1);
});

test('活动列表复用媒体预览，主题列表则移除图片列以突出运营决策信息', () => {
  const activity = read('src/views/business/activity/index.vue');
  assert.ok(activity.includes("import CyMediaPreview from '@/components/CyMediaPreview'"));
  assert.match(activity, /<cy-media-preview[\s\S]*?:sources="getImageList\(scope\.row\.imgArr\)"/);
  assert.match(activity, /getImageList\(pics\)/, '图片分隔格式仍由活动页面保留');

  const topic = read('src/views/business/topic/index.vue');
  assert.doesNotMatch(topic, /import CyMediaPreview from/);
  assert.doesNotMatch(topic, /<cy-media-preview/);
  assert.doesNotMatch(topic, /getImageList\(pics\)/);
  assert.match(topic, /label="内容主体"/);
  assert.doesNotMatch(topic, /getFirstImage\(/);
  assert.doesNotMatch(topic, /getImageCount\(/);
});
