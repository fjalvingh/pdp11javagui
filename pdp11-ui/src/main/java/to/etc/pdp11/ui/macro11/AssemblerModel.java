package to.etc.pdp11.ui.macro11;

import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.macro11.Macro11;
import to.etc.pdp11.core.macro11.Macro11Listing;
import to.etc.pdp11.core.macro11.Macro11ListingParser;
import to.etc.pdp11.core.mem.MemoryCellGroup;
import to.etc.pdp11.core.util.LogChannel;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.ProgressDialog;

import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The program being written: its source, its listing, and the code that came out.
 *
 * <p>This exists because <b>two</b> windows assemble. The Assembler window does it from its
 * Compile button, and the Execution window's "New program" does it from a button that also
 * resets the machine ({@code TFormExecute.NewPgmButtonClick}, {@code FormExecuteU.pas:166-188}).
 * The Pascal solves that by having the execution window reach across and press the other
 * window's buttons - {@code FormMain.FormMacro11Source.CompileButtonClick(nil)}, then
 * {@code FormMain.FormMacro11Listing.DepositAllButtonClick(nil)} - which needs both windows to
 * exist, to be showing, and to be in the right state.</p>
 *
 * <p>With windows created on demand there is nothing to reach for, so the program is a piece of
 * application state that lives on the {@link AppContext} and that windows watch. It is the same
 * answer {@code MachineState} gives to the same question, and it means the Execution window can
 * assemble and load with no assembler window open at all.</p>
 *
 * <h2>The source text is the truth, not the editor</h2>
 *
 * <p>The editor pushes what the user types in here and this writes it out before assembling.
 * That removes the "who saves it first" problem the Pascal has to solve twice, and it is why
 * assembling from the Execution window cannot quietly assemble yesterday's file.</p>
 *
 * <h2>Which thread</h2>
 *
 * <p>{@link #assemble} starts an external process and waits up to five seconds for it, so it
 * runs on a worker; every callback and every listener runs on the event thread. Nothing here
 * touches the console except {@link #deposit}, which goes through
 * {@link AppContext#onConsole}.</p>
 *
 * <p>The worker parses the listing but does <b>not</b> put it into {@link #getGroup()}. That
 * group is on the propagation bus: the Code grid paints from it on the event thread and the
 * command thread walks the bus index during an examine, and nothing in the memory-cell layer is
 * synchronised. So the worker builds a detached {@link Macro11ListingParser.Parsed} and the
 * event thread installs it in one step. For the same reason a second assembly is refused while
 * one is in flight - see {@link #isAssembling()}.</p>
 */
public final class AssemblerModel {
	/** Told whenever the source, the file name or the listing changed. Always on the EDT. */
	@FunctionalInterface
	public interface Listener {
		void assemblerChanged(AssemblerModel model);
	}

	private final AppContext m_context;

	/**
	 * The assembled code.
	 *
	 * <p>On the propagation bus like every other group, so a memory window looking at the same
	 * addresses shows what was deposited. {@code pdpOverwritesEdit} is off for the reason the
	 * Memory Loader has it off: these values came from a file and are waiting to be written, and
	 * another window examining the same addresses must not replace them with what is already in
	 * the machine ({@code FormMacro11CodeU.pas:93}).</p>
	 *
	 * <p>Made on first use rather than up front. Every {@link AppContext} has an assembler
	 * whether or not anybody assembles anything, and a permanently empty group sitting on the
	 * propagation bus is a group every other window has to know to ignore.</p>
	 */
	private MemoryCellGroup m_group;

	private final List<Listener> m_listeners = new CopyOnWriteArrayList<>();

	private String m_sourceText = "";

	/** What is on disk, for {@link #isChanged()}. */
	private String m_savedText = "";

	private Path m_sourceFile;

	private Macro11Listing m_listing;

	private Path m_listingFile;

	/** True after an assembly that produced no errors. {@code TFormMacro11Source.Translated}. */
	private boolean m_translated;

	/**
	 * Whether a worker is assembling right now. Event thread only, which is the whole point: two
	 * assemblies would install two listings into the one code group.
	 */
	private boolean m_assembling;

	public AssemblerModel(AppContext context) {
		m_context = context;
	}

	// -------------------------------------------------------------------------------------
	// What there is
	// -------------------------------------------------------------------------------------

	/**
	 * The group holding the assembled code, made the first time it is asked for.
	 *
	 * <p>Always called from the event thread - by the window that shows it, or by
	 * {@link #assemble} before the worker starts - so there is nothing to synchronise.</p>
	 */
	public MemoryCellGroup getGroup() {
		if(m_group == null) {
			//-- Virtual addresses: a listing says where the program will run, not where a console
			//-- will physically put it. Every console normalises to its own width on the way out.
			m_group = m_context.getMemoryCellGroups().addGroup(MemoryAddressType.VIRTUAL, "MACRO-11 code");
			m_group.setUsageTag("macro11");
			m_group.setPdpOverwritesEdit(false);
		}
		return m_group;
	}

	/** Whether there is any assembled code. Does not make a group just to find out there is none. */
	public boolean hasCode() {
		return m_group != null && !m_group.isEmpty();
	}

	public String getSourceText() {
		return m_sourceText;
	}

	/** The file the source came from, or null for something typed and never saved. */
	public Path getSourceFile() {
		return m_sourceFile;
	}

	/** The listing of the last assembly, or null if nothing has been assembled or loaded. */
	public Macro11Listing getListing() {
		return m_listing;
	}

	public Path getListingFile() {
		return m_listingFile;
	}

	/** Whether the last assembly succeeded. Nothing should be deposited when this is false. */
	public boolean isTranslated() {
		return m_translated;
	}

	/**
	 * Whether an assembly is running. Both windows that assemble ask, so neither offers the
	 * button that {@link #assemble} would refuse.
	 */
	public boolean isAssembling() {
		return m_assembling;
	}

	/** Whether the editor holds something the file does not. */
	public boolean isChanged() {
		return !m_sourceText.equals(m_savedText);
	}

	/**
	 * Whether the editor's contents may be thrown away - which they may when nothing is unsaved,
	 * and otherwise only if the user says so.
	 *
	 * <p>Here rather than in the window because three places destroy this text and they are in
	 * two different classes: New and Open in the Assembler window, and Quit in the main one. A
	 * window that draws a "*" to mean "changed since saved" and then discards those changes
	 * without asking is telling the user something it does not act on.</p>
	 *
	 * <p>Event thread only; see {@link AppContext#confirmDiscard}.</p>
	 *
	 * @param action what is about to happen, in the infinitive: "start a new program",
	 *               "open another file", "quit"
	 */
	public boolean confirmDiscard(String action) {
		if(!isChanged())
			return true;
		String name = m_sourceFile == null
			? "The MACRO-11 source has never been saved"
			: m_sourceFile.getFileName() + " has changes that have not been saved";
		return m_context.confirmDiscard(name + ".\n\nDiscard them and " + action + "?");
	}

	/**
	 * Whether there is anything worth assembling: a name to save under, and some text. Says
	 * nothing about whether an assembly is already running - {@link #isAssembling()} does, and
	 * the two mean different things to the user.
	 */
	public boolean canAssemble() {
		return m_sourceFile != null && !m_sourceText.isBlank();
	}

	public void addListener(Listener l) {
		m_listeners.add(l);
	}

	public void removeListener(Listener l) {
		m_listeners.remove(l);
	}

	// -------------------------------------------------------------------------------------
	// The source
	// -------------------------------------------------------------------------------------

	/** What the editor now holds. */
	public void setSourceText(String text) {
		String value = text == null ? "" : text;
		if(value.equals(m_sourceText))
			return;
		m_sourceText = value;
		fire();
	}

	/**
	 * Start a new, empty program. {@code NewButtonClick} ({@code :460-463}).
	 *
	 * <p>The code group is emptied with it: leaving the last program's words in the Code tab
	 * beside an empty editor is how somebody deposits the wrong thing.</p>
	 */
	public void newSource() {
		m_sourceText = "";
		m_savedText = "";
		m_sourceFile = null;
		clearListing();
		fire();
	}

	/**
	 * Read a source file into the editor.
	 *
	 * <p><b>Not detabbed</b>, unlike the Pascal ({@code :247-248}), which expands tabs to eight
	 * on load and re-tabs on save - with its own comment on the entab calling it "unnecessary,
	 * and broken?". It was a workaround for an editor that could not draw a tab. This one can,
	 * and MACRO-11 source is tab-formatted by convention, so what was in the file goes back into
	 * the file unchanged.</p>
	 */
	public void loadSource(Path file) throws IOException {
		//-- ISO-8859-1 for the same reason the listing is read that way: assembler source is
		//-- bytes, and a stray high byte must not fail the read on a UTF-8 machine.
		String text = Files.readString(file, StandardCharsets.ISO_8859_1);
		m_sourceText = text;
		m_savedText = text;
		m_sourceFile = file.toAbsolutePath();
		clearListing();
		rememberSourceFile();
		m_context.getLogger().log(LogChannel.OTHER, "Loaded MACRO-11 source %s", m_sourceFile);
		fire();
	}

	/** Write the editor's contents out, and remember that this is now the source file. */
	public void saveSource(Path file) throws IOException {
		Path target = file.toAbsolutePath();
		Files.writeString(target, m_sourceText, StandardCharsets.ISO_8859_1);
		m_savedText = m_sourceText;
		m_sourceFile = target;
		rememberSourceFile();
		m_context.getLogger().log(LogChannel.OTHER, "Saved MACRO-11 source %s", target);
		fire();
	}

	/** Open whatever was open last time, quietly doing nothing if it has gone. */
	public void loadLastSource() {
		String last = m_context.getSettings().getLastSourceFile();
		if(last == null || last.isBlank())
			return;
		Path file = Path.of(last);
		if(!Files.isRegularFile(file))
			return;
		try {
			loadSource(file);
		} catch(IOException x) {
			//-- Not worth a dialog on the way up: the window opens empty and the log says why.
			m_context.getLogger().log(LogChannel.OTHER, "Could not reopen %s: %s", file, x);
		}
	}

	private void rememberSourceFile() {
		m_context.getSettings().setLastSourceFile(m_sourceFile == null ? null : m_sourceFile.toString());
	}

	// -------------------------------------------------------------------------------------
	// Assembling
	// -------------------------------------------------------------------------------------

	/** What one assembly produced. Failures are reported before this is delivered. */
	public record Outcome(boolean ok, Macro11Listing listing, String message) {
	}

	/**
	 * Save if needed, run MACRO-11, parse what it wrote.
	 *
	 * <p>Returns at once; {@code whenDone} runs on the event thread, with {@code ok} false when
	 * anything went wrong - which has already been reported through
	 * {@link AppContext#reportFailure}. The auto-save before the run is the Pascal's
	 * ({@code :351-353}) and is what makes Compile mean "compile what I am looking at".</p>
	 */
	public void assemble(Consumer<Outcome> whenDone) {
		if(m_assembling) {
			//-- Two assemblies would race to install into the one code group, and the Execution
			//-- window's "New program" can be pressed while the Assembler window is compiling.
			fail(whenDone, "An assembly is already running", null);
			return;
		}
		if(m_sourceFile == null) {
			fail(whenDone, "Save the source to a file before assembling it - MACRO-11 reads a file,"
				+ " not an editor", null);
			return;
		}
		if(m_sourceText.isBlank()) {
			fail(whenDone, "There is nothing to assemble", null);
			return;
		}
		Path source = m_sourceFile;
		String text = m_sourceText;
		boolean needsSave = isChanged();
		//-- Made here, on the event thread, so the worker never has to. The worker needs its
		//-- width, and nothing else about it.
		MemoryCellGroup group = getGroup();
		MemoryAddressType type = group.getType();
		m_assembling = true;
		fire();

		Thread worker = new Thread(() -> {
			try {
				if(needsSave)
					Files.writeString(source, text, StandardCharsets.ISO_8859_1);
				Macro11.Result result = Macro11.assemble(source, m_context.getLogger());
				//-- Parsed off the worker as well: it is a few hundred lines of string work and
				//-- the event thread has nothing to add to it. Detached, though - the group it
				//-- ends up in belongs to the event thread.
				Macro11ListingParser.Parsed parsed = Macro11ListingParser.parse(result.listing(), type);
				AppContext.onUi(() -> {
					m_assembling = false;
					Macro11Listing listing = parsed.installInto(group);
					if(needsSave)
						m_savedText = text;
					m_listing = listing;
					m_listingFile = result.listing();
					m_translated = listing.isOk();
					m_context.getLogger().log(LogChannel.OTHER, "MACRO-11: %d words, %d problems",
						listing.getWordCount(), listing.getProblems().size());
					//-- A program says where it starts, and the window that starts things is a
					//-- different window. Same route the Memory Loader uses.
					Address start = listing.getStartAddress();
					if(m_translated && start != null)
						m_context.getMachineState().setStartPc(start);
					fire();
					deliver(whenDone, new Outcome(m_translated, listing,
						m_translated
							? listing.getWordCount() + " words assembled"
							: listing.getFirstProblem().describe()));
				});
			} catch(IOException | RuntimeException x) {
				AppContext.onUi(() -> {
					m_assembling = false;
					m_translated = false;
					fire();
					fail(whenDone, "Could not assemble " + source.getFileName(), x);
				});
			}
		}, "macro11-assemble");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * Read a listing file that somebody else produced.
	 *
	 * <p>{@code LoadButtonClick} on the listing window ({@code FormMacro11ListingU.pas:579-591}).
	 * Worth keeping: a listing is the durable half of an assembly, and one saved months ago can
	 * still be deposited into a machine without the source or the assembler being present.</p>
	 */
	public void loadListing(Path file) throws IOException {
		Macro11Listing listing = Macro11ListingParser.parse(file, getGroup());
		m_listing = listing;
		m_listingFile = file.toAbsolutePath();
		//-- Loaded, not assembled: there is no source behind this and nothing to mark errors in.
		m_translated = listing.isOk();
		m_context.getLogger().log(LogChannel.OTHER, "Loaded listing %s: %d words", file, listing.getWordCount());
		fire();
	}

	private void clearListing() {
		m_listing = null;
		m_listingFile = null;
		m_translated = false;
		if(m_group != null)
			m_group.clear();
	}

	// -------------------------------------------------------------------------------------
	// Loading it into the machine
	// -------------------------------------------------------------------------------------

	/**
	 * Write the assembled code into the machine.
	 *
	 * <p>{@code TFormMacro11Listing.Deposit} ({@code :146-154}), which deposits everything rather
	 * than only what changed - the machine's current contents are unknown and comparing against
	 * an unknown would skip every word.</p>
	 *
	 * @param whenDone run on the event thread after the deposit lands, or not at all if it failed
	 */
	public void deposit(Window owner, Runnable whenDone) {
		if(!hasCode()) {
			m_context.reportFailure("There is no assembled code to load", null);
			return;
		}
		MemoryCellGroup group = m_group;
		ProgressDialog progress = new ProgressDialog(owner);
		m_context.onConsole("Loading the program", console -> {
			console.deposit(group, false, progress);
			if(whenDone != null)
				AppContext.onUi(whenDone);
		});
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	private void fail(Consumer<Outcome> whenDone, String message, Throwable cause) {
		m_context.reportFailure(cause == null ? message : message + ": " + cause.getMessage(), cause);
		deliver(whenDone, new Outcome(false, m_listing, message));
	}

	private static void deliver(Consumer<Outcome> whenDone, Outcome outcome) {
		if(whenDone != null)
			whenDone.accept(outcome);
	}

	private void fire() {
		AppContext.onUi(() -> {
			for(Listener l : m_listeners) {
				l.assemblerChanged(this);
			}
		});
	}
}
