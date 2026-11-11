package com.bgsoftware.wildstacker.utils.threads;

import java.lang.reflect.Field;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Makes the spawner ticker lookup also see the tickers a server has not flushed yet.
 * <p>
 * {@code Level.addBlockEntityTicker} puts a new ticker in {@code pendingBlockEntityTickers} instead of
 * {@code blockEntityTickers} while block entities are ticking. DivineMC's Regionized Chunk Ticking does it for every
 * new ticker (the pending list is flushed at the next {@code Level.tickBlockEntities}). The spawner lookup runs at chunk
 * load and at spawner placement, scans {@code blockEntityTickers} only, never finds those tickers and never wraps them:
 * the spawner keeps ticking as a vanilla spawner (vanilla placement rules, no stacking conditions).
 * <p>
 * {@link #view} returns a list that walks {@code blockEntityTickers} then a copy of {@code pendingBlockEntityTickers}.
 * {@code Iterator.remove()} removes the element from the list that holds it, {@code add()} goes to
 * {@code blockEntityTickers}. Falls back to the plain list when the server has no such field (obfuscated runtimes).
 */
public final class RctTickers {

    private static volatile Field pendingField;
    private static volatile boolean searched;

    private RctTickers() {
    }

    public static <T> List<T> view(List<T> main, Object level) {
        try {
            Field field = pendingField;
            if (field == null && !searched) {
                searched = true;
                for (Class<?> clazz = level.getClass(); clazz != null && field == null; clazz = clazz.getSuperclass()) {
                    try {
                        field = clazz.getDeclaredField("pendingBlockEntityTickers");
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                if (field != null)
                    field.setAccessible(true);
                pendingField = field;
            }

            if (field == null)
                return main;

            @SuppressWarnings("unchecked")
            List<T> pending = (List<T>) field.get(level);
            if (pending == null || pending.isEmpty())
                return main;

            return new Combined<>(main, pending);
        } catch (Throwable error) {
            return main;
        }
    }

    private static final class Combined<T> extends AbstractList<T> {

        private final List<T> main;
        private final List<T> pending;

        Combined(List<T> main, List<T> pending) {
            this.main = main;
            this.pending = pending;
        }

        @Override
        public T get(int index) {
            return index < main.size() ? main.get(index) : pending.get(index - main.size());
        }

        @Override
        public int size() {
            return main.size() + pending.size();
        }

        @Override
        public boolean add(T element) {
            return main.add(element);
        }

        @Override
        public Iterator<T> iterator() {
            final Iterator<T> mainIterator = main.iterator();
            // The pending list is copied: remove() acts on the list that holds the element.
            final Iterator<T> pendingIterator = new ArrayList<>(pending).iterator();
            return new Iterator<T>() {
                private Iterator<T> last;
                private T lastValue;

                @Override
                public boolean hasNext() {
                    return mainIterator.hasNext() || pendingIterator.hasNext();
                }

                @Override
                public T next() {
                    if (mainIterator.hasNext()) {
                        last = mainIterator;
                        return lastValue = mainIterator.next();
                    }
                    last = null;
                    return lastValue = pendingIterator.next();
                }

                @Override
                public void remove() {
                    if (last != null)
                        last.remove();
                    else
                        pending.remove(lastValue);
                }
            };
        }

    }

}
