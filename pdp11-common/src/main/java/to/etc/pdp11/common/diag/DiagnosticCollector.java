package to.etc.pdp11.common.diag;

import to.etc.pdp11.common.diag.media.MediaReader;
import to.etc.pdp11.common.diag.media.MediaVolume;
import to.etc.pdp11.common.util.OperationCancelledException;
import to.etc.pdp11.common.util.ProgressMonitor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Collects diagnostics: finds them at the {@link DiagnosticSource}s, downloads what is new, takes
 * the files off every medium, and puts all of it in a {@link DiagnosticLibrary}.
 *
 * <p>Two steps, so that the person can see what a collection will fetch before it fetches it: a
 * {@link #scan} that only reads index pages, and a {@link #collect} of what the scan found.</p>
 *
 * <p>Runs on whatever thread calls it, for as long as the network takes, and checks the monitor
 * between every read. Cancelling leaves the library consistent: the index is saved after each
 * download, so everything collected before the cancel stays collected, and the download that was
 * cut short was never in it.</p>
 */
public final class DiagnosticCollector {
	/** Bigger than any diagnostic medium there is - the largest, an RK07 image, is 27 MB. */
	static final long MAX_DOWNLOAD = 256L * 1024 * 1024;

	private final DiagnosticLibrary m_library;

	private final HttpFetcher m_fetcher;

	private final Supplier<LocalDate> m_today;

	public DiagnosticCollector(DiagnosticLibrary library, HttpFetcher fetcher) {
		this(library, fetcher, LocalDate::now);
	}

	DiagnosticCollector(DiagnosticLibrary library, HttpFetcher fetcher, Supplier<LocalDate> today) {
		m_library = library;
		m_fetcher = fetcher;
		m_today = today;
	}

	/**
	 * What a scan found.
	 *
	 * @param found    every file on offer, new or not
	 * @param problems sources or pages that could not be read
	 */
	public record ScanResult(List<IndexPageScanner.Found> found, List<String> problems) {
		public ScanResult {
			found = List.copyOf(found);
			problems = List.copyOf(problems);
		}
	}

	/**
	 * Read the sources' index pages, and nothing else.
	 */
	public ScanResult scan(List<DiagnosticSource> sources, ProgressMonitor monitor) {
		List<IndexPageScanner.Found> found = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		IndexPageScanner scanner = new IndexPageScanner(m_fetcher);
		monitor.begin("Looking for diagnostics", sources.size());
		try {
			for(DiagnosticSource s : sources) {
				monitor.step(0, s.title());
				try {
					found.addAll(scanner.scan(s, problems, monitor));
				} catch(IOException x) {
					problems.add(s.title() + ": " + x.getMessage());
				}
				monitor.step(1, null);
			}
		} finally {
			monitor.done();
		}
		return new ScanResult(found, problems);
	}

	/** Those found files the library does not have yet. */
	public List<IndexPageScanner.Found> notCollected(List<IndexPageScanner.Found> found) {
		List<IndexPageScanner.Found> out = new ArrayList<>();
		for(IndexPageScanner.Found f : found) {
			if(!m_library.hasDownloaded(f.uri().toString()))
				out.add(f);
		}
		return out;
	}

	/**
	 * What a collection did.
	 *
	 * @param downloaded how many files were fetched
	 * @param media      how many media were read from them
	 * @param newFiles   how many programs the library did not have before
	 * @param problems   downloads that failed, media that could not be read, files that are damaged
	 */
	public record CollectResult(int downloaded, int media, int newFiles, List<String> problems) {
		public CollectResult {
			problems = List.copyOf(problems);
		}
	}

	/**
	 * Download each file and add what is on it to the library.
	 *
	 * @throws OperationCancelledException when the monitor is cancelled; what was collected before
	 *                                     is kept
	 */
	public CollectResult collect(List<IndexPageScanner.Found> files, ProgressMonitor monitor) {
		int downloaded = 0;
		int media = 0;
		int newFiles = 0;
		List<String> problems = new ArrayList<>();
		monitor.begin("Collecting diagnostics", files.size());
		try {
			for(IndexPageScanner.Found f : files) {
				monitor.checkCancelled();
				monitor.step(0, f.fileName());
				byte[] data;
				URI from;
				try {
					Downloaded d = download(f, monitor);
					data = d.data();
					from = d.from();
				} catch(IOException x) {
					problems.add(f.fileName() + ": " + x.getMessage());
					monitor.step(1, null);
					continue;
				}
				downloaded++;
				try {
					for(MediaVolume v : MediaReader.read(f.fileName(), data)) {
						media++;
						newFiles += m_library.add(f.source().id(), f.uri().toString(), v, m_today.get());
						for(String p : v.problems())
							problems.add(v.name() + ": " + p);
					}
					m_library.save();
				} catch(IOException x) {
					problems.add(f.fileName() + ": could not be stored: " + x.getMessage());
				}
				if(!from.equals(f.uri()))
					problems.add(f.fileName() + ": fetched from the mirror " + from.getHost());
				monitor.step(1, null);
			}
		} finally {
			monitor.done();
		}
		return new CollectResult(downloaded, media, newFiles, problems);
	}

	private record Downloaded(byte[] data, URI from) {
	}

	/** From the location the scan used, then from each of the source's others. */
	private Downloaded download(IndexPageScanner.Found f, ProgressMonitor monitor) throws IOException {
		IOException first = null;
		List<URI> tries = new ArrayList<>();
		tries.add(f.uri());
		for(URI root : f.source().urls()) {
			if(!root.equals(f.root()))
				tries.add(f.at(root));
		}
		for(URI u : tries) {
			try {
				return new Downloaded(fetch(u, f.fileName(), monitor), u);
			} catch(IOException x) {
				if(first == null)
					first = x;
			}
		}
		throw first;
	}

	private byte[] fetch(URI uri, String name, ProgressMonitor monitor) throws IOException {
		try(HttpFetcher.Response r = m_fetcher.get(uri)) {
			if(r.length() > MAX_DOWNLOAD)
				throw new IOException("is " + r.length() + " bytes, more than any diagnostic medium");
			InputStream in = r.body();
			ByteArrayOutputStream out = new ByteArrayOutputStream(r.length() > 0 ? (int) r.length() : 64 * 1024);
			byte[] buf = new byte[64 * 1024];
			long lastNote = 0;
			for(int n = in.read(buf); n >= 0; n = in.read(buf)) {
				monitor.checkCancelled();
				out.write(buf, 0, n);
				if(out.size() > MAX_DOWNLOAD)
					throw new IOException("is more than " + MAX_DOWNLOAD + " bytes, more than any diagnostic medium");
				if(out.size() - lastNote >= 256 * 1024) {
					lastNote = out.size();
					monitor.step(0, name + "  " + megabytes(out.size()) + (r.length() > 0 ? " of " + megabytes(r.length()) : ""));
				}
			}
			if(r.length() > 0 && out.size() != r.length())
				throw new IOException("the download stopped at " + out.size() + " of " + r.length() + " bytes");
			return out.toByteArray();
		}
	}

	private static String megabytes(long bytes) {
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
