<script setup lang="ts">
import { reactive } from 'vue'
import { quantityUnitPrice, type QuantityPriceBand } from '../lib/quantity-pricing'
import type { CustomerCategory, CustomerCoa, CustomerProduct } from '../types/customer'

const props = defineProps<{
  categories: CustomerCategory[]
  activeCategory: number | null
  productSearch: string
  cartPending: boolean
  pendingCartProductIds: Set<number>
  cartQuantities: Record<number, number | null>
  visibleProducts: CustomerProduct[]
  t: (text: string) => string
  money: (value: unknown) => string
}>()

const quantityDrafts = reactive<Record<number, number | ''>>({})

const emit = defineEmits<{
  'update:activeCategory': [categoryId: number | null]
  'update:productSearch': [search: string]
  'update-cart-quantity': [product: CustomerProduct, quantity: number]
}>()

function productCoas(product: CustomerProduct): CustomerCoa[] {
  return product.coas || []
}

function cartQuantity(product: CustomerProduct): number {
  return props.cartQuantities[product.id] ?? 0
}

function selectedQuantity(product: CustomerProduct): number | null {
  const draft = quantityDrafts[product.id]
  if (draft === '') return null
  return typeof draft === 'number' ? draft : cartQuantity(product) || null
}

function quantityInputValue(product: CustomerProduct): number | '' {
  const draft = quantityDrafts[product.id]
  return (draft ?? cartQuantity(product)) || ''
}

function commitQuantity(product: CustomerProduct, quantity: number) {
  if (!Number.isInteger(quantity) || quantity < 0 || quantity > product.stock) return
  delete quantityDrafts[product.id]
  if (quantity !== cartQuantity(product)) emit('update-cart-quantity', product, quantity)
}

function updateQuantity(product: CustomerProduct, event: Event) {
  const input = event.target as HTMLInputElement
  if (input.value === '') {
    quantityDrafts[product.id] = ''
    return
  }
  const quantity = Number(input.value)
  if (Number.isInteger(quantity) && quantity > 0) quantityDrafts[product.id] = Math.min(quantity, product.stock)
}

function normalizeQuantity(product: CustomerProduct, event: Event) {
  const input = event.target as HTMLInputElement
  if (input.value === '') {
    delete quantityDrafts[product.id]
    return
  }
  const quantity = Number(input.value)
  if (Number.isInteger(quantity) && quantity >= 1) commitQuantity(product, Math.min(quantity, product.stock))
  else delete quantityDrafts[product.id]
}

function decreaseQuantity(product: CustomerProduct) {
  const quantity = selectedQuantity(product)
  if (quantity !== null && quantity > 0) commitQuantity(product, quantity - 1)
}

function increaseQuantity(product: CustomerProduct) {
  commitQuantity(product, (selectedQuantity(product) ?? 0) + 1)
}

function activePriceBand(product: CustomerProduct, band: QuantityPriceBand): boolean {
  const quantity = selectedQuantity(product)
  return quantity !== null && quantity >= band.minQuantity && (band.maxQuantity === null || quantity <= band.maxQuantity)
}

function unitPrice(product: CustomerProduct): number | string {
  return quantityUnitPrice(product.quantityPrices, selectedQuantity(product) ?? 1, product.price)
}

function subtotal(product: CustomerProduct): number | null {
  const quantity = selectedQuantity(product)
  return quantity === null ? null : Math.round(Number(unitPrice(product)) * 100) * quantity / 100
}
</script>

