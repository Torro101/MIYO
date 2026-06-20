import { useState, useRef, useEffect } from 'react';
import { motion } from 'framer-motion';
import { Play, Download, Clock, Check, TrendingUp, Sparkles } from 'lucide-react';
import SearchBar from '@/components/SearchBar';
import MangaCoverCard from '@/components/MangaCoverCard';
import CategoryPills from '@/components/CategoryPills';
import { useStore } from '@/store';
import { CATEGORIES, getContinueReading, getMangaByCategory, MANGA_DATA } from '@/data';

const containerVariants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: { staggerChildren: 0.05, ease: 'easeOut' as const },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 20 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: [0.2, 0.8, 0.2, 1] as const } },
};

export default function HomeScreen() {
  const { navigateTo } = useStore();
  const [activeCategory, setActiveCategory] = useState('Read later');
  const scrollRef = useRef<HTMLDivElement>(null);
  const [lastScrollY, setLastScrollY] = useState(0);
  const [searchVisible, setSearchVisible] = useState(true);

  const continueReading = getContinueReading();
  const categoryManga = getMangaByCategory(activeCategory);
  const fillManga = MANGA_DATA.filter(m => !categoryManga.find(c => c.id === m.id)).slice(0, 6);
  const displayManga = [...categoryManga, ...fillManga].slice(0, 6);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const handleScroll = () => {
      const y = el.scrollTop;
      setSearchVisible(y < 60 || y < lastScrollY);
      setLastScrollY(y);
    };
    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => el.removeEventListener('scroll', handleScroll);
  }, [lastScrollY]);

  return (
    <div ref={scrollRef} className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Search Bar */}
      <motion.div
        animate={{ y: searchVisible ? 0 : -80, opacity: searchVisible ? 1 : 0 }}
        transition={{ duration: 0.25, ease: 'easeInOut' }}
        className="px-5 pt-4 pb-2 sticky top-0 z-30"
        style={{ background: 'rgba(13, 11, 15, 0.85)', backdropFilter: 'blur(16px)' }}
      >
        <SearchBar onFocus={() => navigateTo('search')} />
      </motion.div>

      {/* Greeting + Stats Row */}
      <motion.div variants={itemVariants} initial="hidden" animate="show" className="px-5 py-3 flex items-center justify-between">
        <div>
          <p className="text-xs text-mantra-muted font-medium tracking-wide uppercase">Welcome back</p>
          <h1 className="text-2xl font-bold text-mantra-text tracking-tight leading-tight mt-0.5">
            Good evening
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center gap-1.5 bg-mantra-elevated/60 px-3 py-1.5 rounded-full border border-white/[0.04]">
            <TrendingUp size={13} className="text-mantra-accent" />
            <span className="text-xs text-mantra-accent font-semibold">{continueReading.length} reading</span>
          </div>
        </div>
      </motion.div>

      {/* Continue Reading */}
      {continueReading.length > 0 && (
        <section className="mb-6">
          <motion.div variants={itemVariants} initial="hidden" animate="show" className="flex items-center justify-between px-5 mb-3">
            <div className="flex items-center gap-2">
              <Sparkles size={16} className="text-mantra-accent" />
              <h2 className="text-base font-bold text-mantra-text tracking-tight">Continue Reading</h2>
            </div>
            <button className="text-xs font-medium text-mantra-accent active:opacity-70 transition-opacity">See all</button>
          </motion.div>
          <div className="flex gap-3.5 overflow-x-auto hide-scrollbar px-5 snap-x snap-mandatory pb-2">
            {continueReading.map((manga, i) => (
              <motion.div
                key={manga.id}
                variants={itemVariants}
                initial="hidden"
                animate="show"
                custom={i}
                className={`snap-start flex-shrink-0 ${i === 0 ? 'relative' : ''}`}
              >
                {i === 0 && (
                  <div className="absolute -inset-3 rounded-2xl pointer-events-none overflow-hidden">
                    <div className="absolute inset-0 ambient-glow-bg" />
                  </div>
                )}
                <div
                  className="w-[140px] cursor-pointer select-none active:scale-[0.96] transition-transform duration-100"
                  onClick={() => navigateTo('detail', manga.id)}
                >
                  <div className="relative h-[210px] rounded-card overflow-hidden shadow-card">
                    <img src={manga.cover} alt={manga.title} loading="lazy" className="absolute inset-0 w-full h-full object-cover" />
                    {/* Gradient overlay at bottom */}
                    <div className="absolute inset-x-0 bottom-0 h-16 bg-gradient-to-t from-black/60 to-transparent" />
                    {/* Progress bar integrated into card */}
                    <div className="absolute bottom-0 left-0 right-0 h-1 bg-black/30">
                      <motion.div
                        className="h-full bg-mantra-accent rounded-full"
                        initial={{ width: 0 }}
                        animate={{ width: `${manga.progress}%` }}
                        transition={{ duration: 0.8, ease: 'easeOut', delay: 0.2 }}
                      />
                    </div>
                    {/* Unread badge */}
                    {manga.unreadCount > 0 && (
                      <div className="absolute top-2 right-2 bg-mantra-error text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full">
                        {manga.unreadCount}
                      </div>
                    )}
                  </div>
                  <p className="mt-2 text-sm font-semibold text-mantra-text leading-tight truncate">{manga.title}</p>
                  <p className="text-[11px] text-mantra-muted mt-0.5">
                    Ch. {manga.currentChapter} · {manga.progress}%
                  </p>
                </div>
              </motion.div>
            ))}
          </div>
        </section>
      )}

      {/* Quick Actions - Card Container */}
      <motion.section variants={itemVariants} initial="hidden" animate="show" className="px-5 mb-6">
        <div className="bg-mantra-surface rounded-2xl p-4 border border-white/[0.04]">
          <p className="text-xs text-mantra-muted font-medium mb-3 uppercase tracking-wider">Quick Access</p>
          <div className="grid grid-cols-2 gap-2.5">
            {[
              { icon: Download, label: 'On device', color: 'text-mantra-accent' },
              { icon: Clock, label: 'New chapters', color: 'text-mantra-success' },
              { icon: Check, label: 'Completed', color: 'text-mantra-accent' },
              { icon: Download, label: 'Downloads', color: 'text-mantra-muted' },
            ].map((action) => (
              <motion.button
                key={action.label}
                whileTap={{ scale: 0.96 }}
                className="flex items-center gap-2.5 h-11 px-3 rounded-xl bg-mantra-elevated/60 border border-white/[0.03] text-mantra-text text-sm font-medium active:bg-mantra-elevated transition-colors"
              >
                <action.icon size={16} className={action.color} strokeWidth={1.5} />
                {action.label}
              </motion.button>
            ))}
          </div>
        </div>
      </motion.section>

      {/* Section Divider */}
      <div className="px-5 mb-4">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      {/* Category Pills */}
      <CategoryPills categories={CATEGORIES} active={activeCategory} onChange={setActiveCategory} />

      {/* Category Header */}
      <div className="flex items-center justify-between px-5 mt-4 mb-3">
        <h2 className="text-base font-bold text-mantra-text tracking-tight">{activeCategory}</h2>
        <span className="text-xs text-mantra-muted">{displayManga.length} titles</span>
      </div>

      {/* Category Grid */}
      <motion.section
        variants={containerVariants}
        initial="hidden"
        animate="show"
        className="px-5"
      >
        <div className="grid grid-cols-3 gap-x-3 gap-y-5">
          {displayManga.map((manga, i) => (
            <motion.div key={manga.id} variants={itemVariants}>
              <MangaCoverCard manga={manga} size="small" index={i} />
            </motion.div>
          ))}
        </div>
      </motion.section>

      {/* Bottom spacer for FAB */}
      <div className="h-20" />

      {/* Floating Continue Button - positioned safely */}
      {continueReading.length > 0 && (
        <motion.button
          initial={{ y: 100, opacity: 0, scale: 0.8 }}
          animate={{ y: 0, opacity: 1, scale: 1 }}
          transition={{ delay: 0.6, type: 'spring', stiffness: 200, damping: 20 }}
          whileHover={{ scale: 1.05 }}
          whileTap={{ scale: 0.9 }}
          className="fixed bottom-20 right-5 z-40 w-14 h-14 rounded-full bg-mantra-accent flex items-center justify-center"
          style={{
            boxShadow: '0 4px 24px rgba(212, 148, 106, 0.45), 0 0 60px rgba(212, 148, 106, 0.15)',
          }}
        >
          <div
            className="absolute -inset-4 rounded-full pointer-events-none animate-breathe"
            style={{
              background: 'radial-gradient(circle, rgba(212, 148, 106, 0.2) 0%, transparent 70%)',
            }}
          />
          <Play size={20} className="text-mantra-bg fill-mantra-bg relative z-10" />
        </motion.button>
      )}
    </div>
  );
}
