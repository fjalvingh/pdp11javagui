# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## What this repository is

A Java/Swing rewrite of **PDP11GUI**, an IDE for real and simulated PDP-11 computers. The
original is Delphi/VCL (Windows), since ported to Lazarus/Free Pascal for Linux and living at
`/home/jal/git/grb/pdp11gui`. That Pascal tree is the **reference implementation** — consult
it constantly while porting; it is not a dependency and nothing here builds against it.

The rewrite is not a transliteration. It drops MDI in favour of free-floating top-level
windows, replaces the single-threaded `Application.ProcessMessages` I/O model with real
threads, and rebuilds the UI with layout managers instead of ~69k lines of absolute-positioned
`.dfm`.

Three modules: `pdp11-core` (model, transports, console protocols, the simulated machines —
headless, and enforced so), `pdp11-ui` (windows, window manager, settings), `pdp11-app`
(`main()`, wiring, resources). The dependency runs core ← ui ← app and never the other way.

## Read the plan first

**`PLAN.md` in this directory is the authoritative design and phasing document.** Read it
before starting any work here. It covers, in order:

- why Swing rather than SWT, and the agreed dependency stack
- the module layout and the layering rule that holds it together
- §1 the threading architecture — **read this before touching any I/O or console code**
- §2 `pdp11-core` type design (`Address`, `MemoryCell*`, `AnswerPhrase`, scanners)
- §3 the free-floating window architecture (`ToolWindow`, `WindowManager`, geometry)
- §4 settings and persistence
- §5 the phased delivery plan — each phase ends with something runnable
- §6 verification strategy
- §7 risks and open items

Keep `PLAN.md` current. When a decision in it turns out to be wrong, amend it rather than
silently diverging.

## Hard rules

- **`pdp11-core` must not depend on Swing or AWT.** The module compiles with
  `--limit-modules java.base`, so an offending import is a compile error, and an ArchUnit test
  covers the test sources that flag cannot reach. It is the single most important structural
  constraint in the project —
  it is what keeps the console protocol layer testable headlessly against the ported fakes.
  If a core class seems to need a dialog or a progress bar, it needs a `ProgressMonitor` or an
  exception (`NoConsolePromptException`, `OperationCancelledException`) instead. See PLAN.md
  §1 and §2.
- **An algorithm goes in `pdp11-core`, even when exactly one window uses it.** If the awkward
  part of a window is arithmetic over what the machine said - which line the PC is on, which
  addresses answered, which data line is stuck - that part is a class in the core with a test,
  and the window is the thing that shows its result. `DisassemblyListing`, `IoPageScanner` and
  `MemoryTester` are all this, and all three were got wrong in a way a test caught. A window with
  an algorithm inside it can only be checked by looking at it.
- **Never call `Console` methods on the EDT, and never call `get()`/`join()` on a console
  future from the EDT.** All console work is submitted to the single-threaded command
  executor, whose results are marshalled back to the EDT with `SwingUtilities.invokeLater`. A
  single blocking call on the EDT deadlocks the app. See PLAN.md §1.
  `ConsoleConnection.call()` is how the work gets onto the command thread, and it throws rather
  than deadlocking if it is called from that thread itself. `ConnectionManager.connect()` blocks
  too, for as long as launching SimH takes — the main window runs it on a worker.
- **A button reaches the machine through `AppContext.onConsole`, and nothing else.** It queues
  the job on the command thread and returns at once, so the rule above cannot be broken by
  accident; the job then calls the console as often as it likes and marshals anything it wants to
  show back with `AppContext.onUi`. Long operations take a `ProgressDialog`, which is the UI's
  implementation of the core's `ProgressMonitor`.
- **No window tells another window anything.** Shared machine state - is it running, where is the
  PC, where does a just-loaded program start - lives on `MachineState`, and which cell the user is
  looking at lives on `CellSelection`.
  Windows watch them. This is what replaces the Pascal's `TFormExecute.SetAndShowPc` naming five
  other forms one by one, and every grid calling `FormMain.SyncBitfieldForm`; a window that is not
  open hears nothing and needs to hear nothing, and adding a window changes no existing one. See
  PLAN.md §5.
