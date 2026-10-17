#!/usr/bin/env node
'use strict';

/**
 * U1 · 接口存在性门禁:小程序发起的每一条 /api/… 请求,后端必须真有对应的 @*Mapping。
 *
 * 抓的是「按钮有、handler 有、接口不存在」这一类最隐蔽的缺陷 —— 前端若同时带
 * silentError:true(见姊妹门禁 ci/silent-error-gate.js),它连 toast 都不弹、console 也不响,
 * 页面只是静静地停在空态。人点是点不完的,只能扫。
 *
 * 用法:
 *   node ci/api-existence-gate.js              全仓扫描(门禁本体)
 *   node ci/api-existence-gate.js --selftest   负控自证:能判红也能判绿
 *   node ci/api-existence-gate.js --baseline   只打清单不判红(生成存量报告用)
 *
 * ── 前端侧:穷尽请求发起形态,不只匹配 `url:` 字面量 ──────────────────────────
 * 全仓 736 处 /api 字面量里,只有 393 处长成 `url: '/api/…'`。其余形态:
 *   ① 局部 req() 封装:      req('/api/roam/tiles', 'GET', {…})            —— 第一个位置参
 *   ② 字符串拼接:            '/api/official/v2/parties/' + partyId + '/' + act
 *   ③ 集中常量:              const URL_X = '/api/x'; … url: URL_X
 *   ④ 模板串:                `/api/x/${id}`                                 —— 现码 0 处,先兜住
 * 所以本门禁**不认 `url:` 这个键**,改为:词法扫出所有 JS 字符串字面量,凡以 `/api` 开头
 * 的均算调用点；唯一非端点例外是经真实 Controller 类 namespace 验证、且仅用作 url: BASE+suffix 的 const 声明 token。直接请求 namespace 仍审查。这样 ①③ 天然覆盖(常量定义处就是一个 /api 字面量);②④ 再由
 * walkConcat 把整条拼接表达式走完 —— 变量段记成哨兵、后缀字面量照收,拼出
 * `/api/city/nodes/{}/favorite` 这样的形状,和后端逐段比。走不完的才降级成前缀匹配 + 未判定。
 *
 * 词法扫描而不是正则:注释里写 `// 数据:/api/club/topics` 全仓有几十处(纯说明,不是调用),
 * 正则会把它们当调用点 ⇒ 误报;而字符串里的 `//`(如 'https://x')又会被朴素的去注释切坏。
 * 正则字面量 /\/api\// 同理。三者只能靠词法状态机分开。
 *
 * ── 后端侧 ────────────────────────────────────────────────────────────────
 * 扫 chengyinhub-{admin,common,framework,generator,quartz,system} 的 src/main/java,取类上的类级
 * @RequestMapping 前缀 + 方法级 @Get/@Post/@Put/@Delete/@Patch/@RequestMapping 路径,
 * 拼成完整 pattern;路径变量 {id} 编译成 [^/]+ 再比。
 *
 * ── 判定与分类 ────────────────────────────────────────────────────────────
 *   A 前端有后端无(已判定的路径没有任何 mapping 命中)  → 判红
 *   B 后端有前端无(mapping 全仓无人调用)              → 只报数不判红(后台/其他端也会调)
 *   C 未判定                                          → 逐条列出并报数,连前缀都对不上的照样判红
 *
 * C 类只剩两种成因,都在报告里逐条注明:
 *   ① 拼接表达式走不完(顶层三元/逻辑运算)—— 只剩静态前缀可比
 *   ② 拼出来的形状要靠「调用侧变量段去顶后端的字面量段」才对得上 —— 那是【假定】
 *      该变量恰好求值成那个字面量,静态判不出来。★ 这一条绝不算判绿:
 *      升级判定如果靠放宽,那还不如不升级(见 segmentMatch 的 strict 参数)。
 *
 * 逃生口:ci/api-existence-allowlist.txt,一行一条 `<路径> # 为什么可以缺`。
 * 首次接入时它是空的 —— 存量必然一片红,这是预期:本轮只建门禁 + 报清单,不修存量。
 *
 * ⚠️ 连字符陷阱:判「后端有没有」绝不能用 \b 或裸 startsWith 收尾。
 *    `/api/club/topic` 不得被 `/api/club/topic-registrations` 接住(\btopic\b 会命中
 *    topic-registrations 里的 topic)。本文件一律整段比对 + 显式段边界,负控见 --selftest。
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// 门禁一律先回仓库根:下面全部路径都是仓库根相对路径,从子目录跑必须给出同一个结论。
// (见 ci/test_cwd_independence.sh —— 那条守的就是这行不被删掉。)
function repoRoot() {
  return execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
}

const XCX_DIR = 'chengyinhub-xcx';
const JAVA_GLOB_ROOTS = [
  'chengyinhub-admin/src/main/java',
  'chengyinhub-common/src/main/java',
  'chengyinhub-framework/src/main/java',
  'chengyinhub-generator/src/main/java',
  'chengyinhub-quartz/src/main/java',
  'chengyinhub-system/src/main/java',
];
const ALLOWLIST_FILE = 'ci/api-existence-allowlist.txt';

// 前端扫描排除:三方产物 + 非上线代码。tests/ 是断言、scripts/ 是本机验证脚本,
// 都不是真实调用点;docs/ 是文档。排除项显式打印,不静默。
const XCX_SKIP_DIRS = new Set(['node_modules', 'miniprogram_npm', 'tests', 'scripts', 'docs', 'images', '.git']);

// 动态段占位:模板串 ${…} 与拼接尾巴统一记成这个 sentinel,后续按前缀降级处理。
const DYN = 'DYN';

// ---------------------------------------------------------------------------
// JS 词法:扫出全部字符串字面量(跳过注释与正则字面量)
// ---------------------------------------------------------------------------
// `/` 前一个「有意义字符」决定它是除号还是正则开头 —— JS 词法本身就要靠上下文判断。
// 这份前导字符/关键字表沿用 chengyinhub-xcx/scripts/wxml-handler-lint.js 里同一套判据
// (那边已被仓库里真实出现过的 /'/g 这类正则验证过)。
const REGEX_PRECEDING_CHARS = new Set(
  ['(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '~', '^', '<', '>', undefined]
);
const REGEX_PRECEDING_KEYWORDS = [
  'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void', 'case', 'do', 'else', 'yield', 'await',
];

/**
 * 一次走完,同时产出两样东西:
 *   literals —— 全部字符串字面量(U1 自己要用)
 *   masked   —— 把【注释 / 字符串 / 正则字面量】全抹成等长空格的源码,行号列号不变
 *
 * masked 是给姊妹门禁 ci/silent-error-gate.js(U5)用的。为什么要共用这一份状态机:
 * U5 原本自己写了个只认注释和引号、**不认正则字面量**的简化版,结果 `/'/g` 这种正则里的
 * 引号被当成字符串起点,把后面整段代码抹成空格 ⇒ 后面的 silentError 调用点【一个都扫不到】。
 * 2026-08-18 实测:连 `/['"]/g` 这种引号成对的正则也会触发(本仓真出现过这个形状)。
 * 那是【假绿】,不是误报 —— 而 U5 那条注释当时恰恰声称「不会造成假绿」。
 * 两份词法 = 修好一份另一份继续坏。所以合并成一份,由两边的 selftest 一起钉。
 */
