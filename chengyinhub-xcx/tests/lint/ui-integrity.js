#!/usr/bin/env node
'use strict';

/**
 * UI-GATE-0:小程序静态 UI 有效性门禁。
 *
 * U1:app.sendRequest 的 /api 路径必须能匹配后端 Mapping;动态/变量路径逐条报告。
 * U2:复用 scripts/wxml-handler-lint.js,校验 WXML 事件绑定的方法存在。
 * U3:handler-like 方法必须至少有一个 WXML 绑定,合法回调使用精确白名单。
 * U4:setData 顶层字段必须在同页 WXML 消费,纯 JS 控制流字段使用精确白名单。
 * U5:silentError:true 必须逐调用点登记理由。
 *
 * 用法:
 *   node tests/lint/ui-integrity.js
 *   node tests/lint/ui-integrity.js --selftest
 */
const fs = require('fs');
const { scanSharedA11y } = require('./shared-a11y');
const crypto = require('crypto');
const os = require('os');
const path = require('path');
// 2026-08-18:解析层已搬进 scripts/lib/xcx-scan.js(与 U3/U4 共用),
// wxml-handler-lint 只剩判据 + 自证,所以这里分两处 require —— 别再从 lint 模块要解析函数。
const {
  extractHandlerNames,
  parseJavaScript,
  extractOwnHandlerEntries,
  findEventBindings,
  maskNonCode,
  matchBrace,
  resolveSpreadRequireTargets,
} = require('../../scripts/lib/xcx-scan');
const { lintRepo: lintWxmlHandlers } = require('../../scripts/wxml-handler-lint');
const BASELINE = require('./ui-integrity-baseline');
const { namespaceDeclarationOffsets, collectBackendMappings } = require('../../../ci/api-existence-gate');

const XCX_ROOT = path.resolve(__dirname, '..', '..');
const REPO_ROOT = path.resolve(XCX_ROOT, '..');
const WARN_AS_ERROR = process.argv.includes('--warn-as-error');
const NON_INCREASING_CEILING = Object.freeze({
  u1Variable: 13,
  u3Debt: 103,
  u4Debt: 196,
  // Retain master's ratchet; merged exact projections still require final key/digest accounting.
  u4Dynamic: 65,
  // 2026-08-26 12 → 13:merchant/customer 触达历史改为「有旧记录时刷新失败静默降级」,
  //   fail 不再写可见错误态,故该 silentError 调用点需要逐项登记理由(已登记,见 baseline)。
  // 2026-09-03 13 → 15:activity/list 删掉「发起官方活动」面板后,那两个列表读取的
  //   silentError 调用点不再被"自动豁免"推导命中(推导会顺着同文件里的具名函数体
  //   往下找可见反馈,面板一删,原来命中的那条路径没了)。**行为没变**:失败仍落
  //   loadError/mineError + errorMsg,页面 cy-error 照常呈现并给重试 —— 只是从
  //   "自动认定安全"转成"逐项登记理由",两条已写进 baseline。
  // 2026-09-10 13 → 15:漫游地图上的「附近还在走的人」新增一对位置心跳,
  //   上行 _reportPresence(30 s)、下行 _fetchRunners(45 s),两条都必须静默 ——
  //   它们每分钟各跑一两次,不承载任何用户动作,也没有「重试」可给(下一次心跳就是重试);
  //   真失效的表现是自己从别人地图上消失 / 附近显示没人,不是弹一个框。
  //   逐项理由已登记在 baseline 的 u5Silent 里(pages/roam/index.js:1842 与 :1851)。
  //   2026-09-11 并 master 后两批叠加:13 → 15(master 那两条)→ 17(漫游心跳那两条)。
  // 2026-09-11 与 master 合流:两边各自抬过这个冻结上限,合并后实测 20。
  //   本分支:merchantapply / signup / merchantinfo 主办章节只读。
  //   master:漫游 f-hangout 与组局 h-place 背景读取。
  // 2026-09-12 审核收口:广场 post-compose 选活动两路列表失败落组件 activityError,
  //   关掉自动 toast 避免双弹,23 → 25。
  // 2026-09-17 release-0917 第三阶段 C 25 → 26:总控裁定(唯一允许的 U5 放宽)——支付成功面板/订单详情上
  //   取商家营销同意状态的附属 GET 失败只隐藏可选勾选行,不弹通道默认错误 toast(utils/marketing-consent-entry.js:60)。
  // 2026-09-19 批复1-a=2 定价页接合作方入口 26 → 27:loadLineupNames 阵容补名是 best-effort
  //   装饰读取(拉不到留类型兜底名,点开合作方页自有错误态),新增一条逐项登记。两条放宽各自成立。
  // 2026-09-21 漫游入口地图新增活动/队伍两路后台刷新。失败均落页面 entryLoadError 并提供重试，
  //   禁用通道 toast 避免拖图时与页内错误态重复反馈，27 → 29。
  // 2026-10-12 主控批准新增本人投诉进展辅助读：仅500/网络留白，401/403/参数错误可见。
  // 2026-10-19 Claude 小程序线移植到 master(用户批准冻结数值更新) 30 → 37:本线 9 处静默调用逐项写明理由(举报/纠错半屏失败落结果半屏、
  //   安全感受换取商家 ID 失败落半屏错误位、通知复盘写触达行、漫游头像堆/可参与官方活动与券统计为附属只读信息)。
  // 2026-10-24 上线合并 37 → 39:业主 UI(#508)合作 tab 帖文流与竞猜待答入口两处静默读取,逐项理由见 baseline。
  // 上海据点新增两处可选公开照片资料读取，逐项校验与失败保留封面理由见 baseline。
  // 2026-10-25 官方地点卡片两条背景读取(节点补读 / 评价列表)逐项登记,41 → 43。
  // 2026-10-27 L06 漫游护照勋章方卡读最新点亮勋章图(失败保持空态插画)逐项登记,43 → 44。
  u5Silent: 44,
});
const EXCLUDED_DIRS = new Set([
  '.git', 'node_modules', 'miniprogram_npm', 'tests', 'scripts', 'coverage', 'dist',
]);

const U1_VARIABLE_ALLOWLIST = new Map(Object.entries(BASELINE.u1Variable || {}));
// U3/U4 是显式存量债务清单，不冒充“已证明安全”的白名单；新债务默认红，只能随修复缩小。
const U3_DEBT_BASELINE = new Map(Object.entries(BASELINE.u3Debt || {}));
const U4_DEBT_BASELINE = new Map(Object.entries(BASELINE.u4Debt || {}));
const U4_DYNAMIC_ALLOWLIST = new Map(Object.entries(BASELINE.u4Dynamic || {}));
const U5_SILENT_ALLOWLIST = new Map(Object.entries(BASELINE.u5Silent || {}));

function walkFiles(root, extension, excluded = EXCLUDED_DIRS) {
  const out = [];
  if (!fs.existsSync(root)) return out;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (entry.name.startsWith('.') || (entry.isDirectory() && excluded.has(entry.name))) continue;
    const target = path.join(root, entry.name);
    if (entry.isDirectory()) out.push(...walkFiles(target, extension, excluded));
    else if (entry.isFile() && entry.name.endsWith(extension)) out.push(target);
  }
  return out.sort();
}

function repoRel(file) {
  return path.relative(REPO_ROOT, file).split(path.sep).join('/');
}

function lineNumber(source, offset) {
  let line = 1;
  for (let i = 0; i < offset; i += 1) if (source[i] === '\n') line += 1;
  return line;
}

function splitTopLevel(masked, start, end) {
  const ranges = [];
  let depth = 0;
  let segmentStart = start;
  for (let i = start; i < end; i += 1) {
    const c = masked[i];
    if (depth === 0 && c === ',') {
      ranges.push([segmentStart, i]);
      segmentStart = i + 1;
      continue;
    }
    if (c === '{' || c === '(' || c === '[') depth += 1;
    else if (c === '}' || c === ')' || c === ']') depth -= 1;
  }
  ranges.push([segmentStart, end]);
  return ranges;
}

function stripLeadingTrivia(raw) {
  let offset = 0;
  while (offset < raw.length) {
    const whitespace = /^\s+/.exec(raw.slice(offset));
    if (whitespace) { offset += whitespace[0].length; continue; }
    const lineComment = /^\/\/[^\n]*(?:\n|$)/.exec(raw.slice(offset));
    if (lineComment) { offset += lineComment[0].length; continue; }
    const blockComment = /^\/\*[\s\S]*?\*\//.exec(raw.slice(offset));
    if (blockComment) { offset += blockComment[0].length; continue; }
    break;
  }
  return { text: raw.slice(offset), offset };
}

