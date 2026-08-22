# Changes

## Unreleased

### Phase 6 part 1 — machine descriptions and the register windows

- **`MemoryCellGroupList`**, the second of the two reusable frames PLAN.md §3 asks for: named
  cells one per row, with register, address, editable value and description. One address under
  several names is several rows that agree — which several real device registers need, the
  RX211's data buffer being declared six times because the controller reinterprets it at each
  stage of a transfer.
- **Machine descriptions are installed into the data directory** on the way up and `pdp11.ini`
  is loaded from there: 17 register groups, 62 bitfield definitions. m4 resolves includes over a
  directory and there is none inside a jar, and it gives the user somewhere to describe their own
  machine. An existing file is never overwritten. `machines/index.txt` says what to install and a
  test asserts it names exactly what is packaged.
- **Register-group windows**, keyed `REGISTER_GROUP/<name>` — the case `WindowKey`'s
  `instanceId` was built for, since these are whatever the loaded description declares rather
  than a fixed set. The Windows menu lists them by name and is built when it opens, so loading a
  different description needs nothing kept in step.
- **The Pascal's global address-width switch is not ported, and does not need to be.** The
  descriptions declare 16-bit I/O page addresses so one definition serves 16, 18 and 22-bit
  machines; the Pascal re-expresses every group in the application whenever a console is chosen,
  from nine call sites. Every console here normalises addresses in its own `toPhysical` and the
  propagation index keys on the 22-bit form, so a 16-bit register group reaches the right
  register on a 22-bit machine untouched. `RegisterGroupWidthTest` proves it against a live
  simulated machine instead of asserting it in a comment.
- `SyncBitfieldForm` becomes an announcement rather than a reach-in: the list says which cell is
  selected, and the Bitfields window will subscribe when it exists.

### Dark theme

- The application runs **FlatLaf's Darcula**. The terminal was always a dark glass TTY -
  `GlassTerminalView` paints itself `0x121214` whatever is around it - so a light frame around
  it was the odd part.
- Every colour that carries meaning now lives in `UiColors`, and nothing outside it names one.
  The six that assumed a white background - a dark-grey status detail, a dark green, a dark red -
  are semantic there now (`SECONDARY_TEXT`, `OK_TEXT`, `ERROR_TEXT`), so a second theme is a
  change to one file rather than a hunt through every panel.
- The two markers carried over from the Pascal keep its hues with the luminance turned round:
  the changed-cell yellow and the program-counter rose are dark blocks with pale text here,
  where `$80FFFF` straight across would be a screaming yellow rectangle in a dark table.
- The terminal's scroll pane loses its border. FlatLaf reports visual padding for it, so
  MigLayout laid the terminal out two pixels outside the panel on every side and the border it
  drew was clipped away regardless - and a terminal that is the window's content should not be
  wearing a widget's frame.
- `UiRenderer` installs the same look and feel the application does; a render of a theme nobody
  will see is not worth looking at.

### Phase 5 — the windows that make it useful

- **The application does what it is for.** Connect to a machine, look at memory, change it, run
  the CPU, single-step it and watch the disassembly follow the program counter — the four
  windows PLAN.md §5's "done when" asks for. `WindowsDriveTheMachineTest` drives all of it from
  the panels themselves against a machine simulated in this JVM: no hardware, no SimH, no serial
  port and no display.
- **Settings: two selectors, not twenty-four sentences.** `ConnectionSettingsPanel` is the
  {console protocol} × {transport} decomposition made visible, with saved named profiles and a
  per-transport card of its own settings. The two combinations that cannot work — SimH on a
  serial line, a real machine "launched as a process" — say why, and Connect is simply not
  offered for a configuration that could not work.
- **Memory view**, unlimited: `MemoryCellGroupTable` is the port of `FrameMemoryCellGroupGridU`,
  the first of the two reusable frames seven Pascal forms share. Octal in and out, a gap in the
  addresses showing as a gap in the grid, the changed-value colour, examine and deposit of one
  cell or the whole range, fill-with-address, verify, and export as a SimH `DO` script.
