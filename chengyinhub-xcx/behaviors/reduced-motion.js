const { readReducedMotion, subscribeReducedMotion } = require('../utils/motion-preference.js')

module.exports = Behavior({
  properties: {
    reducedMotion: { type: Boolean, value: false },
  },
  lifetimes: {
    attached() {
      this._pageReducedMotion = this.data.reducedMotion === true
      this._unsubscribeReducedMotion = subscribeReducedMotion(() => this._syncReducedMotionPreference())
      this._syncReducedMotionPreference()
    },
    detached() {
      if (this._unsubscribeReducedMotion) this._unsubscribeReducedMotion()
    },
  },
  observers: {
    reducedMotion(value) {
      if (this._syncingReducedMotion) return
      this._pageReducedMotion = value === true
      this._syncReducedMotionPreference()
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
      if (this.data.reducedMotion !== reducedMotion) {
        this._syncingReducedMotion = true
        this.setData({ reducedMotion }, () => { this._syncingReducedMotion = false })
      }
    },
  },
})
