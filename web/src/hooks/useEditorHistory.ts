import { useEffect } from 'react';
import { useUiStore, type EditorRoute } from '@/store/uiStore';
import { useToastStore } from '@/store/toastStore';

const KEY = 'notelikeusEditor';
let backHandler: (() => Promise<void>) | null = null;
let saveBeforeSwitch: (() => Promise<boolean>) | null = null;
export function recordEditorHistoryNoteId(noteId: string | null | undefined) {
  if (noteId && routeFromHistory()) window.history.replaceState({...window.history.state,
    [KEY]: {mode: 'edit', noteId}}, '', window.location.href);
}
export function registerEditorBackHandler(handler: () => Promise<void>, save: () => Promise<boolean>) {
  backHandler = handler;
  saveBeforeSwitch = save;
  return () => {
    if (backHandler === handler) {
      backHandler = null;
      saveBeforeSwitch = null;
    }
  };
}

/** Docked notes can switch without closing first. Keep a draft open if its local write fails. */
export async function navigateEditor(next: () => void) {
  const current = useUiStore.getState().editorRoute;
  try {
    if (saveBeforeSwitch && !(await saveBeforeSwitch())) return;
    if (useUiStore.getState().editorRoute === current) next();
  } catch {
    useToastStore.getState().show('Could not save your note. Try again before switching.', 'error');
  }
}
function routeFromHistory(): EditorRoute | null {
  const route = window.history.state?.[KEY];
  return route?.mode === 'new' || (route?.mode === 'edit' && typeof route.noteId === 'string') ? route : null;
}
function push(route: EditorRoute) {
  window.history.pushState({...window.history.state, [KEY]: route}, '', window.location.href);
}

/** Keep an in-app Back destination and use the editor's durable-save guard. */
export function useEditorHistory() {
  useEffect(() => {
    let pending = false;
    // The editor's body lock restores the library position. Native restoration
    // can run afterwards and replace it with the locked page's zero offset.
    const previousScrollRestoration = window.history.scrollRestoration;
    window.history.scrollRestoration = 'manual';
    const unsubscribe = useUiStore.subscribe((state, previous) => {
      if (state.editorRoute === previous.editorRoute) return;
      if (state.editorRoute.mode !== 'closed') {
        const stored = routeFromHistory();
        if (JSON.stringify(stored) !== JSON.stringify(state.editorRoute)) push(state.editorRoute);
      } else if (routeFromHistory()) window.history.back();
    });
    const onPop = () => {
      const current = useUiStore.getState().editorRoute;
      const target = routeFromHistory();
      // A workspace route can leave the same docked note mounted underneath it.
      if (JSON.stringify(current) === JSON.stringify(target)) return;
      if (current.mode === 'closed') {
        if (target) useUiStore.setState({editorRoute: target});
        return;
      }
      // Immediately restore the entry while saving, so repeated Back presses
      // cannot navigate away from a draft whose write has not completed.
      push(current);
      if (pending) return;
      pending = true;
      const close = backHandler ?? (async () => useUiStore.getState().closeEditor());
      void close().catch(() => {
        useToastStore.getState().show('Could not save your note. Try again before closing.', 'error');
      }).finally(() => { pending = false; });
    };
    const initial = useUiStore.getState().editorRoute;
    if (initial.mode !== 'closed' && !routeFromHistory()) push(initial);
    window.addEventListener('popstate', onPop);
    return () => {
      unsubscribe(); window.removeEventListener('popstate', onPop);
      window.history.scrollRestoration = previousScrollRestoration;
    };
  }, []);
}