- **`pdpOverwritesEdit` now follows whether there is anything to protect.** *Divergence.* In the
  Pascal it is decided once at construction, so the plain memory window — the very window the
  frame's own comment uses as its example of the problem — still loses uncommitted edits when
  another window examines the same address. Here a grid with edits in it is never overwritten
  and a grid without them tracks the machine.
- **Execution control**, with the enablement table ported case by case: which buttons work
  depends on the console's features *and* the machine's state at once, and getting it wrong
  produces a button that silently does nothing. Halt is always enabled, as in the Pascal,
  because a console that cannot halt can still say which switch to move.
- **Disassembler**, following the PC. `DisassemblyListing` (in `pdp11-core`, so it is testable
  with no window) does the decoding *and* the awkward part: a PC that falls inside an instruction
  rather than at the start of one, which the listing fixes by beginning two bytes later and
  trying again.
- **`MachineState` replaces the reach-ins.** `TFormExecute.SetAndShowPc` tells five other windows
  about a new PC by naming each of them, which with create-on-demand windows has no target. The
  PC is application state now; windows watch it, the execution window does not know the
  disassembler exists, and a machine that stops while every window is shut is still noticed.
- `AppContext.onConsole(what, job)` is the single door between a button and a machine: queued on
  the command thread, never waited for on the event thread, with cancellation and failure
  handled in one place. `ProgressDialog` implements the core's `ProgressMonitor` and appears only
  after a second, so a bulk examine over a serial line can be watched and stopped while the same
  operation against a simulated machine flashes nothing at anybody.
- `MemoryCellGroup.shiftRange` — how a memory view scrolls and how the disassembler follows the
  PC. **One correction to the original:** `CellIndexByAddr` compares raw address values and
  ignores the width they are expressed at, so a group shifted to a different width carried values
  across between addresses that are not the same location. 16-bit `0177570` and 22-bit
  `017777570` are the same register; the raw numbers are not close.
- Two defects found on the way: `ToolWindow` ran its subscribe hook only on the *first* show
  while unsubscribing on every hide, so any window that was closed and reopened came back
  showing nothing (the Log window had this); and `SerialTransport.availablePortNames()` now
  answers an empty list instead of failing when jSerialComm cannot load its native library —
  a settings dialog that will not open because there are no serial ports is worse than one
  offering none.
- A `JTable` hands its header to the enclosing scroll pane from `addNotify()`, which never runs
  without a display — so the offscreen renders, the ones a person actually looks at, had no
  column headings. Both tables wire theirs up explicitly.

### Phase 5 part 1 — the shell that connects

- **It runs.** `java -jar pdp11gui.jar` opens a window with a terminal, a connection status bar
  and a Windows menu, and connects to a simulated machine of any of the seven console protocols
  from a menu item, with nothing installed.
- `AppContext` first, as PLAN.md §5 insists: every window is handed the services it needs and
  there is no static instance to reach for. Doing this before any window exists is cheap; doing
  it afterwards is a rewrite of every window.
- `to.etc.pdp11.core.conn`: `ConsoleProtocol` × `TransportKind` replaces the 24 flat combo
  entries in `FormSettingsU` — and the *second* cross product nobody had counted, `TConsoleType`
  listing every console twice, once real and once `consoleSelftest*`. Making the simulated
  machine a **transport** collapses both: seven protocols, four transports, and the fakes cost
  one entry rather than seven. `ConnectionManager` turns a profile into a live console;
  `ConnectionManagerTest` drives every protocol against its own simulated machine, end to end.
- `TerminalProfile` on the `Console` interface, applied as a pre-filter in front of the terminal
  rather than as emulator configuration — because ODT means its LF and the 11/44's console means
  a lone CR, and a conforming VT100 fed the latter overwrites every line with the next.
  **Divergence:** SimH sets both flags and sends CR LF together, which the Pascal double-spaces;
  a pair is one line ending here.
- Settings as versioned JSON under the platform config dir (Gson: one jar, no transitives).
  Atomic writes, a newer-schema file left alone rather than truncated, and nothing about a bad
  settings file can stop the application starting.
- `WindowKey`/`ToolWindow`/`WindowManager`: typed keys and lazy creation replacing creation of
  all ~26 windows at startup and lookup by caption. Geometry is per key in screen coordinates,
  and **is clamped back onto a screen that exists** — which the Pascal does not do, and without
  which unplugging a monitor loses a window for good.
