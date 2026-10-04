package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.util.AppVersion;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;

/**
 * {@link HttpFetcher} over {@link HttpURLConnection}, which is in {@code java.base} - this module
 * may use nothing else.
 *
 * <p>Two things the defaults get wrong for the sites diagnostics live on:</p>
 *
 * <ul>
 * <li><b>The user agent.</b> bitsavers answers Java's default {@code Java/21} with 403 Forbidden.
 *     This says honestly what it is instead, which it accepts.</li>
 * <li><b>Redirects across protocols.</b> {@code HttpURLConnection} will not follow {@code http}
 *     to {@code https}, which is the redirect almost every site now sends. Redirects are followed
 *     here, five deep.</li>
 * </ul>
 */
public final class UrlFetcher implements HttpFetcher {
	private static final int MAX_REDIRECTS = 5;

	private static final int CONNECT_TIMEOUT_MS = 20_000;

	private static final int READ_TIMEOUT_MS = 60_000;

	private final String m_userAgent;

	public UrlFetcher() {
		m_userAgent = "Pdp11JavaGui/" + AppVersion.get() + " (diagnostics collector)";
	}

	@Override
	public Response get(URI uri) throws IOException {
		URI at = uri;
		for(int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			URLConnection c = at.toURL().openConnection();
			if(!(c instanceof HttpURLConnection http))
				throw new IOException(at + " is not an http or https address");
			http.setInstanceFollowRedirects(false);
			http.setConnectTimeout(CONNECT_TIMEOUT_MS);
			http.setReadTimeout(READ_TIMEOUT_MS);
			http.setRequestProperty("User-Agent", m_userAgent);
			int status = http.getResponseCode();
			if(status >= 300 && status < 400) {
				String location = http.getHeaderField("Location");
				http.disconnect();
				if(location == null)
					throw new IOException(at + ": redirect " + status + " without a Location");
				at = at.resolve(location);
				continue;
			}
			if(status != HttpURLConnection.HTTP_OK) {
				http.disconnect();
				throw new IOException(at + ": HTTP " + status + " " + nullToEmpty(http.getResponseMessage()));
			}
			InputStream body = http.getInputStream();
			return new Response(body, http.getContentLengthLong());
		}
		throw new IOException(uri + ": more than " + MAX_REDIRECTS + " redirects");
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
