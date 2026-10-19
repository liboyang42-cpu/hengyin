'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const gate = require('../lint/ui-integrity');

function fixture(t, sources) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ui-route-controls-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return Object.entries(sources).map(([name, source]) => {
    const file = path.join(root, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, source);
    return file;
  });
}
function scan(t, source, routes = ['/api/items/read']) {
  return gate.scanU1(fixture(t, { 'index.js': source }), new Set(routes)).violations;
}

test('Spring class/method value and path arrays compose relative, empty and slash paths', t => {
  const [java] = fixture(t, { 'chengyinhub-admin/src/main/java/Example.java': `
    @RequestMapping(path = {"/api/items", "/api/other"}, produces = "/api/ghost")
    public class Example {
      @GetMapping(value = {"read", "/detail"}, produces = "/api/fake") public void read() {}
      @PostMapping public void create() {}
      @GetMapping(produces = "/api/never") public void root() {}
      @GetMapping(value = UNKNOWN) public void unknown() {}
      @GetMapping(value = {"/partial", UNKNOWN}) public void partial() {}
    }` });
  const routes = gate.collectBackendRoutes(path.resolve(java, '../../../../../'));
  assert.deepEqual([...routes].sort(), ['/api/items', '/api/items/detail', '/api/items/read',
    '/api/other', '/api/other/detail', '/api/other/read']);
  assert.equal(gate.routeMatches('/api/items/missing', '/api/items/read'), false);
});

test('unresolved Spring class prefixes cannot become unprefixed method routes', t => {
  const [java] = fixture(t, { 'chengyinhub-admin/src/main/java/Unknown.java':
    '@RequestMapping(UNKNOWN) class Unknown { @GetMapping("/api/pretend") public void read() {} }' });
  assert.deepEqual([...gate.collectBackendRoutes(path.resolve(java, '../../../../../'))], []);
});

test('finite const chains and local function arguments are checked against exact backend paths', t => {
  const good = "const ROOT='/api/items/'; const URL=ROOT+'read'; function req(url){app.sendRequest({url})} req(URL);";
  assert.deepEqual(scan(t, good), []);
  assert.ok(scan(t, good.replace("+'read'", "+'missing'")).length);
  assert.ok(scan(t, "const URL='/api/items/read'; function run(URL){app.sendRequest({url:URL})} run(getRuntimeRoute());").length);
  assert.ok(scan(t, "let URL='/api/items/read'; URL=getRuntimeRoute(); app.sendRequest({url:URL});").length);
  assert.ok(scan(t, "const URL='/api/items/read'; app.sendRequest({url:URL,...getRuntimeOptions()});").length);
  assert.ok(scan(t, "const URL='/api/items/read'; app.sendRequest({url:URL,url:getRuntimeRoute()});").length);
});

test('unknown, cyclic and multilevel unknown sources never satisfy a request', t => {
  for (const source of [
    'function req(url){app.sendRequest({url})} req(getRuntimeRoute());',
    "function a(url){return url} function b(url){return a(url)} app.sendRequest({url:b(getRuntimeRoute())});",
    "const A=B; const B=A; app.sendRequest({url:A});",
    "const A=flag?A:'/api/items/read'; app.sendRequest({url:A});",
    "const ROOT='/api/items/'; function send(action){app.sendRequest({url:ROOT+action})} send(getRuntimeAction());",
    "function req(url){app.sendRequest({url})} req=runtime; req('/api/items/read');",
  ]) assert.ok(scan(t, source).length, source);
});

test('finite exported factory preserves prefix/action pairs; missing mappings still fail', t => {
  const sources = {
    'factory.js': `function factory(prefix, actions) {
      function send(action){ app.sendRequest({url:prefix+'/'+action}) }
      actions.forEach(function(action){send(action)});
    } module.exports={factory};`,
    'index.js': `const factory=require('./factory').factory; factory('/api/a',['read']); factory('/api/b',['write']);`,
  };
  const files = fixture(t, sources), routes = new Set(['/api/a/read', '/api/b/write']);
  assert.deepEqual(gate.scanU1(files, routes).violations, []);
  assert.ok(gate.scanU1(files, new Set(['/api/a/read'])).violations.length);
});

