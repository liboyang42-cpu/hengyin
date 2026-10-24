const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const S = require('../../scripts/lib/xcx-scan');
const { lintRepo } = require('../../scripts/wxml-handler-lint');
function fixture(t, factory, page, bindings) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'xcx-factory-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root,'factory.js'),factory);fs.writeFileSync(path.join(root,'index.js'),page);fs.writeFileSync(path.join(root,'index.wxml'),bindings);return root;
}
test('真实导出factory只合并common与实际methods，源行/override赢家准确',t=>{
  const root=fixture(t, `function runtime(kind, spec) {\nreturn Object.assign({ onField() {}, onTap() {} }, spec.methods || {});\n}\nconst unmounted = { ghost() {} };\nmodule.exports = { runtime };`, `const {runtime} = require('./factory');\nPage(runtime('x', { methods: { onTap() {}, save() {} }, notMethods: { ghost() {} } }));`, '<view bindtap="save"/><view bind:input="onField"/><view bindtap="ghost"/>');
  const file=path.join(root,'index.js'),entries=S.extractOwnHandlerEntries(file),names=S.extractHandlerNames(file);assert.ok(names.includes('onField'));assert.ok(names.includes('save'));assert.ok(!names.includes('ghost'));assert.equal(entries.find(x=>x.name==='onField').file,path.join(root,'factory.js'));assert.equal(entries.find(x=>x.name==='onTap').file,file);assert.equal(entries.find(x=>x.name==='onTap').line,2);assert.deepEqual(lintRepo(root).violations.map(x=>x.handler),['ghost']);
});
test('缺失导出/未知factory/非方法覆盖与同名未挂载仍判红，不执行模块',t=>{
  const root=fixture(t, `throw new Error('must never execute');\nfunction page(config){return Object.assign({alive(){}},config.methods);}\nconst unrelated={missing(){}};module.exports={page};`, `const {page}=require('./factory');Page(page({methods:{alive:0}}));`, '<view bindtap="alive"/><view bindtap="missing"/>');assert.deepEqual(lintRepo(root).violations.map(x=>x.handler),['alive','missing']);
  fs.writeFileSync(path.join(root,'index.js'), `const {notExported}=require('./factory');Page(notExported({methods:{missing(){}}}));`);assert.ok(lintRepo(root).violations.some(x=>x.handler==='missing'));
});
test('单参数Page工厂与不同别名require能识别；只注册实际返回字段',t=>{
  const root=fixture(t, `function page(config){const common={onRetry(){}};return Object.assign(common,config.methods)}module.exports={page};`, `const {page:build}=require('./factory');Page(build({methods:{refresh(){}}}));`, '<view bindtap="onRetry"/><view bindtap="refresh"/><view bindtap="missing"/>');assert.deepEqual(lintRepo(root).violations.map(x=>x.handler),['missing']);
});
test('U3只合并同一实际shared定义的已挂载消费者，完全无消费仍红',t=>{
  const {scanU3}=require('../lint/ui-integrity');
  const root=fixture(t,`function page(config){return Object.assign({onField(){},onMissing(){}},config.methods)}module.exports={page};`, `const {page}=require('./factory');Page(page({methods:{}}));`, '<view bind:input="onField"/>');
  const second=path.join(root,'second.js');fs.writeFileSync(second,`const {page}=require('./factory');Page(page({methods:{}}));`);fs.writeFileSync(path.join(root,'second.wxml'),'<view/>');
  const result=scanU3([path.join(root,'index.js'),second],new Map());assert.ok(result.violations.every(x=>!x.message.startsWith('onField ')));assert.ok(result.violations.some(x=>x.message.startsWith('onMissing ')));
  fs.writeFileSync(second,`const {page}=require('./factory');Page(page({methods:{onField(){}}}));`);assert.ok(scanU3([path.join(root,'index.js'),second],new Map()).violations.some(x=>x.message.startsWith('onField ')));
});

test('未知methods可能覆盖公共函数时保守判红',t=>{
  const root=fixture(t,`function page(config){return Object.assign({onTap(){}},config.methods)}module.exports={page};`, `const {page}=require('./factory');Page(page({methods:unknownMethods()}));`, '<view bindtap="onTap"/>');assert.deepEqual(lintRepo(root).violations.map(x=>x.handler),['onTap']);
});

test('条件return不是受支持静态合并形态，不假定实际走末尾分支',t=>{
  const root=fixture(t,`function page(config){if(config.disabled)return {};return Object.assign({onTap(){}},config.methods)}module.exports={page};`, `const {page}=require('./factory');Page(page({disabled:true,methods:{}}));`, '<view bindtap="onTap"/>');assert.deepEqual(lintRepo(root).violations.map(x=>x.handler),['onTap']);
});

test('U3同文件不同factory的同名handler不得互相豁免',t=>{
  const {scanU3}=require('../lint/ui-integrity');
  const root=fixture(t,`function one(config){return Object.assign({onField(){}},config.methods)} function two(config){return Object.assign({onField(){}},config.methods)} module.exports={one,two};`,
    `const {one}=require('./factory');Page(one({methods:{}}));`, '<view bind:input="onField"/>');
  const second=path.join(root,'second.js');fs.writeFileSync(second,`const {two}=require('./factory');Page(two({methods:{}}));`);fs.writeFileSync(path.join(root,'second.wxml'),'<view/>');
  assert.equal(scanU3([path.join(root,'index.js'),second],new Map()).violations.filter(x=>x.message.startsWith('onField ')).length,1);
});
test('U3不同factory返回同一实际定义时仍可共享真实绑定',t=>{
  const {scanU3}=require('../lint/ui-integrity');
  const root=fixture(t,`const common={onField(){}};function one(config){return Object.assign(common,config.methods)}function two(config){return Object.assign(common,config.methods)}module.exports={one,two};`,
    `const {one}=require('./factory');Page(one({methods:{}}));`, '<view bind:input="onField"/>');
  const second=path.join(root,'second.js');fs.writeFileSync(second,`const {two}=require('./factory');Page(two({methods:{}}));`);fs.writeFileSync(path.join(root,'second.wxml'),'<view/>');
  assert.equal(scanU3([path.join(root,'index.js'),second],new Map()).violations.length,0);
});
