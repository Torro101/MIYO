import { useRef, useEffect, useState, useCallback } from 'react';
import { motion } from 'framer-motion';
import { LayoutGrid, Heart, Compass, Rss, Clock } from 'lucide-react';
import type { Tab } from '@/types';
import { useStore } from '@/store';

const TABS: { key: Tab; label: string; icon: typeof LayoutGrid }[] = [
  { key: 'home', label: 'Home', icon: LayoutGrid },
  { key: 'favorites', label: 'Favorites', icon: Heart },
  { key: 'explore', label: 'Explore', icon: Compass },
  { key: 'feed', label: 'Feed', icon: Rss },
  { key: 'history', label: 'History', icon: Clock },
];

export default function BottomNavigation() {
  const { activeTab, setActiveTab, feedBadgeCount } = useStore();
  const navRef = useRef<HTMLDivElement>(null);
  const [pillStyle, setPillStyle] = useState({ left: 0, width: 0 });
  const buttonRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const updatePill = useCallback(() => {
    const activeIndex = TABS.findIndex(t => t.key === activeTab);
    const btn = buttonRefs.current[activeIndex];
    const nav = navRef.current;
    if (!btn || !nav) return;
    const navRect = nav.getBoundingClientRect();
    const btnRect = btn.getBoundingClientRect();
    setPillStyle({
      left: btnRect.left - navRect.left,
      width: btnRect.width,
    });
  }, [activeTab]);

  useEffect(() => {
    updatePill();
    window.addEventListener('resize', updatePill);
    return () => window.removeEventListener('resize', updatePill);
  }, [updatePill]);

  return (
    <nav
      ref={navRef}
      className="fixed bottom-0 left-0 right-0 z-50 h-16 border-t border-white/[0.06]"
      style={{
        background: 'rgba(22, 18, 26, 0.92)',
        backdropFilter: 'blur(24px) saturate(130%)',
        WebkitBackdropFilter: 'blur(24px) saturate(130%)',
      }}
    >
      <div className="mx-auto max-w-md h-full flex items-center justify-around px-2 relative">
        {/* Active pill background */}
        <motion.div
          className="absolute top-1/2 -translate-y-1/2 h-11 rounded-2xl bg-mantra-elevated/70 pointer-events-none"
          animate={{
            left: pillStyle.left + pillStyle.width * 0.1,
            width: pillStyle.width * 0.8,
          }}
          transition={{ type: 'spring', stiffness: 350, damping: 32 }}
          style={{ willChange: 'left, width' }}
        />

        {TABS.map((tab, i) => {
          const isActive = activeTab === tab.key;
          const Icon = tab.icon;
          return (
            <button
              key={tab.key}
              ref={el => { buttonRefs.current[i] = el; }}
              onClick={() => setActiveTab(tab.key)}
              className="relative flex flex-col items-center justify-center w-14 h-14 z-10 select-none rounded-xl active:scale-90 transition-transform duration-100"
            >
              <div className="relative">
                <Icon
                  size={22}
                  strokeWidth={isActive ? 2.5 : 1.5}
                  className={`transition-colors duration-200 ${
                    isActive ? 'text-mantra-accent' : 'text-mantra-tertiary'
                  }`}
                />
                {/* Feed badge */}
                {tab.key === 'feed' && feedBadgeCount > 0 && (
                  <motion.span
                    initial={{ scale: 0 }}
                    animate={{ scale: 1 }}
                    className="absolute -top-1.5 -right-3 min-w-[18px] h-[18px] rounded-full bg-mantra-error text-white text-[10px] font-bold flex items-center justify-center px-1 animate-pulse-badge"
                  >
                    {feedBadgeCount > 99 ? '99+' : feedBadgeCount}
                  </motion.span>
                )}
              </div>
              <span
                className={`text-[10px] font-semibold mt-0.5 tracking-wide transition-colors duration-200 ${
                  isActive ? 'text-mantra-accent' : 'text-mantra-tertiary'
                }`}
              >
                {tab.label}
              </span>
              {/* Active dot - more visible */}
              {isActive && (
                <motion.div
                  layoutId="activeTabDot"
                  className="absolute -bottom-0.5 w-1.5 h-1.5 rounded-full bg-mantra-accent"
                  style={{ boxShadow: '0 0 6px rgba(212, 148, 106, 0.6)' }}
                  transition={{ type: 'spring', stiffness: 400, damping: 25 }}
                />
              )}
            </button>
          );
        })}
      </div>
    </nav>
  );
}
