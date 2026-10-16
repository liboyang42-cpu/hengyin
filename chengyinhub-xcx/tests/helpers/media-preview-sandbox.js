'use strict';
// tests/helpers/media-preview-sandbox.js —— 给 vm 沙箱页面装真 utils/media-preview.js(BL-21)。
// 该模块运行时读全局 wx;用 Node 真 require 装会读不到沙箱里的 wx 桩,
// 所以在传入 wx 的独立上下文里求值源码,预览调用仍打到测试自己的 wx.previewImage。
const fs = require('node:fs');
const path = require('node:path');
const nodeVm = require('node:vm');

const FILE = path.resolve(__dirname, '../../utils/media-preview.js');

function loadMediaPreview(wx) {
  const source = '(function (module, exports) {' + fs.readFileSync(FILE, 'utf8') + '\n})';
  const mod = { exports: {} };
  nodeVm.runInNewContext(source, { wx }, { filename: FILE })(mod, mod.exports);
  return mod.exports;
}

module.exports = { loadMediaPreview };