<template>
  <main class="catalog-section">
    <section class="catalog-promise" :aria-label="t('Catalog information')">
      <div class="catalog-promise-copy">
        <h1>We are a peptide source factory, specializing in bulk peptide orders.</h1>
        <p>We take 100% responsibility for product quality, logistics transportation and customs‑clearance issues.</p>
      </div>
      <svg class="catalog-promise-visual" viewBox="0 0 320 220" fill="none" aria-hidden="true" focusable="false">
        <path d="M44 151 90 92l49 27 45-66 48 50 44-38" />
        <path d="m90 92 17-56m32 83 14 59m31-125 58-17m-10 67 9 59m35-97 30 23" />
        <circle cx="44" cy="151" r="10" /><circle cx="90" cy="92" r="13" />
        <circle cx="107" cy="36" r="7" /><circle cx="139" cy="119" r="11" />
        <circle cx="153" cy="178" r="8" /><circle cx="184" cy="53" r="14" />
        <circle cx="242" cy="36" r="8" /><circle cx="232" cy="103" r="12" />
        <circle cx="241" cy="162" r="7" /><circle cx="276" cy="65" r="11" />
        <circle cx="306" cy="88" r="6" />
      </svg>
    </section>
    <section class="catalog-frame">
      <div class="catalog-filter-row">
        <div class="category-tabs" role="group" :aria-label="t('Categories')">
          <button type="button" :aria-pressed="activeCategory === null" :class="{ active: activeCategory === null }" @click="emit('update:activeCategory', null)">{{ t('All') }}</button>
          <button
            v-for="category in categories"
            :key="category.id"
            type="button"
            :aria-pressed="activeCategory === category.id"
            :class="{ active: activeCategory === category.id }"
            @click="emit('update:activeCategory', category.id)"
          >{{ category.name }}</button>
        </div>
      </div>

      <div class="product-list-header">
        <h2 class="product-list-title">{{ t('Products') }}</h2>
        <label class="search-bar">
          <span class="sr-only">{{ t('Search products') }}</span>
          <input autocomplete="off"
            :value="productSearch"
            type="search"
            :placeholder="t('Search products...')"
            @input="emit('update:productSearch', ($event.target as HTMLInputElement).value)"
          >
        </label>
      </div>

      <div class="product-grid">
        <article v-for="product in visibleProducts" :key="product.id" class="product-card" :class="{ prebuy: !product.stock }">
          <div class="stock-top">
            <span class="sku-badge">{{ product.sku }}</span>
            <span class="stock-count" :class="{ 'out-of-stock': !product.stock }">{{ product.stock ? `${product.stock} ${t('in stock')}` : t('Out of stock') }}</span>
          </div>
          <div class="product-main">
            <p class="product-family">{{ product.category }}</p>
            <h3 class="product-name">{{ product.name }}</h3>
            <p v-if="product.description" class="product-desc">{{ product.description }}</p>
            <div v-if="productCoas(product).length" class="product-coa-stack">
              <a
                v-for="coa in productCoas(product)"
                :key="coa.fileUuid"
                class="product-coa"
                :href="coa.publicUrl"
                target="_blank"
                rel="noopener noreferrer"
                :aria-label="`${t('View COA')}: ${product.category}, ${coa.name || product.name}, ${t('Lot')} ${coa.lot || '—'}${coa.testDate ? `, ${coa.testDate}` : ''}`"
              >
                <svg class="product-coa-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M5 2.75h6.2L15 6.6v10.65H5z"/><path d="M11 2.75V7h4M7.5 10h5M7.5 13h5"/></svg>
                <span class="product-coa-copy" :title="`${t('View COA')} · ${product.category} · ${coa.name || product.name} · ${t('Lot')} ${coa.lot || '—'}${coa.testDate ? ` · ${coa.testDate}` : ''}`">{{ t('View COA') }} · {{ product.category }} · {{ coa.name || product.name }} · {{ t('Lot') }} {{ coa.lot || '—' }}{{ coa.testDate ? ` · ${coa.testDate}` : '' }}</span>
              </a>
            </div>
          </div>
          <div v-if="product.quantityPrices?.length" class="product-quantity-prices">
            <p>{{ t('Quantity pricing') }}</p>
            <div v-for="band in product.quantityPrices" :key="band.minQuantity" :class="{ active: activePriceBand(product, band) }" :aria-current="activePriceBand(product, band) ? 'true' : undefined"><span>{{ band.maxQuantity === null ? `${band.minQuantity}+` : `${band.minQuantity}–${band.maxQuantity}` }} {{ t('units') }}</span><strong>{{ money(band.unitPrice) }} <small>/ {{ t('unit') }}</small></strong></div>
          </div>
          <section class="product-purchase" :aria-label="`${product.name} ${t('purchase options')}`">
            <div class="product-current-price" aria-live="polite">
              <span>{{ t('Unit price') }}</span>
              <strong>{{ money(unitPrice(product)) }}</strong>
            </div>
            <div class="product-quantity-control">
              <label :for="`product-quantity-${product.id}`">{{ t('Quantity') }}</label>
              <div class="product-quantity-stepper">
                <button type="button" :disabled="!product.stock || cartPending || pendingCartProductIds.has(product.id) || selectedQuantity(product) === null" :aria-label="`${t('Decrease quantity')} ${product.name}`" @click="decreaseQuantity(product)">−</button>
                <input
                  :id="`product-quantity-${product.id}`"
                  :value="quantityInputValue(product)"
                  type="number"
                  min="1"
                  :max="product.stock"
                  step="1"
                  :disabled="!product.stock || cartPending || pendingCartProductIds.has(product.id)"
                  :aria-label="`${t('Quantity for')} ${product.name}`"
                  @input="updateQuantity(product, $event)"
                  @change="normalizeQuantity(product, $event)"
                >
                <button type="button" :disabled="!product.stock || cartPending || pendingCartProductIds.has(product.id) || (selectedQuantity(product) !== null && selectedQuantity(product)! >= product.stock)" :aria-label="`${t('Increase quantity')} ${product.name}`" @click="increaseQuantity(product)">+</button>
              </div>
            </div>
            <div class="product-cart-status" :class="{ active: selectedQuantity(product) !== null }" role="status" aria-live="polite">
              <svg class="cart-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 5h2l1.5 10.5h10L20 8H7"/><circle cx="9.5" cy="19" r="1"/><circle cx="17" cy="19" r="1"/></svg>
              <span>{{ t('In cart') }}</span>
              <strong>{{ money(subtotal(product) ?? 0) }}</strong>
            </div>
          </section>
        </article>
        <div v-if="!visibleProducts.length" class="catalog-empty"><p>{{ t('No products match your search. Try another name or category.') }}</p></div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.product-quantity-prices { border-top: 1px solid var(--color-line); margin-top: 14px; padding-top: 10px; font-size: 13px; font-variant-numeric: tabular-nums; }
