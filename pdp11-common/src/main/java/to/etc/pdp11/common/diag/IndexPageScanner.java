package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.util.ProgressMonitor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the files a {@link DiagnosticSource} offers, by reading its web pages.
 *
 * <p>Neither of the kinds of source there are has an API: bitsavers is Apache's automatic index,
 * GitHub Pages a hand-written list of links. What both have is {@code href}s, so that is all this
 * reads. A link is followed only when it stays inside the source's directory - which rules out the
 * "Parent Directory" link, the column-sorting links ({@code ?C=N;O=D}), and anything on another
 * site - and a directory is entered only when the source says to recurse, and never more than
 * {@link #MAX_DEPTH} deep.</p>
 *
 * <p>Links are resolved against the page as the browser would, and kept encoded: bitsavers has
 * files named with a {@code #}, linked as {@code %23}, and decoding that before asking for the file
 * turns the rest of its name into a fragment and the answer into 404.</p>
 */
public final class IndexPageScanner {
	public static final int MAX_DEPTH = 4;

	/** No index page anybody needs is bigger than this; a bigger one is not an index page. */
	private static final int MAX_PAGE_BYTES = 4 * 1024 * 1024;

	private static final Pattern HREF = Pattern.compile("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

	private final HttpFetcher m_fetcher;

	public IndexPageScanner(HttpFetcher fetcher) {
		m_fetcher = fetcher;
	}

	/**
	 * A file on offer.
	 *
	 * @param uri      where to get it
	 * @param root     the source location it was found under, which is also what to swap for a
	 *                 mirror
	 * @param path     its path below the root, decoded: {@code RX02/dydp/BA-F019F….DSK}
	 */
	public record Found(DiagnosticSource source, URI uri, URI root, String path) {
		/** The last element of the path. */
		public String fileName() {
			int slash = path.lastIndexOf('/');
			return path.substring(slash + 1);
		}

		/** The same file at another of the source's locations. */
		public URI at(URI otherRoot) {
			String rel = uri.toString().substring(root.toString().length());
			return URI.create(otherRoot.toString() + rel);
		}
	}

	/**
	 * Everything the source offers that it wants, in the order the pages list it.
	 *
	 * <p>Each of the source's locations is tried in turn until one answers; a page further down
	 * that fails is a problem noted, not the end of the scan.</p>
	 *
	 * @throws IOException when none of the source's locations answers at all
	 */
	public List<Found> scan(DiagnosticSource source, List<String> problems, ProgressMonitor monitor) throws IOException {
		IOException last = null;
		for(URI root : source.urls()) {
			monitor.checkCancelled();
			try {
				return scan(source, root, problems, monitor);
			} catch(IOException x) {
				last = x;
			}
		}
		throw last;
	}

	private List<Found> scan(DiagnosticSource source, URI root, List<String> problems, ProgressMonitor monitor) throws IOException {
		Set<URI> found = new LinkedHashSet<>();
		Set<URI> visited = new HashSet<>();
		record Page(URI uri, int depth) {
		}
		Deque<Page> todo = new ArrayDeque<>();
		todo.add(new Page(root, 0));
		boolean first = true;
		while(!todo.isEmpty()) {
			monitor.checkCancelled();
			Page page = todo.removeFirst();
			if(!visited.add(page.uri()))
				continue;
			String html;
			try {
				html = fetchPage(page.uri());
			} catch(IOException x) {
				if(first)
					throw x;
				problems.add(source.title() + ": " + x.getMessage());
				continue;
			} finally {
				first = false;
			}
			for(URI link : links(page.uri(), html)) {
				if(!isInside(root, link))
					continue;
				if(link.getPath().endsWith("/")) {
					if(source.recurse() && page.depth() < MAX_DEPTH)
						todo.add(new Page(link, page.depth() + 1));
					continue;
				}
				String name = lastElement(link.getPath());
				if(source.wants(name))
					found.add(link);
			}
		}
		List<Found> out = new ArrayList<>(found.size());
		for(URI u : found)
			out.add(new Found(source, u, root, decodedBelow(root, u)));
		return out;
	}

	private String fetchPage(URI uri) throws IOException {
		try(HttpFetcher.Response r = m_fetcher.get(uri)) {
			byte[] data = r.body().readNBytes(MAX_PAGE_BYTES + 1);
			if(data.length > MAX_PAGE_BYTES)
				throw new IOException(uri + " is too big to be an index page");
			//-- Only the links matter, and they are ASCII; Latin-1 cannot fail on whatever the rest is.
			return new String(data, StandardCharsets.ISO_8859_1);
		}
	}

	/** Every link on a page, resolved against it, without query or fragment. */
	static List<URI> links(URI page, String html) {
		List<URI> out = new ArrayList<>();
		Matcher m = HREF.matcher(html);
		while(m.find()) {
			String href = m.group(1) != null ? m.group(1) : m.group(2);
			href = href.replace("&amp;", "&").strip();
			if(href.isEmpty() || href.startsWith("#") || href.startsWith("?"))
				continue;
			String lower = href.toLowerCase(Locale.ROOT);
			if(lower.startsWith("mailto:") || lower.startsWith("javascript:"))
				continue;
			try {
				String s = page.resolve(new URI(href.replace(" ", "%20"))).toString();
				//-- Cut on the string, not through URI's multi-argument constructor: that one quotes
				//-- every % it is given, and %23 would come out as %2523.
				int cut = s.length();
				for(char c : new char[]{'?', '#'}) {
					int at = s.indexOf(c);
					if(at >= 0)
						cut = Math.min(cut, at);
				}
				out.add(new URI(s.substring(0, cut)));
			} catch(URISyntaxException | IllegalArgumentException x) {
				//-- A link this cannot even parse is not one to a diagnostic.
			}
		}
		return out;
	}

	/** Strictly below the root, on the same site. */
	static boolean isInside(URI root, URI link) {
		if(!equalsIgnoreCase(root.getScheme(), link.getScheme()) || !equalsIgnoreCase(root.getRawAuthority(), link.getRawAuthority()))
			return false;
		String r = root.getRawPath();
		String l = link.getRawPath();
		return l != null && l.length() > r.length() && l.startsWith(r);
	}

	private static boolean equalsIgnoreCase(String a, String b) {
		return a != null && a.equalsIgnoreCase(b);
	}

	private static String lastElement(String path) {
		int slash = path.lastIndexOf('/');
		return path.substring(slash + 1);
	}

	private static String decodedBelow(URI root, URI u) {
		return u.getPath().substring(root.getPath().length());
	}
}
