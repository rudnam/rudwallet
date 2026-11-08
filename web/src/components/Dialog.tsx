import { useEffect, useRef, type PointerEvent, type ReactNode } from 'react';
import { Icon, icons } from './Icon';
import { t } from '../i18n';

// On phones the dialog is a bottom sheet: dragging its top (the grabber and
// title bar) down far enough, or flicking it, closes it.
const SHEET = '(max-width: 560px)';
const CLOSE_DISTANCE = 100; // px
const CLOSE_VELOCITY = 0.5; // px/ms
// The speed of a flick is how far the finger went in its last moments before letting
// go, not between its last two events: those can be a millisecond apart, and a finger
// that stopped before letting go isn't flicking.
const FLICK_WINDOW = 100; // ms

/** A modal dialog (the native <dialog>, so focus and Escape just work). */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  const drag = useRef<{ id: number; y: number; dy: number; moves: { t: number; dy: number }[] } | null>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) {
      d.showModal();
      // React's autoFocus fires while the dialog is still closed, and showModal
      // then focuses the first control, so a field asks for focus this way.
      d.querySelector<HTMLElement>('[data-autofocus]')?.focus();
    }
    return () => d?.close();
  }, []);

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (!window.matchMedia(SHEET).matches || (e.target as Element).closest('button')) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { id: e.pointerId, y: e.clientY, dy: 0, moves: [{ t: e.timeStamp, dy: 0 }] };
    if (ref.current) ref.current.style.transition = 'none';
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const g = drag.current;
    if (!g || g.id !== e.pointerId || !ref.current) return;
    const dy = Math.max(0, e.clientY - g.y);
    g.dy = dy;
    g.moves.push({ t: e.timeStamp, dy });
    while (g.moves.length > 2 && e.timeStamp - g.moves[0].t > FLICK_WINDOW) g.moves.shift();
    ref.current.style.transform = `translateY(${dy}px)`;
  };

  const onPointerUp = (e: PointerEvent<HTMLDivElement>) => {
    const g = drag.current;
    const d = ref.current;
    if (!g || g.id !== e.pointerId || !d) return;
    drag.current = null;
    d.style.transition = 'transform 0.2s ease-out';
    // How far it moved in the window before letting go, over at least 16 ms (a frame), so
    // events a moment apart don't make a twitch look fast.
    const recent = g.moves.filter((m) => e.timeStamp - m.t <= FLICK_WINDOW);
    const first = recent[0];
    const v = first ? (g.dy - first.dy) / Math.max(e.timeStamp - first.t, 16) : 0;
    if (g.dy > CLOSE_DISTANCE || (g.dy > 10 && v > CLOSE_VELOCITY)) {
      d.style.transform = 'translateY(100%)';
      setTimeout(onClose, 180);
    } else {
      d.style.transform = '';
    }
  };

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-label={title}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div
        className="dialog-top"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <div className="dialog-grab" aria-hidden="true" />
        <div className="dialog-head">
          <h2>{title}</h2>
          <button type="button" className="link" aria-label={t('Close')} onClick={onClose}>
            <Icon d={icons.close} className="glyph" />
          </button>
        </div>
      </div>
      {children}
    </dialog>
  );
}
