'use strict'

const modal = require('../../../../utils/modal.js'); const pageMotion = require('../../../../utils/page-motion.js');
const toast = require('../../../../utils/toast.js');
const app = getApp()
const { isRecord, isRecordList, bizFailureMessage } = require('../../../../utils/response-shape.js')
const { exactSessionId } = require('../../../../utils/roam-recovery.js')

const { validationMethodLabel } = require('../../../../utils/validation-method-labels.js')
const mediaPreview = require('../../../../utils/media-preview.js')
const VALIDATION_METHODS = [1, 2, 3, 4, 5]

function nonEmptyString(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

function positiveId(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  const id = exactSessionId(value)
  if (!id) return null
  // Keep v1 pending records compatible while preserving Long strings exactly.
  return Number.isSafeInteger(Number(id)) ? Number(id) : id
}

let attemptSequence = 0
const PREWRITE_REJECTIONS = new Set(['请先登录', '参数不合规', '据点未开放或已下线', '节点未上线', '节点未设置坐标', '缺少定位', '离据点太远,请到店后再完成', '答案不正确', '请上传拍照打卡照片', '照片地址不合法,请重新上传', '请扫描本店的到店打卡码', '这张码不属于本店', '照片未上传成功或不属于当前账号，请重新拍摄上传', '照片校验暂不可用，请稍后重试'])
function ownerId() {
  try { return positiveId(app.getUserID()) } catch (_) { return null }
}
function pendingContext(poi) {
  const owner = ownerId(); const id = positiveId(poi)
  return owner && id ? { ownerId: owner, poiId: id, key: 'cy_roam_poi_checkin_pending:v1:' + owner + ':' + id } : null
}
function readPending(context) {
  const value = wx.getStorageSync(context.key)
  if (value == null || value === '') return null
  if (!isRecord(value) || value.v !== 1 || value.ownerId !== context.ownerId || value.poiId !== context.poiId || typeof value.attemptId !== 'string' || !value.attemptId) throw new Error('invalid pending')
  return value
}
function clearPending(context, record) {
  const current = readPending(context)
  if (!current || current.attemptId !== record.attemptId) return false
  wx.removeStorageSync(context.key + ':photo')
  if (wx.getStorageSync(context.key + ':photo')) throw new Error('photo cache not removed')
  wx.removeStorageSync(context.key)
  if (readPending(context)) throw new Error('pending not removed')
  return true
}

function coordinate(value, max) {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= max ? value : null
}

Component({
  properties: {
    poiId: { type: String, value: '' },
    merchantId: { type: String, value: '' },
  },
  data: {
    photoIndex: 0, photoCredit: '', creditOpen: false, ratingOptions: [1, 2, 3, 4, 5],
    challengePhoto: '', reviews: [], reviewsError: false, reviewText: '', reviewRating: 0, reviewSubmitting: false,
    choiceSheetShow: false,
    choiceSheetItems: [],
    state: 'loading',
    errorText: '节点信息加载失败',
    merchant: null,
    merchantError: '',
    node: null,
    gallery: [],
    tags: [],
    featured: null,
    hasLocation: false,
    checkinPending: false,
    reverifyChecking: false,
    reverifyReady: false,
    reverifyStarted: false,
    pendingStatusText: '',
    completing: false,
    favoriteBusy: false,
    favoriteError: '',
    interactionState: 'idle',
    interactionTitle: '',
    interactionText: '',
    interactionActionText: '',
  },
  observers: { 'poiId, merchantId'() { this._load() } },
  lifetimes: {
    attached() { this._load() },
    detached() {
      this._closed = true
      this._abortPoiPhoto()
      this._interactionToken = (this._interactionToken || 0) + 1
      this._loadToken = (this._loadToken || 0) + 1
      this._completionToken = (this._completionToken || 0) + 1
      this._favoriteToken = (this._favoriteToken || 0) + 1
    },
  },
  pageLifetimes: { show() {
    if (this._closed) return
    const photo = this._poiPhoto
    // 原生相机/裁剪返回时上传可能尚未结束；成功回调也可能先于 show 续开答题或定位。
    if (this._loadedOwner === ownerId() && this.data.checkinPending && this._reverification && photo
        && photo.ownerId === ownerId() && photo.poiId === positiveId(this.data.poiId) && photo.loadToken === this._loadToken
        && photo.operation && !photo.operation.isAborted() && (!photo.operation.isFinished() || photo.delivered)) return
    if (this._loadedOwner !== ownerId() || this.data.checkinPending) this._load()
  } },
  methods: {
    _abortPoiPhoto() {
      const photo = this._poiPhoto
      this._poiPhoto = null
      if (photo && photo.operation) photo.operation.abort()
    },
    _pendingNotice(storageError) {
      this._abortPoiPhoto()
      this._pendingCompletion = null
      this._reverification = null
      if (!storageError) {
        try {
          const context = pendingContext(this.data.poiId)
          const stored = context && readPending(context)
          if (stored && !stored.uncertain) {
            // 只记未知状态，不保存坐标/答案/照片/扫码内容；迟到的拒绝不得清旧未知。
            wx.setStorageSync(context.key, { v: 1, ownerId: stored.ownerId, poiId: stored.poiId, attemptId: stored.attemptId, uncertain: true })
            if (!readPending(context).uncertain) throw new Error('unknown state not saved')
          }
        } catch (_) { storageError = true }
      }
      this.setData({ completing: false, checkinPending: true, reverifyChecking: false, reverifyReady: false, reverifyStarted: false, challengePhoto: '', choiceSheetShow: false })
      this._setInteractionRecovery('unknown', '打卡结果待确认', '可查询当前结果，或重新到现场按当前任务核验；旧记录保留到确认完成。', 'query', '查询当前状态')
      if (storageError) this.setData({ interactionTitle: '打卡记录暂时无法读取或保存', interactionText: '本地存储异常，暂不提交打卡。请恢复存储后查询当前状态。' })
    },
    reverifyOnSite() {
      if (this._closed || this.data.reverifyChecking || this.data.reverifyReady || this.data.completing) return
      this._load(true)
    },
    _currentReverification() {
      const current = this._reverification
      if (!current || this._closed || current.ownerId !== ownerId() || current.poiId !== positiveId(this.data.poiId)
          || current.loadToken !== this._loadToken) return null
      try {
        const stored = readPending(current)
        if (stored && stored.attemptId === current.attemptId) return current
        this._load()
      } catch (_) { this._pendingNotice(true) }
      return null
    },
    onChoiceSheetSelect(e) {
      if (!this.data.choiceSheetShow || this._choiceSheetToken !== this._interactionToken || this._closed) return
      const value = (this._choiceSheetValues || [])[e.detail.index]
      this.setData({ choiceSheetShow: false })
      if (value) this.completeNode(value, this.data.challengePhoto || '')
    },

    /* 取消 = 原 showActionSheet 的 fail 分支:同一个失败通道,别让它静默消失 */
    onChoiceSheetCancel() {
      if (!this.data.choiceSheetShow || this._choiceSheetToken !== this._interactionToken || this._closed) return
      this.setData({ choiceSheetShow: false })
      this._handleInteractionFailure('choice', { errMsg: 'showActionSheet:fail cancel' }, 'start')
    },

    _load(reverify) {
      reverify = reverify === true
      this._abortPoiPhoto()
      this._closed = false
      this._reverification = null
      this._interactionToken = (this._interactionToken || 0) + 1
      const token = (this._loadToken || 0) + 1
      this._loadToken = token
      this._completionToken = (this._completionToken || 0) + 1
      this._favoriteToken = (this._favoriteToken || 0) + 1
      this._pendingCompletion = null
      const poiId = this.data.poiId
      const context = pendingContext(poiId)
      this._loadedOwner = ownerId()
      const loadedOwner = this._loadedOwner
      let pending = null; let storageError = false
      try { if (context) pending = readPending(context) } catch (_) { storageError = true }
      const merchantId = this.data.merchantId
      const resetState = (state) => this.setData({
        photoIndex: 0, photoCredit: '', creditOpen: false,
        challengePhoto: '', reviews: [], reviewsError: false, reviewText: '', reviewRating: 0, reviewSubmitting: false,
        checkinPending: false,
        reverifyChecking: false, reverifyReady: false, reverifyStarted: false, pendingStatusText: '',
        errorText: '',
        merchant: null,
        merchantError: '',
        choiceSheetShow: false,
        node: null,
        gallery: [],
        tags: [],
        featured: null,
        hasLocation: false,
        completing: false,
        favoriteBusy: false,
        favoriteError: '',
        interactionState: 'idle',
        interactionTitle: '',
        interactionText: '',
        interactionActionText: '',
        state,
      })
      if (!poiId && !merchantId) { resetState('empty'); return }
      if (!poiId && merchantId) {
        resetState('loading')
        this._loadMerchant(merchantId, token)
        return
      }
      resetState('loading')
      this.setData({ checkinPending: !!pending || storageError })
      if (pending || storageError) this._pendingNotice(storageError)
      this.setData({ reverifyChecking: reverify && !!pending && !storageError })
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/city/nodes/' + this.data.poiId,
        method: 'GET',
        // SecurityConfig 未匿名放行 city/nodes，使用 request-client 默认认证。
        success: (nodeRes) => {
          if (token !== this._loadToken || this._closed || loadedOwner !== ownerId() || poiId !== this.data.poiId) return
          this.setData({ reverifyChecking: false })
          const rawNode = nodeRes && (nodeRes.code === '200' || nodeRes.code === 200) && isRecord(nodeRes.data) ? nodeRes.data : null
          const nodeId = rawNode && positiveId(rawNode.poiId == null ? rawNode.id : rawNode.poiId)
          if (!nodeId || nodeId !== positiveId(poiId) || (rawNode.id != null && positiveId(rawNode.id) !== nodeId)) {
            this._nodeLoadFailed(nodeRes)
            return
          }
          const method = typeof rawNode.validationMethod === 'number' ? rawNode.validationMethod : null
          const node = {
            ...rawNode,
            vmLabel: validationMethodLabel(method, '—'),
            canInteract: rawNode.status === 1 && VALIDATION_METHODS.includes(method),
          }
          if (this.data.state !== 'ready') pageMotion.enter(this, 'pmPage'); this.setData({ node, state: 'ready', hasLocation: !!this._coordinates(node, null) })
          if (pending && typeof node.completed === 'boolean' && typeof node.needRedeem === 'boolean' && node.completed !== node.needRedeem) {
            try {
              if (clearPending(context, pending)) {
                this.setData({ checkinPending: false })
                this._clearInteractionRecovery()
                this.triggerEvent('completed', { poiId, needRedeem: node.needRedeem, recovered: true })
                toast(node.needRedeem ? '已确认当前待核销' : '已确认当前已完成')
              }
            } catch (_) { this._pendingNotice(true) }
          } else if (pending) {
            this.setData({ pendingStatusText: node.completed === false && node.needRedeem === false
              ? '当前尚未显示完成或待核销，不能据此判断上次请求未执行。'
              : '当前结果还无法确认，请继续查询。' })
            if (reverify && node.completed === false && node.needRedeem === false && node.canInteract) {
              try {
                const stored = readPending(context)
                if (stored && stored.attemptId === pending.attemptId) {
                  // 新核验只用本次照片和输入；旧照片缓存不能代替当前任务凭证。
                  wx.removeStorageSync(context.key + ':photo')
                  if (wx.getStorageSync(context.key + ':photo')) throw new Error('photo cache not removed')
                  this._reverification = { ...context, attemptId: pending.attemptId, loadToken: token }
                  this.setData({ reverifyReady: true, challengePhoto: '', choiceSheetItems: [] })
                }
              } catch (_) { this._pendingNotice(true) }
            }
          }
          if (!node.merchantId) {
            let images = []
            try { images = JSON.parse(node.galleryJson || '[]') } catch (_) {}
            this.setData({ gallery: (Array.isArray(images) ? images : []).filter(u => typeof u === 'string').map(u => app.getImgUrl(u)),
              tags: String(node.tags || '').split(/[,，、]/).filter(Boolean) })
          }
          try {
            const saved = !pending && !storageError && context && wx.getStorageSync(context.key + ':photo')
            if (saved && saved.templateId === node.templateId && typeof saved.url === 'string')
              this.setData({ challengePhoto: saved.url })
          } catch (_) {}
          if (node.merchantId && !this.data.checkinPending) this._loadMerchant(node.merchantId, token)
          this.loadReviews()
          this.loadPhotoCredit(node)
        },
        fail: () => { if (!this._closed && token === this._loadToken && loadedOwner === ownerId()) this.setData({ state: 'error', reverifyChecking: false, errorText: '网络异常，请重试' }) },
        successStatusAbnormal: (res) => { if (!this._closed && token === this._loadToken && loadedOwner === ownerId()) this._nodeLoadFailed(res) },
      })
    },
    _nodeLoadFailed(res) {
      // 200 但数据形状不对时 msg 是「操作成功」,不能当失败原因(2026-09-17 拍板)。
      this.setData({ state: res && (res.code === 410 || res.code === '410') ? 'unavailable' : 'error', reverifyChecking: false, errorText: bizFailureMessage(res, '这次没有读到节点信息，请稍后重试。') })
    },
    _loadMerchant(id, loadToken) {
      const token = loadToken == null ? (this._loadToken || 0) + 1 : loadToken
      if (loadToken == null) this._loadToken = token
      const merchantToken = (this._merchantRequestToken || 0) + 1
      this._merchantRequestToken = merchantToken
      const isCurrent = () => token === this._loadToken && merchantToken === this._merchantRequestToken
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/merchant/public-detail',
        method: 'POST',
        data: JSON.stringify({ id }),
        header: { 'Content-Type': 'application/json' },
        success: (res) => {
          if (!isCurrent()) return
          const validMerchant = res && res.code == '200' && isRecord(res.data) && positiveId(res.data.id) && positiveId(res.data.id) === positiveId(id)
          if (!validMerchant) {
            this._merchantLoadFailed(bizFailureMessage(res, '这次没有读到商家信息，请稍后重试。'))
            return
          }
          const rawMerchant = res.data
          if (rawMerchant.sysCategoryList != null && !isRecordList(rawMerchant.sysCategoryList)) {
            this._merchantLoadFailed('这次没有读到商家信息，请稍后重试。')
            return
          }
          const merchant = {
            ...rawMerchant,
            displayName: nonEmptyString(rawMerchant.name) || '—',
            chargeText: rawMerchant.chargeType === 1 ? '收费承接' : (rawMerchant.chargeType === 0 ? '免费承接' : '—'),
            businessStatusText: rawMerchant.businessStatus === 1 ? '营业中' : (rawMerchant.businessStatus === 0 ? '已打烊' : '—'),
            catNames: (rawMerchant.sysCategoryList || []).map((item) => nonEmptyString(item.categoryName)).filter(Boolean).join(' · '),
          }
          let gallery = []; let tags = []
          try { gallery = merchant.gallery ? JSON.parse(merchant.gallery) : [] } catch (_) {}
          try { tags = merchant.tags ? JSON.parse(merchant.tags) : [] } catch (_) {}
          gallery = Array.isArray(gallery) ? gallery.map(nonEmptyString).filter(Boolean) : []
          tags = Array.isArray(tags) ? tags.map(nonEmptyString).filter(Boolean) : []
          const rawFeatured = isRecord(rawMerchant.featured) ? rawMerchant.featured : (isRecord(res.featured) ? res.featured : null)
          const featuredType = rawFeatured && typeof rawFeatured.featuredType === 'number' ? rawFeatured.featuredType : null
          const featured = rawFeatured ? {
            ...rawFeatured,
            titleText: nonEmptyString(rawFeatured.name) || nonEmptyString(rawFeatured.title) || '主推内容',
            imageUrl: nonEmptyString(rawFeatured.imgUrl) || nonEmptyString(rawFeatured.coverImg) || nonEmptyString(rawFeatured.img),
            actionable: (featuredType === 1 && !!rawFeatured.featuredId) || featuredType === 2,
          } : null
          if (this.data.state !== 'ready') pageMotion.enter(this, 'pmPage'); this.setData({ merchant, merchantError: '', gallery, tags, featured, hasLocation: !!this._coordinates(this.data.node, merchant), state: 'ready' })
        },
        fail: () => { if (isCurrent()) this._merchantLoadFailed('网络异常，这次没有读到商家信息。') },
        successStatusAbnormal: () => { if (isCurrent()) this._merchantLoadFailed('这次没有读到商家信息，请稍后重试。') },
      })
    },
    _merchantLoadFailed(message) {
      if (this.data.node) this.setData({ merchantError: message })
      else this.setData({ state: 'error', errorText: message })
    },
    retryMerchant() {
      if (!this._closed && this.data.node && this.data.node.merchantId) this._loadMerchant(this.data.node.merchantId, this._loadToken)
    },
    retry() { this._load() },
    previewGallery(event) {
      const current = event.currentTarget.dataset.src
      if (current) mediaPreview.open(this.data.gallery || [current], current)
    },
    toggleFavorite() {
      if (this.data.favoriteBusy || !this.data.poiId) return
      const token = (this._favoriteToken || 0) + 1
      this._favoriteToken = token
      const poiId = this.data.poiId
      this.setData({ favoriteBusy: true, favoriteError: '' })
      app.sendRequest({
        url: '/api/city/nodes/' + poiId + '/favorite',
        method: 'POST',
        hideLoading: true,
        silentError: true,
        success: (res) => {
          if (token !== this._favoriteToken) return
          if (res && (res.code === '200' || res.code === 200) && isRecord(res.data) && typeof res.data.favorited === 'boolean') {
            this.setData({ 'node.favorited': res.data.favorited, favoriteBusy: false, favoriteError: '' })
            return
          }
          this.setData({ favoriteBusy: false, favoriteError: bizFailureMessage(res, '收藏状态没有更新，请重试。') })
        },
        fail: () => { if (token === this._favoriteToken) this.setData({ favoriteBusy: false, favoriteError: '网络异常，收藏状态没有更新。' }) },
        successStatusAbnormal: () => { if (token === this._favoriteToken) this.setData({ favoriteBusy: false, favoriteError: '收藏状态没有更新，请重试。' }) },
      })
    },
    _coordinates(nodeValue, merchantValue) {
      const node = nodeValue || {}
      const merchant = merchantValue || {}
      const nodeLat = coordinate(node.lat, 90)
      const nodeLng = coordinate(node.lng, 180)
      if (nodeLat !== null && nodeLng !== null) return { latitude: nodeLat, longitude: nodeLng }
      const merchantLat = coordinate(merchant.latitude, 90)
      const merchantLng = coordinate(merchant.longitude, 180)
      return merchantLat !== null && merchantLng !== null ? { latitude: merchantLat, longitude: merchantLng } : null
    },
    openLocation() {
      const node = this.data.node || {}; const merchant = this.data.merchant || {}
      const location = this._coordinates(node, merchant)
      if (!location) return
      wx.openLocation({ ...location, name: nonEmptyString(node.name) || nonEmptyString(merchant.name) || '节点', address: nonEmptyString(node.merchantAddress) || nonEmptyString(merchant.address), scale: 18 })
    },
    _isInteractionCancel(error) {
      return /\bcancel(?:led)?\b/i.test(String(error && error.errMsg || ''))
    },
    _isPermissionDenied(error) {
      return /auth deny|authorize|permission/i.test(String(error && error.errMsg || ''))
    },
    _clearInteractionRecovery() {
      this.setData({
        interactionState: 'idle',
        interactionTitle: '',
        interactionText: '',
        interactionActionText: '',
      })
    },
    _setInteractionRecovery(state, title, text, retry, actionText) {
      this._interactionRetry = retry
      this.setData({
        interactionState: state,
        interactionTitle: title,
        interactionText: text,
        interactionActionText: actionText != null
          ? actionText
          : (state === 'retry' ? '重试' : (/permission$/.test(state) ? '去设置' : '')),
      })
    },
    _handleInteractionFailure(kind, error, retry) {
      if (this._isInteractionCancel(error)) {
        if (this.data.checkinPending) { this._pendingNotice(false); return }
        this._clearInteractionRecovery()
        return
      }
      if (kind === 'scan' && this._isPermissionDenied(error)) {
        this._setInteractionRecovery('camera-permission', '需要相机权限', '在设置里允许使用相机，返回后会继续扫码。', retry)
        return
      }
      const title = kind === 'choice' ? '选项没有打开' : '扫码没有完成'
      const text = kind === 'choice' ? '操作面板暂时无法显示，请重试。' : '相机暂时无法打开，请稍后重试。'
      this._setInteractionRecovery('retry', title, text, retry)
    },
    _retryInteraction(retry) {
      if (retry === 'complete' && this._pendingCompletion) {
        const pending = this._pendingCompletion
        this.completeNode(pending.answer, pending.photoUrl, pending.code)
        return
      }
      this.startInteract(true)
    },
    recoverInteraction() {
      if (this._closed) return
      if (this.data.checkinPending && !this._reverification) { this._load(); return }
      const token = this._interactionToken
      const state = this.data.interactionState
      const retry = this._interactionRetry
      if (state === 'camera-permission' || state === 'location-permission') {
        const scope = state === 'camera-permission' ? 'scope.camera' : 'scope.userLocation'
        wx.openSetting({
          success: (result) => {
            if (token !== this._interactionToken || this._closed) return
            if (!(result && result.authSetting && result.authSetting[scope])) return
            this._clearInteractionRecovery()
            this._retryInteraction(retry)
          },
        })
        return
      }
      this._clearInteractionRecovery()
      this._retryInteraction(retry)
    },
    startInteract(resume) {
      const reverify = this.data.checkinPending ? this._currentReverification() : null
      if (!reverify) {
        if (this.data.checkinPending) return
      }
      if (reverify && this.data.reverifyStarted && resume !== true) return
      if (this._loadedOwner !== ownerId()) { this._load(); return }
      const node = this.data.node
      if (this._closed || !node || node.completed || this.data.completing) return
      const token = (this._interactionToken || 0) + 1
      this._interactionToken = token
      if (reverify) this.setData({ reverifyStarted: true })
      this._clearInteractionRecovery()
      if (node.needRedeem === true) {
        this.triggerEvent('open', { id: 'qr-citynode', params: { poiId: this.data.poiId, name: node.name || '节点' } })
        return
      }
      if (node.status !== 1) {
        this._setInteractionRecovery('unavailable', '节点已下线', '这里保留你的历史记录，暂不接受新的打卡。', '')
        return
      }
      if (node.canInteract === false || !VALIDATION_METHODS.includes(node.validationMethod)) {
        this._setInteractionRecovery('unavailable', '—', '该节点尚未配置可用的核验方式，暂时不能打卡。', '')
        return
      }
      if (node.photoRequired === true && !this.data.challengePhoto) {
        this.takeChallengePhoto(token)
        return
      }
      if (node.validationMethod === 1) {
        modal.show({ title: '完成互动', editable: true, placeholderText: '输入答案 / 暗号', success: (result) => {
          if (token !== this._interactionToken || this._closed) return
          if (result.confirm) this.completeNode(result.content || '', this.data.challengePhoto || '')
          else if (reverify) this._pendingNotice(false)
        } })
        return
      }
      if (node.validationMethod === 3) {
        const letters = ['A', 'B', 'C', 'D']
        const texts = [node.questionA, node.questionB, node.questionC, node.questionD]
        const items = []
        const picked = []
        for (let i = 0; i < texts.length; i++) {
          if (texts[i]) { items.push(letters[i] + '. ' + texts[i]); picked.push(letters[i]) }
        }
        if (!items.length) { toast('这道题还没配好选项'); return }
        /* 2026-09-02:原来弹 wx.showActionSheet(系统弹层,设计体系外)。
           这是一道选择题 —— 属于「一次选择」,所以走 cy-option-sheet 的默认形态
           (单选圆点 + 确认 CTA),答案要点两下才提交,防误触交卷。 */
        this._choiceSheetToken = token
        this._choiceSheetValues = picked   // 只用于把选中项翻译回答案字母,不渲染 ⇒ 不进 data
        this.setData({ choiceSheetShow: true, choiceSheetItems: items })
        return
      }
      if (node.validationMethod === 2) {
        const photo = { ownerId: ownerId(), poiId: positiveId(this.data.poiId), loadToken: this._loadToken, delivered: false }
        this._poiPhoto = photo
        photo.operation = app.chooseImage((urls) => {
          if (token !== this._interactionToken || this._closed) return
          photo.delivered = !!(urls && urls.length)
          if (urls && urls.length) this.completeNode('', urls[0])
          else if (reverify) this._pendingNotice(false)
        }, 1)
        return
      }
      if (node.validationMethod === 4) {
        wx.scanCode({
          onlyFromCamera: true,
          scanType: ['qrCode'],
          success: (result) => {
            if (token !== this._interactionToken || this._closed) return
            if (!result.result) { if (reverify) this._pendingNotice(false); toast('没识别到码'); return }
            this.completeNode('', this.data.challengePhoto || '', result.result)
          },
          fail: (error) => { if (token === this._interactionToken && !this._closed) this._handleInteractionFailure('scan', error, 'start') },
        })
        return
      }
      this.completeNode('', this.data.challengePhoto || '')
    },
    takeChallengePhoto(token) {
      const node = this.data.node, actor = ownerId()
      const current = () => !this._closed && token === this._interactionToken && actor === ownerId()
      wx.getLocation({ type: 'gcj02', success: loc => {
        if (!current()) return
        const distance = require('../../../../utils/geo.js').haversine(loc.latitude, loc.longitude, Number(node.lat), Number(node.lng))
        if (!Number.isFinite(distance) || distance > Number(node.radiusM || 80) + 30) {
          this._setInteractionRecovery('retry', '还没到达地点', '先导航到地点附近，再拍摄任务照片。', 'start', '重新检查位置')
          return
        }
        const photo = { ownerId: actor, poiId: positiveId(this.data.poiId), loadToken: this._loadToken, delivered: false }
        this._poiPhoto = photo
        photo.operation = app.chooseImage(urls => {
          if (!current()) return
          if (!urls || !urls[0]) { if (this.data.checkinPending) this._pendingNotice(false); return }
          photo.delivered = true
          const context = pendingContext(this.data.poiId)
          if (!context) return
          try { if (!this.data.checkinPending) wx.setStorageSync(context.key + ':photo', { templateId: node.templateId, url: urls[0] }) }
          catch (_) { toast('照片进度保存失败，请重试'); return }
          this.setData({ challengePhoto: urls[0] })
          this.startInteract(true)
        }, 1, { cameraOnly: true, skipCrop: true })
      }, fail: error => {
        if (current()) this._handleInteractionFailure('location', error, 'start')
      } })
    },
    retakeChallengePhoto() {
      if (this.data.completing || this.data.checkinPending) return
      const context = pendingContext(this.data.poiId)
      try { if (context) wx.removeStorageSync(context.key + ':photo') } catch (_) {}
      this.setData({ challengePhoto: '' })
      this.startInteract()
    },
    onPhotoPick(e) { this.setData({ photoIndex: Number(e.currentTarget.dataset.index) || 0 }) },
    onPhotoCredit() { this.setData({ creditOpen: !this.data.creditOpen }) },
    loadPhotoCredit(node) {
      const key = node.coverImg, token = this._loadToken, actor = ownerId()
      if (typeof key !== 'string' || !/^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+)*\.(?:jpg|png|webp)$/.test(key)) return
      app.sendRequest({ url: '/profile/' + key.replace(/\.[^.]+$/, '.gallery.json'), method: 'GET', auth: false,
        hideLoading: true, silentError: true, autoErrorToast: false, success: res => {
          if (this._closed || token !== this._loadToken || actor !== ownerId()) return
          const d = res && res.code == 200 && res.data
          if (d && String(d.poiId) === String(this.data.poiId) && typeof d.credit === 'string')
            this.setData({ photoCredit: d.credit.slice(0, 2000) })
        } })
    },
    loadReviews() {
      const poiId = this.data.poiId, token = this._loadToken, actor = ownerId()
      if (!poiId) return
      this.setData({ reviewsError: false })
      app.sendRequest({ url: '/api/comment/list', method: 'POST', hideLoading: true, silentError: true,
        data: { owner_type: 4, owner_id: poiId, pageNum: 1, pageSize: 10 },
        success: res => {
          if (this._closed || poiId !== this.data.poiId || token !== this._loadToken || actor !== ownerId()) return
          const rows = res && res.code == 200 && res.data && res.data.rows
          this.setData({ reviews: Array.isArray(rows) ? rows.map(row => ({ ...row, mine: actor !== null && positiveId(row.memberId) === actor })) : [], reviewsError: !Array.isArray(rows) })
        },
        fail: () => { if (!this._closed && token === this._loadToken && actor === ownerId()) this.setData({ reviewsError: true }) },
        successStatusAbnormal: () => { if (!this._closed && token === this._loadToken && actor === ownerId()) this.setData({ reviewsError: true }) },
      })
    },
    onReviewInput(e) { this.setData({ reviewText: e.detail.value }) },
    onReviewRating(e) { this.setData({ reviewRating: Number(e.currentTarget.dataset.rating) }) },
    deleteOwnReview(e) {
      if (this._loadedOwner !== ownerId()) { this._load(); return }
      const id = e.currentTarget.dataset.id, poiId = this.data.poiId, actor = ownerId(), token = this._loadToken
      if (!this.data.reviews.some(row => String(row.id) === String(id) && row.mine)) return
      const current = () => !this._closed && token === this._loadToken && actor === ownerId() && poiId === this.data.poiId
      modal.show({ dangerKey: 'club.comment.delete', success: result => {
        if (!result.confirm || !current()) return
        app.sendRequest({ url: '/api/comment/delete', method: 'POST', hideLoading: true, silentError: true, data: { id },
          success: res => {
            if (!current()) return
            if (res && res.code == 200) this._load()
            else toast(bizFailureMessage(res, '评价未删除，请重试'))
          },
          fail: () => { if (current()) toast('评价未删除，请重试') },
        })
      } })
    },
    submitReview() {
      if (this._loadedOwner !== ownerId()) { this._load(); return }
      if (!this.data.node || !this.data.node.completed || this.data.reviewSubmitting) return
      const contents = this.data.reviewText.trim(), poiId = this.data.poiId, actor = ownerId(), token = this._loadToken
      if (!contents) { toast('写下你的游玩感受'); return }
      if (!(this.data.reviewRating >= 1 && this.data.reviewRating <= 5)) { toast('请选择评分'); return }
      this.setData({ reviewSubmitting: true })
      const current = () => !this._closed && token === this._loadToken && actor === ownerId() && poiId === this.data.poiId
      app.sendRequest({ url: '/api/comment/add', method: 'POST', hideLoading: true, silentError: true,
        data: { owner_type: 4, owner_id: poiId, rating: this.data.reviewRating, contents, reply_id: 0 },
        success: res => {
          if (!current()) return
          this.setData({ reviewSubmitting: false })
          if (res && res.code == 200) { toast('评价已提交'); this._load() }
          else toast(bizFailureMessage(res, '评价未提交，请重试'))
        },
        fail: () => { if (current()) { this.setData({ reviewSubmitting: false }); toast('评价未提交，请重试') } },
        successStatusAbnormal: () => { if (current()) { this.setData({ reviewSubmitting: false }); toast('评价未提交，请重试') } },
      })
    },
    completeNode(answer, photoUrl, code) {
      if (this._closed || this.data.completing || !this.data.poiId || !this.data.node || this.data.node.completed === true) return
      const reverify = this.data.checkinPending ? this._currentReverification() : null
      if (!reverify) {
        if (this.data.checkinPending) return
      }
      if (this.data.node.needRedeem === true) return
      if (this._loadedOwner !== ownerId()) { this._load(); return }
      if (this.data.node.status !== 1) return this.startInteract()
      const context = pendingContext(this.data.poiId)
      if (!context) { this._pendingNotice(true); return }
      const token = (this._completionToken || 0) + 1
      this._completionToken = token
      const poiId = this.data.poiId
      const isCurrent = () => !this._closed && token === this._completionToken && poiId === this.data.poiId && context.ownerId === ownerId()
      this._pendingCompletion = { answer: answer || '', photoUrl: photoUrl || '', code: code || '', poiId }
      this._clearInteractionRecovery()
      this.setData({ completing: true })
      let locationSettled = false
      wx.getLocation({
        type: 'gcj02',
        success: (location) => {
          if (!isCurrent() || locationSettled) return
          locationSettled = true
          const record = reverify
            ? { v: 1, ownerId: context.ownerId, poiId: context.poiId, attemptId: reverify.attemptId, uncertain: true }
            : { v: 1, ownerId: context.ownerId, poiId: context.poiId, attemptId: Date.now() + ':' + (++attemptSequence) + ':' + Math.random() }
          try {
            const existing = readPending(context)
            if (reverify ? !existing || existing.attemptId !== record.attemptId : !!existing) { this._load(); return }
            wx.setStorageSync(context.key, record)
            const saved = readPending(context)
            if (!saved || saved.attemptId !== record.attemptId) throw new Error('pending not saved')
          } catch (_) { this._pendingNotice(true); return }
          this.setData({ checkinPending: true })
          this._pendingCompletion = null
          const unknown = () => { if (isCurrent()) this._pendingNotice(false) }
          app.sendRequest({
            url: '/api/city/nodes/' + poiId + '/complete', method: 'POST', hideLoading: true, silentError: true,
            data: { lat: location.latitude, lng: location.longitude, answer: answer || '', photoUrl: photoUrl || '', code: code || '' },
            success: (res) => {
              const receipt = res && res.data
              const matchingPoi = isRecord(receipt) && ['poiId', 'id'].every((key) => !Object.prototype.hasOwnProperty.call(receipt, key) || positiveId(receipt[key]) === context.poiId)
              const validCoupon = isRecord(receipt) && (receipt.needRedeem !== true || positiveId(receipt.couponId) !== null)
              const ok = res && (res.code === 200 || res.code === '200') && matchingPoi && validCoupon && typeof res.data.needRedeem === 'boolean' && (res.data.completed == null || (typeof res.data.completed === 'boolean' && res.data.completed !== res.data.needRedeem)) && (res.data.alreadyClaimed == null || typeof res.data.alreadyClaimed === 'boolean')
              // Only these controller messages prove rejection before insertRewardLogIgnore.
              const rejected = res && (res.code === 500 || res.code === '500') && PREWRITE_REJECTIONS.has(res.msg)
              if (!ok && !rejected) { unknown(); return }
              // 新调用被拒只说明本次没写入，不能否定上次未知请求。
              let unknownBefore = !!reverify
              try { const stored = readPending(context); unknownBefore = unknownBefore || !!(stored && stored.uncertain) }
              catch (_) { if (isCurrent()) this._pendingNotice(true); return }
              if (rejected && unknownBefore) {
                if (isCurrent()) {
                  this._pendingNotice(false)
                  this.setData({ pendingStatusText: '本次核验未通过：' + res.msg + '。上次结果仍待确认。' })
                }
                return
              }
              let cleared
              try { cleared = clearPending(context, record) } catch (_) { if (isCurrent()) this._pendingNotice(true); return }
              if (!isCurrent() || !cleared) {
                // 迟到的正向确认可以结束同一旧标记，但不能让新流程继续提交或重复弹奖励。
                if (cleared && ok && !this._closed && context.ownerId === ownerId() && poiId === this.data.poiId
                    && this._reverification && this._reverification.attemptId === record.attemptId) this._load()
                return
              }
              this._reverification = null
              this._interactionToken = (this._interactionToken || 0) + 1
              this.setData({ completing: false, checkinPending: false })
              if (rejected) { this._setInteractionRecovery('retry', '打卡没有完成', res.msg, 'start', '重新作答'); return }
              const result = res.data
              this.setData({ challengePhoto: '', choiceSheetShow: false, reverifyReady: false, reverifyStarted: false, pendingStatusText: '' })
              this._clearInteractionRecovery()
              this.setData({ 'node.completed': !result.needRedeem, 'node.needRedeem': result.needRedeem })
              const recovered = !!reverify || unknownBefore
              this.triggerEvent('completed', { poiId, alreadyClaimed: result.alreadyClaimed === true, needRedeem: result.needRedeem, ...(recovered ? { recovered: true } : {}) })
              if (recovered) toast(result.needRedeem ? '已确认当前待核销' : '已确认当前已完成')
              else if (result.needRedeem) this.triggerEvent('open', { id: 'qr-citynode', params: { poiId, name: this.data.node.name || '节点' } })
              else toast(result.alreadyClaimed ? '你已完成过' : '打卡成功', { icon: result.alreadyClaimed ? 'none' : 'success' })
            },
            fail: () => { if (isCurrent()) this._pendingNotice(false) },
            successStatusAbnormal: unknown,
          })
        },
        fail: (error) => {
          if (!isCurrent() || locationSettled) return
          locationSettled = true
          this.setData({ completing: false })
          if (this._isPermissionDenied(error)) { this._setInteractionRecovery('location-permission', '需要定位权限', '在设置里允许定位，返回后会继续本次打卡。', 'complete'); return }
          this._setInteractionRecovery('retry', '定位没有完成', '暂时无法获取位置，请稍后重试。', 'complete')
        },
      })
    },
    openFeatured() {
      const item = this.data.featured || {}
      if (item.featuredType == 1 && item.featuredId) this.triggerEvent('open', { id: 'play-activity-detail', params: { id: item.featuredId } })
      else if (item.featuredType == 2) this.triggerEvent('open', { id: 'game-coupon-wallet' })
    },
    /* 投一张换一张:交给宿主页去 navigateTo —— 场景组件不自己跳原生页,
     * 跳了返回栈会落回场景里,人就卡在一个已经关掉的半屏上。 */
    openCityStamp() {
      this.triggerEvent('citystamp', { place: (this.data.node && this.data.node.name) || '这一站' })
    },
    close() {
      this._closed = true
      this._interactionToken = (this._interactionToken || 0) + 1
      this._loadToken = (this._loadToken || 0) + 1
      this._completionToken = (this._completionToken || 0) + 1
      this._favoriteToken = (this._favoriteToken || 0) + 1
      this.setData({ choiceSheetShow: false })
      this.triggerEvent('close')
    },
  },
})
