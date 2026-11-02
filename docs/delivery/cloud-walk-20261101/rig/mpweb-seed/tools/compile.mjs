// Build: compile bundled mini-program sources (mp.js) into a browser bundle without runtime eval.
// WXML -> AST with precompiled expression functions; JS/WXS -> wrapped module functions.
import fs from 'fs';
const [,, inFile, outFile, patchFile] = process.argv;
const src = fs.readFileSync(inFile, 'utf8');
const MP = JSON.parse(src.slice('window.__MP='.length, src.lastIndexOf(';')));
const patches = patchFile ? JSON.parse(fs.readFileSync(patchFile, 'utf8')) : {};

// ---------- expression rewrite: member access -> optional chaining ----------
function rewriteExpr(e) {
  let out = '', i = 0, prev = ''; // prev token kind: id | close | op | str | num
  const n = e.length;
  while (i < n) {
    const c = e[i];
    if (c === '"' || c === "'" || c === '`') {
      let j = i + 1; while (j < n && e[j] !== c) { if (e[j] === '\\') j++; j++; }
      out += e.slice(i, j + 1); i = j + 1; prev = 'str'; continue;
    }
    if (/[A-Za-z_$]/.test(c)) {
      let j = i; while (j < n && /[\w$]/.test(e[j])) j++;
      const w = e.slice(i, j); out += w; i = j;
      prev = ['typeof', 'in', 'instanceof', 'new', 'return', 'void', 'delete'].includes(w) ? 'op' : 'id'; continue;
    }
    if (/[0-9]/.test(c) || (c === '.' && /[0-9]/.test(e[i + 1] || '') && prev !== 'id' && prev !== 'close')) {
      let j = i; while (j < n && /[0-9.eE]/.test(e[j])) j++;
      out += e.slice(i, j); i = j; prev = 'num'; continue;
    }
    if (c === '.' && e[i + 1] === '.' && e[i + 2] === '.') { out += '...'; i += 3; prev = 'op'; continue; }
    if (c === '?' && e[i + 1] === '.') { out += '?.'; i += 2; prev = 'op'; continue; }
    if (c === '.' && (prev === 'id' || prev === 'close' || prev === 'str')) { out += '?.'; i++; prev = 'op'; continue; }
    if (c === '[' && (prev === 'id' || prev === 'close')) { out += '?.['; i++; prev = 'op'; continue; }
    if (c === ')' || c === ']') { out += c; i++; prev = 'close'; continue; }
    if (/\s/.test(c)) { out += c; i++; continue; }
    out += c; i++; prev = 'op';
  }
  return out;
}
const fns = []; const fnIndex = new Map();
function fn(body) {
  if (fnIndex.has(body)) return fnIndex.get(body);
  fnIndex.set(body, fns.length); fns.push(body); return fns.length - 1;
}
// attr value -> spec: string literal | {f:n} (raw single mustache) | {f:n,s:1} (string interpolation)
function splitMustache(v) {
  const parts = []; let i = 0;
  while (i < v.length) {
    const a = v.indexOf('{{', i);
    if (a < 0) { parts.push({ lit: v.slice(i) }); break; }
    if (a > i) parts.push({ lit: v.slice(i, a) });
    // find matching }} not inside quotes
    let j = a + 2, q = null;
    while (j < v.length) { const ch = v[j]; if (q) { if (ch === '\\') j++; else if (ch === q) q = null; } else if (ch === '"' || ch === "'") q = ch; else if (ch === '}' && v[j + 1] === '}' && v[j+2] !== '}') break; j++; }
    parts.push({ expr: v.slice(a + 2, j).trim() }); i = j + 2;
  }
  return parts;
}
function compileValue(v, asObject) {
  if (v == null) return true;
  if (v.indexOf('{{') < 0) return v;
  const parts = splitMustache(v);
  const ex = (x) => { const r = rewriteExpr(x); return asObject ? '({' + r + '})' : '(' + r + ')'; };
  if (parts.length === 1 && parts[0].expr != null) return { f: fn('return ' + ex(parts[0].expr) + ';') };
  const body = 'return ' + parts.map((p) => p.lit != null ? JSON.stringify(p.lit) : '__s(' + ex(p.expr) + ')').join('+') + ';';
  return { f: fn(body), s: 1 };
}
// ---------- WXML parser ----------
let nodeId = 0;
function parseWxml(text, file) {
  const root = { tag: '#root', c: [] }; const stack = [root]; let i = 0; const n = text.length;
  const top = () => stack[stack.length - 1];
  while (i < n) {
    if (text.startsWith('<!--', i)) { const j = text.indexOf('-->', i); i = j < 0 ? n : j + 3; continue; }
    if (text[i] === '<' && text[i + 1] === '/') {
      const j = text.indexOf('>', i); const tag = text.slice(i + 2, j).trim();
      // pop to matching
      for (let k = stack.length - 1; k > 0; k--) if (stack[k].tag === tag) { stack.length = k; break; }
      i = j + 1; continue;
    }
    if (text[i] === '<' && /[a-zA-Z]/.test(text[i + 1] || '')) {
      let j = i + 1; while (j < n && /[\w\-:.]/.test(text[j])) j++;
      const tag = text.slice(i + 1, j); const attrs = [];
      // attributes
      while (j < n) {
        while (j < n && /\s/.test(text[j])) j++;
        if (text[j] === '/' && text[j + 1] === '>') { j += 2; break; }
        if (text[j] === '>') { j++; break; }
        let k = j; while (k < n && !/[\s=>\/]/.test(text[k])) k++;
        if (k === j) { j++; continue; }
        const name = text.slice(j, k); j = k;
        while (j < n && /\s/.test(text[j])) j++;
        if (text[j] === '=') {
          j++; while (j < n && /\s/.test(text[j])) j++;
          const q = text[j];
          if (q === '"' || q === "'") {
            let m = j + 1, depth = 0;
            while (m < n) { if (text.startsWith('{{', m)) { depth++; m += 2; continue; } if (depth && text.startsWith('}}', m)) { depth--; m += 2; continue; } if (!depth && text[m] === q) break; m++; }
            attrs.push([name, text.slice(j + 1, m)]); j = m + 1;
          } else { let m = j; while (m < n && !/[\s>]/.test(text[m])) m++; attrs.push([name, text.slice(j, m)]); j = m; }
        } else attrs.push([name, null]);
      }
      const selfClose = text[j - 2] === '/';
      const el = { tag, attrs, c: [], id: ++nodeId };
      top().c.push(el);
      if (tag === 'wxs' && !selfClose) {
        const e = text.indexOf('</wxs>', j); el.raw = text.slice(j, e); i = e + 6; continue;
      }
      if (!selfClose && !['input', 'image', 'img', 'br', 'import', 'include', 'icon'].includes(tag)) stack.push(el);
      else if (['include', 'import'].includes(tag) && !selfClose) { /* tolerate */ const e = text.indexOf('</' + tag + '>', j); if (e >= 0 && text.slice(j, e).trim() === '') j = e + tag.length + 3; }
      i = j; continue;
    }
    let j = i, dep = 0;
    while (j < n) { if (text.startsWith('{{', j)) { dep++; j += 2; continue; } if (dep && text.startsWith('}}', j)) { dep--; j += 2; continue; } if (!dep && text[j] === '<' && j > i) break; j++; }
    // skip '<' that is part of text like "a < b"? assume templates escape properly
    const t = text.slice(i, j); i = j;
    if (t.trim()) top().c.push({ text: t, id: ++nodeId });
  }
  return root;
}
// transform raw parsed tree into compiled node specs
function compileNode(el, file, ctx) {
  if (el.text != null) {
    let t = el.text.replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&').replace(/&quot;/g, '"');
    return { x: compileValue(t), i: el.id };
  }
  const a = {}; for (const [k, v] of el.attrs) a[k] = v;
  const node = { g: el.tag, i: el.id };
  if (el.tag === 'wxs') {
    node.m = a.module; if (a.src) node.src = resolve(file, a.src, '.wxs'); else { node.code = el.raw; node.inlineId = file + '#' + a.module; }
    return node;
  }
  if (el.tag === 'include' || el.tag === 'import') { node.src = resolve(file, a.src, '.wxml'); return node; }
  if (el.tag === 'template' && a.name) { node.name = a.name; node.c = el.c.map((c) => compileNode(c, file)).filter(Boolean); return node; }
  if (el.tag === 'template' && a.is != null) { node.is = compileValue(a.is); if (a.data != null) node.data = compileValue(a.data, true); return node; }
  const props = {};
  for (const [k, v] of el.attrs) {
    if (k === 'wx:if') node.if = compileValue(v);
    else if (k === 'wx:elif') node.elif = compileValue(v);
    else if (k === 'wx:else') node.else = 1;
    else if (k === 'wx:for' || k === 'wx:for-items') node.for = compileValue(v);
    else if (k === 'wx:for-item') node.fi = v;
    else if (k === 'wx:for-index') node.fx = v;
    else if (k === 'wx:key') node.key = v;
    else props[k] = compileValue(v);
  }
  node.p = props;
  node.c = el.c.map((c) => compileNode(c, file)).filter(Boolean);
  return node;
}
function resolve(file, p, ext) {
  let base;
  if (p.startsWith('/')) base = p.slice(1);
  else { const dir = file.split('/').slice(0, -1); for (const seg of p.split('/')) { if (seg === '..') dir.pop(); else if (seg !== '.') dir.push(seg); } base = dir.join('/'); }
  if (!base.endsWith(ext)) base += ext; return base;
}
// ---------- emit ----------
let out = '';
const templates = {};
for (const [path, text] of Object.entries(MP.files)) {
  if (!path.endsWith('.wxml')) continue;
  const tree = parseWxml(text, path);
  templates[path] = tree.c.map((c) => compileNode(c, path)).filter(Boolean);
}
function serialize(v) {
  if (v === null || v === undefined) return 'null';
  if (Array.isArray(v)) return '[' + v.map(serialize).join(',') + ']';
  if (typeof v === 'object') return '{' + Object.entries(v).map(([k, x]) => JSON.stringify(k) + ':' + serialize(x)).join(',') + '}';
  return JSON.stringify(v);
}
out += 'var __S=function(v){return v==null?"":(typeof v==="object"?JSON.stringify(v):String(v))};\n';
out += 'var __F=[' + fns.map((b) => 'function(__sc){with(__sc){' + b.replace(/__s\(/g, '__S(') + '}}').join(',\n') + '];\n';
out += 'var __T=' + serialize(templates) + ';\n';
// modules
out += 'var __M={};\n';
for (const [path, code0] of Object.entries(MP.files)) {
  if (!path.endsWith('.js') && !path.endsWith('.wxs')) continue;
  let code = code0;
  for (const p of (patches[path] || [])) {
    if (!code.includes(p.find)) { console.error('PATCH MISS', path, p.find.slice(0, 60)); process.exit(1); }
    code = code.replace(p.find, p.replace);
  }
  if (path.endsWith('.wxs')) out += '__M[' + JSON.stringify(path) + ']=function(module,exports,require,getRegExp,getDate){' + code + '\n};\n';
  else out += '__M[' + JSON.stringify(path) + ']=function(module,exports,require,Component,Page,Behavior,getApp,getCurrentPages,wx){' + code + '\n};\n';
}
// inline wxs
const inl = {};
(function walk(list, file) { for (const n of list || []) { if (n.g === 'wxs' && n.code != null) inl[n.inlineId] = n.code; walk(n.c, file); } });
for (const [path, list] of Object.entries(templates)) (function walk(l) { for (const n of l) { if (n.g === 'wxs' && n.code != null) inl[n.inlineId] = n.code; if (n.c) walk(n.c); } })(list);
for (const [id, code] of Object.entries(inl)) out += '__M[' + JSON.stringify(id) + ']=function(module,exports,require,getRegExp,getDate){' + code + '\n};\n';
out += 'var __C=' + JSON.stringify(MP.comps) + ';\nvar __CSS=' + JSON.stringify(MP.css) + ';\nvar __APPCSS=' + JSON.stringify(MP.appcss) + ';\n';
fs.writeFileSync(outFile, out);
console.log('fns', fns.length, 'templates', Object.keys(templates).length, 'bytes', out.length);
