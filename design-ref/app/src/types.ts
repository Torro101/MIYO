export interface Manga {
  id: string;
  title: string;
  cover: string;
  progress: number;
  currentChapter: number;
  totalChapters: number;
  unreadCount: number;
  isFavorited: boolean;
  category: string;
  status: 'ongoing' | 'completed';
  source: string;
  sizeMB: number;
  description: string;
  genres: string[];
  chapters: Chapter[];
}

export interface Chapter {
  id: string;
  number: number;
  title: string;
  date: string;
  isDownloaded: boolean;
  isRead: boolean;
}

export interface FeedItem {
  id: string;
  mangaId: string;
  mangaTitle: string;
  cover: string;
  newChapters: number;
  chapterTitle: string;
  date: string;
  isRead: boolean;
}

export interface Toast {
  id: string;
  message: string;
  type: 'success' | 'info' | 'error';
}

export type Tab = 'home' | 'favorites' | 'explore' | 'feed' | 'history';
export type Screen = 'home' | 'favorites' | 'explore' | 'feed' | 'history' | 'detail' | 'search';
