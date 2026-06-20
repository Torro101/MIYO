import { useState } from 'react';
import { motion } from 'framer-motion';
import { Heart, Check } from 'lucide-react';
import { useStore } from '@/store';

interface Props {
  manga: {
    id: string;
    title: string;
    cover: string;
    progress: number;
    unreadCount: number;
    isFavorited: boolean;
  };
  size?: 'small' | 'medium' | 'large';
  index?: number;
  showHeart?: boolean;
}

const SIZE_MAP = {
  small: { width: 'w-full', aspect: 'aspect-[2/3]', titleSize: 'text-[13px]' },
  medium: { width: 'w-[140px]', aspect: 'aspect-[2/3]', titleSize: 'text-sm' },
  large: { width: 'w-[170px]', aspect: 'aspect-[2/3]', titleSize: 'text-sm' },
};

function ProgressRing({ progress }: { progress: number }) {
  const radius = 13;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (progress / 100) * circumference;
  const isComplete = progress >= 100;

  return (
    <div className="relative w-8 h-8 flex items-center justify-center bg-black/40 rounded-full backdrop-blur-sm">
      <svg width="32" height="32" className="-rotate-90">
        <circle cx="16" cy="16" r={radius} fill="none" stroke="rgba(255,255,255,0.15)" strokeWidth="2.5" />
        <motion.circle
          cx="16" cy="16" r={radius} fill="none"
          stroke={isComplete ? '#6AB88A' : '#D4946A'}
          strokeWidth="2.5" strokeLinecap="round"
          strokeDasharray={circumference}
          initial={{ strokeDashoffset: circumference }}
          animate={{ strokeDashoffset: offset }}
          transition={{ duration: 0.6, ease: 'easeOut' }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        {isComplete ? (
          <Check size={10} className="text-mantra-success" strokeWidth={3} />
        ) : (
          <motion.span className="text-[9px] font-bold text-white" initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.3 }}>
            {progress}
          </motion.span>
        )}
      </div>
    </div>
  );
}

export default function MangaCoverCard({ manga, size = 'medium', index = 0, showHeart = true }: Props) {
  const { toggleFavorite, navigateTo } = useStore();
  const [imgLoaded, setImgLoaded] = useState(false);
  const [pressed, setPressed] = useState(false);
  const dims = SIZE_MAP[size];

  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: index * 0.04, duration: 0.4, ease: [0.2, 0.8, 0.2, 1] }}
      className={`${size !== 'small' ? dims.width : ''} flex-shrink-0 cursor-pointer select-none`}
      style={{ willChange: 'transform' }}
    >
      <div
        className={`relative ${dims.aspect} rounded-xl overflow-hidden shadow-card ${dims.width}`}
        onPointerDown={() => setPressed(true)}
        onPointerUp={() => setPressed(false)}
        onPointerLeave={() => setPressed(false)}
        onClick={() => navigateTo('detail', manga.id)}
        style={{
          transform: pressed ? 'scale(0.96)' : 'scale(1)',
          transition: 'transform 0.1s ease, box-shadow 0.2s ease',
          boxShadow: pressed ? '0 8px 30px rgba(212, 148, 106, 0.15)' : undefined,
        }}
      >
        {/* Shimmer placeholder */}
        {!imgLoaded && <div className="absolute inset-0 shimmer" />}
        {/* Cover image */}
        <img
          src={manga.cover}
          alt={manga.title}
          loading="lazy"
          onLoad={() => setImgLoaded(true)}
          className={`absolute inset-0 w-full h-full object-cover transition-opacity duration-300 ${imgLoaded ? 'opacity-100' : 'opacity-0'}`}
        />
        {/* Subtle vignette */}
        <div className="absolute inset-0 shadow-[inset_0_-20px_30px_-10px_rgba(0,0,0,0.3)]" />

        {/* Heart overlay */}
        {showHeart && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              toggleFavorite(manga.id);
            }}
            className="absolute top-2 left-2 w-7 h-7 rounded-full bg-black/40 backdrop-blur-sm flex items-center justify-center z-10 active:scale-75 transition-transform duration-100"
          >
            <Heart
              size={13}
              className={manga.isFavorited ? 'text-mantra-error fill-mantra-error' : 'text-white/80'}
              strokeWidth={2}
            />
          </button>
        )}

        {/* Unread badge */}
        {manga.unreadCount > 0 && (
          <motion.div
            initial={{ scale: 0 }}
            animate={{ scale: 1 }}
            transition={{ type: 'spring', stiffness: 400, damping: 20 }}
            className="absolute top-2 right-2 bg-mantra-error text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full z-10 shadow-lg"
          >
            {manga.unreadCount}
          </motion.div>
        )}

        {/* Progress ring */}
        <div className="absolute bottom-2 right-2 z-10">
          <ProgressRing progress={manga.progress} />
        </div>
      </div>
      {/* Title */}
      <p className={`mt-2 ${dims.titleSize} font-semibold text-mantra-text leading-snug line-clamp-2`}>
        {manga.title}
      </p>
    </motion.div>
  );
}
