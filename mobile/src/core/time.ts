export function formatDuration(diffMs: number) {
  const totalSeconds = Math.max(1, Math.round(diffMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }
  return `${seconds}s`;
}

export function formatDurationSince(value: string | null | undefined, now = Date.now()) {
  if (!value) {
    return null;
  }
  const diffMs = Math.max(0, now - new Date(value).getTime());
  return formatDuration(diffMs);
}

export function formatRelativeTime(value: string | null | undefined, now = Date.now()) {
  if (!value) {
    return null;
  }
  const diffMs = Math.max(0, now - new Date(value).getTime());
  if (diffMs < 5000) {
    return 'hace unos segundos';
  }
  return `hace ${formatDuration(diffMs)}`;
}

export function formatAbsoluteAndRelativeTime(value: string | null | undefined, now = Date.now()) {
  if (!value) {
    return null;
  }
  const timestamp = new Date(value);
  const relative = formatRelativeTime(value, now);
  return relative
    ? `${timestamp.toLocaleTimeString()} · ${relative}`
    : timestamp.toLocaleTimeString();
}
