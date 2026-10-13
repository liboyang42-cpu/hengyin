import DOMPurify from 'dompurify'

const ALLOWED_TAGS = ['b', 'i', 'em', 'strong', 'p', 'br', 'ul', 'ol', 'li', 'a']
const ALLOWED_ATTR = ['href', 'target', 'rel']

const config = { ALLOWED_TAGS, ALLOWED_ATTR }

export function sanitizeHtml(dirty) {
  if (!dirty || typeof dirty !== 'string') return dirty
  return DOMPurify.sanitize(dirty, config)
}