- `TerminalView` with a `GlassTerminalView` behind it — the fallback PLAN.md §3 describes.
  JediTerm stays deferred; it is the riskiest dependency in the stack and these consoles are dumb
  TTYs.
- The Log window keeps the Pascal's one-column-per-channel layout, which is what makes a console
  conversation readable, with the byte-level channels off unless asked for.
- **The phase-3 warning is discharged:** something now reads SimH's console channel.
  `ConnectionManager` drains it and keeps the last 256 KB, so the SimH Console window in phase 6
  finds a transcript rather than a bug.
- **Layout is tested with no display at all.** The windows are thin frames around a `JPanel`
  (`MainPanel`, `LogPanel`), because a panel can be sized, laid out and painted into an image
  headlessly and a `JFrame` cannot. `UiRenderer` does that; the layout assertions run on CI, and
  `target/ui-render/*.png` is written on every build for the part that needs eyes. It found a
  six-pixel seam between the terminal and the status bar (`insets 0` does not imply `gap 0` in
  MigLayout) and a deadlock in the harness itself — laying out off the event thread while the
  terminal appends on it takes the AWT tree lock and the document lock in opposite orders.
- One bug caught by writing the test first: `SettingsStore.getLastProblem()` answered "nothing"
  before anything had been loaded, and its only caller asks on the way up — so an unreadable
  settings file would have been silently ignored. It loads first now.

### Phase 4 — console layer (SimH first)

- `to.etc.pdp11.core.console`: the threading model of PLAN.md §1, made real.
  `ConsoleConnection` owns one **reader thread** (blocks on the transport, masks each byte to
  7 bits, forwards a copy to the terminal, feeds the decoder) and one **command thread** (a
  single-threaded executor every `Console` call runs on). That executor *is* the Pascal's
  critical section: `BeginCriticalSection`/`EndCriticalSection` and their nesting counter are
  gone, as are the 100 ms monitor timer and the `ProcessMessages` between decoded phrases.
  `call()` refuses to be called from the command thread rather than deadlocking there — which
  is the trap the Pascal walks into when `SilentHaltTimerTimer` issues a console command from
  a timer callback.
- `AnswerPhrase` is a sealed hierarchy (`Prompt`, `Halt`, `ExamineResult`, `OtherLine`) in
  place of a class whose fields are valid only for certain values of a discriminator, and
  `ConsoleScanner` is the restartable lexer, generic over each console's own symbol enum.
- `SimhConsole`: examine, deposit, bulk examine with address-range batching, reset, run, halt,
  single step, and the event-driven `CpuState` tracking PLAN.md said to preserve.
- **The prompt is not a synchronisation point, and the echo is.** SimH prints its prompt
  *before* echoing the command it is about to run, so "send, then wait for `sim>`" can be
  satisfied by the *previous* command's prompt. That is what made phase 3's attempts pass and
  then fail on the next run. `sendCommand` waits for SimH's echo of the command — the one
  thing in the stream that cannot predate the command — and reads the prompt, the replies and
  the rejection check strictly after it. `SimhConsoleIT` now drives examine, deposit, run,
  halt and single step against a real SimH, repeatedly, without drifting out of step.
- Two follow-ups to that, both found by running the suite repeatedly rather than by reading:
  `resync` now repeats the `^E` up to four times rather than assuming a connected socket means
  a listening console, and the echo is matched on the *end* of a line rather than on the whole
  of it - "Simulator Running..." has no line ending, so a command sent while it is still
  unconsumed comes back glued to it and an equality match loses the anchor entirely. The second
  of those was a real five-second intermittent failure and now has a test of its own.
- **Known: SimH occasionally does not come up.** About one launch in thirty accepts both telnet
  connections, sends its banner and then says nothing at all - it never enters master mode, and
  no amount of `^E` rescues it. The console layer already does the right thing (it reports a
  console that will not answer), and `SimhConsoleIT` now launches once more when it happens,
  printing the first attempt's evidence either way. PLAN.md phase 4 records what has been ruled
  out. CI does not run this test.
