<script setup lang="ts">
import type { CustomerCategory, CustomerCoa, CustomerProduct } from '../types/customer'

defineProps<{
  categories: CustomerCategory[]
  activeCategory: number | null
  productSearch: string
  cartPending: boolean
  pendingCartProductIds: Set<number>
  visibleProducts: CustomerProduct[]
  t: (text: string) => string
  money: (value: unknown) => string
}>()

const emit = defineEmits<{
  'update:activeCategory': [categoryId: number | null]
  'update:productSearch': [search: string]
  openCertificate: [certificate: { fileUuid: string }]
  addToCart: [product: CustomerProduct]
}>()

function productCoas(product: CustomerProduct): CustomerCoa[] {
  return product.coas || []
}
</script>

<template>
  <main class="catalog-section">
    <section class="catalog-promise" aria-label="Factory and order commitments">
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
              <button
                v-for="coa in productCoas(product)"
                :key="coa.fileUuid"
                class="product-coa"
                type="button"
                :aria-label="`${t('View COA')}: ${product.category}, ${coa.name || product.name}, ${t('Lot')} ${coa.lot || '—'}${coa.testDate ? `, ${coa.testDate}` : ''}`"
                @click="emit('openCertificate', { fileUuid: coa.fileUuid })"
              >
                <svg class="product-coa-icon" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M5 2.75h6.2L15 6.6v10.65H5z"/><path d="M11 2.75V7h4M7.5 10h5M7.5 13h5"/></svg>
                <span class="product-coa-copy" :title="`${t('View COA')} · ${product.category} · ${coa.name || product.name} · ${t('Lot')} ${coa.lot || '—'}${coa.testDate ? ` · ${coa.testDate}` : ''}`">{{ t('View COA') }} · {{ product.category }} · {{ coa.name || product.name }} · {{ t('Lot') }} {{ coa.lot || '—' }}{{ coa.testDate ? ` · ${coa.testDate}` : '' }}</span>
              </button>
            </div>
          </div>
          <div v-if="product.quantityPrices?.length" class="product-quantity-prices">
            <p>{{ t('Quantity pricing') }}</p>
            <div v-for="band in product.quantityPrices" :key="band.minQuantity"><span>{{ band.maxQuantity === null ? `${band.minQuantity}+` : `${band.minQuantity}–${band.maxQuantity}` }} {{ t('units') }}</span><strong>{{ money(band.unitPrice) }} <small>/ {{ t('unit') }}</small></strong></div>
          </div>
          <div class="card-bottom">
            <span class="price-text">{{ money(product.price) }}</span>
            <button class="addcart-btn" type="button" :disabled="!product.stock || cartPending || pendingCartProductIds.has(product.id)" :aria-busy="pendingCartProductIds.has(product.id)" :aria-label="t('Add to cart')" @click="emit('addToCart', product)">
              <svg class="cart-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M4 5h2l1.5 10.5h10L20 8H7"/><circle cx="9.5" cy="19" r="1"/><circle cx="17" cy="19" r="1"/></svg>
            </button>
          </div>
        </article>
        <div v-if="!visibleProducts.length" class="catalog-empty"><p>{{ t('No products match your search. Try another name or category.') }}</p></div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.product-quantity-prices { border-top: 1px solid #d8e1da; margin-top: 14px; padding-top: 10px; font-size: 13px; font-variant-numeric: tabular-nums; }
.product-quantity-prices p { margin: 0 0 8px; color: #667a72; }
.product-quantity-prices div { display: flex; justify-content: space-between; gap: 12px; margin: 6px 0; }
.product-quantity-prices strong { color: #1f5148; white-space: nowrap; }
.product-quantity-prices small { font-weight: normal; }
</style>
