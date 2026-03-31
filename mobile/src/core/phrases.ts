export function buildPortfolioStatusCommand() {
  return 'dime el estado de los proyectos';
}

export function buildProjectStatusCommand(projectName?: string | null) {
  return projectName ? `dime como esta ${projectName}` : 'dime como esta el proyecto activo';
}

export function buildActivateProjectCommand(projectName: string) {
  return `vamos a trabajar en ${projectName}`;
}

export function buildOpenSessionCommand(projectName?: string | null, title?: string | null) {
  const normalizedTitle = title?.trim();
  if (normalizedTitle) {
    return projectName
      ? `abre una sesion para ${projectName}: ${normalizedTitle}`
      : `abre una sesion: ${normalizedTitle}`;
  }
  return projectName
    ? `abre una sesion para ${projectName}`
    : 'abre una sesion';
}

export function buildSessionSummaryCommand() {
  return 'dime el estado de la sesion';
}

export function buildPublishCommand() {
  return 'haz publish';
}

export function buildSyncPullRequestCommand() {
  return 'haz sync de la pr';
}

export function buildSessionDeliverablesCommand() {
  return 'muestrame los deliverables';
}

export function buildGenerateDeliverableCommand(deliverableType: string) {
  return `genera ${humanizeDeliverableType(deliverableType)}`;
}

export function buildCloseSessionCommand() {
  return 'cierra la sesion';
}

export function humanizeDeliverableType(deliverableType: string) {
  return deliverableType
    .replace(/_/g, ' ')
    .toLowerCase();
}
