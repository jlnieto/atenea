type PillTone = 'default' | 'good' | 'warning' | 'danger' | 'info';

function prettifyEnum(value: string) {
  return value
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ');
}

export function labelSessionStatus(value: string | null | undefined) {
  switch (value) {
    case 'OPEN':
      return 'Abierta';
    case 'CLOSED':
      return 'Cerrada';
    case 'CLOSING':
      return 'Cerrando';
    default:
      return value ? prettifyEnum(value) : 'Sin estado';
  }
}

export function toneSessionStatus(value: string | null | undefined): PillTone {
  switch (value) {
    case 'CLOSED':
      return 'good';
    case 'CLOSING':
      return 'warning';
    default:
      return 'default';
  }
}

export function labelPullRequestStatus(value: string | null | undefined) {
  switch (value) {
    case 'NOT_CREATED':
      return 'PR no creada';
    case 'OPEN':
      return 'PR abierta';
    case 'MERGED':
      return 'PR mergeada';
    case 'CLOSED':
      return 'PR cerrada';
    default:
      return value ? prettifyEnum(value) : 'Sin PR';
  }
}

export function tonePullRequestStatus(value: string | null | undefined): PillTone {
  switch (value) {
    case 'MERGED':
      return 'good';
    case 'OPEN':
      return 'info';
    case 'CLOSED':
      return 'warning';
    case 'NOT_CREATED':
    default:
      return 'default';
  }
}

export function labelBillingStatus(value: string | null | undefined) {
  switch (value) {
    case 'READY':
      return 'Lista';
    case 'BILLED':
      return 'Facturada';
    case 'PENDING':
      return 'Pendiente';
    default:
      return value ? prettifyEnum(value) : 'Sin estado';
  }
}

export function toneBillingStatus(value: string | null | undefined): PillTone {
  switch (value) {
    case 'BILLED':
      return 'good';
    case 'READY':
      return 'warning';
    default:
      return 'default';
  }
}

export function labelDeliverableStatus(value: string | null | undefined) {
  switch (value) {
    case 'PENDING':
      return 'Pendiente';
    case 'RUNNING':
      return 'En generación';
    case 'SUCCEEDED':
      return 'Generado';
    case 'FAILED':
      return 'Fallido';
    case 'SUPERSEDED':
      return 'Reemplazado';
    default:
      return value ? prettifyEnum(value) : 'Sin estado';
  }
}

export function labelDeliverableType(value: string | null | undefined) {
  switch (value) {
    case 'WORK_TICKET':
      return 'Ticket de trabajo';
    case 'WORK_BREAKDOWN':
      return 'Desglose de trabajo';
    case 'PRICE_ESTIMATE':
      return 'Presupuesto';
    default:
      return value ? prettifyEnum(value) : 'Entregable';
  }
}

export function toneDeliverableStatus(value: string | null | undefined): PillTone {
  switch (value) {
    case 'SUCCEEDED':
      return 'good';
    case 'RUNNING':
      return 'info';
    case 'FAILED':
      return 'danger';
    case 'PENDING':
    case 'SUPERSEDED':
    default:
      return 'warning';
  }
}

export function labelCoreCommandStatus(value: string | null | undefined) {
  switch (value) {
    case 'RECEIVED':
      return 'Recibido';
    case 'NEEDS_CLARIFICATION':
      return 'Necesita aclaración';
    case 'NEEDS_CONFIRMATION':
      return 'Necesita confirmación';
    case 'SUCCEEDED':
      return 'Completado';
    case 'REJECTED':
      return 'Rechazado';
    case 'FAILED':
      return 'Fallido';
    default:
      return value ? prettifyEnum(value) : 'Sin estado';
  }
}

export function toneCoreCommandStatus(value: string | null | undefined): PillTone {
  switch (value) {
    case 'SUCCEEDED':
      return 'good';
    case 'NEEDS_CONFIRMATION':
    case 'NEEDS_CLARIFICATION':
      return 'warning';
    case 'FAILED':
    case 'REJECTED':
      return 'danger';
    default:
      return 'default';
  }
}

export function labelCoreRiskLevel(value: string | null | undefined) {
  switch (value) {
    case 'READ':
      return 'Lectura';
    case 'SAFE_WRITE':
      return 'Escritura segura';
    case 'DESTRUCTIVE':
      return 'Destructivo';
    default:
      return value ? prettifyEnum(value) : 'Sin riesgo';
  }
}

export function toneCoreRiskLevel(value: string | null | undefined): PillTone {
  switch (value) {
    case 'READ':
      return 'default';
    case 'SAFE_WRITE':
      return 'warning';
    case 'DESTRUCTIVE':
      return 'danger';
    default:
      return 'default';
  }
}

export function labelInterpreterSource(value: string | null | undefined) {
  switch (value) {
    case 'DETERMINISTIC':
      return 'Determinista';
    case 'LLM':
      return 'Modelo';
    case 'DETERMINISTIC_FALLBACK':
      return 'Fallback determinista';
    default:
      return value ? prettifyEnum(value) : 'Sin fuente';
  }
}

export function labelCoreCapability(value: string | null | undefined) {
  switch (value) {
    case 'activate_project_context':
      return 'Activar proyecto';
    case 'get_project_overview':
      return 'Resumen de proyecto';
    case 'create_work_session':
      return 'Abrir sesión';
    case 'continue_work_session':
      return 'Continuar sesión';
    case 'publish_work_session':
      return 'Publicar sesión';
    case 'sync_work_session_pull_request':
      return 'Sincronizar PR';
    case 'get_work_session_summary':
      return 'Resumen de sesión';
    case 'get_session_deliverables':
      return 'Ver entregables';
    case 'generate_session_deliverable':
      return 'Generar entregable';
    case 'approve_session_deliverable':
      return 'Aprobar entregable';
    case 'mark_price_estimate_billed':
    case 'MARK_PRICE_ESTIMATE_BILLED':
      return 'Marcar facturado';
    case 'close_work_session':
      return 'Cerrar sesión';
    default:
      return value ? prettifyEnum(value) : 'Capacidad';
  }
}