.product-quantity-prices p { margin: 0 0 8px; color: #667a72; }
.product-quantity-prices div { display: flex; justify-content: space-between; gap: 12px; margin: 6px 0; padding: 5px 7px; border-radius: 5px; }
.product-quantity-prices div.active { background: var(--color-soft); color: var(--color-primary); }
.product-quantity-prices strong { color: var(--color-primary); white-space: nowrap; }
.product-quantity-prices small { font-weight: normal; }
.product-purchase { margin-top: auto; padding-top: 16px; border-top: 1px solid var(--color-line); }
.product-current-price { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 14px; font-variant-numeric: tabular-nums; }
.product-current-price span { color: var(--color-muted); font-size: 12px; }
.product-current-price strong { color: var(--color-primary); font-size: 23px; white-space: nowrap; }
.product-quantity-control { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.product-quantity-control > label { color: var(--color-ink); font-size: 13px; font-weight: 600; }
.product-quantity-stepper { display: flex; align-items: center; overflow: hidden; border: 1px solid var(--color-line-strong); border-radius: 8px; background: var(--color-surface); }
.product-quantity-stepper button { display: grid; width: 36px; height: 36px; place-items: center; border: 0; background: transparent; color: var(--color-primary); font-size: 19px; cursor: pointer; }
.product-quantity-stepper button:disabled { color: var(--color-muted); cursor: not-allowed; opacity: .5; }
.product-quantity-stepper input { width: 54px; height: 36px; padding: 0 2px; border: 0; border-right: 1px solid var(--color-line); border-left: 1px solid var(--color-line); border-radius: 0; background: transparent; color: var(--color-ink); font: inherit; font-size: 14px; font-variant-numeric: tabular-nums; text-align: center; }
.product-quantity-stepper input:focus { outline: 2px solid var(--color-primary); outline-offset: -2px; }
.product-quantity-stepper input::-webkit-inner-spin-button, .product-quantity-stepper input::-webkit-outer-spin-button { margin: 0; appearance: none; }
.product-cart-status { display: flex; min-height: 46px; align-items: center; gap: 9px; margin-top: 13px; padding: 8px 12px; border: 1px solid var(--color-line); border-radius: 9px; background: var(--color-soft); color: var(--color-muted); font-size: 13px; font-weight: 700; }
.product-cart-status.active { border-color: var(--color-primary); background: var(--color-primary); color: var(--color-surface); }
.product-cart-status .cart-icon { width: 20px; flex: 0 0 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.5; }
.product-cart-status strong { margin-left: auto; font-size: 14px; font-variant-numeric: tabular-nums; white-space: nowrap; }
</style>
