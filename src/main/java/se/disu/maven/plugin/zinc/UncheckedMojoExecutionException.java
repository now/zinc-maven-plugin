package se.disu.maven.plugin.zinc;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import org.apache.maven.plugin.MojoExecutionException;
import se.disu.util.function.ThrowingFunction;

final class UncheckedMojoExecutionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  static UncheckedMojoExecutionException of(String messageKey, Object... arguments) {
    return new UncheckedMojoExecutionException(
        new MojoExecutionException(Messages.format(messageKey, arguments)));
  }

  static UncheckedMojoExecutionException of(Exception e, String messageKey, Object... arguments) {
    return new UncheckedMojoExecutionException(
        new MojoExecutionException(Messages.format(messageKey, arguments), e));
  }

  static <T, R> Function<T, R> of(
      ThrowingFunction<?, ? super T, ? extends R> f, String messageKey, Object... arguments) {
    return t -> {
      try {
        return f.apply(t);
      } catch (Exception e) {
        Object[] as = new String[arguments.length + 1];
        as[0] = t;
        System.arraycopy(arguments, 0, as, 1, arguments.length);
        throw of(e, messageKey, as);
      }
    };
  }

  UncheckedMojoExecutionException(MojoExecutionException e) {
    super(requireNonNull(e));
  }

  @Override
  public MojoExecutionException getCause() {
    return (MojoExecutionException) super.getCause();
  }
}
