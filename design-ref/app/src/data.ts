import type { Manga, FeedItem } from './types';

const now = new Date();
const fmt = (d: Date) => d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
const daysAgo = (n: number) => {
  const d = new Date(now);
  d.setDate(d.getDate() - n);
  return fmt(d);
};

export const CATEGORIES = ['Read later', 'Cultivation/Murim', 'Regress/FL/ML', 'Action', 'Romance', 'Fantasy'];

export const SOURCES = [
  { id: '1', name: 'MangaDex', icon: '📚' },
  { id: '2', name: 'Webtoon', icon: '🌐' },
  { id: '3', name: 'MangaPlus', icon: '➕' },
  { id: '4', name: 'Comikey', icon: '🔑' },
  { id: '5', name: 'Toonily', icon: '🎨' },
  { id: '6', name: 'ReaperScans', icon: '💀' },
];

const chapters: Record<string, { id: string; number: number; title: string; date: string; isDownloaded: boolean; isRead: boolean }[]> = {};

function genChapters(mangaId: string, total: number, readUpTo: number) {
  if (chapters[mangaId]) return chapters[mangaId];
  const list = Array.from({ length: total }, (_, i) => ({
    id: `${mangaId}-ch-${i + 1}`,
    number: i + 1,
    title: `Chapter ${i + 1}`,
    date: daysAgo(total - i),
    isDownloaded: i < readUpTo + 3,
    isRead: i < readUpTo,
  }));
  chapters[mangaId] = list;
  return list;
}

export const MANGA_DATA: Manga[] = [
  {
    id: '1', title: "Raijin's Fury", cover: '/covers/cover1.jpg',
    progress: 75, currentChapter: 36, totalChapters: 48,
    unreadCount: 5, isFavorited: true, category: 'Action',
    status: 'ongoing', source: 'MangaDex', sizeMB: 245,
    description: 'A legendary swordsman wielding the power of thunder seeks vengeance against the demon clan that destroyed his village. Each strike of his blade summons divine lightning.',
    genres: ['Action', 'Fantasy', 'Supernatural'],
    chapters: [],
  },
  {
    id: '2', title: 'Moonlit Rose Garden', cover: '/covers/cover2.jpg',
    progress: 40, currentChapter: 16, totalChapters: 40,
    unreadCount: 3, isFavorited: true, category: 'Romance',
    status: 'ongoing', source: 'Webtoon', sizeMB: 189,
    description: 'In a moonlit garden where roses never wither, a mysterious girl with silver hair guards a secret that could change the fate of two worlds.',
    genres: ['Romance', 'Fantasy', 'Drama'],
    chapters: [],
  },
  {
    id: '3', title: 'Ascending Spirit', cover: '/covers/cover3.jpg',
    progress: 60, currentChapter: 48, totalChapters: 80,
    unreadCount: 8, isFavorited: true, category: 'Cultivation/Murim',
    status: 'ongoing', source: 'ReaperScans', sizeMB: 320,
    description: 'In the martial world where strength is law, a discarded disciple discovers an ancient cultivation technique that lets him absorb spiritual energy from the heavens.',
    genres: ['Action', 'Cultivation', 'Adventure'],
    chapters: [],
  },
  {
    id: '4', title: 'The Accidental Archmage', cover: '/covers/cover4.jpg',
    progress: 25, currentChapter: 12, totalChapters: 50,
    unreadCount: 2, isFavorited: false, category: 'Fantasy',
    status: 'ongoing', source: 'MangaDex', sizeMB: 156,
    description: 'An ordinary high school student accidentally opens a portal to a magical realm during detention. Now he must master arcane arts to find his way home.',
    genres: ['Fantasy', 'Isekai', 'Comedy'],
    chapters: [],
  },
  {
    id: '5', title: 'Red Sky Ruin', cover: '/covers/cover5.jpg',
    progress: 90, currentChapter: 45, totalChapters: 50,
    unreadCount: 1, isFavorited: true, category: 'Action',
    status: 'ongoing', source: 'MangaPlus', sizeMB: 278,
    description: 'In a world consumed by eternal twilight, a lone survivor with crimson eyes wanders through ruined cities, searching for the truth behind the apocalypse.',
    genres: ['Action', 'Post-Apocalyptic', 'Seinen'],
    chapters: [],
  },
  {
    id: '6', title: 'Fox Spirit Lantern', cover: '/covers/cover6.jpg',
    progress: 55, currentChapter: 22, totalChapters: 40,
    unreadCount: 4, isFavorited: false, category: 'Fantasy',
    status: 'ongoing', source: 'Comikey', sizeMB: 198,
    description: 'A fox spirit girl tends to a sacred lantern that guides lost souls. When the lantern flickers, she must journey into the human world to restore its light.',
    genres: ['Fantasy', 'Supernatural', 'Slice of Life'],
    chapters: [],
  },
  {
    id: '7', title: 'Neon Noir Mysteries', cover: '/covers/cover7.jpg',
    progress: 30, currentChapter: 15, totalChapters: 50,
    unreadCount: 6, isFavorited: false, category: 'Action',
    status: 'ongoing', source: 'Toonily', sizeMB: 167,
    description: 'A faceless detective solves impossible crimes in a rain-soaked cyberpunk city where memories can be bought and sold on the black market.',
    genres: ['Mystery', 'Sci-Fi', 'Thriller'],
    chapters: [],
  },
  {
    id: '8', title: 'Starlight Chronicles', cover: '/covers/cover8.jpg',
    progress: 45, currentChapter: 27, totalChapters: 60,
    unreadCount: 3, isFavorited: true, category: 'Fantasy',
    status: 'ongoing', source: 'MangaDex', sizeMB: 234,
    description: 'A young prince discovers his bloodline carries the power to command starlight. With an ancient castle as his base, he must unite the fractured kingdoms.',
    genres: ['Fantasy', 'Adventure', 'Royal'],
    chapters: [],
  },
  {
    id: '9', title: 'Delicious Duel', cover: '/covers/cover9.jpg',
    progress: 80, currentChapter: 40, totalChapters: 50,
    unreadCount: 2, isFavorited: false, category: 'Romance',
    status: 'ongoing', source: 'Webtoon', sizeMB: 145,
    description: 'A cheerful chef enters the most prestigious cooking competition in the kingdom, where dishes are judged by magical creatures with very particular tastes.',
    genres: ['Cooking', 'Comedy', 'Slice of Life'],
    chapters: [],
  },
  {
    id: '10', title: 'Iceblood Legend', cover: '/covers/cover10.jpg',
    progress: 15, currentChapter: 9, totalChapters: 60,
    unreadCount: 7, isFavorited: false, category: 'Action',
    status: 'ongoing', source: 'ReaperScans', sizeMB: 289,
    description: 'A wolf-warrior with ice in his veins protects the northern territories from encroaching darkness. The aurora borealis is said to be his ancestors watching over him.',
    genres: ['Action', 'Adventure', 'Fantasy'],
    chapters: [],
  },
  {
    id: '11', title: 'Elemental Breakers', cover: '/covers/cover11.jpg',
    progress: 50, currentChapter: 30, totalChapters: 60,
    unreadCount: 4, isFavorited: true, category: 'Fantasy',
    status: 'ongoing', source: 'MangaPlus', sizeMB: 267,
    description: 'Four students at the Arcane Arts Academy discover they are the reincarnations of ancient elemental guardians. Together they must prevent a magical catastrophe.',
    genres: ['Fantasy', 'School Life', 'Supernatural'],
    chapters: [],
  },
  {
    id: '12', title: 'Blood Roses', cover: '/covers/cover12.jpg',
    progress: 35, currentChapter: 21, totalChapters: 60,
    unreadCount: 5, isFavorited: false, category: 'Romance',
    status: 'ongoing', source: 'Comikey', sizeMB: 213,
    description: 'A vampire queen rules from her gothic throne, surrounded by blood-red roses. When a human thief steals her most precious rose, she ventures into the mortal world for the first time in centuries.',
    genres: ['Romance', 'Supernatural', 'Drama'],
    chapters: [],
  },
];

