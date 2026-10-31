const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const S = require('../../scripts/lib/xcx-scan.js');
const ROOT = path.resolve(__dirname, '../..');
const componentNames = ['play-pause', 'club-topic-merchants', 'club-publish-sheet',
  'club-topic-settings', 'level-progress', 'identity-tag', 'club-topic-groupcode',
  'club-topic-onboarding', 'node', 'scene-topic-customers'];
const selected = componentNames.flatMap((name) => ['js', 'json', 'wxml', 'wxss']
  .map((ext) => `components/cy/${name}/index.${ext}`)).concat([
  'images/home-banner-2.jpg', 'images/home-route-free-placeholder.jpg',
  'images/group1.png', 'images/icon_air.png',
  'images/publish-sheet-template.svg', 'images/publish-sheet-topic.svg',
  'images/publish-sheet-activity.svg', 'images/d_ok.png', 'images/publish-sheet-lock.svg',
  'components/cy/icon/spec.json', 'images/publish-quick/README.md', '.gitignore',
  'project.private.config.json', 'project.config.json', 'pages/publish/utils/publish/guide-progress.js',
]);
const config = JSON.parse(fs.readFileSync(path.join(ROOT, 'project.config.json'), 'utf8'));
const ignore = config.packOptions.ignore;
const fileIgnores = new Set(ignore.filter((x) => x.type === 'file').map((x) => x.value));
const folders = ignore.filter((x) => x.type === 'folder').map((x) => x.value.replace(/\/$/, ''));
function excluded(file) {
  return fileIgnores.has(file) || folders.some((dir) => file === dir || file.startsWith(dir + '/'));
}
function localPath(file, target) {
  if (typeof target !== 'string' || !target || /^[a-z][\w+.-]*:/i.test(target)) return null;
  return path.posix.normalize(target.startsWith('/') ? target.slice(1)
    : path.posix.join(path.posix.dirname(file), target));
}
function walkAst(node, visit) {
  if (!node || typeof node !== 'object') return;
  visit(node);
  for (const value of Object.values(node)) {
    if (Array.isArray(value)) value.forEach((child) => walkAst(child, visit));
    else if (value && typeof value === 'object') walkAst(value, visit);
  }
}
function prefix(node) {
  if (node.type === 'Literal' && typeof node.value === 'string') return { value: node.value, full: true };
  if (node.type === 'TemplateLiteral') return { value: node.quasis[0].value.cooked || '', full: node.expressions.length === 0 };
  if (node.type === 'BinaryExpression' && node.operator === '+') {
    const left = prefix(node.left);
    if (!left.full) return left;
    const right = prefix(node.right);
    return { value: left.value + right.value, full: right.full };
  }
  return { value: '', full: false };
}
// Only a reference guard for reviewed exclusions; it does not decide that other files are dead.
function violations(file, source, targets = fileIgnores) {
  const problems = [];
  function ref(value, kind, extensions = ['']) {
    const local = localPath(file, value);
    if (local && extensions.some((ext) => targets.has(local + ext))) problems.push(kind + ':' + local);
  }
  function image(value) {
    if (typeof value !== 'string') return;
    const normalized = (value.startsWith('images/') ? value : localPath(file, value) || '').split(/[?#]/)[0];
    if (!normalized.startsWith('images/')) return;
    if ([...targets].some((x) => x.startsWith(normalized))) problems.push('image-or-dynamic-prefix:' + normalized);
  }
  function ast(sourceText) {
    let parsed;
    try { parsed = S.parseJavaScript(sourceText); } catch (error) {
      error.message = file + ': ' + error.message;
      throw error;
    }
    walkAst(parsed, (node) => {
      if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.callee.name === 'require') {
        const arg = node.arguments.length >= 1 && prefix(node.arguments[0]);
        if (!arg || !arg.full) problems.push('dynamic-require-needs-review');
        else ref(arg.value, 'require', ['', '.js', '.json', '/index.js']);
      }
      image(prefix(node).value);
    });
  }
  if (/\.(js|wxs)$/.test(file)) ast(source);
  if (file.endsWith('.json')) {
    const json = JSON.parse(source);
    function values(value, visit) {
      if (typeof value === 'string') visit(value);
      else if (value && typeof value === 'object') Object.values(value).forEach((x) => values(x, visit));
    }
    values(json.usingComponents, (value) => ref(value, 'usingComponents', ['', '.json']));
    values(json.componentGenerics, (value) => ref(value, 'componentGenerics', ['', '.json']));
    values(json, image);
  }
  if (file.endsWith('.wxml')) {
    const text = S.maskWxmlComments(source);
    for (const tag of S.scanTags(text)) {
      const raw = text.slice(tag.start, tag.end + 1);
      for (const attr of raw.matchAll(/([\w:-]+)\s*=\s*(["'])([\s\S]*?)\2/g)) {
        const [name, value] = [attr[1], attr[3]];
        if (name === 'src' && ['include', 'import', 'wxs'].includes(tag.name)) ref(value, 'wxml-' + tag.name);
        if (name.startsWith('generic:')) ref(value, 'wxml-generic', ['', '.json']);
        image(value);
        for (const expr of value.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
          const shorthand = name === 'data' && /(?:^\s*\.\.\.|(?:^|,)\s*[\w$]+\s*:)/.test(expr[1]);
          ast(shorthand ? '({' + expr[1] + '})' : '(' + expr[1] + ')');
        }
      }
    }
  }
  if (file.endsWith('.wxss')) {
    const text = source.replace(/\/\*[\s\S]*?\*\//g, '');
    for (const match of text.matchAll(/@import\s+(?:url\(\s*)?(["'])([^"']+)\1/g)) ref(match[2], 'wxss-import');
    for (const match of text.matchAll(/@import\s+url\(\s*([^\s"')]+)\s*\)/g)) ref(match[1], 'wxss-import');
    for (const match of text.matchAll(/url\(\s*(["']?)([^"')]+)\1\s*\)/g)) image(match[2]);
  }
  return [...new Set(problems)];
}
function runtimeFiles(directory = ROOT, rel = '') {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const file = rel ? rel + '/' + entry.name : entry.name;
    if (entry.name.startsWith('.') || excluded(file)) return [];
    if (entry.isDirectory()) return runtimeFiles(path.join(directory, entry.name), file);
    return /\.(js|wxs|json|wxml|wxss)$/.test(file) ? [file] : [];
  });
}

test('the reviewed exclusions remain exact files, with source files retained', () => {
  assert.equal(selected.length, 55);
  for (const file of selected) {
    assert.ok(fileIgnores.has(file), 'missing exact exclusion: ' + file);
    // This IDE-only optional file need not exist on a clean CI checkout.
    if (file !== 'project.private.config.json') assert.ok(fs.statSync(path.join(ROOT, file)).isFile(), file);
  }
});

test('no packaged runtime source references an excluded file or image prefix', () => {
  const files = runtimeFiles();
  assert.ok(files.some((file) => file === 'app.js'));
  const problems = files.flatMap((file) => violations(file, fs.readFileSync(path.join(ROOT, file), 'utf8'))
    .map((problem) => file + ':' + problem));
  assert.deepEqual(problems, []);
});

test('new JS module references and static or dynamic image prefixes fail in memory', () => {
  const targets = new Set(['utils/retired.js', 'images/old-card.svg']);
  for (const source of ["require('../utils/retired')", "require('../utils/retired.js')",
    "const src = '/images/old-card.svg'", "const src = '/images/' + name + '.svg'",
    "const src = `/images/${name}.svg`", "const src = '../images/old-card.svg'", "require(variable)",
    "require('../utils/retired', () => {}, () => {})"]) {
    assert.ok(violations('pages/test.js', source, targets).length, source);
  }
  assert.deepEqual(violations('pages/test.js', "// require('../utils/retired')\nconst src = 'https://example.invalid/images/old-card.svg'", targets), []);
  assert.deepEqual(violations('pages/test.js', "require('../utils/live', () => {}, () => {})", targets), []);
});

test('new component, generic, template, WXS and stylesheet references fail in memory', () => {
  const targets = new Set(['components/retired/index.json', 'parts/old.wxml', 'parts/old.wxs', 'style/old.wxss', 'images/old-card.svg']);
  const cases = [
    ['pages/test.json', '{"usingComponents":{"old":"/components/retired/index"}}'],
    ['pages/test.json', '{"componentGenerics":{"slot":{"default":"/components/retired/index"}}}'],
    ['pages/test.wxml', '<include src="/parts/old.wxml"/>'],
    ['pages/test.wxml', '<import src="/parts/old.wxml"/>'],
    ['pages/test.wxml', '<wxs src="/parts/old.wxs" module="old"/>'],
    ['pages/test.wxml', '<host generic:slot="/components/retired/index"/>'],
    ['pages/test.wxml', '<image src="{{\'/images/\' + name + \'.svg\'}}"/>'],
    ['pages/test.wxss', '@import "/style/old.wxss";'],
    ['pages/test.wxss', '@import url(/style/old.wxss);'],
    ['pages/test.wxss', '.x{background:url("/images/old-card.svg")}'],
  ];
  for (const [file, source] of cases) assert.ok(violations(file, source, targets).length, source);
  assert.deepEqual(violations('pages/test.wxml', '<!-- <include src="/parts/old.wxml"/> -->', targets), []);
});

test('seven dynamically selected interaction covers and the MIT license remain packaged', () => {
  const { gameTemplateCover } = require('../../utils/template-display.js');
  const covers = { '闭眼味觉师': 'blind-taste', '沉默点单': 'silent-order', '便利店调酒师': 'diy-drink',
    '治愈音乐角': 'music-corner', '午夜电台': 'midnight-radio', '低碳行动': 'low-carbon', '今日城市签': 'daily-city-sign' };
  for (const [title, name] of Object.entries(covers)) {
    const file = 'images/interaction-templates/' + name + '.svg';
    assert.equal(gameTemplateCover({ title }), '/' + file);
    assert.ok(!excluded(file), file);
    assert.ok(fs.statSync(path.join(ROOT, file)).isFile());
  }
  const license = 'images/publish-quick/LICENSE';
  assert.ok(!excluded(license));
  assert.match(fs.readFileSync(path.join(ROOT, license), 'utf8'), /MIT License[\s\S]*Permission is hereby granted/);
});