function scanJs(src) {
  const out = [];
  const masked = src.split('');
  // 抹掉但保留换行:行号不能被抹动,否则报出来的行号会偏
  const blank = (from, to) => {
    for (let k = from; k < to && k < src.length; k += 1) {
      if (masked[k] !== '\n') masked[k] = ' ';
    }
  };
  let i = 0;
  let line = 1;
  let lastSignificant;
  let lastWord = '';

  while (i < src.length) {
    const c = src[i];
    if (c === '\n') { line += 1; i += 1; continue; }
    if (c === '/' && src[i + 1] === '/') {
      const start = i;
      while (i < src.length && src[i] !== '\n') i += 1;
      blank(start, i);
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      const start = i;
      i += 2;
      while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] === '\n') line += 1;
        i += 1;
      }
      i += 2;
      blank(start, i);
      continue;
    }
    if (c === '/') {
      const isRegex = REGEX_PRECEDING_CHARS.has(lastSignificant) || REGEX_PRECEDING_KEYWORDS.includes(lastWord);
      if (isRegex) {
        const start = i;
        i += 1;
        let inClass = false;
        while (i < src.length) {
          const d = src[i];
          if (d === '\\') { i += 2; continue; }
          if (d === '[') inClass = true;
          else if (d === ']') inClass = false;
          else if (d === '/' && !inClass) break;
          else if (d === '\n') break;
          i += 1;
        }
        i += 1;
        blank(start, i);      // ★ 正则整段抹掉:里面的引号不是字符串起点
      } else {
        i += 1;
      }
      lastSignificant = '/';
      lastWord = '';
      continue;
    }
    if (c === "'" || c === '"' || c === '`') {
      const quote = c;
      const startLine = line;
      const startIndex = i;
      let buf = '';
      i += 1;
      while (i < src.length) {
        const d = src[i];
        if (d === '\\') { buf += (src[i + 1] || ''); i += 2; continue; }
        if (d === quote) break;
        if (d === '\n') line += 1;
        if (quote === '`' && d === '$' && src[i + 1] === '{') {
          // ${…} 内容不是路径的一部分,记成动态段(允许内部再嵌大括号)
          buf += DYN;
          i += 2;
          let depth = 1;
          while (i < src.length && depth > 0) {
            if (src[i] === '{') depth += 1;
            else if (src[i] === '}') depth -= 1;
            else if (src[i] === '\n') line += 1;
            if (depth > 0) i += 1;
          }
          i += 1;
          continue;
        }
        buf += d;
        i += 1;
      }
      i += 1;
      out.push({ value: buf, line: startLine, startIndex, endIndex: i });
      blank(startIndex, i);
      lastSignificant = quote;
      lastWord = '';
      continue;
    }
    if (/\s/.test(c)) { i += 1; continue; }
    if (/[A-Za-z_$]/.test(c)) {
      let j = i;
      while (j < src.length && /[A-Za-z0-9_$]/.test(src[j])) j += 1;
      lastWord = src.slice(i, j);
      lastSignificant = src[j - 1];
      i = j;
      continue;
    }
    lastSignificant = c;
    lastWord = '';
    i += 1;
  }
  return { literals: out, masked: masked.join('') };
}

// U1 只要字面量;保持原签名不变,免得动它那 43 条 selftest。
function scanJsStringLiterals(src) {
  return scanJs(src).literals;
}

// U5 只要掩码。两边共用上面那一份状态机 —— 见 scanJs 的注释。
function maskJsNonCode(src) {
  return scanJs(src).masked;
}

// ---------------------------------------------------------------------------
// 前端:收集调用点
// ---------------------------------------------------------------------------
function isApiLiteral(v) {
  return v === '/api' || v.startsWith('/api/');
}

// Spring @*Mapping 只描述 `?` 前的路由。查询参数可以是静态值或运行时值，
// 但都不能改变接口是否存在；把它们留在 resolved 里会把合法 @RequestParam
// 调用误报成 A 类，或仅因 query 动态就塞进 C 类。
function routePathOnly(value) {
  const queryAt = value.indexOf('?');
  return queryAt === -1 ? value : value.slice(0, queryAt);
}

/**
 * 从一个 /api 字面量的右引号之后往下走,把整条【字符串拼接表达式】的剩余部分解出来。
 *
 * 为什么要走这一步:`'/api/city/nodes/' + poiId + '/favorite'` 里变量只占中间一段,
 * 后缀 `/favorite` 本身是字面量。只取第一段前缀 ⇒ 这条调用永远停在「未判定」,
 * 而它其实完全可判 —— 拼出来是 /api/city/nodes/<段>/favorite,拿去和后端
 * /api/city/nodes/{id}/favorite 逐段比即可。全仓 12 处未判定里 11 处属于这一类。
 *
 * 返回 { tail, complete }:
 *   tail      —— 后续拼上去的内容,变量段记成 DYN 哨兵
 *   complete  —— 是否走到了表达式自然结束(遇到 , ; ) ] } 之类的终止符)。
 * ★ 走不明白就 complete=false,调用方降级回「按前缀比 + 计入未判定」。
 *   绝不把「没看懂」当成「拼出来了」—— 那等于拿蒙的结果去判红,比不判还坏。
 */
function walkConcat(src, startIndex) {
  let i = startIndex;
  let tail = '';

  const skipTrivia = () => {
    for (;;) {
      while (i < src.length && /\s/.test(src[i])) i += 1;
      if (src[i] === '/' && src[i + 1] === '/') { while (i < src.length && src[i] !== '\n') i += 1; continue; }
      if (src[i] === '/' && src[i + 1] === '*') {
        i += 2;
        while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) i += 1;
        i += 2;
        continue;
      }
      return;
    }
  };

  const readString = () => {
    const quote = src[i];
    let buf = '';
    i += 1;
    while (i < src.length) {
      const d = src[i];
      if (d === '\\') { buf += (src[i + 1] || ''); i += 2; continue; }
      if (d === quote) { i += 1; break; }
      if (quote === '`' && d === '$' && src[i + 1] === '{') {
        buf += DYN;
        i += 2;
        let depth = 1;
        while (i < src.length && depth > 0) {
          if (src[i] === '{') depth += 1;
          else if (src[i] === '}') depth -= 1;
          if (depth > 0) i += 1;
        }
        i += 1;
        continue;
      }
      buf += d;
      i += 1;
    }
    return buf;
  };

  for (let guard = 0; guard < 64; guard += 1) {
    skipTrivia();
    if (src[i] !== '+') return { tail, complete: true };                              // 表达式到此为止
    if (src[i + 1] === '+' || src[i + 1] === '=') return { tail, complete: false };   // ++ / += 不是字符串拼接
    i += 1;
    skipTrivia();
    const c = src[i];
    if (c === "'" || c === '"' || c === '`') { tail += readString(); continue; }
    // 变量 / 属性链 / 调用 / 括号表达式:整段吞掉,记成一个动态段
    let depth = 0;
    let bailed = false;
    while (i < src.length) {
      const d = src[i];
      if (d === "'" || d === '"' || d === '`') { readString(); continue; }
      if (d === '(' || d === '[' || d === '{') { depth += 1; i += 1; continue; }
      if (d === ')' || d === ']' || d === '}') {
        if (depth === 0) break;              // 收到外层闭括号 = 表达式结束
        depth -= 1; i += 1; continue;
      }
      if (depth === 0) {
        if (d === '+') break;                // 下一个拼接项
        if (d === ',' || d === ';') break;
        // 顶层三元 / 逻辑运算:看不懂就承认看不懂,别猜
        if (d === '?' || d === ':' || d === '&' || d === '|') { bailed = true; break; }
      }
      i += 1;
    }
    tail += DYN;
    if (bailed) return { tail, complete: false };
  }
  return { tail, complete: false };
}

// A class namespace is not an endpoint only when its const is exclusively used
// as a concatenation base. Direct requests and unknown namespaces stay checked.
function namespaceDeclarationOffsets(source, namespacePrefixes) {
  const ignored = new Set();
  const masked = maskJsNonCode(source);
  for (const literal of scanJsStringLiterals(source)) {
    if (!namespacePrefixes.has(literal.value)) continue;
    const declaration = /\bconst\s+([A-Za-z_$][\w$]*)\s*=\s*$/.exec(masked.slice(0, literal.startIndex));
    if (!declaration) continue;
    const name = declaration[1];
    const nameAt = declaration.index + declaration[0].indexOf(name);
    const uses = new RegExp('\\b' + name.replace(/[$]/g, '\\$&') + '\\b', 'g');
    let match, count = 0, safe = true;
    while ((match = uses.exec(masked))) {
      if (match.index === nameAt) continue;
      count += 1;
      if (!/\burl\s*:\s*$/.test(masked.slice(0, match.index)) || !/^\s*\+(?![+=])/.test(masked.slice(match.index + name.length))) { safe = false; break; }
    }
    if (safe && count > 0) ignored.add(literal.startIndex);
  }
  return ignored;
}

function collectFrontendCalls(root, namespacePrefixes = collectBackendMappings(root).namespacePrefixes) {
  const calls = [];
  const files = [];
  walkFiles(path.join(root, XCX_DIR), (p, name, dirName) => {
    if (dirName && XCX_SKIP_DIRS.has(dirName)) return false;
    if (name.endsWith('.js')) files.push(p);
    return true;
  });

  for (const file of files) {
    const rel = path.relative(root, file);
    const src = fs.readFileSync(file, 'utf8');
    const namespaceOffsets = namespaceDeclarationOffsets(src, namespacePrefixes);
    for (const lit of scanJsStringLiterals(src)) {
      if (namespaceOffsets.has(lit.startIndex)) continue;
      if (!isApiLiteral(lit.value)) continue;
      // 字面量后面紧跟 + ⇒ 后面还要拼东西,把整条拼接表达式走完(见 walkConcat)
      const probe = src.slice(lit.endIndex, lit.endIndex + 8).replace(/^[\s\\]+/, '');
      const concatenated = probe.startsWith('+') && probe[1] !== '+' && probe[1] !== '=';
      const walked = concatenated ? walkConcat(src, lit.endIndex) : { tail: '', complete: true };
      const resolved = routePathOnly(lit.value + walked.tail);
      calls.push({
        file: rel,
        line: lit.line,
        raw: lit.value,
        resolved,                       // 拼完的整条路径,变量段是 DYN 哨兵
        dynamic: resolved.includes(DYN),
        complete: walked.complete,      // 拼接表达式有没有走到自然结束
        // 静态前缀 = 第一个动态段之前的部分(complete=false 时只剩这一条可比)
        staticPrefix: resolved.split(DYN)[0],
      });
    }
  }
  return { calls, fileCount: files.length };
}

