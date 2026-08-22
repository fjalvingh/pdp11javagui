# Changes

## Unreleased

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
