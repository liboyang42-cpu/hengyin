'use strict';
// FOLLOWUP-A-1:主题 / 模板广场 / 个人玩法的举报入口。流程照抄动态详情 reportSquare:
// 同一句确认 → 同一份平台举报原因(社区 capabilities 的 reporting.reasons)→ 同一个「选择举报原因」弹层 → 同样的 toast。
// 这三个接口(#383)只收表单 id + reason(原因文字),不走社区 v1 的幂等举报单。
const modal = require('./modal.js');
const toast = require('./toast.js');
const reportSheet = require('./community-report-sheet.js');

// 网络失败:结束在途,页面还在才如实提示。
function networkFailed(callbacks) {
  callbacks.done();
  if (callbacks.current()) toast('网络异常，请重试');
}

function reasonLabels(res) {
  const reporting = res && Number(res.code) === 200 && res.data && res.data.reporting;
  if (!reporting || !Array.isArray(reporting.reasons)) return [];
  return reporting.reasons
    .map(function (r) { return r && typeof r.label === 'string' ? r.label.trim() : ''; })
    .filter(Boolean);
}

// current():页面仍在、登录身份没换,才继续下一步或弹结果。submit 发本入口唯一的举报写请求。
function run(page, app, id, submit) {
  if (!id || page._contentReporting) return false;
  // 2026-10-24 游客写前登录:举报写库,游客先弹「登录后继续」,登录后恢复这次举报。
  if (app.ensureLoginFor && !app.ensureLoginFor('report', function () { run(page, app, id, submit); }, { owner: page, target: () => [id, page.data && page.data.id, page.data && page.data.clubId] })) return false;
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
            submit({ id: id, reason: labels[index] }, {
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

// 三个入口各自只发一个字面量写接口(UI-GATE U1 只认字面量路径;U5 要求 fail 在同一请求里可见;
// 按钮台账按 handler 认写接口,不能让一个入口同时挂上三个举报 API)。
function reportTopic(page, app, id) {
  return run(page, app, id, function (data, callbacks) {
    return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/topic/report', method: 'POST', data: data, success: callbacks.success, fail: function () { networkFailed(callbacks); } });
  });
}
function reportTemplate(page, app, id) {
  return run(page, app, id, function (data, callbacks) {
    return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/template/report', method: 'POST', data: data, success: callbacks.success, fail: function () { networkFailed(callbacks); } });
  });
}
function reportPersonalTemplate(page, app, id) {
  return run(page, app, id, function (data, callbacks) {
    return app.sendRequest({ hideLoading: true, silentError: true, url: '/api/template/personal-report', method: 'POST', data: data, success: callbacks.success, fail: function () { networkFailed(callbacks); } });
  });
}

module.exports = { reportTopic, reportTemplate, reportPersonalTemplate };
