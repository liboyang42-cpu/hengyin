const { readReducedMotion, subscribeReducedMotion } = require('../utils/motion-preference.js')

module.exports = Behavior({
  properties: {
    reducedMotion: { type: Boolean, value: false },
  },
  observers: {
    reducedMotion(value) {
      if (this._syncingReducedMotion) return
      this._pageReducedMotion = value === true
      this._syncReducedMotionPreference()
    },
  },
  lifetimes: {
    attached() {
      this._pageReducedMotion = this.data.reducedMotion === true
      this._unsubscribeReducedMotion = subscribeReducedMotion(() => this._syncReducedMotionPreference())
      this._syncReducedMotionPreference()
    },
    detached() {
      if (this._unsubscribeReducedMotion) this._unsubscribeReducedMotion()
      this._unsubscribeReducedMotion = null
    },
  },
  pageLifetimes: {
    show() {
      this._syncReducedMotionPreference()
    },
  },
  methods: {
    _syncReducedMotionPreference() {
      const reducedMotion = this._pageReducedMotion === true || readReducedMotion()
      if (this.data.reducedMotion === reducedMotion) return
      this._syncingReducedMotion = true
      try { this.setData({ reducedMotion }) } finally { this._syncingReducedMotion = false }
    },
  },
})
