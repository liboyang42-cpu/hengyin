const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
const packages = app.subPackages || app.subpackages || [];
const owner = file => packages.find(p => file.startsWith(p.root + '/'))?.root || 'MAIN';

test('registered routes stay unique and every Tab entry remains in the main package', () => {
  const routes = [...app.pages, ...packages.flatMap(p => p.pages.map(page => p.root + '/' + page))];
  assert.equal(new Set(routes).size, routes.length);
  for (const tab of app.tabBar.list) assert.ok(app.pages.includes(tab.pagePath), tab.pagePath);
  for (const route of ['pages/privacy/index', 'pages/agreement/index', 'pages/deregister/index', 'pages/team/detail/index', 'pages/team/join/index']) {
    assert.ok(routes.includes(route), route);
    assert.notEqual(owner(route), 'MAIN', route);
    for (const suffix of ['.js', '.json', '.wxml', '.wxss']) {
      assert.ok(fs.existsSync(path.join(root, route + suffix)), route + suffix);
    }
  }
});

test('local components exist and cross-package consumers declare asynchronous placeholders', () => {
  function walk(directory) {
    for (const item of fs.readdirSync(directory, { withFileTypes: true })) {
      if (item.name.startsWith('.') || ['tests', 'scripts', 'node_modules', 'artifacts'].includes(item.name)) continue;
      const file = path.join(directory, item.name);
      if (item.isDirectory()) { walk(file); continue; }
      if (!item.name.endsWith('.json')) continue;
      const config = JSON.parse(fs.readFileSync(file, 'utf8'));
      if (!config || typeof config !== 'object' || Array.isArray(config)) continue;
      const source = path.relative(root, file).split(path.sep).join('/');
      for (const [name, value] of Object.entries(config.usingComponents || {})) {
        if (typeof value !== 'string' || !/^[/.]/.test(value)) continue;
        const target = value.startsWith('/') ? path.join(root, value.slice(1)) : path.resolve(path.dirname(file), value);
        assert.ok(fs.existsSync(target + '.js'), `${source}: ${value}`);
        const destination = path.relative(root, target).split(path.sep).join('/');
        const targetOwner = owner(destination);
        if (targetOwner !== 'MAIN' && targetOwner !== owner(source)) {
          assert.ok(config.componentPlaceholder?.[name], `${source}: ${name} crosses into ${targetOwner}`);
        }
      }
    }
  }
  walk(root);
});
