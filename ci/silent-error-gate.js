#!/usr/bin/env node
'use strict';

/**
 * U5 · silentError 白名单门禁:小程序里每一处 `silentError: true` 都必须在白名单册子里,
 * 并且写明**为什么这里可以静默**。
 *
 * 为什么值得建这道闸:silentError 是所有「接口不存在 / 后端报错」类缺陷的放大器。
 * request-client 对 code!=200 本来会自动 toast 一次(utils/transport/request-client.js:100/123),
 * 传了 silentError 就关掉这一声 —— 于是「接口 404」和「数据真的为空」在屏幕上长得一模一样,
 * console 也不响。它有正当用途(页面自己用 cy-error 内联报错、防双弹、埋点这类背景请求),
 * 所以不能一刀切禁,只能记账:每一处都得有人签字说明理由,新增必须过一次人眼。
 * 姊妹门禁 ci/api-existence-gate.js(U1)管的是「接口到底存不存在」。
 *
 * 用法:
 *   node ci/silent-error-gate.js              全仓核账(门禁本体)
 *   node ci/silent-error-gate.js --selftest   负控自证:能判红也能判绿
 *   node ci/silent-error-gate.js --baseline   只打清单不判红,顺带打印可粘贴的册子草稿
 *
 * 册子格式(ci/silent-error-allowlist.txt),一行一条(同一文件可多行,处数相加):
 *   <仓库根相对路径> <该行认领的处数>   # 为什么这里可以静默
 * 理由(#)必填,且不许只写 TODO/FIXME/待填 —— 占位符不是理由,它只是把空白换了个写法。
 *
 * ── 2026-08-18 存量基线棘轮 ──────────────────────────────────────────────
 * 立册当天 77 处 / 43 文件一次性登记为 `UNREVIEWED_BASELINE_20260818:<冻结处数>`。
 * 这【不是豁免】,是把它变成有人签过字的已知债 —— 没人逐条判过,所以理由栏写的是
 * 明确的未审查标记,不是编一句听起来合理的话(编了比不写更坏:会让下一个人以为审过了)。
 *
 * 闸必须能把「未审查存量」和「真写了理由」分开,否则棘轮一天就漏光:
 *   ① 存量行必须带冻结处数;② 该行申报处数 <= 冻结处数(只许往下走);
 *   ③ 全部冻结数之和 == 头部 `# BASELINE_TOTAL_<日期> <总数>`;
 *   ④ 一个文件只许一条存量行(否则拆行就能绕开 ②)。
 * ⇒ 往挂着存量标记的文件里新增一处,唯一能转绿的路是【另起一行写真理由】。
 *
 * ponytail: 记账粒度是「文件 + 处数」,不是「具体哪一行」。行号会随任何编辑腐烂,
 * 用行号当 key 的白名单三天就失效;代价是同一文件里「删一处 + 加一处」净额为零时看不出来。
 * 真要精确到调用点,再把 key 换成「文件 + 该调用点的 /api 路径」(需要括号配对找宿主对象)。
 * 剩下的天花板:同时改动某行的冻结数【和】头部 BASELINE_TOTAL 仍能把存量额度撑大 ——
 * 那需要在同一个 diff 里动两处写着「冻结」的字面量,是刺眼的故意动作,不会顺手发生。
 *
 * ⚠️ 三个假绿陷阱,selftest 里各锁了一条:
 *   ① 注释、字符串、【正则字面量】里的 "silentError" 不算调用点,且它们都不能把后面的
 *      真调用点吃掉 —— 词法与 U1 共用(见下面 maskJsCommentsAndStrings)。
 *   ② 标识符必须整词相等:`silentErrorFallback: true` / `notSilentError: true` 不算;
 *      文件路径必须整串相等:册子里的 `pages/x/index.js` 不许顺手放行 `pages/x-v2/index.js`
 *      —— 这就是 /\bdetail\b/ 命中 detail-cta-bar 的同一族陷阱。
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

// 门禁一律先回仓库根(见 ci/test_cwd_independence.sh)。
function repoRoot() {
  return execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim();
}

const XCX_DIR = 'chengyinhub-xcx';
const ALLOWLIST_FILE = 'ci/silent-error-allowlist.txt';
// tests/ 里满是 assert.match(source, /silentError:\s*true/) 这类断言字面量,scripts/ 是本机验证脚本,
// 都不是线上调用点。排除项显式打印,不静默。
const SKIP_DIRS = new Set(['node_modules', 'miniprogram_npm', 'tests', 'scripts', 'docs', 'images', '.git']);

// ---------------------------------------------------------------------------
// 只保留「代码」:注释 / 字符串 / 正则字面量的内容抹成等长空格(行号列号不变)。
//
// ★ 2026-08-18 换成姊妹门禁 U1 的共用词法(ci/api-existence-gate.js 的 scanJs)。
//   原来这里是自写的简化版,只认注释和引号、**不认正则字面量** —— 于是 `/'/g` 里的引号
//   被当成字符串起点,把它之后的整段代码抹成空格,后面的 silentError 调用点【一个都扫不到】。
//   实测连 `/['"]/g` 这种引号成对的正则也会触发,而这个形状本仓真出现过。
//
//   ⚠️ 这里原先那条注释写的是「最多造成一次可见的误报(红),不会造成假绿」——【写反了】。
//      它造成的恰恰是假绿。一条声称「不会假绿」的注释比没有注释更坏:它会让下一个人
//      不去查。现在的实际行为是:正则字面量整段抹掉,里面的引号不参与配对。
//      负控见 selftest 的「正则字面量」那一组。
// ---------------------------------------------------------------------------
const { maskJsNonCode } = require('./api-existence-gate.js');

function maskJsCommentsAndStrings(src) {
  return maskJsNonCode(src);
}

// 整词匹配:前后都不许是 JS 标识符字符,否则 silentErrorFallback / notSilentError 会被算进来。
const SILENT_ERROR_RE = /(^|[^A-Za-z0-9_$])silentError\s*:\s*true(?![A-Za-z0-9_$])/g;

function findSilentErrorSites(src) {
  const masked = maskJsCommentsAndStrings(src);
  const sites = [];
  SILENT_ERROR_RE.lastIndex = 0;
  let m;
  while ((m = SILENT_ERROR_RE.exec(masked)) !== null) {
    sites.push({ index: m.index, line: masked.slice(0, m.index).split('\n').length });
    SILENT_ERROR_RE.lastIndex = m.index + m[0].length;
  }
  return sites;
}

function walkFiles(dir, visit) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue;
      walkFiles(p, visit);
    } else if (e.isFile() && e.name.endsWith('.js')) {
      visit(p);
    }
  }
}

function scanRepo(root) {
  const byFile = new Map();
  let fileCount = 0;
  walkFiles(path.join(root, XCX_DIR), (p) => {
    fileCount += 1;
    const sites = findSilentErrorSites(fs.readFileSync(p, 'utf8'));
    if (sites.length) byFile.set(path.relative(root, p), sites);
  });
  return { byFile, fileCount };
}

// ---------------------------------------------------------------------------
// 册子
// ---------------------------------------------------------------------------
// 存量棘轮标记。冒号后面那个数是【冻结处数】—— 立册当天该文件有多少处未审查存量,
// 写死之后永不再改。册子头部另有一条 `# BASELINE_TOTAL_<日期> <总数>` 对全部冻结数求和兜底。
const BASELINE_MARKER_RE = /\bUNREVIEWED_BASELINE_(\d{8}):(\d+)/;
// 只写了标记、没写冻结处数(少了 :N)—— 单独认出来给一句人话,别掉进「格式不对」的大筐
const BASELINE_MARKER_LOOSE_RE = /\bUNREVIEWED_BASELINE_\d{8}\b/;
const BASELINE_TOTAL_RE = /^#\s*BASELINE_TOTAL_(\d{8})\s+(\d+)\s*$/;
// 占位符理由:开头就是 TODO/FIXME/XXX/待填/待补/问号的,一律不算理由
const PLACEHOLDER_REASON_RE = /^(TODO|FIXME|XXX|TBD|待填|待补|待定|\?)/i;

/**
 * 解析册子。一个文件可以有【多行】:一行存量棘轮 + 任意行「真写了理由」的。
 * 这不是宽松,恰恰是棘轮的出口 —— 往一个还挂着存量标记的文件里新加静默调用时,
 * 唯一能转绿的路是【另起一行写真理由】,而不是把存量那行的数字 +1。
 *
 * 棘轮三条约束(缺一条这册子一天就漏光):
 *   ① 存量行必须带冻结处数 UNREVIEWED_BASELINE_日期:N
 *   ② 该行申报处数 <= N —— 只许往下走。立册当天申报 == N,所以任何新增都会撞上这条
 *   ③ 全部冻结数之和 == 头部 BASELINE_TOTAL —— 想改 N 就得连带改总数,两处都是
 *      写着「冻结」的字面量,改它是个刺眼的、故意的动作,不会顺手发生
 * 一个文件只许有一条存量行(否则 ② 就能靠拆行绕开)。
 */
