package to.etc.pdp11.common.diag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A web of pages and files in memory, standing in for bitsavers in the tests.
 */
class FakeWeb implements HttpFetcher {
	private final Map<String, byte[]> m_content = new HashMap<>();

	private final Set<String> m_down = new HashSet<>();

	final List<String> m_requests = new ArrayList<>();

	/** Called before each request is answered, with the URL; may throw. */
	Hook m_hook = u -> {
	};

	interface Hook {
		void before(String url) throws IOException;
	}

	FakeWeb page(String url, String html) {
		m_content.put(url, html.getBytes(StandardCharsets.ISO_8859_1));
		return this;
	}

	FakeWeb file(String url, byte[] data) {
		m_content.put(url, data);
		return this;
	}

	/** Every URL on this host fails. */
	FakeWeb down(String host) {
		m_down.add(host);
		return this;
	}

	/** An Apache index page linking each name, with the junk Apache adds around them. */
	static String apacheIndex(String... names) {
		StringBuilder sb = new StringBuilder("<html><body><h1>Index of /x</h1><table>");
		sb.append("<tr><th><a href=\"?C=N;O=D\">Name</a></th><th><a href=\"?C=M;O=A\">Last modified</a></th></tr>");
		sb.append("<tr><td><a href=\"/bits/DEC/\">Parent Directory</a></td></tr>");
		for(String n : names)
			sb.append("<tr><td><a href=\"").append(n).append("\">").append(n).append("</a></td><td>2012-09-12</td></tr>");
		sb.append("</table><address>Apache</address></body></html>");
		return sb.toString();
	}

	@Override
	public Response get(URI uri) throws IOException {
		String u = uri.toString();
		m_requests.add(u);
		m_hook.before(u);
		if(m_down.contains(uri.getHost()))
			throw new IOException(u + ": connection refused");
		byte[] data = m_content.get(u);
		if(data == null)
			throw new IOException(u + ": HTTP 404 Not Found");
		return new Response(new ByteArrayInputStream(data), data.length);
	}
}