// Populate chapters
MANGA_DATA.forEach(m => {
  m.chapters = genChapters(m.id, m.totalChapters, m.currentChapter);
});

export const FEED_DATA: FeedItem[] = [
  { id: 'f1', mangaId: '1', mangaTitle: "Raijin's Fury", cover: '/covers/cover1.jpg', newChapters: 2, chapterTitle: 'Ch. 36-37', date: daysAgo(0), isRead: false },
  { id: 'f2', mangaId: '3', mangaTitle: 'Ascending Spirit', cover: '/covers/cover3.jpg', newChapters: 3, chapterTitle: 'Ch. 48-50', date: daysAgo(0), isRead: false },
  { id: 'f3', mangaId: '5', mangaTitle: 'Red Sky Ruin', cover: '/covers/cover5.jpg', newChapters: 1, chapterTitle: 'Ch. 45', date: daysAgo(0), isRead: false },
  { id: 'f4', mangaId: '7', mangaTitle: 'Neon Noir Mysteries', cover: '/covers/cover7.jpg', newChapters: 2, chapterTitle: 'Ch. 15-16', date: daysAgo(0), isRead: false },
  { id: 'f5', mangaId: '11', mangaTitle: 'Elemental Breakers', cover: '/covers/cover11.jpg', newChapters: 2, chapterTitle: 'Ch. 30-31', date: daysAgo(1), isRead: false },
  { id: 'f6', mangaId: '8', mangaTitle: 'Starlight Chronicles', cover: '/covers/cover8.jpg', newChapters: 1, chapterTitle: 'Ch. 27', date: daysAgo(1), isRead: false },
  { id: 'f7', mangaId: '12', mangaTitle: 'Blood Roses', cover: '/covers/cover12.jpg', newChapters: 2, chapterTitle: 'Ch. 21-22', date: daysAgo(2), isRead: false },
  { id: 'f8', mangaId: '10', mangaTitle: 'Iceblood Legend', cover: '/covers/cover10.jpg', newChapters: 3, chapterTitle: 'Ch. 9-11', date: daysAgo(2), isRead: false },
];

export function getMangaById(id: string): Manga | undefined {
  return MANGA_DATA.find(m => m.id === id);
}

export function getMangaByCategory(category: string): Manga[] {
  return MANGA_DATA.filter(m => m.category === category);
}

export function getFavoritedManga(): Manga[] {
  return MANGA_DATA.filter(m => m.isFavorited);
}

export function getContinueReading(): Manga[] {
  return MANGA_DATA.filter(m => m.progress > 0 && m.progress < 100).sort((a, b) => b.progress - a.progress);
}

export function getUnreadFeedCount(): number {
  return FEED_DATA.filter(f => !f.isRead).reduce((sum, f) => sum + f.newChapters, 0);
}