function parseAllowlist(text) {
  const entries = new Map();
  const problems = [];
  let baselineTotalDeclared = null;
  let baselineStamp = null;

  text.split('\n').forEach((rawLine, idx) => {
    const lineNo = idx + 1;
    const line = rawLine.trim();
    if (!line) return;
    if (line.startsWith('#')) {
      const t = BASELINE_TOTAL_RE.exec(line);
      if (t) {
        if (baselineTotalDeclared !== null) {
          problems.push(`${ALLOWLIST_FILE}:${lineNo} BASELINE_TOTAL 重复声明`);
          return;
        }
        baselineStamp = t[1];
        baselineTotalDeclared = Number(t[2]);
      }
      return;
    }
    const hash = line.indexOf('#');
    const decl = (hash === -1 ? line : line.slice(0, hash)).trim();
    const reason = hash === -1 ? '' : line.slice(hash + 1).trim();
    const m = /^(\S+)\s+(\d+)$/.exec(decl);
    if (!m) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} 格式不对,应为「<文件相对路径> <处数>  # 理由」:${rawLine.trim()}`);
      return;
    }
    const file = m[1];
    const count = Number(m[2]);
    if (!reason) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${file} 没写理由 —— 白名单的价值就在那句「为什么这里可以静默」`);
      return;
    }
    // 占位符不是理由。不拦这一条,册子最省事的绕法就是全填 TODO,闸当场退化成「有行就放行」。
    if (PLACEHOLDER_REASON_RE.test(reason)) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${file} 的理由是占位符「${reason.slice(0, 20)}」——`
        + ' 占位符不是理由。要么真写为什么可以静默,要么用 UNREVIEWED_BASELINE_ 标记如实说「没审过」。');
      return;
    }

    let baseline = null;
    const marked = BASELINE_MARKER_RE.exec(reason);
    if (marked) {
      baseline = { stamp: marked[1], frozen: Number(marked[2]) };
      if (count > baseline.frozen) {
        problems.push(`${ALLOWLIST_FILE}:${lineNo} ${file} 申报 ${count} 处 > 冻结 ${baseline.frozen} 处 ——`
          + ' 存量标记只许往下走。新增的静默调用要【另起一行写真理由】,不许挂在存量帐上。');
        return;
      }
    } else if (BASELINE_MARKER_LOOSE_RE.test(reason)) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${file} 的存量标记缺冻结处数,应写成 UNREVIEWED_BASELINE_日期:N`);
      return;
    }

    const entry = entries.get(file) || { allowed: 0, frozen: 0, lines: [] };
    if (baseline && entry.lines.some((l) => l.baseline)) {
      problems.push(`${ALLOWLIST_FILE}:${lineNo} ${file} 有两条存量行 —— 拆行会绕开「只许往下走」,一个文件只许一条`);
      return;
    }
    entry.allowed += count;
    if (baseline) entry.frozen += baseline.frozen;
    entry.lines.push({ count, reason, lineNo, baseline });
    entries.set(file, entry);
  });

  const frozenSum = [...entries.values()].reduce((n, e) => n + e.frozen, 0);
  if (baselineTotalDeclared === null) {
    if (frozenSum > 0) {
      problems.push(`${ALLOWLIST_FILE} 缺头部的 # BASELINE_TOTAL_<日期> <总数> —— 没有它,`
        + '单行的冻结数改了没人拦得住,棘轮就只剩一层纸。');
    }
  } else if (frozenSum !== baselineTotalDeclared) {
    problems.push(`${ALLOWLIST_FILE} 冻结处数之和 ${frozenSum} ≠ 头部声明的 BASELINE_TOTAL_${baselineStamp} `
      + `${baselineTotalDeclared} —— 有人动过「冻结」的数字。存量只该被【审查后拆成真理由】而变少,`
      + '不该凭空长大。');
  }

  return { entries, problems, baselineTotal: { declared: baselineTotalDeclared, actual: frozenSum, stamp: baselineStamp } };
}

