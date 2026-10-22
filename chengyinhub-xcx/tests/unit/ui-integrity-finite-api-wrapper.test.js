const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const { scanU1 } = require('../lint/ui-integrity');
const ROUTES = new Set(['/api/present', '/api/other', '/api/item/{id}', '/api/v1/ns/posts']);
function messages(t, source) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'finite-api-wrapper-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const file = path.join(root, 'index.js'); fs.writeFileSync(file, source);
  return scanU1([file], ROUTES, new Set(['/api/v1/ns'])).violations.map(v => v.message);
}
const green = (t, source) => assert.deepEqual(messages(t, source), [], source);
const red = (t, source, pattern) => assert.ok(messages(t, source).some(m => pattern.test(m)), source);
const RUNTIME = /运行时变量/, WRAPPER = /受控 wrapper/;

test('链式回调里的请求按自身callee定位，不被外层then/catch调用吞掉', t => {
  green(t, "app.ready().then(()=>{app.sendRequest({url:'/api/present'})}).catch(()=>{});");
  red(t, "app.ready().then(()=>{app.sendRequest({url:unknownUrl})}).catch(()=>{});", RUNTIME);
  green(t, "new Promise(r=>{try{options.sendRequest({url:'/api/other',success:r})}catch(e){}}).then(x=>x);");
});

test('未知拼接段只在落到真实Mapping时可证明，命名空间常量加任意后缀仍红', t => {
  green(t, "const id=load();app.sendRequest({url:'/api/item/'+id});");
  red(t, "const BASE='/api/v1/ns';app.sendRequest({url:BASE+path});", RUNTIME);
  red(t, "app.sendRequest({url:'/api/item/x/'+anything});", RUNTIME);
  green(t, "const BASE='/api/v1/ns';app.sendRequest({url:BASE+'/posts'});");
});

const OPT = "function req(o){app.sendRequest(Object.assign({}, o, {hideLoading:true}))}";
test('options对象wrapper按调用处url属性证明：正控绿、未知分支红', t => {
  green(t, OPT + "req({url:'/api/present',method:'POST'});");
  green(t, OPT + "req({url:flag?'/api/present':'/api/other'});");
  red(t, OPT + "req({url:unknownUrl});", WRAPPER);
  red(t, OPT + "req({url:flag?'/api/present':unknownUrl});", WRAPPER);
  red(t, OPT + "req({url:'/api/missing'});", WRAPPER);
  red(t, OPT + "const o={url:'/api/present'};req(o);", WRAPPER);
  green(t, OPT + "req({...extra,url:'/api/present'});");
  red(t, OPT + "req({url:'/api/present',...extra});", WRAPPER);
});

test('options对象wrapper的重赋、shadow与可变闭包捕获保持未知', t => {
  green(t, OPT + "let u='/api/present';req({url:u});u='/api/other';req({url:u});");
  red(t, OPT + "let u='/api/present';u=unknownUrl;req({url:u});", WRAPPER);
  red(t, OPT + "let u='/api/present';if(flag)u=unknownUrl;req({url:u});", WRAPPER);
  green(t, OPT + "const u='/api/present';function later(){req({url:u})}later();");
  red(t, OPT + "let u='/api/present';function later(){req({url:u})}u=unknownUrl;later();", WRAPPER);
  red(t, OPT + "const u='/api/present';function f(u){req({url:u})}f(x);", WRAPPER);
  red(t, OPT + "let call=req;call=unknownRequest;call({url:'/api/present'});", WRAPPER);
});

test('wrapper自身可改写url时不承认为受控wrapper', t => {
  red(t, "function req(o){app.sendRequest(Object.assign({}, o, {url:other}))}req({url:'/api/present'});", RUNTIME);
  red(t, "function req(o){app.sendRequest(Object.assign({}, o, more))}req({url:'/api/present'});", RUNTIME);
  red(t, "function req(o){o=other;app.sendRequest(Object.assign({}, o))}req({url:'/api/present'});", RUNTIME);
  red(t, "const Object=fake;function req(o){app.sendRequest(Object.assign({}, o))}req({url:'/api/present'});", RUNTIME);
  green(t, "function req(o){app.sendRequest(Object.assign({hideLoading:true}, o, {silentError:true}))}req({url:'/api/present'});");
});

