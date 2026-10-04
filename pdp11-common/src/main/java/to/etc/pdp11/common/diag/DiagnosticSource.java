package to.etc.pdp11.common.diag;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A place on the Internet that has diagnostics, and which of its files to take.
 *
 * <p>The application ships no diagnostics - they are DEC's, and copyright is not this project's to
 * waive - but it does know where people have put them. Each source is a web directory: an Apache
 * index page on bitsavers, a static page of links on GitHub Pages. The scanner reads the page,
 * follows the links that stay inside it, and keeps the ones that match {@link #includes()}. So a
 * file added to a source later is found by the next scan without a new release of this program.</p>
 *
 * <p>The list is the resource {@code sources.txt}, in blocks like this:</p>
 *
 * <pre>
 * [bitsavers-rl02]
 * title   = XXDP V2.2 and V2.5 on RL02
 * url     = https://www.bitsavers.org/bits/DEC/pdp11/discimages/rl02/
 * mirror  = https://bitsavers.trailing-edge.com/bits/DEC/pdp11/discimages/rl02/
 * include = *.rl02.gz
 * recurse = no
 * default = yes
 * note    = One sentence for the person choosing.
 * </pre>
 *
 * @param id        stable name, used for the directory its media are kept in
 * @param title     what the person choosing sees
 * @param urls      the directory, then its mirrors, tried in order
 * @param includes  file name patterns, {@code *} and {@code ?}, any case
 * @param recurse   whether to follow links into subdirectories
 * @param byDefault whether the source is ticked to begin with
 * @param note      what is there and why it is worth having; may be empty
 */
public record DiagnosticSource(String id, String title, List<URI> urls, List<String> includes, boolean recurse,
	boolean byDefault, String note) {
	public static final String RESOURCE = "sources.txt";

	public DiagnosticSource {
		if(id == null || id.isBlank())
			throw new IllegalArgumentException("A source needs an id");
		if(urls.isEmpty())
			throw new IllegalArgumentException("Source " + id + " has no url");
		for(URI u : urls) {
			if(!u.getPath().endsWith("/"))
				throw new IllegalArgumentException("Source " + id + ": " + u + " is not a directory; end it with /");
		}
		urls = List.copyOf(urls);
		includes = List.copyOf(includes);
	}

	/** The primary location. */
	public URI url() {
		return urls.get(0);
	}

	/** Whether a file name - the last path element, decoded - is one this source wants. */
	public boolean wants(String fileName) {
		for(String glob : includes) {
			if(globToPattern(glob).matcher(fileName).matches())
				return true;
		}
		return false;
	}

	static Pattern globToPattern(String glob) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if(c == '*')
				sb.append(".*");
			else if(c == '?')
				sb.append('.');
			else
				sb.append(Pattern.quote(String.valueOf(c)));
		}
		return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
	}

	/** The sources shipped with the application. */
	public static List<DiagnosticSource> builtIn() {
		try(InputStream in = DiagnosticSource.class.getResourceAsStream(RESOURCE)) {
			if(in == null)
				throw new IOException(RESOURCE + " is missing from the build");
			return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
		} catch(IOException x) {
			throw new UncheckedIOException("The built-in list of diagnostic sources cannot be read", x);
		}
	}

	/**
	 * Read a source list.
	 *
	 * @throws IllegalArgumentException naming the line, for a list that is wrong - it is a resource
	 *                                  of this build, so wrong means a bug, and a test reads it
	 */
	public static List<DiagnosticSource> parse(Reader reader) throws IOException {
		List<DiagnosticSource> out = new ArrayList<>();
		BufferedReader r = new BufferedReader(reader);
		Builder b = null;
		int number = 0;
		for(String line = r.readLine(); line != null; line = r.readLine()) {
			number++;
			String s = line.strip();
			if(s.isEmpty() || s.startsWith("#"))
				continue;
			if(s.startsWith("[") && s.endsWith("]")) {
				if(b != null)
					out.add(b.build());
				b = new Builder(s.substring(1, s.length() - 1).strip());
				continue;
			}
			int eq = s.indexOf('=');
			if(b == null || eq < 0)
				throw new IllegalArgumentException(RESOURCE + " line " + number + ": expected [id] or key = value");
			String key = s.substring(0, eq).strip().toLowerCase(Locale.ROOT);
			String value = s.substring(eq + 1).strip();
			switch(key) {
				case "title" -> b.m_title = value;
				case "url", "mirror" -> b.m_urls.add(URI.create(value));
				case "include" -> {
					for(String g : value.split("\\s+"))
						b.m_includes.add(g);
				}
				case "recurse" -> b.m_recurse = yes(value, number);
				case "default" -> b.m_default = yes(value, number);
				case "note" -> b.m_note = b.m_note.isEmpty() ? value : b.m_note + " " + value;
				default -> throw new IllegalArgumentException(RESOURCE + " line " + number + ": unknown key " + key);
			}
		}
		if(b != null)
			out.add(b.build());
		return out;
	}

	private static boolean yes(String value, int line) {
		return switch(value.toLowerCase(Locale.ROOT)) {
			case "yes", "true" -> true;
			case "no", "false" -> false;
			default -> throw new IllegalArgumentException(RESOURCE + " line " + line + ": expected yes or no, not " + value);
		};
	}

	private static final class Builder {
		final String m_id;

		String m_title = "";

		final List<URI> m_urls = new ArrayList<>();

		final List<String> m_includes = new ArrayList<>();

		boolean m_recurse;

		boolean m_default = true;

		String m_note = "";

		Builder(String id) {
			m_id = id;
		}

		DiagnosticSource build() {
			return new DiagnosticSource(m_id, m_title.isEmpty() ? m_id : m_title, m_urls, m_includes, m_recurse, m_default, m_note);
		}
	}
}
