import { SessionDeliverablesView } from '../api/types';
import { labelDeliverableType } from './presentation';

const REQUIRED_DELIVERABLE_TYPES = ['WORK_TICKET', 'WORK_BREAKDOWN', 'PRICE_ESTIMATE'] as const;

type DeliverableType = (typeof REQUIRED_DELIVERABLE_TYPES)[number];

export type SessionCloseDeliverableAdvisory = {
  pendingTypes: DeliverableType[];
};

export function getSessionCloseDeliverableAdvisory(
  deliverables: SessionDeliverablesView | null | undefined
): SessionCloseDeliverableAdvisory | null {
  if (deliverables == null) {
    return null;
  }

  const latestByType = new Map<string, SessionDeliverablesView['deliverables'][number]>();
  for (const deliverable of deliverables.deliverables ?? []) {
    if (!latestByType.has(deliverable.type)) {
      latestByType.set(deliverable.type, deliverable);
    }
  }

  const pendingTypes = REQUIRED_DELIVERABLE_TYPES.filter((type) => {
    const deliverable = latestByType.get(type);
    return deliverable == null || deliverable.status !== 'SUCCEEDED';
  });

  if (pendingTypes.length === 0) {
    return null;
  }

  return {
    pendingTypes,
  };
}

export function describeSessionCloseDeliverableAdvisory(
  advisory: SessionCloseDeliverableAdvisory | null
) {
  if (advisory == null) {
    return null;
  }

  return `Faltan por generar correctamente: ${formatDeliverableTypeList(advisory.pendingTypes)}.`;
}

function formatDeliverableTypeList(types: readonly DeliverableType[]) {
  return types.map((type) => labelDeliverableType(type)).join(', ');
}
