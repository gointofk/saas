<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppModal from './AppModal.vue'
import { merchantApi, type Merchant, type MerchantPage, type PublicMenu } from '../lib/api'
import { useSellerTranslation } from '../lib/seller-locale'
const t = useSellerTranslation()
const emit = defineEmits<{ saved: [] }>()
const result = ref<MerchantPage>({ items: [], page: 1, total: 0, totalPages: 1 })
const pageNumbers = computed(() => {
  const pages = result.value.totalPages || 1
  if (pages <= 5) return Array.from({ length: pages }, (_, index) => index + 1)
  const first = Math.min(Math.max(result.value.page - 2, 1), pages - 4)
  return Array.from({ length: 5 }, (_, index) => first + index)
})
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const modal = ref(false)
const editing = ref<Merchant | null>(null)
const options: Array<{ code: PublicMenu; label: string }> = [
  { code: 'products', label: '商品管理' }, { code: 'certificates', label: 'COA 管理' }, { code: 'categories', label: '类目管理' },
]
const draft = reactive({ code: '', name: '', active: true, shippingFee: '0.00', permissions: [] as PublicMenu[], adminAccount: '', adminName: '', password: '', confirmation: '' })
function message(e: unknown) { return e instanceof Error ? e.message : 'Request failed.' }
async function load(page = result.value.page) {
  loading.value = true; error.value = ''
  try { result.value = await merchantApi.page(page) } catch (e) { error.value = message(e) } finally { loading.value = false }
}
function setPage(page: number) {
  if (loading.value || page < 1 || page > result.value.totalPages || page === result.value.page) return
  void load(page)
}
function open(item: Merchant | null = null) {
  editing.value = item; error.value = ''
  Object.assign(draft, { code: item?.code || '', name: item?.name || '', active: item?.active ?? true, shippingFee: String(item?.shippingFee ?? '0.00'), permissions: [...(item?.permissions || [])], adminAccount: '', adminName: '', password: '', confirmation: '' })
  modal.value = true
}
function close() { if (!saving.value) { modal.value = false; draft.password = ''; draft.confirmation = '' } }
async function save() {
  if (saving.value) return
  error.value = ''
  if (!draft.name.trim() || !/^\d+(\.\d{1,2})?$/.test(draft.shippingFee)) { error.value = t('请填写商户名称和有效运费。'); return }
  if (!editing.value && (draft.password.length < 12 || draft.password.length > 64 || draft.password !== draft.confirmation)) { error.value = t('密码须为 12–64 位，两次输入需一致。'); return }
  saving.value = true
  try {
    if (editing.value) await merchantApi.update(editing.value.id, { name: draft.name.trim(), active: draft.active, shippingFee: draft.shippingFee, permissions: draft.permissions, expectedUpdatedAt: editing.value.updatedAt })
    else await merchantApi.create({ code: draft.code.trim(), name: draft.name.trim(), shippingFee: draft.shippingFee, permissions: draft.permissions, adminAccount: draft.adminAccount.trim(), adminName: draft.adminName.trim(), password: draft.password, confirmation: draft.confirmation })
    modal.value = false; draft.password = ''; draft.confirmation = ''
    await load(); emit('saved')
  } catch (e) { error.value = message(e) } finally { saving.value = false }
}
onMounted(() => load())
</script>

