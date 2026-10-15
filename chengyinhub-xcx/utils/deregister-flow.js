const modal = require('./modal.js');
const app = getApp();
const { sendUiStateRequest } = require('./ui-state-request.js');
const { bizFailureMessage } = require('./response-shape.js');
const { formatRefundDeadline } = require('./order-status.js');
const { toTimestamp } = require('./datetime.js');

const STATUS_AFTER_APPLICATION = ['NORMAL', 'CANCELLED', 'FAILED'];

function requestId() {
  return 'deregister-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
}

function isOk(res) {
  return !!(res && (res.code === 200 || res.code === '200'));
}

function isRecord(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

function blockerList(value) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string' || !item.trim())) return null;
  return value.map((item) => item.trim());
}

// 1-39:后端 Date 无 @JsonFormat,Spring Boot 默认下发 ISO 串("2026-09-24T10:30:00.000+08:00");
// ISO/epoch 一律格式化成中国时区,解析不了的字符串才原样显示。
function executeAfterText(value) {
  const text = formatRefundDeadline(value);
  return text || (typeof value === 'string' ? value : '');
}

// F-R2S-07:冷静期满到每日批次执行之间仍可撤销(服务端与批次行锁互斥);页面按真实 executeAfter 切换说明文案。
function executeAfterPassed(value, now) {
  if (value === null || value === undefined || value === '') return false;
  const ts = toTimestamp(value);
  return Number.isFinite(ts) && ts > 0 && ts <= (now == null ? Date.now() : now);
}

function pendingPatch(data) {
  return { status: 'PENDING', executeAfter: executeAfterText(data.executeAfter),
    coolingOffEnded: executeAfterPassed(data.executeAfter), verificationMethod: 'UNAVAILABLE', canApply: false };
}

function initialData() {
  return {
    status: 'LOADING',
    statusError: '',
    verificationMethod: 'UNAVAILABLE',
    blockers: [],
    executeAfter: '',
    coolingOffEnded: false,
    phone: '',
    phoneMasked: '',
    smscode: '',
    confirmed: false,
    canApply: false,
    showNoticeSheet: false,
    smsSending: false,
    smsSent: false,
    smsError: '',
    submitting: false,
    applyError: '',
    canceling: false,
    cancelError: '',
  };
}

