import { useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowBackIcon, ChevronRightIcon, CloseIcon } from '@/components/icons/Icons';
import { useBodyScrollLock } from '@/hooks/useBodyScrollLock';
import { useFocusTrap } from '@/hooks/useFocusTrap';

const ZOOM_LEVELS = [1, 1.5, 2, 3, 4];
const IMAGE_GUTTER = 16;
const VIEWER_BUTTON = 'flex size-11 shrink-0 items-center justify-center rounded-full hover:bg-white/10 focus-visible:outline focus-visible:outline-white disabled:opacity-40 aria-disabled:opacity-40';

interface ImageViewerProps {
  url: string | undefined;
  failed: boolean;
  onDecodeFailure: (url: string) => void;
  onClose: () => void;
  onRemove: () => void;
  position?: string;
  previous?: () => void;
  next?: () => void;
}

export function AttachmentImageViewer({
  url, failed, onDecodeFailure, onClose, onRemove, position, previous, next,
}: ImageViewerProps) {
  const dialogRef = useFocusTrap<HTMLDivElement>(true, onClose);
  useBodyScrollLock(true);
  const viewportRef = useRef<HTMLDivElement>(null);
  const anchor = useRef<{ x: number; y: number } | null>(null);
  const drag = useRef<{ id: number; x: number; y: number; left: number; top: number } | null>(null);
  const [viewportSize, setViewportSize] = useState({ width: 0, height: 0 });
  const [imageSize, setImageSize] = useState<{ width: number; height: number } | null>(null);
  const [zoomIndex, setZoomIndex] = useState(0);
  const zoom = ZOOM_LEVELS[zoomIndex]!;
  const canZoom = Boolean(url && imageSize);
  const zoomed = canZoom && zoom > 1;
  const fit = imageSize && Math.min(
    Math.max(1, viewportSize.width - IMAGE_GUTTER * 2) / imageSize.width,
    Math.max(1, viewportSize.height - IMAGE_GUTTER * 2) / imageSize.height,
  );
  const rendered = imageSize && fit && {
    width: imageSize.width * fit * zoom,
    height: imageSize.height * fit * zoom,
  };

  useLayoutEffect(() => {
    const viewport = viewportRef.current!;
    const measure = () => setViewportSize({ width: viewport.offsetWidth, height: viewport.offsetHeight });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(viewport, { box: 'border-box' });
    return () => observer.disconnect();
  }, []);

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport || !anchor.current) return;
    const { x, y } = anchor.current;
    viewport.scrollLeft = x * (viewport.scrollWidth - viewport.clientWidth);
    viewport.scrollTop = y * (viewport.scrollHeight - viewport.clientHeight);
    anchor.current = null;
  }, [zoomIndex]);

  function changeZoom(target: number) {
    const viewport = viewportRef.current;
    const clamped = Math.max(0, Math.min(ZOOM_LEVELS.length - 1, target));
    if (!canZoom || !viewport || clamped === zoomIndex) return;
    const xRange = viewport.scrollWidth - viewport.clientWidth;
    const yRange = viewport.scrollHeight - viewport.clientHeight;
    anchor.current = {
      x: xRange ? viewport.scrollLeft / xRange : 0.5,
      y: yRange ? viewport.scrollTop / yRange : 0.5,
    };
    setZoomIndex(clamped);
  }

  return createPortal(
    <div
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-label="Image viewer"
      className="fixed inset-0 z-50 flex flex-col bg-black text-white"
      onKeyDown={(event) => {
        if (event.ctrlKey || event.metaKey || event.altKey) return;
        const target = event.key === '+' || event.key === '=' ? zoomIndex + 1
          : event.key === '-' ? zoomIndex - 1 : event.key === '0' ? 0 : undefined;
        if (target !== undefined) {
          event.preventDefault();
          changeZoom(target);
          return;
        }
        if (zoomed && event.target === viewportRef.current) return;
        const step = event.key === 'ArrowLeft' ? previous : event.key === 'ArrowRight' ? next : undefined;
        if (step) {
          event.preventDefault();
          step();
        }
      }}
    >
      <div className="flex items-center gap-2 p-2 pt-safe">
        {position ? <span className="shrink-0 pl-3 text-sm" aria-live="polite">{position}</span> : null}
        <button type="button" aria-label="Close image" className={`${VIEWER_BUTTON} order-last`} onClick={onClose}>
          <CloseIcon />
        </button>
        <div className="ml-auto flex items-center" role="group" aria-label="Image zoom">
          <button type="button" aria-label="Zoom out" aria-disabled={!zoomed} className={VIEWER_BUTTON} onClick={() => changeZoom(zoomIndex - 1)}>
            <span aria-hidden="true" className="text-xl">−</span>
          </button>
          <button type="button" aria-label="Fit image to viewer" aria-disabled={!zoomed} title="Fit image to viewer" className={`${VIEWER_BUTTON} min-w-14 px-2 text-sm tabular-nums`} onClick={() => changeZoom(0)}>
            {zoomed ? `${Math.round(zoom * 100)}%` : 'Fit'}
          </button>
          <button type="button" aria-label="Zoom in" aria-disabled={!canZoom || zoomIndex === ZOOM_LEVELS.length - 1} className={VIEWER_BUTTON} onClick={() => changeZoom(zoomIndex + 1)}>
            <span aria-hidden="true" className="text-xl">+</span>
          </button>
          <span className="sr-only" role="status">{zoomed ? `${Math.round(zoom * 100)}% of fit` : 'Fit to viewer'}</span>
        </div>
      </div>
      <div
        ref={viewportRef}
        role="region"
        aria-label="Image canvas"
        tabIndex={zoomed ? 0 : -1}
        className={`min-h-0 flex-1 overflow-auto overscroll-contain focus-visible:outline focus-visible:-outline-offset-2 focus-visible:outline-white ${zoomed ? 'cursor-grab active:cursor-grabbing' : ''}`}
        onPointerDown={(event) => {
          if (!zoomed || event.pointerType !== 'mouse' || event.button !== 0) return;
          const viewport = event.currentTarget;
          // Preserve dragging the native scrollbars.
          if (event.target === viewport) return;
          drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop };
          viewport.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          const start = drag.current;
          if (!start || start.id !== event.pointerId) return;
          event.currentTarget.scrollLeft = start.left + start.x - event.clientX;
          event.currentTarget.scrollTop = start.top + start.y - event.clientY;
        }}
        onLostPointerCapture={(event) => {
          if (drag.current?.id === event.pointerId) drag.current = null;
        }}
      >
        <div
          className="grid min-h-full min-w-full place-items-center select-none"
          style={rendered && url ? { width: rendered.width + IMAGE_GUTTER * 2, height: rendered.height + IMAGE_GUTTER * 2 } : undefined}
        >
          {url ? (
            <img
              src={url}
              alt="Enlarged note attachment"
              draggable={false}
              className="block max-w-none"
              style={rendered || { visibility: 'hidden', width: 1, height: 1 }}
              onLoad={({ currentTarget }) => setImageSize({ width: currentTarget.naturalWidth, height: currentTarget.naturalHeight })}
              onError={() => onDecodeFailure(url)}
            />
          ) : (
            <p role="status">{failed ? 'Could not load image' : 'Loading image…'}</p>
          )}
        </div>
      </div>
      <div className="flex gap-2 p-2 pb-safe-action">
        {position ? (
          <div className="flex gap-2">
            <button type="button" aria-label="Previous image" onClick={previous} disabled={!previous} className={VIEWER_BUTTON}>
              <ArrowBackIcon />
            </button>
            <button type="button" aria-label="Next image" onClick={next} disabled={!next} className={VIEWER_BUTTON}>
              <ChevronRightIcon />
            </button>
          </div>
        ) : null}
        <button
          type="button"
          onClick={onRemove}
          className="tap-target ml-auto rounded-note px-3 text-sm text-white/85 hover:bg-white/10 focus-visible:outline focus-visible:outline-white"
        >
          Remove image
        </button>
      </div>
    </div>,
    document.body,
  );
}
