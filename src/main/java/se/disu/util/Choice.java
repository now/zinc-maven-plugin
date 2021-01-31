package se.disu.util;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface Choice<T> {
  Choice<T> filter(Predicate<? super T> p);

  <U> Choice<U> flatMap(Function<? super T, Choice<U>> f);

  void ifPresent(Consumer<? super T> c);

  void ifPresentOrElse(Consumer<? super T> c, Runnable r);

  <U> Choice<U> map(Function<? super T, ? extends U> f);

  Choice<T> or(Supplier<? extends Choice<? extends T>> f);

  T orElse(T o);

  T orElseGet(Supplier<? extends T> f);

  <E extends Throwable> T orElseThrow(Supplier<? extends E> f) throws E;

  Choice<T> peek(Consumer<? super T> c);

  Stream<T> stream();

  Optional<T> toOptional();

  static <T> Choice<T> empty() {
    @SuppressWarnings("unchecked")
    Choice<T> t = (Choice<T>) Empty.INSTANCE;
    return t;
  }

  static <T> Choice<T> of(T value) {
    return value == null ? empty() : value(value);
  }

  static <T> Choice<T> ofOptional(Optional<T> o) {
    return o.map(Choice::value).orElseGet(() -> empty());
  }

  static <T> Choice<T> value(T value) {
    return new Value<>(value);
  }

  static final class Empty<T> implements Choice<T> {
    private static final Empty<?> INSTANCE = new Empty<>();

    private Empty() {}

    @Override
    public Choice<T> filter(Predicate<? super T> p) {
      requireNonNull(p);
      return this;
    }

    @Override
    public <U> Choice<U> flatMap(Function<? super T, Choice<U>> f) {
      requireNonNull(f);
      return empty();
    }

    @Override
    public void ifPresent(Consumer<? super T> c) {}

    @Override
    public void ifPresentOrElse(Consumer<? super T> c, Runnable r) {
      r.run();
    }

    @Override
    public <U> Choice<U> map(Function<? super T, ? extends U> f) {
      requireNonNull(f);
      return empty();
    }

    @Override
    public Choice<T> or(Supplier<? extends Choice<? extends T>> f) {
      requireNonNull(f);
      @SuppressWarnings("unchecked")
      Choice<T> r = (Choice<T>) f.get();
      return requireNonNull(r);
    }

    @Override
    public T orElse(T o) {
      return o;
    }

    @Override
    public T orElseGet(Supplier<? extends T> f) {
      return f.get();
    }

    @Override
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> f) throws X {
      throw f.get();
    }

    @Override
    public Choice<T> peek(Consumer<? super T> c) {
      return this;
    }

    @Override
    public Stream<T> stream() {
      return Stream.empty();
    }

    @Override
    public Optional<T> toOptional() {
      return Optional.empty();
    }

    @Override
    public boolean equals(Object o) {
      return o == this || o instanceof Empty;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(null);
    }

    @Override
    public String toString() {
      return "Choice.empty";
    }
  }

  static final class Value<T> implements Choice<T> {
    private final T value;

    private Value(T value) {
      this.value = requireNonNull(value);
    }

    @Override
    public Choice<T> filter(Predicate<? super T> p) {
      requireNonNull(p);
      return p.test(value) ? this : empty();
    }

    @Override
    public <U> Choice<U> flatMap(Function<? super T, Choice<U>> f) {
      requireNonNull(f);
      return requireNonNull(f.apply(value));
    }

    @Override
    public void ifPresent(Consumer<? super T> c) {
      c.accept(value);
    }

    @Override
    public void ifPresentOrElse(Consumer<? super T> c, Runnable r) {
      c.accept(value);
    }

    @Override
    public <U> Choice<U> map(Function<? super T, ? extends U> f) {
      requireNonNull(f);
      return of(f.apply(value));
    }

    @Override
    public Choice<T> or(Supplier<? extends Choice<? extends T>> f) {
      requireNonNull(f);
      return this;
    }

    @Override
    public T orElse(T o) {
      return value;
    }

    @Override
    public T orElseGet(Supplier<? extends T> f) {
      return value;
    }

    @Override
    public <X extends Throwable> T orElseThrow(Supplier<? extends X> f) throws X {
      return value;
    }

    @Override
    public Choice<T> peek(Consumer<? super T> c) {
      c.accept(value);
      return this;
    }

    @Override
    public Stream<T> stream() {
      return Stream.of(value);
    }

    @Override
    public Optional<T> toOptional() {
      return Optional.of(value);
    }

    @Override
    public boolean equals(Object o) {
      return o == this || (o instanceof Value && ((Value<?>) o).value.equals(value));
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      return String.format("Choice.value(%s)", value);
    }
  }
}
