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
- **The protocol layer is byte-oriented, not text.** Keep `byte[]` / ISO-8859-1 below the
  terminal; never let a default-charset conversion near it.
- **Octal throughout.** All PDP-11 addresses and values are octal in user-facing I/O, per
  PDP-11 convention.
- **Use `java.nio.file.Path` for all path handling.** The Pascal has hardcoded `\` separators
  and a drive-letter-aware `CorrectPath`; none of that is ported.

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
be on `PATH`; both are present at `/home/jal/bin/`. The app should detect their absence at
startup and say so clearly rather than failing at first use. `m4` is *not* a runtime
dependency of the Java version — machine-description preprocessing is reimplemented in Java
(see PLAN.md §7, which also records that this feature is currently broken on Linux in the
Pascal build).
