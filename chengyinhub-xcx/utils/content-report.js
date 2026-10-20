'use strict';
// FOLLOWUP-A-1:主题 / 模板广场 / 个人玩法的举报入口。流程照抄动态详情 reportSquare:
// 同一句确认 → 同一份平台举报原因(社区 capabilities 的 reporting.reasons)→ 同一个「选择举报原因」弹层 → 同样的 toast。
// 这三个接口(#383)只收表单 id + reason(原因文字),不走社区 v1 的幂等举报单。
const modal = require('./modal.js');
const toast = require('./toast.js');
const reportSheet = require('./community-report-sheet.js');

const KINDS = ['topic', 'template', 'personalTemplate'];

// 网络失败:结束在途,页面还在才如实提示。
function networkFailed(callbacks) {
  callbacks.done();
  if (callbacks.current()) toast('网络异常，请重试');
}

// 三个举报接口各写一次字面量 URL(UI-GATE U1 只认字面量路径;U5 要求 fail 在同一请求里可见)。
function submitReport(app, kind, data, callbacks) {
  const success = callbacks.success;
  if (kind === 'topic') return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/topic/report', method: 'POST', data: data, success: success, fail: function () { networkFailed(callbacks); } });
  if (kind === 'template') return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/template/report', method: 'POST', data: data, success: success, fail: function () { networkFailed(callbacks); } });
  return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/template/personal-report', method: 'POST', data: data, success: success, fail: function () { networkFailed(callbacks); } });
}

function reasonLabels(res) {
  const reporting = res && Number(res.code) === 200 && res.data && res.data.reporting;
  if (!reporting || !Array.isArray(reporting.reasons)) return [];
  return reporting.reasons
    .map(function (r) { return r && typeof r.label === 'string' ? r.label.trim() : ''; })
    .filter(Boolean);
}

// current():页面仍在、登录身份没换,才继续下一步或弹结果。
function report(page, app, kind, id) {
  if (KINDS.indexOf(kind) < 0 || !id || page._contentReporting) return false;
  const owner = String(app.getUserID() || '');
  const current = function () { return !page._unloaded && !page._disposed && owner === String(app.getUserID() || ''); };
  const done = function () { page._contentReporting = false; };
  page._contentReporting = true;
  modal.show({
    title: '举报内容',
    content: '确认举报这条内容？举报后将提交平台审核。',
    confirmText: '举报',
    success: function (res) {
      if (!res.confirm || !current()) { done(); return; }
      app.sendRequest({
        hideLoading: true, silentError: true, url: '/api/v1/community/capabilities', method: 'GET', data: {},
        success: function (resp) {
          if (!current()) { done(); return; }
          const labels = reasonLabels(resp);
          if (!labels.length) { done(); toast('举报原因暂不可用'); return; }
          reportSheet.open(page, labels, { aborted: false }).then(function (index) {
            if (!current()) { done(); return; }
            submitReport(app, kind, { id: id, reason: labels[index] }, {
              done: done, current: current,
              success: function (r) {
                done();
                if (!current()) return;
                if (r && r.code == '200') toast((r && r.msg) || '已提交举报');
                else toast((r && r.msg) || '举报失败');
              }
            });
          }, function (error) {
            done();
            if (current() && error && Number(error.code) !== 499) toast(error.msg || '举报失败');
          });
        },
        fail: function () { done(); if (current()) toast('网络异常，请重试'); }
      });
    },
    fail: done
  });
  return true;
}

module.exports = { report };
