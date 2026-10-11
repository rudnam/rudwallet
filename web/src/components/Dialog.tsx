import { useEffect, useRef, type ReactNode } from 'react';

/** A modal dialog (the native <dialog>, so focus and Escape just work). */
export function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (d && !d.open) d.showModal();
    return () => d?.close();
  }, []);

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
      <div className="dialog-head">
        <h2>{title}</h2>
        <button type="button" className="link" aria-label="Close" onClick={onClose}>
          ✕
        </button>
      </div>
      {children}
    </dialog>
  );
}