- `FakeSimh`, which has no Pascal counterpart: the Pascal tests its SimH console against SimH
  itself, and CI has no SimH. It reproduces the three properties the protocol layer has to
  cope with — nothing works before `^E`, every command is echoed back, and the prompt arrives
  before the echo — so `SimhConsoleTest` covers the same ground everywhere the build runs.
- Two divergences worth knowing. `examine` returns a `CellValue`, not an `int`: PLAN.md §1
  sketched the sentinel and PLAN.md §2 had already decided it does not survive the port, and a
  UNIBUS timeout is exactly what it was used for. And a bulk deposit now *skips* a cell whose
  edit value was never set, where the Pascal sends its `$ffffffff` sentinel to the machine as a
  value and deposits `177777` into whatever it names.
- Terminal input is queued on the command thread instead of being dropped whenever the console
  is busy, which is what `TSerialIoHub.DataFromTerminal` does.
- `OdtConsole` and `OdtScanner`: the microcode ODT console, in 16, 18 and 22-bit form, driven
  against the phase-3 `FakePdp11Odt` for both dialects. This is the console that talks to real
  hardware, and it is parsed symbol by symbol rather than by lines, because ODT is a terminal:
  everything sent is echoed and the reply is glued to the echo, so `1000/` comes back as
  `1000/000000 ` with the first five characters our own. `OdtDialect` replaces the two
  independent `isK1630` / `GobbleExtraSpaceAfterPrompt` booleans threaded through the Pascal
  scanner, which the Pascal's own comment says have to agree and nothing enforces.
- A deposit at ODT is a conversation, not a command: send `addr/`, wait for the location to
  open and print its contents, then send the value. `haltCpu` explains that ODT cannot stop a
  running machine — it is the CPU's own microcode, so nobody is listening — where the Pascal
  leaves the method abstract and raises an abstract-method error.
- **One decode-loop fix.** A pass that consumes input without recognising a phrase now reports
  progress instead of stopping. The K1630 prefixes a halt report with `ESC S`, which matches no
  rule; the Pascal returns "nothing recognised" there and looks no further until the next byte
  arrives — and if that was the last of the reply, never.

- `Pdp1144Console` and `Pdp1144Firmware`: the PDP-11/44's console processor, both firmwares,
  driven against both fakes. This is the console that reads a block per command - `E/N:100
  <addr>` returns sixty-four words in one round trip - and the one where a stop report and an
  examine answer are the same line: `17777707 000114` is both "stopped at 000114" and what
  `E/G 7` answers, so it becomes two phrases, halt first, because the prompt after them looks
  two back for the halt. `17777707` only counts at the start of a line, or every examine of the
  PC would report a halt.
- **A stop event could be lost.** The task posted to the command thread re-read the pending PC
  from a field, and a second prompt with no halt in front of it clears that field first. The
  address is now captured when the task is created - a fix in the shared base, so it applies to
  all three consoles.
- The 11/44 fakes gained `H`, `N` and `C`, which the Pascal's never grew even though the shipped
  driver sends all three; and the V3.40C fake's `^P` now reports where it stopped, without which
  the driver's halt has nothing to report. Both are marked in the source as inferred from the
  driver rather than observed. A gate of our own that made the classic fake ignore the console
  while a program runs has been removed: the Pascal has no such gate, and it is the V3.40C
  firmware that stops listening.

### Phase 3 — transports and fakes

- `to.etc.pdp11.core.io`: `PhysicalTransport`, the boundary the whole test strategy rests on,
  with four implementations - `FakeTransport` (a simulated PDP-11 in this JVM),
  `SerialTransport` (jSerialComm), `TelnetTransport` (socket plus IAC state machine, replacing
  `OverbyteIcsTnCnx.pas`) and `SimhProcessTransport` (launches SimH and drives its remote
  console). Reads block, so one reader thread per connection replaces the Pascal's 10 ms poll
  timer, its 20 ms telnet poll, and the `Application.ProcessMessages` call inside
  `Physical_ReadByte` itself.
