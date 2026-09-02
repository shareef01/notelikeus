/** Maps Firestore note documents to the existing Notelikeus backup JSON (version 3). */

export const BACKUP_VERSION = 3;

function asInteger(value, fallback) {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    if (Number.isInteger(parsed)) return parsed;
  }
  return fallback;
}

function asString(value, fallback = '') {
  return typeof value === 'string' ? value : fallback;
}

function asBoolean(value, fallback = false) {
  return typeof value === 'boolean' ? value : fallback;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function timestampMillis(value) {
  if (value == null) return null;
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'object') {
    const seconds = value.seconds ?? value._seconds;
    const nanos = value.nanoseconds ?? value._nanoseconds ?? 0;
    if (typeof seconds === 'number') {
      return seconds * 1000 + Math.floor(nanos / 1_000_000);
    }
    if (typeof value.toMillis === 'function') return Math.trunc(value.toMillis());
  }
  return null;
}

export function firestoreDocToBackupNote(documentId, data = {}) {
  const parsedId = Number(documentId);
  const hasNumericId = Number.isInteger(parsedId) && parsedId > 0;
  const localId = hasNumericId ? parsedId : asInteger(data.localId, Date.now());
  const labels = [];
  for (const entry of asArray(data.labels)) {
    if (typeof entry === 'string' && entry.trim()) labels.push(entry.trim());
    else if (entry && typeof entry === 'object') {
      const name = asString(entry.name).trim();
      if (name) labels.push(name);
    }
  }
  const note = {
    id: localId,
    title: asString(data.title),
    content: asString(data.content),
    timestamp: asInteger(data.timestamp, Date.now()),
    color: asInteger(data.color, 0xff1a1a1a | 0),
    isPinned: asBoolean(data.isPinned),
    isArchived: asBoolean(data.isArchived),
    isTrashed: asBoolean(data.isTrashed),
    position: asInteger(data.position, 0),
    labels,
    checklist: asArray(data.checklist).map((raw, index) => {
      const item = raw && typeof raw === 'object' ? raw : {};
      return {
        text: asString(item.text),
        isChecked: asBoolean(item.isChecked),
        position: asInteger(item.position, index),
      };
    }),
  };
  const reminder = asInteger(data.reminderTimestamp, NaN);
  if (Number.isFinite(reminder)) note.reminderTimestamp = reminder;
  const serverUpdatedAt = timestampMillis(data.serverUpdatedAt);
  if (serverUpdatedAt != null) note.serverUpdatedAt = serverUpdatedAt;
  return note;
}

export function buildBackupPayload(uid, notes, exportedAt = Date.now()) {
  const labelNames = new Set();
  for (const note of notes) {
    for (const name of note.labels ?? []) labelNames.add(name);
  }
  return {
    version: BACKUP_VERSION,
    exportedAt,
    app: 'Notelikeus',
    appVersion: '1.0.0 (ops-export)',
    sourceUid: uid,
    labels: [...labelNames].sort().map((name) => ({
      id: `label-${name.toLowerCase()}`,
      name,
    })),
    notes,
  };
}
