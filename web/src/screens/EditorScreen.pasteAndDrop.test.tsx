import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { EditorScreen } from '@/screens/EditorScreen';
import { useUiStore } from '@/store/uiStore';
import { useToastStore } from '@/store/toastStore';
import * as noteEditorHook from '@/hooks/useNoteEditor';
import * as attachmentConfig from '@/lib/attachments/attachmentConfig';

(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('@/hooks/useCloudSync', () => ({
  useCloudSync: () => ({ userId: 'test-user', isGuest: false, online: true }),
}));

vi.mock('@/lib/attachments/pendingAttachmentStore', () => ({
  isPendingAttachment: () => false,
}));

describe('Web Rapid Image Capture — EditorScreen paste and drag-and-drop', () => {
  let mockAddAttachment: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.restoreAllMocks();
    useUiStore.setState({ editorRoute: { mode: 'edit', noteId: 'note-1' } });
    mockAddAttachment = vi.fn().mockResolvedValue(undefined);

    vi.spyOn(attachmentConfig, 'isR2AttachmentsEnabled').mockReturnValue(true);

    vi.spyOn(noteEditorHook, 'useNoteEditor').mockReturnValue({
      state: {
        id: 'note-1',
        title: 'Test Note',
        content: 'Initial content',
        checklist: [],
        attachments: [],
        labels: [],
        color: 0,
        isPinned: false,
        isArchived: false,
        isTrashed: false,
        reminderTimestamp: null,
        timestamp: 1700000000000,
        lastSavedAt: null,
        isSaving: false,
        saveFailed: false,
      },
      setTitle: vi.fn(),
      setContent: vi.fn(),
      setContentSmart: vi.fn().mockReturnValue({
        nextContent: '',
        structureChanged: false,
        selectionStart: 0,
        selectionEnd: 0,
      }),
      applyContentFormatting: vi.fn(),
      setChecklist: vi.fn(),
      addChecklistItem: vi.fn(),
      updateChecklistItem: vi.fn(),
      removeChecklistItem: vi.fn(),
      convertContentToChecklist: vi.fn(),
      convertChecklistToContent: vi.fn(),
      addAttachment: mockAddAttachment,
      removeAttachment: vi.fn(),
      setColor: vi.fn(),
      togglePinned: vi.fn(),
      togglePin: vi.fn(),
      setArchived: vi.fn(),
      toggleArchive: vi.fn(),
      trashNote: vi.fn(),
      setReminder: vi.fn(),
      setReminderTimestamp: vi.fn(),
      clearReminder: vi.fn(),
      createLabel: vi.fn(),
      toggleLabel: vi.fn(),
      allLabels: [],
      flushSave: vi.fn().mockResolvedValue({ status: 'saved' }),
      discardChanges: vi.fn(),
    } as any);
  });

  function renderEditor() {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => {
      root.render(
        createElement(EditorScreen, {
          route: { mode: 'edit', noteId: 'note-1' },
        }),
      );
    });

    return {
      container,
      cleanup: () => {
        act(() => {
          root.unmount();
        });
        container.remove();
      },
    };
  }

  it('calls editor.addAttachment when pasting an image-only clipboard payload', async () => {
    const { container, cleanup } = renderEditor();

    const editorSurface = container.querySelector('[role="dialog"]');
    expect(editorSurface).toBeDefined();

    const imageFile = new File(['fake-png-bytes'], 'screenshot.png', { type: 'image/png' });
    let defaultPrevented = false;

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(pasteEvent, 'defaultPrevented', {
      get: () => defaultPrevented,
    });
    pasteEvent.preventDefault = () => {
      defaultPrevented = true;
    };
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: {
        getData: (format: string) => (format === 'text/plain' ? '' : ''),
        types: ['Files'],
        items: [{ kind: 'file', type: 'image/png', getAsFile: () => imageFile }],
        files: [imageFile],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(pasteEvent);
    });

    expect(defaultPrevented).toBe(true);
    expect(mockAddAttachment).toHaveBeenCalledTimes(1);
    expect(mockAddAttachment).toHaveBeenCalledWith(imageFile);

    cleanup();
  });

  it('does NOT call addAttachment when pasting plain text', async () => {
    const { container, cleanup } = renderEditor();

    const textarea = container.querySelector('textarea');
    expect(textarea).toBeDefined();

    let defaultPrevented = false;
    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    pasteEvent.preventDefault = () => {
      defaultPrevented = true;
    };
    Object.defineProperty(pasteEvent, 'defaultPrevented', {
      get: () => defaultPrevented,
    });
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: {
        getData: (format: string) => (format === 'text/plain' ? 'Copied notes paragraph' : ''),
        types: ['text/plain'],
        items: [{ kind: 'string', type: 'text/plain' }],
        files: [],
      },
    });

    await act(async () => {
      textarea?.dispatchEvent(pasteEvent);
    });

    expect(defaultPrevented).toBe(false);
    expect(mockAddAttachment).not.toHaveBeenCalled();

    cleanup();
  });

  it('does NOT call addAttachment when pasting mixed text and image (conservative text precedence)', async () => {
    const { container, cleanup } = renderEditor();

    const editorSurface = container.querySelector('[role="dialog"]');
    const imageFile = new File(['img'], 'web.png', { type: 'image/png' });

    let defaultPrevented = false;
    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    pasteEvent.preventDefault = () => {
      defaultPrevented = true;
    };
    Object.defineProperty(pasteEvent, 'defaultPrevented', {
      get: () => defaultPrevented,
    });
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: {
        getData: (format: string) =>
          format === 'text/plain' ? 'Selected web article text' : '',
        types: ['text/plain', 'Files'],
        items: [
          { kind: 'string', type: 'text/plain' },
          { kind: 'file', type: 'image/png', getAsFile: () => imageFile },
        ],
        files: [imageFile],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(pasteEvent);
    });

    expect(defaultPrevented).toBe(false);
    expect(mockAddAttachment).not.toHaveBeenCalled();

    cleanup();
  });

  it('does NOT attach image when attachments are disabled (gated availability)', async () => {
    vi.spyOn(attachmentConfig, 'isR2AttachmentsEnabled').mockReturnValue(false);
    const toastSpy = vi.spyOn(useToastStore.getState(), 'show');

    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const imageFile = new File(['fake-png-bytes'], 'screenshot.png', { type: 'image/png' });

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(pasteEvent, 'clipboardData', {
      value: {
        getData: () => '',
        types: ['Files'],
        items: [{ kind: 'file', type: 'image/png', getAsFile: () => imageFile }],
        files: [imageFile],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(pasteEvent);
    });

    expect(mockAddAttachment).not.toHaveBeenCalled();
    expect(toastSpy).toHaveBeenCalledWith('Attachments are not enabled', 'error');

    cleanup();
  });

  it('activates drop overlay on dragenter with files and handles nested elements without flicker', async () => {
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    expect(editorSurface).toBeDefined();

    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).toBeNull();

    // 1. Drag enter on root editor
    const dragEnter1 = new Event('dragenter', { bubbles: true, cancelable: true });
    Object.defineProperty(dragEnter1, 'dataTransfer', {
      value: { types: ['Files'] },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dragEnter1);
    });
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).not.toBeNull();

    // 2. Drag enter on child element (e.g. title input)
    const titleInput = container.querySelector('#note-title');
    const dragEnter2 = new Event('dragenter', { bubbles: true, cancelable: true });
    Object.defineProperty(dragEnter2, 'dataTransfer', {
      value: { types: ['Files'] },
    });

    await act(async () => {
      titleInput?.dispatchEvent(dragEnter2);
    });
    // Overlay remains stable
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).not.toBeNull();

    // 3. Drag leave from child element
    const dragLeave1 = new Event('dragleave', { bubbles: true, cancelable: true });
    Object.defineProperty(dragLeave1, 'dataTransfer', {
      value: { types: ['Files'] },
    });

    await act(async () => {
      titleInput?.dispatchEvent(dragLeave1);
    });
    // Depth is still 1, overlay must not flicker off
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).not.toBeNull();

    // 4. Drag leave from editor surface (complete exit)
    const dragLeave2 = new Event('dragleave', { bubbles: true, cancelable: true });
    Object.defineProperty(dragLeave2, 'dataTransfer', {
      value: { types: ['Files'] },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dragLeave2);
    });
    // Depth is 0, overlay is cleanly removed
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).toBeNull();

    cleanup();
  });

  it('ingests dropped image file, prevents browser navigation, and dismisses overlay', async () => {
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const imageFile = new File(['dropped-img-bytes'], 'photo.jpg', { type: 'image/jpeg' });

    // Drag enter first
    const dragEnter = new Event('dragenter', { bubbles: true, cancelable: true });
    Object.defineProperty(dragEnter, 'dataTransfer', {
      value: { types: ['Files'] },
    });
    await act(async () => {
      editorSurface?.dispatchEvent(dragEnter);
    });
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).not.toBeNull();

    // Drop file
    let defaultPrevented = false;
    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = () => {
      defaultPrevented = true;
    };
    Object.defineProperty(dropEvent, 'defaultPrevented', {
      get: () => defaultPrevented,
    });
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [imageFile],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(defaultPrevented).toBe(true);
    expect(mockAddAttachment).toHaveBeenCalledWith(imageFile);
    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).toBeNull();

    cleanup();
  });

  it('prevents navigation and displays single toast when unsupported file is dropped', async () => {
    const toastSpy = vi.spyOn(useToastStore.getState(), 'show');
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const pdfFile = new File(['pdf-bytes'], 'document.pdf', { type: 'application/pdf' });

    let defaultPrevented = false;
    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = () => {
      defaultPrevented = true;
    };
    Object.defineProperty(dropEvent, 'defaultPrevented', {
      get: () => defaultPrevented,
    });
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [pdfFile],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(defaultPrevented).toBe(true);
    expect(mockAddAttachment).not.toHaveBeenCalled();
    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).toHaveBeenCalledWith('Only images are supported', 'error');

    cleanup();
  });

  it('processes multiple dropped image files sequentially in order', async () => {
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const img1 = new File(['1'], 'img1.png', { type: 'image/png' });
    const img2 = new File(['2'], 'img2.png', { type: 'image/png' });

    const callOrder: string[] = [];
    mockAddAttachment.mockImplementation(async (file: File) => {
      callOrder.push(file.name);
    });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = vi.fn();
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [img1, img2],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(2);
    expect(callOrder).toEqual(['img1.png', 'img2.png']);

    cleanup();
  });

  it('rejects multiple dropped unsupported files with a single consolidated toast', async () => {
    const toastSpy = vi.spyOn(useToastStore.getState(), 'show');
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const pdf1 = new File(['pdf1'], 'doc1.pdf', { type: 'application/pdf' });
    const pdf2 = new File(['pdf2'], 'doc2.pdf', { type: 'application/pdf' });
    const pdf3 = new File(['pdf3'], 'doc3.pdf', { type: 'application/pdf' });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = vi.fn();
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [pdf1, pdf2, pdf3],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(mockAddAttachment).not.toHaveBeenCalled();
    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).toHaveBeenCalledWith('Only images are supported', 'error');

    cleanup();
  });

  it('mixed drop attaches supported images, skips unsupported files, and shows single toast', async () => {
    const toastSpy = vi.spyOn(useToastStore.getState(), 'show');
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const img1 = new File(['1'], 'img1.png', { type: 'image/png' });
    const pdf = new File(['pdf'], 'notes.pdf', { type: 'application/pdf' });
    const img2 = new File(['2'], 'img2.jpg', { type: 'image/jpeg' });

    const attachedFiles: string[] = [];
    mockAddAttachment.mockImplementation(async (file: File) => {
      attachedFiles.push(file.name);
    });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = vi.fn();
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [img1, pdf, img2],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(2);
    expect(attachedFiles).toEqual(['img1.png', 'img2.jpg']);
    expect(toastSpy).toHaveBeenCalledTimes(1);
    expect(toastSpy).toHaveBeenCalledWith('Only images are supported', 'error');

    cleanup();
  });

  it('multiple drop with intermediate failure attaches subsequent valid images in order', async () => {
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');
    const imgA = new File(['A'], 'a.png', { type: 'image/png' });
    const imgB = new File(['B-too-large'], 'b.png', { type: 'image/png' });
    const imgC = new File(['C'], 'c.png', { type: 'image/png' });

    const attachedFiles: string[] = [];
    mockAddAttachment.mockImplementation(async (file: File) => {
      if (file.name === 'b.png') {
        // Simulates rejection/failure in canonical addAttachment
        useToastStore.getState().show('Image must be under 10 MB', 'error');
        return;
      }
      attachedFiles.push(file.name);
    });

    const dropEvent = new Event('drop', { bubbles: true, cancelable: true });
    dropEvent.preventDefault = vi.fn();
    Object.defineProperty(dropEvent, 'dataTransfer', {
      value: {
        types: ['Files'],
        files: [imgA, imgB, imgC],
      },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dropEvent);
    });

    expect(mockAddAttachment).toHaveBeenCalledTimes(3);
    expect(attachedFiles).toEqual(['a.png', 'c.png']);

    cleanup();
  });

  it('resets drag overlay when window loses focus (window blur)', async () => {
    const { container, cleanup } = renderEditor();
    const editorSurface = container.querySelector('[role="dialog"]');

    const dragEnterEvent = new Event('dragenter', { bubbles: true, cancelable: true });
    dragEnterEvent.preventDefault = vi.fn();
    Object.defineProperty(dragEnterEvent, 'dataTransfer', {
      value: { types: ['Files'] },
    });

    await act(async () => {
      editorSurface?.dispatchEvent(dragEnterEvent);
    });

    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).not.toBeNull();

    // Trigger window blur (e.g. alt-tab or drag leaves window)
    await act(async () => {
      window.dispatchEvent(new Event('blur'));
    });

    expect(container.querySelector('[data-testid="editor-drop-overlay"]')).toBeNull();

    cleanup();
  });

  it('title input focus: image paste attaches image and prevents default; text paste remains native', async () => {
    const { container, cleanup } = renderEditor();
    const titleInput = container.querySelector('input[placeholder="Title"]') as HTMLInputElement;
    expect(titleInput).not.toBeNull();

    const imageFile = new File(['img'], 'title-screenshot.png', { type: 'image/png' });

    // 1. Paste image while title input has focus
    let imgDefaultPrevented = false;
    const imgPasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    imgPasteEvent.preventDefault = () => {
      imgDefaultPrevented = true;
    };
    Object.defineProperty(imgPasteEvent, 'clipboardData', {
      value: {
        items: [{ kind: 'file', type: 'image/png', getAsFile: () => imageFile }],
        types: ['Files'],
        getData: () => '',
      },
    });

    await act(async () => {
      titleInput.dispatchEvent(imgPasteEvent);
    });

    expect(imgDefaultPrevented).toBe(true);
    expect(mockAddAttachment).toHaveBeenCalledWith(imageFile);

    // 2. Paste text while title input has focus
    let textDefaultPrevented = false;
    const textPasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    textPasteEvent.preventDefault = () => {
      textDefaultPrevented = true;
    };
    Object.defineProperty(textPasteEvent, 'clipboardData', {
      value: {
        items: [{ kind: 'string', type: 'text/plain' }],
        types: ['text/plain'],
        getData: (format: string) => (format === 'text/plain' ? 'Meeting Notes Title' : ''),
      },
    });

    mockAddAttachment.mockClear();
    await act(async () => {
      titleInput.dispatchEvent(textPasteEvent);
    });

    expect(textDefaultPrevented).toBe(false);
    expect(mockAddAttachment).not.toHaveBeenCalled();

    cleanup();
  });

  it('checklist input focus: image paste attaches image; text paste remains native', async () => {
    const { container, cleanup } = renderEditor();

    // Click "Add checklist" to enter checklist mode
    const checklistBtn = container.querySelector('button[aria-label="Add checklist"]');
    if (checklistBtn) {
      await act(async () => {
        checklistBtn.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
    }

    const itemInput =
      container.querySelector('input[aria-label="Checklist item"]') ||
      container.querySelector('input[type="text"]');
    expect(itemInput).not.toBeNull();

    const imageFile = new File(['img'], 'checklist-photo.png', { type: 'image/png' });

    // 1. Paste image while checklist input has focus
    let imgDefaultPrevented = false;
    const imgPasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    imgPasteEvent.preventDefault = () => {
      imgDefaultPrevented = true;
    };
    Object.defineProperty(imgPasteEvent, 'clipboardData', {
      value: {
        items: [{ kind: 'file', type: 'image/png', getAsFile: () => imageFile }],
        types: ['Files'],
        getData: () => '',
      },
    });

    await act(async () => {
      itemInput?.dispatchEvent(imgPasteEvent);
    });

    expect(imgDefaultPrevented).toBe(true);
    expect(mockAddAttachment).toHaveBeenCalledWith(imageFile);

    // 2. Paste text while checklist item has focus
    let textDefaultPrevented = false;
    const textPasteEvent = new Event('paste', { bubbles: true, cancelable: true });
    textPasteEvent.preventDefault = () => {
      textDefaultPrevented = true;
    };
    Object.defineProperty(textPasteEvent, 'clipboardData', {
      value: {
        items: [{ kind: 'string', type: 'text/plain' }],
        types: ['text/plain'],
        getData: (format: string) => (format === 'text/plain' ? 'Buy groceries' : ''),
      },
    });

    mockAddAttachment.mockClear();
    await act(async () => {
      itemInput?.dispatchEvent(textPasteEvent);
    });

    expect(textDefaultPrevented).toBe(false);
    expect(mockAddAttachment).not.toHaveBeenCalled();

    cleanup();
  });

  it('aggressive text paste variants never invoke addAttachment and preserve native paste', async () => {
    const textVariants = [
      'Single line plain text',
      'Multi\nline\r\ntext\nwith\nbreaks',
      'https://example.com/notes?id=123&sort=desc',
      '# Markdown heading\n- item 1\n- item 2\n**bold text**',
      'Unicode text: \u4f60\u597d \u0645\u0631\u062d\u0628\u0627 \u05e9\u05dc\u05d5\u05dd',
      'Emoji payload: \ud83d\ude80 \u2728 \ud83c\udf89 \ud83d\udcdd',
      '<p>HTML snippet with <strong>rich</strong> text</p>',
    ];

    const { container, cleanup } = renderEditor();
    const textarea = container.querySelector('textarea[placeholder="Start writing\u2026"]') as HTMLTextAreaElement;

    for (const text of textVariants) {
      mockAddAttachment.mockClear();
      let defaultPrevented = false;
      const pasteEvent = new Event('paste', { bubbles: true, cancelable: true });
      pasteEvent.preventDefault = () => {
        defaultPrevented = true;
      };
      Object.defineProperty(pasteEvent, 'clipboardData', {
        value: {
          items: [{ kind: 'string', type: 'text/plain' }],
          types: ['text/plain'],
          getData: (format: string) => (format === 'text/plain' ? text : ''),
        },
      });

      await act(async () => {
        textarea.dispatchEvent(pasteEvent);
      });

      expect(defaultPrevented).toBe(false);
      expect(mockAddAttachment).not.toHaveBeenCalled();
    }

    cleanup();
  });

  it('file picker consistency: hidden file input specifies accept="image/*" and calls addAttachment', async () => {
    const { container, cleanup } = renderEditor();
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;

    expect(fileInput).not.toBeNull();
    expect(fileInput.accept).toBe('image/*');

    const chosenImage = new File(['png-data'], 'picked.png', { type: 'image/png' });
    Object.defineProperty(fileInput, 'files', {
      value: [chosenImage],
      writable: true,
    });

    await act(async () => {
      fileInput.dispatchEvent(new Event('change', { bubbles: true }));
    });

    expect(mockAddAttachment).toHaveBeenCalledWith(chosenImage);

    cleanup();
  });
});
