// 把 master 的珐琅勋章渲染器(PR #653)原样包成浏览器脚本:shared/enamel.bundle.js → window.ENAMEL / window.GLYPHS。
// 源文件不改;enamel-pin.js 头注释本来就写明「小程序 2D canvas 与浏览器原型共用这一份」。
const fs = require('fs');
const path = require('path');
const DIR = path.resolve(__dirname, '../../../../chengyinhub-xcx/subpackageP3/pages/badge-wall/index');
const g = fs.readFileSync(path.join(DIR, 'glyphs.js'), 'utf8');
const e = fs.readFileSync(path.join(DIR, 'enamel-pin.js'), 'utf8');
const out = '/* 自动生成 —— tools/build-medal.js 从 master subpackageP3/pages/badge-wall/index/{glyphs,enamel-pin}.js 包出,勿手改 */\n(function () {\n' +
  'var GLYPHS_MOD = (function () { var module = { exports: {} }; ' + g + '\n; return module.exports; })();\n' +
  'var ENAMEL = (function () { var module = { exports: {} }; var require = function () { return GLYPHS_MOD; }; ' + e + '\n; return module.exports; })();\n' +
  'window.GLYPHS = GLYPHS_MOD; window.ENAMEL = ENAMEL;\n})();\n';
fs.writeFileSync(path.resolve(__dirname, '../shared/enamel.bundle.js'), out);
console.log('ok', out.length);
