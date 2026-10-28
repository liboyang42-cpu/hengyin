'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { scanSharedA11y } = require('../lint/shared-a11y');
const read = (file) => fs.readFileSync(path.resolve(__dirname, '../..', file), 'utf8');

test('共享组件名称、角色、状态与文本替代完整', () => {
  assert.deepEqual(scanSharedA11y(), []);
});

for (const [component, before, after] of [
  ['field', 'aria-label=', 'data-label='],
  ['field', 'aria-live="polite"', 'data-live="polite"'],
  ['search', 'aria-label=', 'data-label='],
  ['btn', 'aria-busy=', 'data-busy='],
  ['btn', 'loadingLabel', 'missingText'],
  ['cell', "{{value ? '，' + value : ''}}", ''],
  ['tabs', 'aria-selected=', 'data-selected='],
]) {
  test(`负控 ${component} 删除 ${before} 必须判红`, () => {
    const key = `components/cy/${component}/index.wxml`;
    const result = scanSharedA11y((file) => file === key ? read(file).replaceAll(before, after) : read(file));
    assert.ok(result.some((item) => item.key === key));
  });
}

for (const [component, before, after] of [
  ['btn', 'min-height: 88rpx;', 'min-height: 64rpx;'],
  ['btn', 'flex-wrap: wrap;', 'flex-wrap: nowrap;'],
  ['field', 'width: 88rpx;', 'width: 48rpx;'],
  ['search', 'calc(var(--cy-type-body)', 'calc(28rpx'],
  // 2026-10-27 业主 G-7/M-20:单元格右侧值改为单行省略,负控随之反转 —— 改回折行必须判红
  ['cell', 'white-space: nowrap;\n  overflow: hidden;', 'white-space: normal;\n  overflow: hidden;'],
]) {
  test(`大字触达负控 ${component} 删除 ${before} 必须判红`, () => {
    const key = `components/cy/${component}/index.wxss`;
    assert.ok(scanSharedA11y((file) => file === key ? read(file).replaceAll(before, after) : read(file))
      .some((item) => item.key === key));
  });
}

for (const [key, before, after] of [
  ['components/cy/btn/index.js', 'behaviors: [reducedMotionBehavior]', 'behaviors: []'],
  ['components/cy/empty/index.wxml', 'reducedMotion', 'missingPreference'],
  ['components/cy/search/index.wxss', 'animation: none;', 'animation: spin 1s infinite;'],
  ['components/cy/skeleton/index.wxss', '../../../style/reduced-motion.wxss', '../../../style/missing.wxss'],
  ['style/reduced-motion.wxss', 'animation: none !important', 'animation-duration: 1ms'],
  ['style/reduced-motion.wxss', 'prefers-reduced-motion: reduce', 'prefers-reduced-motion: no-preference'],
  ['style/reduced-motion.wxss', 'display: none !important', 'display: block'],
]) {
  test(`静态替代负控 ${key} 删除 ${before} 必须判红`, () => {
    assert.ok(scanSharedA11y((file) => file === key ? read(file).replaceAll(before, after) : read(file))
      .some((item) => item.key === key));
  });
}

const vm = require('node:vm');
const { readReducedMotion } = require('../../utils/motion-preference');
for (const component of ['btn', 'search', 'empty', 'upload']) {
  test(`${component} 挂载和返回页面均读取既有偏好`, () => {
    let saved = true;
    const storage = { getStorageSync() { return saved; } };
    const context = {
      module: { exports: {} },
      Behavior(value) { return value; },
      require() { return { readReducedMotion: () => readReducedMotion(storage), subscribeReducedMotion: () => () => {} }; },
    };
    vm.runInNewContext(read('behaviors/reduced-motion.js'), context);
    const behavior = context.module.exports;
    let definition;
    vm.runInNewContext(read(`components/cy/${component}/index.js`), {
      require(name) { assert.match(name, /behaviors\/reduced-motion\.js$/); return behavior; },
      Component(value) { definition = value; },
    });
    assert.equal(definition.behaviors[0], behavior);
    const instance = {
      data: { reducedMotion: false },
      setData(patch) { Object.assign(this.data, patch); },
      ...behavior.methods,
    };
    behavior.lifetimes.attached.call(instance);
    assert.equal(instance.data.reducedMotion, true);
    saved = false;
    behavior.pageLifetimes.show.call(instance);
    assert.equal(instance.data.reducedMotion, false);
  });
}

test('隔离组件静态样式不能退回标签选择器', () => {
  assert.ok(scanSharedA11y((file) => file === 'style/reduced-motion.wxss'
    ? read(file).replace('.btn,', 'view,') : read(file)).length > 0);
});

test('新增组件动画没有对应静态覆盖必须判红', () => {
  const key = 'components/cy/btn/index.wxss';
  assert.ok(scanSharedA11y((file) => file === key
    ? read(file) + '\n.new-animation { animation: new-motion 1s infinite; }' : read(file))
    .some((item) => item.key === key && item.message.includes('.new-animation')));
});

for (const component of ['modal', 'sheet']) {
  test(`关闭叠层负控 ${component} 删除 aria-hidden 必须判红`, () => {
    const key = `components/cy/${component}/index.wxml`;
    assert.ok(scanSharedA11y((file) => file === key
      ? read(file).replace('aria-hidden="{{!show || _closing}}"', '') : read(file))
      .some(item => item.key === key));
  });
}
test('字段禁用色负控：不能污染普通占位色或把尺寸误拼进禁用颜色规则', () => {
  const key = 'components/cy/field/index.wxss';
  for (const mutate of [
    s => s.replace('.fd__ph { color: var(--cy-color-text-placeholder); }', '.fd__ph { color: var(--cy-color-text-disabled); }'),
    s => s.replace('.is-disabled .fd__ph { color: var(--cy-color-text-disabled); }', '.is-disabled .fd__ph { min-height: 88rpx; }'),
  ]) assert.ok(scanSharedA11y(file => file === key ? mutate(read(file)) : read(file)).some(item => item.key === key));
});
test('所有 tab 容器都必须有 tablist，不能只校验 tab 子项', () => {
  const key = 'components/cy/tabs/index.wxml';
  assert.ok(scanSharedA11y(file => file === key ? read(file).replaceAll('aria-role="tablist"', '') : read(file))
    .some(item => item.key === key));
});
for (const component of ['search', 'upload', 'modal']) {
  test(`loading 负控 ${component} 删除忙碌态必须判红`, () => {
    const key = `components/cy/${component}/index.wxml`;
    assert.ok(scanSharedA11y(file => file === key ? read(file).replaceAll('aria-busy=', 'data-busy=') : read(file))
      .some(item => item.key === key));
  });
}
