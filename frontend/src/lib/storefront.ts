import { reactive } from 'vue'
import { request } from './api'
export const storefrontBrand = reactive({ name: '', logoUrl: '', theme: 'classic', registrationEnabled: false })
export async function loadStorefront() {
  const brand = await request<typeof storefrontBrand>('/customer/storefront', {}, 'customer', { token: null, clearOnUnauthorized: false })
  Object.assign(storefrontBrand, brand)
  document.title = brand.name
  document.documentElement.dataset.storefrontTheme = brand.theme
}
