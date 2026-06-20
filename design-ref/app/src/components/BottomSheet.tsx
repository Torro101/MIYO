import { motion, AnimatePresence } from 'framer-motion';
import { useDrag } from '@use-gesture/react';
import { useState, type ReactNode } from 'react';

interface Props {
  isOpen: boolean;
  onClose: () => void;
  children: ReactNode;
  maxHeight?: string;
}

export default function BottomSheet({ isOpen, onClose, children, maxHeight = '70vh' }: Props) {
  const [y, setY] = useState(0);

  const bind = useDrag(
    ({ down, movement: [, my], velocity: [, vy], direction: [, dy] }) => {
      if (down) {
        setY(Math.max(0, my));
      } else {
        if (my > 100 || (vy > 0.5 && dy > 0)) {
          onClose();
          setTimeout(() => setY(0), 300);
        } else {
          setY(0);
        }
      }
    },
    { axis: 'y', from: () => [0, 0] }
  );

  return (
    <AnimatePresence>
      {isOpen && (
        <>
          {/* Backdrop */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            onClick={() => {
              onClose();
              setY(0);
            }}
            className="fixed inset-0 z-[60]"
            style={{ background: 'rgba(13, 11, 15, 0.85)' }}
          />
          {/* Sheet */}
          <motion.div
            initial={{ y: '100%' }}
            animate={{ y: y }}
            exit={{ y: '100%' }}
            transition={{ duration: 0.35, ease: [0.2, 0.8, 0.2, 1] }}
            className="fixed bottom-0 left-0 right-0 z-[70] rounded-t-3xl overflow-hidden"
            style={{
              maxHeight,
              background: 'rgba(22, 18, 26, 0.95)',
              backdropFilter: 'blur(20px) saturate(120%)',
              WebkitBackdropFilter: 'blur(20px) saturate(120%)',
              touchAction: 'none',
            }}
          >
            {/* Drag handle */}
            <div {...bind()} className="pt-3 pb-2 cursor-grab active:cursor-grabbing">
              <div className="w-10 h-1 rounded-full bg-mantra-tertiary mx-auto" />
            </div>
            {/* Content */}
            <div className="overflow-y-auto hide-scrollbar px-5 pb-8" style={{ maxHeight: `calc(${maxHeight} - 40px)` }}>
              {children}
            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
