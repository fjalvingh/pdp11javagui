# Changes

## Unreleased

### Fixes

- **A file loaded into the Memory Loader could be silently replaced by what was already in the
  machine, before it was deposited.** The loader, the dumper and the assembler each turn their
  group's `pdpOverwritesEdit` off permanently in their constructors — that flag is the whole
  reason a loaded file survives another window reading the same addresses. But the shared grid
  reset it on every refresh from "are there uncommitted edits right now", so the refresh after a
  successful *Deposit all* turned it back on, and the next *Load file* had no protection at all:
  any other window examining those addresses propagated the machine's values over the top of the
  file, and Verify afterwards compared the machine against itself and reported agreement. The
  dynamic policy is now opt-in (`MemoryCellGroupTable.OverwritePolicy`), and only the plain
  memory window — a view of the machine that happens to be typeable — asks for it.
- **Scanning the I/O page of a 16- or 18-bit machine threw away everything it found.** The
  scanner examined all 4096 addresses — minutes of it over a serial line — and then stored them
  into a group the window had created at 22 bits, which refuses an address of any other width.
  The result was an `IllegalArgumentException` on the first address stored and nothing to show
  for the scan. `IoPageScanner.scan` now re-expresses the target at the machine's width before
  filling it, which is what the window's "the group's type follows the machine" always assumed.
  Any 16- or 18-bit console hits it — an 11/23's ODT, real or simulated; the existing test missed
  it by building its target at the machine's width instead of at the window's.
- **A connection that dropped left the application saying "Connected".** When the reader thread
  ended — SimH exited, the serial line was unplugged, the socket was reset — `AbstractConsole`
  closed its answer queue and that was the whole of it. Nothing told `ConnectionManager`, so the
  state stayed CONNECTED, the status bar kept saying so, terminal input stayed live, and every
  window went on offering buttons that reached a dead wire and failed one at a time with write
  and timeout errors. `ConsoleConnection` now has a `LostListener` that fires when the reader
  stops for a reason nobody asked for, and the manager tears the connection down and reports
  FAILED with the reason: *"The connection to … was closed at the other end"*. The main window
  prints that in the terminal too, but only when a live connection is what was lost — a failed
  *attempt* already says so on its own.
- **A second Connect could tear down the connection it was replacing and leave the application
  saying it was not connected while it was.** `ConnectionManager.connect` closed, rebuilt and
  published its fields one at a time with nothing serialising two callers, so an attempt that was
  overtaken while it was blocked launching SimH or waiting out a handshake went on to close the
  *newer* attempt's transport and console and to fire FAILED after the newer one had fired
  CONNECTED. What was left was a working command thread behind a manager that said FAILED: the
  MMU window showing "Not connected to a machine", the status bar showing "Connection failed",
  both of them correct about what they were told. An attempt now takes a generation number,
  builds everything into locals and publishes it in one step; one that has been overtaken by then
  closes what it built, removes its own MMU register group by identity rather than every group
  with the MMU usage tag, and changes no state at all. It reports that with
  `ConnectionSupersededException`, which is not a failure and is not shown as one. The main
  window disables Connect, "Connect to simulated" and Disconnect while a connection is being
  made, so the race is not offered in the first place.
- **The MMU window could show "Not connected to a machine" beside a Refresh button that was
  enabled and worked.** Building the two memory maps is 65536 translations, and it happened
  between the line that enables the button and the line that sets the status — so anything thrown
  in there left the window half updated, still showing whatever it had said last, with the stack
  trace on stderr where nobody looks. One page register was enough to throw: a PAF of all ones
  over a full-length page addresses 0140 words past the top of a 22-bit bus, and `Address.of`
  refuses an address that wide. The hardware's adder is as wide as the bus and simply loses the
  carry, so `Pdp11Mmu.translate` now wraps like the machine does (the Pascal computes the same sum
  into a dword and never looks, `Pdp11MmuU.pas:253`). The window builds its maps first and sets
  its widgets afterwards, and a failure it cannot recover from goes into the status line instead
  of leaving the last answer standing.
