package se.disu.text;

import static java.lang.Integer.parseInt;

import java.text.FieldPosition;
import java.text.Format;
import java.text.MessageFormat;
import java.text.ParsePosition;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import se.disu.util.Choice;

@FunctionalInterface
public interface Messages {
  String format(String messageKey, Object... arguments);

  @FunctionalInterface
  interface FormatConstructor {
    Format construct(Function<String, FormatConstructor> formats, String style, Locale l);
  }

  static final Map<String, FormatConstructor> DEFAULT_FORMATS =
      Choice.of(new HashMap<String, FormatConstructor>())
          .peek(m -> m.put("list", ListFormat::new))
          .map(Collections::unmodifiableMap)
          .orElseThrow(() -> new AssertionError());

  static String format(
      Function<String, String> messages,
      Supplier<Locale> locale,
      String messageKey,
      Object... arguments) {
    return of(messages, locale).format(messageKey, arguments);
  }

  static Messages of(String resourceBundlePath) {
    return of(ResourceBundle.getBundle(resourceBundlePath));
  }

  static Messages of(ResourceBundle b) {
    return of(b::getString, b::getLocale);
  }

  static Messages of(Function<String, String> messages, Supplier<Locale> locale) {
    return of(DEFAULT_FORMATS::get, messages, locale);
  }

  static Messages of(
      Function<String, FormatConstructor> formats,
      Function<String, String> messages,
      Supplier<Locale> locale) {
    return new Messages() {
      @Override
      public String format(String messageKey, Object... arguments) {
        return new Message(formats, messages.apply(messageKey), locale.get()).format(arguments);
      }

      @Override
      public String toString() {
        return String.format(
            "%s(%s, %s, %s)", getClass().getSimpleName(), formats, messages, locale);
      }
    };
  }

  final class Message extends Format {
    private static final long serialVersionUID = 1L;

    private final MessageFormat format;

    Message(Function<String, FormatConstructor> formats, String template, Locale l) {
      Map<Integer, Format> fs = new HashMap<>();
      StringBuilder b = new StringBuilder();
      Parse p = new Parse(template);
      while (!p.isEmpty()) {
        b.append(p.not('{'));
        if (p.eat('{')) {
          b.append('{');
          int i = p.integer();
          b.append(String.valueOf(i));
          if (p.eat(',')) {
            String t = p.not(',');
            String s = p.eat(',') ? p.not('}') : "";
            Choice.of(formats.apply(t))
                .or(() -> Choice.of(formats.apply(t.trim().toLowerCase())))
                .ifPresentOrElse(
                    f -> fs.put(i, f.construct(formats, s, l)),
                    () -> {
                      if (!t.isEmpty()) {
                        b.append(',').append(t);
                        if (!s.isEmpty()) b.append(',').append(s);
                      }
                    });
          }
          p.expect('}');
          b.append('}');
        }
      }
      format = new MessageFormat(b.toString(), l);
      fs.entrySet().stream()
          .forEach(e -> format.setFormatByArgumentIndex(e.getKey(), e.getValue()));
    }

    @Override
    public StringBuffer format(Object o, StringBuffer b, FieldPosition p) {
      return format.format(o, b, p);
    }

    @Override
    public Object parseObject(String s, ParsePosition p) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return String.format("%s(%s)", getClass().getSimpleName(), format.toPattern());
    }
  }

  final class ListFormat extends Format {
    private static final long serialVersionUID = 1L;

    private final String format;
    private final Map<Integer, Format> formats = new HashMap<>();
    private final Fallback fallback = new Fallback();

    ListFormat(Function<String, FormatConstructor> formats, String format, Locale l) {
      this.format = format;
      Parse p = new Parse(format);
      do {
        if (p.eat('n')) {
          p.expect('=');
          do {
            if (p.eat('n')) {
              p.expect('=');
              fallback.fallback = new Message(formats, p.not(';'), l);
            } else {
              int m = p.eat('-') ? -1 : 1;
              int i = p.integer("Digit, 0…9, ‘n’, or ‘-’ expected");
              p.expect('=');
              fallback.formats.put(m * i, new Message(formats, p.not(';'), l));
            }
          } while (!p.isEmpty() && p.eat(';'));
        } else {
          int i = p.integer("Digit, 0…9, or ‘n’ expected");
          p.expect('=');
          this.formats.put(i, new Message(formats, p.not(','), l));
          p.eat(',');
        }
      } while (!p.isEmpty());
    }

    @Override
    public StringBuffer format(Object o, StringBuffer b, FieldPosition p) {
      Object[] es;
      if (o instanceof List<?>) {
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) o;
        es = l.toArray(new Object[l.size()]);
      } else es = (Object[]) o;
      return Choice.of(formats.get(es.length))
          .map(f -> f.format(es, b, p))
          .orElseGet(
              () -> {
                StringBuffer r = b;
                int i = 1;
                for (Object e : es) {
                  int j = i++;
                  r =
                      Stream.of(
                              fallback.formats.get(j),
                              fallback.formats.get(j - (es.length + 1)),
                              fallback.fallback)
                          .filter(Objects::nonNull)
                          .findFirst()
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException(
                                      String.format(
                                          "List format “%s” doesn’t define a sub-format or "
                                              + "fallback that handles index %d",
                                          format, j)))
                          .format(new Object[] {e}, r, p);
                }
                return r;
              });
    }

    @Override
    public Object parseObject(String s, ParsePosition p) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
      return String.format("%s(%s, %s)", getClass().getSimpleName(), formats, fallback);
    }

    private static final class Fallback {
      final Map<Integer, Format> formats = new HashMap<>();
      Format fallback;

      @Override
      public String toString() {
        return String.format("%s(%s, %s)", getClass().getSimpleName(), formats, fallback);
      }
    }
  }

  final class Parse {
    private final String s;
    private int i;

    Parse(String s) {
      this.s = s;
      i = 0;
    }

    void expect(char c) {
      if (!eat(c)) throw exception(String.format("‘%c’ expected", c));
    }

    boolean eat(char c) {
      if (isEmpty() || c() != c) return false;
      i++;
      return true;
    }

    boolean isEmpty() {
      return i == s.length();
    }

    String not(char c) {
      int b = i;
      while (!isEmpty() && c() != c)
        if (eat('{'))
          for (int d = 1; !isEmpty() && d > 0; )
            if (eat('{')) d++;
            else if (eat('}')) d--;
            else if (eat('\'')) while (!isEmpty() && !eat('\'')) i++;
            else i++;
        else if (eat('\'')) while (!isEmpty() && !eat('\'')) i++;
        else i++;
      return s.substring(b, i);
    }

    int integer() {
      return integer("Digit, 0…9, expected");
    }

    int integer(String expected) {
      int b = i;
      while (!isEmpty() && '0' <= c() && c() <= '9') i++;
      if (i == b) throw exception(expected);
      String ds = s.substring(b, i);
      int r;
      try {
        r = parseInt(ds);
      } catch (NumberFormatException e) {
        throw exception(
            String.format(
                "Argument number %s is larger than maximum argument number %d",
                ds, Integer.MAX_VALUE),
            b);
      }
      return r;
    }

    private char c() {
      return s.charAt(i);
    }

    private IllegalArgumentException exception(String message) {
      return exception(message, i);
    }

    private IllegalArgumentException exception(String message, int b) {
      return new IllegalArgumentException(
          String.format(
              "%s at %s “%s”",
              message,
              b == s.length()
                  ? "end of"
                  : "“"
                      + (b + 10 < s.length() ? s.substring(b, b + 9) + "…" : s.substring(b))
                      + "” in",
              s));
    }
  }
}
