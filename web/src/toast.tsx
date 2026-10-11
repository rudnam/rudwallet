// A short message after an action ("Saved"), shown for a moment at the
// bottom of the screen. Call useToast()("Saved") from anywhere in the app.
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';

const Ctx = createContext<(message: string) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const timer = useRef<number>(undefined);
  const show = useCallback((m: string) => {
    window.clearTimeout(timer.current);
    setMessage(m);
    timer.current = window.setTimeout(() => setMessage(''), 2400);
  }, []);
  return (
    <Ctx.Provider value={show}>
      {children}
      <div className="toast" aria-live="polite" key={message}>
        {message}
      </div>
    </Ctx.Provider>
  );
}

export function useToast() {
  return useContext(Ctx);
}