test('分支写入只在每条路径都已赋值或离开时按全部写入的并集证明', t => {
  green(t, "function f(a){let p;if(a)p='/present';else if(b)p='/other';else return;app.sendRequest({url:'/api'+p})}");
  green(t, "function f(a){let p='/present';if(a){if(c)return;p='/other'}app.sendRequest({url:'/api'+p})}");
  red(t, "function f(a){let p;if(a)p='/present';else if(b)p='/other';app.sendRequest({url:'/api'+p})}", RUNTIME);
  red(t, "function f(a){let p;if(a)p='/present';else p=unknownPath;app.sendRequest({url:'/api'+p})}", RUNTIME);
  red(t, "function f(a){let p;if(a)p='/present';else p='/missing';app.sendRequest({url:'/api'+p})}", RUNTIME);
  red(t, "function f(a){let p;if(a)p='/present';else p+='/other';app.sendRequest({url:'/api'+p})}", RUNTIME);
  red(t, "function f(a){let p;if(a)p='/present';else return;later(()=>{p=unknownPath});app.sendRequest({url:'/api'+p})}", RUNTIME);
  green(t, "function f(a){let p;if(a)p='/present';else return;{let p;if(a)p=x;}try{p='/other'}catch(e){}app.sendRequest({url:'/api'+p})}");
  red(t, "function f(a){let p;if(a)p='/present';else return;try{p=unknownPath}catch(e){}app.sendRequest({url:'/api'+p})}", RUNTIME);
  red(t, "function f(a){let p;if(a){let p='/present'}else return;app.sendRequest({url:'/api'+p})}", RUNTIME);
});

test('Object.assign里最后写url的一方决定请求路径', t => {
  green(t, "function f(o){app.sendRequest(Object.assign({}, o, {url:'/api/present'}))}");
  green(t, "function f(o){app.sendRequest(Object.assign({}, {url:'/api/other'}, o))}f({url:'/api/present'});");
  red(t, "function f(o){app.sendRequest(Object.assign({}, {url:'/api/present'}, o))}f({url:unknownUrl});", WRAPPER);
  red(t, "function f(o){app.sendRequest(Object.assign({}, o, {url:'/api/present'}, more))}", RUNTIME);
  green(t, "const P={submit(u,s){app.sendRequest(Object.assign({}, {url:u, data:s}))}};P.submit('/api/present');function g(){P.submit('/api/other')}");
});

test('url之后的形参展开只在调用处实参证明不含url时放行', t => {
  const W = "const H={header:{a:1}};function req(url,o){app.sendRequest({url,...(o||{})})}";
  green(t, W + "req('/api/present');req('/api/other',{silentError:true});req('/api/present',H);");
  red(t, W + "req('/api/present',{url:other});", WRAPPER);
  red(t, W + "req('/api/present',{...extra});", WRAPPER);
  red(t, W + "req('/api/present',unknownOpts);", WRAPPER);
  red(t, W + "let h={};h=unknownOpts;req('/api/present',h);", WRAPPER);
  red(t, "function req(url,o){o=other;app.sendRequest({url,...o})}req('/api/present',{});", RUNTIME);
  red(t, "function req(url){app.sendRequest({url,...extra})}req('/api/present');", RUNTIME);
});

test('对象成员改写或Object.assign改写后不再证明其不含url', t => {
  const W = "function req(url,o){app.sendRequest({url,...(o||{})})}";
  green(t, W + "const H={header:{a:1}};req('/api/present',H);");
  red(t, W + "const H={header:{a:1}};H.url=other;req('/api/present',H);", WRAPPER);
  red(t, W + "const H={header:{a:1}};Object.assign(H,extra);req('/api/present',H);", WRAPPER);
  red(t, "function req(url,o){o.url=other;app.sendRequest({url,...o})}req('/api/present',{});", RUNTIME);
  red(t, "function req(o){o.url=other;app.sendRequest(Object.assign({}, o))}req({url:'/api/present'});", RUNTIME);
  red(t, "function req(o){Object.assign(o,extra);app.sendRequest(Object.assign({}, o))}req({url:'/api/present'});", RUNTIME);
});
