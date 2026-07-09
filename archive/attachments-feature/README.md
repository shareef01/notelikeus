# Archived: Image attachments feature

Removed from the app on 2026-07-09. Spark (free) Firebase plan does not support
Cloud Storage; embedding images in Firestore was deferred.

## What was archived

| File | Role |
|------|------|
| `src/AttachmentStorage.kt` | Local image file persistence |
| `src/ImageHeader.kt` | Editor image preview + remove |
| `src/FirebaseAttachmentSync.kt` | Firebase Storage upload/download |
| `storage.rules` | Firebase Storage security rules |

## What was removed from active code

- Editor: image picker, `ImageHeader`, add-attachment bottom bar button
- Note list: image thumbnail on `NoteCard`
- Backup export/import: Base64 image embedding
- Cloud sync: attachment fields and Firebase Storage dependency
- Coil image loading dependency (only used for attachments)

## Database

The `attachments` Room table and `Attachment` model remain for schema compatibility.
New saves persist notes with no attachments; existing attachment rows are cleared on
the next note edit.

## To restore later

1. Copy archived `src/` files back into their original packages under `app/src/main/java/`.
2. Re-add `firebase-storage` and `coil-compose` in Gradle.
3. Restore UI wiring in `EditorScreen`, `EditorBottomBar`, and `NoteCard`.
4. Restore backup and `FirebaseNoteSync` attachment handling (see git history).
5. Enable Firebase Storage (Blaze plan) and publish `storage.rules`.

Original paths:

- `com.aus.notelikeus.data.local.AttachmentStorage`
- `com.aus.notelikeus.ui.editor.components.ImageHeader`
- `com.aus.notelikeus.data.remote.FirebaseAttachmentSync`
