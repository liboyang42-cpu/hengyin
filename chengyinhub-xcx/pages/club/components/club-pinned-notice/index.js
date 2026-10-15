Component({
  properties: {
    notices: { type: Array, value: [] },
  },
  methods: {
    onOpen(e) {
      const id = e.currentTarget.dataset.id
      if (id !== undefined && id !== '') this.triggerEvent('open', { id })
    },
  },
})
