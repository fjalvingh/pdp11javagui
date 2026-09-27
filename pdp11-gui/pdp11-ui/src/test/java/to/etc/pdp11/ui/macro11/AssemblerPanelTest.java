package to.etc.pdp11.ui.macro11;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import to.etc.pdp11.core.addr.Address;
import to.etc.pdp11.core.addr.MemoryAddressType;
import to.etc.pdp11.core.conn.ConnectionProfile;
import to.etc.pdp11.core.conn.ConsoleProtocol;
import to.etc.pdp11.core.macro11.Macro11;
import to.etc.pdp11.ui.AppContext;
import to.etc.pdp11.ui.Edt;
import to.etc.pdp11.ui.TestContext;
import to.etc.pdp11.ui.UiRenderer;
import to.etc.pdp11.ui.exec.ExecutionPanel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Assembler window: source in one tab, the listing in the second, the code in the third.
 *
 * <p>The listing format itself is {@code Macro11ListingParserTest}'s problem, and running the
 * assembler for real is {@code Macro11IT}'s. What is checked here is the window: that the editor
 * and the model stay in step, that a listing loaded from disk fills the code grid, that the
 * error and PC markers land on the right lines, and that depositing writes the program into a
 * machine.</p>
 *
 * <p>Most of it needs no {@code macro11} at all - a listing is a text file, and reading one is
 * the durable half of an assembly. The tests that do run the tool say so and skip themselves.</p>
 */
class AssemblerPanelTest {
	private static final long TIMEOUT_MS = 30_000;

	/** Real {@code macro11} output for a three-instruction program at 1000. */
	private static final String LISTING = String.join("\n",
		"       1                                \t.asect",
		"       2 001000                         \t.=1000",
		"       3 001000 012706  000400          start:\tmov\t#400,sp",
		"       4 001004 005000                  \tclr\tr0",
		"       5 001006 000000                  \thalt",
		"       6                                \t.end",
		"");

	@BeforeAll
	static void lookAndFeel() {
		UiRenderer.installLookAndFeel();
	}