<template>
  <section class="panel active">
    <p v-if="error && !modal" class="field-error" role="alert">{{ t(error) }}</p>
    <div class="card">
      <div class="card-head"><button class="btn" :disabled="loading" @click="open()">{{ t('新增商户') }}</button><button class="btn" :disabled="loading" @click="load()">{{ t('刷新') }}</button></div>
      <div class="card-body table-wrap" :aria-busy="loading">
        <p v-if="loading">{{ t('加载中…') }}</p>
        <table v-else class="admin-table"><thead><tr><th>{{ t('商户编号') }}</th><th>{{ t('商户名称') }}</th><th>{{ t('状态') }}</th><th>{{ t('运费') }}</th><th>{{ t('公共管理权限') }}</th><th>{{ t('操作') }}</th></tr></thead>
          <tbody><tr v-for="item in result.items" :key="item.id"><td>{{ item.code }}</td><td>{{ item.name }}</td><td>{{ t(item.active ? '启用' : '停用') }}</td><td>USD {{ item.shippingFee }}</td><td>{{ options.filter(option => item.permissions.includes(option.code)).map(option => t(option.label)).join(' / ') || '—' }}</td><td><button class="link-button" @click="open(item)">{{ t('编辑') }}</button></td></tr><tr v-if="!result.items.length"><td colspan="6">{{ t('暂无商户') }}</td></tr></tbody>
        </table>
      </div>
      <div v-if="result.total" class="admin-pagination">
        <span>{{ t('共 {0} 条 · 第 {1} / {2} 页', [result.total, result.page, result.totalPages]) }}</span>
        <div>
          <button type="button" :disabled="loading || result.page <= 1" @click="setPage(1)">«</button>
          <button type="button" :disabled="loading || result.page <= 1" @click="setPage(result.page - 1)">‹</button>
          <button v-if="pageNumbers[0] > 1" type="button" :disabled="loading" @click="setPage(1)">1</button>
          <span v-if="pageNumbers[0] > 2">…</span>
          <button v-for="page in pageNumbers" :key="page" type="button" :class="{ active: result.page === page }" :aria-current="result.page === page ? 'page' : undefined" :disabled="loading" @click="setPage(page)">{{ page }}</button>
          <span v-if="pageNumbers[pageNumbers.length - 1] < result.totalPages - 1">…</span>
          <button v-if="pageNumbers[pageNumbers.length - 1] < result.totalPages" type="button" :disabled="loading" @click="setPage(result.totalPages)">{{ result.totalPages }}</button>
          <button type="button" :disabled="loading || result.page >= result.totalPages" @click="setPage(result.page + 1)">›</button>
          <button type="button" :disabled="loading || result.page >= result.totalPages" @click="setPage(result.totalPages)">»</button>
        </div>
      </div>
    </div>
    <AppModal v-if="modal" :label="t(editing ? '编辑商户' : '新增商户')" content-class="seller-modal" @close="close">
      <form @submit.prevent="save">
        <h2 class="modal-title">{{ t(editing ? '编辑商户' : '新增商户') }}</h2>
        <fieldset :disabled="saving" class="merchant-fields">
          <div class="form-grid">
            <label class="form-group">{{ t('商户编号') }}<input v-model="draft.code" required pattern="[A-Za-z0-9_-]{2,32}" maxlength="32" :disabled="!!editing"></label>
            <label class="form-group">{{ t('商户名称') }}<input v-model="draft.name" required maxlength="100"></label>
            <label class="form-group">{{ t('运费') }} (USD)<input v-model="draft.shippingFee" required type="number" min="0" step="0.01"></label>
          </div>
          <label v-if="editing" class="checkbox-field"><input v-model="draft.active" type="checkbox">{{ t('启用') }}</label>
          <p v-if="editing && !draft.active" class="field-hint">{{ t('停用后，该商户的成员和买家均无法访问业务；历史数据保留。') }}</p>
          <p>{{ t('公共管理权限') }}</p>
          <label v-for="option in options" :key="option.code" class="checkbox-field"><input v-model="draft.permissions" type="checkbox" :value="option.code">{{ t(option.label) }}</label>
          <p class="field-hint">{{ t('公共资料修改会影响全部商户。商户获批后，成员还需对应菜单权限；撤销会同时移除成员的对应授权。') }}</p>
          <div v-if="!editing" class="form-grid">
            <label class="form-group">{{ t('初始管理员账号') }}<input v-model="draft.adminAccount" required maxlength="64" autocomplete="off"></label>
            <label class="form-group">{{ t('管理员姓名') }}<input v-model="draft.adminName" required maxlength="100"></label>
            <label class="form-group">{{ t('密码') }}<input v-model="draft.password" required type="password" minlength="12" maxlength="64" autocomplete="new-password"></label>
            <label class="form-group">{{ t('确认密码') }}<input v-model="draft.confirmation" required type="password" minlength="12" maxlength="64" autocomplete="new-password"></label>
          </div>
        </fieldset>
        <p v-if="error" class="field-error" role="alert">{{ t(error) }}</p>
        <div class="modal-actions"><button type="button" class="modal-cancel" :disabled="saving" @click="close">{{ t('取消') }}</button><button type="submit" class="modal-save" :disabled="saving">{{ t(saving ? '保存中…' : '保存') }}</button></div>
      </form>
    </AppModal>
  </section>
</template>
<style scoped>
.merchant-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } }
</style>
