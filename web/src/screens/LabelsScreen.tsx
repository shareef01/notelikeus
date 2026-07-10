import { useLabelManagement } from '@/hooks/useLabelManagement';
import { CloseIcon, TrashIcon, AddIcon } from '@/components/icons/Icons';
import { useState } from 'react';
import type { Label } from '@/types/label';

interface LabelsScreenProps {
  onClose: () => void;
}

/**
 * Elite Label Management (Web)
 * Geometric Discipline: Absolute 20px radius and Backdrop-Blur.
 */
export function LabelsScreen({ onClose }: LabelsScreenProps) {
  const { labels, createLabel, updateLabel, deleteLabel } = useLabelManagement();
  const [newLabelName, setNewLabelName] = useState('');
  const [labelToEdit, setLabelToEdit] = useState<Label | null>(null);
  const [editName, setEditName] = useState('');

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    if (newLabelName.trim()) {
      createLabel(newLabelName.trim());
      setNewLabelName('');
    }
  };

  const handleStartEdit = (label: Label) => {
    setLabelToEdit(label);
    setEditName(label.name);
  };

  const handleUpdate = () => {
    if (labelToEdit && editName.trim() && editName !== labelToEdit.name) {
      updateLabel(labelToEdit.id, editName.trim());
    }
    setLabelToEdit(null);
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-true-black animate-in fade-in duration-300">
      <header className="flex items-center justify-between px-4 py-4 lg:px-6">
        <h2 className="text-xl font-bold tracking-tight text-brand-primary">Edit labels</h2>
        <button
          onClick={onClose}
          className="flex size-10 items-center justify-center rounded-full hover:bg-white/5 transition-colors"
        >
          <CloseIcon size={24} />
        </button>
      </header>

      <div className="flex-1 overflow-y-auto px-4 lg:px-6 pb-24">
        <form onSubmit={handleCreate} className="mb-8 flex items-center gap-3 border-b border-brand-outline pb-6 pt-2">
          <div className="flex size-10 items-center justify-center text-brand-primary/40">
            <AddIcon size={24} />
          </div>
          <input
            type="text"
            value={newLabelName}
            onChange={(e) => setNewLabelName(e.target.value)}
            placeholder="Create new label"
            className="flex-1 bg-transparent text-lg outline-none placeholder:text-brand-muted/40"
          />
          {newLabelName.trim() ? (
            <button
              type="submit"
              className="rounded-note px-4 py-2 text-sm font-bold text-brand-primary transition-colors hover:bg-white/5"
            >
              Create
            </button>
          ) : null}
        </form>

        {labels.length === 0 ? (
          <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
            <p className="text-[18px] font-bold tracking-tight text-brand-primary opacity-80">
              No labels yet
            </p>
            <p className="mt-2 text-[14px] font-medium leading-[1.4em] text-brand-muted opacity-65">
              Create labels to organize your notes
            </p>
          </div>
        ) : (
          <div className="space-y-0">
            {labels.map((label, index) => (
              <div key={label.id}>
                <div className="flex min-h-[56px] items-center gap-4 rounded-note px-4 transition-colors hover:bg-white/5">
                  <span className="text-xl opacity-40">🏷️</span>

                  {labelToEdit?.id === label.id ? (
                    <input
                      autoFocus
                      type="text"
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                      onBlur={handleUpdate}
                      onKeyDown={(e) => e.key === 'Enter' && handleUpdate()}
                      className="flex-1 border-b border-brand-primary/30 bg-transparent text-base outline-none"
                    />
                  ) : (
                    <button
                      onClick={() => handleStartEdit(label)}
                      className="flex-1 truncate text-left text-base text-brand-primary"
                    >
                      {label.name}
                    </button>
                  )}

                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => deleteLabel(label.id)}
                      className="flex size-10 items-center justify-center rounded-full text-brand-muted transition-colors hover:bg-red-500/10 hover:text-red-400"
                      aria-label="Delete label"
                    >
                      <TrashIcon size={20} />
                    </button>
                  </div>
                </div>
                {index < labels.length - 1 ? (
                  <div className="ml-14 h-px bg-brand-outline/45" />
                ) : null}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
