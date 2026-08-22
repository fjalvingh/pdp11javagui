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
- **Never call `Console` methods on the EDT, and never call `get()`/`join()` on a console
  future from the EDT.** All console work is submitted to the single-threaded command
  executor, whose results are marshalled back to the EDT with `SwingUtilities.invokeLater`. A
  single blocking call on the EDT deadlocks the app. See PLAN.md §1.
  `ConsoleConnection.call()` is how the work gets onto the command thread, and it throws rather
  than deadlocking if it is called from that thread itself. `ConnectionManager.connect()` blocks
  too, for as long as launching SimH takes — the main window runs it on a worker.
- **A window is handed what it needs; it never reaches for it.** Everything shared lives on
  `AppContext`, which is constructed once in `Pdp11Gui.main` and passed down. There is no static
  instance and there must not be one. This is what makes lazy window creation work at all — see
  PLAN.md §5 on the ~120 `FormMain.X` reads in the Pascal that have no target here.
- **UI logic goes in a `JPanel`, not in the `JFrame`.** A panel can be sized, laid out and
  painted into an image with no display; a frame cannot. So a window is a thin frame around a
  panel (`MainPanel`, `LogPanel`), and the layout is checked headlessly by `UiRenderer` and
  rendered to `target/ui-render/*.png` on every build. Anything put directly on a frame is
  untestable on CI and unlookable-at without borrowing somebody's screen.
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

## External tools

`macro11` (MACRO-11 assembler) and SimH's `pdp11` are invoked as external processes and must
be on `PATH` **to be used**; both are present at `/home/jal/bin/`. Neither is needed to build,
to run the tests, or to drive a simulated machine — `TransportKind.SIMULATED` needs nothing at
all, which is why the tests and the first-run experience both lean on it. The app should detect
their absence when something asks for them and say so clearly rather than failing obscurely. `m4` is *not* a runtime
dependency of the Java version — machine-description preprocessing is reimplemented in Java
(see PLAN.md §7, which also records that this feature is currently broken on Linux in the
Pascal build).
