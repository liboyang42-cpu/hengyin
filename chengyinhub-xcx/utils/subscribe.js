// 微信订阅消息授权 helper(业务提醒用)。
//
// 模板 ID 已配置(微信公众平台「功能 → 订阅消息」):
//   signupSuccess  报名成功通知
//   activityStart  活动开始提醒
//   refund         退款到账通知
//   refundRejected 退款被拒通知
// 后端在对应业务节点用 WxSubscribeMsgService 下发。
//
// 注:teamFormed(成团成功通知)已随成团下线(2026-07-15)移除 —— 后端 notifyTeamFormed 已删、
// application.yml 的 tplTeamFormed 已撤。它此前还占着 requestSubscribeMessage 仅有的 3 个坑之一,
// 把真正会下发的 refund 挤出前 3 → 退款到账通知一直没被授权过。
const TEMPLATES = {
  signupSuccess: 'NfXW_QNj6Poy4GQj51heJ-LGJiljtJBkAqFlsnWdPuA',
  activityStart: 'noH01EDe1vqpAFvWSwXMhyqSw1a4YHbP92CG7ehti54',
  refund:        'hrxJ8GaoNVNNjLBMOn2ZntnNzjxpBZvNqTcAoKjr1Xg',
  refundRejected:'fqsssX0TRhDO88wNbrAGVdhT4kMIUNK_qXXe0HNoqCk',
  // 合作/定价四类(F3-2)
  coopInvited:   'aC5iPgiluAkGT1LkQHT7-qVk_MmxnO9H-updKXyG6KM',  // 合作被邀约(发给受邀方)
  coopStatus:    'Mc6LPLxOGodPlVbeUeyp09pc7wQoOL354MmUwedKr2c',  // 邀约状态变更(发给发起方)
  recruit:       'pVihLGRWi3uA4wGvA-Hv-NsuOGvtj1Ts3kV1GmU56Dw',  // 新活动招募(发给发布者)
  coopSettle:    'Z10x9KV0jciDbFGTS6javEjqz0abOi-ik6x_0e_pt-o',  // 订单结算到账(发给商家)
  clubInterest:  '',  // 俱乐部承接意向(发给模板主题创建者)——模板待公众平台创建,填入 ID 前跳过并仅告警一次
  roamRecall:    '',  // 漫游完成召回——公众平台模板创建并与后端 tplRoamRecall 同步后再填
};

const STATUS = Object.freeze({
  ACCEPTED: 'accepted',
  PARTIAL: 'partial',
  REJECTED: 'rejected',
  NOT_CONFIGURED: 'not_configured',
  UNSUPPORTED: 'unsupported',
  FAILED: 'failed',
});
const warnedUnconfigured = Object.create(null);

// 未知业务 key 不得命中 Object.prototype 的继承成员。
function configuredTemplateId(key) {
  return Object.prototype.hasOwnProperty.call(TEMPLATES, key) ? TEMPLATES[key] : undefined;
}

// 按调用方顺序选择前 3 个不同的已配置模板；重复 key/别名不占授权名额。
function selectTemplates(keys) {
  var ids = [];
  (keys || []).forEach(function (key) {
    var id = configuredTemplateId(key);
    if (id && ids.indexOf(id) < 0 && ids.length < 3) ids.push(id);
  });
  var requestedKeys = (keys || []).filter(function (key, index, all) {
    return all.indexOf(key) === index && ids.indexOf(configuredTemplateId(key)) >= 0;
  });
  return { ids: ids, keys: requestedKeys };
}

function resolveTemplateIds(keys) {
  return selectTemplates(keys).ids;
}

function unconfiguredKeys(keys) {
  return (keys || []).filter(function (key, index, all) {
    return !configuredTemplateId(key) && all.indexOf(key) === index;
  });
}

function warnUnconfiguredOnce(keys) {
  var fresh = unconfiguredKeys(keys).filter(function (key) { return !warnedUnconfigured[key]; });
  if (!fresh.length) return;
  fresh.forEach(function (key) { warnedUnconfigured[key] = true; });
  if (typeof console !== 'undefined' && console.warn) {
    console.warn('[subscribe] 未配置模板，已跳过');
  }
}

function result(status, requestedKeys, acceptedKeys, rejectedKeys, missingKeys) {
  return {
    status: status,
    requestedKeys: requestedKeys,
    acceptedKeys: acceptedKeys,
    rejectedKeys: rejectedKeys,
    unconfiguredKeys: missingKeys,
  };
}

// 请求订阅授权。keys 如 ['signupSuccess','refund']。
// 始终 resolve 结构化结果，不阻塞报名/支付主流程。必须在用户点击手势里调用。
function request(keys) {
  return new Promise(function (resolve) {
    var inputKeys = keys || [];
    var missingKeys = unconfiguredKeys(inputKeys);
    warnUnconfiguredOnce(inputKeys);
    var selected = selectTemplates(inputKeys);
    var ids = selected.ids;
    var requestedKeys = selected.keys;
    var requestedIds = requestedKeys.map(function (key) { return configuredTemplateId(key); });
    if (!ids.length) {
      resolve(result(STATUS.NOT_CONFIGURED, [], [], [], missingKeys));
      return;
    }
    if (typeof wx === 'undefined' || typeof wx.requestSubscribeMessage !== 'function') {
      resolve(result(STATUS.UNSUPPORTED, requestedKeys, [], [], missingKeys));
      return;
    }
    try {
      wx.requestSubscribeMessage({
        tmplIds: ids,
        success: function (res) {
          var acceptedKeys = [];
          var rejectedKeys = [];
          requestedKeys.forEach(function (key, index) {
            if (res && res[requestedIds[index]] === 'accept') acceptedKeys.push(key);
            else rejectedKeys.push(key);
          });
          var status = acceptedKeys.length === requestedKeys.length
            ? STATUS.ACCEPTED
            : (acceptedKeys.length ? STATUS.PARTIAL : STATUS.REJECTED);
          resolve(result(status, requestedKeys, acceptedKeys, rejectedKeys, missingKeys));
        },
        fail: function () {
          resolve(result(STATUS.FAILED, requestedKeys, [], [], missingKeys));
        }
      });
    } catch (e) {
      resolve(result(STATUS.FAILED, requestedKeys, [], [], missingKeys));
    }
  });
}

module.exports = { request: request, STATUS: STATUS, TEMPLATES: TEMPLATES, resolveTemplateIds: resolveTemplateIds };