- **The MMU window no longer shows a map of a machine that has not answered yet.** A console —
  and its MMU — used to exist from the moment `connect` built it, which is before the handshake
  says whether there is a machine there. Opening the window in that gap showed a full memory map
  with the Refresh button greyed out beside it, because the tables asked "is there an MMU" and the
  button asked "are we connected". It is one question now — and since the fix above, a console is
  not published until it has answered, so there is no longer a gap to open the window in.
- **"Read the MMU registers" took eight seconds and never read the PSW.** SimH shows a register
  declared with a `BITFIELD` table decoded — `E PSW` on an 11/70 answers
  `PSW:	000340	CM=K PM=K RS0 FPD0 IPL=7 TBIT0 N0 Z0 V0 C0` — and the decoder's "and there is
  no third word" test — the Pascal's, `ConsolePDP11SimHU.pas:524-525`, which has the same bug
  against a modern SimH — filed that as an ordinary line. The examine
  then waited out its full `CMD_TIMEOUT_MS` for an answer that had already arrived: eight seconds
  of progress dialog, and a PSW left unknown afterwards, so the MMU never learned which mode the
  machine was in. Everything after the value is now ignored. Measured against a real SimH: 8115 ms
  and one unknown cell before, 157 ms and none after.
- **`FakeSimh` prints the PSW's bit-fields too**, which is why no test caught the above — the fake
  answered two neat words that no live machine ever sends.

### Phase 6 part 10 — the Microcode window

- **The PDP-11/44's microcode, one microword at a time**: its 104 bits cut back into the 37
  fields the print set names, each with its value and what that value means — `2 = DATO`, not
  `2` — and the microassembler source line that produced it.
- **The listing is packaged with the application.** DEC's *EY-C3012-RB-001 PDP-11/44 Processor
  Maintenance Supplementary Listings (microcode), April 1981* is a resource in `pdp11-core`, so
  the window works the moment it is opened. The Pascal remembers a file name in the registry and
  opens on "code not loaded" until somebody finds a copy of a 1981 DEC document and puts it in
  the data directory (`FormMicroCodeU.pas:88-110`). Load is still there, for another scan or
  another revision, and what it loads is remembered.
- **Reading and decoding it is `Pdp1144Microcode` in `pdp11-core`**, with the whole listing as
  its test fixture: 1018 microwords, and 22 tests over them.
- **The listing cross-checks itself, and that is now a test rather than a startup exception.**
  Each line prints its address twice, and each microword's next-address *bits* must agree with
  the `J/<tag>` written in its own source *text* — so a digit misread anywhere in the octal
  shows up as a contradiction instead of as a plausible wrong answer. All 1018 pass.
- **A damaged listing costs its own line, not the microcode.** The Pascal raises on the first
  thing it dislikes and abandons the load; here every complaint is a `Problem` with its file and
  line, the rest of the listing loads, and the status line says how many there were. This is what
  a document that mostly arrives as somebody's scan needs.
- **A comment line whose `;` was scanned as `:`** — line 1020 of the shipped listing — is not a
  comment by the usual test and would be joined onto the microword above it, corrupting that
  microword's source text. Its line number is what still says it is a line of the listing rather
  than a continuation. Found by running the cross-checks over the real listing.
- **You can go back.** Next follows the fall-through, as it does there; a microword also lists
  what falls through to *it*, and Back returns the way you came — microcode is mostly read
  backwards from the state you ended up in. Fall-through only, and the row says so: a branch
  target is chosen by hardware substituting bits into the next address, which no listing spells
  out.
- **A search that finds nothing says so and changes nothing.** The Pascal reads an address it
  cannot parse as `0` and jumps to the first microword instead (`FormMicroCodeU.pas:369`), which
  looks like the window ignoring what was typed.
- **The next-address row is not highlighted.** Highlighting means "this microword sets this", and
  the next address has no resting value to differ from — the Pascal compares it against `-1` and
  so paints it yellow in every microword, where a row that is always yellow says nothing.
- **Sorted views are computed once, three of them.** The Pascal re-sorts one shared list every
  time the window's "search by" changes, so the model's order is a property of the UI; and its
  `InstructionByAddr` is a linear scan called once per microword from inside its own verify loop.

### Phase 6 part 9 — the Number Converter