- **Only `UiColors` names a colour.** Everything that means something - a value typed and not
  deposited, the line the PC is on, connected, failed - is a constant there, tuned for the dark
  theme the application runs. A `new Color(...)` anywhere else is how a second theme becomes a
  hunt through every panel. Note the two carried over from the Pascal are Delphi `TColor`
  literals, which are **BGR**: `$80FFFF` is light yellow, not pale blue.
- **A window is handed what it needs; it never reaches for it.** Everything shared lives on
  `AppContext`, which is constructed once in `Pdp11Gui.main` and passed down. There is no static
  instance and there must not be one. This is what makes lazy window creation work at all — see
  PLAN.md §5 on the ~120 `FormMain.X` reads in the Pascal that have no target here.
- **UI logic goes in a `JPanel`, not in the `JFrame`.** A panel can be sized, laid out and
  painted into an image with no display; a frame cannot. So a window is a thin frame around a
  panel (`MainPanel`, `LogPanel`), and the layout is checked headlessly by `UiRenderer` and
  rendered to `target/ui-render/*.png` on every build. Anything put directly on a frame is
  untestable on CI and unlookable-at without borrowing somebody's screen.
- **A `JTable` column needs a *minimum* width, not just a preferred one.** Any auto-resize mode
  redistributes the preferred widths on the first layout pass and keeps the result, so a column
  set to 160 comes back at 67 and its text is elided beside an empty neighbour. Set both.
- **All Swing work on the event thread, including in tests.** Laying out a component tree takes
  the AWT tree lock and then a text component's document lock; appending to a terminal takes
  them in the opposite order. Doing those on two threads deadlocks reliably, and it presents as
  a hang rather than as an error. `UiRenderer` marshals for this reason.
- **The protocol layer is byte-oriented, not text.** Keep `byte[]` / ISO-8859-1 below the
  terminal; never let a default-charset conversion near it.
- **Octal throughout.** All PDP-11 addresses and values are octal in user-facing I/O, per
  PDP-11 convention.
