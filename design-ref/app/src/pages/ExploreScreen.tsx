import { useState, useRef } from 'react';
import { motion } from 'framer-motion';
import { FolderOpen, Bookmark, Dices, Download, TrendingUp, Star } from 'lucide-react';
import SearchBar from '@/components/SearchBar';
import { useStore } from '@/store';
import { MANGA_DATA, SOURCES } from '@/data';

const QUICK_ACTIONS = [
  { icon: FolderOpen, label: 'Local storage', subtitle: 'Browse downloaded', color: 'text-amber-400' },
  { icon: Bookmark, label: 'Bookmarks', subtitle: 'Saved pages', color: 'text-sky-400' },
  { icon: Dices, label: 'Random', subtitle: 'Discover something new', color: 'text-emerald-400' },
  { icon: Download, label: 'Downloads', subtitle: 'Manage queue', color: 'text-rose-400' },
];

const SUGGESTIONS = MANGA_DATA.slice(0, 5);

export default function ExploreScreen() {
  const { navigateTo } = useStore();
  const [activeSuggestion, setActiveSuggestion] = useState(0);
  const touchRef = useRef<{ x: number; y: number } | null>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const [tilt, setTilt] = useState({ rx: 0, ry: 0 });

  const currentSuggestion = SUGGESTIONS[activeSuggestion];

  const handleTouchStart = (e: React.TouchEvent) => {
    const touch = e.touches[0];
    touchRef.current = { x: touch.clientX, y: touch.clientY };
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!touchRef.current || !cardRef.current) return;
    const touch = e.touches[0];
    const rect = cardRef.current.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;
    const rx = ((touch.clientY - cy) / (rect.height / 2)) * -4;
    const ry = ((touch.clientX - cx) / (rect.width / 2)) * 4;
    setTilt({ rx: Math.max(-4, Math.min(4, rx)), ry: Math.max(-4, Math.min(4, ry)) });
  };

  const handleTouchEnd = () => {
    touchRef.current = null;
    setTilt({ rx: 0, ry: 0 });
  };

  return (
    <div className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Search */}
      <div className="px-5 pt-4 pb-3 sticky top-0 z-30" style={{ background: 'rgba(13, 11, 15, 0.85)', backdropFilter: 'blur(16px)' }}>
        <SearchBar onFocus={() => navigateTo('search')} />
      </div>

      {/* Quick Actions Grid */}
      <section className="px-5 mb-6">
        <div className="flex items-center gap-2 mb-3">
          <Star size={14} className="text-mantra-accent" />
          <p className="text-xs text-mantra-muted font-medium uppercase tracking-wider">Quick Access</p>
        </div>
        <div className="grid grid-cols-2 gap-2.5">
          {QUICK_ACTIONS.map((action, i) => (
            <motion.button
              key={action.label}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: i * 0.05 }}
              whileTap={{ scale: 0.96 }}
              className="flex items-center gap-3 h-[72px] rounded-xl bg-mantra-surface border border-white/[0.04] px-4 text-left active:bg-mantra-elevated transition-colors"
            >
              <div className="w-10 h-10 rounded-lg bg-mantra-elevated flex items-center justify-center flex-shrink-0">
                <action.icon size={18} className={action.color} strokeWidth={1.5} />
              </div>
              <div className="min-w-0">
                <p className="text-sm font-semibold text-mantra-text truncate">{action.label}</p>
                <p className="text-[11px] text-mantra-muted truncate">{action.subtitle}</p>
              </div>
            </motion.button>
          ))}
        </div>
      </section>

      {/* Section Divider */}
      <div className="px-5 mb-5">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      {/* Suggestions Carousel */}
      <section className="mb-6">
        <div className="flex items-center justify-between px-5 mb-3">
          <div className="flex items-center gap-2">
            <TrendingUp size={16} className="text-mantra-accent" />
            <h2 className="text-base font-bold text-mantra-text tracking-tight">Suggestions</h2>
          </div>
          <button className="text-xs font-semibold text-mantra-accent active:opacity-70 transition-opacity">More</button>
        </div>

        <div className="px-5">
          <motion.div
            ref={cardRef}
            onTouchStart={handleTouchStart}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
            onClick={() => navigateTo('detail', currentSuggestion.id)}
            animate={{ rotateX: tilt.rx, rotateY: tilt.ry }}
            transition={{ type: 'spring', stiffness: 300, damping: 20 }}
            className="relative w-full h-[200px] rounded-2xl overflow-hidden cursor-pointer active:scale-[0.98] transition-transform duration-100"
            style={{ perspective: 1000, transformStyle: 'preserve-3d', willChange: 'transform' }}
          >
            <img src={currentSuggestion.cover} alt={currentSuggestion.title} className="absolute inset-0 w-full h-full object-cover" />
            <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/30 to-transparent" />
            <div className="absolute bottom-4 left-4 right-4">
              <p className="text-base font-bold text-white">{currentSuggestion.title}</p>
              <p className="text-xs text-white/60 mt-0.5">From {currentSuggestion.source}</p>
            </div>
          </motion.div>

          {/* Dots + controls */}
          <div className="flex items-center justify-between mt-3">
            <button
              onClick={() => setActiveSuggestion((p) => (p - 1 + SUGGESTIONS.length) % SUGGESTIONS.length)}
              className="text-xs text-mantra-tertiary active:text-mantra-muted transition-colors px-2 py-1"
            >
              Previous
            </button>
            <div className="flex gap-2">
              {SUGGESTIONS.map((_, i) => (
                <button
                  key={i}
                  onClick={() => setActiveSuggestion(i)}
                  className={`rounded-full transition-all duration-300 ${
                    i === activeSuggestion ? 'w-5 h-2 bg-mantra-accent' : 'w-2 h-2 bg-mantra-tertiary/50'
                  }`}
                />
              ))}
            </div>
            <button
              onClick={() => setActiveSuggestion((p) => (p + 1) % SUGGESTIONS.length)}
              className="text-xs text-mantra-tertiary active:text-mantra-muted transition-colors px-2 py-1"
            >
              Next
            </button>
          </div>
        </div>
      </section>

      {/* Section Divider */}
      <div className="px-5 mb-5">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      {/* Manga Sources */}
      <section className="px-5 mb-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-bold text-mantra-text tracking-tight">Manga sources</h2>
          <button className="text-xs font-semibold text-mantra-accent active:opacity-70 transition-opacity">Manage</button>
        </div>
        <div className="grid grid-cols-3 gap-4">
          {SOURCES.map((source, i) => (
            <motion.button
              key={source.id}
              initial={{ opacity: 0, scale: 0.85 }}
              animate={{ opacity: 1, scale: 1 }}
              transition={{ delay: i * 0.03, duration: 0.3 }}
              whileTap={{ scale: 0.9 }}
              className="flex flex-col items-center active:opacity-70 transition-opacity"
            >
              <div className="w-14 h-14 rounded-2xl bg-mantra-surface border border-white/[0.06] flex items-center justify-center text-2xl mb-2 shadow-sm">
                {source.icon}
              </div>
              <span className="text-xs text-mantra-text font-medium truncate w-full text-center">{source.name}</span>
            </motion.button>
          ))}
        </div>
      </section>

      {/* Featured Banner */}
      <section className="px-5 pb-4">
        <motion.div
          whileTap={{ scale: 1.02 }}
          onClick={() => navigateTo('detail', MANGA_DATA[7].id)}
          className="relative w-full h-36 rounded-2xl overflow-hidden cursor-pointer active:scale-[0.98] transition-transform duration-100"
        >
          <img src={MANGA_DATA[7].cover} alt="Featured" className="absolute inset-0 w-full h-full object-cover" style={{ filter: 'blur(40px) brightness(0.35)' }} />
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <img src={MANGA_DATA[7].cover} alt="Featured" className="w-16 h-24 object-cover rounded-lg shadow-elevated mb-2 ring-1 ring-white/10" />
            <p className="text-sm font-bold text-mantra-text">Featured this week</p>
            <p className="text-[11px] text-mantra-accent mt-0.5">{MANGA_DATA[7].title}</p>
          </div>
        </motion.div>
      </section>
    </div>
  );
}
