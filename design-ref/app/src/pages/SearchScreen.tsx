import { useState, useMemo } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowLeft, Search, X, Clock, Mic } from 'lucide-react';
import { useStore } from '@/store';
import { MANGA_DATA } from '@/data';

const RECENT_SEARCHES = ['cultivation', 'isekai', 'romance', 'action', 'martial arts'];

export default function SearchScreen() {
  const { goBack, navigateTo } = useStore();
  const [query, setQuery] = useState('');
  const [recents, setRecents] = useState(RECENT_SEARCHES);
  const [, setIsFocused] = useState(true);

  const results = useMemo(() => {
    if (!query.trim()) return [];
    const q = query.toLowerCase();
    return MANGA_DATA.filter(
      (m) => m.title.toLowerCase().includes(q) || m.genres.some((g) => g.toLowerCase().includes(q)) || m.category.toLowerCase().includes(q)
    );
  }, [query]);

  const removeRecent = (item: string) => setRecents((prev) => prev.filter((r) => r !== item));

  return (
    <motion.div
      initial={{ y: '100%' }}
      animate={{ y: 0 }}
      exit={{ y: '100%' }}
      transition={{ duration: 0.35, ease: [0.2, 0.8, 0.2, 1] }}
      className="h-full bg-mantra-bg flex flex-col"
    >
      {/* Search Input */}
      <div className="px-4 pt-4 pb-3 flex items-center gap-2 border-b border-white/[0.04]">
        <button onClick={goBack} className="w-10 h-10 flex items-center justify-center rounded-full active:bg-mantra-elevated transition-colors -ml-1">
          <ArrowLeft size={22} className="text-mantra-text" strokeWidth={1.5} />
        </button>
        <div className="flex-1 relative">
          <input
            type="text"
            autoFocus
            placeholder="Search manga..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onFocus={() => setIsFocused(true)}
            className="w-full h-11 bg-mantra-elevated rounded-xl px-4 pr-10 text-mantra-text text-[15px] outline-none placeholder:text-mantra-muted/60 border border-transparent focus:border-mantra-accent/40 transition-all"
          />
          {query && (
            <button onClick={() => setQuery('')} className="absolute right-3 top-1/2 -translate-y-1/2 w-6 h-6 flex items-center justify-center rounded-full bg-mantra-tertiary/30">
              <X size={14} className="text-mantra-muted" />
            </button>
          )}
        </div>
        <button className="w-10 h-10 flex items-center justify-center rounded-full active:bg-mantra-elevated transition-colors">
          <Mic size={20} className="text-mantra-muted" strokeWidth={1.5} />
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto hide-scrollbar px-5 pt-4">
        {!query ? (
          <div>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-bold text-mantra-text uppercase tracking-wider">Recent searches</h3>
              <button onClick={() => setRecents([])} className="text-xs text-mantra-accent font-medium active:opacity-70 transition-opacity">Clear all</button>
            </div>
            <AnimatePresence>
              {recents.map((item) => (
                <motion.div
                  key={item}
                  layout
                  initial={{ opacity: 1, x: 0 }}
                  exit={{ opacity: 0, x: -40 }}
                  transition={{ duration: 0.2 }}
                  className="flex items-center gap-3 py-3 border-b border-white/[0.04] last:border-0 cursor-pointer active:bg-white/[0.02] transition-colors rounded-lg px-2 -mx-2"
                  onClick={() => setQuery(item)}
                >
                  <Clock size={15} className="text-mantra-tertiary" strokeWidth={1.5} />
                  <span className="flex-1 text-[15px] text-mantra-text capitalize">{item}</span>
                  <button onClick={(e) => { e.stopPropagation(); removeRecent(item); }} className="w-7 h-7 flex items-center justify-center rounded-full active:bg-mantra-elevated transition-colors">
                    <X size={14} className="text-mantra-tertiary" />
                  </button>
                </motion.div>
              ))}
            </AnimatePresence>

            {/* Trending tags */}
            <div className="mt-6">
              <h3 className="text-sm font-bold text-mantra-text uppercase tracking-wider mb-3">Trending</h3>
              <div className="flex flex-wrap gap-2">
                {['Isekai', 'Cultivation', 'Romance', 'Action', 'Horror', 'Comedy', 'Drama', 'Fantasy'].map((tag) => (
                  <button
                    key={tag}
                    onClick={() => setQuery(tag)}
                    className="h-9 px-4 rounded-full bg-mantra-elevated border border-mantra-tertiary/20 text-xs text-mantra-text font-medium active:bg-mantra-surface active:scale-95 transition-all"
                  >
                    {tag}
                  </button>
                ))}
              </div>
            </div>
          </div>
        ) : results.length > 0 ? (
          <div className="pt-2">
            <p className="text-xs text-mantra-muted mb-3">{results.length} result{results.length !== 1 ? 's' : ''} for &quot;{query}&quot;</p>
            <div className="grid grid-cols-3 gap-x-3 gap-y-5">
              {results.map((manga, i) => (
                <motion.div
                  key={manga.id}
                  initial={{ opacity: 0, y: 16 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: i * 0.04 }}
                  whileTap={{ scale: 0.96 }}
                  onClick={() => navigateTo('detail', manga.id)}
                  className="cursor-pointer"
                >
                  <div className="w-full aspect-[2/3] rounded-xl overflow-hidden shadow-card ring-1 ring-white/[0.04]">
                    <img src={manga.cover} alt={manga.title} className="w-full h-full object-cover" loading="lazy" />
                  </div>
                  <p className="mt-2 text-xs font-semibold text-mantra-text leading-snug line-clamp-2">{manga.title}</p>
                  <p className="text-[11px] text-mantra-muted mt-0.5">{manga.category}</p>
                </motion.div>
              ))}
            </div>
          </div>
        ) : (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} className="flex flex-col items-center justify-center py-20">
            <Search size={48} className="text-mantra-tertiary mb-4" strokeWidth={1} />
            <p className="text-mantra-text font-semibold mb-1">No results found</p>
            <p className="text-mantra-muted text-sm">Try different keywords</p>
          </motion.div>
        )}
      </div>
    </motion.div>
  );
}
