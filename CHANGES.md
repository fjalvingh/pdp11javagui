# Changes

## Unreleased

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