function walkFiles(dir, visit) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (visit(p, e.name, e.name) === false) continue;
      walkFiles(p, visit);
    } else if (e.isFile()) {
      visit(p, e.name, null);
    }
  }
}

// ---------------------------------------------------------------------------
// 后端:收集 @*Mapping
// ---------------------------------------------------------------------------
// 只抹注释,保留字符串内容(注解里的路径就在字符串里)。行号靠等长空格保持。
function maskJavaComments(src) {
  const out = src.split('');
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') { out[i] = ' '; i += 1; }
      continue;
    }
    if (c === '/' && src[i + 1] === '*') {
      out[i] = ' '; out[i + 1] = ' ';
      i += 2;
      while (i < src.length && !(src[i] === '*' && src[i + 1] === '/')) {
        if (src[i] !== '\n') out[i] = ' ';
        i += 1;
      }
      if (i < src.length) { out[i] = ' '; out[i + 1] = ' '; i += 2; }
      continue;
    }
    if (c === '"' || c === "'") {
      const quote = c;
      i += 1;
      while (i < src.length) {
        if (src[i] === '\\') { i += 2; continue; }
        if (src[i] === quote) { i += 1; break; }
        i += 1;
      }
      continue;
    }
    i += 1;
  }
  return out.join('');
}

const MAPPING_ANNOTATION = /@(Get|Post|Put|Delete|Patch|Request)Mapping\b/g;

function matchParens(src, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < src.length; i += 1) {
    if (src[i] === '(') depth += 1;
    else if (src[i] === ')') {
      depth -= 1;
      if (depth === 0) return i;
    }
  }
  return -1;
}

// 从注解实参里取路径:有 value=/path= 就只取该赋值的右值,否则取开头的裸串/裸数组。
// 这样 produces = "application/json" 不会被当成路径捡走。
function pathsFromAnnotationArgs(args) {
  const trimmed = args.trim();
  if (!trimmed) return [];
  const named = /(?:^|,)\s*(?:value|path)\s*=\s*/.exec(trimmed);
  let region;
  if (named) {
    region = trimmed.slice(named.index + named[0].length);
    // 截到该赋值结束(下一个 顶层 `xxx =`)
    const nextAssign = /,\s*[A-Za-z_][A-Za-z0-9_]*\s*=/.exec(region);
    if (nextAssign) region = region.slice(0, nextAssign.index);
  } else {
    if (!(trimmed.startsWith('"') || trimmed.startsWith('{'))) return [];
    region = trimmed;
    const nextAssign = /,\s*[A-Za-z_][A-Za-z0-9_]*\s*=/.exec(region);
    if (nextAssign) region = region.slice(0, nextAssign.index);
  }
  const out = [];
  const re = /"((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = re.exec(region)) !== null) out.push(m[1]);
  return out;
}

function joinPath(prefix, suffix) {
  const a = prefix || '';
  const b = suffix || '';
  if (!a) return normalizePath(b);
  if (!b) return normalizePath(a);
  return normalizePath(`${a}/${b}`);
}

function normalizePath(p) {
  let out = p.replace(/\/{2,}/g, '/');
  if (!out.startsWith('/')) out = `/${out}`;
  if (out.length > 1 && out.endsWith('/')) out = out.slice(0, -1);
  return out;
}

function collectBackendMappings(root) {
  const namespacePrefixes = new Set();
  const mappings = [];
  const files = [];
  for (const jroot of JAVA_GLOB_ROOTS) {
    walkFiles(path.join(root, jroot), (p, name) => {
      if (name.endsWith('.java')) files.push(p);
      return true;
    });
  }

  let controllerFileCount = 0;
  for (const file of files) {
    const rawSrc = fs.readFileSync(file, 'utf8');
    if (!/@RestController\b|@Controller\b/.test(rawSrc)) continue;
    controllerFileCount += 1;
    const rel = path.relative(root, file);
    const src = maskJavaComments(rawSrc);

    // 类声明位置:它之前的 @RequestMapping 是类级前缀,之后的都是方法级。
    const classDecl = /\b(class|interface|enum)\s+[A-Za-z_][A-Za-z0-9_]*/.exec(src);
    const classAt = classDecl ? classDecl.index : 0;

    const hits = [];
    MAPPING_ANNOTATION.lastIndex = 0;
    let m;
    while ((m = MAPPING_ANNOTATION.exec(src)) !== null) {
      const verb = m[1];
      const at = m.index;
      // 注解后面可能没有括号(@GetMapping 裸写)
      const rest = src.slice(m.index + m[0].length);
      const lead = /^\s*/.exec(rest)[0];
      let paths = [];
      if (rest[lead.length] === '(') {
        const openIndex = m.index + m[0].length + lead.length;
        const closeIndex = matchParens(src, openIndex);
        if (closeIndex > 0) paths = pathsFromAnnotationArgs(src.slice(openIndex + 1, closeIndex));
      }
      hits.push({ verb, at, paths, line: src.slice(0, at).split('\n').length });
    }

    const classPrefixes = [];
    for (const h of hits) {
      if (h.at < classAt && h.verb === 'Request') classPrefixes.push(...(h.paths.length ? h.paths : ['']));
    }
    classPrefixes.filter(prefix => prefix.startsWith('/api/')).forEach(prefix => namespacePrefixes.add(prefix));
    const prefixes = classPrefixes.length ? classPrefixes : [''];

    for (const h of hits) {
      if (h.at < classAt) continue;
      const suffixes = h.paths.length ? h.paths : [''];
      for (const pre of prefixes) {
        for (const suf of suffixes) {
          mappings.push({ pattern: joinPath(pre, suf), verb: h.verb, file: rel, line: h.line });
        }
      }
    }
  }
  return { mappings, namespacePrefixes, controllerFileCount, javaFileCount: files.length };
}

