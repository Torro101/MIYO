interface Props {
  categories: string[];
  active: string;
  onChange: (cat: string) => void;
  showHearts?: boolean;
}

export default function CategoryPills({ categories, active, onChange, showHearts = false }: Props) {
  return (
    <div className="flex gap-2 overflow-x-auto hide-scrollbar px-5 pb-1">
      {categories.map((cat) => {
        const isActive = active === cat;
        return (
          <button
            key={cat}
            onClick={() => onChange(cat)}
            className={`relative flex-shrink-0 h-9 px-4 rounded-pill flex items-center gap-1.5 transition-colors duration-200 border ${
              isActive
                ? 'bg-mantra-accent border-transparent text-mantra-bg'
                : 'bg-transparent border-mantra-tertiary/50 text-mantra-muted'
            }`}
          >
            {showHearts && (
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={isActive ? 'fill-current' : ''}>
                <path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"/>
              </svg>
            )}
            <span className="text-xs font-medium tracking-wide whitespace-nowrap">{cat}</span>
          </button>
        );
      })}
    </div>
  );
}
