'use strict';
// 2026-11-03 第九轮 m1 第 9 项:地址一点即开地图,全站只走这一个入口。
// 有 GCJ-02 坐标 → wx.openLocation;只有文字且调用方允许 → /api/map/geocode(后端缓存 + 本机缓存)拿坐标再开;
// 两样都没有 → canOpen 为假,页面就不给点。
const CACHE_KEY = 'cy_addr_geo_v1';
const CACHE_MAX = 60;
let memo = null;

function num(v) { const n = typeof v === 'string' && v.trim() === '' ? NaN : Number(v); return Number.isFinite(n) ? n : NaN; }

function coords(src) {
  if (!src || typeof src !== 'object') return null;
  const lat = num(src.lat != null ? src.lat : src.latitude);
  const lng = num(src.lng != null ? src.lng : (src.longitude != null ? src.longitude : src.lon));
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || (lat === 0 && lng === 0) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
  return { lat, lng };
}

function text(v) { return typeof v === 'string' ? v.trim() : ''; }

function addressOf(src) { return src ? text(src.address) || text(src.addressName) : ''; }

function load() {
  if (memo) return memo;
  try { const raw = wx.getStorageSync(CACHE_KEY); memo = raw && typeof raw === 'object' ? raw : {}; } catch (e) { memo = {}; }
  return memo;
}

function remember(address, c) {
  const cache = load();
  cache[address] = { lat: c.lat, lng: c.lng };
  const keys = Object.keys(cache);
  if (keys.length > CACHE_MAX) delete cache[keys[0]];
  try { wx.setStorageSync(CACHE_KEY, cache); } catch (e) { /* 存不下就只留内存 */ }
}

function cached(src) {
  const address = addressOf(src);
  return address ? coords(load()[address]) : null;
}

/** 能不能给点:有坐标,或(允许 geocode 且)有地址文字 */
function canOpen(src, opts) {
  if (coords(src)) return true;
  return !!(opts && opts.geocode && addressOf(src));
}

function show(src, c) {
  if (typeof wx === 'undefined' || typeof wx.openLocation !== 'function') return false;
  wx.openLocation({ latitude: c.lat, longitude: c.lng, name: text(src.name) || text(src.addressName) || addressOf(src), address: addressOf(src), scale: 17 });
  return true;
}

// 换不到坐标:不开地图,只给一句轻提示
function missed(resolve) {
  const toast = require('./toast.js');
  toast('暂时无法定位');
  resolve(null);
}

function geocode(src) {
  const address = addressOf(src);
  const app = typeof getApp === 'function' ? getApp() : null;
  if (!address || !app || typeof app.sendRequest !== 'function') return Promise.resolve(null);
  return new Promise((resolve) => {
    app.sendRequest({
      url: '/api/map/geocode', method: 'POST', data: { address, region: text(src.city) }, hideLoading: true, autoErrorToast: false,
      success: (res) => {
        const c = res && (res.code === 200 || res.code === '200') ? coords(res.data) : null;
        if (!c) { missed(resolve); return; }
        remember(address, c);
        resolve(c);
      },
      fail: () => missed(resolve),
      successStatusAbnormal: () => missed(resolve),
    });
  });
}

/** 打开地图。src: {lat,lng,name,address[,city]};opts.geocode=true 时允许拿文字地址换坐标。返回 Promise<boolean> */
function openAddress(src, opts) {
  const c = coords(src) || (opts && opts.geocode ? cached(src) : null);
  if (c) return Promise.resolve(show(src, c));
  if (!canOpen(src, opts)) return Promise.resolve(false);
  return geocode(src).then((g) => (g ? show(src, g) : false));
}

/** 页面 bindtap 用:data-lat / data-lng / data-name / data-address / data-geo="1" */
function openFromEvent(e) {
  const d = (e && e.currentTarget && e.currentTarget.dataset) || {};
  return openAddress(d, { geocode: d.geo === true || d.geo === 1 || d.geo === '1' });
}

function _reset() { memo = null; }

module.exports = { coords, canOpen, openAddress, openFromEvent, _reset };