// ---------------------------------------------------------------------------
// 匹配
// ---------------------------------------------------------------------------
// {id} / {id:regex} → 一段(不跨 /);* → 一段;** → 任意。整串 ^…$ 锚定。
//
// ⚠️ 2026-08-18 订正:原注释写「负控在 --selftest 里锁死」—— 【当时是假的】。
//    实测把 `$` 删掉,--selftest 照样全绿。原因是 analyse 早已改走 segmentMatch,
//    只有 pattern 含 `*` 才会落到本函数,而实仓 972 条 mapping 里带 `*` 的 = 0 条 ⇒
//    这条路径根本没被任何用例走到。**一条声称「已锁死」却锁不住的注释,比没有注释坏。**
//    现在补了直接打本函数的负控(见 --selftest 的「patternToRegex 锚定」一组),
//    删 `^` 或删 `$` 都会红。
//
// 本函数仍然是活代码,但只对含 `*` 的 mapping 生效(见 segmentMatch)。
// 真正给绝大多数 mapping 做「整串锚定」的是 segmentMatch 的【段数必须相等 + 逐段整段比】,
// 连字符假绿(topic vs topic-registrations)死在那一层,不是死在这里 —— 别记错地方。
function patternToRegex(pattern) {
  let re = '';
  for (let i = 0; i < pattern.length; i += 1) {
    const c = pattern[i];
    if (c === '{') {
      let depth = 1;
      i += 1;
      while (i < pattern.length && depth > 0) {
        if (pattern[i] === '{') depth += 1;
        else if (pattern[i] === '}') depth -= 1;
        i += 1;
      }
      i -= 1;
      re += '[^/]+';
      continue;
    }
    if (c === '*') {
      if (pattern[i + 1] === '*') { re += '.*'; i += 1; continue; }
      re += '[^/]*';
      continue;
    }
    re += c.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp(`^${re}$`);
}

// pattern 的静态前缀:第一个 { 或 * 之前的部分(动态调用只能比到这里)
function staticPrefixOf(pattern) {
  const cut = pattern.search(/[{*]/);
  return cut === -1 ? pattern : pattern.slice(0, cut);
}

function segmentsOf(p) {
  return p.split('/').filter(Boolean);
}

/**
 * 两个静态前缀是否「同一条路上」—— 一方的路径段序列是另一方的前缀。
 *
 * ⚠️ 必须按【段】比,不能按字符串 startsWith:
 *   '/api/club/topic-registrations/'.startsWith('/api/club/topic') === true
 *   —— 这就是 \bdetail\b 命中 detail-cta-bar 的同一族陷阱,连字符把两条不同的路
 *   粘成了一条,动态调用会被隔壁接口静默接住。
 * 另外要求至少共享 2 段(即 /api/xxx 级别):SysIndexController 的类级 @RequestMapping("/")
 * 静态前缀就是 '/',按字符串比它是所有路径的前缀 ⇒ 任何动态调用都能找到候选 = 恒真放行。
 */
const MIN_SHARED_SEGMENTS = 2;
function prefixCompatible(a, b) {
  const sa = segmentsOf(a);
  const sb = segmentsOf(b);
  const n = Math.min(sa.length, sb.length);
  if (n < MIN_SHARED_SEGMENTS) return false;
  for (let i = 0; i < n; i += 1) if (sa[i] !== sb[i]) return false;
  return true;
}

// 展示用:把 DYN 哨兵还原成人看得懂的 {}
function displayPath(p) {
  return p.split(DYN).join('{}');
}

/**
 * 逐段比对一条【已拼完的调用路径】和一条【后端 mapping】。段数必须相同,再逐段判:
 *
 *   调用侧字面量 vs mapping 字面量   → 必须整段相等(连字符陷阱就死在这:
 *                                      topic ≠ topic-registrations,不是 startsWith)
 *   调用侧字面量 vs mapping 路径变量 → 命中。具体值本来就会被路由进 {id}
 *   调用侧变量段 vs mapping 路径变量 → 命中。两边都是「任意一段」
 *   调用侧变量段 vs mapping 字面量   → ★ 只有 strict=false 才算命中
 *
 * 最后那一条是「真判出来」和「蒙一个」的分界线,也是本次升级的全部价值所在:
 *   `'/api/…/organizer-invites/' + partyId + '/' + (x ? 'accept' : 'decline')`
 *   拼出来是 …/{}/{},它能对上后端 …/{partyId}/accept —— 但那是【假定】那个变量
 *   恰好求值成 'accept'。静态判不出来,所以它只能进「未判定」,不能算判绿。
 *   把这条也当命中 = 升级判定靠放宽,那还不如不升级。
 */
function segmentMatch(callPath, pattern, strict) {
  // 通配 mapping(** / *)段数不定,退回正则;调用侧带变量段时无法安全判断,只算 loose。
  if (/[*]/.test(pattern)) {
    if (callPath.includes(DYN)) return !strict;
    return patternToRegex(pattern).test(callPath);
  }
  const cs = segmentsOf(callPath);
  const ms = segmentsOf(pattern);
  if (cs.length !== ms.length) return false;
  let guessedIntoMapLiteral = false;   // 调用侧变量段 顶了 mapping 的字面量段
  let literalIntoMapHole = false;      // 调用侧字面量  被 mapping 的路径变量吸收
  for (let i = 0; i < cs.length; i += 1) {
    const callHole = cs[i].includes(DYN);
    const mapHole = /[{]/.test(ms[i]);
    if (!callHole && !mapHole) { if (cs[i] !== ms[i]) return false; continue; }
    if (callHole && !mapHole) { if (strict) return false; guessedIntoMapLiteral = true; continue; }
    if (!callHole && mapHole) { literalIntoMapHole = true; continue; }
    // 变量段 ↔ 路径变量:两边都是「任意一段」,没有不确定性
  }
  // ★ 放宽最多只允许【一个方向】的不确定性。两个方向同时出现 = 这条 mapping 其实是隔壁那条路。
  //   实测踩过:调用 /api/city/nodes/<变量>/favorite-ghost 会被 /api/city/nodes/merchant/{merchantId}
  //   接住 —— 靠「假定该变量恰好等于 merchant」+「把 favorite-ghost 塞进 {merchantId}」两次猜测凑成,
  //   于是一条指向不存在接口的调用被判成「未判定」而不是红。两个方向都猜 = 没有任何证据,不算候选。
  if (guessedIntoMapLiteral && literalIntoMapHole) return false;
  return true;
}

// 占位符不是理由 —— 与姊妹门禁 U5 同一份判据(ci/silent-error-gate.js)。
const PLACEHOLDER_REASON_RE = /^(TODO|FIXME|XXX|TBD|待填|待补|待定|\?)/i;
const UNDECIDED_MAX_RE = /^#\s*UNDECIDED_MAX\s+(\d+)\s*$/;

/**
 * 逃生口册子。2026-08-18(G4)从「一行一个路径、什么都不校验」升级成 U5 那一套:
 * 理由必填 + 占位符判红 + 条数只许减不许增。
 *
 * 为什么同一批次里 U5 有而 U1 没有是双标:这个逃生口的语义是
 * 「我知道这条 /api/… 后端不存在,放行」—— **每多一行就多一个「按钮点下去什么都不会发生」的页面**。
 * 它比 U5 那种「错误不弹 toast」更硬,却反而一行字都不用写。
 *
 * ★ 明说这条设计的取舍:条数只许减不许增 ⇒ 这个逃生口**实际上是关着的**。
 *   现在 0 条、A 类存量也是 0,所以关着不挡任何人。真出现非加不可的情况时会撞红,
 *   那时该做的判断本来就该有人参与 —— 一个「让按钮变成死按钮」的豁免,值得撞一次红。
 */
function parseApiAllowlist(text) {
  const entries = new Map();
  const problems = [];
  let undecidedMax = null;
  text.split('\n').forEach((rawLine, idx) => {
    const lineNo = idx + 1;
    const line = rawLine.trim();
    if (!line) return;
    if (line.startsWith('#')) {
      const m = UNDECIDED_MAX_RE.exec(line);
      if (m) undecidedMax = Number(m[1]);
      return;
    }
    const hash = line.indexOf('#');
    const p = (hash === -1 ? line : line.slice(0, hash)).trim();
    const reason = hash === -1 ? '' : line.slice(hash + 1).trim();
    if (!p) return;
    if (!reason) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${p} 没写理由 —— 这一行让一个按钮变成死按钮,`
        + '至少要说清为什么可以这样');
      return;
    }
    if (PLACEHOLDER_REASON_RE.test(reason)) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${p} 的理由是占位符「${reason.slice(0, 20)}」`
        + ' —— 占位符不是理由,它只是把空白换了个写法');
      return;
    }
    if (entries.has(p)) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${p} 重复登记`);
      return;
    }
    entries.set(p, { reason, lineNo });
  });
  return { entries, problems, undecidedMax };
}

/** 逃生口条数【只许减不许增】—— 否则它就是个可以无限写的静默豁免(与 U5 的 BASELINE_TOTAL 同理)。 */
function checkAllowlistRatchet(currentCount, masterCount) {
  if (masterCount === null) return null;
  if (currentCount <= masterCount) return null;
  return `${ALLOWLIST_FILE} 的条数从 ${masterCount} 涨到了 ${currentCount} —— 逃生口【只许减不许增】。`
    + '每一条都是一个「按钮点下去什么都不会发生」的页面;要放行新的一条,'
    + '先把这条红拿给人看,别让它自己溜过去。';
}

/** C 类「未判定」条数【只许减不许增】—— 否则「未判定」就是个无限逃生口:闸永不判红也无上限。 */
function checkUndecidedRatchet(currentCount, cap) {
  if (cap === null) {
    return `${ALLOWLIST_FILE} 缺头部的 # UNDECIDED_MAX <n> —— C 类「未判定」永不判红,`
      + '没有上限它就是个无限逃生口:新写一条 \'/api/x/\' + a + \'/\' + (c?\'m\':\'n\') 形态,'
      + '永久落 C 而闸恒绿。';
  }
  if (currentCount <= cap) return null;
  return `C 类「未判定」${currentCount} 处 > 上限 ${cap} —— 新增的动态拼接调用没人判过。`
    + '要么把它写成静态可判的形状,要么调低上限时说清为什么这一条判不了。'
    + `(这个上限本身也只许减不许增。)`;
}

function masterFileText(root, relPath) {
  const { execFileSync } = require('child_process');
  for (const ref of ['github/master', 'origin/master']) {
    try {
      return execFileSync('git', ['-C', root, 'show', `${ref}:${relPath}`],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    } catch (e) { /* 该 ref 上没有这份文件 */ }
  }
  return null;
}

function readAllowlist(root) {
  const file = path.join(root, ALLOWLIST_FILE);
  if (!fs.existsSync(file)) return { entries: new Set(), problems: [], undecidedMax: null, size: 0 };
  const parsed = parseApiAllowlist(fs.readFileSync(file, 'utf8'));
  return {
    entries: new Set(parsed.entries.keys()),
    problems: parsed.problems,
    undecidedMax: parsed.undecidedMax,
    size: parsed.entries.size,
  };
}

function analyse(rawCalls, mappings, allowlist) {
  // ⚠️ 这里曾经还算一个 `re: patternToRegex(...)` 字段 —— 全程无人读的死代码(2026-08-18 删)。
  //    留着它会让人以为匹配走的是正则,从而把负控写到错的地方(G1 就是这么错的)。
  const compiled = mappings.map((m) => ({ ...m, prefix: staticPrefixOf(m.pattern) }));
  const used = new Set();

  // 兼容手工构造的调用对象(selftest / 老调用方):没给 resolved/complete 时按旧语义补。
  const calls = rawCalls.map((c) => ({
    ...c,
    resolved: c.resolved !== undefined ? c.resolved : c.raw,
    complete: c.complete !== undefined ? c.complete : !c.dynamic,
  }));

  const missingStatic = new Map();   // A:前端有后端无(已判定的路径)
  const undecided = [];              // C:未判定
  const missingDynamic = new Map();  // A':拼接没走完、连静态前缀都对不上 —— 照样是缺

  for (const call of calls) {
    // 「已判定」= 不含变量段,或含变量段但整条拼接表达式走完了(路径形状是确定的)
    if (call.complete) {
      const key = displayPath(call.resolved);
      const strict = compiled.filter((m) => segmentMatch(call.resolved, m.pattern, true));
      if (strict.length) { strict.forEach((h) => used.add(h.pattern)); continue; }
      // 严格对不上,但放宽「调用侧变量段可以顶后端字面量段」就能凑上 ⇒ 这是【蒙】,只能算未判定
      const loose = compiled.filter((m) => segmentMatch(call.resolved, m.pattern, false));
      if (loose.length) {
        loose.forEach((h) => used.add(h.pattern));
        undecided.push({
          ...call,
          reason: '变量段要顶后端的字面量段才对得上 —— 那等于假定该变量恰好求值成那个字面量',
          candidates: loose.slice(0, 4).map((h) => h.pattern),
        });
        continue;
      }
      if (allowlist.has(key)) continue;
      if (!missingStatic.has(key)) missingStatic.set(key, []);
      missingStatic.get(key).push(call);
      continue;
    }
    // 拼接表达式没走完(顶层三元 / 逻辑运算等):只剩静态前缀可比,一律计入未判定。
    // 双向按【路径段】比,见 prefixCompatible —— 按字符串 startsWith 比会被连字符坑。
    const p = call.staticPrefix !== undefined ? call.staticPrefix : call.resolved.split(DYN)[0];
    const hit = compiled.filter((m) => prefixCompatible(m.prefix, p));
    if (hit.length) {
      hit.forEach((h) => used.add(h.pattern));
      undecided.push({
        ...call,
        reason: '拼接表达式没走完(顶层三元/逻辑运算),只能比到静态前缀',
        candidates: hit.slice(0, 4).map((h) => h.pattern),
      });
      continue;
    }
    if (allowlist.has(p)) { undecided.push({ ...call, reason: 'allowlist', candidates: ['(allowlist)'] }); continue; }
    if (!missingDynamic.has(p)) missingDynamic.set(p, []);
    missingDynamic.get(p).push(call);
  }

  const unusedBackend = compiled
    .filter((m) => m.pattern.startsWith('/api') && !used.has(m.pattern))
    .sort((a, b) => (a.pattern < b.pattern ? -1 : 1));

  return { missingStatic, missingDynamic, undecided, unusedBackend };
}

// ---------------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------------
function report(result, stats, { baselineOnly }) {
  const { missingStatic, missingDynamic, undecided, unusedBackend } = result;
  const lines = [];
  const say = (s) => lines.push(s);

  say(`扫描前端 ${stats.fileCount} 个 js(已排除 ${[...XCX_SKIP_DIRS].join('/')}),`
    + `抓到 ${stats.callCount} 处 /api 调用点(静态 ${stats.staticCount} / 动态 ${stats.dynamicCount});`
    + `后端 ${stats.controllerFileCount} 个 controller,共 ${stats.mappingCount} 条 mapping。`);

  say('');
  say(`【A 前端有后端无】静态路径 ${missingStatic.size} 条 + 动态前缀 ${missingDynamic.size} 条`);
  for (const [p, cs] of [...missingStatic.entries()].sort()) {
    say(`  ✗ ${p}`);
    cs.forEach((c) => say(`      ${c.file}:${c.line}`));
  }
  for (const [p, cs] of [...missingDynamic.entries()].sort()) {
    say(`  ✗ ${p}… (拼接,连静态前缀都没有 mapping)`);
    cs.forEach((c) => say(`      ${c.file}:${c.line}`));
  }

  say('');
  say(`【B 后端有前端无】${unusedBackend.length} 条 mapping 全仓小程序无人调用(只报数、不判红:后台/其他端也会调)`);
  unusedBackend.forEach((m) => say(`  · ${m.pattern}  [${m.verb}]  ${m.file}:${m.line}`));

  say('');
  say(`【C 未判定】${undecided.length} 处(不静默跳过,逐条列出判不了的原因)`);
  undecided
    .sort((a, b) => (a.file + a.line < b.file + b.line ? -1 : 1))
    .forEach((c) => {
      say(`  ? ${c.file}:${c.line}  ${displayPath(c.resolved || c.raw)}`);
      say(`      判不了:${c.reason || '(未注明)'}`);
      say(`      候选:${c.candidates.join(' , ')}`);
    });

  say('');
  say(`小计:A=${stats.missingCallCount} 处(${missingStatic.size + missingDynamic.size} 条路径) `
    + `B=${unusedBackend.length} 条 C=${undecided.length} 处`);

  const text = lines.join('\n');
  console.log(text);
  if (baselineOnly) return 0;
  if (missingStatic.size || missingDynamic.size) {
    console.error('');
    console.error('[api-gate] ✗ 上述 /api 路径在后端找不到任何 @*Mapping —— 按钮点下去只会静默落错误态。');
    console.error(`[api-gate]   修法二选一:① 后端补上对应 Controller 方法;② 前端删掉这条死调用。`);
    console.error(`[api-gate]   确有正当理由暂缺的,写进 ${ALLOWLIST_FILE}(一行一条路径 + # 为什么可以缺)。`);
    return 1;
  }
  return 0;
}

// ---------------------------------------------------------------------------
// 负控自证
// ---------------------------------------------------------------------------
function runSelfTest() {
  const failures = [];
  const check = (ok, label) => { if (!ok) failures.push(label); };

  // ① JS 词法:注释里的 /api 不算调用点,字符串里的 // 不能被当注释切坏,正则字面量不能吃掉后文
  const js = [
    "// 数据:/api/ghost/in-comment",
    "/* 块注释里的 /api/ghost/in-block */",
    "const site = 'https://www.chengyinhub.com/prod-api';",
    "const RE = /\\/api\\/ghost-in-regex/;",
    "app.sendRequest({ url: '/api/real/one', method: 'POST' });",
    "req('/api/real/two', 'GET');",
    "wx.request({ url: base + '/api/real/three/' + id + '/accept' });",
    "const u = `/api/real/four/${id}`;",
  ].join('\n');
  const lits = scanJsStringLiterals(js).filter((l) => isApiLiteral(l.value));
  const vals = lits.map((l) => l.value);
  check(!vals.some((v) => v.includes('ghost')), `词法:注释/正则里的 /api 被当成了调用点 → ${JSON.stringify(vals)}`);
  check(vals.includes('/api/real/one'), '词法:漏抓 url: 字面量');
  check(vals.includes('/api/real/two'), '词法:漏抓 req() 位置参');
  check(vals.includes('/api/real/three/'), '词法:漏抓拼接前缀');
  check(vals.some((v) => v.startsWith('/api/real/four/') && v.includes(DYN)), '词法:漏抓模板串 ${} 动态段');
  check(!vals.includes('https://www.chengyinhub.com/prod-api'), '词法:非 /api 开头的串不该进来');

  // ② Java 抽取:类级前缀 + 方法级、value=、数组、裸注解、produces 不能被当路径
  const java = [
    'package x;',
    '@RestController',
    '// @RequestMapping("/api/ghost/in-comment")',
    '@RequestMapping("/api/demo")',
    'public class DemoController {',
    '  @PostMapping("/list") public void a() {}',
    '  @GetMapping(value = "/detail/{id}") public void b() {}',
    '  @GetMapping(value = { "/x", "/y" }) public void c() {}',
    '  @GetMapping public void root() {}',
    '  @PostMapping(value = "/z", produces = "application/json") public void d() {}',
    '}',
  ].join('\n');
  const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'api-gate-selftest-'));
  const jdir = path.join(tmp, 'chengyinhub-admin/src/main/java');
  fs.mkdirSync(jdir, { recursive: true });
  fs.writeFileSync(path.join(jdir, 'DemoController.java'), java);
  const { mappings } = collectBackendMappings(tmp);
  const pats = mappings.map((m) => m.pattern).sort();
  check(!pats.some((p) => p.includes('ghost')), `Java:注释里的 @RequestMapping 被当真 → ${JSON.stringify(pats)}`);
  check(pats.includes('/api/demo/list'), `Java:类级+方法级没拼上 → ${JSON.stringify(pats)}`);
  check(pats.includes('/api/demo/detail/{id}'), 'Java:value= 形态没抓到');
  check(pats.includes('/api/demo/x') && pats.includes('/api/demo/y'), 'Java:数组形态没抓全');
  check(pats.includes('/api/demo'), 'Java:裸 @GetMapping 没落到类级路径');
  check(pats.includes('/api/demo/z'), 'Java:带 produces 的没抓到');
  check(!pats.some((p) => p.includes('application')), 'Java:produces 的值被当成了路径');

  // 查询参数不属于 Spring Mapping 路径。静态值与动态值都必须只核对 `?` 前的路由，
  // 但路径变量仍要继续接受后端 `{id}` 的逐段校验，不能因为带 query 就落入 C 类逃生口。
  const xdir = path.join(tmp, 'chengyinhub-xcx');
  fs.mkdirSync(xdir, { recursive: true });
  fs.writeFileSync(path.join(xdir, 'query.js'), [
    "send({ url: '/api/demo/list?windowDays=7' });",
    'send({ url: `/api/demo/list?page=${page}` });',
    'send({ url: `/api/demo/detail/${id}?page=${page}` });',
  ].join('\n'));
  const queryCalls = collectFrontendCalls(tmp).calls;
  const queryResult = analyse(queryCalls, mappings, new Set());
  check(queryResult.missingStatic.size === 0,
    `查询参数:合法 Mapping 被 query 当成路径判红 → ${JSON.stringify([...queryResult.missingStatic.keys()])}`);
  check(queryResult.missingDynamic.size === 0 && queryResult.undecided.length === 0,
    `查询参数:仅 query 动态却落入 C 类/动态缺失 → ${JSON.stringify({ missing: [...queryResult.missingDynamic.keys()], undecided: queryResult.undecided })}`);
  fs.writeFileSync(path.join(xdir, 'query-ghost.js'),
    "send({ url: '/api/query-ghost?windowDays=7' });\n");
  const ghostQueryResult = analyse(collectFrontendCalls(tmp).calls, mappings, new Set());
  check(ghostQueryResult.missingStatic.has('/api/query-ghost'),
    `查询参数:不存在的 Mapping 被 query 剥离误放行 → ${JSON.stringify([...ghostQueryResult.missingStatic.keys()])}`);
  fs.rmSync(tmp, { recursive: true, force: true });

  // ③ ★连字符陷阱负控:/api/club/topic 不得被 /api/club/topic-registrations 接住。
  //    这就是 \bdetail\b 命中 detail-cta-bar 的同族;少一个 $ 锚就假绿。
  const hyphenMappings = [{ pattern: '/api/club/topic-registrations', verb: 'Post', file: 'F.java', line: 1 }];
  const hyphenCalls = [{ file: 'p.js', line: 1, raw: '/api/club/topic', dynamic: false, staticPrefix: '/api/club/topic' }];
  const hyphenResult = analyse(hyphenCalls, hyphenMappings, new Set());
  check(hyphenResult.missingStatic.has('/api/club/topic'),
    '连字符陷阱:/api/club/topic 被 /api/club/topic-registrations 假绿接住了');
  // 反向也要成立:真调 topic-registrations 必须判绿,不能连正常路径都误报
  const hyphenOk = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/club/topic-registrations', dynamic: false, staticPrefix: '/api/club/topic-registrations' }],
    hyphenMappings, new Set()
  );
  check(hyphenOk.missingStatic.size === 0, '连字符陷阱:正常路径被误判红,门禁会制造假故障');

  // ④ 路径变量:/api/x/12/accept 必须被 /api/x/{id}/accept 接住;段内不跨 /
  const pvMappings = [{ pattern: '/api/x/{id}/accept', verb: 'Post', file: 'F.java', line: 1 }];
  const pvGreen = analyse([{ file: 'p.js', line: 1, raw: '/api/x/12/accept', dynamic: false, staticPrefix: '/api/x/12/accept' }], pvMappings, new Set());
  check(pvGreen.missingStatic.size === 0, '路径变量:{id} 没能接住具体值');
  const pvRed = analyse([{ file: 'p.js', line: 1, raw: '/api/x/12/34/accept', dynamic: false, staticPrefix: '/api/x/12/34/accept' }], pvMappings, new Set());
  check(pvRed.missingStatic.size === 1, '路径变量:{id} 跨 / 匹配了,等于放行任意深度');

  // ⑤ 未判定不许静默:动态调用即使前缀命中,也必须落进 C 类清单
  const dynRes = analyse(
    [{ file: 'p.js', line: 9, raw: '/api/x/', dynamic: true, staticPrefix: '/api/x/' }],
    [{ pattern: '/api/x/{id}', verb: 'Get', file: 'F.java', line: 1 }], new Set()
  );
  check(dynRes.undecided.length === 1, '未判定:动态调用被静默当成通过了');
  const dynMiss = analyse(
    [{ file: 'p.js', line: 9, raw: '/api/nowhere/', dynamic: true, staticPrefix: '/api/nowhere/' }], [], new Set()
  );
  check(dynMiss.missingDynamic.size === 1, '未判定:连前缀都没有 mapping 的动态调用没判红');

  // ⑤b ★连字符陷阱的动态版:'/api/club/topic-registrations/' + id 不得被 /api/club/topic/{id} 接住。
  //     按字符串 startsWith 比就会假绿(这是 U1 里第二处 \b 同族陷阱)。
  const dynHyphen = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/club/topic-registrations/', dynamic: true, staticPrefix: '/api/club/topic-registrations/' }],
    [{ pattern: '/api/club/topic/{id}', verb: 'Get', file: 'F.java', line: 1 }], new Set()
  );
  check(dynHyphen.missingDynamic.size === 1,
    '连字符陷阱(动态):topic-registrations 被 /api/club/topic/{id} 假绿接住了');

  // ⑤c ★恒真放行负控:类级 @RequestMapping("/")(SysIndexController 真有这么一个)
  //     若按字符串前缀比,它是所有路径的前缀 ⇒ 任何动态调用都能找到候选 = 门禁永远不会红。
  const rootTaut = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/nowhere/', dynamic: true, staticPrefix: '/api/nowhere/' }],
    [{ pattern: '/', verb: 'Request', file: 'SysIndexController.java', line: 1 }], new Set()
  );
  check(rootTaut.missingDynamic.size === 1, '恒真放行:类级 @RequestMapping("/") 把动态调用全接住了');

  // ⑥ 逃生口真的生效,且是整串匹配(不是前缀)
  const allowed = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/gone', dynamic: false, staticPrefix: '/api/gone' }], [], new Set(['/api/gone'])
  );
  check(allowed.missingStatic.size === 0, '逃生口:allowlist 没生效');
  const allowedNear = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/gone-too', dynamic: false, staticPrefix: '/api/gone-too' }], [], new Set(['/api/gone'])
  );
  check(allowedNear.missingStatic.size === 1, '逃生口:allowlist 的 /api/gone 顺手放行了 /api/gone-too(连字符假绿)');

  // ⑧ ★拼接表达式行走器 walkConcat:后缀字面量必须收进来,看不懂的必须承认看不懂
  const concatSrc = [
    "a({ url: '/api/a/' + id + '/b', method: 'POST' });",
    "b({ url: '/api/c/' + this.data.x });",
    "c({ url: '/api/d/' + (cond ? 'x' : 'y') });",
    "d({ url: '/api/e' + '/f' });",
    "e({ url: '/api/g/' + fn(1, 2) + '/h' });",
    "f({ url: cond ? '/api/h/' + a : '/api/i' });",
    "g({ url: '/api/j/' + a || '/api/k' });",
  ].join('\n');
  const concatCalls = [];
  for (const lit of scanJsStringLiterals(concatSrc)) {
    if (!isApiLiteral(lit.value)) continue;
    const probe = concatSrc.slice(lit.endIndex, lit.endIndex + 8).replace(/^[\s\\]+/, '');
    const concatenated = probe.startsWith('+') && probe[1] !== '+' && probe[1] !== '=';
    const w = concatenated ? walkConcat(concatSrc, lit.endIndex) : { tail: '', complete: true };
    concatCalls.push({ path: displayPath(lit.value + w.tail), complete: w.complete });
  }
  check(concatCalls[0] && concatCalls[0].path === '/api/a/{}/b' && concatCalls[0].complete,
    `walkConcat:后缀字面量没收进来 → ${JSON.stringify(concatCalls[0])}`);
  check(concatCalls[1] && concatCalls[1].path === '/api/c/{}' && concatCalls[1].complete,
    `walkConcat:属性链结尾没走完 → ${JSON.stringify(concatCalls[1])}`);
  // 括号包起来的三元是【一个】操作数,整体求值成一段 —— 记成一个变量段是准确的,不是蒙
  check(concatCalls[2] && concatCalls[2].path === '/api/d/{}' && concatCalls[2].complete,
    `walkConcat:括号里的三元应当算一个变量段 → ${JSON.stringify(concatCalls[2])}`);
  check(concatCalls[3] && concatCalls[3].path === '/api/e/f' && concatCalls[3].complete,
    `walkConcat:字面量 + 字面量没拼上 → ${JSON.stringify(concatCalls[3])}`);
  check(concatCalls[4] && concatCalls[4].path === '/api/g/{}/h' && concatCalls[4].complete,
    `walkConcat:带逗号的函数调用把表达式截断了 → ${JSON.stringify(concatCalls[4])}`);
  // ★ 真正该承认「看不懂」的两种:表达式还没完就撞上外层三元的 : / 逻辑运算 ||
  //   —— 后面可能还有别的片段,这时候把手上这半截当成「拼完了」就是拿蒙的结果去判红。
  //   ★ 按路径找,不按下标找:三元的两个分支各自是一个 /api 字面量,下标会随用例增删漂移
  const byPrefix = (pre) => concatCalls.find((c) => c.path.startsWith(pre));
  check((byPrefix('/api/h/') || {}).complete === false,
    `walkConcat:外层三元的 : 没让它承认看不懂 → ${JSON.stringify(byPrefix('/api/h/'))}`);
  check((byPrefix('/api/j/') || {}).complete === false,
    `walkConcat:|| 没让它承认看不懂 → ${JSON.stringify(byPrefix('/api/j/'))}`);
  //   反向:三元里那个不参与拼接的裸字面量,该照常算「拼完了」
  check((byPrefix('/api/i') || {}).complete === true && byPrefix('/api/i').path === '/api/i',
    `walkConcat:不参与拼接的裸字面量被误判成没走完 → ${JSON.stringify(byPrefix('/api/i'))}`);

  // ⑨ ★★升级判定不能靠放宽:拼接式指向【不存在】的后端路径,必须红
  const cityMappings = [
    { pattern: '/api/city/nodes/{id}/favorite', verb: 'Post', file: 'F.java', line: 1 },
    { pattern: '/api/city/nodes/{id}', verb: 'Get', file: 'F.java', line: 2 },
  ];
  const concatCall = (resolved) => ([{ file: 'p.js', line: 1, raw: '/api/city/nodes/', resolved, dynamic: true, complete: true, staticPrefix: '/api/city/nodes/' }]);
  const ghost = analyse(concatCall(`/api/city/nodes/${DYN}/ghost`), cityMappings, new Set());
  check(ghost.missingStatic.size === 1 && ghost.missingStatic.has('/api/city/nodes/{}/ghost'),
    `升级判定:拼接式指向不存在的后端路径没判红 —— 那是靠放宽换来的假绿 → ${JSON.stringify([...ghost.missingStatic.keys()])}`);
  check(ghost.undecided.length === 0, '升级判定:不存在的路径被塞进「未判定」蒙混过关');
  // 正向:真的拼出了已知路径,必须判绿(不许因为收紧而制造假故障)
  const realHit = analyse(concatCall(`/api/city/nodes/${DYN}/favorite`), cityMappings, new Set());
  check(realHit.missingStatic.size === 0 && realHit.undecided.length === 0,
    '升级判定:真的拼出了已知路径却被判红,门禁会制造假故障');
  // 段数不同不许对上
  const deeper = analyse(concatCall(`/api/city/nodes/${DYN}/favorite/extra`), cityMappings, new Set());
  check(deeper.missingStatic.size === 1, '升级判定:段数不同也算命中,等于放行任意深度');

  // ⑨b ★★两个方向同时猜 = 不算候选(真仓库上实测踩到过的假绿,锁死)
  //     调用 /api/city/nodes/<变量>/favorite-ghost 曾被 /api/city/nodes/merchant/{merchantId} 接住:
  //     靠「假定该变量恰好 = merchant」+「把 favorite-ghost 塞进 {merchantId}」两次猜测凑成,
  //     于是一条指向不存在接口的调用落进「未判定」而不是红 —— 门禁 exit 0,等于没升级。
  const twoWayMappings = [
    { pattern: '/api/city/nodes/{id}/favorite', verb: 'Post', file: 'F.java', line: 1 },
    { pattern: '/api/city/nodes/merchant/{merchantId}', verb: 'Get', file: 'F.java', line: 2 },
  ];
  const twoWay = analyse(concatCall(`/api/city/nodes/${DYN}/favorite-ghost`), twoWayMappings, new Set());
  check(twoWay.missingStatic.size === 1,
    `两向猜测:指向不存在接口的拼接调用被隔壁 mapping 接住了 → ${JSON.stringify({ u: twoWay.undecided.length, m: [...twoWay.missingStatic.keys()] })}`);
  check(twoWay.undecided.length === 0, '两向猜测:被塞进「未判定」蒙混过关,门禁不会红');
  // 反向:只猜一个方向的仍然算候选(否则 #7 那一处会被误判红)
  const oneWay = analyse(concatCall(`/api/city/nodes/${DYN}/${DYN}`), twoWayMappings, new Set());
  check(oneWay.undecided.length === 1 && oneWay.missingStatic.size === 0,
    '两向猜测:收紧过头,只猜一个方向的也被判红了');

  // ⑩ ★「变量段顶后端字面量段」只能算未判定 —— 不判绿也不判红(这是 #7 那一处的形状)
  const guess = analyse(concatCall(`/api/city/nodes/${DYN}/${DYN}`), cityMappings, new Set());
  check(guess.undecided.length === 1 && /变量段要顶后端的字面量段/.test(guess.undecided[0].reason),
    `升级判定:变量段顶字面量段被当成了判绿 → ${JSON.stringify({ u: guess.undecided.length, m: [...guess.missingStatic.keys()] })}`);
  check(guess.missingStatic.size === 0, '升级判定:变量段顶字面量段被误判红');

  // ⑪ ★连字符陷阱的拼接版:/api/club/topic-registrations/{} 不得被 /api/club/topic/{id} 接住
  const hyConcat = analyse(
    [{ file: 'p.js', line: 1, raw: '/api/club/topic-registrations/', resolved: `/api/club/topic-registrations/${DYN}`, dynamic: true, complete: true, staticPrefix: '/api/club/topic-registrations/' }],
    [{ pattern: '/api/club/topic/{id}', verb: 'Get', file: 'F.java', line: 1 }], new Set()
  );
  check(hyConcat.missingStatic.size === 1,
    '连字符陷阱(拼接·已判定):topic-registrations 被 /api/club/topic/{id} 假绿接住了');

  // ⑪ ★G4:逃生口不再裸着 —— 理由必填、占位符判红、条数与 C 类上限都只许减不许增。
  //     同一批次里 U5 有这一套而 U1 没有,是双标;而 U1 这个逃生口更硬:
  //     每一行都让一个按钮变成死按钮。
  check(parseApiAllowlist('/api/x  # 后端下周补,已跟后端确认').problems.length === 0,
    '逃生口:合法行被判红');
  check(parseApiAllowlist('/api/x').problems.some((x) => /没写理由/.test(x)),
    '逃生口:没写理由却放行 —— 那就退回成一行路径什么都不用交代');
  check(parseApiAllowlist('/api/x  # TODO 回头补').problems.some((x) => /占位符/.test(x)),
    '逃生口:理由写 TODO 却放行');
  check(parseApiAllowlist('/api/x  # 待填').problems.some((x) => /占位符/.test(x)),
    '逃生口:理由写「待填」却放行');
  check(parseApiAllowlist('/api/x  # 理由\n/api/x  # 又一遍').problems.some((x) => /重复登记/.test(x)),
    '逃生口:同一路径重复登记却放行');
  check(parseApiAllowlist('# 纯注释\n\n').problems.length === 0, '逃生口:纯注释/空行被误判红');
  check(parseApiAllowlist('# UNDECIDED_MAX 3\n').undecidedMax === 3, '逃生口:UNDECIDED_MAX 没解析到');
  //     条数棘轮
  check(checkAllowlistRatchet(2, 1) !== null, '逃生口棘轮:条数涨了却没判红 —— 那它就是个能无限写的静默豁免');
  check(/只许减不许增/.test(checkAllowlistRatchet(2, 1) || ''), '逃生口棘轮:判红了但没说清为什么');
  check(checkAllowlistRatchet(0, 1) === null, '逃生口棘轮:真删掉一条(变少)反被判红');
  check(checkAllowlistRatchet(1, 1) === null, '逃生口棘轮:没动条数的 PR 被误判红');
  check(checkAllowlistRatchet(2, null) === null, '逃生口棘轮:master 上还没这份册子时不该判红');
  //     C 类棘轮 —— 没有上限时「未判定」就是无限逃生口
  check(checkUndecidedRatchet(1, null) !== null,
    'C 类棘轮:没声明 UNDECIDED_MAX 却放行 —— C 类永不判红,没上限就是个无限逃生口');
  check(checkUndecidedRatchet(2, 1) !== null, 'C 类棘轮:未判定条数超上限却放行');
  check(checkUndecidedRatchet(1, 1) === null, 'C 类棘轮:正好等于上限被误判红');
  check(checkUndecidedRatchet(0, 1) === null, 'C 类棘轮:真把未判定清掉了反被判红');

  // ⑫ ★G1:直接打 patternToRegex 的锚定,不经过 analyse。
  //     原注释声称「负控在 --selftest 里锁死」是假的 —— 实测删 $ 照样全绿,因为
  //     analyse 早已改走 segmentMatch,只有含 * 的 pattern 才落到 patternToRegex,
  //     而实仓 972 条 mapping 里带 * 的 = 0 ⇒ 这条路径没有任何用例走到。
  //     负控必须直接打被声称的那个判据,不能隔着一层「恰好走不到」的调用链。
  const reTopic = patternToRegex('/api/club/topic');
  check(reTopic.test('/api/club/topic'), 'patternToRegex 锚定:正常路径被拒');
  check(!reTopic.test('/api/club/topic-registrations'),
    'patternToRegex 锚定:少了 $ —— /api/club/topic 把 topic-registrations 接住了(连字符假绿)');
  check(!reTopic.test('/x/api/club/topic'),
    'patternToRegex 锚定:少了 ^ —— 前面挂任意前缀也能命中');
  const reVar = patternToRegex('/api/x/{id}/accept');
  check(reVar.test('/api/x/12/accept'), 'patternToRegex:{id} 接不住具体值');
  check(!reVar.test('/api/x/12/34/accept'), 'patternToRegex:{id} 跨 / 了,等于放行任意深度');
  check(!reVar.test('/api/x/12/accepted'), 'patternToRegex:少了 $ —— accept 把 accepted 接住了');
  const reStar = patternToRegex('/api/x/*');
  check(reStar.test('/api/x/abc') && !reStar.test('/api/x/a/b'),
    'patternToRegex:* 应当只吃一段');
  check(patternToRegex('/api/x/**').test('/api/x/a/b'), 'patternToRegex:** 应当吃任意段');

  // ⑬ ★G2:直接打 MIN_SHARED_SEGMENTS 这个常量本身。
  //     原来只有一条用例钉住 "/"(0 段)那个场景,而注释宣称的判据是「至少共享 2 段」——
  //     把 2 改成 1 注入后 selftest 照绿(等价变异)。判据是几就得有用例钉住几。
  check(MIN_SHARED_SEGMENTS === 2,
    `MIN_SHARED_SEGMENTS 被改了(现为 ${MIN_SHARED_SEGMENTS})—— 下面几条用例是按「至少 2 段」写的,`
    + '改了这个常量必须连同它们一起重新论证,不能只改数字');
  //     只共享 1 段(/api)不算「同一条路」:否则任何 /api/* 的动态调用都能互相当候选
  check(!prefixCompatible('/api', '/api/city'),
    'MIN_SHARED_SEGMENTS:只共享 1 段就算兼容 —— 那 /api 会成为所有路径的候选,等于恒真放行');
  check(!prefixCompatible('/api/', '/api/official/events/'),
    'MIN_SHARED_SEGMENTS:/api/ 这种 1 段前缀不该跟任何 /api/xxx 兼容');
  //     共享 2 段才算,且第 2 段必须整段相等(连字符陷阱在这一层)
  check(prefixCompatible('/api/city', '/api/city/nodes/'), '共享 2 段应当算兼容');
  check(!prefixCompatible('/api/city', '/api/city-node'),
    'MIN_SHARED_SEGMENTS:第 2 段按前缀比了 —— city 不该跟 city-node 兼容');

  // ⑦ B 类:无人调用的 mapping 要被点名,但不判红
  const unused = analyse([], [{ pattern: '/api/lonely', verb: 'Get', file: 'F.java', line: 1 }], new Set());
  check(unused.unusedBackend.length === 1, 'B 类:没被调用的 mapping 没被点名');
  check(unused.missingStatic.size === 0, 'B 类:不该判红');

  if (failures.length) {
    console.error('接口存在性门禁:自证失败');
    failures.forEach((f) => console.error(`  ✗ ${f}`));
    process.exit(1);
  }
  console.log('接口存在性门禁:自证通过'
    + '(词法能分开注释/正则/字符串、Java 类级+方法级+数组+裸注解+produces 都对、'
    + '连字符不假绿也不误报、路径变量不跨段、动态调用不静默、逃生口整串匹配、B 类只报不红;'
    + '拼接升级:后缀字面量收得进、外层三元/|| 承认看不懂、拼出不存在的路径判红、'
    + '拼出已知路径判绿、段数不同不命中、变量段顶字面量段只算未判定、两个方向同时猜不算候选;'
    + 'patternToRegex 的 ^ 与 $ 各自被直接钉住、MIN_SHARED_SEGMENTS=2 这个判据本身被钉住;'
    + '逃生口理由必填/占位符判红/条数只减不增、C 类未判定也有上限且只减不增)');
}