function objectProperties(source, masked, openBrace, closeBrace) {
  const properties = [];
  const dynamic = [];
  for (const [start, end] of splitTopLevel(masked, openBrace + 1, closeBrace)) {
    const raw = source.slice(start, end);
    const leading = stripLeadingTrivia(raw);
    const trimmed = leading.text.trim();
    if (!trimmed) continue;
    if (trimmed.startsWith('...')) {
      dynamic.push({ raw: trimmed, offset: start + leading.offset });
      continue;
    }
    if (trimmed.startsWith('[')) {
      const computed = /^\[\s*(?:`|['"])([A-Za-z_$][\w$]*)(?:[.[`'"])/.exec(trimmed);
      if (computed) {
        properties.push({ name: computed[1], raw: trimmed, start: start + leading.offset, end, offset: start + leading.offset });
      } else {
        dynamic.push({ raw: trimmed, offset: start + leading.offset });
      }
      continue;
    }
    const match = /^\s*(?:(['"])([^'"]+)\1|([A-Za-z_$][\w$]*))\s*(?::|\(|$)/.exec(leading.text);
    if (!match) {
      dynamic.push({ raw: trimmed, offset: start + leading.offset });
      continue;
    }
    properties.push({
      name: match[2] || match[3],
      raw: leading.text,
      start: start + leading.offset,
      end,
      offset: start + leading.offset + match.index,
    });
  }
  return { properties, dynamic };
}

function stripJsComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length));
}

function mappingPaths(annotationArgs) {
  if (annotationArgs == null || !annotationArgs.trim()) return [''];
  const quoted = [];
  const re = /(['"])(.*?)\1/g;
  let match;
  while ((match = re.exec(annotationArgs))) {
    if (match[2].startsWith('/')) quoted.push(match[2]);
  }
  return quoted.length ? quoted : [''];
}

function joinRoute(base, child) {
  const joined = `${base || ''}/${child || ''}`.replace(/\/{2,}/g, '/');
  if (joined.length > 1) return joined.replace(/\/$/, '');
  return joined;
}

function collectBackendRoutes(repoRoot = REPO_ROOT) {
  const routes = new Set();
  const moduleDirs = fs.readdirSync(repoRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith('chengyinhub-'))
    .map((entry) => path.join(repoRoot, entry.name, 'src', 'main', 'java'))
    .filter(fs.existsSync);
  const annotationRe = /@(Request|Get|Post|Put|Delete|Patch)Mapping\s*(?:\(([\s\S]*?)\))?/g;
  for (const javaFile of moduleDirs.flatMap((dir) => walkFiles(dir, '.java', new Set()))) {
    const source = stripJsComments(fs.readFileSync(javaFile, 'utf8'));
    const classIndex = source.search(/\b(?:class|interface)\s+[A-Za-z_$]/);
    if (classIndex < 0) continue;
    const prefix = source.slice(0, classIndex);
    const classBases = [];
    let match;
    annotationRe.lastIndex = 0;
    while ((match = annotationRe.exec(prefix))) {
      if (match[1] === 'Request') classBases.push(...mappingPaths(match[2]));
    }
    const bases = classBases.length ? classBases : [''];
    annotationRe.lastIndex = classIndex;
    while ((match = annotationRe.exec(source))) {
      const children = mappingPaths(match[2]);
      for (const base of bases) for (const child of children) {
        const route = joinRoute(base, child);
        if (route.startsWith('/api/')) routes.add(route);
      }
    }
  }
  return routes;
}

function routeMatches(frontendRoute, backendRoute) {
  const front = frontendRoute.replace(/[?#].*$/, '').replace(/\/$/, '').split('/').filter(Boolean);
  const back = backendRoute.replace(/\/$/, '').split('/').filter(Boolean);
  const dynamicTail = front[front.length - 1] === ':dynamic';
  if (front.length !== back.length) return false;
  return front.every((segment, index) => {
    if (dynamicTail && index === front.length - 1) return true;
    const candidate = back[index];
    return segment === ':dynamic' || /^\{[^}]+\}$/.test(candidate) || candidate === '*' || segment === candidate;
  });
}

function expressionToApiRoute(expression) {
  const value = expression.trim();
  if (value.startsWith('`')) {
    const end = value.lastIndexOf('`');
    if (end <= 0) return null;
    const content = value.slice(1, end);
    if (!content.startsWith('/api/')) return null;
    const dynamic = content.includes('${');
    return { route: content.replace(/\$\{[\s\S]*?\}/g, ':dynamic').replace(/[?#].*$/, ''), dynamic };
  }
  const literal = /^(['"])(\/api\/[^'"]*)\1\s*$/.exec(value);
  if (literal) return { route: literal[2].replace(/[?#].*$/, ''), dynamic: false };
  const first = /^(['"])(\/api\/[^'"]*)\1/.exec(value);
  if (!first) return null;
  let route = '';
  let cursor = 0;
  const stringRe = /(['"])(.*?)\1/g;
  let match;
  while ((match = stringRe.exec(value))) {
    if (match.index > cursor && value.slice(cursor, match.index).replace(/[+\s]/g, '')) route += ':dynamic';
    route += match[2];
    cursor = stringRe.lastIndex;
  }
  if (value.slice(cursor).replace(/[+\s]/g, '')) route += ':dynamic';
  return { route: route.replace(/[?#].*$/, ''), dynamic: true };
}

function concatenatedRouteTail(source, start) {
  let cursor = start;
  let tail = '';
  let dynamic = false;
  while (cursor < source.length) {
    while (/\s/.test(source[cursor]) && source[cursor] !== '\n') cursor += 1;
    if (source[cursor] !== '+') break;
    dynamic = true;
    cursor += 1;
    while (/\s/.test(source[cursor]) && source[cursor] !== '\n') cursor += 1;
    const quote = source[cursor];
    if (quote === "'" || quote === '"' || quote === '`') {
      cursor += 1;
      let literal = '';
      while (cursor < source.length) {
        if (source[cursor] === '\\') {
          literal += source[cursor];
          if (cursor + 1 < source.length) literal += source[cursor + 1];
          cursor += 2;
          continue;
        }
        if (source[cursor] === quote) { cursor += 1; break; }
        literal += source[cursor];
        cursor += 1;
      }
      tail += literal.replace(/\$\{[\s\S]*?\}/g, ':dynamic');
      continue;
    }
    let paren = 0;
    while (cursor < source.length) {
      const ch = source[cursor];
      if (ch === '(' || ch === '[' || ch === '{') paren += 1;
      else if (ch === ')' || ch === ']' || ch === '}') {
        if (paren === 0) break;
        paren -= 1;
      }
      if (paren === 0 && (ch === '+' || ch === ',' || ch === ';' || ch === '\n')) break;
      cursor += 1;
    }
    tail += ':dynamic';
  }
  return { tail, dynamic };
}

function collectApiLiterals(source) {
  const found = [];
  let i = 0;
  while (i < source.length) {
    if (source[i] === '/' && source[i + 1] === '/') {
      i = source.indexOf('\n', i + 2);
      if (i < 0) break;
      continue;
    }
    if (source[i] === '/' && source[i + 1] === '*') {
      const end = source.indexOf('*/', i + 2);
      i = end < 0 ? source.length : end + 2;
      continue;
    }
    const quote = source[i];
    if (quote !== "'" && quote !== '"' && quote !== '`') { i += 1; continue; }
    const start = i;
    i += 1;
    let value = '';
    while (i < source.length) {
      if (source[i] === '\\') {
        value += source[i];
        if (i + 1 < source.length) value += source[i + 1];
        i += 2;
        continue;
      }
      if (source[i] === quote) { i += 1; break; }
      value += source[i];
      i += 1;
    }
    if (!value.startsWith('/api/')) continue;
    const concatenation = concatenatedRouteTail(source, i);
    const dynamic = (quote === '`' && value.includes('${')) || concatenation.dynamic;
    let route = value.replace(/\$\{[\s\S]*?\}/g, ':dynamic').replace(/[?#].*$/, '');
    route += concatenation.tail;
    route = route.replace(/[?#].*$/, '');
    found.push({
      offset: start,
      route,
      dynamic,
    });
  }
  return found;
}

function enclosingNamedFunctions(masked, offset) {
  const candidates = [];
  const patterns = [
    { kind: 'function', re: /\bfunction\s+([A-Za-z_$][\w$]*)\s*\(([^(){}\n]*)\)\s*\{/g },
    { kind: 'method', re: /\b([A-Za-z_$][\w$]*)\s*\(([^(){}\n]*)\)\s*\{/g },
    { kind: 'property', re: /\b([A-Za-z_$][\w$]*)\s*:\s*function\s*\(([^(){}\n]*)\)\s*\{/g },
  ];
  for (const pattern of patterns) {
    const fnRe = pattern.re;
    let match;
    while ((match = fnRe.exec(masked)) && match.index < offset) {
      if (['if', 'for', 'while', 'switch', 'catch', 'function'].includes(match[1])) continue;
      const bodyStart = masked.indexOf('{', match.index);
      const bodyEnd = matchBrace(masked, bodyStart);
      if (bodyStart < offset && bodyEnd > offset) {
        candidates.push({
          kind: pattern.kind,
          name: match[1],
          nameOffset: match.index + match[0].indexOf(match[1]),
          params: match[2].split(',').map((item) => item.trim())
            .filter((item) => /^[A-Za-z_$][\w$]*$/.test(item)),
        });
      }
    }
  }
  return candidates;
}

function callArguments(source, masked, openParen) {
  let depth = 0;
  let closeParen = openParen + 1;
  for (; closeParen < masked.length; closeParen += 1) {
    const ch = masked[closeParen];
    if (ch === '(' || ch === '[' || ch === '{') depth += 1;
    else if (ch === ')' || ch === ']' || ch === '}') {
      if (depth === 0) break;
      depth -= 1;
    }
  }
  return splitTopLevel(masked, openParen + 1, closeParen)
    .map(([start, end]) => source.slice(start, end).trim());
}

// Canonical requests are recognized only for a real local createClient binding.
// This retains the same URL and error-feedback audits used by sendRequest.
function requestTransportRegex(source, masked) {
  const binding = /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*require\(['"][^'"]*utils\/community-post-api\.js['"]\)\.createClient\s*\(/.exec(source);
  const name = binding && /^(?:const|let|var)\b/.test(masked.slice(binding.index)) && binding[1];
  const escaped = name && name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return name ? new RegExp('\\.\\s*sendRequest\\s*\\(|\\b' + escaped + '\\.\\s*request\\s*\\(', 'g') : /\.\s*sendRequest\s*\(/g;
}

// Finite URL facts belong to lexical bindings and their current writes, not names.
// Unknown branches and captured mutable values stay unproved; no module execution.
function finiteApiAnalysis(source, backendRoutes) {
  const ast = parseJavaScript(source), scopes = new Map(), parents = new Map();
  const calls = [], functions = [], assignments = [], unknown = Symbol('unknown');
  function scope(parent, fn) { return { parent, fn, bindings: new Map() }; }
  const root = scope(null, ast);
  function lookup(s, name) {
    for (; s; s = s.parent) if (s.bindings.has(name)) return s.bindings.get(name);
    return null;
  }
  function declare(s, id, init, kind) {
    if (!id) return;
    if (id.type === 'Identifier') {
      if (kind === 'var') while (s.parent && s.fn === s.parent.fn) s = s.parent;
      const previous = s.bindings.get(id.name);
      if (kind === 'var' && previous) {
        if (init) {
          const write = { type: 'AssignmentExpression', operator: '=', right: init, start: id.start };
          scopes.set(write, s); previous.writes.push({ node: write, uncertain: false });
        }
      } else s.bindings.set(id.name, { scope: s, id, init, kind, writes: [] });
    } else {
      targets(id).forEach(child => declare(s, child, null, kind));
    }
  }
  function visit(node, current, parent, uncertain = false) {
    if (!node || typeof node.type !== 'string') return;
    parents.set(node, parent);
    if (node.type === 'FunctionDeclaration' && node.id) declare(current, node.id, node, 'function');
    if (/^(?:FunctionDeclaration|FunctionExpression|ArrowFunctionExpression)$/.test(node.type)) {
      functions.push(node); current = scope(current, node);
      if (node.type === 'FunctionExpression' && node.id) declare(current, node.id, node, 'function');
      node.params.forEach(param => declare(current, param, null, 'param'));
      uncertain = false;
    } else if (node.type === 'BlockStatement' || node.type === 'CatchClause' ||
        /^(?:ForStatement|ForInStatement|ForOfStatement|SwitchStatement)$/.test(node.type)) {
      current = scope(current, current.fn);
      if (node.type === 'CatchClause') declare(current, node.param, null, 'param');
    }
    scopes.set(node, current);
    if (node.type === 'VariableDeclaration') for (const d of node.declarations) declare(current, d.id, d.init, node.kind);
    if (node.type === 'CallExpression') calls.push(node);
    if (node.type === 'AssignmentExpression' || node.type === 'UpdateExpression') assignments.push({ node, uncertain });
    const branch = uncertain || /^(?:IfStatement|ConditionalExpression|SwitchCase|WhileStatement|DoWhileStatement|ForStatement|ForInStatement|ForOfStatement|TryStatement)$/.test(node.type);
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc') continue;
      if (Array.isArray(value)) value.forEach(child => visit(child, current, node, branch));
      else if (value && typeof value.type === 'string') visit(value, current, node, branch);
    }
  }
  visit(ast, root, null);
  function targets(node, out = []) {
    if (!node) return out;
    if (node.type === 'Identifier') out.push(node);
    else if (node.type === 'RestElement') targets(node.argument, out);
    else if (node.type === 'AssignmentPattern') targets(node.left, out);
    else if (node.type === 'ArrayPattern') node.elements.forEach(n => targets(n, out));
    else if (node.type === 'ObjectPattern') node.properties.forEach(p => targets(p.value || p.argument, out));
    return out;
  }
  for (const item of assignments) for (const id of targets(item.node.left || item.node.argument)) {
    const binding = lookup(scopes.get(item.node), id.name);
    if (binding) binding.writes.push(item);
  }
  function currentValue(binding, read) {
    if (!binding || binding.kind === 'param') return unknown;
    if (binding.kind !== 'function' && binding.id.start > read.start && binding.scope.fn === scopes.get(read).fn) return unknown;
    const readScope = scopes.get(read);
    // Closures can run after later outer writes; source order is not execution order.
    if (binding.writes.some(w => scopes.get(w.node).fn !== readScope.fn) ||
        (binding.scope.fn !== readScope.fn && binding.writes.length)) return unknown;
    const writes = binding.writes.filter(w => w.node.start < read.start).sort((a, b) => a.node.start - b.node.start);
    const last = writes[writes.length - 1];
    if (!last) return binding.init;
    if (!last.uncertain) return last.node.type === 'AssignmentExpression' && last.node.operator === '=' ? last.node.right : unknown;
    // A branch write is provable only as the union of every write (and init) once each path to
    // the read has assigned or left; an unassigned path or any compound write stays unknown.
    if (binding.kind === 'function' || !binding.writes.every(w => w.node.type === 'AssignmentExpression' && w.node.operator === '=') ||
        !definitelyAssigned(binding, read)) return unknown;
    return { type: 'WriteUnion', options: (binding.init ? [binding.init] : []).concat(binding.writes.map(w => w.node.right)) };
  }
  function assignsOrExits(statement, binding) {
    if (!statement) return false;
    if (statement.type === 'ReturnStatement' || statement.type === 'ThrowStatement') return true;
    if (statement.type === 'BlockStatement') return statement.body.some(child => assignsOrExits(child, binding));
    if (statement.type === 'IfStatement') return assignsOrExits(statement.consequent, binding) && assignsOrExits(statement.alternate, binding);
    if (statement.type !== 'ExpressionStatement') return false;
    const expressions = statement.expression.type === 'SequenceExpression' ? statement.expression.expressions : [statement.expression];
    return expressions.some(e => e.type === 'AssignmentExpression' && e.operator === '=' && e.left.type === 'Identifier' &&
      lookup(scopes.get(e.left), e.left.name) === binding);
  }
  function definitelyAssigned(binding, read) {
    if (binding.init) return true;
    for (let node = read, p = parents.get(read); p; node = p, p = parents.get(p)) {
      if (/^(?:FunctionDeclaration|FunctionExpression|ArrowFunctionExpression)$/.test(p.type)) return false;
      const body = p.type === 'BlockStatement' || p.type === 'Program' ? p.body : p.type === 'SwitchCase' ? p.consequent : null;
      if (body && body.slice(0, body.indexOf(node)).some(statement => assignsOrExits(statement, binding))) return true;
    }
    return false;
  }
  function owner(node) {
    for (let p = node; p; p = parents.get(p)) if (/^(?:FunctionDeclaration|FunctionExpression)$/.test(p.type)) {
      const property = parents.get(p);
      return property && property.type === 'Property' ? parents.get(property) : null;
    }
    return null;
  }
  function target(node, read = node, active = new Set()) {
    if (!node || node === unknown || active.has(node)) return unknown;
    const next = new Set(active); next.add(node);
    if (/^(?:FunctionDeclaration|FunctionExpression|ArrowFunctionExpression)$/.test(node.type)) return node;
    if (node.type === 'Identifier') { const value = currentValue(lookup(scopes.get(node), node.name), read); return target(value, value, next); }
    if (node.type === 'MemberExpression' && !node.computed && node.object.type === 'ThisExpression') {
      const object = owner(node);
      const property = object && object.properties.find(p => p.type === 'Property' && !p.computed && (p.key.name || p.key.value) === node.property.name);
      return property ? target(property.value, read, next) : unknown;
    }
    return unknown;
  }
  function possible(node, active = new Set()) {
    if (!node || active.has(node)) return [];
    const next = new Set(active); next.add(node);
    if (/^(?:FunctionDeclaration|FunctionExpression|ArrowFunctionExpression)$/.test(node.type)) return [node];
    if (node.type === 'Identifier') {
      const binding = lookup(scopes.get(node), node.name);
      return binding ? [binding.init, ...binding.writes.map(w => w.node.right)].flatMap(value => possible(value, next)) : [];
    }
    const resolved = target(node);
    return resolved === unknown ? [] : [resolved];
  }
  function values(node, read = node, active = new Set()) {
    if (!node || node === unknown || active.has(node)) return null;
    const next = new Set(active); next.add(node);
    if (node.type === 'Literal' && typeof node.value === 'string') return [node.value];
    if (node.type === 'WriteUnion') {
      const all = node.options.map(option => values(option, option, next));
      return all.every(Boolean) ? all.flat() : null;
    }
    if (node.type === 'Identifier') { const value = currentValue(lookup(scopes.get(node), node.name), read); return values(value, value, next); }
    if (node.type === 'TemplateLiteral') return [node.quasis.map(q => q.value.cooked).join(':dynamic')];
    if (node.type === 'ConditionalExpression') {
      const a = values(node.consequent, read, next), b = values(node.alternate, read, next);
      return a && b ? a.concat(b) : null;
    }
    if (node.type === 'BinaryExpression' && node.operator === '+') {
      const a = values(node.left, read, next), b = values(node.right, read, next);
      return a ? a.flatMap(x => (b || [':dynamic']).map(y => x + y)) : null;
    }
    return null;
  }
  // Unknown operands become :dynamic, so each value must still hit a real Mapping;
  // namespace constants are skipped by the literal scan and are only checked here.
  function finite(node) {
    const result = values(node);
    return !!result && result.length > 0 && result.every(v => v.startsWith('/api/') &&
      [...backendRoutes].some(route => routeMatches(v, route)));
  }
  const isUrlKey = p => p.type === 'Property' && !p.computed && (p.key.name || p.key.value) === 'url';
  function isObjectAssign(node) {
    return node && node.type === 'CallExpression' && node.callee.type === 'MemberExpression' && !node.callee.computed &&
      node.callee.object.type === 'Identifier' && node.callee.object.name === 'Object' &&
      !lookup(scopes.get(node.callee.object), 'Object') && node.callee.property.name === 'assign';
  }
  // In-file object mutation (`x.url = ...`, Object.assign(x, ...)) can add url after the literal.
  function rootIdentifier(node) {
    while (node && node.type === 'MemberExpression') node = node.object;
    return node && node.type === 'Identifier' ? node : null;
  }
  const mutatedRoots = assignments.map(a => a.node.left || a.node.argument)
    .filter(target => target && target.type === 'MemberExpression').map(rootIdentifier)
    .concat(calls.filter(call => call.callee.type === 'MemberExpression' && !call.callee.computed &&
      call.callee.object.type === 'Identifier' && call.callee.object.name === 'Object' &&
      /^(?:assign|defineProperty|defineProperties|setPrototypeOf)$/.test(call.callee.property.name))
      .map(call => rootIdentifier(call.arguments[0])))
    .filter(Boolean);
  function mutated(binding) {
    return !binding || mutatedRoots.some(id => lookup(scopes.get(id), id.name) === binding);
  }
  // `...(opts || {})` spreads opts; the empty fallback cannot carry url.
  function spreadSource(node) {
    return node.type === 'LogicalExpression' && node.operator === '||' && node.right.type === 'ObjectExpression' &&
      node.right.properties.length === 0 ? node.left : node;
  }
  // An argument that provably cannot set url: absent/null/undefined or a url-less, spread-less object.
  function lacksUrl(node, active = new Set()) {
    if (!node || (node.type === 'Literal' && node.value === null)) return true;
    if (active.has(node)) return false;
    const next = new Set(active); next.add(node);
    if (node.type === 'Identifier') {
      if (node.name === 'undefined' && !lookup(scopes.get(node), 'undefined')) return true;
      const binding = lookup(scopes.get(node), node.name);
      const value = currentValue(binding, node);
      return !mutated(binding) && value !== unknown && !!value && value.type === 'ObjectExpression' && lacksUrl(value, next);
    }
    return node.type === 'ObjectExpression' && node.properties.every(p => p.type === 'Property' && !isUrlKey(p));
  }
  // The last writer of `url` decides: a url property or spread inside an object, else an earlier
  // Object.assign source. Returns { value, overrides } for a url property (overrides: identifier
  // spreads after it, each must lack url), { spread } for a whole object, or null.
  function urlSetter(node) {
    if (!node) return null;
    if (node.type === 'ObjectExpression') {
      const overrides = [];
      for (let i = node.properties.length - 1; i >= 0; i -= 1) {
        const p = node.properties[i];
        if (isUrlKey(p)) return { value: p.value, overrides };
        if (p.type === 'Property') continue;
        const source = spreadSource(p.argument);
        if (source.type !== 'Identifier' || node.properties.slice(0, i).every(q => !isUrlKey(q))) return { spread: source };
        overrides.push(source);
      }
      return { none: true };
    }
    if (isObjectAssign(node)) {
      for (let i = node.arguments.length - 1; i >= 0; i -= 1) {
        const a = node.arguments[i];
        if (a.type === 'ObjectExpression') { const r = urlSetter(a); if (!r.none) return r; continue; }
        return { spread: a };
      }
      return { none: true };
    }
    return { spread: node };
  }
  function urlValue(node) {
    const setter = urlSetter(node);
    return setter && setter.value && setter.overrides.every(o => lacksUrl(o)) ? setter.value : null;
  }
  // The transport match starts at `.sendRequest(` or `receiver.request(`; the matched call is the
  // nearest member property after it, never an outer chained then/catch call enclosing it.
  function requestCall(offset) {
    return calls.filter(call => call.callee.type === 'MemberExpression' && !call.callee.computed &&
      /^(?:sendRequest|request)$/.test(call.callee.property.name) &&
      call.callee.start <= offset && call.callee.property.start > offset)
      .sort((a, b) => a.callee.property.start - b.callee.property.start)[0];
  }
  return {
    requestFinite(offset) {
      const call = requestCall(offset);
      const url = call && call.arguments.length === 1 && urlValue(call.arguments[0]);
      return !!url && finite(url);
    },
    // 'url' when the transport's last url writer is the parameter itself, 'options' when it is the
    // whole parameter object; anything else (override, unknown spread) is not a controlled wrapper.
    wrapperMode(offset, fn, index) {
      const call = requestCall(offset), parameter = fn && fn.params[index];
      if (!call || call.arguments.length !== 1 || !parameter || parameter.type !== 'Identifier') return null;
      const binding = lookup(scopes.get(fn), parameter.name);
      const isParameter = node => !!node && node.type === 'Identifier' && lookup(scopes.get(node), node.name) === binding;
      const setter = urlSetter(call.arguments[0]);
      if (setter && setter.value) {
        if (!isParameter(setter.value)) return null;
        const overrides = setter.overrides.map(o => fn.params.findIndex(p => p.type === 'Identifier' &&
          lookup(scopes.get(o), o.name) === lookup(scopes.get(fn), p.name)));
        return overrides.every(i => i >= 0 && i !== index) &&
          setter.overrides.every(o => !mutated(lookup(scopes.get(o), o.name))) ? { mode: 'url', overrides } : null;
      }
      return setter && setter.spread && isParameter(setter.spread) && !mutated(binding) ? { mode: 'options', overrides: [] } : null;
    },
    functionAt(offset) {
      return functions.find(fn => (fn.id && fn.id.start === offset) || (parents.get(fn)?.type === 'Property' && parents.get(fn).key.start === offset));
    },
    stableParameter(fn, index) {
      const parameter = fn && fn.params[index];
      const binding = parameter?.type === 'Identifier' && lookup(scopes.get(fn), parameter.name);
      return !!binding && binding.writes.length === 0;
    },
    wrapperCalls(wrappers) {
      const byNode = new Map([...wrappers].filter(w => w.node).map(w => [w.node, w]));
      return calls.flatMap(call => {
        const actual = target(call.callee);
        const related = possible(call.callee).find(fn => byNode.has(fn));
        const wrapper = byNode.get(actual) || byNode.get(related);
        if (!wrapper) return [];
        const argument = call.arguments[wrapper.urlParamIndex];
        const url = wrapper.options ? urlValue(argument) : argument;
        const overridden = wrapper.overrides.some(i => !lacksUrl(call.arguments[i]));
        return [{ node: call, wrapper, proven: actual === wrapper.node && !!url && !overridden && finite(url) }];
      });
    },
  };
}

function scanU1(jsFiles, backendRoutes, namespacePrefixes = collectBackendMappings(REPO_ROOT).namespacePrefixes) {
  const violations = [];
  const dynamicReports = [];
  const variableReports = [];
  let staticCount = 0;
  for (const file of jsFiles) {
    const source = fs.readFileSync(file, 'utf8');
    const namespaceOffsets = namespaceDeclarationOffsets(source, namespacePrefixes);
    for (const parsed of collectApiLiterals(source)) {
      if (namespaceOffsets.has(parsed.offset)) continue;
      const key = `${repoRel(file)}:${lineNumber(source, parsed.offset)}`;
      if (parsed.dynamic) dynamicReports.push({ key, route: parsed.route });
      else staticCount += 1;
      if (![...backendRoutes].some((route) => routeMatches(parsed.route, route))) {
        violations.push({ code: 'U1', key, message: `${parsed.dynamic ? '动态' : '静态'}路径 ${parsed.route} 无后端 Mapping` });
      }
    }
    const masked = maskNonCode(source);
    const finite = finiteApiAnalysis(source, backendRoutes);
    const wrappers = new Map();
    const requestRe = requestTransportRegex(source, masked);
    let request;
    while ((request = requestRe.exec(masked))) {
      const openParen = masked.indexOf('(', request.index);
      let argStart = openParen + 1;
      while (/\s/.test(masked[argStart])) argStart += 1;
      const enclosing = enclosingNamedFunctions(masked, request.index);
      if (finite.requestFinite(request.index)) continue;
      let expression = '';
      if (masked[argStart] === '{') {
        const closeBrace = matchBrace(masked, argStart);
        if (closeBrace < 0) continue;
        const { properties } = objectProperties(source, masked, argStart, closeBrace);
        const urlProp = properties.find((property) => property.name === 'url');
        if (urlProp) {
          const colon = urlProp.raw.indexOf(':');
          expression = colon >= 0 ? urlProp.raw.slice(colon + 1).trim() : urlProp.name;
        }
      } else {
        const closeParen = masked.indexOf(')', argStart);
        expression = source.slice(argStart, closeParen < 0 ? argStart + 120 : closeParen).trim();
      }
      let urlParamIndex = -1;
      const wrapper = enclosing.sort((a, b) => b.nameOffset - a.nameOffset).find((fn) => fn.params.some((param, index) => {
        const matches = (expression === param
          && (fn.kind !== 'property' || param === 'url'))
        || (fn.kind !== 'property' && /^Object\.assign\s*\(/.test(expression)
          && new RegExp(`\\b${param}\\b`).test(expression));
        if (matches) urlParamIndex = index;
        return matches;
      }));
      const wrapperNode = wrapper && finite.functionAt(wrapper.nameOffset);
      const mode = wrapper && finite.wrapperMode(request.index, wrapperNode, urlParamIndex);
      if (mode && finite.stableParameter(wrapperNode, urlParamIndex) &&
          mode.overrides.every(i => finite.stableParameter(wrapperNode, i))) {
        wrappers.set(wrapper.nameOffset, { ...wrapper, urlParamIndex, options: mode.mode === 'options',
          overrides: mode.overrides, node: wrapperNode });
        continue;
      }
      const key = `${repoRel(file)}:${lineNumber(source, request.index)}`;
      const allowed = U1_VARIABLE_ALLOWLIST.get(key);
      variableReports.push({ key, detail: expression.slice(0, 100) || 'sendRequest 参数无静态 url', allowed });
      if (!allowed) {
        violations.push({ code: 'U1', key, message: '运行时变量请求路径无法核对，必须改成可枚举 /api 字面量或受控 wrapper' });
      }
    }
    for (const call of finite.wrapperCalls(wrappers.values())) {
      if (call.proven) continue;
      const key = `${repoRel(file)}:${lineNumber(source, call.node.start)}`;
      const allowed = U1_VARIABLE_ALLOWLIST.get(key);
      variableReports.push({ key, detail: source.slice(call.node.start, call.node.end).slice(0, 100), allowed });
      if (!allowed) violations.push({ code: 'U1', key,
        message: `受控 wrapper ${call.wrapper.name} 的调用目标或路径不是可证明的有限 /api 表达式` });
    }
  }
  return { violations, staticCount, dynamicReports, variableReports, backendRouteCount: backendRoutes.size };
}

const PAGE_LIFECYCLES = new Set([
  'onLoad', 'onReady', 'onShow', 'onHide', 'onUnload', 'onPullDownRefresh',
  'onReachBottom', 'onShareAppMessage', 'onShareTimeline', 'onPageScroll',
  'onResize', 'onTabItemTap', 'onAddToFavorites', 'onSaveExitState',
]);
const HANDLER_LIKE_RE = /^(?:on|go|tap|handle|retry)[A-Z0-9_$]/;

function collectWxmlSource(file, seen = new Set()) {
  if (!file || seen.has(file) || !fs.existsSync(file)) return '';
  seen.add(file);
  const source = fs.readFileSync(file, 'utf8');
  let combined = source;
  const includeRe = /<include\s+src=["']([^"']+)["']/g;
  let match;
  while ((match = includeRe.exec(source))) {
    combined += `\n${collectWxmlSource(path.resolve(path.dirname(file), match[1]), seen)}`;
  }
  return combined;
}

function methodLine(source, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|\\n)\\s*(?:async\\s+)?${escaped}\\s*(?:\\(|:)`).exec(source);
  return match ? lineNumber(source, match.index) : 1;
}

function jsReferencesMethod(source, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const references = source.match(new RegExp(`\\b${escaped}\\b`, 'g')) || [];
  return references.length > 1;
}

function scanU3(jsFiles, debtBaseline = U3_DEBT_BASELINE) {
  const violations = [];
  const candidates = [];
  // 2026-10-14(取自 cloud22 归档已审逻辑):Page 工厂(subpackageTools/lib/runtime 等)里的共享方法,
  // 只要挂同一工厂的某个真实页面在 WXML 里绑定了它,就不算孤儿 —— 否则每个工厂页都会把没用到的共享方法报一遍。
  // 只放行「定义在别的文件、且被别页真实绑定」的那一份定义;页面自己文件里的方法仍按原规则判。
  const sharedBound = new Set();
  const ownDefs = new Map();
  for (const file of jsFiles) {
    const wxmlFile = file.replace(/\.js$/, '.wxml');
    if (!fs.existsSync(wxmlFile)) continue;
    const entries = extractOwnHandlerEntries(file);
    ownDefs.set(file, new Map(entries.map((entry) => [entry.name, entry])));
    const names = new Set(findEventBindings(collectWxmlSource(wxmlFile)).flatMap((binding) => binding.value.match(/[A-Za-z_$][\w$]*/g) || []));
    entries.forEach((entry) => { if (entry.file !== file && names.has(entry.name)) sharedBound.add(`${entry.file}:${entry.start}:${entry.end}`); });
  }
  for (const file of jsFiles) {
    const wxmlFile = file.replace(/\.js$/, '.wxml');
    if (!fs.existsSync(wxmlFile)) continue;
    const source = fs.readFileSync(file, 'utf8');
    // extractHandlerNames 会跟进 `...MODULE_METHODS` 拿到 spread 目标里的定义,
    // 但「js 内部有没有引用它」不能只看宿主正文 —— 定义在目标文件、调用在宿主,
    // 只搜宿主就把 onPrepareSession 这类恒判成孤儿。目标要**去重**:
    // 同一个模块常被 spread 两次(DATA + METHODS 同源),拼两遍会让目标里每个定义
    // 都「出现两次」,反过来把真孤儿静默放行,比不修更糟。
    const spreadSources = [...new Set(resolveSpreadRequireTargets(source))].map((rel) => {
      try { return fs.readFileSync(path.resolve(path.dirname(file), rel), 'utf8'); } catch (e) { return ''; }
    });
    const jsScope = maskNonCode([source].concat(spreadSources).join('\n'));
    const wxml = collectWxmlSource(wxmlFile);
    const bound = new Set();
    for (const binding of findEventBindings(wxml)) {
      const names = binding.value.match(/[A-Za-z_$][\w$]*/g) || [];
      names.forEach((name) => bound.add(name));
    }
    for (const name of new Set(extractHandlerNames(file))) {
      if (!HANDLER_LIKE_RE.test(name) || PAGE_LIFECYCLES.has(name) || bound.has(name)
          || jsReferencesMethod(jsScope, name)) continue;
      const def = ownDefs.get(file) && ownDefs.get(file).get(name);
      if (def && def.file !== file && sharedBound.has(`${def.file}:${def.start}:${def.end}`)) continue;
      const key = `${repoRel(file)}#${name}`;
      const allowed = debtBaseline.get(key);
      const item = { key, line: methodLine(source, name), allowed };
      candidates.push(item);
      if (!allowed) violations.push({ code: 'U3', key: `${repoRel(file)}:${item.line}`, message: `${name} 是零 WXML 绑定的 handler-like 方法` });
    }
  }
  return { violations, candidates };
}

function wxmlConsumesField(wxml, field) {
  const escaped = field.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^\\w$.])${escaped}\\b`).test(wxml.replace(/<!--[\s\S]*?-->/g, ' '));
}

function stripCommentsOnly(source) {
  const out = source.split('');
  let quote = null;
  for (let i = 0; i < source.length; i += 1) {
    if (quote) {
      if (source[i] === '\\') { i += 1; continue; }
      if (source[i] === quote) quote = null;
      continue;
    }
    if (source[i] === "'" || source[i] === '"' || source[i] === '`') {
      quote = source[i];
      continue;
    }
    if (source[i] === '/' && source[i + 1] === '/') {
      while (i < source.length && source[i] !== '\n') { out[i] = ' '; i += 1; }
      i -= 1;
      continue;
    }
    if (source[i] === '/' && source[i + 1] === '*') {
      out[i] = ' '; out[i + 1] = ' '; i += 2;
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) {
        if (source[i] !== '\n') out[i] = ' ';
        i += 1;
      }
      if (i < source.length) { out[i] = ' '; out[i + 1] = ' '; i += 1; }
    }
  }
  return out.join('');
}

function jsConsumesDataField(source, field) {
  const escaped = field.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const dotRead = new RegExp(`\\bthis\\.data\\.${escaped}\\b`).test(maskNonCode(source));
  const bracketRead = new RegExp(`\\bthis\\.data\\[['\"]${escaped}['\"]\\]`).test(stripCommentsOnly(source));
  return dotRead || bracketRead;
}

function scanU4(jsFiles, debtBaseline = U4_DEBT_BASELINE, dynamicAllowlist = U4_DYNAMIC_ALLOWLIST) {
  const violations = [];
  const candidates = [];
  const dynamicReports = [];
  for (const file of jsFiles) {
    const wxmlFile = file.replace(/\.js$/, '.wxml');
    if (!fs.existsSync(wxmlFile)) continue;
    const source = fs.readFileSync(file, 'utf8');
    const masked = maskNonCode(source);
    const wxml = collectWxmlSource(wxmlFile);
    const seenFields = new Set();
    const callRe = /\b(?:this|that)\s*\.\s*setData\s*\(/g;
    let call;
    while ((call = callRe.exec(masked))) {
      const openParen = masked.indexOf('(', call.index);
      let argStart = openParen + 1;
      while (/\s/.test(masked[argStart])) argStart += 1;
      const line = lineNumber(source, call.index);
      if (masked[argStart] !== '{') {
        const key = `${repoRel(file)}:${line}`;
        const allowed = dynamicAllowlist.get(key);
        dynamicReports.push({ key, allowed, detail: 'setData 参数不是对象字面量' });
        if (!allowed) violations.push({ code: 'U4', key, message: '动态 setData 未登记,无法核对顶层字段消费' });
        continue;
      }
      const closeBrace = matchBrace(masked, argStart);
      if (closeBrace < 0) {
        violations.push({ code: 'U4', key: `${repoRel(file)}:${line}`, message: 'setData 对象大括号无法配对' });
        continue;
      }
      const { properties, dynamic } = objectProperties(source, masked, argStart, closeBrace);
      for (const entry of dynamic) {
        const key = `${repoRel(file)}:${lineNumber(source, entry.offset)}`;
        const allowed = dynamicAllowlist.get(key);
        dynamicReports.push({ key, allowed, detail: entry.raw.slice(0, 100) });
        if (!allowed) violations.push({ code: 'U4', key, message: '动态 setData 字段未登记,无法核对 WXML 消费' });
      }
      for (const property of properties) {
        const field = property.name.split(/[.[]/, 1)[0];
        if (!field || seenFields.has(field) || wxmlConsumesField(wxml, field)
            || jsConsumesDataField(source, field)) continue;
        seenFields.add(field);
        const key = `${repoRel(file)}#${field}`;
        const allowed = debtBaseline.get(key);
        const item = { key, line: lineNumber(source, property.offset), allowed };
        candidates.push(item);
        if (!allowed) violations.push({ code: 'U4', key: `${repoRel(file)}:${item.line}`, message: `setData 顶层字段 ${field} 在同页 WXML 零消费` });
      }
    }
  }
  return { violations, candidates, dynamicReports };
}

function namedFunctionBodies(source, name) {
  const masked = maskNonCode(source);
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const patterns = [
    new RegExp(`\\bfunction\\s+${escaped}\\s*\\([^)]*\\)\\s*\\{`, 'g'),
    new RegExp(`\\b${escaped}\\s*:\\s*function\\s*\\([^)]*\\)\\s*\\{`, 'g'),
    new RegExp(`\\b${escaped}\\s*=\\s*(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>\\s*\\{`, 'g'),
    new RegExp(`\\b${escaped}\\s*\\([^)]*\\)\\s*\\{`, 'g'),
  ];
  const bodies = [];
  for (const pattern of patterns) {
    let match;
    while ((match = pattern.exec(masked))) {
      const open = masked.indexOf('{', match.index);
      const close = open < 0 ? -1 : matchBrace(masked, open);
      if (close > open) {
        const signature = source.slice(match.index, open);
        const paramStart = signature.indexOf('(');
        const paramEnd = signature.lastIndexOf(')');
        let params = [];
        if (paramStart >= 0 && paramEnd > paramStart) {
          const signatureMasked = maskNonCode(signature);
          params = splitTopLevel(signatureMasked, paramStart + 1, paramEnd)
            .map(([start, end]) => signature.slice(start, end).trim())
            .map((item) => (/^[A-Za-z_$][\w$]*$/.test(item) ? item : null));
        } else {
          const arrowParam = /=\s*([A-Za-z_$][\w$]*)\s*=>/.exec(signature);
          if (arrowParam) params = [arrowParam[1]];
        }
        bodies.push({ body: source.slice(open + 1, close), params });
      }
    }
  }
  return bodies;
}

function matchParen(masked, openParen) {
  let depth = 0;
  for (let i = openParen; i < masked.length; i += 1) {
    if (masked[i] === '(') depth += 1;
    else if (masked[i] === ')' && --depth === 0) return i;
  }
  return -1;
}

function helperRendersVisibleMessage(file, body, literalParams) {
  const masked = maskNonCode(body);
  const wxmlPath = file.replace(/\.js$/, '.wxml');
  const wxml = fs.existsSync(wxmlPath) ? collectWxmlSource(wxmlPath) : '';
  const tainted = new Set(literalParams);
  const assignmentRe = /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\n]+)/g;
  let changed = true;
  while (changed) {
    changed = false;
    assignmentRe.lastIndex = 0;
    let assignment;
    while ((assignment = assignmentRe.exec(masked))) {
      if (tainted.has(assignment[1])) continue;
      if ([...tainted].some((name) => new RegExp(`\\b${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`).test(assignment[2]))) {
        tainted.add(assignment[1]);
        changed = true;
      }
    }
  }
  const setDataRe = /\bsetData\s*\(\s*\{/g;
  let match;
  while ((match = setDataRe.exec(masked))) {
    const patchStart = masked.indexOf('{', match.index);
    const patchEnd = patchStart < 0 ? -1 : matchBrace(masked, patchStart);
    if (patchEnd < 0) break;
    const patch = objectProperties(body, masked, patchStart, patchEnd).properties;
    if (patch.some((property) => /(?:err|error|message|messages)/i.test(property.name)
      && wxmlConsumesField(wxml, property.name.split(/[.[]/, 1)[0])
      && [...tainted].some((name) => new RegExp(`\\b${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`)
        .test(maskNonCode(property.raw))))) return true;
    setDataRe.lastIndex = patchEnd + 1;
  }
  return false;
}

function hasVisibleU5Feedback(file, source, candidate, seen = new Set()) {
  const masked = maskNonCode(candidate);
  // 2026-09-06 原生弹层全删:轻提示统一走 utils/toast.js(toast / toast.success / toast.error / cyToast),它们就是可见反馈
  if (/\bshowToast\s*\(|\.tips\s*\(|\b(?:cy)?[tT]oast(?:\.(?:success|error|show))?\s*\(/.test(masked)) return true;
  const wxmlPath = file.replace(/\.js$/, '.wxml');
  const wxml = fs.existsSync(wxmlPath) ? collectWxmlSource(wxmlPath) : '';
  const setDataRe = /\bsetData\s*\(\s*\{/g;
  let setDataMatch;
  while ((setDataMatch = setDataRe.exec(masked))) {
    const patchStart = masked.indexOf('{', setDataMatch.index);
    const patchEnd = patchStart < 0 ? -1 : matchBrace(masked, patchStart);
    if (patchEnd < 0) break;
    const patch = objectProperties(candidate, masked, patchStart, patchEnd).properties;
    if (patch.some((property) => {
      const field = property.name.split(/[.[]/, 1)[0];
      const requestErrorFallback = /\bgetRequestErrorMessage\s*\([^)]*,\s*['"][^'"]+['"]/.test(candidate);
      const errorField = /(?:err|error|empty|offline|failed)/i.test(property.name)
        && (/\:\s*true\b/.test(property.raw)
          || /['"][^'"]+['"]/.test(property.raw)
          || /\:\s*[A-Z_$][A-Z0-9_$]*(?:ERROR|EMPTY|OFFLINE|FAILED)[A-Z0-9_$]*/.test(property.raw)
          || requestErrorFallback);
      const explicitErrorState = /(?:state|status)$/i.test(property.name)
        && /['"](?:error|empty|offline|failed)['"]/i.test(property.raw);
      const errorSemantic = errorField || explicitErrorState;
      return errorSemantic && wxmlConsumesField(wxml, field);
    })) return true;
    setDataRe.lastIndex = patchEnd + 1;
  }
  if (seen.size >= 6) return false;
  const callRe = /(?:(?:this|that)\s*\.\s*)?\b([A-Za-z_$][\w$]*)\s*\(/g;
  let call;
  while ((call = callRe.exec(masked))) {
    const name = call[1];
    if (seen.has(name) || ['resolve', 'reject', 'setData'].includes(name)) continue;
    const bodies = namedFunctionBodies(source, name);
    if (!bodies.length) continue;
    const openParen = callRe.lastIndex - 1;
    const closeParen = matchParen(masked, openParen);
    if (closeParen > openParen && bodies.some(({ body, params }) => {
      const args = splitTopLevel(masked, openParen + 1, closeParen)
        .map(([start, end]) => candidate.slice(start, end).trim());
      const literalParams = params.filter((param, index) => param && /^(['"])[^'"]+\1$/.test(args[index] || ''));
      return literalParams.length && helperRendersVisibleMessage(file, body, literalParams);
    })) return true;
    const nextSeen = new Set(seen);
    nextSeen.add(name);
    if (bodies.some(({ body }) => hasVisibleU5Feedback(file, source, body, nextSeen))) return true;
  }
  return false;
}

// Resolve only a local const, zero-argument expression arrow used as fail.
// Look in the current open lexical block, never an unrelated function.
function referencedFailBody(source, masked, requestStart, failSource) {
  const reference = /^\s*fail\s*:\s*([A-Za-z_$][\w$]*)\s*$/.exec(failSource);
  if (!reference) return failSource;
  const blocks = [];
  for (let i = 0; i < requestStart; i += 1) {
    if (masked[i] === '{') blocks.push(i);
    else if (masked[i] === '}') blocks.pop();
  }
  const start = (blocks[blocks.length - 1] ?? -1) + 1;
  const name = reference[1].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const prefix = masked.slice(start, requestStart);
  const declaration = new RegExp('\\bconst\\s+' + name + '\\s*=\\s*\\(\\s*\\)\\s*=>\\s*([^;{}\\n]+);', 'g');
  const definitions = [...prefix.matchAll(declaration)];
  if (definitions.length !== 1) return failSource;
  const definition = definitions[0];
  let depth = 0;
  for (const token of prefix.slice(0, definition.index)) {
    if (token === '{') depth += 1;
    else if (token === '}') depth -= 1;
  }
  if (depth !== 0) return failSource;
  const offset = start + definition.index + definition[0].indexOf(definition[1]);
  return source.slice(offset, offset + definition[1].length);
}

function scanU5(jsFiles, allowlist = U5_SILENT_ALLOWLIST) {
  const violations = [];
  const callsites = [];
  const autoSafe = [];
  for (const file of jsFiles) {
    const source = fs.readFileSync(file, 'utf8');
    const masked = maskNonCode(source);
    const requestOffsets = [...masked.matchAll(requestTransportRegex(source, masked))].map(match => match.index);
    const re = /\bsilentError\s*:\s*true\b/g;
    let match;
    while ((match = re.exec(masked))) {
      const key = `${repoRel(file)}:${lineNumber(source, match.index)}`;
      const requestStart = requestOffsets.filter(offset => offset < match.index).pop() ?? -1;
      const objectStart = requestStart < 0 ? -1 : masked.indexOf('{', requestStart);
      const objectEnd = objectStart < 0 ? -1 : matchBrace(masked, objectStart);
      const sameRequest = objectStart >= 0 && objectStart < match.index && objectEnd > match.index
        ? source.slice(objectStart, objectEnd + 1) : '';
      const sameRequestMasked = maskNonCode(sameRequest);
      const { properties } = sameRequest
        ? objectProperties(sameRequest, sameRequestMasked, 0, sameRequest.length - 1)
        : { properties: [] };
      const failProperty = properties.find((property) => property.name === 'fail');
      const failSource = referencedFailBody(source, masked, requestStart, failProperty ? failProperty.raw : '');
      const visibleFallback = hasVisibleU5Feedback(file, source, failSource);
      if (visibleFallback) {
        autoSafe.push({ key, detail: 'fail 回调内存在 toast/tips 或 WXML 已消费的错误/空态 setData' });
        continue;
      }
      const allowed = allowlist.get(key);
      callsites.push({ key, allowed });
      if (!allowed) violations.push({ code: 'U5', key, message: 'silentError:true 调用点未登记允许静默的理由' });
    }
  }
  return { violations, callsites, autoSafe };
}

function scanLegacy(jsFiles, wxmlFiles) {
  const errors = [];
  const warnings = [];
  const app = JSON.parse(fs.readFileSync(path.join(XCX_ROOT, 'app.json'), 'utf8'));
  const registered = new Set((app.pages || []).map((page) => `/${page}`));
  for (const subpackage of app.subPackages || []) {
    const root = subpackage.root.replace(/^\/|\/$/g, '');
    for (const page of subpackage.pages || []) registered.add(`/${root}/${page}`);
  }
  const normalize = (raw) => raw.trim().replace(/[?#].*$/, '').replace(/\/$/, '');
  for (const file of jsFiles) {
    const source = fs.readFileSync(file, 'utf8');
    let match;
    const navRe = /(?:navigateTo|redirectTo|reLaunch)\s*\(\s*\{[^}]*?url:\s*[`'"]([^`'"]+)[`'"]/g;
    while ((match = navRe.exec(source))) {
      const target = normalize(match[1]);
      if (target.startsWith('/') && !target.includes('${') && !registered.has(target)) {
        errors.push({ code: 'LEGACY', key: `${repoRel(file)}:${lineNumber(source, match.index)}`, message: `导航目标 ${target} 未在 app.json 注册` });
      }
    }
    // 提示文案闸都扫剥掉注释后的代码:注释里提到「参数错误」不算,代码里 msg.includes('参数错误') 也不算(那是在拦它)。
    const code = stripCommentsOnly(source);
    const TOAST_CALL = '(?:app\\.tips|(?:cy)?[tT]oast(?:\\.(?:success|error|show))?)\\s*\\(';
    const PLACEHOLDER_WORDS = '开发中|暂未开放|暂未上线|敬请期待|即将开放|功能建设中|待开放|即将上线|尚未可用|尚未开放|实验预览';
    const placeholderRe = new RegExp(`(?:(?:title|content):|${TOAST_CALL})\\s*[\`'"][^\`'"\\n]*(?:${PLACEHOLDER_WORDS})[^\`'"\\n]*[\`'"]`, 'g');
    while ((match = placeholderRe.exec(code))) {
      warnings.push({ code: 'LEGACY', key: `${repoRel(file)}:${lineNumber(code, match.index)}`, message: '存在占位入口文案' });
    }
    // 怪文案闸(审核清单 §6.3):开发者口径 / 没有下一步的网络文案 / 半角逗号 / 超长 toast。
    // safe-user-message.js 是净化层本身,里面出现这些词是为了拦它们。
    if (!repoRel(file).endsWith('utils/transport/safe-user-message.js')) {
      const devTermRe = /[`'"][^`'"\n]*(?:参数错误|参数异常|数据异常|系统错误|系统异常|网络请求失败)[^`'"\n]*[`'"]/g;
      while ((match = devTermRe.exec(code))) {
        // includes('参数错误') / === '参数错误' 是在识别后端原话,不是给用户看的
        if (/(?:includes|indexOf|startsWith|test)\(\s*$|[=!]==?\s*$/.test(code.slice(Math.max(0, match.index - 14), match.index))) continue;
        warnings.push({ code: 'LEGACY', key: `${repoRel(file)}:${lineNumber(code, match.index)}`, message: '开发者口径文案(参数/数据异常类),要写成用户下一步能做什么' });
      }
      const bareNetRe = /[`'"](?:网络错误|网络异常)[`'"]/g;
      while ((match = bareNetRe.exec(code))) {
        warnings.push({ code: 'LEGACY', key: `${repoRel(file)}:${lineNumber(code, match.index)}`, message: '网络文案没有下一步,统一「网络异常，请重试」' });
      }
      const toastLiteralRe = new RegExp(`${TOAST_CALL}\\s*([\`'"])([^\`'"\\n]+)\\1`, 'g');
      while ((match = toastLiteralRe.exec(code))) {
        const text = match[2];
        const key = `${repoRel(file)}:${lineNumber(code, match.index)}`;
        if (text.includes(',')) warnings.push({ code: 'LEGACY', key, message: '提示文案用了半角逗号' });
        const size = text.replace(/\$\{[^}]*\}/g, '').replace(/\s/g, '').length;   // 模板占位不算字数
        if (size > 16) warnings.push({ code: 'LEGACY', key, message: `提示文案 ${size} 字,超过 16 字上限,长说明请落到页内错误态` });
      }
    }
  }
  for (const file of wxmlFiles) {
    const source = fs.readFileSync(file, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
    const navRe = /<navigator[^>]*\burl=["']([^"']+)["']/g;
    let match;
    while ((match = navRe.exec(source))) {
      const target = normalize(match[1]);
      if (target.startsWith('/') && !target.includes('{{') && !registered.has(target)) {
        errors.push({ code: 'LEGACY', key: `${repoRel(file)}:${lineNumber(source, match.index)}`, message: `navigator 目标 ${target} 未在 app.json 注册` });
      }
    }
  }
  return { errors, warnings };
}

function enforcedLegacyViolations(legacy, warnAsError = WARN_AS_ERROR) {
  return [...legacy.errors, ...(warnAsError ? legacy.warnings : [])];
}

function assertSelftest(name, bad, good, detail) {
  if (!bad || !good) throw new Error(`${name} 自证失败:${detail}`);
  console.log(`[SELFTEST][${name}] RED caught; restored GREEN — ${detail}`);
}

function baselineKeyDigest(baseline) {
  return crypto.createHash('sha256').update([...baseline.keys()].sort().join('\n')).digest('hex');
}

function baselineContract(name, baseline, observedKeys, ratchetLimit, ceiling = ratchetLimit,
  expectedDigest) {
  const violations = [];
  const observed = new Set(observedKeys);
  for (const [key, reason] of baseline) {
    if (!observed.has(key)) {
      violations.push({ code: 'BASELINE', key, message: `${name} 基线已未命中，必须删除过期项` });
    }
    if (typeof reason !== 'string' || reason.trim().length < 12) {
      violations.push({ code: 'BASELINE', key, message: `${name} 保留理由必须逐项写明真实原因` });
    }
  }
  if (!Number.isInteger(ratchetLimit) || ratchetLimit !== baseline.size) {
    violations.push({
      code: 'BASELINE', key: name,
      message: `${name} 棘轮=${ratchetLimit}、基线=${baseline.size}；修复后必须显式同步调小，新增不得隐式放宽`,
    });
  }
  if (!Number.isInteger(ceiling) || baseline.size > ceiling) {
    violations.push({
      code: 'BASELINE', key: name,
      message: `${name} 基线=${baseline.size} 超过冻结上限=${ceiling}；债务/豁免只准变小，新增必须单独审查并显式改门禁`,
    });
  }
  if (typeof expectedDigest !== 'string' || !/^[0-9a-f]{64}$/.test(expectedDigest)) {
    violations.push({
      code: 'BASELINE', key: name,
      message: `${name} keyDigest 缺失或格式无效；债务/豁免 key 集合必须显式冻结`,
    });
  } else if (expectedDigest !== baselineKeyDigest(baseline)) {
    violations.push({
      code: 'BASELINE', key: name,
      message: `${name} key 集合变化但 digest 未显式更新；等量替换也必须单独审查`,
    });
  }
  return violations;
}

function runSelftest() {
  const fixtureRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'ui-gate-0-'));
  try {
    const js = path.join(fixtureRoot, 'index.js');
    const wxml = path.join(fixtureRoot, 'index.wxml');

    fs.writeFileSync(js, "function req(url){return getApp().sendRequest({url})}Page({load(){req('/api/missing')}})\n");
    fs.writeFileSync(wxml, '<view bindtap="load"></view>\n');
    const u1Bad = scanU1([js], new Set(['/api/present']), new Map()).violations.some((v) => v.code === 'U1');
    fs.writeFileSync(js, "function req(url){return getApp().sendRequest({url})}Page({load(){req('/api/present')}})\n");
    const u1Good = scanU1([js], new Set(['/api/present']), new Map()).violations.length === 0;
    const chained = collectApiLiterals("req('/api/present/' + id + '/accept')")[0];
    const u1ChainedGood = chained.route === '/api/present/:dynamic/accept'
      && routeMatches(chained.route, '/api/present/{id}/accept')
      && !routeMatches('/api/present/:dynamic', '/api/present/{id}/extra');
    fs.writeFileSync(js, "const route=getRuntimeRoute();Page({load(){getApp().sendRequest({url:route})}})\n");
    const u1VariableBad = scanU1([js], new Set(['/api/present'])).violations
      .some((item) => item.message.includes('运行时变量'));
    fs.writeFileSync(js, "function req(url){return getApp().sendRequest({url})}Page({load(){req(getRuntimeRoute())}})\n");
    const u1WrapperVariableBad = scanU1([js], new Set(['/api/present'])).violations
      .some((item) => item.message.includes('受控 wrapper'));
    fs.writeFileSync(js, "Page({onTap(e){getApp().sendRequest({url:e.currentTarget.dataset.url})}})\n");
    const u1HandlerVariableBad = scanU1([js], new Set(['/api/present'])).violations
      .some((item) => item.message.includes('运行时变量'));
    assertSelftest('U1', u1Bad && u1VariableBad && u1WrapperVariableBad && u1HandlerVariableBad,
      u1Good && u1ChainedGood,
      '不存在的 /api Mapping 会阻断，拼接后缀与路由段数不会 fail-open');

    fs.writeFileSync(js, 'Page({ realHandler() {} })\n');
    fs.writeFileSync(wxml, '<button bindtap="ghostHandler">x</button>\n');
    const u2Bad = lintWxmlHandlers(fixtureRoot).violations.length === 1;
    fs.writeFileSync(js, 'Page({ ghostHandler() {} })\n');
    const u2Good = lintWxmlHandlers(fixtureRoot).violations.length === 0;
    assertSelftest('U2', u2Bad, u2Good, '真实 lintRepo 扫描路径能判红并恢复绿');

    fs.writeFileSync(js, 'Page({ retryLoad() {} })\n');
    fs.writeFileSync(wxml, '<view>no binding</view>\n');
    const u3Bad = scanU3([js], new Map()).violations.some((v) => v.code === 'U3');
    fs.writeFileSync(wxml, '<button bindtap="retryLoad">retry</button>\n');
    const u3Good = scanU3([js], new Map()).violations.length === 0;
    assertSelftest('U3', u3Bad, u3Good, '零绑定 handler-like 方法会阻断');

    fs.writeFileSync(js, 'Page({ load() { this.setData({ reco: [] }) } })\n');
    fs.writeFileSync(wxml, '<view>empty</view>\n');
    const u4Bad = scanU4([js], new Map(), new Map()).violations.some((v) => v.code === 'U4');
    fs.writeFileSync(wxml, '<view wx:for="{{reco}}">{{item}}</view>\n');
    const u4WxmlGood = scanU4([js], new Map(), new Map()).violations.length === 0;
    fs.writeFileSync(js, "Page({ load() { this.setData({ reco: [] }); return this.data['reco'] } })\n");
    fs.writeFileSync(wxml, '<view>JS-only state</view>\n');
    const u4BracketGood = scanU4([js], new Map(), new Map()).violations.length === 0;
    fs.writeFileSync(js, 'Page({ writeBack() { this._prev.setData({ nodes: [] }) } })\n');
    const u4ForeignReceiverGood = scanU4([js], new Map(), new Map()).violations.length === 0;
    assertSelftest('U4', u4Bad, u4WxmlGood && u4BracketGood && u4ForeignReceiverGood,
      'WXML 零消费会阻断，this.data bracket 真实消费不会被注释掩码误报');

    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true})}})\n");
    const u5Bad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){}})}})\n");
    const u5EmptyFailBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){resolve(null)}})}})\n");
    const u5ResolveNullBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(err){auditFailures.push(err)}})}})\n");
    const u5SideEffectFailBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();function renderFailure(page){page.setData({loading:false})}Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){renderFailure(this)}})}})\n");
    fs.writeFileSync(wxml, '<view wx:if="{{loading}}">加载中</view>\n');
    const u5HelperLoadingBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();function renderFailure(page,label){auditFailures.push(label);page.setData({message:''})}Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){renderFailure(this,'audit')}})}})\n");
    fs.writeFileSync(wxml, '<view>{{message}}</view>\n');
    const u5HelperEmptyMessageBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();function renderFailure(ignored=0,message,page){page.setData({message:message})}Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){renderFailure('audit',null,this)}})}})\n");
    const u5HelperParamShiftBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,success(){wx.showToast({title:'完成'})},fail(){resolve(null)}})}})\n");
    const u5SuccessToastBad = scanU5([js], new Map()).violations.some((v) => v.code === 'U5');
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){wx.showToast({title:'网络异常'})}})}})\n");
    const u5VisibleToastGood = scanU5([js], new Map()).violations.length === 0;
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){this.setData({loadError:true})}})}})\n");
    fs.writeFileSync(wxml, '<view wx:if="{{loadError}}">加载失败</view>\n');
    const u5VisibleStateGood = scanU5([js], new Map()).violations.length === 0;
    fs.writeFileSync(js, "const app=getApp();function renderFailure(page){page.setData({state:'ERROR'})}Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){renderFailure(this)}})}})\n");
    fs.writeFileSync(wxml, '<view wx:if="{{state === \'ERROR\'}}">加载失败</view>\n');
    const u5VisibleHelperStateGood = scanU5([js], new Map()).violations.length === 0;
    const u5Key = `${repoRel(js)}:1`;
    fs.writeFileSync(js, "const app=getApp();Page({load(){app.sendRequest({url:'/api/present',silentError:true,fail(){resolve(null)}})}})\n");
    const u5AllowlistedGood = scanU5([js], new Map([[u5Key, 'fixture:调用方确认该增强允许静默']])).violations.length === 0;
    assertSelftest('U5', u5Bad && u5EmptyFailBad && u5ResolveNullBad
      && u5SideEffectFailBad && u5HelperLoadingBad && u5HelperEmptyMessageBad
      && u5HelperParamShiftBad && u5SuccessToastBad,
    u5VisibleToastGood && u5VisibleStateGood && u5VisibleHelperStateGood && u5AllowlistedGood,
    'resolve(null)、纯副作用、helper 仅关闭 loading/写空 message/参数错位及 success-only toast 都会红；fail 内可见 toast/错误态与显式逐项登记会绿');

    const roamForbiddenBaselineKeys = [
      'chengyinhub-xcx/pages/roam/index.js#retryReco',
      'chengyinhub-xcx/pages/roam/index.js#reco',
      'chengyinhub-xcx/pages/roam/index.js#goal',
      'chengyinhub-xcx/pages/roam/index.js#onGoalTap',
    ];
    const baselineSections = [BASELINE.cleanupPlan || {}, BASELINE.u3Debt || {}, BASELINE.u4Debt || {}];
    const roamBaselineClear = roamForbiddenBaselineKeys.every((key) => baselineSections.every((section) => !section[key]));
    fs.writeFileSync(js, 'Page({ retryReco() {}, onGoalTap() {}, load() { this.setData({ reco: [], goal: {} }) } })\n');
    fs.writeFileSync(wxml, '<view>no roam controls</view>\n');
    const roamU3Bad = scanU3([js], new Map()).violations.filter((item) => item.code === 'U3').length === 2;
    const roamU4Bad = scanU4([js], new Map(), new Map()).violations.filter((item) => item.code === 'U4').length === 2;
    fs.writeFileSync(js, 'Page({ onGoalTap() {}, load() { this.setData({ goal: {} }) } })\n');
    fs.writeFileSync(wxml, '<view bindtap="onGoalTap">{{goal.text}}</view>\n');
    const roamWiredGood = scanU3([js], new Map()).violations.length === 0
      && scanU4([js], new Map(), new Map()).violations.length === 0;
    assertSelftest('ROAM-NAMING', roamBaselineClear && roamU3Bad && roamU4Bad, roamWiredGood,
      'retryReco/reco 死链必须删除，goal/onGoalTap 必须 WXML 可见且真实绑定，四个 key 禁止 baseline 逃逸');

    const placeholderBad = [
      "Page({show(){wx.showToast({title:'功能建设中'})}})\n",
      "Page({show(){cyToast('二维码功能即将上线')}})\n",
      "Page({show(){toast('参数错误')}})\n",
      "Page({show(){toast('网络异常')}})\n",
      "Page({show(){toast('这家还没有在平台建档,先电话联系')}})\n",
      "Page({show(){toast.error('主办退出必须先完成受控转交，不能在收件箱直接退出')}})\n",
      "Page({show(){app.tips('标记已读失败,请重试')}})\n",
    ].every((src) => { fs.writeFileSync(js, src); return enforcedLegacyViolations(scanLegacy([js], []), true).length >= 1; });
    // 注释里提到这些词、代码里 includes('参数错误') 拦它们,都不算违规
    fs.writeFileSync(js, "// 后端偶尔回 '参数错误'\nPage({show(){wx.showToast({title:'操作完成'}); toast('网络异常，请重试'); if (msg.includes('参数错误')) return;}})\n");
    const placeholderGood = enforcedLegacyViolations(scanLegacy([js], []), true).length === 0;
    assertSelftest('WARN-AS-ERROR', placeholderBad, placeholderGood,
      '--warn-as-error 下占位/开发者口径/裸网络/半角逗号/超长提示文案会进入失败判定');

    const staleBaseline = baselineContract('U3', new Map([['old#handler', '真实旧理由足够长']]),
      [], 1).some((item) => item.message.includes('未命中'));
    const growthRed = baselineContract('U3', new Map([['new#handler', '新增债务应被冻结上限拦截']]),
      ['new#handler'], 1, 0).some((item) => item.message.includes('冻结上限'));
    const oldDigest = baselineKeyDigest(new Map([['old#handler', '旧键']]));
    const swapRed = baselineContract('U3', new Map([['new#handler', '等量替换也应显式审查']]),
      ['new#handler'], 1, 1, oldDigest).some((item) => item.message.includes('digest'));
    const missingDigestRed = baselineContract('U3', new Map(), [], 0, 0, undefined)
      .some((item) => item.message.includes('keyDigest'));
    const emptyDigest = baselineKeyDigest(new Map());
    const ratchetGreen = baselineContract('U3', new Map(), [], 0, 0, emptyDigest).length === 0;
    assertSelftest('BASELINE-RATCHET', staleBaseline && growthRed && swapRed && missingDigestRed, ratchetGreen,
      '未命中、增长、等量换 key 或缺 digest 真红；显式删除并调小棘轮后恢复绿');
    console.log('UI-GATE-0 selftest:PASS (U1-U5 五条均完成变异红→撤销→绿)');
  } finally {
    fs.rmSync(fixtureRoot, { recursive: true, force: true });
  }
}

