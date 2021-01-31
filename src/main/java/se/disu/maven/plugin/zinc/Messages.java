package se.disu.maven.plugin.zinc;

final class Messages {
  private static final se.disu.text.Messages messages = se.disu.text.Messages.of("messages");

  static String format(String messageKey, Object... arguments) {
    return messages.format(messageKey, arguments);
  }

  private Messages() {
    throw new AssertionError();
  }
}