// ---------------------------------------------------------------------------
function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--selftest')) { runSelfTest(); return; }

  const root = repoRoot();
  const { calls, fileCount } = collectFrontendCalls(root);
  const { mappings, controllerFileCount } = collectBackendMappings(root);
  const allow = readAllowlist(root);
  const result = analyse(calls, mappings, allow.entries);

  // ── 逃生口与 C 类的两条棘轮(G4)。与 U5 同一套判据,同一批次不该双标。 ──
  const gateProblems = [...allow.problems];
  const masterText = masterFileText(root, ALLOWLIST_FILE);
  if (masterText === null) {
    console.log(`(${ALLOWLIST_FILE} 在 github/master 上还不存在,本次不校验两条「只减不增」)`);
  } else {
    const masterParsed = parseApiAllowlist(masterText);
    const p1 = checkAllowlistRatchet(allow.size, masterParsed.entries.size);
    if (p1) gateProblems.push(p1);
    if (masterParsed.undecidedMax !== null && allow.undecidedMax !== null
        && allow.undecidedMax > masterParsed.undecidedMax) {
      gateProblems.push(`${ALLOWLIST_FILE} 的 UNDECIDED_MAX 从 ${masterParsed.undecidedMax}`
        + ` 涨到了 ${allow.undecidedMax} —— 这个上限也只许减不许增,否则「未判定」仍是无限逃生口。`);
    }
  }
  const p2 = checkUndecidedRatchet(result.undecided.length, allow.undecidedMax);
  if (p2) gateProblems.push(p2);

  const missingCallCount = [...result.missingStatic.values()].reduce((n, a) => n + a.length, 0)
    + [...result.missingDynamic.values()].reduce((n, a) => n + a.length, 0);

  const stats = {
    fileCount,
    callCount: calls.length,
    staticCount: calls.filter((c) => !c.dynamic).length,
    dynamicCount: calls.filter((c) => c.dynamic).length,
    controllerFileCount,
    mappingCount: mappings.length,
    missingCallCount,
  };
  const rc = report(result, stats, { baselineOnly: argv.includes('--baseline') });
  if (gateProblems.length && !argv.includes('--baseline')) {
    console.error('');
    console.error('[api-gate] ✗ 逃生口 / 未判定这两处的账没记住:');
    gateProblems.forEach((x) => console.error(`      ${x}`));
    process.exit(1);
  }
  process.exit(rc);
}

if (require.main === module) main();

module.exports = {
  namespaceDeclarationOffsets, scanJs, scanJsStringLiterals, maskJsNonCode, collectFrontendCalls, collectBackendMappings,
  maskJavaComments, pathsFromAnnotationArgs, patternToRegex, staticPrefixOf, analyse,
  prefixCompatible, segmentMatch, MIN_SHARED_SEGMENTS,
  parseApiAllowlist, checkAllowlistRatchet, checkUndecidedRatchet,
};