/**
 * ★ 存量棘轮的最后一层:BASELINE_TOTAL 相对 github/master 【只许减不许增】。
 *
 * 2026-08-18 补。此前这道闸自认有个天花板(见文件头 ponytail 段):
 * 给一个【新文件】挂 UNREVIEWED_BASELINE_20260818:N,只要在同一个 diff 里把头部
 * BASELINE_TOTAL 一起加大,三条约束全都满足 ⇒ 转绿。
 * 也就是说「拿存量标记登记新增」这条裁决,靠前三层是拦不住的 —— 它们只在一份册子【内部】自洽,
 * 而作弊要改的正是那个自洽用的锚。锚必须跟【外部】比,才不是自己证明自己。
 *
 * 判据:current <= master。相等(既没清也没加)也放行 —— 大多数 PR 都不碰这个数。
 * 册子在 master 上还不存在时(首次引入)没有基线可比,放行并打印说明。
 */
function checkBaselineTotalRatchet(currentTotal, masterTotal) {
  if (masterTotal === null) return null;          // master 上还没有这份册子
  const cur = currentTotal === null ? 0 : currentTotal;   // 存量清零后整段可以不写
  if (cur <= masterTotal) return null;
  return `${ALLOWLIST_FILE} 的 BASELINE_TOTAL 从 ${masterTotal} 涨到了 ${cur} —— `
    + '存量基线【只许减不许增】。把新增的静默调用挂上 UNREVIEWED_BASELINE_ 标记、'
    + '再把总数一起调大,正是裁决明令禁止的「拿存量标记当新增的挡箭牌」。'
    + '新增要另起一行写真理由;这个数只有在【审查掉存量】时才该往下走。';
}

/**
 * ★ 存量棘轮的【文件级】外锚:每个文件的未审查存量申报处数相对 github/master 只能往下走。
 *
 * 2026-08-18 补(G3)。此前文件级只有一条 `count <= frozen` —— 那是一个【永久额度】,
 * 不是单调下降。利用路径**根本不碰 BASELINE_TOTAL**,所以 F2 那条总数外锚拦不住:
 *   第一步:某文件真删掉 2 处静默 ⇒ count 从 frozen 降到 frozen-2,全绿(这一步是好事)
 *   第二步:此后新增 2 处未审查的静默,**只把该文件那个数字改回 frozen**,
 *           理由栏仍是 UNREVIEWED_BASELINE、BASELINE_TOTAL 一动不动 ⇒ 全绿。
 * 跨提交、单处数字、不刺眼 —— 而它恰恰就是裁决明令禁止的「拿存量标记登记新增」。
 *
 * 病根与 F2 同源:`count <= frozen` 里的 frozen 是**册子自己写的**,
 * 一个只跟自己比的约束挡不住有人把自己调回去。真实额度必须是
 * min(历史上曾经到过的最小值, frozen) —— 降过就再也回不去。
 * 「历史最小值」用 master 上那一版当代理(每次合并把新的低点固化下来)。
 *
 * ⚠️ 这条与 F2 的 BASELINE_TOTAL 外锚是【两个层级】:
 *    F2 管总数(拦「新开一个文件条目 + 调大总数」),G3 管单文件额度(拦「把某文件的数字调回去」)。
 *    两条都要在,少哪条都有一条完整的绕过路径。
 */
function checkPerFileRatchet(currentEntries, masterEntries) {
  const problems = [];
  for (const [file, cur] of currentEntries) {
    const prev = masterEntries.get(file);
    if (!prev) continue;                    // master 上还没这条目 —— 由 BASELINE_TOTAL 那层管
    const curBaseline = cur.lines.filter((line) => line.baseline)
      .reduce((sum, line) => sum + line.count, 0);
    const prevBaseline = prev.lines.filter((line) => line.baseline)
      .reduce((sum, line) => sum + line.count, 0);
    if (curBaseline > prevBaseline) {
      problems.push(`${ALLOWLIST_FILE} ${file} 的存量申报处数从 ${prevBaseline} 涨到了 ${curBaseline}`
        + ' —— 每个文件的存量静默额度【只能棘轮向下】,降过就回不去了。'
        + '先删掉 2 处、之后再把数字调回原值来给新增腾位置,正是裁决禁止的'
        + '「拿存量标记当新增的挡箭牌」。新增要另起一行写真理由。');
    }
  }
  return problems;
}

