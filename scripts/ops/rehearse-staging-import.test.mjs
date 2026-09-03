import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assertApplyNoteChangeAccepted,
  rpcArgsFromBackupNote,
} from './rehearse-staging-import.mjs';

test('accepts apply_note_change status applied', () => {
  assert.doesNotThrow(() => assertApplyNoteChangeAccepted({ status: 'applied', revision: 10001 }));
  assert.doesNotThrow(() => assertApplyNoteChangeAccepted({ status: 'ok' }));
  assert.doesNotThrow(() => assertApplyNoteChangeAccepted({}));
});

test('rejects conflict and unknown apply_note_change status', () => {
  assert.throws(() => assertApplyNoteChangeAccepted({ status: 'conflict' }), /conflict/);
  assert.throws(() => assertApplyNoteChangeAccepted({ status: 'nope' }), /rejected/);
});

test('maps backup notes to apply_note_change RPC args', () => {
  const args = rpcArgsFromBackupNote({
    id: 101,
    title: 'Migration rehearsal',
    content: 'Body',
    timestamp: 1,
    color: -14474606,
    isPinned: true,
    labels: ['rehearsal'],
    checklist: [{ text: 'export dump', isChecked: true, position: 0 }],
  });
  assert.equal(args.p_note_id, '101');
  assert.equal(args.p_local_id, 101);
  assert.equal(args.p_base_revision, null);
  assert.equal(args.p_labels[0].name, 'rehearsal');
});
