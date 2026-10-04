package to.etc.pdp11.common.diag;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * How the collector reaches the Internet: one GET at a time.
 *
 * <p>An interface so the collector and the scanner can be tested against pages and images held in
 * memory - the tests have no network, and must not depend on bitsavers being up.</p>
 */
public interface HttpFetcher {
	/**
	 * A response body, open for reading.
	 *
	 * @param length the length the server announced, or -1
	 */
	record Response(InputStream body, long length) implements AutoCloseable {
		@Override
		public void close() throws IOException {
			body.close();
		}
	}

	/**
	 * GET a URL, following redirects.
	 *
	 * @throws IOException for anything but success, with the status in the message
	 */
	Response get(URI uri) throws IOException;
}
