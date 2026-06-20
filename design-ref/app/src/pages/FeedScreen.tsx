import { useState, useMemo } from 'react';
import { motion } from 'framer-motion';
import { BookOpen, Bell, Zap } from 'lucide-react';
import SearchBar from '@/components/SearchBar';
import MangaCoverCard from '@/components/MangaCoverCard';
import CategoryPills from '@/components/CategoryPills';
import { useStore } from '@/store';
import { FEED_DATA, MANGA_DATA } from '@/data';

const FEED_CATEGORIES = ['All', 'Read later', 'Cultivation/Murim', 'Regress/FL/ML', 'Action'];

export default function FeedScreen() {
  const { navigateTo, markFeedRead, feedRead } = useStore();
  const [cat, setCat] = useState('All');

  const todayManga = useMemo(() => MANGA_DATA.filter(m => m.unreadCount > 0), []);

  return (
    <div className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Search */}
      <div className="px-5 pt-4 pb-3 sticky top-0 z-30" style={{ background: 'rgba(13, 11, 15, 0.85)', backdropFilter: 'blur(16px)' }}>
        <SearchBar onFocus={() => navigateTo('search')} />
      </div>

      {/* Category Pills */}
      <CategoryPills categories={FEED_CATEGORIES} active={cat} onChange={setCat} />

      {/* Updates Horizontal Row */}
      <section className="mt-5 mb-6">
        <div className="flex items-center justify-between px-5 mb-3">
          <div className="flex items-center gap-2">
            <Zap size={16} className="text-mantra-accent" />
            <h2 className="text-base font-bold text-mantra-text tracking-tight">Updates</h2>
          </div>
          <button className="text-xs font-semibold text-mantra-accent active:opacity-70 transition-opacity">More</button>
        </div>
        <div className="flex gap-3.5 overflow-x-auto hide-scrollbar px-5 snap-x snap-mandatory pb-1">
          {todayManga.map((manga, i) => (
            <div key={manga.id} className="snap-start">
              <MangaCoverCard manga={manga} size="medium" index={i} />
            </div>
          ))}
        </div>
      </section>

      {/* Section Divider */}
      <div className="px-5 mb-4">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      {/* Today's Updates List */}
      <section className="px-5">
        <div className="flex items-center gap-2 mb-3">
          <Bell size={14} className="text-mantra-accent" />
          <h2 className="text-base font-bold text-mantra-text tracking-tight">Today</h2>
        </div>

        {FEED_DATA.length > 0 ? (
          <div className="space-y-0">
            {FEED_DATA.map((item, i) => {
              const isRead = feedRead.has(item.id);
              return (
                <motion.div
                  key={item.id}
                  initial={{ opacity: 0, x: -12 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: i * 0.03, duration: 0.35, ease: [0.2, 0.8, 0.2, 1] }}
                  onClick={() => {
                    markFeedRead(item.id);
                    navigateTo('detail', item.mangaId);
                  }}
                  className="flex items-center gap-3 py-3 cursor-pointer border-b border-white/[0.04] last:border-0 relative active:bg-white/[0.02] transition-colors rounded-lg px-2 -mx-2"
                >
                  {/* New indicator */}
                  {!isRead && (
                    <div className="absolute left-0 top-1/2 -translate-y-1/2 w-[3px] h-5 bg-mantra-accent rounded-r-full" style={{ boxShadow: '0 0 8px rgba(212, 148, 106, 0.4)' }} />
                  )}
                  {/* Thumbnail */}
                  <div className="relative flex-shrink-0">
                    <img src={item.cover} alt={item.mangaTitle} className="w-12 h-16 rounded-lg object-cover" />
                    {!isRead && (
                      <div className="absolute -top-1 -right-1 w-4 h-4 rounded-full bg-mantra-error border-2 border-mantra-bg" />
                    )}
                  </div>
                  {/* Text */}
                  <div className="flex-1 min-w-0">
                    <p className="text-[15px] font-semibold text-mantra-text truncate">{item.mangaTitle}</p>
                    <p className="text-xs text-mantra-muted flex items-center gap-1.5 mt-1">
                      <span className="w-1.5 h-1.5 rounded-full bg-mantra-accent flex-shrink-0" />
                      {item.newChapters} new chapter{item.newChapters > 1 ? 's' : ''}
                    </p>
                    <p className="text-[11px] text-mantra-tertiary mt-0.5">{item.date}</p>
                  </div>
                  {/* Count badge */}
                  {item.newChapters > 1 && (
                    <span className="min-w-[24px] h-6 px-1.5 rounded-full bg-mantra-error/90 text-white text-[10px] font-bold flex items-center justify-center flex-shrink-0">
                      {item.newChapters}
                    </span>
                  )}
                </motion.div>
              );
            })}
          </div>
        ) : (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            className="flex flex-col items-center justify-center py-20"
          >
            <motion.div animate={{ y: [-3, 3, -3] }} transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}>
              <BookOpen size={72} className="text-mantra-tertiary mb-4" strokeWidth={1} />
            </motion.div>
            <p className="text-mantra-text font-semibold mb-1">No updates yet</p>
            <p className="text-mantra-muted text-sm text-center px-8 leading-relaxed">
              New chapters from your followed manga will appear here
            </p>
          </motion.div>
        )}
      </section>
    </div>
  );
}
