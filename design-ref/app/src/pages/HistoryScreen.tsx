import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { List, LayoutGrid, Bookmark, Search, Download, Play } from 'lucide-react';
import { useStore } from '@/store';
import { getMangaById } from '@/data';

const VIEWS = [
  { key: 'list', icon: List, label: 'List' },
  { key: 'grid', icon: LayoutGrid, label: 'Grid' },
  { key: 'bookmarks', icon: Bookmark, label: 'Bookmarks' },
];

export default function HistoryScreen() {
  const { navigateTo, selectedMangaId } = useStore();
  const [view, setView] = useState('list');
  const manga = selectedMangaId ? getMangaById(selectedMangaId) : getMangaById('1');

  if (!manga) return null;
  const chapters = manga.chapters || [];

  return (
    <div className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Header */}
      <div className="px-5 pt-4 pb-2">
        <div className="flex items-center justify-between">
          <div className="flex bg-mantra-elevated rounded-xl p-1">
            {VIEWS.map((v) => (
              <button
                key={v.key}
                onClick={() => setView(v.key)}
                className={`relative flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  view === v.key ? 'text-mantra-text' : 'text-mantra-muted'
                }`}
              >
                {view === v.key && (
                  <motion.div
                    layoutId="historyViewPill"
                    className="absolute inset-0 bg-mantra-surface border border-mantra-tertiary/20 rounded-lg shadow-sm"
                    transition={{ type: 'spring', stiffness: 300, damping: 30 }}
                  />
                )}
                <span className="relative z-10 flex items-center gap-1.5">
                  <v.icon size={15} strokeWidth={1.5} />
                  {v.label}
                </span>
              </button>
            ))}
          </div>
          <button onClick={() => navigateTo('search')} className="w-10 h-10 flex items-center justify-center rounded-xl active:bg-mantra-elevated transition-colors">
            <Search size={18} className="text-mantra-muted" strokeWidth={1.5} />
          </button>
        </div>
      </div>

      {/* Manga info header */}
      <div className="px-5 py-3 flex items-center gap-3">
        <img src={manga.cover} alt={manga.title} className="w-10 h-14 rounded-lg object-cover flex-shrink-0" />
        <div className="min-w-0">
          <h2 className="text-sm font-bold text-mantra-text truncate">{manga.title}</h2>
          <p className="text-xs text-mantra-muted">{chapters.length} chapters · <span className="capitalize">{manga.status}</span></p>
        </div>
      </div>

      <div className="px-5 mb-2">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      <AnimatePresence mode="wait">
        {view === 'list' && (
          <motion.div
            key="list"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
          >
            <div>
              {chapters.slice(0, 20).map((ch, i) => {
                const isCurrent = ch.number === manga.currentChapter;
                return (
                  <motion.div
                    key={ch.id}
                    initial={{ opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: Math.min(i * 0.012, 0.2) }}
                    className={`flex items-center gap-2 px-5 py-3 border-b border-white/[0.03] last:border-0 cursor-pointer active:bg-white/[0.02] transition-colors ${
                      isCurrent ? 'bg-mantra-accent/5' : ''
                    }`}
                  >
                    {/* Selection indicator */}
                    <div className={`w-[3px] h-5 rounded-r-full flex-shrink-0 ${isCurrent ? 'bg-mantra-accent' : 'bg-transparent'}`}
                      style={isCurrent ? { boxShadow: '0 0 6px rgba(212, 148, 106, 0.3)' } : undefined}
                    />
                    {/* Play icon */}
                    <div className="w-5 flex-shrink-0 flex justify-center">
                      {isCurrent ? <Play size={9} className="text-mantra-accent fill-mantra-accent" /> : <span className="text-[10px] text-mantra-tertiary w-5 text-center">{ch.number}</span>}
                    </div>
                    {/* Text */}
                    <div className="flex-1 min-w-0">
                      <p className={`text-sm truncate ${isCurrent ? 'text-mantra-accent font-semibold' : 'text-mantra-text'}`}>
                        {manga.title} {ch.number}
                      </p>
                      <p className="text-[11px] text-mantra-muted mt-0.5">{ch.date}</p>
                    </div>
                    {/* Download icon */}
                    <Download size={16} className={ch.isDownloaded ? 'text-mantra-accent' : 'text-mantra-tertiary/50'} strokeWidth={1.5} />
                  </motion.div>
                );
              })}
            </div>
          </motion.div>
        )}

        {view === 'grid' && (
          <motion.div
            key="grid"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="px-5"
          >
            <div className="grid grid-cols-3 gap-x-3 gap-y-5">
              {[manga, ...[1, 2, 3, 4, 5].map(i => getMangaById(String(i)) || manga)].filter(Boolean).slice(0, 6).map((m, i) => (
                <motion.div
                  key={`${m.id}-${i}`}
                  initial={{ opacity: 0, scale: 0.9 }}
                  animate={{ opacity: 1, scale: 1 }}
                  transition={{ delay: i * 0.04 }}
                  whileTap={{ scale: 0.95 }}
                  onClick={() => navigateTo('detail', m.id)}
                  className="cursor-pointer"
                >
                  <div className="w-full aspect-[2/3] rounded-xl overflow-hidden shadow-card ring-1 ring-white/[0.04]">
                    <img src={m.cover} alt={m.title} className="w-full h-full object-cover" loading="lazy" />
                  </div>
                  <p className="mt-2 text-xs font-semibold text-mantra-text leading-snug line-clamp-2">{m.title}</p>
                </motion.div>
              ))}
            </div>
          </motion.div>
        )}

        {view === 'bookmarks' && (
          <motion.div
            key="bookmarks"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.15 }}
            className="flex flex-col items-center justify-center py-20 px-8"
          >
            <Bookmark size={60} className="text-mantra-tertiary mb-4" strokeWidth={1} />
            <p className="text-mantra-text font-semibold mb-1">No bookmarks yet</p>
            <p className="text-mantra-muted text-sm text-center leading-relaxed">Bookmark pages while reading to find them here</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