test('unexported, unmounted or runtime factory inputs cannot suppress a prefix violation', t => {
  for (const [body, call] of [
    ['module.exports={};', "factory('/api/a',['read']);"],
    ['module.exports={factory};', ''],
    ['module.exports={factory};', "factory(getRuntimeRoute(),['read']);"],
    ['module.exports={factory};', "factory('/api/a',getRuntimeActions());"],
    ['module.exports={factory}; module.exports.factory=runtime;', "factory('/api/a',['read']);"],
    ['module.exports={factory};', "const actions=['read']; actions.push(getRuntimeAction());factory('/api/a',actions);"],
  ]) {
    const files = fixture(t, {
      'factory.js': `function factory(prefix,actions){function send(action){app.sendRequest({url:prefix+'/'+action})}
        actions.forEach(function(action){send(action)});} ${body}`,
      'index.js': `const factory=require('./factory').factory;${call}`,
    });
    assert.ok(gate.scanU1(files, new Set(['/api/a/read'])).violations.length);
  }
});

test('existing profile navigation passes; unregistered and deferred targets fail', t => {
  const profile = path.join(gate.XCX_ROOT, 'subpackageMember/components/cy/profile/index.js');
  assert.deepEqual(gate.scanLegacy([profile], []).errors, []);
  for (const source of [
    "wx.navigateTo({url:'/subpackageTools/index/missing'});",
    "function go(feature){wx.navigateTo({url:'/subpackageTools/'+feature+'/index'})} go('missing');",
    "function go(feature){wx.navigateTo({url:'/subpackageTools/'+feature+'/index'})} go(getRuntimeFeature());",
  ]) assert.ok(gate.scanLegacy(fixture(t, { 'nav.js': source }), []).errors.length);
});

test('replayed fields require an immediate rejection guard and all writable sources', t => {
  const source = `function factory(){let pending=null; const saved=storage.get();
    if(saved){if(!saved.operation || saved.operation.path!=='') throw Error('bad'); pending=JSON.parse(JSON.stringify(saved.operation));}
    function send(path){const operation={path};pending=operation;app.sendRequest({url:'/api/items'+path});}
    function retry(){send(pending.path)} send('/read');retry();}`;
  assert.deepEqual(scan(t, source, ['/api/items', '/api/items/read']), []);
  assert.ok(scan(t, source.replace("saved.operation.path!==''", 'false'), ['/api/items', '/api/items/read']).length);
  assert.ok(scan(t, source.replace('function retry()', "pending.path=getRuntimeRoute();function retry()"), ['/api/items', '/api/items/read']).length);
});

// Independent review repros: expected rejection must pass through the real scanner.

