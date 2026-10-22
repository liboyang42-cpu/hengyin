'use strict';

/**
 * 页面入口来源(route provenance):只有「真的被导航消费者传进去」的路由才算入口。
 *
 * 孤儿页门禁(nav-route-exists)和页面清理审计(audit-page-cleanup)原来把任何 `url:` / `path:` /
 * `focusPath:` 字面量、甚至任何提到页面路径的行都当成「有人跳到这页」。一个没人用的 URL 常量、
 * 一段说明文字里的路径,就能让一张没有任何入口的页面判成可达。这里收紧为:
 *
 *   1. 导航 API 调用:wx.navigateTo / redirectTo / switchTab / reLaunch 的 url(含指向这些 API 的
 *      同文件别名,如 `const go = cond ? wx.redirectTo : wx.navigateTo`)。
 *   2. 同文件导航包装:函数/方法把自己的形参(或由形参派生的局部变量)作为 url 传给 1 或另一个
 *      包装(如 `_leave(url) { wx.navigateTo({ url }) }`),调用它时传入的路由算入口。
 *   3. WXML `<navigator url="...">`。
 *
 * url 表达式里能静态读出的路由才算:字符串/模板字面量、三元/逻辑两支、同文件变量的初值,
 * 以及「固定路径 + 查询串」的拼接(首段已带 ?/#,或紧跟的一段是 ?... 字面量 / 两支都是 ?... 或空串的三元)。
 * 路径本身被拼接的(如 '/pages/a' + suffix)读不出确定页面,不算入口 —— 它们由动态导航清单单独盘点。
 *
 * 已知局限(如实说明):同文件变量按名字取初值,不做作用域精确解析;不跟踪跨文件的数据流
 * (配置数组里的 url 经 WXML data-* 再由点击处理读出的链路不算入口)。两者都只会让入口更少,
 * 不会让闲置字面量变成入口。
 */

const fs = require('node:fs');
const path = require('node:path');
const { maskWxmlComments } = require('../../scripts/lib/xcx-scan');

const NAV_APIS = new Set(['navigateTo', 'redirectTo', 'switchTab', 'reLaunch']);
const ROUTE_RE = /^\/(?:pages|subpackage[A-Za-z0-9]*)\/[^?#]*/;
const NAVIGATOR_RE = /<navigator\b[^>]*?\burl\s*=\s*"(\/(?:pages|subpackage[A-Za-z0-9]*)\/[^"?#{]*)/g;

let acorn;
function parser() {
  if (acorn) return acorn;
  try {
    acorn = require('internal/deps/acorn/acorn/dist/acorn');
  } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error;
    const bundled = process.binding('natives')['internal/deps/acorn/acorn/dist/acorn'];
    if (!bundled) throw new Error('入口来源扫描需要 Node 内置 Acorn;当前 Node 不提供,禁止跳过');
    const parserModule = { exports: {} };
    require('vm').runInNewContext(bundled, { module: parserModule, exports: parserModule.exports });
    acorn = parserModule.exports;
  }
  return acorn;
}

function parse(source, rel) {
  for (const sourceType of ['script', 'module']) {
    try {
      return parser().parse(source, { ecmaVersion: 'latest', sourceType, locations: true,
        allowHashBang: true, allowReturnOutsideFunction: true, allowAwaitOutsideFunction: true });
    } catch (error) {
      if (error.name !== 'SyntaxError' || sourceType === 'module') throw new Error(`${rel}: ${error.message}`);
    }
  }
  return null;
}

function walk(node, visit, parent) {
  if (!node || typeof node.type !== 'string') return;
  visit(node, parent);
  for (const key of Object.keys(node)) {
    const value = node[key];
    if (Array.isArray(value)) value.forEach((child) => walk(child, visit, node));
    else if (value && typeof value.type === 'string') walk(value, visit, node);
  }
}

function stringOf(node) {
  if (node && node.type === 'Literal' && typeof node.value === 'string') return node.value;
  if (node && node.type === 'TemplateLiteral' && node.quasis.length === 1) return node.quasis[0].value.cooked;
  return null;
}

