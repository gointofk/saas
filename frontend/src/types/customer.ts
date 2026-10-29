import type { QuantityPriceBand } from '../lib/quantity-pricing'
import type { CustomerOrderAction, FulfillmentSummary, OrderParcel } from '../lib/api'

export type CustomerCoa = {
  name: string | null
  fileUuid: string
  lot: string | null
  testDate: string | null
}

export type CustomerProduct = {
  id: number
  categoryId: number
  category: string
  sku: string
  name: string
  description: string | null
  price: number | string
  quantityPrices?: QuantityPriceBand[]
  stock: number
  coas?: CustomerCoa[]
}

export type CustomerCategory = { id: number; name: string }

export type CustomerCertificate = {
  id: number
  productName?: string | null
  name?: string | null
  sku?: string | null
  fileName?: string | null
  lot?: string | null
  purity?: string | null
  fill?: string | null
  testDate?: string | null
  fileUuid?: string
}

export type CustomerCartItem = {
  productId: number
  quantity: number
  unitPrice: number | string
  lineTotal: number | string
  invalidReason?: string | null
  product: { name: string; sku: string; stock: number; active?: boolean }
}

export type CustomerCart = {
  items: CustomerCartItem[]
  subtotal: number | string
  shippingFee: number | string
  total: number | string
}

export type CustomerAddress = {
  id: number
  name: string
  street: string
  apt?: string | null
  city: string
  region?: string | null
  postalCode: string
  phone: string
  isDefault: boolean
}

export type CustomerProfile = {
  id?: number
  name?: string
  email?: string
  userCode?: string
}

export type CustomerReferral = {
  id: number
  name: string
  email?: string
  userCode?: string
  currentRelation?: boolean
  historicalRateCount?: number
  commissionRate?: number | string
  historicalRate?: number | string
  pendingBase?: number | string
  pendingCommission?: number | string
  settledCommissionAmount?: number | string
}

export type CustomerReferralSummary = {
  currentCount?: number
  pendingBase?: number | string
  pendingCommission?: number | string
  orderCount?: number
  pendingVerificationOrderCount?: number
  pendingPaymentOrderCount?: number
  paidOrderCount?: number
  commissionAmount?: number | string
  estimatedCommissionAmount?: number | string
  payableCommissionAmount?: number | string
  settledCommissionAmount?: number | string
}

export type CustomerPaymentChannel = {
  id: number
  name: string
  currencyCode: string
  network: string
  receivingAddress: string
  instructions?: string | null
  additionalRate?: number | string | null
  qrFileUuid: string
}

export type CustomerOrderQuote = {
  quoteToken: string
  items: Array<{
    productId: number
    quantity: number
    name: string
    unitPrice: number | string
    lineTotal: number | string
  }>
  subtotal: number | string
  shippingFee: number | string
  total: number | string
}

export type CustomerOrderItem = {
  orderItemId: number
  productId: number
  name: string
  quantity: number
  quantityPrices?: QuantityPriceBand[]
  unitPrice: number | string
  lineTotal: number | string
  productType?: string
}

export type CustomerOrder = {
  amountLocked?: boolean
  fulfillmentSummary?: FulfillmentSummary
  parcels?: OrderParcel[]
  allowedActions?: CustomerOrderAction[]
  id: number
  orderNo: string
  createdAt: string
  status: string
  paymentStatus: string
  total: number | string
  paidAmount: number | string
  remainingAmount: number | string
  discountAmount?: number | string
  grossTotal?: number | string
  subtotal?: number | string
  shippingFee?: number | string
  buyerNote?: string
  items?: CustomerOrderItem[]
  updatedAt?: string
  address?: { id?: number; name?: string; street?: string; city?: string }
}

export type CustomerOrderSummary = {
  allOrders?: number
  received?: number
  transit?: number
  pendingShip?: number
  payableOrders?: number
  payableTotal?: number | string
  cancelled?: number
}
