import { useState, useMemo } from 'react';
import { motion } from 'framer-motion';
import { BookOpen, Heart } from 'lucide-react';
import SearchBar from '@/components/SearchBar';
import MangaCoverCard from '@/components/MangaCoverCard';
import CategoryPills from '@/components/CategoryPills';
import { useStore } from '@/store';
import { CATEGORIES } from '@/data';

export default function FavoritesScreen() {
  const { manga, activeCategory, setActiveCategory, navigateTo } = useStore();
  const [activeFilter, setActiveFilter] = useState<string | null>(null);

  const filters = ['On device', 'New chapters', 'Completed'];

  const filteredManga = useMemo(() => {
    let result = manga.filter(m => m.isFavorited);
    if (activeCategory !== 'Read later') {
      result = result.filter(m => m.category === activeCategory);
    }
    if (activeFilter === 'New chapters') {
      result = result.filter(m => m.unreadCount > 0);
    } else if (activeFilter === 'Completed') {
      result = result.filter(m => m.progress >= 100);
    }
    return result;
  }, [manga, activeCategory, activeFilter]);

  return (
    <div className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Search */}
      <div className="px-5 pt-4 pb-3 sticky top-0 z-30" style={{ background: 'rgba(13, 11, 15, 0.85)', backdropFilter: 'blur(16px)' }}>
        <SearchBar onFocus={() => navigateTo('search')} />
      </div>

      {/* Category Tabs with Hearts */}
      <CategoryPills categories={CATEGORIES} active={activeCategory} onChange={setActiveCategory} showHearts />

      {/* Filter Chips */}
      <div className="flex gap-2 px-5 mt-3 mb-4">
        {filters.map((filter) => (
          <button
            key={filter}
            onClick={() => setActiveFilter(activeFilter === filter ? null : filter)}
            className={`h-8 px-3.5 rounded-full text-xs font-medium transition-all duration-200 border active:scale-95 ${
              activeFilter === filter
                ? 'bg-mantra-accent border-transparent text-mantra-bg'
                : 'bg-mantra-elevated border-mantra-tertiary/25 text-mantra-text'
            }`}
          >
            {filter}
          </button>
        ))}
      </div>

      {/* Results count */}
      <div className="flex items-center justify-between px-5 mb-3">
        <span className="text-xs text-mantra-muted">{filteredManga.length} titles</span>
        <Heart size={14} className="text-mantra-error" strokeWidth={2} />
      </div>

      {/* Favorites Grid */}
      {filteredManga.length > 0 ? (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="px-5"
        >
          <div className="grid grid-cols-3 gap-x-3 gap-y-5">
            {filteredManga.map((m, i) => (
              <MangaCoverCard key={m.id} manga={m} size="small" index={i} />
            ))}
          </div>
        </motion.div>
      ) : (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="flex flex-col items-center justify-center py-24 px-8"
        >
          <motion.div
            animate={{ y: [-4, 4, -4] }}
            transition={{ duration: 3, repeat: Infinity, ease: 'easeInOut' }}
          >
            <BookOpen size={72} className="text-mantra-tertiary mb-5" strokeWidth={1} />
          </motion.div>
          <p className="text-mantra-text text-base font-semibold mb-1">No manga here yet</p>
          <p className="text-mantra-muted text-sm text-center leading-relaxed">
            Browse the Explore tab to find manga to add to your collection
          </p>
        </motion.div>
      )}
    </div>
  );
}
