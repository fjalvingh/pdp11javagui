package to.etc.pdp11.web;

import org.slf4j.LoggerFactory;
import to.etc.pdp11.common.util.LogChannel;
import to.etc.pdp11.common.util.Logger;

/**
 * Where pdp11-common's diagnostics go on a server: to slf4j, which is what DomUI logs through
 * too. The desktop's Log window is the other implementation.
 */
final class Slf4jLogger implements Logger {
	private final org.slf4j.Logger m_log;

	Slf4jLogger(Class<?> owner) {
		m_log = LoggerFactory.getLogger(owner);
	}

	@Override
	public boolean isEnabled(LogChannel channel) {
		return m_log.isInfoEnabled();
	}

	@Override
	public void log(LogChannel channel, String message) {
		m_log.info("[{}] {}", channel, message);
	}
}