test("independent review: nested_return", t => {
 const files=fixture(t, {'index.js':"function route(){if(flag)return getRuntimeRoute();return '/api/items/read'} app.sendRequest({url:route()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: external_object_mutation", t => {
 const files=fixture(t, {'index.js':"const options={url:'/api/items/read'}; mutateAtRuntime(options);app.sendRequest({url:options.url});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: alias_object_mutation", t => {
 const files=fixture(t, {'index.js':"const options={url:'/api/items/read'};const alias=options;alias.url=getRuntimeRoute();app.sendRequest({url:options.url});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: duplicate_url", t => {
 const files=fixture(t, {'index.js':"app.sendRequest({url:'/api/items/read',url:getRuntimeRoute()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: runtime_spread", t => {
 const files=fixture(t, {'index.js':"app.sendRequest({url:'/api/items/read',...getRuntimeOptions()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: unencoded_dynamic", t => {
 const files=fixture(t, {'index.js':"const prefix='/api/items/';app.sendRequest({url:prefix+getRuntimeRoute()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read','/api/items/{id}'])).violations;
 assert.ok(errors.length);
});

test("independent review: dynamic_query_tail", t => {
 const files=fixture(t, {'index.js':"const prefix='/api/items/read?x=';app.sendRequest({url:prefix+getRuntimeValue()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.deepEqual(errors,[]);
});

test("independent review: unknown_method_call", t => {
 const files=fixture(t, {'index.js':"const host={route(){return '/api/items/read'}};replaceAtRuntime(host);app.sendRequest({url:host.route()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: nested_return_known_bad", t => {
 const files=fixture(t, {'index.js':"function route(){if(flag)return '/api/items/missing';return '/api/items/read'} app.sendRequest({url:route()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: mutable_identifier", t => {
 const files=fixture(t, {'index.js':"let URL='/api/items/read';URL=getRuntimeRoute();app.sendRequest({url:URL});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: cyclic_identifier", t => {
 const files=fixture(t, {'index.js':"const A=B;const B=A;app.sendRequest({url:A});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: cyclic_member", t => {
 const files=fixture(t, {'index.js':"const A={url:flag?A.url:'/api/items/read'};app.sendRequest({url:A.url});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: missing_segment", t => {
 const files=fixture(t, {'index.js':"const prefix='/api/items/r';app.sendRequest({url:prefix+getRuntimeRoute()});"});
 const errors=gate.scanU1(files,new Set(['/api/items/read'])).violations;
 assert.ok(errors.length);
});

test("independent review: nav_const", t => {
 const files=fixture(t, {'index.js':"const URL='/not-registered/page';wx.navigateTo({url:URL});"});
 const errors=gate.scanLegacy(files,[]).errors;
 assert.ok(errors.length);
});

test("independent review: nav_parameter", t => {
 const files=fixture(t, {'index.js':"function go(url){wx.navigateTo({url})}go('/not-registered/page');"});
 const errors=gate.scanLegacy(files,[]).errors;
 assert.ok(errors.length);
});

test("independent review: nav_unknown", t => {
 const files=fixture(t, {'index.js':"wx.navigateTo({url:getRuntimeRoute()});"});
 const errors=gate.scanLegacy(files,[]).errors;
 assert.ok(errors.length);
});

test("independent review: nested_unknown_export", t => {
 const files=fixture(t, {'factory.js':"function factory(prefix,actions){function send(action){app.sendRequest({url:prefix+'/'+action})}actions.forEach(function(action){send(action)});} module.exports={factory}; Object.assign(module.exports,getRuntimeExports());", 'index.js':"const factory=require('./factory').factory;factory('/api/items',['read']);"});
 assert.ok(gate.scanU1(files,new Set(['/api/items/read'])).violations.length);
});

test("independent review: duplicate_export", t => {
 const files=fixture(t, {'factory.js':"function factory(prefix,actions){function send(action){app.sendRequest({url:prefix+'/'+action})}actions.forEach(function(action){send(action)});} module.exports={factory,factory:getRuntimeFactory()};", 'index.js':"const factory=require('./factory').factory;factory('/api/items',['read']);"});
 assert.ok(gate.scanU1(files,new Set(['/api/items/read'])).violations.length);
});

test("independent review: mutated_export", t => {
 const files=fixture(t, {'factory.js':"function factory(prefix,actions){function send(action){app.sendRequest({url:prefix+'/'+action})}actions.forEach(function(action){send(action)});} module.exports={factory}; module.exports.factory=getRuntimeFactory();", 'index.js':"const factory=require('./factory').factory;factory('/api/items',['read']);"});
 assert.ok(gate.scanU1(files,new Set(['/api/items/read'])).violations.length);
});

test('recovery audit: deleted, escaped, duplicate and locally mutated navigation objects reject', t => {
  for (const source of [
    "const options={url:'/pages/play/index'};delete options.url;wx.navigateTo({url:options.url});",
    "const options={url:'/pages/play/index'};const alias=options;mutateAtRuntime(alias);wx.navigateTo({url:options.url});",
    "const options={url:'/pages/play/index'};function mutate(o){o.url=runtimeRoute()}mutate(options);wx.navigateTo({url:options.url});",
    "const options={url:'/pages/play/index',url:runtimeRoute()};wx.navigateTo({url:options.url});",
    "Page({data:{url:'/pages/play/index'},tap(){const d=this.data;d.url=getRuntimeRoute();wx.navigateTo({url:this.data.url})}});",
    "Page({data:{url:'/pages/play/index'},tap(){delete this.data.url;wx.navigateTo({url:this.data.url})}});",
    "Page({data:{url:'/pages/play/index'},tap(){const that=this;that.setData({url:runtimeRoute()});wx.navigateTo({url:this.data.url})}});",
    "Page({data:{url:'/pages/play/index'},tap(){this.setData({url:'/pages/play/index',...runtimeState()});wx.navigateTo({url:this.data.url})}});",
    "const options={url:'/pages/play/index'};options.mutate();wx.navigateTo({url:options.url});",
  ]) assert.ok(gate.scanLegacy(fixture(t, { 'nav.js': source }), []).errors.length, source);
});

test('finite WXML datasets, unchanged var/let literals and query helpers keep positive navigation', t => {
  const files = fixture(t, {
    'index.js': "const ROUTE='/pages/play/index';Page({data:{actions:[{url:ROUTE}]},tap(e){wx.navigateTo({url:e.currentTarget.dataset.url})}});",
    'index.wxml': '<view wx:for="{{actions}}" bindtap="tap" data-url="{{item.url}}"/>',
  });
  assert.deepEqual(gate.scanLegacy(files.filter(file => file.endsWith('.js')), []).errors, []);
  for (const source of [
    "var route='/pages/play/index';wx.navigateTo({url:route});",
    "let route='/pages/play/index?id='+value;route+='&scope='+getRuntimeValue();wx.navigateTo({url:route});",
    "function route(id){if(!id)return '';let url='/pages/play/index?id='+id;url+='&tab=about';return url}const url=route(runtimeId);if(url)wx.navigateTo({url});",
  ]) assert.deepEqual(gate.scanLegacy(fixture(t, { 'nav.js': source }), []).errors, [], source);
  const mutated = files.find(file => file.endsWith('.js'));
  fs.appendFileSync(mutated, "Page({tap(){this.setData({actions:runtimeActions()})}});");
  assert.ok(gate.scanLegacy([mutated], []).errors.length);
});

test('recovery audit: literal fragments never hide unknown path-producing calls', t => {
  for (const source of [
    "app.sendRequest({url:'/api/items/'+getRuntimeRoute()+'/read'});",
    "function route(){return runtimeRoute()}app.sendRequest({url:'/api/items/'+route()+'/read'});",
  ]) assert.ok(scan(t, source, ['/api/items/{id}/read']).length, source);
});

test('recovery audit: export aliases, deletes and unknown overwrites reject', t => {
  for (const change of [
    'const alias=module.exports;alias.factory=runtimeFactory();',
    'delete module.exports.factory;',
    'mutateAtRuntime(module.exports);',
  ]) {
    const files = fixture(t, {
      'factory.js': "function factory(prefix,actions){function send(action){app.sendRequest({url:prefix+'/'+action})}actions.forEach(function(action){send(action)});}module.exports={factory};" + change,
      'index.js': "const factory=require('./factory').factory;factory('/api/items',['read']);",
    });
    assert.ok(gate.scanU1(files, new Set(['/api/items/read'])).violations.length, change);
  }
});

test('registered navigation proof requires exact guard, immutable export and app.json parity', t => {
  const boundary = path.join(gate.XCX_ROOT, 'utils/registered-navigation.js');
  const source = fs.readFileSync(boundary, 'utf8');
  const files = fixture(t, { 'nav.js': '' });
  const relative = path.relative(path.dirname(files[0]), boundary).split(path.sep).join('/');
  const caller = `const {navigateRegistered}=require('${relative}');navigateRegistered({url:runtimeRoute()});`;
  fs.writeFileSync(files[0], caller);
  assert.deepEqual(gate.scanLegacy(files, []).errors, []);
  for (const body of [
    source.replace('!REGISTERED_PATHS.includes(target)', 'false'),
    source.replace('"pages/index/index"', '"missing/index"'),
    source + '\nmodule.exports.navigateRegistered=runtimeNavigation;',
    source + '\nconst alias=module.exports;alias.navigateRegistered=runtimeNavigation;',
    source.replace('module.exports = { navigateRegistered };', 'module.exports = { navigateRegistered, navigateRegistered: runtimeNavigation };'),
  ]) {
    const originalRead = fs.readFileSync;
    try {
      fs.readFileSync = function(file, ...args) { return path.resolve(String(file)) === boundary ? body : originalRead.call(this, file, ...args); };
      assert.ok(gate.scanLegacy(files, []).errors.length);
    } finally { fs.readFileSync = originalRead; }
  }
  for (const value of [
    "'/missing/index'",
    "'/pages/play/index',url:runtimeRoute()",
    "'/pages/play/index',...runtimeOptions()",
  ]) {
    fs.writeFileSync(files[0], caller.replace('runtimeRoute()', value));
    assert.ok(gate.scanLegacy(files, []).errors.length, value);
  }
  fs.writeFileSync(files[0], `let go=require('${relative}').navigateRegistered;go=runtimeNavigation;go({url:'/pages/play/index'});`);
  assert.ok(gate.scanLegacy(files, []).errors.length);
});

test('independent recovery review rejects whole-state replacement/escape and escaped request callbacks', t => {
  for (const source of [
    "Page({data:{url:'/pages/play/index'},onLoad(){this.data={url:getRuntimeRoute()};wx.navigateTo({url:this.data.url});}});",
    "Page({data:{url:'/pages/play/index'},onLoad(){mutateAtRuntime(this.data);wx.navigateTo({url:this.data.url});}});",
  ]) assert.ok(gate.scanLegacy(fixture(t, { 'nav.js': source }), []).errors.length, source);
  assert.ok(scan(t, "function req(url){app.sendRequest({url});} req('/api/items/read'); const opts={callback:req};registerRuntimeCallback(opts);").length);
});
