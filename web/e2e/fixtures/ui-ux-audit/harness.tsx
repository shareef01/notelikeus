import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import '@/styles/globals.css';
import '@fontsource/inter/400.css';
import '@fontsource/inter/600.css';
import { CloudWipeNotice } from '@/components/notes/CloudWipeNotice';
import { useNotesStore } from '@/store/notesStore';
import { EmailCodeSignIn } from '@/components/auth/EmailCodeSignIn';
import { ProfileSheet } from '@/components/settings/ProfileSheet';
import { DeleteAccountDialog } from '@/components/settings/DeleteAccountDialog';
import { SignOutDialog } from '@/components/settings/SignOutDialog';
import { ConfirmDialog } from '@/components/ui/ModalDialog';
import { PrivacyPolicyDialog } from '@/components/settings/PrivacyPolicyDialog';
import type { ThemePreference } from '@/store/settingsStore';
import { ConflictReviewDialog } from '@/components/notes/ConflictReviewDialog';
import { createEmptyNote } from '@/types/note';
import { useUiStore } from '@/store/uiStore';

const query = new URLSearchParams(location.search);
const base = query.get('theme') === 'dark' ? 'dark' : 'light';
document.documentElement.className = `theme-${base} ${base === 'dark' ? 'dark' : ''}`;
if (query.get('surface') === 'cloud-wipe') useNotesStore.getState().setCloudWipeNotice({ generation: 1, localNoteCount: 3 });
if (query.get('surface') === 'conflict') useNotesStore.getState().setNotes([
  createEmptyNote({id:'audit-original', title:'Audit comparison', content:'Original text\n'.repeat(12)}),
  createEmptyNote({id:'audit-copy', title:'Audit comparison (conflicted copy)', content:'Edited text\n'.repeat(12)}),
]);
if (query.get('surface') === 'conflict') useUiStore.getState().openConflictReview('audit-copy');
const noop = () => {};
function Fixture() {
  const [open, setOpen] = useState(true);
  const [theme, setTheme] = useState<ThemePreference>({ base, accent: 'neutral', amoled: false });
  const surface = query.get('surface');
  const close = () => setOpen(false);
  if (surface === 'conflict') return <div onClickCapture={event => {event.preventDefault(); event.stopPropagation();}}><ConflictReviewDialog /></div>;
  if (surface === 'email-code') return <main className="mx-auto max-w-md bg-true-surface p-6"><EmailCodeSignIn submitLabel="Sign in" /></main>;
  if (surface === 'cloud-wipe') return <main className="min-h-screen bg-true-surface" onClickCapture={event => { event.preventDefault(); event.stopPropagation(); }}><CloudWipeNotice userId="audit-no-network-actions" /></main>;
  if (surface === 'delete-account') return <DeleteAccountDialog open={open} onCancel={close} onConfirm={async () => {}} onExportBackup={async () => ({ complete: false, included: 2, total: 12 })} />;
  if (surface === 'sign-out') return <SignOutDialog open={open} onCancel={close} onSignOut={noop} onSignOutAndDelete={noop} />;
  if (surface === 'privacy') return <PrivacyPolicyDialog open={open} onClose={close} />;
  if (surface === 'long-confirm') return <ConfirmDialog open={open} onCancel={close} onConfirm={noop} title="Delete label?" description={'A long label name '.repeat(60)} confirmLabel="Delete" />;
  return <ProfileSheet open={open} onClose={close} noteCount={42} viewColumns={2} sortOrder="newest" onViewColumnsCycle={noop} onSortOrderSelect={noop} theme={theme} onThemeBaseChange={value => setTheme({ ...theme, base: value })} onAccentChange={value => setTheme({ ...theme, accent: value })} onAmoledChange={value => setTheme({ ...theme, amoled: value })} isSignedIn isGuest={false} userEmail="audit@example.invalid" syncStatus="online" onExportBackup={noop} onExportCompleteBackup={noop} onExportDeviceBackup={noop} onShowDiagnostics={noop} onImportBackup={noop} onPrivacyPolicy={noop} onSignIn={noop} onSignUp={noop} onSignOut={noop} onDeleteAccount={noop} />;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
