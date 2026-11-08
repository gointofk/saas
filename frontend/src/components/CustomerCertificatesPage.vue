<script setup lang="ts">
import { computed } from 'vue'
import type { CustomerCertificate } from '../types/customer'

const props = defineProps<{
  items: CustomerCertificate[]
  search: string
  t: (text: string) => string
}>()

const visibleItems = computed(() => {
  const query = props.search.trim().toLocaleLowerCase()
  if (!query) return props.items

  return props.items.filter(certificate => [
    certificate.productName,
    certificate.sku,
    certificate.name,
    certificate.fileName,
    certificate.lot,
    certificate.purity,
    certificate.fill,
    certificate.testDate,
  ].some(value => value?.toLocaleLowerCase().includes(query)))
})

const emit = defineEmits<{
  'update:search': [search: string]
}>()
</script>

<template>
  <main class="container customer-page-container">
    <div class="page-heading">
      <h1>{{ t('Certificates of Analysis') }}</h1>
      <p class="certificate-page-description">{{ t('Documentation by product and lot. Each certificate corresponds to a specific lot and test date.') }}</p>
    </div>
    <label class="search-box">
      <span class="sr-only">{{ t('Search certificates') }}</span>
      <input autocomplete="off"
        :value="search"
        type="search"
        :placeholder="t('Search by product, SKU, or lot...')"
        @input="emit('update:search', ($event.target as HTMLInputElement).value)"
      >
    </label>
    <div class="result-count">{{ visibleItems.length }}{{ t('certificates') }}</div>
    <div class="table-card table-scroll">
      <table class="customer-table">
        <thead>
          <tr><th>{{ t('Product') }}</th><th>{{ t('SKU') }}</th><th>{{ t('Lot') }}</th><th>{{ t('Purity') }}</th><th>{{ t('Fill') }}</th><th>{{ t('Test Date') }}</th><th>{{ t('Certificate') }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="certificate in visibleItems" :key="certificate.id">
            <td>{{ certificate.productName || certificate.name }}</td>
            <td>{{ certificate.sku || '—' }}</td>
            <td>{{ certificate.lot || '—' }}</td>
            <td>{{ certificate.purity || '—' }}</td>
            <td>{{ certificate.fill || '—' }}</td>
            <td>{{ certificate.testDate || '—' }}</td>
            <td><a class="link-button" :href="certificate.publicUrl" target="_blank" rel="noopener noreferrer"><svg class="certificate-action-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M6.75 3.75h7.5L18 7.5v12.75H6.75z"/><path d="M14.25 3.75V7.5H18M9.75 12h5.25M9.75 15h5.25"/></svg>{{ t('View / download ↗') }}</a></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!items.length" class="catalog-empty">{{ t('No certificates found.') }}</div>
    </div>
  </main>
</template>