- **One number in octal, decimal, hex and binary at once**, each field editable and the rest
  following. The window needs no machine — it is a desk tool for the moment a listing says
  `012737` and the manual says `0x15DF`.
- **The conversions are `NumberConverter` in `pdp11-core`**: digit validity, parsing against a
  width, padded and unpadded formatting, binary grouping and the signed reading. All of it is
  loose functions inside a Delphi form in the original, where the only way to check that the
  binary groups line up with the octal digits above them is to type into the window and count
  columns.
- **A width, rather than always 32 bits.** The original's value is a `Dword`, so every word comes
  with sixteen leading zeros. This defaults to 16 — a PDP-11 word — with 8, 18, 22 and 32 in the
  selector. Narrowing truncates and says so.
- **Binary is grouped for its own base**: threes under the octal field, so the columns line up
  with the six digits above them, and fours under hex. A width that is not a multiple leaves the
  leftmost group short, which is exactly right: the leading `1` of `177777` really is one bit.
- **A signed reading**, which the original does not show. `177777` is `-1`, and having to work
  that out by hand is what this window exists to stop.
- **Overflow refuses the keystroke** rather than deleting the number's leading digit, which is
  what the original does (`:312-313`) — so there, typing one digit too many silently turns
  `177777` into `777777`.
- **Pasting works.** The original filters key presses and has a `stripInvalidDigits` for the paste
  case that it never calls — both call sites are commented out — so pasting `1,234` into its
  decimal field raises out of `StrToInt64`. Here a `DocumentFilter` sees typing, pasting and
  drops alike.

### Phase 6 part 8 — the MMU window

- **See what memory management is actually doing**: the 64 KB of virtual address space as a list
  of blocks, each saying where it really is, for instruction space and data space, in whichever
  CPU mode you pick.
- **The walk over address space is `MmuMemoryMap` in `pdp11-core`**, not in the window. The
  Pascal computes it inside a grid-filling procedure nested in `UpdateDisplay`
  (`FormMmuU.pas:84-160`), so which addresses run together as one block was never checked except
  by looking at it. Eleven tests now say what a relocated page, two consecutive pages, a
  one-block page and a downward-expanding stack page each look like.
- **Any mode, not only the current one.** The original shows `MMU.curCpuMode`, so there is no way
  to look at the user map while the machine is stopped in the kernel — which is exactly when you
  want it. The selector starts on the mode the machine is in and says so when you move it.
- **A page length error is named.** The Pascal prints "not assigned" for both ways translation can
  fail, and a page length error — what the unused end of a stack page looks like — is not that.
- **Refresh is two steps and the second is not optional.** `ExamineMMU` (`Pdp11MmuU.pas:365-370`)
  examines the registers and then re-evaluates them, because cell propagation excludes the cell it
  started from: examining the MMU's *own* register group never reaches the MMU's own listener.
- **Two bugs found on the way.** The MMU's register group was created per console and never
  removed, so every reconnect left another 99 cells on the propagation index; and a simulated
  machine was given its I/O page *before* the console built those cells, so it answered nothing
  at any MMU register — the window's Refresh did nothing at all against a fake.

### Phase 6 part 7 — the SimH Console, and what the main terminal shows

- **The main terminal is the machine's console now, whatever the machine is.** It was the
  *transport*, which on a real PDP-11 is the same thing — ODT and the 11/44 firmware answer on
  the machine's serial line, and PDP11GUI drives that same line — but on SimH it meant the
  terminal showed `sim>` commands and their replies, which is not a console any PDP-11 ever had.
  A SimH connection's terminal now shows SimH's console channel: boot messages, the operating
  system, program output, and what you type goes to the machine. This is a deliberate divergence
  from the Pascal, where `SerialIoHubU.Physical_Poll` feeds the terminal from the physical
  channel whatever it is.
- **`ConnectionManager` owns both channels** as `TextChannel`s — one for the machine console, one
  for the console protocol — and says which is which (`hasMachineConsole`,
  `hasSeparateMachineConsole`). `TextChannel.subscribe` hands over the backlog and starts the
  live stream under one lock, so a window opened after something happened still shows it and
  misses nothing in between. The 256 KB drain of SimH's console channel that phase 5 left
  waiting for a window is now that window's source.
