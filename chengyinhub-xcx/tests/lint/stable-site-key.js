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
        else calls.push({ start: node.start, end: node.end, digest: crypto.createHash('sha256').update(canonical(node)).digest('hex') });
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
    const counts = new Map(), keys = [];
    let offset = 0;
    const code = maskJavaScriptComments(source).split('\n');
    source.split('\n').forEach((text, index) => {
      const position = offset + Math.max(0, text.search(/\S/));
      const owner = functions.filter(fn => fn.start <= position && position < fn.end)
        .sort((a, b) => (a.end - a.start) - (b.end - b.start))[0]?.owner || '<module>';
      const covering = calls.filter(call => call.start <= position && position < call.end)
        .sort((a, b) => (a.end - a.start) - (b.end - b.start));
      const call = covering[0] || calls.find(call => offset <= call.start && call.start < offset + text.length);
      const digest = crypto.createHash('sha256').update(owner + '\n' + (call?.digest || '') + '\n' + code[index].trim()).digest('hex');
      const ordinal = (counts.get(digest) || 0) + 1;
      counts.set(digest, ordinal);
      keys.push(`${file}#site:${digest}:${ordinal}`);
      offset += text.length + 1;
    });
    entry = { source, keys };
    cache.set(file, entry);
  }
  if (!Number.isInteger(line) || line < 1 || line > entry.keys.length)
    throw new Error(`invalid finding location: ${file}:${line}`);
  return entry.keys[line - 1];
}

module.exports = { stableSiteKey };
