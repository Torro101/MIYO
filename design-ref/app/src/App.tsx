import { AnimatePresence, motion } from 'framer-motion';
import { useStore } from '@/store';
import BottomNavigation from '@/components/BottomNavigation';
import Toast from '@/components/Toast';
import HomeScreen from '@/pages/HomeScreen';
import FavoritesScreen from '@/pages/FavoritesScreen';
import ExploreScreen from '@/pages/ExploreScreen';
import FeedScreen from '@/pages/FeedScreen';
import HistoryScreen from '@/pages/HistoryScreen';
import DetailScreen from '@/pages/DetailScreen';
import SearchScreen from '@/pages/SearchScreen';
import type { Screen } from '@/types';

const screenComponents: Record<Screen, React.ComponentType> = {
  home: HomeScreen,
  favorites: FavoritesScreen,
  explore: ExploreScreen,
  feed: FeedScreen,
  history: HistoryScreen,
  detail: DetailScreen,
  search: SearchScreen,
};

const mainTabs: Screen[] = ['home', 'favorites', 'explore', 'feed', 'history'];

export default function App() {
  const { currentScreen, showBottomNav } = useStore();
  const ScreenComponent = screenComponents[currentScreen] || HomeScreen;
  const isMainTab = mainTabs.includes(currentScreen);

  return (
    <div className="h-screen w-full bg-black flex justify-center items-center p-0 md:p-4 overflow-hidden">
      {/* Mobile viewport container */}
      <div
        className="w-full max-w-md bg-mantra-bg rounded-none overflow-hidden shadow-2xl relative isolate flex flex-col"
        style={{
          height: '100dvh',
          maxHeight: '900px',
        }}
      >
        {/* Screen Content with transitions */}
        <main className="flex-1 overflow-hidden relative">
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={currentScreen}
              initial={
                currentScreen === 'search'
                  ? { y: '100%', opacity: 1 }
                  : !isMainTab
                  ? { x: 60, opacity: 0 }
                  : { opacity: 1 }
              }
              animate={{ x: 0, y: 0, opacity: 1 }}
              exit={
                currentScreen === 'search'
                  ? { y: '100%', opacity: 1 }
                  : !isMainTab
                  ? { x: 60, opacity: 0 }
                  : { opacity: 1 }
              }
              transition={{ duration: 0.28, ease: [0.32, 0.72, 0, 1] }}
              className="absolute inset-0"
              style={{ willChange: 'transform, opacity' }}
            >
              <ScreenComponent />
            </motion.div>
          </AnimatePresence>
        </main>

        {/* Bottom Navigation */}
        <AnimatePresence>
          {showBottomNav && (
            <motion.div
              initial={{ y: 100 }}
              animate={{ y: 0 }}
              exit={{ y: 100 }}
              transition={{ type: 'spring', stiffness: 300, damping: 30 }}
            >
              <BottomNavigation />
            </motion.div>
          )}
        </AnimatePresence>

        {/* Toast */}
        <Toast />
      </div>
    </div>
  );
}
