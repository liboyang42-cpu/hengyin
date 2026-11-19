/**
 * F10(FE-G13/14)自由探索作者路径:自己承接商家位置 / 位置承接进度 / 编辑器票价区填价直接发布。
 *
 * 纯函数(状态推导、校验、请求体)与请求包装分开:页面只调这里,测试直接覆盖纯函数。
 * 服务端:/api/topic/chapter/self-supply、/api/topic/fe/sale-readiness、/api/topic/fe/publish-for-sale。
 */
'use strict';

const FREE_EXPLORE = 2;
const RECRUITING = 1;
const PRICING = 2;

/** 编辑器票价区的状态。只对已发布的自由探索主题、招募中/待定价出现。 */
function pricingPanel(topic, readiness, tickets) {
  const lifecycle = Number(readiness && readiness.lifecycle != null ? readiness.lifecycle : topic && topic.lifecycle);
  const freeExplore = Number(topic && topic.productType) === FREE_EXPLORE;
  if (!freeExplore || (lifecycle !== RECRUITING && lifecycle !== PRICING)) return { show: false };
  const open = Math.max(0, Number(readiness && readiness.openSlots) || 0);
  const canFill = !!(readiness && readiness.canFillPrice);
  return {
    show: true,
    lifecycle,
    open,
    total: Math.max(0, Number(readiness && readiness.totalSlots) || 0),
    disabled: !canFill,
    // 招募中:置灰并写出还差几个位置;全部承接后可填价发布
    hint: canFill ? '' : '还有 ' + open + ' 个位置待承接',
    rows: (tickets || []).filter(t => t && t.id != null).map(t => ({
      id: t.id,
      name: t.name || '门票',
      price: t.price == null || t.price === '' ? '' : String(t.price),
    })),
  };
}

/** 票价输入:非负、最多两位小数。返回 null 表示合法,否则返回提示语。 */
function priceError(raw) {
  const text = String(raw == null ? '' : raw).trim();
  if (!text) return '每张票都要填写票价';
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return '票价最多两位小数，且不能为负';
  return null;
}

/** 发布请求体;任一票价不合法抛出带提示的 Error。 */
function publishBody(topicId, rows) {
  if (!topicId) throw new Error('缺少主题');
  if (!rows || !rows.length) throw new Error('请填写票价');
  const ticketPrices = {};
  rows.forEach(row => {
    const err = priceError(row.price);
    if (err) throw new Error(err);
    ticketPrices[String(row.id)] = String(row.price).trim();
  });
  return { topicId: topicId, ticketPrices: ticketPrices };
}

/** 自己承接权益卡请求体(复用 chapter-node-form 的 PERK 字段)。 */
function selfSupplyBody(topicId, chapterId, perk) {
  const p = perk || {};
  const name = String(p.name || '').trim();
  if (!name) throw new Error('请填写权益名称');
  const capacity = Number(p.capacity);
  if (!Number.isInteger(capacity) || capacity <= 0) throw new Error('请填写可接待人次');
  const validEnd = String(p.validEnd || '').trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(validEnd)) throw new Error('请选择权益有效期');
  return {
    topicId: topicId,
    chapterId: chapterId,
    perk: {
      name: name,
      redeemRule: String(p.redeemRule || '').trim(),
      validEnd: validEnd,
      capacity: capacity,
    },
  };
}

const toast = require('./toast.js');

/* 每个请求都写字面量 URL(UI 门禁按 /api 字面量核对后端 Mapping);失败一律在 fail 里 toast 原话,
   reject 出去的 Error 带 shown=true,页面只收尾状态,不再重复提示。 */
function fail(reject, res, fallback) {
  const msg = (res && res.msg) || fallback;
  toast(msg);
  const err = new Error(msg);
  err.shown = true;
  reject(err);
}

function loadReadiness(app, topicId) {
  return new Promise((resolve, reject) => {
    app.sendRequest({
      url: '/api/topic/fe/sale-readiness', method: 'GET', data: { topicId: topicId },
      hideLoading: true, silentError: true,
      success(res) { if (res && res.code == '200') resolve(res.data); else fail(reject, res, '承接进度加载失败'); },
      fail(res) { toast('承接进度加载失败'); fail(reject, res, '承接进度加载失败'); },
      successStatusAbnormal(res) { fail(reject, res, '承接进度加载失败'); },
    });
  });
}

function publishForSale(app, topicId, rows) {
  const body = publishBody(topicId, rows);
  return new Promise((resolve, reject) => {
    app.sendRequest({
      url: '/api/topic/fe/publish-for-sale', method: 'POST', data: JSON.stringify(body),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, silentError: true,
      success(res) { if (res && res.code == '200') resolve(res.data); else fail(reject, res, '发布失败，请重试'); },
      fail(res) { toast('发布失败，请重试'); fail(reject, res, '发布失败，请重试'); },
      successStatusAbnormal(res) { fail(reject, res, '发布失败，请重试'); },
    });
  });
}

function selfSupply(app, topicId, chapterId, perk) {
  const body = selfSupplyBody(topicId, chapterId, perk);
  return new Promise((resolve, reject) => {
    app.sendRequest({
      url: '/api/topic/chapter/self-supply', method: 'POST', data: JSON.stringify(body),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, silentError: true,
      success(res) { if (res && res.code == '200') resolve(res.data); else fail(reject, res, '承接失败，请重试'); },
      fail(res) { toast('承接失败，请重试'); fail(reject, res, '承接失败，请重试'); },
      successStatusAbnormal(res) { fail(reject, res, '承接失败，请重试'); },
    });
  });
}

function withdrawSelfSupply(app, topicId, offerId) {
  return new Promise((resolve, reject) => {
    app.sendRequest({
      url: '/api/topic/chapter/self-supply/withdraw', method: 'POST',
      data: JSON.stringify({ topicId: topicId, offerId: offerId }),
      header: { 'Content-Type': 'application/json' }, hideLoading: true, silentError: true,
      success(res) { if (res && res.code == '200') resolve(res.data); else fail(reject, res, '撤回失败，请重试'); },
      fail(res) { toast('撤回失败，请重试'); fail(reject, res, '撤回失败，请重试'); },
      successStatusAbnormal(res) { fail(reject, res, '撤回失败，请重试'); },
    });
  });
}

module.exports = {
  pricingPanel, priceError, publishBody, selfSupplyBody,
  loadReadiness, publishForSale, selfSupply, withdrawSelfSupply,
};
