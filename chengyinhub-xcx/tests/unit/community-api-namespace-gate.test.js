const test = require('node:test');
const assert = require('node:assert/strict');
const gate = require('../../../ci/api-existence-gate');
const namespaces = new Set(['/api/v1/community']);
test('only a real Controller namespace const exclusively used for concatenation is omitted', () => {
  const source = "const BASE='/api/v1/community'; send({url:BASE + path}); send({url:'/api/unknown'});";
  const offsets = gate.namespaceDeclarationOffsets(source, namespaces);
  assert.deepEqual([...offsets], [source.indexOf("'/api/v1/community'")]);
  assert.equal(offsets.has(source.indexOf("'/api/unknown'")), false);
});
test('direct namespace requests and unknown namespaces remain checked', () => {
  for (const source of ["const BASE='/api/v1/community'; send({url:BASE});", "send({url:'/api/v1/community'});", "const BASE='/api/unknown'; send({url:BASE + path});", "const BASE='/api/v1/community'; send({url:BASE + path}); send({url:BASE});"]) {
    assert.equal(gate.namespaceDeclarationOffsets(source, namespaces).size, 0, source);
  }
});
test('same-source namespace, real full route and unknown full route retain request literals', () => {
  const source = "const BASE='/api/v1/community'; send({url:BASE + path}); send({url:'/api/v1/community/posts'}); send({url:'/api/v1/community/unknown'});";
  const ignored = gate.namespaceDeclarationOffsets(source, namespaces);
  assert.deepEqual(gate.scanJsStringLiterals(source).filter(x => x.value.startsWith('/api/') && !ignored.has(x.startIndex)).map(x => x.value), ['/api/v1/community/posts', '/api/v1/community/unknown']);
});

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const strict = require('../lint/ui-integrity');
test('real Controller class mapping supplies namespaces; full unknown route remains RED in both scanners', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'community-namespace-'));
  try {
    const java = path.join(root, 'chengyinhub-admin/src/main/java/X.java');
    const js = path.join(root, 'chengyinhub-xcx/page.js');
    fs.mkdirSync(path.dirname(java), {recursive:true}); fs.mkdirSync(path.dirname(js), {recursive:true});
    fs.writeFileSync(java, '@RestController @RequestMapping("/api/v1/community") class X { @GetMapping("/posts") void posts() {} }');
    fs.writeFileSync(js, "const BASE='/api/v1/community'; app.sendRequest({url:BASE + path}); app.sendRequest({url:'/api/v1/community/posts'}); app.sendRequest({url:'/api/v1/community/unknown'});");
    const backend = gate.collectBackendMappings(root);
    assert.deepEqual([...backend.namespacePrefixes], ['/api/v1/community']);
    const calls = gate.collectFrontendCalls(root, backend.namespacePrefixes).calls;
    assert.deepEqual(calls.map(x => x.raw), ['/api/v1/community/posts','/api/v1/community/unknown']);
    assert.equal(gate.analyse(calls, backend.mappings, new Set()).missingStatic.size, 1);
    const result = strict.scanU1([js], new Set(['/api/v1/community/posts']), backend.namespacePrefixes);
    assert.equal(result.violations.filter(x => x.message.includes('无后端 Mapping')).length, 1);
    assert.equal(result.violations.some(x => x.message.includes('运行时变量')), true);
  } finally { fs.rmSync(root, {recursive:true,force:true}); }
});
test('non-url concatenations do not exempt namespace constants', () => {
  assert.equal(gate.namespaceDeclarationOffsets("const BASE='/api/v1/community'; const arbitrary=BASE + suffix;", namespaces).size,0);
});
const closure = [
 ['GET','/guidelines/active'],['POST','/guidelines/ack'],['GET','/capabilities'],['POST','/media/register'],
 ['POST','/posts'],['GET','/posts/17'],['PATCH','/posts/17'],['DELETE','/posts/17'],['POST','/posts/17/publish'],
 ['GET','/drafts'],['GET','/me/bookmarks'],['GET','/me/posts'],['GET','/feeds/LATEST'],['POST','/posts/17/actions'],['DELETE','/posts/17/actions/LIKE'],['DELETE','/posts/17/actions/BOOKMARK'],
 ['GET','/posts/17/comments'],['POST','/posts/17/comments'],['DELETE','/posts/17/comments/19'],['POST','/comments/19/actions'],['DELETE','/comments/19/actions/LIKE'],['POST','/reports']
];
test('canonical adapter closure matches actual Controller methods; unknown route and wrong method are RED', () => {
 const mappings=gate.collectBackendMappings(path.resolve(__dirname,'../../..')).mappings;
 const accepts=(method,route)=>mappings.some(m=>m.verb.toUpperCase()===method && gate.patternToRegex(m.pattern).test('/api/v1/community'+route));
 for(const [method,route] of closure) assert.equal(accepts(method,route),true,method+' '+route);
 assert.equal(accepts('GET','/unknown'),false);
 assert.equal(accepts('DELETE','/guidelines/active'),false);
});