- **Use `java.nio.file.Path` for all path handling.** The Pascal has hardcoded `\` separators
  and a drive-letter-aware `CorrectPath`; none of that is ported.
- **Nothing in settings may stop the application starting.** A settings file can be missing,
  empty, truncated, hand-edited into nonsense or written by a newer version. Every one of those
  carries on with defaults and says so; a program that will not start because it cannot remember
  where its windows were is worse than one that forgets.

## Porting conventions

- Comments in the Pascal source are frequently in German. Translate them to English when
  carrying logic across. Write new comments in English.
- Some Pascal comments are **specifications, not commentary** — notably the ODT state
  transition table and the real-hardware behaviour notes verified on an actual PDP-11/23
  (`Pdp11gui/FakePDP11ODTU.pas:60-130`). Port those verbatim as Javadoc and as test names.
- Pascal `assert()` becomes a real `IllegalArgumentException`/`IllegalStateException`, not a
  Java `assert` — both are disabled by default, and several encode genuine invariants.
- Pascal strings are 1-based. Convert to 0-based deliberately, per scanner, with tests. Note
  `Copy(s, i, maxint)` returns `""` past the end where `substring` throws.
- Pascal has no unsigned types in Java. Do not translate the `MEMORYCELL_ILLEGALVAL`
  (`$ffffffff`) sentinel — as a signed `int` it is `-1` and every relational comparison
  inverts. Use the `CellValue` / `AddressRange` types described in PLAN.md §2.
- Do not port dead code: `MemoryLoaderU.org.pas`, `.org2.pas`, `SerialIoHubU.indy.pas`,
  `FormTerminalU.emulvt.pas`, `FormTerminalU.richedit.org.pas` are excluded from the Pascal
  build by unit-name collision. `common/JH_Utilities.pas` is a 2,659-line junk drawer —
  cherry-pick only the functions with real callers.

## Testing conventions

- **Everything must run headless**, including the layout tests. A test that needs a display is a
  test CI cannot run.
- **Test against the fakes, not against mocks.** `core.fake` has a simulated machine for every
  console protocol; they exercise the real protocol code end to end, and a `ConnectionProfile`
  with `TransportKind.SIMULATED` drives the whole application against one.
- **A test that needs an external program skips rather than fails** when it is missing — see
  `SimhConsoleIT`. CI has no SimH, no `macro11` and no Free Pascal, and is not getting them.
- **Anything with a reader and a writer is tested by round trip**, in one test that does both.
  Two bugs in the original's split-byte format survived for years because each hides the other:
  the writer produces an all-zero high byte file, the reader takes half the words, and reading
  back what it wrote looks like a corrupt file rather than like two mistakes. A test that only
  writes, or only reads, cannot see either.
- **A diagnostic must be shown the fault it exists to find.** The fakes can be broken on purpose
  - `FakePdp11.setStuckDataLines`, `setDeadAddressLine` - so the memory tests are checked against
  a stuck data line and a dead address line rather than against working memory. The original's
  author did this by hand, with two commented-out lines in `TestSingleBit`; here it runs on every
  build.
- **Wait for the thing you are asserting, not for something that precedes it.** A value reaches a
  cell on the command thread and reaches the view on the event thread. A test that waits for the
  first and asserts the second passes until it does not. This has already been fixed once.
- **Where a fake is extended beyond what the Pascal's does, say so in the source and say what
  the evidence was.** The 11/44 fakes answer `H`, `N` and `C` because the shipped driver sends
  them; the reply format came from the Pascal's parser and so from real hardware, but the
  acceptance is inferred. That distinction matters when reading the test later.

## Changelog

New functionality must be recorded in `CHANGES.md` in this directory. Add a short entry
describing what was added or changed when you implement it — don't wait to be asked.

## Out of scope

The PDP-11/70 front panel (`FormPdp1170PanelU` and all of `pdp1170panel/`) is deliberately not
ported; its IO-Warrior USB binding is already inert on Linux. Note that
`FormExecuteBlinkenlightU`/`BlinkenlightInstructionsU` are *not* the panel — they generate
instructions for keying memory in on a real front panel — and are in scope.

## What a memory cell's two values mean

Every window that shows memory shows `MemoryCell`, which carries a **machine value** (what the
PDP-11 last said) and an **edit value** (what should be there instead). The difference is the
window's whole vocabulary and is worth stating once:

- an **examine** sets both, so nothing shows as changed;
- **typing**, and **loading a file**, set only the edit value, so every affected word shows as
  changed until it has been deposited - which is what makes the Deposit button mean something;
- a **verify** sets only the machine value, so the file's values stay put and the disagreements
  colour themselves. This only works because those groups have `pdpOverwritesEdit` off; with it
  on, the read would silently replace what the user is about to write.

A cell whose value was never read is `CellValue.UNKNOWN`, and that is not zero. When a positional
file format has to write something for one, write zero and **report how many** - the Pascal writes
its `$ffffffff` sentinel truncated to `0177777`, which is a real value, quietly, in the middle of
something about to be burned into a ROM.

## Machine descriptions

The `.ini` files that describe a machine's devices are resources in `pdp11-app`, and are
**installed into the data directory on the way up** (`MachineDescriptionStore`) because m4
resolves includes over a directory and a jar has none. An existing file is never overwritten, so
a user's edit survives an upgrade. `machines/index.txt` says what to install and a test asserts
it names exactly what is packaged — add a `.modules` file and forget the index, and the load
breaks for everyone whose copy is not already on disk.

Descriptions declare every address as a **16-bit I/O page address** so one definition serves 16,
18 and 22-bit machines. Nothing has to be done about that: every console normalises addresses to
its own width in its own `toPhysical`, and `MemoryCellGroups` keys its propagation index on the
22-bit form. The Pascal's `ChangeAdddressWidth`, called from nine places in `FormMainU`, is not
ported and is not needed — `RegisterGroupWidthTest` holds that claim down.

## External tools

`macro11` (MACRO-11 assembler) and SimH's `pdp11` are invoked as external processes and must
be on `PATH` **to be used**; both are present at `/home/jal/bin/`. Neither is needed to build,
to run the tests, or to drive a simulated machine — `TransportKind.SIMULATED` needs nothing at
all, which is why the tests and the first-run experience both lean on it. The app should detect
their absence when something asks for them and say so clearly rather than failing obscurely. `m4` is *not* a runtime
dependency of the Java version — machine-description preprocessing is reimplemented in Java
(see PLAN.md §7, which also records that this feature is currently broken on Linux in the
Pascal build).