- `to.etc.pdp11.core.fake`: `FakePdp11` and `FakePdp11Odt`, the latter carrying both the DEC
  and Robotron K1630 dialects. The ODT state transition table and the behaviour Steve Maddison
  measured on a real PDP-11/23 in 2008 are carried over verbatim as Javadoc, with one test per
  case - odd-address rejection, nonexistent memory reading back as zero, echo-then-`?`, and
  the missing `@` on auto-advanced lines.
- `Scheduler` in `to.etc.pdp11.core.util`, so the fakes' simulated run-to-halt can be driven
  deterministically in tests instead of waiting out a randomly chosen one to five seconds.
- **SimH's remote console stays mute until its console channel is connected too.** With only
  the remote channel open it sends a banner and then never answers anything. Beyond that,
  `^E` is what produces a `sim>` prompt at all. `SimhProcessTransport` opens both channels and
  drains SimH's stdout for diagnosis; see the note on that class and PLAN.md phase 3 for the
  full handshake and for what phase 4's scanner has to expect.
- `SimhProcessTransportIT` launches a real SimH and asserts what phase 3 owns: the port probe,
  both channels connecting, IAC stripping and the banner. Commands are phase 4's business and
  are covered by `SimhConsoleIT`. Both skip when SimH is not on `PATH`, which is the case on
  CI.
- `FakePdp1144` and `FakePdp1144V340c`: the 11/44's separate console processor, with its
  line-oriented command language (`E`/`D`/`S`/`I`, stacked `/G` and `/N:count` modifiers) and
  its daft RUBOUT, which goes on echoing the last character it deleted after there is nothing
  left to delete. The V3.40C firmware is a subclass rather than the second copy of the unit the
  Pascal keeps: what differs is console/program mode, backspace, worded errors, an address space
  class on every examine, and masked rather than rejected addresses.
- `FakePdp11M9312` and `FakePdp11M9301`: the boot ROM console emulators, the feeblest consoles
  supported and the only ones where a mistake stops the machine - an odd address or a bus
  timeout halts the emulator itself, and only ESC (standing in for a front-panel control-boot)
  gets it back. They validate input as it is typed, which is why `DL` is a boot command and not
  a malformed deposit. The M9301 differs from the M9312 by a `$` prompt with a NUL after it.
- **`FakePDP11ODTK1630U` needed no port**: it is an 18-bit ODT with one flag set, and making the
  dialect a constructor argument in phase 3 had already absorbed it.
- Three Pascal bugs found and not reproduced, all recorded in PLAN.md phase 3: an error path in
  the 11/44 fake that replaces the output buffer instead of appending to it, a missing `Exit`
  that lets the M9312 start a program at an address it has just refused, and an off-by-one that
  leaves R7 reachable where R0..R6 are not - that one kept deliberately.

### Phase 2 — model

- `to.etc.pdp11.core.mem`: `CellValue` and `AddressRange` replace the `$ffffffff` sentinel with
  types where the unknown state cannot be compared at all; `MemoryCell`, `MemoryCellGroup`,
  `MemoryCellGroups` with a real listener list per group (the Pascal has a single delegate, so
  a second subscriber silently unsubscribed the first) and an address index across all groups
  in place of the O(groups x cells) scan on every word.
  - The index is keyed on the address normalised to 22 bits, so the MMU's 22-bit group and a
    description's 16-bit groups recognise each other's registers.
  - All three propagation guards are preserved and tested: the per-group `pdpOverwritesEdit`
    opt-out, self-exclusion, and the value-equality short-circuit that terminates propagation.
    A depth guard sits behind them, since the Pascal has no recursion guard at all.
- `to.etc.pdp11.core.machine`: `M4Preprocessor` reimplements the m4 subset the machine
  descriptions use and **reproduces GNU m4 1.4.21 byte for byte** over the shipped
  description. This replaces a shell-out to `m4.bat` — a Windows batch file, which is why
  bitfields, the I/O page scanner and the register-group windows have never worked on Linux —
  along with its temp file, 5 second timeout and two `ProcessMessages` spin loops.
  `IniFile` and `MachineDescription` load the result: 17 device groups, 62 bitfield
  definitions and 233 cells from `pdp11.ini`, with no warnings.
