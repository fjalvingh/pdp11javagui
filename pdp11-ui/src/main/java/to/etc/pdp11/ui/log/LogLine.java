package to.etc.pdp11.ui.log;

import to.etc.pdp11.core.util.LogChannel;

/**
 * One line in the log: when, on which channel, and what.
 *
 * @param millis wall clock, so a transcript can be read against something that happened outside
 * @param channel which column this belongs in
 */
public record LogLine(long millis, LogChannel channel, String text) {
}
