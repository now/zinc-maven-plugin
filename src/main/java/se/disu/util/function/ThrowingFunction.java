package se.disu.util.function;

@FunctionalInterface
public interface ThrowingFunction<E extends Exception, T, R> {
  R apply(T t) throws E;
}
