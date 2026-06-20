import { useState } from 'react';
import { motion } from 'framer-motion';
import { Search, MoreVertical } from 'lucide-react';
import { useStore } from '@/store';

interface Props {
  onFocus?: () => void;
}

export default function SearchBar({ onFocus }: Props) {
  const [isFocused, setIsFocused] = useState(false);
  const { setSearchQuery } = useStore();

  return (
    <motion.div
      initial={false}
      animate={{
        boxShadow: isFocused
          ? '0 0 20px rgba(212, 148, 106, 0.15)'
          : '0 0 0px rgba(212, 148, 106, 0)',
      }}
      className={`flex items-center h-[52px] rounded-pill px-5 transition-all duration-200 border ${
        isFocused
          ? 'border-mantra-accent/50 bg-mantra-elevated/90'
          : 'border-transparent bg-mantra-elevated'
      }`}
    >
      <Search size={20} className="text-mantra-muted flex-shrink-0" strokeWidth={1.5} />
      <input
        type="text"
        placeholder="Search manga"
        className="flex-1 bg-transparent text-mantra-text placeholder:text-mantra-muted ml-3 text-[15px] outline-none"
        onFocus={() => {
          setIsFocused(true);
          onFocus?.();
        }}
        onBlur={() => setIsFocused(false)}
        onChange={(e) => setSearchQuery(e.target.value)}
        readOnly={!!onFocus}
      />
      <button className="flex-shrink-0">
        <MoreVertical size={20} className="text-mantra-muted" strokeWidth={1.5} />
      </button>
    </motion.div>
  );
}
