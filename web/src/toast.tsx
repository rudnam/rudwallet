// A short message after an action ("Saved"), shown for a moment at the
// bottom of the screen. Call useToast()("Saved") from anywhere in the app,
// or useToast()("Saved", { label: 'Undo', run }) to offer a way back.
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';

export interface ToastAction {
  label: string;
  run: () => void;
}

type Show = (message: string, action?: ToastAction) => void;

const Ctx = createContext<Show>(() => {});

// A toast with an action stays longer, so there's time to reach it.
const SHORT = 2400; // ms
const LONG = 6000; // ms

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; action?: ToastAction; n: number }>({ message: '', n: 0 });
  const timer = useRef<number>(undefined);
  const show = useCallback<Show>((message, action) => {
    window.clearTimeout(timer.current);
    setToast((t) => ({ message, action, n: t.n + 1 }));
    timer.current = window.setTimeout(() => setToast((t) => ({ message: '', n: t.n })), action ? LONG : SHORT);
  }, []);
  const { message, action, n } = toast;
  return (
    <Ctx.Provider value={show}>
      {children}
      <div className="toast" aria-live="polite" key={n}>
        {message}
        {message && action && (
          <button
            type="button"
            className="toast-action"
            onClick={() => {
              window.clearTimeout(timer.current);
              setToast((t) => ({ message: '', n: t.n }));
              action.run();
            }}
          >
            {action.label}
          </button>
        )}
      </div>
    </Ctx.Provider>
  );
}

export function useToast() {
  return useContext(Ctx);
}