/**
 * ★ 两条外锚的基线 ref 解析。
 *
 * 2026-08-19 修:原实现只试 `github/master` / `origin/master`,两个都拿不到就
 * **静默降级成「不校验两条外锚」**。CI 里 actions/checkout 默认 `fetch-depth: 1` 且只取
 * `refs/remotes/pull/<n>/merge`,这两个 ref 一个都不存在 ⇒ 棘轮在**最该生效的地方**没通电,
 * 日志还打成「册子在 github/master 上还不存在」——那是假话,master 上明明有(#736 已合)。
 *
 * 现在分成两件事,因为它们的处置完全不同:
 *   · **ref 解析不到** ⇒ 判红。外锚要跟外部比才有意义,比不了就不是「没问题」,是「没验」。
 *   · **ref 解析到了、但那份册子在它上面还不存在** ⇒ 放行(册子首次引入,确实没有基线可比)。
 *
 * 解析顺序:已在本地的候选 ref → 都没有就按 PR 基线分支去 fetch 一次(CI 里 actions/checkout
 * 留着凭据,`git fetch --depth=1` 拿得到)→ 再试一遍。
 */
function baselineRefCandidates() {
  const base = (process.env.GITHUB_BASE_REF || '').trim();
  const refs = ['github/master', 'origin/master'];
  if (base) refs.unshift(`origin/${base}`, `refs/remotes/origin/${base}`);
  return refs;
}

