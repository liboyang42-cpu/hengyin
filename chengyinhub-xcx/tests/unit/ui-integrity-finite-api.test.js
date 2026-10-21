const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const { scanU1 } = require('../lint/ui-integrity');
function scan(t, source) {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'finite-api-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  const file=path.join(root,'index.js');fs.writeFileSync(file,source);
  return scanU1([file],new Set(['/api/present','/api/other']),new Map());
}
const WRAP="function req(url){getApp().sendRequest({url})}";
test('有限API不以任一字面量掩盖条件里的未知分支',t=>{
  assert.ok(scan(t,WRAP+"req(flag?'/api/present':unknownUrl);").violations.length);
  assert.equal(scan(t,WRAP+"req(flag?'/api/present':'/api/other');").violations.length,0);
});
test('有限URL别名顺序重赋未知值后必须判红，合法重赋仍绿',t=>{
  assert.equal(scan(t,WRAP+"let url='/api/present';req(url);url='/api/other';req(url);").violations.length,0);
  assert.ok(scan(t,WRAP+"let url='/api/present';req(url);url=unknownUrl;req(url);").violations.length);
});
test('wrapper别名重新赋未知函数后不能沿用旧wrapper的字面量豁免',t=>{
  assert.equal(scan(t,WRAP+"let call=req;call('/api/present');").violations.length,0);
  assert.ok(scan(t,WRAP+"let call=req;call=unknownRequest;call('/api/present');").violations.length);
  assert.ok(scan(t,WRAP+"req=unknownRequest;req('/api/present');").violations.length);
});
test('有限API局部shadow和同名wrapper按真实定义隔离',t=>{
  assert.equal(scan(t,WRAP+"const url='/api/present';function outer(){const url='/api/other';req(url)}req(url);").violations.length,0);
  assert.ok(scan(t,WRAP+"const url='/api/present';function outer(url){req(url)}req(url);").violations.length);
  assert.ok(scan(t,"function one(){function req(url){getApp().sendRequest({url})}req(unknownUrl)}function two(){function req(url){getApp().sendRequest({url})}req('/api/present')}").violations.length);
});
test('闭包捕获必须跟踪定义所有权和潜在重赋，不能冻结声明处旧值',t=>{
  assert.equal(scan(t,WRAP+"const url='/api/present';function later(){req(url)}later();").violations.length,0);
  assert.ok(scan(t,WRAP+"let url='/api/present';function later(){req(url)}url=unknownUrl;later();").violations.length);
  assert.ok(scan(t,WRAP+"let call=req;function later(){call('/api/present')}call=unknownRequest;later();").violations.length);
});
test('直接请求有限别名可证明，未知来源与遗漏后端映射仍红',t=>{
  assert.equal(scan(t,"const url='/api/present';getApp().sendRequest({url});").violations.length,0);
  assert.ok(scan(t,"let url='/api/present';url=unknownUrl;getApp().sendRequest({url});").violations.length);
  assert.ok(scan(t,WRAP+"const url='/api/missing';req(url);").violations.length);
});

test('别名捕获的是当时的定义与值，后续修改源绑定不改写已有别名',t=>{
  assert.equal(scan(t,WRAP+"let url='/api/present';const alias=url;url=unknownUrl;req(alias);").violations.length,0);
  assert.equal(scan(t,WRAP+"const call=req;req=unknownRequest;call('/api/present');").violations.length,0);
  assert.ok(scan(t,WRAP+"let url='/api/present';const read=()=>url;url=unknownUrl;req(read());").violations.length);
});
test('块级shadow与不确定分支写入保守判红，不把其它定义的常量借过来',t=>{
  assert.ok(scan(t,WRAP+"const url='/api/present';{let url=unknownUrl;req(url)}").violations.length);
  assert.ok(scan(t,WRAP+"let url='/api/present';if(flag)url=unknownUrl;req(url);").violations.length);
  assert.ok(scan(t,WRAP+"let call=req;{let call=unknownRequest;call('/api/present')}call(unknownUrl);").violations.length);
});

test('wrapper路径形参被改写后不能用调用处字面量放行',t=>{
  assert.ok(scan(t,"function req(url){url=unknownUrl;getApp().sendRequest({url})}req('/api/present');").violations.length);
  assert.ok(scan(t,WRAP+"var url=unknownUrl;function later(){req(url)}later();var url='/api/present';").violations.length);
  assert.equal(scan(t,WRAP+"var url='/api/present';req(url);var url='/api/other';req(url);").violations.length,0);
});
