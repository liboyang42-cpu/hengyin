(() => {
  window.__ls = [];
  const st = () => new Error().stack.split('\n').slice(2, 10).map(s => s.trim()).join(' | ');
  const a = getApp();
  for (const k of ['firstLogin', 'reLogin', 'ensureLogin', 'requireLogin', 'promptLogin']) {
    if (typeof a[k] === 'function') { const f = a[k]; a[k] = function () { window.__ls.push(k + ': ' + st()); return f.apply(this, arguments); }; }
  }
  const sm = a.getSessionManager();
  for (const k of Object.keys(sm)) if (typeof sm[k] === 'function') { const f = sm[k]; sm[k] = function () { window.__ls.push('sm.' + k + ': ' + st()); return f.apply(this, arguments); }; }
  const o = wx.login; wx.login = function () { window.__ls.push('wx.login'); return o.apply(this, arguments); };
})()