	private static void until(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while(System.currentTimeMillis() < deadline) {
			if(condition.getAsBoolean())
				return;
			try {
				Thread.sleep(20);
			} catch(InterruptedException x) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(x);
			}
		}
		throw new AssertionError("Timed out waiting for " + what);
	}

	private static Path write(Path dir, String name, String content) throws Exception {
		Path f = dir.resolve(name);
		Files.writeString(f, content, StandardCharsets.ISO_8859_1);
		return f;
	}

	// -------------------------------------------------------------------------------------
	// The editor and the model
	// -------------------------------------------------------------------------------------

	@Test
	void theThreeTabsAreThereAndTheLayoutHolds(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		UiRenderer.layOut(panel, 1000, 700);

		assertEquals(3, panel.getTabs().getTabCount());
		assertEquals("Source", panel.getTabs().getTitleAt(0));
		assertEquals("Listing", panel.getTabs().getTitleAt(1));
		assertEquals("Code", panel.getTabs().getTitleAt(2));
		//-- Nothing loaded, so there is nothing to assemble and nothing to deposit.
		assertFalse(panel.getCompileButton().isEnabled());
		assertFalse(panel.getDepositAllButton().isEnabled());
	}

	/** Typing reaches the model, which is what makes the Execution window's button work. */
	@Test
	void whatIsTypedReachesTheModel(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(() -> panel.getSourceArea().setText("\thalt\n"));
		assertEquals("\thalt\n", ctx.getAssembler().getSourceText());
		assertTrue(ctx.getAssembler().isChanged());
	}

	/** Loading a source file fills the editor and stops it looking changed. */
	@Test
	void loadingASourceFillsTheEditor(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\n\thalt\n");

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});
		assertTrue(panel.getSourceArea().getText().contains(".=1000"));
		assertFalse(ctx.getAssembler().isChanged());
		assertTrue(panel.getSourceStatusText().contains("p.mac"), panel.getSourceStatusText());
	}

	/**
	 * Tabs are not expanded on load.
	 *
	 * <p>The Pascal detabs to eight on the way in and re-tabs on the way out, which rewrites a
	 * file the user did not edit. Its own comment on the entab reads "unnecessary, and broken?".
	 */
	@Test
	void aSourceIsSavedBackExactlyAsItWasRead(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		Edt.call(() -> new AssemblerPanel(ctx));
		String text = "start:\tmov\t#400,sp\t; tabs, not spaces\n";
		Path src = write(dir, "p.mac", text);

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
				ctx.getAssembler().saveSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});
		assertEquals(text, Files.readString(src, StandardCharsets.ISO_8859_1));
	}

	/** New empties the editor and the code with it, so nothing stale can be deposited. */
	@Test
	void newClearsTheCodeAsWellAsTheEditor(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path lst = write(dir, "p.lst", LISTING);
		Edt.run(() -> {
			try {
				ctx.getAssembler().loadListing(lst);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});
		assertEquals(4, ctx.getAssembler().getGroup().size());

		Edt.run(() -> panel.getNewButton().doClick());
		assertTrue(ctx.getAssembler().getGroup().isEmpty(), "the Code tab is emptied too");
		assertEquals("", panel.getSourceArea().getText());
	}

	// -------------------------------------------------------------------------------------
	// Not losing what was typed
	// -------------------------------------------------------------------------------------

	/**
	 * New asks before throwing unsaved text away, and takes no for an answer.
	 *
	 * <p>The window draws a "*" and enables Save to say "this is not on disk", and then used to
	 * discard exactly that with no prompt - from New, from Open, and from Quit.</p>
	 */
	@Test
	void newAsksBeforeDiscardingUnsavedSourceAndTakesNoForAnAnswer(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		List<String> asked = new ArrayList<>();
		ctx.setDiscardConfirmer(question -> {
			asked.add(question);
			return false;
		});

		Edt.run(() -> panel.getSourceArea().setText("\thalt\n"));
		assertTrue(ctx.getAssembler().isChanged());
		Edt.run(() -> panel.getNewButton().doClick());

		assertEquals(1, asked.size(), "it asked");
		assertTrue(asked.get(0).contains("never been saved"), asked.get(0));
		assertTrue(asked.get(0).contains("start a new program"), asked.get(0));
		assertEquals("\thalt\n", ctx.getAssembler().getSourceText(), "and kept what was typed");
		assertEquals("\thalt\n", panel.getSourceArea().getText());
	}

	/** Said yes, and it goes - the answer is obeyed in both directions. */
	@Test
	void newDiscardsWhenTheAnswerIsYes(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		ctx.setDiscardConfirmer(question -> true);

		Edt.run(() -> panel.getSourceArea().setText("\thalt\n"));
		Edt.run(() -> panel.getNewButton().doClick());
		assertEquals("", ctx.getAssembler().getSourceText());
	}

	/** Nothing unsaved, nothing to ask about: New must not put a dialog in the way for no reason. */
	@Test
	void newAsksNothingWhenThereIsNothingToLose(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path src = write(dir, "p.mac", "\thalt\n");
		List<String> asked = new ArrayList<>();
		ctx.setDiscardConfirmer(question -> {
			asked.add(question);
			return true;
		});

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});
		assertFalse(ctx.getAssembler().isChanged());
		Edt.run(() -> panel.getNewButton().doClick());
		assertEquals(List.of(), asked);
		assertEquals("", ctx.getAssembler().getSourceText());
	}

	/**
	 * Open asks too, and names the file whose changes would go.
	 *
	 * <p>Clicking Open would raise a file chooser, which no test can answer, so this asks the
	 * model the question the button asks it. The point being checked is the wording and the
	 * refusal, not the chooser.</p>
	 */
	@Test
	void openAndQuitAskAboutTheNamedFile(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		Edt.call(() -> new AssemblerPanel(ctx));
		Path src = write(dir, "p.mac", "\thalt\n");
		List<String> asked = new ArrayList<>();
		ctx.setDiscardConfirmer(question -> {
			asked.add(question);
			return false;
		});

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
			ctx.getAssembler().setSourceText("\tclr\tr0\n");
		});

		assertFalse(ctx.getAssembler().confirmDiscard("open another file"));
		assertFalse(ctx.getAssembler().confirmDiscard("quit"));
		assertEquals(2, asked.size());
		//-- The name matters: "the MACRO-11 source" is not enough to decide with when several
		//-- files have been open this session.
		assertTrue(asked.get(0).contains("p.mac"), asked.get(0));
		assertTrue(asked.get(0).contains("open another file"), asked.get(0));
		assertTrue(asked.get(1).contains("quit"), asked.get(1));
	}

	// -------------------------------------------------------------------------------------
	// The listing
	// -------------------------------------------------------------------------------------

	/** A listing on disk is a whole program: no source and no assembler needed. */
	@Test
	void aListingLoadedFromDiskFillsTheCodeTab(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path lst = write(dir, "p.lst", LISTING);

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadListing(lst);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});
		assertEquals(4, ctx.getAssembler().getGroup().size());
		assertEquals("001000", panel.getStartAddressField().getText());
		assertTrue(panel.getListingArea().getText().contains("start:"));
		assertTrue(panel.getCodeStatusText().contains("4 words at 001000"), panel.getCodeStatusText());
		//-- The grid shows the group, laid out by address.
		assertEquals(ctx.getAssembler().getGroup(), panel.getGrid().getGroup());
	}

	/**
	 * The error marker lands on the source line the assembler named, and on every listing line
	 * that line produced.
	 */
	@Test
	void anErrorMarksTheSourceLineAndItsListingLines(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		//-- Source and listing together, so the marker has somewhere to land in both.
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\n\t.byte\t1\n\tmov\t#1,r0\n\t.end\n");
		Path lst = write(dir, "p.lst", String.join("\n",
			"       1                                \t.asect",
			"       2 001000                         \t.=1000",
			"       3 001000    001                  \t.byte\t1",
			"p.mac:4: ***ERROR Instruction on odd address",
			"       4 001001 012700  000001          \tmov\t#1,r0",
			""));

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
				ctx.getAssembler().loadListing(lst);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});

		assertFalse(ctx.getAssembler().isTranslated());
		assertTrue(panel.getListingStatusText().contains("Instruction on odd address"),
			panel.getListingStatusText());
		assertTrue(panel.getSourceStatusText().contains("line 4"), panel.getSourceStatusText());
		//-- Line 4 of the source, which is index 3, is the one marked.
		assertEquals(3, singleMarkedLine(panel.getSourceMarkedLines()));
		//-- In the listing both lines belonging to source line 4 are marked: the diagnostic
		//-- itself, at index 3, and the instruction it is about, at index 4. Marking only one of
		//-- them would leave either the message or the code it refers to uncoloured.
		assertEquals(java.util.List.of(3, 4), panel.getListingMarkedLines());
	}

	/**
	 * The PC marker follows the machine, and only while the listing describes what is in it.
	 *
	 * <p>Nothing tells this panel where the PC is; it watches {@code MachineState}, like every
	 * other window that cares. The Pascal is told, by name, from {@code SetAndShowPc}.</p>
	 */
	@Test
	void thePcMarkerFollowsTheMachine(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\nstart:\tmov\t#400,sp\n\tclr\tr0\n\thalt\n\t.end\n");
		Path lst = write(dir, "p.lst", LISTING);
		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
				ctx.getAssembler().loadListing(lst);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});

		//-- Stopped at 001004, which the listing says is the clr on its line index 3.
		Edt.run(() -> ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 001004)));
		assertEquals(3, singleMarkedLine(panel.getListingMarkedLines()));
		//-- And the source line that made it, which the listing's own numbering says is line 4.
		assertEquals(3, singleMarkedLine(panel.getSourceMarkedLines()));

		//-- A PC outside the program marks nothing rather than marking the nearest thing.
		Edt.run(() -> ctx.getMachineState().stopped(Address.of(MemoryAddressType.VIRTUAL, 020000)));
		assertEquals(-1, singleMarkedLine(panel.getListingMarkedLines()));
	}

	// -------------------------------------------------------------------------------------
	// The machine
	// -------------------------------------------------------------------------------------

	@Test
	void depositingWritesTheProgramIntoTheMachine(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path lst = write(dir, "p.lst", LISTING);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				try {
					ctx.getAssembler().loadListing(lst);
				} catch(Exception x) {
					throw new IllegalStateException(x);
				}
			});
			assertTrue(panel.getDepositAllButton().isEnabled());
			Edt.run(() -> panel.getDepositAllButton().doClick());
			until("the deposit to finish", () -> !ctx.getAssembler().getGroup().cell(0).isEdited());

			var m = ctx.getConnectionManager();
			var value = m.getConnection().call(() -> m.getConsole().examine(
				Address.of(m.getConsole().physicalAddressType(), 01000)));
			assertEquals(012706, value.word());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * "New program" on the Execution window assembles, loads and resets - with no assembler
	 * window open anywhere.
	 *
	 * <p>That is the whole reason the program is state on the context rather than the contents
	 * of a window. Needs the real assembler, so it skips without one.</p>
	 */
	@Test
	void theExecutionWindowCanAssembleLoadAndResetOnItsOwn(@TempDir Path dir) throws Exception {
		assumeTrue(Macro11.isAvailable(), "macro11 is not on the PATH");
		AppContext ctx = TestContext.create(dir);
		ExecutionPanel execution = Edt.call(() -> new ExecutionPanel(ctx));
		Edt.run(execution::attach);
		//-- No AssemblerPanel is built here on purpose.
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\n\tmov\t#400,sp\n\tclr\tr0\n\thalt\n\t.end\n");

		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				try {
					ctx.getAssembler().loadSource(src);
				} catch(Exception x) {
					throw new IllegalStateException(x);
				}
			});
			assertTrue(ctx.getAssembler().canAssemble());

			Edt.run(() -> ctx.getAssembler().assemble(outcome -> {
				assertTrue(outcome.ok(), outcome.message());
				ctx.getAssembler().deposit(null, null);
			}));
			until("the program to reach the machine",
				() -> !ctx.getAssembler().getGroup().isEmpty()
					&& !ctx.getAssembler().getGroup().cell(0).isEdited());

			var m = ctx.getConnectionManager();
			var value = m.getConnection().call(() -> m.getConsole().examine(
				Address.of(m.getConsole().physicalAddressType(), 01000)));
			assertEquals(012706, value.word());
			//-- And the program said where it starts, which the execution window is showing.
			assertNotNull(ctx.getMachineState().getStartPc());
			assertEquals("001000", execution.getStartPcField().getText());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	/**
	 * A second assembly while one is in flight is refused, rather than racing it into the group.
	 *
	 * <p>Two windows assemble - Compile here and "New program" on the Execution window - and both
	 * install their listing into the one code group. Two workers doing that at once is what the
	 * detached parse cannot protect against on its own.</p>
	 *
	 * <p>No {@code macro11} needed: both calls are made inside one block on the event thread, so
	 * the first worker cannot have got as far as its {@code onUi} step whatever it found.</p>
	 */
	@Test
	void aSecondAssemblyIsRefusedWhileOneIsRunning(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\n\thalt\n\t.end\n");
		StringBuilder reported = new StringBuilder();

		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
			assertFalse(ctx.getAssembler().isAssembling());
			ctx.getAssembler().assemble(null);
			//-- Only now is the failure handler armed: what the worker eventually reports is not
			//-- what this is about.
			ctx.setFailureHandler((message, cause) -> reported.append(message));
			ctx.getAssembler().assemble(outcome -> assertFalse(outcome.ok()));
		});
		assertTrue(reported.toString().contains("already running"), reported.toString());
	}

	/** Assembling with no source file says so rather than running the assembler on nothing. */
	@Test
	void assemblingWithoutAFileIsRefusedWithASentence(@TempDir Path dir) {
		AppContext ctx = TestContext.create(dir);
		Edt.call(() -> new AssemblerPanel(ctx));
		StringBuilder reported = new StringBuilder();
		ctx.setFailureHandler((message, cause) -> reported.append(message));

		Edt.run(() -> ctx.getAssembler().setSourceText("\thalt\n"));
		Edt.run(() -> ctx.getAssembler().assemble(outcome -> assertFalse(outcome.ok())));
		assertTrue(reported.toString().contains("Save the source to a file"), reported.toString());
	}

	/**
	 * Verify compares the assembled program with the machine instead of replacing it.
	 *
	 * <p>The button used to call {@code examineAll}, which copies what the machine said over
	 * every cell's edit value. The assembled words were silently thrown away, nothing could ever
	 * show as differing, and a program that had not been loaded at all - or loaded wrongly -
	 * reported agreement. Same label as the Memory Loader's Verify, and now the same
	 * semantics.</p>
	 *
	 * <p>Needs no {@code macro11}: a listing on disk is a whole program.</p>
	 */
	@Test
	void verifyingComparesTheCodeWithTheMachineRatherThanReplacingIt(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path lst = write(dir, "p.lst", LISTING);
		try {
			ctx.getConnectionManager().connect(ConnectionProfile.simulated(ConsoleProtocol.SIMH));
			Edt.run(() -> {
				try {
					ctx.getAssembler().loadListing(lst);
				} catch(Exception x) {
					throw new IllegalStateException(x);
				}
			});
			assertEquals(4, ctx.getAssembler().getGroup().size());

			//-- Nothing deposited yet, so the machine holds zeros and three of the four words
			//-- disagree - the fourth is the halt, which is 000000 and genuinely agrees with an
			//-- empty machine. A verify that says "4 of 4" is not comparing, it is guessing.
			Edt.run(() -> panel.getVerifyButton().doClick());
			until("the verify to finish", () -> panel.getCodeStatusText().contains("differ from")
				|| panel.getCodeStatusText().contains("holds exactly"));
			assertTrue(panel.getCodeStatusText().contains("3 words of 4 differ"), panel.getCodeStatusText());
			//-- And the program is still in the grid rather than having been overwritten by the
			//-- machine's zeros, which is the part that used to be lost.
			assertEquals(012706, ctx.getAssembler().getGroup().cell(0).getEditValue().word());
			assertTrue(ctx.getAssembler().getGroup().cell(0).isEdited());

			//-- Load it and verify again: now the machine holds the program.
			Edt.run(() -> panel.getDepositAllButton().doClick());
			until("the deposit", () -> !ctx.getAssembler().getGroup().cell(0).isEdited());
			Edt.run(() -> panel.getVerifyButton().doClick());
			until("the second verify", () -> panel.getCodeStatusText().contains("holds exactly"));
			assertEquals(012706, ctx.getAssembler().getGroup().cell(0).getEditValue().word());
		} finally {
			ctx.getConnectionManager().close();
		}
	}

	// -------------------------------------------------------------------------------------
	// Odds and ends
	// -------------------------------------------------------------------------------------

	/**
	 * Each tab is rendered to a PNG so the layout can be looked at without a display.
	 *
	 * <p>Three images rather than one, because two of the three tabs are never painted by a
	 * renderer that only sees the selected one - and a tab nobody looks at is where a layout
	 * goes wrong unnoticed.</p>
	 */
	@Test
	void everyTabPaintsWithNoDisplay(@TempDir Path dir) throws Exception {
		AppContext ctx = TestContext.create(dir);
		AssemblerPanel panel = Edt.call(() -> new AssemblerPanel(ctx));
		Edt.run(panel::attach);
		Path src = write(dir, "p.mac", "\t.asect\n\t.=1000\nstart:\tmov\t#400,sp\t; the stack\n"
			+ "\tclr\tr0\n\thalt\n\t.end\n");
		Path lst = write(dir, "p.lst", LISTING);
		Edt.run(() -> {
			try {
				ctx.getAssembler().loadSource(src);
				ctx.getAssembler().loadListing(lst);
			} catch(Exception x) {
				throw new IllegalStateException(x);
			}
		});

		String[] names = {"assembler-source.png", "assembler-listing.png", "assembler-code.png"};
		for(int tab = 0; tab < names.length; tab++) {
			int index = tab;
			Edt.run(() -> panel.getTabs().setSelectedIndex(index));
			Path png = UiRenderer.renderToFile(panel, 1000, 700, Path.of("target", "ui-render", names[tab]));
			assertTrue(Files.size(png) > 0);
		}
	}

	/** The one line marked in an editor, or -1 when nothing is. */
	private static int singleMarkedLine(java.util.List<Integer> marks) {
		return marks.isEmpty() ? -1 : marks.get(0);
	}
}
