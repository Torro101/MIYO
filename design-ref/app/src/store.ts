import { create } from 'zustand';
import type { Tab, Screen, Manga, Toast } from './types';
import { MANGA_DATA, FEED_DATA } from './data';

interface AppState {
  // Navigation
  activeTab: Tab;
  currentScreen: Screen;
  selectedMangaId: string | null;
  previousScreen: Screen | null;
  setActiveTab: (tab: Tab) => void;
  navigateTo: (screen: Screen, mangaId?: string) => void;
  goBack: () => void;

  // Manga data
  manga: Manga[];
  toggleFavorite: (id: string) => void;
  updateProgress: (id: string, chapter: number) => void;

  // Favorites
  activeCategory: string;
  setActiveCategory: (cat: string) => void;
  activeFilter: string | null;
  setActiveFilter: (filter: string | null) => void;

  // Feed
  feedRead: Set<string>;
  markFeedRead: (id: string) => void;
  feedBadgeCount: number;

  // UI
  showBottomNav: boolean;
  setShowBottomNav: (show: boolean) => void;
  toast: Toast | null;
  showToast: (message: string, type?: Toast['type']) => void;
  dismissToast: () => void;

  // Search
  searchQuery: string;
  setSearchQuery: (q: string) => void;

  // Explore
  activeSuggestionIndex: number;
  setActiveSuggestionIndex: (i: number) => void;
}

export const useStore = create<AppState>((set, get) => ({
  activeTab: 'home',
  currentScreen: 'home',
  selectedMangaId: null,
  previousScreen: null,

  manga: [...MANGA_DATA],

  activeCategory: 'Read later',
  activeFilter: null,

  feedRead: new Set(),
  feedBadgeCount: FEED_DATA.filter(f => !f.isRead).reduce((s, f) => s + f.newChapters, 0),

  showBottomNav: true,
  toast: null,

  searchQuery: '',

  activeSuggestionIndex: 0,

  setActiveTab: (tab) => set({ activeTab: tab, currentScreen: tab as Screen }),

  navigateTo: (screen, mangaId) => {
    const prev = get().currentScreen;
    set({
      currentScreen: screen,
      previousScreen: prev,
      selectedMangaId: mangaId || null,
      showBottomNav: screen === 'home' || screen === 'favorites' || screen === 'explore' || screen === 'feed' || screen === 'history',
    });
  },

  goBack: () => {
    const prev = get().previousScreen;
    if (prev) {
      const tabMap: Record<string, Tab> = {
        home: 'home', favorites: 'favorites', explore: 'explore', feed: 'feed', history: 'history',
      };
      set({
        currentScreen: prev,
        previousScreen: null,
        showBottomNav: true,
        activeTab: tabMap[prev] || get().activeTab,
      });
    } else {
      set({ currentScreen: get().activeTab as Screen, showBottomNav: true });
    }
  },

  toggleFavorite: (id) => {
    set(state => ({
      manga: state.manga.map(m =>
        m.id === id ? { ...m, isFavorited: !m.isFavorited } : m
      ),
    }));
    const m = get().manga.find(x => x.id === id);
    get().showToast(m?.isFavorited ? 'Removed from favorites' : 'Added to favorites', 'success');
  },

  updateProgress: (id, chapter) => {
    set(state => ({
      manga: state.manga.map(m => {
        if (m.id !== id) return m;
        const newCurrent = Math.min(chapter, m.totalChapters);
        const progress = Math.round((newCurrent / m.totalChapters) * 100);
        return { ...m, currentChapter: newCurrent, progress };
      }),
    }));
  },

  setActiveCategory: (cat) => set({ activeCategory: cat }),
  setActiveFilter: (filter) => set({ activeFilter: filter }),

  markFeedRead: (id) => {
    set(state => {
      const newSet = new Set(state.feedRead);
      newSet.add(id);
      const item = FEED_DATA.find(f => f.id === id);
      const count = item ? state.feedBadgeCount - item.newChapters : state.feedBadgeCount;
      return { feedRead: newSet, feedBadgeCount: Math.max(0, count) };
    });
  },

  setShowBottomNav: (show) => set({ showBottomNav: show }),

  showToast: (message, type = 'info') => {
    const id = Date.now().toString();
    set({ toast: { id, message, type } });
    setTimeout(() => {
      if (get().toast?.id === id) {
        set({ toast: null });
      }
    }, 2500);
  },

  dismissToast: () => set({ toast: null }),

  setSearchQuery: (q) => set({ searchQuery: q }),
  setActiveSuggestionIndex: (i) => set({ activeSuggestionIndex: i }),
}));