function runRepoScan() {
  const jsFiles = walkFiles(XCX_ROOT, '.js');
  const wxmlFiles = walkFiles(XCX_ROOT, '.wxml');
  const backendRoutes = collectBackendRoutes();
  const u1 = scanU1(jsFiles, backendRoutes);
  const u2 = lintWxmlHandlers();
  const u2Violations = u2.violations.map((item) => ({
    code: 'U2', key: `${repoRel(item.file)}:${item.line}`, message: `${item.attrName}="${item.handler}" 对应 JS 方法不存在`,
  }));
  const u3 = scanU3(jsFiles);
  const u4 = scanU4(jsFiles);
  const u5 = scanU5(jsFiles);
  const legacy = scanLegacy(jsFiles, wxmlFiles);
  const a11y = scanSharedA11y();
  const ratchet = BASELINE.ratchet || {};
  const baselineViolations = [
    ...baselineContract('U1-VARIABLE', U1_VARIABLE_ALLOWLIST,
      u1.variableReports.map((item) => item.key), ratchet.u1Variable, NON_INCREASING_CEILING.u1Variable,
      ratchet.keyDigest.u1Variable),
    ...baselineContract('U3-DEBT', U3_DEBT_BASELINE, u3.candidates.map((item) => item.key),
      ratchet.u3Handler, NON_INCREASING_CEILING.u3Debt, ratchet.keyDigest.u3Debt),
    ...baselineContract('U4-DEBT', U4_DEBT_BASELINE, u4.candidates.map((item) => item.key),
      ratchet.u4Field, NON_INCREASING_CEILING.u4Debt, ratchet.keyDigest.u4Debt),
    ...baselineContract('U4-DYNAMIC', U4_DYNAMIC_ALLOWLIST, u4.dynamicReports.map((item) => item.key),
      ratchet.u4Dynamic, NON_INCREASING_CEILING.u4Dynamic, ratchet.keyDigest.u4Dynamic),
    ...baselineContract('U5', U5_SILENT_ALLOWLIST, u5.callsites.map((item) => item.key),
      ratchet.u5Silent, NON_INCREASING_CEILING.u5Silent, ratchet.keyDigest.u5Silent),
  ];
  const violations = [
    ...u1.violations, ...u2Violations, ...u3.violations, ...u4.violations,
    ...u5.violations, ...enforcedLegacyViolations(legacy), ...baselineViolations, ...a11y,
  ];

  console.log(`UI-GATE-0 扫描:${jsFiles.length} JS / ${wxmlFiles.length} WXML / ${backendRoutes.size} backend Mapping`);
  console.log(`U1 ${u1.violations.length ? 'FAIL' : 'PASS'}:静态路径 ${u1.staticCount},动态路径 ${u1.dynamicReports.length},变量路径 ${u1.variableReports.length},逐项理由 ${u1.variableReports.filter((item) => item.allowed).length}`);
  u1.dynamicReports.forEach((item) => console.log(`  [U1 DYNAMIC] ${item.key} -> ${item.route}`));
  u1.variableReports.forEach((item) => console.log(`  [U1 VARIABLE${item.allowed ? ' ALLOW' : ''}] ${item.key} ${item.detail}${item.allowed ? ` — ${item.allowed}` : ''}`));
  console.log(`U2 ${u2Violations.length ? 'FAIL' : 'PASS'}:绑定 ${u2.checkedBindingCount},显式 skip ${u2.skipped.length},白名单 0(复用 wxml-handler-lint)`);
  console.log(`U3 ${u3.violations.length ? 'FAIL' : 'PASS'}:零绑定债务 ${u3.candidates.length}/${u3.candidates.filter((item) => item.allowed).length} 已登记,生命周期规则 ${PAGE_LIFECYCLES.size}`);
  console.log(`U4 ${u4.violations.length ? 'FAIL' : 'PASS'}:零消费债务 ${u4.candidates.length}/${u4.candidates.filter((item) => item.allowed).length} 已登记,动态 setData ${u4.dynamicReports.length}/${u4.dynamicReports.filter((item) => item.allowed).length} 已登记`);
  console.log(`U5 ${u5.violations.length ? 'FAIL' : 'PASS'}:需逐项理由 ${u5.callsites.length},同请求内静态证明显式处理 ${u5.autoSafe.length},逐项理由 ${u5.callsites.filter((item) => item.allowed).length}`);
  console.log(`A11Y ${a11y.length ? 'FAIL' : 'PASS'}:共享组件语义、触达与静态替代契约`);
  console.log(`Legacy ${legacy.errors.length ? 'FAIL' : 'PASS'}:error ${legacy.errors.length},warn ${legacy.warnings.length}`);
  legacy.warnings.forEach((item) => console.log(`  [WARN] ${item.key} ${item.message}`));

  if (violations.length) {
    console.error(`UI-GATE-0:FAIL (${violations.length} violations)`);
    violations.forEach((item) => console.error(`  [${item.code}] ${item.key} ${item.message}`));
    process.exitCode = 1;
  } else {
    console.log('UI-GATE-0:PASS');
  }
}

if (require.main === module) {
  if (process.argv.includes('--selftest')) runSelftest();
  else runRepoScan();
}

module.exports = {
  XCX_ROOT,
  collectBackendRoutes,
  collectApiLiterals,
  expressionToApiRoute,
  objectProperties,
  routeMatches,
  scanU1,
  scanU3,
  scanU4,
  scanU5,
  scanLegacy,
  walkFiles,
};
