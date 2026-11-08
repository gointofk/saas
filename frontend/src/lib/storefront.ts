import { reactive } from 'vue'
import { request } from './api'
export const storefrontBrand = reactive({ name: '', logoUrl: '', theme: 'classic', registrationEnabled: false, commissionEnabled: true, whatsappNumber: null as string | null })

export function applyMerchantBranding(name: string, logoUrl: string) {
  document.title = name || 'DailyActivity'
  let icon = document.querySelector<HTMLLinkElement>('link[rel~="icon"]')
  if (!icon) {
    icon = document.createElement('link')
    icon.rel = 'icon'
    document.head.append(icon)
  }
  icon.removeAttribute('type')
  icon.href = logoUrl || '/dailyactivity-logo.svg'
}

export async function loadStorefront() {
  const brand = await request<typeof storefrontBrand>('/customer/storefront', {}, 'customer', { token: null, clearOnUnauthorized: false })
  Object.assign(storefrontBrand, brand)
  applyMerchantBranding(brand.name, brand.logoUrl)
  document.documentElement.dataset.storefrontTheme = brand.theme
}