- `to.etc.pdp11.core.mmu`: `Pdp11Mmu`, with four bugs in the Pascal corrected — a displacement
  mask of `$1777` that is not a contiguous field, a page-length check off by one block (a legal
  one-block page rejected every address in it), downward-expanding pages raising instead of
  translating, and four of twelve register-dispatch branches copy-pasted so that kernel
  instruction PAR/PDR wrote the user arrays and three PDR sets could never be written at all.
- Two validation rules added on the way in turned out to be wrong about the real data and were
  removed: overlapping bitfields and several register names at one address are both
  deliberate. See `machines/README.md`.

### Phase 1 — pure core

- `to.etc.pdp11.core.addr`: `MemoryAddressType` and an immutable `Address` record.
  `withWidth` carries the I/O page rebasing rule — addresses below the I/O page are
  width-invariant, addresses inside it are rebased because the page sits at the top of memory
  and the top moves — and refuses to narrow an address that would not fit rather than
  truncating it. Round-trip tested across every pair of widths over the whole 8 KB I/O page.
- `to.etc.pdp11.core.bits`: `BitfieldDef`, `BitfieldsDef`, `BitfieldsDefs`. Address lookup is
  keyed on the canonical 16-bit form instead of the Pascal's linear scan that rewrote stored
  addresses in place, so a definition linked under one machine is still found under a machine
  of a different width. Overlapping and duplicate fields are now rejected.
- `to.etc.pdp11.core.disas`: `Disassembler`, `DecodedInstruction`, `MemoryImage`. The
  `PAnsiChar` pair the Pascal takes existed only for the retired `PDP11DISAS.DLL` ABI and is
  gone.
- `to.etc.pdp11.core.util`: `Logger`/`LogChannel` (channels, not severities — the Log window
  shows one column each, which is what makes a byte-level serial conversation readable),
  `ProgressMonitor`, `OperationCancelledException`, and `Octal.digitsForBits`.
- **Disassembler cross-checked against SimH over all 65536 words**, via
  `tools/gen-disas-corpus.sh`; the corpus is committed so the test needs no SimH. Every
  disagreement was settled by assembling the disputed instruction with `macro11`, an
  independent implementation of the same encoding.
  - Two bugs in the Pascal are **not** reproduced: `SPL` reads its level from bits 8..6 instead
    of 2..0 — and since those bits are part of SPL's own opcode it prints `SPL 2` for every SPL
    — and float operands mask the accumulator field to 2 bits, so `CLRF AC5` prints as
    `CLRF AC1`. `macro11` assembles `CLRF AC5` to `170405`, which settles it.
  - Two bugs in SimH are **not** adopted: `000256` and `000276` each duplicate the previous
    entry in the condition-code group; correct decodes are `CLN CLZ CLV` and `SEN SEZ SEV`.
- `tools/pascal-disas-diff.sh` diffs this disassembler against the Pascal over all 65536 words.
  It reports 183 differing words and no others, word counts included: the 7 `SPL` words and
  176 float-operand words above. It is a hand-run check, not a committed test — a regression
  test pinned to an implementation with known bugs would pin the bugs.
- `tools/gen-disas-corpus.sh` documents how to get SimH's `pdp11` to start when it is linked
  against a `libvdeplug.so.2` that is no longer packaged under that name.

### Phase 0 — scaffolding

- Maven multi-module skeleton: `pdp11-core`, `pdp11-ui`, `pdp11-app`, targeting Java 21.
- Maven wrapper pinning Maven 3.9.11; the enforcer refuses anything below 3.9.
- The `pdp11-core` headlessness rule is enforced at compile time with
  `--limit-modules java.base` and again by an ArchUnit test. Both were verified to fail on a
  deliberately planted Swing reference.
- FlatLaf shell: `Pdp11Gui.main()` opens an empty `MainWindow` with a menu bar.
- GitHub Actions build on Linux, Windows and macOS.
- `Octal` formatting and parsing in `pdp11-core`, with tests.
- Machine descriptions recovered from the PDP11GUI 1.48.6 MSI installer and committed under
  `pdp11-app/src/main/resources/machines`: `pdp11.ini` and the eight `*.modules` m4 libraries
  it includes. These are not in the Pascal repository. See
  `pdp11-app/src/main/resources/machines/README.md`.