const methods = {
  _readCurrent(token, memberId, res, statusCode) {
    if (token !== this._statusToken) return false;
    const nextId = String(app.getUserID && app.getUserID() || '');
    if (memberId !== nextId || res && res.reason === 'sessionChanged') {
      this.dispose();
      this.setData(Object.assign(initialData(), { status: Number(nextId) > 0 ? 'LOADING' : 'LOGIN_REQUIRED' }));
      if (Number(nextId) > 0 && !this._hidden && !this._detached && !this._unloaded) this.loadStatus();
      return false;
    }
    return !this._readAuthOutcome(res, statusCode);
  },
  _readAuthOutcome(res, statusCode) {
    const code = Number(statusCode || res && (res.statusCode || res.code));
    if (code !== 401 && code !== 403) return false;
    this.dispose();
    this.setData({ status: code === 401 ? 'LOGIN_REQUIRED' : 'DENIED', blockers: [],
      phone: '', phoneMasked: '', smscode: '', confirmed: false, canApply: false, verificationMethod: 'UNAVAILABLE',
      smsSending: false, submitting: false, canceling: false });
    return true;
  },

  loginToRead() {
    if (this.data.status === 'DENIED') { this.loadStatus(); return; }
    if (this._readLoginPending) return;
    const epoch = this._readLoginEpoch || 0;
    this._readLoginPending = true;
    let login;
    try {
      login = Number(app.getUserID && app.getUserID()) > 0 && typeof app.reLogin === 'function'
        ? app.reLogin() : typeof app.firstLogin === 'function' ? app.firstLogin() : null;
    } catch (error) { this._readLoginPending = false; return; }
    Promise.resolve(login).then(result => {
      if (epoch !== (this._readLoginEpoch || 0)) return;
      this._readLoginPending = false;
      if (!(result && result.ok === false) && Number(app.getUserID && app.getUserID()) > 0) this.loadStatus();
    }).catch(() => { if (epoch === (this._readLoginEpoch || 0)) this._readLoginPending = false; });
  },
  _statusFailure(message) {
    this.setData({ status: 'ERROR', statusError: message || '没能确认注销条件，请稍后重试。', blockers: [] });
  },

  loadStatus() {
    const memberId = String(app.getUserID && app.getUserID() || '');
    if (!(Number(memberId) > 0)) { this._readAuthOutcome({ code: 401 }); return; }
    const token = (this._statusToken || 0) + 1;
    this._statusToken = token;
    this.setData({ status: 'LOADING', statusError: '', blockers: [], cancelError: '', verificationMethod: 'UNAVAILABLE', canApply: false });
    sendUiStateRequest(app, '/api/user/deregister/status', {
      method: 'GET',
      success: (res) => {
        if (!this._readCurrent(token, memberId, res)) return;
        const data = isOk(res) && isRecord(res.data) ? res.data : null;
        if (!data || typeof data.status !== 'string') {
          this._statusFailure(bizFailureMessage(res, '注销状态暂时无法读取，请重试。'));
          return;
        }
        if (data.status === 'PENDING') {
          this.setData(Object.assign(pendingPatch(data), { statusError: '' }));
          return;
        }
        if (STATUS_AFTER_APPLICATION.includes(data.status)) {
          this.loadPrecheck(token);
          return;
        }
        this._statusFailure('账号注销状态待确认，请稍后重试。');
      },
      fail: (res, code) => { if (this._readCurrent(token, memberId, res, code)) this._statusFailure('网络异常，注销状态没有读取成功。'); },
      successStatusAbnormal: (res, code) => { if (this._readCurrent(token, memberId, res, code)) this._statusFailure('服务暂时不可用，请稍后重试。'); },
    });
  },

  loadPrecheck(existingToken) {
    const memberId = String(app.getUserID && app.getUserID() || '');
    if (!(Number(memberId) > 0)) { this._readAuthOutcome({ code: 401 }); return; }
    const token = existingToken == null ? (this._statusToken || 0) + 1 : existingToken;
    this._statusToken = token;
    if (existingToken == null) this.setData({ status: 'LOADING', statusError: '', blockers: [] });
    sendUiStateRequest(app, '/api/user/deregister/precheck', {
      method: 'POST',
      success: (res) => {
        if (!this._readCurrent(token, memberId, res)) return;
        const data = isOk(res) && isRecord(res.data) ? res.data : null;
        const blockers = data ? blockerList(data.blockers) : null;
        if (!data || (data.status !== 'ELIGIBLE' && data.status !== 'BLOCKED') || blockers === null
          || (data.status === 'ELIGIBLE' && blockers.length) || (data.status === 'BLOCKED' && !blockers.length)) {
          this._statusFailure(bizFailureMessage(res, '注销条件返回异常，请稍后重试。'));
          return;
        }
        const verificationMethod = data.verificationMethod == null ? 'SMS'
          : ['SMS', 'WECHAT', 'UNAVAILABLE'].includes(data.verificationMethod) ? data.verificationMethod : 'UNAVAILABLE';
        this.setData({ status: data.status, blockers, verificationMethod: data.status === 'ELIGIBLE' ? verificationMethod : 'UNAVAILABLE',
          statusError: '', canceling: false, cancelError: '', phone: '', phoneMasked: '', smscode: '', confirmed: false, canApply: false });
      },
      fail: (res, code) => { if (this._readCurrent(token, memberId, res, code)) this._statusFailure('网络异常，注销条件没有核验成功。'); },
      successStatusAbnormal: (res, code) => { if (this._readCurrent(token, memberId, res, code)) this._statusFailure('服务暂时不可用，请稍后重试。'); },
    });
  },

  goCancellationNotice() { this.setData({ showNoticeSheet: true }); },
  closeNoticeSheet() { this.setData({ showNoticeSheet: false }); },
  inputCode(event) {
    this.setData({ smscode: event.detail.value || '', applyError: '' }, () => this.refreshApplyState());
  },
  toggleConfirmed(event) {
    this.setData({ confirmed: !!event.detail.value.length, applyError: '' }, () => this.refreshApplyState());
  },

  refreshApplyState() {
    const canApply = this.data.status === 'ELIGIBLE' && this.data.confirmed
      && (this.data.verificationMethod === 'WECHAT' || this.data.verificationMethod === 'SMS' && !!(this.data.smscode || '').trim());
    if (canApply !== this.data.canApply) this.setData({ canApply });
  },

  sendSms() {
    if (this.data.smsSending || this.data.status !== 'ELIGIBLE' || this.data.verificationMethod !== 'SMS') return;
    const token = (this._smsToken || 0) + 1;
    this._smsToken = token;
    this.setData({ smsSending: true, smsSent: false, smsError: '' });
    sendUiStateRequest(app, '/api/user/info', {
      method: 'POST', data: { member_id: app.getUserID() },
      success: (res) => {
        if (token !== this._smsToken) return;
        const phone = isOk(res) && isRecord(res.data) && typeof res.data.phone === 'string' ? res.data.phone.trim() : '';
        if (!phone) {
          this.setData({ smsSending: false, smsSent: false, smsError: bizFailureMessage(res, '请先绑定手机号后再获取验证码。') });
          return;
        }
        sendUiStateRequest(app, '/api/sms/send', {
          method: 'POST', data: { phone },
          success: (smsRes) => {
            if (token !== this._smsToken) return;
            if (isOk(smsRes)) {
              this.setData({ phone, phoneMasked: phone.slice(-4), smsSending: false, smsSent: true, smsError: '' });
              return;
            }
            this.setData({ smsSending: false, smsSent: false, smsError: (smsRes && smsRes.msg) || '验证码发送失败，请重试。' });
          },
          fail: () => { if (token === this._smsToken) this.setData({ smsSending: false, smsSent: false, smsError: '网络异常，验证码没有发送。' }); },
          successStatusAbnormal: () => { if (token === this._smsToken) this.setData({ smsSending: false, smsSent: false, smsError: '服务暂时不可用，验证码没有发送。' }); },
        });
      },
      fail: () => { if (token === this._smsToken) this.setData({ smsSending: false, smsSent: false, smsError: '网络异常，手机号没有读取成功。' }); },
      successStatusAbnormal: () => { if (token === this._smsToken) this.setData({ smsSending: false, smsSent: false, smsError: '服务暂时不可用，手机号没有读取成功。' }); },
    });
  },

  apply() {
    if (this.data.submitting || this.data.status !== 'ELIGIBLE' || this._hidden || this._detached || this._unloaded) return;
    const smscode = (this.data.smscode || '').trim();
    if (!this.data.confirmed) {
      this.setData({ applyError: '请先确认已阅读并理解注销后果。' });
      return;
    }
    if (!['SMS', 'WECHAT'].includes(this.data.verificationMethod)) {
      this.setData({ applyError: '身份验证暂不可用，请重新核验。' });
      return;
    }
    if (this.data.verificationMethod === 'SMS' && !smscode) {
      this.setData({ applyError: '请输入短信验证码。' });
      return;
    }
    const token = (this._applyToken || 0) + 1;
    this._applyToken = token;
    const owner = [app.getUserID(), (app.globalData || {}).authorization || ''].join('|');
    const current = () => token === this._applyToken && !this._hidden && !this._detached && !this._unloaded
      && owner === [app.getUserID(), (app.globalData || {}).authorization || ''].join('|');
    const useWechat = this.data.verificationMethod === 'WECHAT';
    this.setData({ submitting: true, applyError: '' });
    Promise.resolve().then(() => current() ? app.recordConsent({
      docType: 'account_cancellation_notice', scene: 'account_cancel', eventType: 'AGREE',
    }) : null).then(() => {
      if (!current()) return null;
      if (!useWechat) return { smscode };
      return new Promise((resolve, reject) => {
        let settled = false;
        const finish = (value, error) => {
          if (settled) return;
          settled = true; clearTimeout(timer);
          if (error) reject(error); else resolve(value);
        };
        const timer = setTimeout(() => finish(null, new Error('WECHAT_CONFIRMATION_FAILED')), 15000);
        try {
          wx.login({ timeout: 10000,
            success: res => typeof res.code === 'string' && res.code.trim()
              ? finish({ wxcode: res.code }) : finish(null, new Error('WECHAT_CONFIRMATION_FAILED')),
            fail: () => finish(null, new Error('WECHAT_CONFIRMATION_FAILED')) });
        } catch (error) { finish(null, new Error('WECHAT_CONFIRMATION_FAILED')); }
      });
    }).then(verification => {
      if (!current() || !verification) return;
      sendUiStateRequest(app, '/api/user/deregister/apply', {
        method: 'POST',
        data: Object.assign({ requestId: requestId() }, verification),
        success: (res) => {
          if (!current()) return;
          const data = isOk(res) && isRecord(res.data) ? res.data : null;
          if (data && data.status === 'PENDING') {
            this.setData(Object.assign(pendingPatch(data), { submitting: false, applyError: '' }));
            return;
          }
          const blockers = data && data.status === 'BLOCKED' ? blockerList(data.blockers) : null;
          if (blockers && blockers.length) {
            this.setData({ status: 'BLOCKED', blockers, submitting: false, applyError: '' });
            return;
          }
          this.setData({ submitting: false, applyError: bizFailureMessage(res, '注销申请没有提交成功，请重试。') });
          this._reconcileApply();
        },
        // F-R2S-07:回包丢失时申请可能已在服务端生效(冷静期已开始、会话已失效),先回查真实状态。
        fail: () => { if (current()) { this.setData({ submitting: false, applyError: '网络异常，验证码和确认状态已保留，请重试。' }); this._reconcileApply(); } },
        successStatusAbnormal: () => { if (current()) { this.setData({ submitting: false, applyError: '服务暂时不可用，验证码和确认状态已保留，请重试。' }); this._reconcileApply(); } },
      });
    }).catch(error => {
      if (current()) this.setData({ submitting: false, applyError: error && error.message === 'WECHAT_CONFIRMATION_FAILED'
        ? '微信身份确认失败，请重试。' : '同意记录失败，请重试。' });
    });
  },

  // 申请结果未知时只读回查 /status:确认为 PENDING 才切到冷静期卡片;其余情况保留当前表单与错误提示,
  // 不走 loadStatus/loadPrecheck(它们会清空已输入的验证码与确认勾选)。
  _reconcileApply() {
    const memberId = String(app.getUserID && app.getUserID() || '');
    if (!(Number(memberId) > 0) || this._hidden || this._detached || this._unloaded) return;
    const token = (this._reconcileToken || 0) + 1;
    this._reconcileToken = token;
    const applyToken = this._applyToken;
    sendUiStateRequest(app, '/api/user/deregister/status', { method: 'GET',
      success: (res) => {
        if (token !== this._reconcileToken || applyToken !== this._applyToken || this._hidden || this._detached || this._unloaded) return;
        if (memberId !== String(app.getUserID && app.getUserID() || '')) return;
        const data = isOk(res) && isRecord(res.data) ? res.data : null;
        if (data && data.status === 'PENDING' && this.data.status === 'ELIGIBLE') {
          this.setData(Object.assign(pendingPatch(data), { submitting: false, applyError: '', smscode: '', confirmed: false }));
        }
      },
      fail: () => {},
      successStatusAbnormal: () => {},
    });
  },

  // F-R2S-07:阻断项处理完后可在原页重新核验,不必退出再进。
  recheck() {
    if (this.data.status !== 'BLOCKED') return;
    this.loadStatus();
  },

  cancel() {
    if (this.data.canceling) return;
    const token = (this._cancelToken || 0) + 1;
    this._cancelToken = token;
    this.setData({ canceling: true, cancelError: '' });
    modal.show({
      dangerKey: 'account.deregister.cancel',   // 三段式文案在 utils/danger-actions.js
      success: (result) => {
        if (token !== this._cancelToken) return;
        if (!result.confirm) {
          this.setData({ canceling: false });
          return;
        }
        sendUiStateRequest(app, '/api/user/deregister/cancel', {
          method: 'POST',
          success: (res) => {
            if (token !== this._cancelToken) return;
            const data = isOk(res) && isRecord(res.data) ? res.data : null;
            if (data && data.status === 'CANCELLED') {
              this.setData({ canceling: false, cancelError: '' });
              this.loadPrecheck();
              return;
            }
            this.setData({ canceling: false, cancelError: bizFailureMessage(res, '撤销申请没有完成，请重试。') });
          },
          fail: () => { if (token === this._cancelToken) this.setData({ canceling: false, cancelError: '网络异常，撤销申请没有完成。' }); },
          successStatusAbnormal: () => { if (token === this._cancelToken) this.setData({ canceling: false, cancelError: '服务暂时不可用，撤销申请没有完成。' }); },
        });
      },
      fail: () => { if (token === this._cancelToken) this.setData({ canceling: false, cancelError: '撤销确认没有打开，请重试。' }); },
    });
  },

  dispose() {
    this._readLoginEpoch = (this._readLoginEpoch || 0) + 1;
    this._readLoginPending = false;
    this._statusToken = (this._statusToken || 0) + 1;
    this._smsToken = (this._smsToken || 0) + 1;
    this._applyToken = (this._applyToken || 0) + 1;
    this._cancelToken = (this._cancelToken || 0) + 1;
    this._reconcileToken = (this._reconcileToken || 0) + 1;
  },
};

module.exports = { initialData, methods, executeAfterPassed };
