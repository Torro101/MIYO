import { motion, AnimatePresence } from 'framer-motion';
import { CheckCircle, Info, AlertCircle } from 'lucide-react';
import { useStore } from '@/store';

export default function Toast() {
  const { toast } = useStore();

  if (!toast) return null;

  const iconMap = {
    success: <CheckCircle size={18} className="text-mantra-success" />,
    info: <Info size={18} className="text-mantra-accent" />,
    error: <AlertCircle size={18} className="text-mantra-error" />,
  };

  return (
    <AnimatePresence>
      <motion.div
        key={toast.id}
        initial={{ y: -80, opacity: 0 }}
        animate={{ y: 0, opacity: 1 }}
        exit={{ y: -80, opacity: 0 }}
        transition={{ type: 'spring', stiffness: 400, damping: 30 }}
        className="fixed top-6 left-4 right-4 z-[80] flex items-center gap-3 h-12 px-5 rounded-pill shadow-elevated"
        style={{
          background: 'rgba(30, 25, 35, 0.95)',
          backdropFilter: 'blur(20px)',
          border: '1px solid rgba(138, 129, 152, 0.1)',
        }}
      >
        {iconMap[toast.type]}
        <span className="text-[13px] font-medium text-mantra-text">{toast.message}</span>
      </motion.div>
    </AnimatePresence>
  );
}
