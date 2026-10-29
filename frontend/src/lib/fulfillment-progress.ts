import type { FulfillmentSummary } from './api'

export type FulfillmentStageKey = 'pending' | 'packed' | 'shipped' | 'received' | 'completed'
export type PartialFulfillmentStage = 'packed' | 'shipped' | 'received'
export type FulfillmentStageState = 'done' | 'current' | 'partial' | 'upcoming' | 'stopped'

const stages: FulfillmentStageKey[] = ['pending', 'packed', 'shipped', 'received', 'completed']

export function partialFulfillmentStage(summary?: FulfillmentSummary): PartialFulfillmentStage | null {
  if (summary?.receiptStatus === 'partial') return 'received'
  if (summary?.shippingStatus === 'partial') return 'shipped'
  if (summary?.packingStatus === 'partial') return 'packed'
  return null
}

export function fulfillmentStageState(status: string, summary: FulfillmentSummary | undefined, stage: FulfillmentStageKey): FulfillmentStageState {
  if (status === 'cancelled') return 'stopped'
  if (status === 'completed') return 'done'

  const currentStage = partialFulfillmentStage(summary) ?? status
  if (stage === currentStage) return 'current'
  if (summary) {
    if (stage === 'pending') return summary.started ? 'done' : 'upcoming'
    if (stage === 'completed') return 'upcoming'
    const progress = stage === 'packed' ? summary.packingStatus : stage === 'shipped' ? summary.shippingStatus : summary.receiptStatus
    return progress === 'all' ? 'done' : progress === 'partial' ? 'partial' : 'upcoming'
  }

  return stages.indexOf(stage) < stages.indexOf(status as FulfillmentStageKey) ? 'done' : 'upcoming'
}
