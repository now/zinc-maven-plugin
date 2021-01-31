package se.disu.util;

import java.util.Objects;

public interface Couple<T, U> {
  T fst();

  U snd();

  default void accept(java.util.function.BiConsumer<? super T, ? super U> c) {
    c.accept(fst(), snd());
  }

  default <R> R apply(java.util.function.BiFunction<? super T, ? super U, R> f) {
    return f.apply(fst(), snd());
  }

  default Couple<T, U> peek(java.util.function.BiConsumer<? super T, ? super U> c) {
    accept(c);
    return this;
  }

  default <V, W> Couple<V, W> map(
      java.util.function.Function<? super T, ? extends V> f,
      java.util.function.Function<? super U, ? extends W> g) {
    return of(f.apply(fst()), g.apply(snd()));
  }

  default <V> Couple<V, U> mapFst(java.util.function.Function<? super T, ? extends V> f) {
    return of(f.apply(fst()), snd());
  }

  default <V> Couple<T, V> mapSnd(java.util.function.Function<? super U, ? extends V> f) {
    return of(fst(), f.apply(snd()));
  }

  default <V> V reduce(java.util.function.BiFunction<? super T, ? super U, ? extends V> f) {
    return f.apply(fst(), snd());
  }

  default Couple<U, T> swap() {
    return of(snd(), fst());
  }

  static <T, U> Couple<T, U> of(T t, U u) {
    return new Couple<T, U>() {
      @Override
      public T fst() {
        return t;
      }

      @Override
      public U snd() {
        return u;
      }

      @Override
      public boolean equals(Object o) {
        return o == this
            || (o instanceof Couple<?, ?>
                && Objects.equals(((Couple<?, ?>) o).fst(), fst())
                && Objects.equals(((Couple<?, ?>) o).snd(), snd()));
      }

      @Override
      public int hashCode() {
        return Objects.hash(fst(), snd());
      }

      @Override
      public String toString() {
        return String.format("%s(%s, %s)", Couple.class.getSimpleName(), fst(), snd());
      }
    };
  }
}
