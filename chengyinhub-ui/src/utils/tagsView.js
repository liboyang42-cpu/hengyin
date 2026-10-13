export const MAX_RECENT_VIEWS = 8

export const hasEntityId = view => {
  const query = view && view.query
  return query && Object.prototype.hasOwnProperty.call(query, 'id') && query.id !== '' && query.id !== null && query.id !== undefined
}

export const getViewKey = view => {
  if (!hasEntityId(view)) {
    return view.path
  }
  return `${view.path}?id=${encodeURIComponent(String(view.query.id))}`
}
