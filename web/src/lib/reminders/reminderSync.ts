import { rescheduleAllReminders } from '@/lib/reminders/reminderScheduler';
import { notesContentEqual } from '@/lib/notes/noteEquality';
import { useNotesStore } from '@/store/notesStore';

let reminderSyncStarted = false;

/** Module-level subscription — avoids React render loops from note changes in App. */
export function ensureReminderSync() {
  if (reminderSyncStarted) return;
  reminderSyncStarted = true;

  useNotesStore.subscribe((state, previous) => {
    if (!notesContentEqual(state.notes, previous.notes)) {
      rescheduleAllReminders(state.notes);
    }
  });
}