function resolveBaselineRef(root) {
  const tryRefs = (refs) => refs.find((ref) => {
    try {
      execFileSync('git', ['-C', root, 'rev-parse', '--verify', '--quiet', `${ref}^{commit}`],
        { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
      return true;
    } catch (e) { return false; }
  });

  let ref = tryRefs(baselineRefCandidates());
  if (ref) return ref;

  // 本地一个都没有:CI 的浅克隆就是这种形态。按 PR 基线分支补一次 fetch。
  const base = (process.env.GITHUB_BASE_REF || 'master').trim() || 'master';
  try {
    execFileSync('git', ['-C', root, 'fetch', '--no-tags', '--depth=1', 'origin', base],
      { encoding: 'utf8', stdio: ['ignore', 'ignore', 'ignore'] });
  } catch (e) { /* 没有 origin / 没网 / 没凭据:下面统一按「解析不到」判红 */ }
  return tryRefs(baselineRefCandidates().concat(['FETCH_HEAD']));
}

function masterAllowlist(root) {
  const ref = resolveBaselineRef(root);
  if (!ref) return { parsed: null, ref: null, refResolved: false };
  try {
    const text = execFileSync('git', ['-C', root, 'show', `${ref}:${ALLOWLIST_FILE}`],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    return { parsed: parseAllowlist(text), ref, refResolved: true };
  } catch (e) {
    // ref 在,但那一版上没有这份文件 —— 册子首次引入,确实没有基线可比。
    return { parsed: null, ref, refResolved: true };
  }
}

/**
 * ★ 负控要钉的就是这一条:拿不到基线 ref 时**必须判红**,不许静默跳过。
 *
 * 单独抽成纯函数,是为了让「解析不到 ⇒ 红」这个决定本身可被测试 ——
 * 埋在 main() 里的 if/else 没法在自证里证伪,而「静默跳过外锚」正是本轮反复抓到的形状。
 */
function baselineAnchorProblem(refResolved) {
  if (refResolved) return null;
  return `${ALLOWLIST_FILE} 的两条外锚(BASELINE_TOTAL 只减不增 / 逐文件存量额度只减不增)`
    + '拿不到基线 ref,无法校验 —— 这不是「没问题」,是「没验」,所以判红。'
    + '\n  已试过:' + baselineRefCandidates().join(' / ') + ' 以及 fetch 后的 FETCH_HEAD。'
    + '\n  CI 里的常见成因:actions/checkout 默认 fetch-depth: 1 且只取 pull/<n>/merge,'
    + '本地没有任何 master ref;本 job 需要能 `git fetch --depth=1 origin <base>`。'
    + '\n  本地跑出这一条,多半是这个 worktree 没有 github/origin 远端。';
}

function readAllowlist(root) {
  const file = path.join(root, ALLOWLIST_FILE);
  if (!fs.existsSync(file)) return { entries: new Map(), problems: [`缺文件 ${ALLOWLIST_FILE}`] };
  return parseAllowlist(fs.readFileSync(file, 'utf8'));
}

/**
 * 核账。文件路径一律【整串相等】比对(Map.get),不做任何前缀/包含匹配。
 * 三类都判红,方向不同:
 *   over     实际 > 登记 —— 有人新加了静默调用没签字(本门禁的主目标)
 *   under    实际 < 登记 —— 册子有虚额,不收回来就是给未来的静默调用留了白条
 *   unlisted 整个文件没登记
 */
function reconcile(byFile, entries) {
  const over = [];
  const under = [];
  const unlisted = [];
  const stale = [];

  for (const [file, sites] of byFile) {
    const entry = entries.get(file);
    if (!entry) { unlisted.push({ file, actual: sites.length, sites }); continue; }
    if (sites.length > entry.allowed) over.push({ file, actual: sites.length, allowed: entry.allowed, sites });
    else if (sites.length < entry.allowed) under.push({ file, actual: sites.length, allowed: entry.allowed });
  }
  for (const [file, entry] of entries) {
    // allowed === 0 的行是「存量已清零」的收据:该留着(它扛着冻结数,删了 BASELINE_TOTAL 就对不上),
    // 且它本来就不该有对应调用,所以不算失效登记。
    if (!byFile.has(file) && entry.allowed > 0) stale.push({ file, allowed: entry.allowed });
  }
  return { over, under, unlisted, stale };
}

// ---------------------------------------------------------------------------
function report(scan, allow, result, { baselineOnly }) {
  const total = [...scan.byFile.values()].reduce((n, s) => n + s.length, 0);
  const unreviewed = [...allow.entries.values()]
    .reduce((n, e) => n + e.lines.filter((l) => l.baseline).reduce((k, l) => k + l.count, 0), 0);
  const reviewed = [...allow.entries.values()]
    .reduce((n, e) => n + e.lines.filter((l) => !l.baseline).reduce((k, l) => k + l.count, 0), 0);
  console.log(`扫描 ${scan.fileCount} 个小程序 js(已排除 ${[...SKIP_DIRS].join('/')}),`
    + `找到 ${total} 处 silentError:true,分布在 ${scan.byFile.size} 个文件;`
    + `册子登记 ${allow.entries.size} 个文件。`);
  console.log(`其中:存量未审查 ${unreviewed} 处(冻结基线 `
    + `${allow.baselineTotal.declared === null ? '未声明' : `BASELINE_TOTAL_${allow.baselineTotal.stamp}=${allow.baselineTotal.declared}`}`
    + `),写了真理由的 ${reviewed} 处。`);

  const { over, under, unlisted, stale } = result;
  console.log('');
  console.log(`【未登记】${unlisted.length} 个文件 / ${unlisted.reduce((n, u) => n + u.actual, 0)} 处`);
  unlisted.sort((a, b) => (a.file < b.file ? -1 : 1)).forEach((u) => {
    console.log(`  ✗ ${u.file}  ${u.actual} 处  行 ${u.sites.map((s) => s.line).join(',')}`);
  });
  console.log('');
  console.log(`【超额】${over.length} 个文件(实际多于登记 = 新增了没签字的静默调用)`);
  over.forEach((o) => console.log(`  ✗ ${o.file}  实际 ${o.actual} > 登记 ${o.allowed}  行 ${o.sites.map((s) => s.line).join(',')}`));
  console.log('');
  console.log(`【虚额】${under.length} 个文件 + ${stale.length} 条已无对应调用的登记(册子该收回来)`);
  under.forEach((u) => console.log(`  ✗ ${u.file}  实际 ${u.actual} < 登记 ${u.allowed}`));
  stale.forEach((s) => console.log(`  ✗ ${s.file}  登记 ${s.allowed} 处,但该文件已无 silentError:true(或文件已不在)`));
  if (allow.problems.length) {
    console.log('');
    console.log(`【册子本身有问题】${allow.problems.length} 条`);
    allow.problems.forEach((p) => console.log(`  ✗ ${p}`));
  }

  if (baselineOnly) {
    console.log('');
    console.log('---- 可粘贴的册子草稿(理由请逐条改写,别原样交)----');
    [...scan.byFile.entries()].sort().forEach(([f, s]) => {
      console.log(`${f} ${s.length}  # 在这里写清楚为什么这处可以静默(占位符会判红);没审过就写 UNREVIEWED_BASELINE_<日期>:${s.length}`);
    });
    return 0;
  }

  const bad = over.length + under.length + unlisted.length + stale.length + allow.problems.length;
  if (bad) {
    console.error('');
    console.error('[silent-error-gate] ✗ silentError 是「接口不存在 / 后端报错」的静音开关,每一处都要有人签字。');
    console.error(`[silent-error-gate]   登记到 ${ALLOWLIST_FILE}:「<文件相对路径> <处数>  # 为什么这里可以静默」。`);
    console.error('[silent-error-gate]   写不出理由 = 这处大概率不该静默:去掉 silentError,让 request-client 照常 toast。');
    return 1;
  }
  console.log('');
  console.log('silentError 白名单门禁:通过');
  return 0;
}

// ---------------------------------------------------------------------------
// 负控自证
// ---------------------------------------------------------------------------
function runSelfTest() {
  // 钩子会带入 GIT_DIR 等变量；临时仓库必须独立，否则 git init/config 会改调用者仓库。
  for (const key of execFileSync('git', ['rev-parse', '--local-env-vars'], { encoding: 'utf8' }).trim().split('\n')) {
    delete process.env[key];
  }
  const failures = [];
  const check = (ok, label) => { if (!ok) failures.push(label); };

  // ① 注释/字符串里的 silentError 不算调用点(本仓注释里真有十几处解释性文字)
  const src = [
    '// silentError: true  ← 这是注释里的说明,不是调用点',
    '/* 块注释:silentError: true */',
    "const doc = 'silentError: true';",
    'app.sendRequest({ url: "/api/a", silentError: true });',
    'app.sendRequest({ url: "/api/b", silentError:true });',
    'app.sendRequest({ url: "/api/c", hideLoading: true });',
  ].join('\n');
  const sites = findSilentErrorSites(src);
  check(sites.length === 2, `注释/字符串里的 silentError 被当成了调用点(应 2 处,实得 ${sites.length})`);
  check(sites[0] && sites[0].line === 4 && sites[1] && sites[1].line === 5,
    `行号被注释/字符串带偏了 → ${JSON.stringify(sites)}`);

  // ①b ★★正则字面量:里面的引号不是字符串起点,不许把后面的真调用点吃掉
  //     2026-08-18 实测的【假绿】。原来那版只认注释和引号,`/'/g` 里那个引号被当成字符串起点,
  //     把它之后的整段代码抹成空格 ⇒ 后面的 silentError 一个都扫不到,门禁 exit 0。
  //     而当时那条注释恰恰写着「不会造成假绿」。现在词法与 U1 共用(scanJs)。
  const regexCases = [
    ["正则含奇数单引号", "const q = /'/g;\napp.req({ url: '/api/x', silentError: true });"],
    ['正则含奇数双引号', 'const q = /"/g;\napp.req({ url: "/api/x", silentError: true });'],
    ['正则含反引号', 'const q = /`/g;\napp.req({ url: "/api/x", silentError: true });'],
    // ★ 引号【成对】也一样会坏 —— 本仓真出现过 /['"]/g 这个形状,别以为只有奇数引号危险
    ['正则含成对引号', 'const q = /[\'"]/g;\napp.req({ url: "/api/x", silentError: true });'],
    ['正则里带转义斜杠', 'const q = /\\/\'/g;\napp.req({ url: "/api/x", silentError: true });'],
  ];
  for (const [label, src] of regexCases) {
    const found = findSilentErrorSites(src);
    check(found.length === 1,
      `正则字面量(${label}):把后面的 silentError 吃掉了 —— 这是【假绿】不是误报,`
      + `实得 ${found.length} 处`);
    check(found.length === 1 && found[0].line === 2,
      `正则字面量(${label}):行号被带偏了 → ${JSON.stringify(found)}`);
  }
  // 反向:正则【里面】写了 silentError: true 不算调用点
  check(findSilentErrorSites('const q = /silentError: true/;').length === 0,
    '正则字面量里的 silentError 被当成了调用点');

  // ② ★整词陷阱:近名标识符不算数(\b 家族的假绿)
  const nearMiss = [
    'x({ silentErrorFallback: true });',
    'x({ notSilentError: true });',
    'x({ silentError: false });',
    'x({ silentError : true });',   // 空格形态要算
  ].join('\n');
  const nm = findSilentErrorSites(nearMiss);
  check(nm.length === 1 && nm[0].line === 4,
    `整词陷阱:silentErrorFallback / notSilentError / :false 被算进来了 → ${JSON.stringify(nm)}`);

  // ③ 册子解析:没写理由必须判红;格式不对必须判红;重复登记必须判红
  check(parseAllowlist('a/b.js 2  # 因为 X').problems.length === 0, '册子:合法行被判红');
  check(parseAllowlist('a/b.js 2').problems.length === 1, '册子:没写理由的行没被判红');
  check(parseAllowlist('a/b.js  # 因为 X').problems.length === 1, '册子:缺处数的行没被判红');
  check(parseAllowlist('# 纯注释行\n\n').problems.length === 0, '册子:纯注释/空行被误判红');
  // 占位符不是理由:不拦它,册子最省事的绕法就是全填 TODO
  check(parseAllowlist('a/b.js 2  # TODO 回头补').problems.some((p) => /占位符/.test(p)),
    '册子:理由写 TODO 却判绿 —— 闸退化成「有行就放行」');
  check(parseAllowlist('a/b.js 2  # 待填').problems.some((p) => /占位符/.test(p)), '册子:理由写「待填」却判绿');
  check(parseAllowlist('a/b.js 2  # 轮询,失败下一轮自然重来').problems.length === 0,
    '册子:正常理由被占位符规则误伤');
  // 同一文件多行「真理由」是合法的(处数相加)—— 这是棘轮给新增留的唯一出口
  const multi = parseAllowlist('a/b.js 1  # 因为 X\na/b.js 2  # 因为 Y');
  check(multi.problems.length === 0 && multi.entries.get('a/b.js').allowed === 3,
    `册子:同一文件多行真理由应相加 → ${JSON.stringify(multi.problems)}`);

  // ⑤b ★★BASELINE_TOTAL 只许减不许增(相对 github/master)
  //     补的是这道闸自认的那个天花板:给新文件挂存量标记 + 同一 diff 里把总数一起调大 ⇒ 曾经能转绿。
  //     前三层约束只在一份册子【内部】自洽,而作弊要改的正是那个自洽用的锚 —— 锚必须跟外部比。
  check(checkBaselineTotalRatchet(78, 77) !== null,
    '棘轮外锚:BASELINE_TOTAL 涨了却没判红 —— 「拿存量标记登记新增」这条裁决就还是绕得过去');
  check(/只许减不许增/.test(checkBaselineTotalRatchet(78, 77) || ''),
    '棘轮外锚:判红了但没说清为什么,人会以为是门禁抽风');
  check(checkBaselineTotalRatchet(70, 77) === null, '棘轮外锚:真审查掉了存量(变小)反被判红');
  check(checkBaselineTotalRatchet(77, 77) === null, '棘轮外锚:没动这个数的 PR 被误判红');
  check(checkBaselineTotalRatchet(0, 77) === null, '棘轮外锚:存量清零(降到 0)被误判红');
  check(checkBaselineTotalRatchet(null, 77) === null,
    '棘轮外锚:存量清零后整段不写了(current 为 null)应视作 0,不该判红');
  check(checkBaselineTotalRatchet(78, null) === null,
    '棘轮外锚:master 上还没有这份册子(首次引入)时不该判红 —— 没有基线可比');

  // ⑤b-2 ★★★ 拿不到基线 ref ⇒ 必须判红,不许静默跳过。
  //     2026-08-19 补。PR #741 的 CI 日志里这道闸打的是
  //     「ci/silent-error-allowlist.txt 在 github/master 上还不存在,本次不校验两条外锚」——
  //     而 master 上明明有(#736 已合)。真相是 CI 的浅克隆里一个 master ref 都没有,
  //     于是两条外锚**在最该生效的地方**静默失效,日志还给出一句假的解释。
  //     「拿不到基线 = 没验」不等于「没问题」,所以这里钉死它必须红。
  check(baselineAnchorProblem(false) !== null,
    '外锚:拿不到基线 ref 时静默跳过了 —— 棘轮在 CI 里等于没通电,这正是本条要防的');
  check(/判红/.test(baselineAnchorProblem(false) || ''),
    '外锚:判红了但没说清「这不是没问题、是没验」,人会当成噪音');
  check(/fetch-depth/.test(baselineAnchorProblem(false) || ''),
    '外锚:没写清 CI 里的成因,下一个人还得重新查一遍');
  check(baselineAnchorProblem(true) === null,
    '外锚:基线 ref 解析到了却仍判红 —— 会把所有正常 PR 拖红');

  // ⑤b-3 真负控:在一个【没有任何远端】的临时 git 仓库上跑真正的解析函数,
  //     它必须解析不出基线 ref(而不是凭空造一个),再由上面那条把它变成红。
  //     只测纯函数不够 —— 那样只证明「if 写对了」,证明不了「解析真会失败」。
  {
    const os = require('os');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'sil-gate-noremote-'));
    try {
      execFileSync('git', ['-C', tmp, 'init', '-q'], { stdio: ['ignore', 'ignore', 'ignore'] });
      execFileSync('git', ['-C', tmp, 'config', 'user.email', 'e2e@local'], { stdio: 'ignore' });
      execFileSync('git', ['-C', tmp, 'config', 'user.name', 'e2e'], { stdio: 'ignore' });
      fs.writeFileSync(path.join(tmp, 'x.txt'), 'x\n');
      execFileSync('git', ['-C', tmp, 'add', '.'], { stdio: 'ignore' });
      execFileSync('git', ['-C', tmp, 'commit', '-qm', 'init'], { stdio: ['ignore', 'ignore', 'ignore'] });
      const resolved = resolveBaselineRef(tmp);
      check(resolved === undefined || resolved === null,
        `外锚真负控:无远端仓库上竟解析出了基线 ref(${resolved}) —— 那说明解析在造假`);
      const noRemote = masterAllowlist(tmp);
      check(noRemote.refResolved === false,
        '外锚真负控:无远端时 refResolved 仍为 true —— 会被误判成「有基线、只是没册子」而放行');
      check(baselineAnchorProblem(noRemote.refResolved) !== null,
        '外锚真负控:无远端 ⇒ 端到端没有判红');
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }
  }

  // ⑤c ★★文件级额度棘轮(G3):count <= frozen 是【永久额度】,不是单调下降。
  //     利用路径不碰 BASELINE_TOTAL,所以 ⑤b 那条总数外锚拦不住:
  //     先真删 2 处让 count 降下去,之后新增 2 处未审查静默、只把数字改回 frozen ⇒ 曾经全绿。
  const mkEntries = (file, n) => parseAllowlist(
    `# BASELINE_TOTAL_20260818 7\n${file} ${n}  # UNREVIEWED_BASELINE_20260818:7 存量未审查`).entries;
  check(checkPerFileRatchet(mkEntries('a/b.js', 7), mkEntries('a/b.js', 5)).length === 1,
    '文件级棘轮:降到 5 之后又升回 7 没判红 —— 这正是不碰 BASELINE_TOTAL 的那条绕法');
  check(/只能棘轮向下/.test(checkPerFileRatchet(mkEntries('a/b.js', 7), mkEntries('a/b.js', 5))[0] || ''),
    '文件级棘轮:判红了但没说清为什么');
  check(checkPerFileRatchet(mkEntries('a/b.js', 3), mkEntries('a/b.js', 5)).length === 0,
    '文件级棘轮:真清理存量(继续往下降)反被判红');
  check(checkPerFileRatchet(mkEntries('a/b.js', 5), mkEntries('a/b.js', 5)).length === 0,
    '文件级棘轮:没动这个数的 PR 被误判红');
  check(checkPerFileRatchet(mkEntries('a/b.js', 7), mkEntries('other/c.js', 7)).length === 0,
    '文件级棘轮:master 上还没这条目(新开的文件)不该在这一层判红 —— 那一层归 BASELINE_TOTAL 管');
  const reviewedAddition = parseAllowlist(
    '# BASELINE_TOTAL_20260818 7\n'
    + 'a/b.js 5  # UNREVIEWED_BASELINE_20260818:7 存量未审查\n'
    + 'a/b.js 2  # 页面内错误态承接，避免双重 toast').entries;
  check(checkPerFileRatchet(reviewedAddition, mkEntries('a/b.js', 5)).length === 0,
    '文件级棘轮:另起一行写真理由的已审查增量被误判成存量额度回涨');
  //     ★ 两个层级都要在:证明这条绕法【不动】BASELINE_TOTAL,所以总数外锚确实拦不住它
  check(checkBaselineTotalRatchet(7, 7) === null,
    '前提自检:这条绕法里 BASELINE_TOTAL 不变,总数外锚对它必然放行 —— 所以文件级那条不能省');

  // ⑥ ★存量基线棘轮 —— 本门禁的核心,坏了它一天就漏光
  const BT = '# BASELINE_TOTAL_20260818 7\n';
  check(parseAllowlist(`${BT}a/b.js 7  # UNREVIEWED_BASELINE_20260818:7 存量未审查`).problems.length === 0,
    '棘轮:合法的存量行被判红');
  // 6.1 把存量行的数字 +1 当挡箭牌 —— 必须红(否则棘轮形同虚设)
  // 断言的是【哪道闸】响的,不只是「响了」:相邻几条闸抛的都是 problem,只数个数会互相接住
  check(parseAllowlist(`${BT}a/b.js 8  # UNREVIEWED_BASELINE_20260818:7 存量未审查`)
    .problems.some((p) => /申报 8 处 > 冻结 7 处/.test(p)),
  '棘轮:申报 8 > 冻结 7 没判红 —— 新增可以直接挂在存量帐上,棘轮漏光');
  // 6.2 申报小于冻结 = 审查掉了几处,合法
  check(parseAllowlist(`${BT}a/b.js 3  # UNREVIEWED_BASELINE_20260818:7 存量未审查`).problems.length === 0,
    '棘轮:申报变少(审查掉了)被误判红');
  // 6.3 存量标记缺冻结处数
  check(parseAllowlist('a/b.js 7  # UNREVIEWED_BASELINE_20260818 存量未审查').problems.length === 1,
    '棘轮:缺冻结处数的存量标记没判红');
  // 6.4 拆成两条存量行绕开「只许往下走」
  check(parseAllowlist(`${BT}a/b.js 7  # UNREVIEWED_BASELINE_20260818:7 x\na/b.js 1  # UNREVIEWED_BASELINE_20260818:1 y`)
    .problems.length >= 1, '棘轮:同一文件两条存量行没判红,拆行就能绕开');
  // 6.5 改冻结数必须撞上 BASELINE_TOTAL
  check(parseAllowlist(`${BT}a/b.js 8  # UNREVIEWED_BASELINE_20260818:8 x`)
    .problems.some((p) => /冻结处数之和 8 ≠ 头部声明的 BASELINE_TOTAL_20260818 7/.test(p)),
  '棘轮:冻结数被改大却没撞上 BASELINE_TOTAL —— 兜底那层没通电');
  // 6.6 有存量行却没有 BASELINE_TOTAL
  check(parseAllowlist('a/b.js 7  # UNREVIEWED_BASELINE_20260818:7 x').problems.length === 1,
    '棘轮:缺头部 BASELINE_TOTAL 却判绿');
  // 6.7 ★近名陷阱:理由里只是提到「基线 / baseline」的散文,不算存量标记
  const prose = parseAllowlist('a/b.js 2  # 基线期的轮询请求(baseline polling),失败下一轮自然重来');
  check(prose.problems.length === 0 && prose.entries.get('a/b.js').lines[0].baseline === null,
    '近名陷阱:理由里提到 baseline/基线 的散文被当成了存量标记');
  // 6.8 存量 + 真理由混排:总额相加,存量那条仍受冻结约束
  const mixed = parseAllowlist(`${BT}a/b.js 7  # UNREVIEWED_BASELINE_20260818:7 x\na/b.js 1  # 真理由:埋点失败对用户无意义`);
  check(mixed.problems.length === 0 && mixed.entries.get('a/b.js').allowed === 8,
    `棘轮:存量行 + 真理由行没能相加 → ${JSON.stringify(mixed.problems)}`);
  // 6.9 存量清零后留作收据的 0 行,不算失效登记
  const receipt = reconcile(new Map(), parseAllowlist(`${BT}a/b.js 0  # UNREVIEWED_BASELINE_20260818:7 已清零`).entries);
  check(receipt.stale.length === 0, '棘轮:清零后的收据行被当成失效登记,逼人删掉它 = 冻结数丢失');

  // ④ 核账三方向
  const byFile = new Map([['p/a.js', [{ line: 1 }, { line: 2 }]]]);
  const exact = reconcile(byFile, parseAllowlist('p/a.js 2  # 因为 X').entries);
  check(exact.over.length + exact.under.length + exact.unlisted.length + exact.stale.length === 0, '核账:数目正好却判红');
  const over = reconcile(byFile, parseAllowlist('p/a.js 1  # 因为 X').entries);
  check(over.over.length === 1, '核账:新增了静默调用却没判红 —— 这是本门禁的主目标,它坏了等于没有门禁');
  const under = reconcile(byFile, parseAllowlist('p/a.js 5  # 因为 X').entries);
  check(under.under.length === 1, '核账:册子虚额没被判红(虚额 = 给未来的静默调用留白条)');
  const unlisted = reconcile(byFile, new Map());
  check(unlisted.unlisted.length === 1, '核账:整个文件没登记却判绿');
  const stale = reconcile(new Map(), parseAllowlist('p/gone.js 1  # 因为 X').entries);
  check(stale.stale.length === 1, '核账:册子里挂着已不存在的登记却判绿');

  // ⑤ ★路径整串陷阱:册子里的 p/a.js 不许顺手放行 p/a.js 的近名兄弟
  const sibling = new Map([['p/list-v2/index.js', [{ line: 1 }]]]);
  const siblingRes = reconcile(sibling, parseAllowlist('p/list/index.js 1  # 因为 X').entries);
  check(siblingRes.unlisted.length === 1,
    '路径陷阱:册子里的 p/list/index.js 把 p/list-v2/index.js 也放行了(连字符假绿)');

  if (failures.length) {
    console.error('silentError 白名单门禁:自证失败');
    failures.forEach((f) => console.error(`  ✗ ${f}`));
    process.exit(1);
  }
  console.log('silentError 白名单门禁:自证通过'
    + '(注释/字符串/正则字面量都不算调用点、也都不许吃掉后面的真调用点且不带偏行号、近名标识符不算数、册子缺理由/缺处数都判红、'
    + '超额+虚额+未登记+失效登记四向都判红、路径整串比不放行近名兄弟;'
    + '外锚拿不到基线 ref 判红(含无远端仓库真负控)、存量棘轮:申报>冻结判红、拆两条存量行判红、缺冻结处数判红、改冻结数撞 BASELINE_TOTAL、'
    + '缺 BASELINE_TOTAL 判红、散文里的 baseline/基线 不算标记、存量+真理由可相加、清零收据不算失效、'
    + 'BASELINE_TOTAL 相对 master 只许减不许增、逐文件存量额度也只许减不许增)');
}

// ---------------------------------------------------------------------------
function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--selftest')) { runSelfTest(); return; }
  const root = repoRoot();
  const scan = scanRepo(root);
  const allow = readAllowlist(root);
  const master = masterAllowlist(root);
  const anchorProblem = baselineAnchorProblem(master.refResolved);
  if (anchorProblem) {
    allow.problems.push(anchorProblem);
  } else if (master.parsed === null) {
    console.log(`(${ALLOWLIST_FILE} 在 ${master.ref} 上还不存在,本次不校验「只减不增」两条外锚)`);
  } else {
    const masterTotal = master.parsed.baselineTotal.declared;
    const problem = checkBaselineTotalRatchet(allow.baselineTotal.declared, masterTotal);
    if (problem) allow.problems.push(problem);
    else console.log(`BASELINE_TOTAL 相对 ${master.ref}:${masterTotal} → `
      + `${allow.baselineTotal.declared === null ? 0 : allow.baselineTotal.declared}(只减不增 ✓)`);
    // 文件级额度棘轮(G3):与总数是两个层级,少哪条都有一条完整绕过路径
    const perFile = checkPerFileRatchet(allow.entries, master.parsed.entries);
    if (perFile.length) allow.problems.push(...perFile);
    else console.log(`逐文件存量额度相对 ${master.ref}:全部只减不增 ✓`);
  }
  const result = reconcile(scan.byFile, allow.entries);
  process.exit(report(scan, allow, result, { baselineOnly: argv.includes('--baseline') }));
}

if (require.main === module) main();

module.exports = {
  maskJsCommentsAndStrings, findSilentErrorSites, parseAllowlist, reconcile, scanRepo,
  checkBaselineTotalRatchet, checkPerFileRatchet, masterAllowlist,
  baselineAnchorProblem, resolveBaselineRef,
};
