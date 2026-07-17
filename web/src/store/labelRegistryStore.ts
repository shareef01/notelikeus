import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { labelFromName } from '@/types/label';
import type { Label } from '@/types/label';

interface LabelRegistryState {
  /** Labels created explicitly (e.g. from the labels screen) before any note references them. */
  labels: Record<string, Label>;
  addLabel: (name: string) => void;
  renameLabel: (id: string, name: string) => void;
  removeLabel: (id: string) => void;
  reset: () => void;
}

export const useLabelRegistryStore = create<LabelRegistryState>()(
  persist(
    (set) => ({
      labels: {},
      addLabel: (name) =>
        set((state) => {
          const label = labelFromName(name);
          if (state.labels[label.id]) return state;
          return { labels: { ...state.labels, [label.id]: label } };
        }),
      renameLabel: (id, name) =>
        set((state) => {
          if (!(id in state.labels)) return state;
          return { labels: { ...state.labels, [id]: labelFromName(name, id) } };
        }),
      removeLabel: (id) =>
        set((state) => {
          if (!(id in state.labels)) return state;
          const rest = { ...state.labels };
          delete rest[id];
          return { labels: rest };
        }),
      reset: () => set({ labels: {} }),
    }),
    {
      name: 'notelikeus-label-registry',
      skipHydration: true,
    },
  ),
);
