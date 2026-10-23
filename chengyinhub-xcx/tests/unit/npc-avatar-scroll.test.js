const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

test('预设头像导出图片随正文滚动，旧头像和离开后的回调不能覆盖当前形象', () => {
  const file = path.resolve(__dirname, '../../components/cy/npc-avatar/index.js');
  let definition;
  const exports = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    require: createRequire(file), Component: (value) => { definition = value; },
    wx: { canvasToTempFilePath: (options) => exports.push(options) },
  });
  const component = Object.assign({}, definition.methods, {
    data: Object.assign({}, definition.data, { src: 'px1:p01' }),
    setData(patch, callback) { Object.assign(this.data, patch); if (callback) callback(); },
    createSelectorQuery() {
      return { select: () => ({ fields: () => ({ exec: (callback) => callback([
        { node: { getContext: () => ({ fillRect() {} }) } },
      ]) }) }) };
    },
  });
  definition.observers.src.call(component, component.data.src);
  definition.lifetimes.ready.call(component);
  assert.equal(exports.length, 1, '预设形象须复用本机画布导出');
  component.data.src = 'px1:p04';
  definition.observers.src.call(component, component.data.src);
  exports[0].success({ tempFilePath: '/old.png' });
  assert.equal(component.data.renderedSrc, '', '旧形象回调不得串图');
  exports[1].success({ tempFilePath: '/current.png' });
  assert.equal(component.data.renderedSrc, '/current.png');
  component.data.src = 'px1:p07';
  definition.observers.src.call(component, component.data.src);
  definition.lifetimes.detached.call(component);
  exports[2].success({ tempFilePath: '/detached.png' });
  exports[2].fail();
  assert.equal(component.data.renderedSrc, '');
  assert.equal(component.data.failed, false);
  definition.lifetimes.ready.call(component);
  exports[3].fail();
  assert.equal(component.data.failed, true, '当前导出失败须保留角色保底');
  component.data.src = '/uploaded.png';
  definition.observers.src.call(component, component.data.src);
  assert.equal(component.data.preset, false);
  assert.equal(component.data.failed, false);
  assert.equal(exports.length, 4, '上传形象继续使用原图片入口');
});