test('strict U1 recognizes only the explicitly bound canonical receiver and retains its variable wrapper debt', () => {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'canonical-wrapper-'));
 const js=path.join(root,'page.js');
 try {
  fs.writeFileSync(js,"const community=require('../../utils/community-post-api.js').createClient(app,wx); function req(url){return community.request({url});} req(unknown); req('/api/present');");
  const result=strict.scanU1([js],new Set(['/api/present']),new Set());
  assert.equal(result.variableReports.length,1); assert.equal(result.violations.some(x=>x.message.includes('受控 wrapper')),true);
  fs.writeFileSync(js,"const community=require('../../utils/community-post-api.js').createClient(app,wx); community.request({url:'/api/unknown'});");
  assert.equal(strict.scanU1([js],new Set(['/api/present']),new Set()).violations.some(x=>x.message.includes('无后端 Mapping')),true);
 }finally{fs.rmSync(root,{recursive:true,force:true});}
});

test('actual private call literals match verbs, and reviewed dynamic suffix inventory cannot silently grow', () => {
 const root=path.resolve(__dirname,'../../..');
 const adapter=fs.readFileSync(path.join(root,'chengyinhub-xcx/utils/community-post-api.js'),'utf8');
 const progress=fs.readFileSync(path.join(root,'chengyinhub-xcx/utils/community-submit-progress.js'),'utf8');
 const source=adapter+'\n'+progress;
 assert.match(adapter,/if \(ownReportLookup === true\) request\.url = '\/api\/community-trust\/reports\/mine'/);
 const mappings=gate.collectBackendMappings(root).mappings;
 const accepts=(method,route)=>mappings.some(m=>m.verb.toUpperCase()===method && gate.patternToRegex(m.pattern).test('/api/v1/community'+route));
 const directCalls=src=>[...src.matchAll(/\b(?:call\(|write\(\s*'[^']+'\s*,)\s*'([^']+)'\s*,\s*'([^']+)'/g)];
 assert.ok(directCalls(source).length>=5);
 for(const m of directCalls(source)) assert.equal(accepts(m[2],m[1]),true,m[2]+' '+m[1]);
 const wrong=source.replace("call('/capabilities', 'GET'", "call('/capabilities', 'DELETE'");
 assert.equal(directCalls(wrong).some(m=>!accepts(m[2],m[1])),true);
 const suffixes=src=>[...new Set(gate.scanJsStringLiterals(src).map(x=>x.value).filter(x=>x.startsWith('/')&&!x.startsWith('/api/')))].sort();
 const reviewed=['/','/LIKE','/actions','/bookmark','/capabilities','/comments','/comments/','/drafts','/feeds/LATEST','/guidelines/ack','/guidelines/active','/me/bookmarks','/me/posts','/media/register','/posts','/posts/','/publish','/reports'];
 assert.deepEqual(suffixes(source),reviewed);
 assert.notDeepEqual(suffixes(source+";const unknown='/unreviewed';"),reviewed);
});

test('canonical U5 retains visible fail feedback; arbitrary request, removed feedback and success-only feedback are RED', () => {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'canonical-u5-'));const js=path.join(root,'page.js');
 const binding="const community=require('../../utils/community-post-api.js').createClient(app,wx);";
 const cases=[
 [binding+"community.request({url:'/api/present',silentError:true,fail(){wx.showToast({title:'请求失败，请重试'});}});",0],
 [binding+"community.request({url:'/api/present',silentError:true,fail(){resolve(null);}});",1],
 [binding+"community.request({url:'/api/present',silentError:true,success(){wx.showToast({title:'请求失败，请重试'});}});",1],
 ["arbitrary.request({url:'/api/present',silentError:true,fail(){wx.showToast({title:'请求失败，请重试'});}});",1],
 ["// "+binding+"\ncommunity.request({url:'/api/present',silentError:true,fail(){wx.showToast({title:'请求失败，请重试'});}});",1]
 ];
 try{for(const [source,count]of cases){fs.writeFileSync(js,source);assert.equal(strict.scanU5([js],new Map()).violations.length,count,source);}}
 finally{fs.rmSync(root,{recursive:true,force:true});}
});

test('a local expression-arrow fail reference retains feedback analysis without making unknown or silent references safe', () => {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'canonical-fail-reference-'));const js=path.join(root,'page.js');
 const binding="const community=require('../../utils/community-post-api.js').createClient(app,wx);";
 const cases=[
 [binding+"function load(){const confirmFailed=()=>toast('状态确认失败，请刷新');community.request({url:'/api/present',silentError:true,fail:confirmFailed});}",0],
 [binding+"function load(){const confirmFailed=()=>resolve(null);community.request({url:'/api/present',silentError:true,fail:confirmFailed});}",1],
 [binding+"function load(){community.request({url:'/api/present',silentError:true,fail:unknown});}",1],
 [binding+"function unrelated(){const confirmFailed=()=>toast('失败，请重试');} function load(){community.request({url:'/api/present',silentError:true,fail:confirmFailed});}",1],
 [binding+"function load(){const confirmFailed=()=>toast('失败，请重试');community.request({url:'/api/present',silentError:true,success:confirmFailed});}",1],
 [binding+"function load(){function unrelated(){const confirmFailed=()=>toast('失败，请重试');}community.request({url:'/api/present',silentError:true,fail:confirmFailed});}",1]
 ];
 try{for(const [source,count]of cases){fs.writeFileSync(js,source);assert.equal(strict.scanU5([js],new Map()).violations.length,count,source);}}
 finally{fs.rmSync(root,{recursive:true,force:true});}
});