- **One SimH window where the Pascal has two.** `FormSimhConsoleU` showed the emulated machine's
  console, which is the main terminal here, and `FormSimhRemoteLogU` showed a transcript of the
  `sim>` protocol — so what is left is the transcript, and it has a command line on it.
- **It is interactive**, which the Pascal refused: its comment says the `sim>` parser "was built
  and tested only against a clean administrative channel". `SimhConsole.command()` goes through
  the same echo-anchored `sendCommand` as every other command, so a typed command is serialized
  on the command thread and lands *between* the console layer's own commands rather than in the
  middle of one. Command history on the arrows, a Halt (^E) button — a command that starts the
  machine never returns to a prompt, so there would otherwise be nowhere to type the one that
  stops it — and neither a rejected command nor a missing prompt is treated as a failure, because
  both are ordinary things to type.
- **It opens with a SimH connection**, since after this change nothing else on screen shows that a
  simulator is being driven at all.
- **SimH we did not launch has no machine console at all** — the simulated machine, and telnet to
  somebody else's SimH — and the terminal says so rather than quietly showing `sim>` traffic
  instead. Typing there does nothing, deliberately: the only wire is the `sim>` channel, and a
  keystroke on that lands in the middle of whatever the console layer is saying.
- `ConnectionManagerSimhIT` proves the split against real SimH both ways: a program that prints a
  character arrives on the machine console and not on the protocol channel, and a keystroke sent
  the way the terminal sends one is read by a program running on the emulated machine.

### Phase 6 part 6 — the Assembler

- **Write a MACRO-11 program, assemble it, and load it into the machine**, in one window with
  Source, Listing and Code tabs — merging `FormMacro11Source`, `FormMacro11Listing` and
  `FormMacro11Code`, which are always about the same program and were always opened together.
  The editor is RSyntaxTextArea with a hand-written MACRO-11 highlighter.
- **`Macro11` and `Macro11ListingParser` are in `pdp11-core`**: the first runs `macro11` as a
  child process (two runs, the hex listing allowed to fail, five-second timeout); the second
  turns the listing's code column into memory cells. Both are tested headlessly, and `Macro11IT`
  runs the real assembler when it is installed and skips when it is not.
- **The assembler's exit code says nothing.** `macro11` reports success for a source full of
  errors, so errors are found by parsing the listing. An unresolved global symbol — `000000G` in
  the code column — is not an error to MACRO-11 at all, prints no diagnostic, and leaves a hole
  where an address should be; it is reported here.
- **Two bugs in the original's listing parser, not carried across.** An unrecognised value suffix
  raises, which abandons the parse and throws away every word already read — here it costs one
  word and is reported. And a byte at an odd address is ORed into the `$ffffffff` sentinel when
  the word below it is unset, turning a single byte into `177777`.
- **"New program: compile, load and reset" on the Execution window**, which the Pascal implements
  by pressing two other windows' buttons. The program is now `AppContext.getAssembler()` — state,
  like `MachineState` — so it assembles, deposits and resets with no assembler window open.
- **The error and PC markers are separate.** The Lazarus port can mark only one line, because its
  replacement for JvEditor's line styles is a single field; an error marks every listing line its
  source line produced, and the PC marks the line the machine stopped on.
- Behaviour dropped deliberately, each a workaround for something gone: the editor clearing itself
  when the window is hidden, the detab-on-load/entab-on-save round trip (the original's own
  comment: "unnecessary, and broken?"), and the modal dialog on every syntax error.

### Phase 6 part 5 — the Memory Loader

- **Read a program out of a file and put it into the machine**, in the same four formats the
  dumper writes. Loading fills the grid and touches nothing else; depositing is a separate button
  and a separate decision, and Verify reads the machine back *beside* what was loaded so the
  disagreements colour themselves.
- **A second bug in the Pascal's split-byte class, and the round trip is what found it.** Its
  `Load` takes `stream_l.Size div 2` words from a file holding one byte per word, so it reads half
  of them — and its `Save`, fixed in the previous commit, wrote an all-zero high byte file. Each
  bug hides the other: load what that save wrote and you get half a program of low bytes, which
  looks like a corrupt file rather than like two bugs.
- A loaded word is an **edit value** with the machine value unknown, which is what makes every
  word show as changed until it has been deposited.
