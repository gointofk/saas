export type QuantityPriceBand = { minQuantity: number; maxQuantity: number | null; unitPrice: string | number }
export type PriceTier = 'V1' | 'V2' | 'V3' | 'V4' | 'V5' | 'V6'
export type TierQuantityPrices = { tier: PriceTier; bands: QuantityPriceBand[] }
export const priceTiers: PriceTier[] = ['V1', 'V2', 'V3', 'V4', 'V5', 'V6']

// Preview from server supplied prices; the server always validates the saved amount.
export function quantityUnitPrice(bands: QuantityPriceBand[] | undefined, quantity: number, fallback: string | number): string | number {
  return bands?.find(band => quantity >= band.minQuantity && (band.maxQuantity === null || quantity <= band.maxQuantity))?.unitPrice ?? fallback
}
export function quantityPriceError(tiers: TierQuantityPrices[]): string {
  for (const { bands } of tiers) {
    let next = 1
    if (!bands.length || bands.length > 100) return '每个等级最多设置 100 个数量档。'
    for (let i = 0; i < bands.length; i++) {
      const band = bands[i]!
      if (!Number.isInteger(band.minQuantity) || band.minQuantity !== next || band.minQuantity > 2147483647
        || (i === bands.length - 1 ? band.maxQuantity !== null : band.maxQuantity === null || !Number.isInteger(band.maxQuantity) || band.maxQuantity < band.minQuantity)) return '数量档必须从 1 开始、连续且不重叠，最后一档无上限。'
      if (!/^\d+(\.\d{1,2})?$/.test(String(band.unitPrice)) || Number(band.unitPrice) <= 0) return '阶梯单价必须大于 0，最多两位小数。'
      next = (band.maxQuantity ?? 0) + 1
    }
  }
  return ''
}