// 只有查询串/片段的拼接段:'?a=1'、`?a=${x}`、cond ? '?a' : ''
function isQueryOnly(node) {
  if (!node) return false;
  if (node.type === 'TemplateLiteral') return /^[?#]/.test(node.quasis[0].value.cooked || '');
  if (node.type === 'ConditionalExpression') {
    return [node.consequent, node.alternate].every((branch) => {
      const text = stringOf(branch);
      return text === '' || (text != null && /^[?#]/.test(text)) || (branch.type === 'TemplateLiteral' && isQueryOnly(branch));
    });
  }
  const text = stringOf(node);
  return text != null && /^[?#]/.test(text);
}

function concatOperands(node) {
  if (node.type === 'BinaryExpression' && node.operator === '+') return concatOperands(node.left).concat([node.right]);
  return [node];
}

function routesOf(expr, decls, seen = new Set()) {
  if (!expr) return [];
  switch (expr.type) {
    case 'Literal': {
      const match = typeof expr.value === 'string' && expr.value.match(ROUTE_RE);
      return match ? [match[0]] : [];
    }
    case 'TemplateLiteral': {
      const head = expr.quasis[0].value.cooked || '';
      const match = head.match(ROUTE_RE);
      // `${}` 落在路径里 ⇒ 读不出确定页面
      if (!match || (expr.quasis.length > 1 && !/[?#]/.test(head))) return [];
      return [match[0]];
    }
    case 'BinaryExpression': {
      if (expr.operator !== '+') return [];
      const [head, next] = concatOperands(expr);
      const text = stringOf(head);
      const match = text != null && text.match(ROUTE_RE);
      if (!match) return [];
      return /[?#]/.test(text) || isQueryOnly(next) ? [match[0]] : [];
    }
    case 'ConditionalExpression':
      return routesOf(expr.consequent, decls, seen).concat(routesOf(expr.alternate, decls, seen));
    case 'LogicalExpression':
      return routesOf(expr.left, decls, seen).concat(routesOf(expr.right, decls, seen));
    case 'Identifier': {
      if (seen.has(expr.name)) return [];
      const next = new Set(seen).add(expr.name);
      return (decls.get(expr.name) || []).flatMap((init) => routesOf(init, decls, next));
    }
    default:
      return [];
  }
}

function functionName(node, parent) {
  if (node.type === 'FunctionDeclaration' && node.id) return node.id.name;
  if (parent && (parent.type === 'Property' || parent.type === 'MethodDefinition') && parent.value === node) {
    return parent.key.name || parent.key.value || null;
  }
  if (parent && parent.type === 'VariableDeclarator' && parent.init === node && parent.id.type === 'Identifier') return parent.id.name;
  return null;
}

function scanJs(rel, source) {
  const ast = parse(source, rel);
  const decls = new Map();
  const remember = (name, init) => { if (!decls.has(name)) decls.set(name, []); decls.get(name).push(init); };
  walk(ast, (node) => {
    if (node.type === 'VariableDeclarator' && node.id.type === 'Identifier' && node.init) remember(node.id.name, node.init);
    if (node.type === 'AssignmentExpression' && node.operator === '=' && node.left.type === 'Identifier') remember(node.left.name, node.right);
  });
  const isWxNav = (node) => node.type === 'MemberExpression' && !node.computed && node.object.type === 'Identifier'
    && node.object.name === 'wx' && NAV_APIS.has(node.property.name);
  const isNavApi = (callee) => {
    if (isWxNav(callee)) return true;
    if (callee.type !== 'Identifier') return false;
    return (decls.get(callee.name) || []).some((init) => {
      let found = false;
      walk(init, (node) => { if (isWxNav(node)) found = true; });
      return found;
    });
  };
  const objectUrl = (arg) => {
    let object = arg;
    if (object && object.type === 'Identifier') object = (decls.get(object.name) || []).find((init) => init.type === 'ObjectExpression');
    if (!object || object.type !== 'ObjectExpression') return null;
    const urls = object.properties.filter((p) => p.type === 'Property' && (p.key.name || p.key.value) === 'url');
    return urls.length ? urls[urls.length - 1].value : null;
  };
  const calleeName = (callee) => {
    if (callee.type === 'Identifier') return callee.name;
    if (callee.type === 'MemberExpression' && !callee.computed
      && (callee.object.type === 'ThisExpression' || callee.object.type === 'Identifier')) return callee.property.name;
    return null;
  };
  const wrappers = new Map();
  // 一次调用传给导航的 url 表达式;不是导航调用返回 null
  const navUrl = (call, self) => {
    if (isNavApi(call.callee)) return objectUrl(call.arguments[0]);
    const name = calleeName(call.callee);
    if (!name || name === self || !wrappers.has(name)) return null;
    const arg = call.arguments[wrappers.get(name)];
    return arg && arg.type === 'ObjectExpression' ? objectUrl(arg) : arg || null;
  };
  // 包装可以套包装:迭代到不再新增
  for (let changed = true; changed;) {
    changed = false;
    walk(ast, (node, parent) => {
      if (!/Function/.test(node.type)) return;
      const name = functionName(node, parent);
      if (!name || wrappers.has(name)) return;
      const params = node.params.map((p) => (p.type === 'Identifier' ? p.name : p.type === 'AssignmentPattern' && p.left.type === 'Identifier' ? p.left.name : null));
      if (!params.some(Boolean)) return;
      const locals = new Map();
      walk(node.body, (n) => { if (n.type === 'VariableDeclarator' && n.id.type === 'Identifier' && n.init) locals.set(n.id.name, n.init); });
      // url 的值本身就来自哪个形参:p、p.url、两支/逻辑之一、p + 查询串,或经局部变量转一手。
      // 只在查询串里用到形参(如 '/pages/x?id=' + e.id)不算包装 —— 那是普通点击处理,目标页是它自己的字面量。
      const sources = (expr, depth = 0, out = new Set()) => {
        if (!expr) return out;
        if (expr.type === 'Identifier') {
          out.add(expr.name);
          if (depth < 2 && locals.has(expr.name)) sources(locals.get(expr.name), depth + 1, out);
        } else if (expr.type === 'MemberExpression' && !expr.computed && expr.property.name === 'url' && expr.object.type === 'Identifier') {
          out.add(expr.object.name);
        } else if (expr.type === 'ConditionalExpression') {
          sources(expr.consequent, depth, out); sources(expr.alternate, depth, out);
        } else if (expr.type === 'LogicalExpression') {
          sources(expr.left, depth, out); sources(expr.right, depth, out);
        } else if (expr.type === 'BinaryExpression' && expr.operator === '+') {
          sources(concatOperands(expr)[0], depth, out);
        }
        return out;
      };
      walk(node.body, (call) => {
        if (call.type !== 'CallExpression' || wrappers.has(name)) return;
        const url = navUrl(call, name);
        if (!url) return;
        const used = sources(url);
        const index = params.findIndex((p) => p && used.has(p));
        if (index >= 0) { wrappers.set(name, index); changed = true; }
      });
    });
  }
  const entries = [];
  walk(ast, (call) => {
    if (call.type !== 'CallExpression') return;
    const url = navUrl(call, null);
    if (!url) return;
    const consumer = isNavApi(call.callee) ? 'wx-nav' : 'wrapper:' + calleeName(call.callee);
    for (const route of new Set(routesOf(url, decls))) entries.push({ rel, line: url.loc.start.line, route, consumer });
  });
  return entries;
}

function scanWxml(rel, source) {
  const masked = maskWxmlComments(source);
  const entries = [];
  for (const match of masked.matchAll(NAVIGATOR_RE)) {
    entries.push({ rel, line: masked.slice(0, match.index).split('\n').length, route: match[1], consumer: 'navigator' });
  }
  return entries;
}

/** @param {Array<{rel: string, source: string}>} sources @returns {Array<{rel, line, route, consumer}>} */
function navigationEntries(sources) {
  return sources.flatMap(({ rel, source }) => {
    if (rel.endsWith('.wxml')) return scanWxml(rel, source);
    if (rel.endsWith('.js')) return scanJs(rel, source);
    return [];
  });
}

const SKIP_DIRS = new Set(['node_modules', 'miniprogram_npm', '.git', '.serena', 'docs', 'scripts', 'tests', '.mcp-artifacts', '.impeccable']);

/** 小程序运行时源码(不含 tests/scripts/docs),rel 相对 xcxRoot */
function runtimeSources(xcxRoot) {
  const out = [];
  const visit = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) { if (!SKIP_DIRS.has(entry.name)) visit(path.join(dir, entry.name)); continue; }
      if (/\.(js|wxml)$/.test(entry.name)) {
        const file = path.join(dir, entry.name);
        out.push({ rel: path.relative(xcxRoot, file).split(path.sep).join('/'), source: fs.readFileSync(file, 'utf8') });
      }
    }
  };
  visit(xcxRoot);
  return out;
}

module.exports = { navigationEntries, runtimeSources };