- `MachineState.setStartPc` replaces the last reach-in in this group: a paper tape image knows
  where its program starts, and the execution window shows it without either window knowing the
  other exists.
- The "Load at" field appears only for the two formats that do not carry their own addresses.

### Phase 6 part 4 — the Memory Dumper

- **Read a range off a machine and write it to a file**, in four formats: a binary byte stream,
  separate low-byte and high-byte files (for programming a 16-bit memory built from 8-bit chips),
  a readable text listing, and DEC Standard Absolute Paper Tape — the thing a real PDP-11's
  absolute loader reads, blocks and checksums and all. `MemoryDumper` and `MemoryFileFormat` are
  in `pdp11-core` and are shared with the Memory Loader when it arrives.
- **A bug in the original, not carried across.** The split-byte format writes `byte_h := w shl 8`
  where every other line in the unit shifts right, so the high byte file it produces is all
  zeros. Its own `Load` gets it right. Nobody notices until a ROM does not work.
- **A word that was never read is counted, not silently invented.** The Pascal writes its
  `$ffffffff` sentinel truncated to `0177777` — a real value, quietly, in the middle of a dump.
  Here the positional formats write zero and report how many, and the window says so; the text and
  paper tape formats leave them out, which they can because both carry their addresses.
- The file name rows are built from the format's own list of what it needs, rather than five
  loader objects sharing one set of widgets that get shown and hidden. Writing needs cells, not a
  connection: a dump read earlier can still be written after the machine has gone.

### Phase 6 part 3 — the Memory Test

- **Four memory diagnostics**, ported from `FormMemoryTestU` into `MemoryTester` in `pdp11-core`:
  data lines (moving one and moving zero, combined across chips so a dead chip cannot masquerade
  as a dead line), address lines (write each address into itself, in two phases so a *short*
  between lines shows up as well as a break), data bits (a moving-one pattern in every chip,
  which is what finds a dead chip), and random.
- **The simulated machines can be broken on purpose.** `FakePdp11.setStuckDataLines` and
  `setDeadAddressLine` mean every diagnosis is now checked against the fault it exists to find,
  rather than asserted in a comment. The original's author did the same thing by hand — two
  commented-out lines in `TestSingleBit` tie bit 8 high and bit 15 low.
- The window is a range, a chip size, four buttons and a log — no grid, because the Pascal's grid
  is created and never shown. Log lines appear as the test runs, so a long test can be watched
  rather than only reported on, and it can be cancelled from the progress dialog.
- Test patterns are written and not put back: these are for a machine that is not running
  anything, which is the assumption the original makes too. A range is clamped to stop at the I/O
  page, because device registers are not memory and writing patterns into them would *do*
  something.

### Phase 6 part 2 — Bitfields and the I/O Page Scanner

- **Bitfields**: one register broken into its named bits, editable from either side — type the
  word and every field follows, type a field and the word follows. Setting the PSW's priority to
  7 no longer means working out that it is `0340`. A field too wide for its own bits is refused
  rather than corrupting its neighbours.
- **I/O Page Scanner**: reads all 4096 words of the I/O page, keeps the addresses that answer,
  names them from the loaded machine description, and writes an `.ini` section for everything it
  cannot name — which is how you describe hardware nobody has documented. The scan is
  `IoPageScanner` in `pdp11-core`, tested end to end against a simulated machine whose I/O page
  is built from the description.
- **A bug the scanner found in the SimH console.** `examine` threw for `017777710..717` — the
  second register set SimH does not model — so a scan died eight addresses in. That address is
  indistinguishable from one that times out on the bus, which the same console already reports as
  "unknown", so it now answers `CellValue.UNKNOWN`. `deposit` still throws, because a write that
  cannot happen must be reported.
- `CellSelection` replaces `FormMain.SyncBitfieldForm`: a view announces which cell is selected
  and the Bitfields window subscribes, rather than every grid knowing the main form and the main
  form knowing the Bitfields window.
- **Preferred table column widths do not survive layout** — a `JTable` with an auto-resize mode
  redistributes them on the first layout pass and keeps the result. Both list views set minimum
  widths, which is the floor the redistribution respects. Caught by looking at a render.

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
