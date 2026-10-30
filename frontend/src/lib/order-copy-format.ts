type CopyOrderItem = { sku?: unknown; quantity?: unknown }
const COPY_COLUMN_GAP = 20
const COPY_COLUMN_SPACE = '\u2007'

function textWidth(value: string) {
  let width = 0
  for (const character of value) {
    const codePoint = character.codePointAt(0) || 0
    width += (
      (codePoint >= 0x1100 && codePoint <= 0x11ff) ||
      (codePoint >= 0x2e80 && codePoint <= 0xa4cf) ||
      (codePoint >= 0xac00 && codePoint <= 0xd7af) ||
      (codePoint >= 0xf900 && codePoint <= 0xfaff) ||
      (codePoint >= 0xfe10 && codePoint <= 0xfe6f) ||
      (codePoint >= 0xff00 && codePoint <= 0xffef)
    ) ? 2 : 1
  }
  return width
}

export function formatOrderCopyRows(items: readonly CopyOrderItem[], skuHeading: string, quantityHeading: string) {
  const skus = items.map(item => String(item.sku ?? ''))
  const skuColumnWidth = skus.reduce((width, sku) => Math.max(width, textWidth(sku)), textWidth(skuHeading))
  const padToQuantity = (value: string) => COPY_COLUMN_SPACE.repeat(Math.max(0, skuColumnWidth - textWidth(value)))

  return [
    `${skuHeading}${padToQuantity(skuHeading)}${COPY_COLUMN_SPACE.repeat(COPY_COLUMN_GAP)}${quantityHeading}`,
    ...items.map((item, index) => {
      const sku = skus[index] || ''
      return `${sku}${padToQuantity(sku)}${COPY_COLUMN_SPACE.repeat(COPY_COLUMN_GAP)}${String(item.quantity ?? '')}`
    }),
  ]
}
