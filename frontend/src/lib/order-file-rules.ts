export const MAX_ORDER_ATTACHMENT_BYTES = 15 * 1024 * 1024
export const ORDER_IMAGE_MIME_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/bmp'] as const
export const ORDER_IMAGE_ACCEPT = ORDER_IMAGE_MIME_TYPES.join(',')
export const ORDER_IMAGE_FORMATS = 'JPG / PNG / GIF / BMP'
