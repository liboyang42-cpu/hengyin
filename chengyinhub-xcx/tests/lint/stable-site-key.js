'use strict';

const crypto = require('node:crypto');
const { parseJavaScript, maskJavaScriptComments } = require('../../scripts/lib/xcx-scan');
const cache = new Map();

function stableSiteKey(file, source, line) {
  let entry = cache.get(file);
  if (!entry || entry.source !== source) {
    const functions = [], calls = [];
    const canonical = node => JSON.stringify(node, (key, value) => ['start', 'end', 'loc', 'range', 'raw'].includes(key) ? undefined : value);
    function walk(node, owners = []) {
      if (!node || typeof node !== 'object') return;
      let next = owners;
      if (node.type === 'CallExpression') {
        if (['Page', 'Component', 'Behavior'].includes(node.callee?.name)) next = owners.concat(node.callee.name);
        else calls.push({ start: node.start, end: node.end, node });
      }
      if (/Function/.test(node.type || '')) {
        const name = node.id?.name || owners.at(-1) || '<callback>';
        functions.push({ start: node.start, end: node.end, owner: owners.concat(name).join('/') });
      }
      if (node.type === 'Property' && /Function/.test(node.value?.type || ''))
        next = owners.concat(node.key.name || String(node.key.value));
      if (node.type === 'VariableDeclarator' && node.id?.type === 'Identifier')
        next = owners.concat(node.id.name);
      for (const [key, value] of Object.entries(node)) {
        if (['start', 'end', 'loc', 'range'].includes(key)) continue;
        if (Array.isArray(value)) value.forEach(child => walk(child, next));
        else if (value && typeof value === 'object') walk(value, next);
      }
    }
    walk(parseJavaScript(source));
    let offset = 0;
    const code = maskJavaScriptComments(source).split('\n');
    const lines = source.split('\n').map((text, index) => {
      const position = offset + Math.max(0, text.search(/\S/));
      const item = { offset, text, position, code: code[index].trim() };
      offset += text.length + 1;
      return item;
    });
    const signatures = new Map();
    function signature(index) {
      if (signatures.has(index)) return signatures.get(index);
      const { offset, text, position, code } = lines[index];
      const owner = functions.filter(fn => fn.start <= position && position < fn.end)
        .sort((a, b) => (a.end - a.start) - (b.end - b.start))[0]?.owner || '<module>';
      const covering = calls.filter(call => call.start <= position && position < call.end)
        .sort((a, b) => (a.end - a.start) - (b.end - b.start));
      const call = covering[0] || calls.find(call => offset <= call.start && call.start < offset + text.length);
      if (call && !call.digest) call.digest = crypto.createHash('sha256').update(canonical(call.node)).digest('hex');
      const digest = crypto.createHash('sha256').update(owner + '\n' + (call?.digest || '') + '\n' + code).digest('hex');
      signatures.set(index, digest);
      return digest;
    }
    entry = { source, lines, signature, keys: new Map() };
    cache.set(file, entry);
  }
  if (!Number.isInteger(line) || line < 1 || line > entry.lines.length)
    throw new Error(`invalid finding location: ${file}:${line}`);
  if (!entry.keys.has(line)) {
    const digest = entry.signature(line - 1);
    let ordinal = 0;
    for (let index = 0; index < line; index++)
      if (entry.lines[index].code === entry.lines[line - 1].code && entry.signature(index) === digest) ordinal++;
    entry.keys.set(line, `${file}#site:${digest}:${ordinal}`);
  }
  return entry.keys.get(line);
}

module.exports = { stableSiteKey };
