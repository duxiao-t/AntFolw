import { create } from 'zustand';

export const MAX_FAVORITE_APPS = 8;
export const FAVORITE_LIMIT_MESSAGE = '最多添加 8 个常用应用';

export interface FavoriteDraftState {
  readonly ids: ReadonlyArray<number>;
  readonly source: ReadonlyArray<number>;
  readonly isDirty: boolean;
  readonly initialized: boolean;
  readonly reason: 'limit' | null;
  add(id: number): void;
  remove(id: number): void;
  move(from: number, to: number): void;
  reset(ids: ReadonlyArray<number>): void;
  syncSource(ids: ReadonlyArray<number>): void;
  markClean(): void;
  isLimitReached(): boolean;
}

function sameIds(left: ReadonlyArray<number>, right: ReadonlyArray<number>): boolean {
  return left.length === right.length && left.every((id, index) => id === right[index]);
}

function appendMissing(source: ReadonlyArray<number>, extra: ReadonlyArray<number>): number[] {
  const next = source.slice(0, MAX_FAVORITE_APPS);
  extra.forEach((id) => {
    if (next.length < MAX_FAVORITE_APPS && !next.includes(id)) next.push(id);
  });
  return next;
}

export const useFavoriteDraftStore = create<FavoriteDraftState>((set, get) => ({
  ids: [],
  source: [],
  isDirty: false,
  initialized: false,
  reason: null,

  add(id) {
    const current = get().ids;
    if (current.includes(id)) return;
    if (current.length >= MAX_FAVORITE_APPS) {
      set({ reason: 'limit' });
      return;
    }
    const next = [...current, id];
    set({ ids: next, isDirty: !sameIds(next, get().source), reason: null });
  },

  remove(id) {
    const current = get().ids;
    const next = current.filter((entry) => entry !== id);
    set({
      ids: next,
      isDirty: !sameIds(next, get().source),
      reason: null,
    });
  },

  move(from, to) {
    const current = [...get().ids];
    if (from < 0 || from >= current.length || to < 0 || to >= current.length) return;
    const [moved] = current.splice(from, 1);
    if (moved === undefined) return;
    current.splice(to, 0, moved);
    set({ ids: current, isDirty: !sameIds(current, get().source), reason: null });
  },

  reset(ids) {
    set({
      ids: [...ids],
      source: [...ids],
      isDirty: false,
      initialized: true,
      reason: null,
    });
  },

  syncSource(ids) {
    const current = get();
    const source = [...ids];
    if (!current.initialized && current.isDirty) {
      const next = appendMissing(source, current.ids);
      set({
        ids: next,
        source,
        isDirty: !sameIds(next, source),
        initialized: true,
        reason: null,
      });
      return;
    }
    if (current.isDirty) return;
    if (current.initialized && sameIds(current.ids, source) && sameIds(current.source, source)) {
      return;
    }
    set({
      ids: source,
      source,
      isDirty: false,
      initialized: true,
      reason: null,
    });
  },

  markClean() {
    set({
      source: [...get().ids],
      isDirty: false,
      initialized: true,
      reason: null,
    });
  },

  isLimitReached() {
    return get().ids.length >= MAX_FAVORITE_APPS;
  },
}));
