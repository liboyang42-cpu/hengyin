'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const S = require('../../scripts/lib/xcx-scan.js');
const { moves } = require('../../scripts/baselines/mainpkg-leaves.json');
const ROOT = path.resolve(__dirname, '../..');
const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
const config = JSON.parse(fs.readFileSync(path.join(ROOT, 'project.config.json'), 'utf8'));
const roots = (app.subPackages || app.subpackages).map(x => x.root);
const owner = file => roots.find(root => file.startsWith(root + '/')) || 'MAIN';
const oldFiles = new Set(moves.map(x => x.from));
const newFiles = new Set(moves.map(x => x.to));
const images = moves.filter(x => x.from.startsWith('images/'));
const ignoredFiles = new Set(config.packOptions.ignore.filter(x => x.type === 'file').map(x => x.value));
const ignoredFolders = config.packOptions.ignore.filter(x => x.type === 'folder').map(x => x.value.replace(/\/$/, ''));
function packaged(file) {
  return !ignoredFiles.has(file) && !ignoredFolders.some(dir => file === dir || file.startsWith(dir + '/'));
}
function runtimeFiles(dir = ROOT, relative = '') {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = relative ? relative + '/' + entry.name : entry.name;
    if (entry.name.startsWith('.') || !packaged(file)) return [];
    if (entry.isDirectory()) return runtimeFiles(path.join(dir, entry.name), file);
    return /\.(js|wxs|json|wxml|wxss)$/.test(file) ? [file] : [];
  });
}
function local(file, value) {
  if (typeof value !== 'string' || !value || /^[a-z][\w+.-]*:/i.test(value)) return null;
  return path.posix.normalize(value.startsWith('/') ? value.slice(1) : path.posix.join(path.posix.dirname(file), value));
}
function prefix(node) {
  if (!node) return '';
  if (node.type === 'Literal' && typeof node.value === 'string') return node.value;
  if (node.type === 'TemplateLiteral') return node.quasis[0].value.cooked || '';
  if (node.type === 'BinaryExpression' && node.operator === '+') return prefix(node.left);
  return '';
}
function walk(node, visit) {
  if (!node || typeof node !== 'object') return;
  visit(node);
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach(child => walk(child, visit));
    else if (value && typeof value === 'object') walk(value, visit);
  }
}
// Guard the reviewed moves, including references in other subpackages.
// This does not classify any other file as unused.
function violations(file, source) {
  const problems = [];
  function reference(value) {
    const resolved = local(file, value);
    if (!resolved) return;
    const candidates = [resolved, resolved + '.js', resolved + '.json', resolved + '/index.js', resolved + '/index.json'];
    if (candidates.some(candidate => oldFiles.has(candidate))) problems.push('retired path: ' + resolved);
    const target = candidates.find(candidate => newFiles.has(candidate));
    if (target && owner(file) !== owner(target)) problems.push('cross-package leaf: ' + target);
    if (newFiles.has(file) && !candidates.some(candidate => fs.existsSync(path.join(ROOT, candidate)))) {
      problems.push('missing dependency: ' + resolved);
    }
  }
  function image(value) {
    if (!value || /^[a-z][\w+.-]*:/i.test(value)) return;
    const resolved = (value.startsWith('images/') ? value : local(file, value) || '').split(/[?#]/)[0];
    if (!resolved || !/(?:^|\/)images(?:\/|$)/.test(resolved)) return;
    for (const moved of images) {
      if (moved.from.startsWith(resolved)) problems.push('retired image or dynamic prefix: ' + resolved);
      if (moved.to.startsWith(resolved) && owner(file) !== owner(moved.to)) problems.push('cross-package image: ' + resolved);
    }
  }
  function javascript(text) {
    walk(S.parseJavaScript(text), node => {
      if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.callee.name === 'require') {
        const arg = node.arguments[0];
        if (!arg || arg.type !== 'Literal' || typeof arg.value !== 'string') problems.push('dynamic require needs review');
        else reference(arg.value);
      }
      image(prefix(node));
    });
  }
  if (/\.(js|wxs)$/.test(file)) javascript(source);
  if (file.endsWith('.json')) {
    const json = JSON.parse(source);
    Object.values(json.usingComponents || {}).forEach(reference);
    Object.values(json.componentGenerics || {}).forEach(value => { if (value.default) reference(value.default); });
  }
  if (file.endsWith('.wxml')) {
    const text = S.maskWxmlComments(source);
    for (const tag of S.scanTags(text)) {
      for (const attr of text.slice(tag.start, tag.end + 1).matchAll(/([\w:-]+)\s*=\s*(["'])([\s\S]*?)\2/g)) {
        const name = attr[1], value = attr[3];
        if (name === 'src' && ['include', 'import', 'wxs'].includes(tag.name)) reference(value);
        if (name.startsWith('generic:')) reference(value);
        image(value);
        for (const expr of value.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
          const object = name === 'data' && /(?:^\s*\.\.\.|(?:^|,)\s*[\w$]+\s*:)/.test(expr[1]);
          javascript(object ? '({' + expr[1] + '})' : '(' + expr[1] + ')');
        }
      }
    }
  }
  if (file.endsWith('.wxss')) {
    const text = source.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const match of text.matchAll(/@import\s+(?:url\(\s*)?["']([^"']+)["']/g)) reference(match[1]);
    for (const match of text.matchAll(/url\(\s*(["']?)([^"')]+)\1\s*\)/g)) image(match[2]);
  }
  return [...new Set(problems)];
}

test('reviewed leaves remain inside existing subpackages and keep their original source files out of main', () => {
  assert.equal(moves.length, 33);
  for (const moved of moves) {
    assert.equal(owner(moved.from), 'MAIN', moved.from);
    assert.notEqual(owner(moved.to), 'MAIN', moved.to);
    assert.ok(!fs.existsSync(path.join(ROOT, moved.from)), moved.from);
    assert.ok(fs.statSync(path.join(ROOT, moved.to)).isFile(), moved.to);
    assert.ok(packaged(moved.to), moved.to);
  }
});

test('runtime references resolve after relocation without new cross-subpackage leaf dependencies', () => {
  assert.deepEqual(runtimeFiles().flatMap(file =>
    violations(file, fs.readFileSync(path.join(ROOT, file), 'utf8')).map(problem => file + ': ' + problem)), []);
});

test('negative controls reject stale modules, stale WXS, dynamic image paths and cross-package imports', () => {
  const cases = [
    ['subpackageTools/monthly-recap/index.js', "require('../../utils/features/monthly-recap-client')"],
    ['pages/roam/index.js', "require('../../subpackageTools/utils/features/monthly-recap-client')"],
    ['pages/publish/fabu/index.wxml', '<wxs src="/utils/wxs/chapter-title.wxs" module="title"/>'],
    ['pages/test.js', "const src = '/images/' + name + '.png'"],
    ['pages/test.js', "const src = '/subpackageMember/images/icon_set.png'"],
    ['subpackageTools/style/tool-ui.wxss', '@import "/style/retired.wxss";'],
    ['pages/test.json', '{"usingComponents":{"invite":"/subpackageMember/components/cy/coop-invite-sheet/index"}}'],
    ['subpackageMember/components/cy/profile/index.json', '{"usingComponents":{"invite":"/components/cy/coop-invite-sheet/index"}}'],
  ];
  for (const [file, source] of cases) assert.ok(violations(file, source).length, source);
  assert.deepEqual(violations('subpackageTools/monthly-recap/index.js', "require('../utils/features/monthly-recap-client')"), []);
  assert.deepEqual(violations('pages/test.js', "const src = 'https://example.invalid/images/icon_set.png'"), []);
});
