'use strict';
// The input is the selected /theme/card public projection, never a route, owner or runtime state.
const WIDTH = 750, MAX_HEIGHT = 2400, SCALE = 1, MAX_TEXT = 6000, MAX_ROWS = 52;
const MAX_CANVAS_SIDE = 2400, MAX_CANVAS_PIXELS = 1800000;
const RENDER_TIMEOUT = 15000, NATIVE_TIMEOUT = 30000, MAX_COVER_PIXELS = 8000000;
// Same print-card palette as the existing play-story export, not a change to the dark play UI.
const COLOR = Object.freeze({ page: '#FFFFFF', text: '#111111', muted: '#5E6470', line: '#8B8F99', accent: '#4B46F5' });
function failure(code, message) { const error = new Error(message); error.code = code; return error; }
function cancelled() { return failure('CANCELLED', '结果选择已变化'); }
function publicSnapshot(card, textOnly) {
  if (!card || typeof card.success !== 'boolean') throw failure('PUBLIC_CARD', '请先读取公开结果');
  let left = MAX_TEXT, clipped = false;
  const text = (value, limit) => {
    if (typeof value !== 'string') return '';
    const n = Math.max(0, Math.min(limit, left));
    const points = Array.from(value.slice(0, n * 2 + 2).replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, ''));
    if (points.length > n || value.length > n * 2 + 2) clipped = true;
    const result = points.slice(0, n).join(''); left -= Array.from(result).length; return result;
  };
  const array = (value, max, map) => {
    if (!Array.isArray(value)) return [];
    if (value.length > max) clipped = true;
    return value.slice(0, max).map(map).filter(Boolean);
  };
  const numeric = value => typeof value === 'number' && Number.isFinite(value);
  const snapshot = { success: card.success, endedAtText: text(card.endedAtText, 48), title: text(card.title, 100),
    scene: text(card.scene, 80), summary: text(card.summary, 1800), cover: '', stats: [], items: [], medals: [], story: [], unlocks: [], next: [] };
  if (!snapshot.endedAtText) throw failure('PUBLIC_CARD', '公开结果缺少结束时间');
  if (!textOnly && typeof card.cover === 'string') {
    if (card.cover.length > 2048) throw failure('COVER', '封面地址过长，可生成文字版');
    snapshot.cover = card.cover;
  }
  snapshot.stats = array(card.stats, 4, row => row && ['hp', 'luck', 'actions', 'rescues'].includes(row.key) && numeric(row.value)
    ? text(row.label, 40) + '：' + String(row.value) : '');
  const content = row => row && typeof row.name === 'string' ? text(row.name, 80) + (numeric(row.quantity) ? ' × ' + row.quantity : '') : '';
  snapshot.medals = array(card.medals, 12, content); snapshot.items = array(card.items, 12, content);
  snapshot.story = array(card.epilogues, 8, row => row ? [text(row.name, 40), text(row.line, 300)].filter(Boolean).join('：') : '')
    .concat(array(card.exhibits, 8, row => text(row, 300)));
  if (card.unlocks && Number.isSafeInteger(card.unlocks.total) && card.unlocks.total >= 0) {
    snapshot.unlocks.push('当前已知结局 / 共 ' + card.unlocks.total + ' 个');
    snapshot.unlocks.push(...array(card.unlocks.known, 12, row => text(row && row.title, 80)));
  }
  if (card.nextScene && typeof card.nextScene.name === 'string') {
    snapshot.next = ['当前发布安排：' + text(card.nextScene.name, 80), '查询时点：' + (text(card.nextScene.asOfText, 48) || '当前') + '，不代表本局已发生内容'];
  }
  snapshot.textOnly = !!textOnly; snapshot.clipped = clipped; return snapshot;
}
function wrapText(text, width, measure) {
  const lines = []; let current = '';
  for (const char of Array.from(text)) {
    if (char === '\n') { lines.push(current); current = ''; continue; }
    const next = current + char, measured = measure(next);
    if (!Number.isFinite(measured) || measured < 0) throw failure('CANVAS', '画布文字测量不可用');
    if (current && measured > width) { lines.push(current); current = char; } else current = next;
  }
  if (current || !lines.length) lines.push(current);
  return lines;
}
function buildLayout(snapshot, context, hasCover) {
  if (!context || typeof context.measureText !== 'function') throw failure('CANVAS', '当前环境不能绘制结果图');
  const commands = []; let y = 70, clipped = snapshot.clipped;
  const add = (value, size, color, maxLines) => {
    if (!value) return;
    context.font = '400 ' + size + 'px sans-serif';
    const lines = wrapText(value, WIDTH - 112, text => context.measureText(text).width);
    if (lines.length > maxLines) clipped = true;
    for (const line of lines.slice(0, maxLines)) {
      const lineHeight = Math.ceil(size * 1.5);
      if (commands.length >= MAX_ROWS || y + lineHeight > MAX_HEIGHT - 150) { clipped = true; break; }
      commands.push({ text: line, x: 56, y, size, color }); y += lineHeight;
    }
  };
  add('城瘾 · 公开结果摘要', 20, COLOR.muted, 1); y += 16;
  add(snapshot.title, 40, COLOR.text, 3); add(snapshot.scene, 24, COLOR.muted, 2);
  add((snapshot.success ? '已完成' : '本局已结束') + ' · ' + snapshot.endedAtText + '（北京时间）', 20, COLOR.muted, 2);
  let coverBox = null;
  if (hasCover) { y += 20; coverBox = { x: 56, y, width: WIDTH - 112, height: 300 }; y += 328; }
  add(snapshot.summary, 28, COLOR.text, 10);
  const section = (label, values) => {
    if (!values.length) return; y += 20; add(label, 24, COLOR.accent, 1);
    values.forEach(value => add(value, 24, COLOR.text, 4));
  };
  section('本局数值', snapshot.stats); section('本局徽章', snapshot.medals); section('本局物品', snapshot.items);
  section('故事尾声', snapshot.story); section('结局记录', snapshot.unlocks); section('下一幕安排', snapshot.next);
  const height = Math.max(700, Math.min(MAX_HEIGHT, y + 130));
  return { width: WIDTH, height, commands, coverBox, clipped,
    footer: clipped ? '篇幅有限，更多公开详情请回结果页查看' : '城瘾 · 走进城市的瘾' };
}
function removeExport(api, path) {
  if (typeof path !== 'string' || !path || path.length > 4096) return;
  try { const fs = api.getFileSystemManager && api.getFileSystemManager(); if (fs && fs.unlink) fs.unlink({ filePath: path, fail() {} }); } catch (error) { /* native temp-file GC is the fallback */ }
}
function exportLease(api, path, details) {
  let disposed = false, pins = 0, removed = false, timeout = null;
  const remove = () => { if (removed) return; removed = true; if (timeout) clearTimeout(timeout); removeExport(api, path); };
  return Object.assign({ path,
    pin() { if (disposed || removed) throw cancelled(); pins++; let done = false; return () => { if (done) return; done = true; pins--; if (disposed && !pins) remove(); }; },
    dispose() {
      disposed = true;
      if (!pins) remove(); else if (!timeout) timeout = setTimeout(remove, NATIVE_TIMEOUT);
    },
  }, details);
}
function callbackOperation(start, timeoutMs, onLateSuccess) {
  return new Promise((resolve, reject) => {
    let finished = '';
    const timer = setTimeout(() => { finished = 'timeout'; reject(failure('TIMEOUT', '生成或系统操作超时，请重试')); }, timeoutMs);
    const success = value => { if (finished) { if (finished !== 'success' && onLateSuccess) onLateSuccess(value); return; } finished = 'success'; clearTimeout(timer); resolve(value); };
    const fail = value => { if (finished) return; finished = 'failure'; clearTimeout(timer); reject(value instanceof Error ? value : failure('NATIVE', (value && value.errMsg) || '系统操作失败')); };
    try { start(success, fail); } catch (error) { fail(error); }
  });
}
async function render(api, snapshot, isCurrent) {
  const deadline = Date.now() + RENDER_TIMEOUT;
  const alive = () => { if (!isCurrent()) throw cancelled(); };
  const remaining = () => { const ms = deadline - Date.now(); if (ms <= 0) throw failure('TIMEOUT', '结果图生成超时'); return ms; };
  alive();
  if (!api || typeof api.createOffscreenCanvas !== 'function' || typeof api.canvasToTempFilePath !== 'function')
    throw failure('CANVAS', '当前微信版本暂不支持结果图');
  // Each explicit request owns one canvas; it never depends on hidden WXML nodes.
  const canvas = api.createOffscreenCanvas({ type: '2d', width: 1, height: 1 });
  if (!canvas || typeof canvas.getContext !== 'function') throw failure('CANVAS', '当前微信版本暂不支持结果图');
  try {
    let cover = null;
    if (snapshot.cover) {
      if (!/^https:\/\//.test(snapshot.cover) || !api.getImageInfo || !canvas.createImage) throw failure('COVER', '封面暂不可用，可重试或生成文字版');
      try {
        // getImageInfo may return a shared native cache path. It is not our file to unlink.
        const info = await callbackOperation((ok, fail) => api.getImageInfo({ src: snapshot.cover, success: ok, fail }), Math.min(8000, remaining()));
        alive();
        if (!info || !['png', 'jpeg', 'jpg'].includes(info.type) || !Number.isFinite(info.width) || !Number.isFinite(info.height)
            || info.width <= 0 || info.height <= 0 || info.width > 4096 || info.height > 4096 || info.width * info.height > MAX_COVER_PIXELS
            || typeof info.path !== 'string' || !info.path || info.path.length > 4096)
          throw failure('COVER', '封面尺寸或格式暂不支持，可生成文字版');
        cover = await callbackOperation((ok, fail) => {
          const image = canvas.createImage(); image.onload = () => ok({ image, width: info.width, height: info.height }); image.onerror = fail; image.src = info.path;
        }, Math.min(8000, remaining())); alive();
      } catch (error) { if (error.code === 'CANCELLED') throw error; throw failure('COVER', '封面未能加载，可重试或生成文字版'); }
    }
    const context = canvas.getContext && canvas.getContext('2d');
    const layout = buildLayout(snapshot, context, !!cover); alive();
    const width = layout.width * SCALE, height = layout.height * SCALE;
    if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width <= 0 || height <= 0
        || width > MAX_CANVAS_SIDE || height > MAX_CANVAS_SIDE || width * height > MAX_CANVAS_PIXELS)
      throw failure('CANVAS', '结果图尺寸超限，请重试');
    canvas.width = width; canvas.height = height;
    const g = canvas.getContext('2d'); g.scale(SCALE, SCALE); g.textAlign = 'left'; g.textBaseline = 'alphabetic';
    g.fillStyle = COLOR.page; g.fillRect(0, 0, layout.width, layout.height);
    if (cover && layout.coverBox) {
      const box = layout.coverBox, scale = Math.min(box.width / cover.width, box.height / cover.height);
      g.drawImage(cover.image, box.x + (box.width - cover.width * scale) / 2, box.y + (box.height - cover.height * scale) / 2, cover.width * scale, cover.height * scale);
    }
    layout.commands.forEach(command => { g.font = '400 ' + command.size + 'px sans-serif'; g.fillStyle = command.color; g.fillText(command.text, command.x, command.y); });
    g.font = '400 20px sans-serif'; g.fillStyle = COLOR.muted; g.fillText(layout.footer, 56, layout.height - 44);
    const exported = await callbackOperation((ok, fail) => api.canvasToTempFilePath({ canvas, destWidth: canvas.width, destHeight: canvas.height, fileType: 'png', success: ok, fail }), remaining(), late => removeExport(api, late && late.tempFilePath));
    if (!exported || typeof exported.tempFilePath !== 'string' || !exported.tempFilePath || exported.tempFilePath.length > 4096) throw failure('EXPORT', '结果图生成失败，请重试');
    const lease = exportLease(api, exported.tempFilePath, { width: canvas.width, height: canvas.height, clipped: layout.clipped, textOnly: snapshot.textOnly });
    if (!isCurrent()) { lease.dispose(); throw cancelled(); }
    return lease;
  } finally {
    // Release the bounded bitmap even when cover decode/export or identity checks fail.
    try { canvas.width = 1; canvas.height = 1; } catch (error) { /* native GC is the fallback */ }
  }
}
module.exports = { publicSnapshot, buildLayout, wrapText, render, exportLease, callbackOperation,
  WIDTH, MAX_HEIGHT, SCALE, MAX_CANVAS_SIDE, MAX_CANVAS_PIXELS, MAX_TEXT, MAX_ROWS, RENDER_TIMEOUT, NATIVE_TIMEOUT };
