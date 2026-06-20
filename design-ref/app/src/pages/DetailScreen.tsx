import { useState } from 'react';
import { motion } from 'framer-motion';
import { ArrowLeft, Share2, Download, MoreVertical, List, LayoutGrid, Bookmark, ChevronDown, Play, Heart } from 'lucide-react';
import { useStore } from '@/store';
import { getMangaById } from '@/data';

export default function DetailScreen() {
  const { selectedMangaId, goBack, toggleFavorite, navigateTo } = useStore();
  const [descExpanded, setDescExpanded] = useState(false);
  const [coverLoaded, setCoverLoaded] = useState(false);

  const manga = selectedMangaId ? getMangaById(selectedMangaId) : getMangaById('1');

  if (!manga) {
    return (
      <div className="h-full flex items-center justify-center">
        <p className="text-mantra-muted">Manga not found</p>
      </div>
    );
  }

  return (
    <div className="h-full overflow-y-auto hide-scrollbar pb-28" style={{ overscrollBehaviorY: 'auto' }}>
      {/* Hero Cover Section */}
      <div className="relative h-[240px] overflow-hidden">
        {/* Blurred background cover */}
        <img
          src={manga.cover}
          alt=""
          className="absolute inset-0 w-full h-full object-cover"
          style={{ filter: 'blur(30px) brightness(0.3) saturate(1.3)' }}
        />
        <div className="absolute inset-0 bg-gradient-to-b from-mantra-bg/40 via-transparent to-mantra-bg" />

        {/* Header overlay */}
        <div className="absolute top-0 left-0 right-0 z-40 flex items-center justify-between px-4 py-3">
          <button onClick={goBack} className="w-10 h-10 flex items-center justify-center rounded-full bg-black/30 backdrop-blur-sm active:bg-black/50 transition-colors">
            <ArrowLeft size={22} className="text-white" strokeWidth={1.5} />
          </button>
          <div className="flex items-center gap-1">
            <button className="w-10 h-10 flex items-center justify-center rounded-full bg-black/30 backdrop-blur-sm active:bg-black/50 transition-colors">
              <Share2 size={18} className="text-white" strokeWidth={1.5} />
            </button>
            <button className="w-10 h-10 flex items-center justify-center rounded-full bg-black/30 backdrop-blur-sm active:bg-black/50 transition-colors">
              <Download size={18} className="text-white" strokeWidth={1.5} />
            </button>
            <button className="w-10 h-10 flex items-center justify-center rounded-full bg-black/30 backdrop-blur-sm active:bg-black/50 transition-colors">
              <MoreVertical size={18} className="text-white" strokeWidth={1.5} />
            </button>
          </div>
        </div>

        {/* Center cover in hero */}
        <div className="absolute inset-0 flex items-center justify-center pt-6">
          <motion.div
            initial={{ opacity: 0, scale: 0.9, y: 20 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            transition={{ delay: 0.1, duration: 0.5, ease: [0.2, 0.8, 0.2, 1] }}
            className="relative"
          >
            <div className="w-[120px] h-[180px] rounded-xl overflow-hidden shadow-elevated ring-2 ring-white/10">
              {!coverLoaded && <div className="w-full h-full shimmer" />}
              <img
                src={manga.cover}
                alt={manga.title}
                loading="eager"
                onLoad={() => setCoverLoaded(true)}
                className={`w-full h-full object-cover transition-opacity duration-300 ${coverLoaded ? 'opacity-100' : 'opacity-0'}`}
              />
            </div>
            {/* Favorite heart on cover */}
            <button
              onClick={() => toggleFavorite(manga.id)}
              className="absolute -top-2 -right-2 w-8 h-8 rounded-full bg-mantra-surface border border-white/10 flex items-center justify-center shadow-lg active:scale-90 transition-transform z-10"
            >
              <Heart
                size={14}
                className={manga.isFavorited ? 'text-mantra-error fill-mantra-error' : 'text-mantra-muted'}
                strokeWidth={2}
              />
            </button>
          </motion.div>
        </div>
      </div>

      {/* Title Section */}
      <motion.div
        initial={{ opacity: 0, y: 15 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.2 }}
        className="px-5 text-center -mt-2 relative z-10"
      >
        <h1 className="text-xl font-bold text-mantra-text tracking-tight">{manga.title}</h1>
        <p className="text-xs text-mantra-muted mt-1">{manga.source} · {manga.status === 'ongoing' ? 'Ongoing' : 'Completed'}</p>
      </motion.div>

      {/* Progress Bar */}
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.3 }}
        className="px-5 mt-4"
      >
        <div className="flex items-center justify-between mb-1.5">
          <span className="text-xs text-mantra-muted">Reading progress</span>
          <span className="text-xs font-semibold text-mantra-accent">{manga.progress}%</span>
        </div>
        <div className="h-1.5 bg-mantra-elevated rounded-full overflow-hidden">
          <motion.div
            className="h-full rounded-full"
            style={{
              background: manga.progress >= 100
                ? 'linear-gradient(90deg, #6AB88A, #8BC9A4)'
                : 'linear-gradient(90deg, #D4946A, #E8B896)',
            }}
            initial={{ width: 0 }}
            animate={{ width: `${manga.progress}%` }}
            transition={{ duration: 1, ease: 'easeOut' }}
          />
        </div>
        <p className="text-xs text-mantra-muted mt-1.5">Chapter {manga.currentChapter} of {manga.totalChapters}</p>
      </motion.div>

      {/* Metadata Grid */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.35 }}
        className="px-5 mt-5"
      >
        <div className="grid grid-cols-3 gap-3">
          {[
            { label: 'Chapters', value: `${manga.totalChapters}` },
            { label: 'Size', value: `${manga.sizeMB} MB` },
            { label: 'Language', value: 'English' },
          ].map((item) => (
            <div key={item.label} className="bg-mantra-surface rounded-xl p-3 text-center border border-white/[0.04]">
              <p className="text-sm font-bold text-mantra-text">{item.value}</p>
              <p className="text-[11px] text-mantra-muted mt-0.5">{item.label}</p>
            </div>
          ))}
        </div>
      </motion.div>

      {/* Section Divider */}
      <div className="px-5 mt-6 mb-4">
        <div className="h-px bg-gradient-to-r from-transparent via-mantra-tertiary/20 to-transparent" />
      </div>

      {/* Description */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.4 }}
        className="px-5 mb-5"
      >
        <h3 className="text-sm font-bold text-mantra-text mb-2 uppercase tracking-wider">Description</h3>
        <p className={`text-[15px] text-mantra-muted leading-relaxed ${!descExpanded ? 'line-clamp-3' : ''}`}>
          {manga.description}
        </p>
        <button
          onClick={() => setDescExpanded(!descExpanded)}
          className="text-xs font-semibold text-mantra-accent mt-2 active:opacity-70 transition-opacity"
        >
          {descExpanded ? 'Show less' : 'Read more'}
        </button>
      </motion.div>

      {/* Genre Tags */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.45 }}
        className="px-5 mb-6"
      >
        <div className="flex flex-wrap gap-2">
          {manga.genres.map((genre) => (
            <span
              key={genre}
              className="h-8 px-3.5 rounded-full bg-mantra-elevated border border-mantra-tertiary/20 text-xs text-mantra-text font-medium flex items-center active:bg-mantra-tertiary/20 transition-colors"
            >
              {genre}
            </span>
          ))}
        </div>
      </motion.div>

      {/* Related Manga */}
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.5 }}
        className="mb-6"
      >
        <div className="flex items-center justify-between px-5 mb-3">
          <h3 className="text-sm font-bold text-mantra-text uppercase tracking-wider">Related manga</h3>
          <button className="text-xs font-semibold text-mantra-accent active:opacity-70 transition-opacity">Show all</button>
        </div>
        <div className="flex gap-3 overflow-x-auto hide-scrollbar px-5 snap-x snap-mandatory pb-2">
          {[1, 2, 3, 4].map((id) => {
            const related = getMangaById(String(id));
            if (!related || related.id === manga.id) return null;
            return (
              <motion.div
                key={related.id}
                whileTap={{ scale: 0.95 }}
                onClick={() => navigateTo('detail', related.id)}
                className="snap-start cursor-pointer flex-shrink-0"
              >
                <div className="w-[90px] h-[135px] rounded-xl overflow-hidden shadow-card ring-1 ring-white/[0.04]">
                  <img
                    src={related.cover}
                    alt={related.title}
                    className="w-full h-full object-cover"
                    loading="lazy"
                  />
                </div>
                <p className="mt-1.5 text-xs font-medium text-mantra-text truncate w-[90px]">{related.title}</p>
              </motion.div>
            );
          })}
        </div>
      </motion.div>

      {/* Bottom Action Bar */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.55 }}
        className="fixed bottom-0 left-0 right-0 z-40 px-5 py-3 flex items-center justify-between max-w-md mx-auto"
        style={{
          background: 'linear-gradient(to top, rgba(22, 18, 26, 0.98) 0%, rgba(22, 18, 26, 0.9) 70%, transparent)',
          backdropFilter: 'blur(16px)',
          paddingBottom: 'max(12px, env(safe-area-inset-bottom))',
        }}
      >
        <div className="flex items-center gap-0">
          <button className="w-11 h-11 flex items-center justify-center rounded-xl active:bg-mantra-elevated transition-colors">
            <List size={20} className="text-mantra-tertiary" strokeWidth={1.5} />
          </button>
          <button className="w-11 h-11 flex items-center justify-center rounded-xl active:bg-mantra-elevated transition-colors">
            <LayoutGrid size={20} className="text-mantra-tertiary" strokeWidth={1.5} />
          </button>
          <button className="w-11 h-11 flex items-center justify-center rounded-xl active:bg-mantra-elevated transition-colors">
            <Bookmark size={20} className="text-mantra-tertiary" strokeWidth={1.5} />
          </button>
        </div>
        <div className="flex items-center gap-2">
          <motion.button
            whileTap={{ scale: 0.95 }}
            className="h-12 px-6 rounded-xl bg-mantra-accent text-mantra-bg font-bold text-sm flex items-center gap-2 shadow-lg"
            style={{ boxShadow: '0 4px 20px rgba(212, 148, 106, 0.4)' }}
          >
            <Play size={16} className="fill-mantra-bg" />
            Continue
          </motion.button>
          <motion.button
            whileTap={{ scale: 0.95 }}
            className="w-12 h-12 rounded-xl bg-mantra-elevated border border-mantra-tertiary/20 flex items-center justify-center active:bg-mantra-surface transition-colors"
          >
            <ChevronDown size={18} className="text-mantra-text" strokeWidth={1.5} />
          </motion.button>
        </div>
      </motion.div>
    </div>
  );
}
