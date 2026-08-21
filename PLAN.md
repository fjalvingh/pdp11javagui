# PDP11GUI: Java/Swing rewrite with free-floating windows

## Context

`/home/jal/git/grb/pdp11gui` is an IDE for real and simulated PDP-11 computers, originally
Delphi/VCL for Windows, recently ported to Lazarus/Free Pascal so it builds on Linux. The
Lazarus port works, but it carries a heavy tax:

- **The MDI model is fighting the toolkit.** Hiding a tool window is implemented by flipping
  `FormStyle` between `fsMDIChild` and `fsNormal` (`Pdp11gui/FormChildU.pas:92-98`), which
  destroys and recreates the native handle. That one choice is the root cause of a Qt5
  segfault workaround (`FormMainU.pas:1037-1056`), an `OnShow`-suppression hack
  (`FormChildU.pas:95-97`), and editor-content clearing on hide
  (`FormMacro11SourceU.pas:187-192`, `FormMacro11ListingU.pas:199-204`). All four vanish
  with free-floating top-level windows.
- **HiDPI needs environment hacks.** `run.sh` computes `QT_SCALE_FACTOR` and pins
  `QT_FONT_DPI=96` around a Qt5/X11 double-scaling bug in popup menus.
- **~69k lines of absolute-positioned `.dfm`** that do not survive font or DPI changes.
- **Single-threaded `Application.ProcessMessages` I/O.** ~40 calls across 21 files, with
  three hand-rolled counters standing in for real concurrency control.
- **Windows-era assumptions** persist: registry-shaped settings, hardcoded `\` separators
  (`MediaImageDevicesU.pas:370-488` ×9, `FormMainU.pas:511-513`, and others).

**Goal:** rewrite in Java/Swing as a cross-platform application with free-floating top-level
windows instead of MDI, eventually replacing the Lazarus version — which stays as the
reference implementation throughout the port.

**Target:** `/home/jal/git/grb/java11gui`.

## Progress

| Phase | State | Notes |
|---|---|---|
| 0 — Scaffolding | **Done** | 3 Maven modules, wrapper on 3.9.11, FlatLaf shell, CI on all three platforms. Machine `.ini` files recovered from the MSI; the m4 subset they use is measured and tiny. |
| 1 — Pure core | **Done** | `Address`/`MemoryAddressType`, `BitfieldDef*`, `Disassembler`, `Logger`, `ProgressMonitor`, `Octal`. 47 tests. Disassembler agrees with SimH on all 65536 words bar two documented SimH bugs, and with the Pascal on all but 183 words, all of them Pascal bugs. |
| 2 — Model | Next | `MemoryCell*` + listener bus, `Pdp11Mmu`, machine-description parsing and the m4 replacement. |
| 3 — Transports and fakes | | |
| 4 — Console layer | | The keystone. Budget it generously. |
| 5 — First usable app | | |
| 6 — Assembler and tools | | |
| 7 — Disc images | | |
| 8 — Packaging | | |

Each phase's entry below carries its own "done when" and, once finished, what it actually
found. Keep both current: the findings are the part that changes what the *next* phase should
do, and they are worth more than the tick.

## Decisions taken

| Question | Decision |
|---|---|
| Toolkit | **Swing** |
| Window model | Free-floating top-level `JFrame`s, no MDI |
| Main window | Hosts the terminal + connection status + menu bar |
| Layout management | Auto-restore each window's geometry; no named layouts, no tiling |
| Platforms | Linux, Windows, macOS |
| UI fidelity | Redesign the UX |
| Terminal | Real VT100/ANSI emulation |
| Assembler UI | One window, Source / Listing / Code as tabs |
| Memory views | Unlimited, opened on demand (replaces fixed Mem1–Mem4) |
| PDP-11/70 panel | **Dropped** |
| MACRO-11 assembler | External binary, required on `PATH` |
| m4 preprocessor | Replaced by Java templating |
| Parity testing | Port the `Fake*` consoles, test against them; golden transcripts deferred |
| First console | SimH direct |
| Disc images | Out of scope for first usable release; sequenced last |
| Packaging | Runnable fat jar first; `jpackage` installers later |
| Settings migration | None — start fresh |
| Machine `.ini` files | Extract from the retrocmp.com installer in phase 0, commit to the repo |

## Why Swing rather than SWT

Dropping MDI removes what would have been the strongest argument (Swing has
`JDesktopPane`/`JInternalFrame`; SWT has no MDI at all). The remaining case is narrower but
still clear:

1. **The MACRO-11 editor.** [RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea)
   is a mature Swing editor — a MACRO-11 mode is a JFlex token file, and folding, gutter
   icons, error markers and bracket matching come free. SWT's equivalent is JFace Text,
   painful outside Eclipse.
2. **Three platforms, one artifact.** SWT needs a different native jar per OS/arch selected
   at launch; macOS SWT additionally requires `-XstartOnFirstThread`, constraining the whole
   app. Swing ships in the JDK.
3. **HiDPI.** Swing does fractional scaling via `sun.java2d.uiScale`; SWT-GTK inherits GTK's
   integer-only scaling — the same class of problem already fought through on Qt5.
4. **No manual resource disposal.** SWT requires disposing every `Color`, `Font`, `Image`,
   `GC`; across ~25 windows that is a steady source of leaks.

**Stack:** Java 21+ · Swing · [FlatLaf](https://www.formdev.com/flatlaf/) · RSyntaxTextArea ·
MigLayout · [jSerialComm](https://github.com/Fazecast/jSerialComm) ·
[JediTerm](https://github.com/JetBrains/jediterm) · JUnit 5 · Maven (matching
`/home/jal/git/domui`, which is also multi-module).

## Module layout

```
java11gui/
  pdp11-core/   no Swing dependency at all — model, transports, console protocol,
                disassembler, memory loaders, disk images. Unit-tested headlessly.
  pdp11-ui/     Swing windows, window manager, settings binding
  pdp11-app/    main(), packaging, resources (driver *.mac, machines/*.ini)
```

The split is load-bearing. **`maven-enforcer` cannot express it** — Swing and AWT are JDK
packages, not artifacts, so there is no dependency to ban. What does express it is compiling
`pdp11-core` with `--limit-modules java.base`, which makes javac reject the import outright:

```
package javax.swing is not visible
  (package javax.swing is declared in module java.desktop, which is not in the module graph)
```

That is a compile failure in the module itself, which is both earlier and harder than a test.
The ArchUnit test of §6 stays as the belt to those braces: it also covers the test sources,
which cannot carry the flag because ArchUnit and JUnit need more of the JDK than the
production code is allowed to touch. Both were verified to fail on a planted Swing reference
in phase 0. `maven-enforcer` earns its place on the build-environment rules instead —
Maven ≥ 3.9, JDK ≥ 21, `requirePluginVersions`, `banDuplicatePomDependencyVersions`.

If a genuine `java.base` gap turns up later (`java.logging`, `java.xml`, …) add that module to
the flag explicitly and record why. Never add `java.desktop`.

---

## 1. Threading architecture — design this first

This is the one place where a faithful port is the *wrong* port. Today there are **no
threads at all**: a 10 ms poll timer (`SerialIoHubU.pas:329-334`), a 100 ms monitor timer
(`ConsoleGenericU.pas:358-361`), a 20 ms telnet poll (`OverbyteIcsTnCnx.pas:105-108`), and
`Application.ProcessMessages` used as a coroutine yield. The blocking wait is a spin loop
(`ConsoleGenericU.pas:450-469`). Three hand-rolled counters stand in for synchronization:
`Physical_Poll_Disable` (`SerialIoHubU.pas:182`), the `InCriticalSection` nesting counter
(`ConsoleGenericU.pas:392-408`), and the `BusyForm` abort flag.

### Target model

Three threads, with a strict rule: **the console API is never called on the EDT.**

- **Reader thread** (one per connection). Blocks on the transport, feeds bytes to the
  scanner, publishes completed `AnswerPhrase`s to a `BlockingQueue`, and forwards a copy of
  every byte to the terminal.
- **Command thread** (a single-threaded executor). All `Console` calls run here, serialized,
  so `BeginCriticalSection`/`EndCriticalSection` disappears entirely — the executor *is* the
  critical section.
- **EDT.** Owns all widgets. Receives model changes via `SwingUtilities.invokeLater`.

```java
public interface PhysicalTransport extends AutoCloseable {
    int  read(byte[] buf, int off, int len) throws IOException; // blocking, -1 at EOF
    void write(byte[] buf, int off, int len) throws IOException;
    boolean isOpen();
}
// impls: SerialTransport (jSerialComm), TelnetTransport (Socket + IAC state machine),
//        SimhProcessTransport (ProcessBuilder + TelnetTransport), FakeTransport
```

`FakeTransport` is the key to the test strategy: the Pascal fakes already sit exactly at the
transport boundary — `TFakePDP11Generic` exposes only `SerialReadByte`/`SerialWriteByte`
(`FakePDP11GenericU.pas:100-101`), called from `SerialIoHubU.pas:815-818` and `:865-868`.
Everything above runs unchanged. Preserving that boundary means the ported fakes exercise
the real protocol code.

```java
public interface Console {
    EnumSet<ConsoleFeature> features();
    MemoryAddressType physicalAddressType();
    String name();

    void    resync()                        throws ConsoleException;
    int     examine(Address a)              throws ConsoleException;
    void    deposit(Address a, int value)   throws ConsoleException;
    void    examine(MemoryCellGroup g, boolean unknownOnly, ProgressMonitor pm)
                                            throws ConsoleException;
    void    deposit(MemoryCellGroup g, boolean optimize, ProgressMonitor pm)
                                            throws ConsoleException;
    void    resetMachine(Address newPc)     throws ConsoleException;
    void    resetAndStart(Address newPc)    throws ConsoleException;
    void    continueCpu()                   throws ConsoleException;
    Address haltCpu()                       throws ConsoleException;  // was `var newpc_v`
    void    singleStep()                    throws ConsoleException;
}
```

Notes on the signature changes:

- `HaltCpu(var newpc_v)` (`ConsoleGenericU.pas:259`) becomes a return value.
- `TConsoleFeatureSet`, a Pascal set driving button enablement, becomes
  `EnumSet<ConsoleFeature>`.
- **Progress and cancellation replace `BusyForm` and `ProcessMessages`.** Today
  `TConsoleGeneric.Deposit(mcg,…)` drives `BusyForm.Start/StepIt/Aborted/Close` directly
  (`ConsoleGenericU.pas:539-554`). Replace with a `ProgressMonitor` interface in
  `pdp11-core` (`begin(int total)`, `step(int n, String note)`, `boolean isCancelled()`),
  implemented in `pdp11-ui` by a modal progress dialog that appears only after a delay —
  matching today's 1 s threshold (`FormBusyU.pas:113-124`).
- **Modal dialogs must leave the protocol layer.** `CheckPrompt` currently calls
  `FormNoConsolePrompt.ShowModal` (`ConsoleGenericU.pas:480`) then `Abort` to unwind via
  `EAbort`; same at `ConsolePDP11ODTU.pas:362`. In Java, throw `NoConsolePromptException`
  and let the UI decide what to ask. `EAbort`-style silent unwinding becomes
  `OperationCancelledException`.

### Preserving execution-stop ordering

`MonitorTimerCallback` (`ConsoleGenericU.pas:521-525`) deliberately fires `OnExecutionStop`
*outside* any command sequence, so the handler may immediately issue new console commands.
Reproduce this by posting stop-events as tasks onto the **same single-threaded command
executor**. Serialization then gives the ordering guarantee for free — a stop-event can
never interleave with a command, and a handler may enqueue further commands safely.

The other `ProcessMessages`, at `ConsoleGenericU.pas:424` between decoded phrases, is *not*
semantic — it is a UI-responsiveness hack for when one chunk yields many phrases. With a
reader thread the UI is responsive because it is a different thread. Preserving it would mean
deliberately introducing reentrancy into the decoder. Delete it.

**One concrete deadlock trap to get right:** `ConsolePDP11SimHU.SilentHaltTimerTimer`
(`:300-318`) *issues a console command* — `Examine(017777707)` to resolve the PC — from
inside its timer callback, guarded by `InCriticalSection`. In Java this must be a task
submitted to the console executor, **never** an EDT listener callback, or it deadlocks
against the executor it is trying to use.

### Byte-orientation

The protocol layer is byte-oriented, not text — `curbyte := curbyte and $7f`
(`SerialIoHubU.pas:843`). Keep `byte[]`/ISO-8859-1 throughout `pdp11-core` and never let a
default-charset conversion near it.

---

## 2. `pdp11-core` design

```
to.etc.pdp11.core.addr      Address, MemoryAddressType
to.etc.pdp11.core.mem       MemoryCell, MemoryCellGroup, MemoryCellGroups, listeners
to.etc.pdp11.core.bits      BitfieldDef, BitfieldsDef, BitfieldsDefs
to.etc.pdp11.core.mmu       Pdp11Mmu
to.etc.pdp11.core.disas     Disassembler, DecodedInstruction
to.etc.pdp11.core.io        PhysicalTransport + impls, IoHub
to.etc.pdp11.core.console   Console, ConsoleScanner, AnswerPhrase, per-machine impls
to.etc.pdp11.core.fake      ported Fake* simulators
to.etc.pdp11.core.machine   machine-description .ini parsing (+ include/define preprocessing)
to.etc.pdp11.core.media     disk image devices, RLE transfer codec   [last phase]
to.etc.pdp11.core.util      Logger, ProgressMonitor, octal formatting
```

### `Address` — immutable record

```java
public record Address(MemoryAddressType type, long val) {
    public Address withWidth(MemoryAddressType newType) { … }
    public String toOctal() { … }
    public static Address parseOctal(String s, MemoryAddressType t) { … }
}
```

- **`long`, not `int`.** Addresses are 22-bit today, but the sentinel discussion below makes
  unsigned-safety worth buying outright.
- `tmpval` (`AddressU.pas:58-62`, "another representation of val") does not belong in the
  record — audit its uses and make it a local.
- **`withWidth` carries the one genuinely non-obvious rule** in `AddressU.pas:131-145`:
  addresses below the I/O page are width-invariant; addresses *in* the I/O page are rebased
  by `IopageBase(new) - IopageBase(old)`, so 16-bit `177570` ↔ 22-bit `17777570`. Unit-test
  this directly — it is easy to get subtly wrong and affects every window when the target
  machine changes.
- `assert(newMat > matAnyPhysical)` (`AddressU.pas:134`) makes the enum *ordering*
  semantically load-bearing; Java enums won't give that for free. Add an explicit
  `isConcretePhysical()` and stop relying on ordinal comparison.

### The `MEMORYCELL_ILLEGALVAL` sentinel — replace it

`$ffffffff` (`MemoryCellU.pas:46`) marks "unknown" for *both* addresses and values. As a
signed Java `int` that is `-1`, and every relational comparison against it silently inverts.
This is the highest-risk mechanical hazard in the port. Do not translate the sentinel:

```java
public final class MemoryCell {
    private final Address addr;
    private int  pdpValue;    private boolean pdpValueKnown;
    private int  editValue;   private boolean editValueKnown;
}
```

Better still, make the unknown state **unrepresentable in a comparison** rather than relying
on discipline across 26 windows — a convention of "remember `Integer.compareUnsigned`" will
fail somewhere:

```java
public record CellValue(int raw) {
    public static final CellValue UNKNOWN = new CellValue(0xFFFF_FFFF);
    public static CellValue of(int word) { return new CellValue(word & 0xFFFF); }
    public boolean isKnown() { return raw != 0xFFFF_FFFF; }
    public int word() { if (!isKnown()) throw new IllegalStateException(); return raw; }
}

public record AddressRange(MemoryAddressType type, int lo, int hi, boolean empty) {
    public AddressRange extend(int addrValue);   // no sentinel comparison at all
    public boolean mayContain(int addrValue);    // Integer.compareUnsigned internally
}
```

`AddressRange` matters specifically because `extendAddrRange` (`MemoryCellU.pas:325-340`) does
sentinel-detection *and* ordering in the same `min_addr.val > addrval` comparison, and assigns
`min_addr.val` without setting `.mat`. That is exactly the expression that inverts under
signed `int`.

Also drop `TMemoryCell`'s direct widget back-references (`grid: TStringGrid; grid_r, grid_c`,
`MemoryCellU.pas:74-75`) — a layering violation that cannot follow into `pdp11-core`.

### Notification bus

Today: a **single delegate** `OnMemoryCellChange` (`MemoryCellU.pas:122`), so a second
assignment silently unsubscribes the first, and `FrameMemoryCellGroupListU.pas:318` relies
on assigning `nil` to unsubscribe. `SyncMemoryCells` (`MemoryCellU.pas:793-812`) then
linearly scans every group on every change.

Replace with a real listener list plus an address index:

```java
public class MemoryCellGroups {
    private final Map<Long, List<MemoryCell>> byAddress = new HashMap<>();
    public void addListener(MemoryCellListener l);      // CopyOnWriteArrayList
    public void removeListener(MemoryCellListener l);
    public void syncMemoryCells(MemoryCell source);
}
```

Three semantics must be preserved exactly, or propagation will storm:

1. Per-group `pdpOverwritesEdit` opt-out. Documented at
   `FrameMemoryCellGroupGridU.pas:38-48`: without it, another window's refresh silently
   clobbers edits the user has typed but not yet deposited.
2. Self-exclusion (`mc != source`).
3. **Value-equality short-circuit** — this is what terminates propagation. There is no
   explicit recursion guard, so a listener that writes back a *different* value will recurse.
   Keep the equality check and add a depth guard as a backstop.

Only `pdpValue` propagates; `editValue` never does.

Note `Pdp11MmuU.pas:153` subscribes to this bus and is **not a window** — the MMU recomputes
its own state when anything deposits to PSW or an MMU register. That is business logic on
the notification bus, so the bus belongs in `pdp11-core`, not the UI.

### `AnswerPhrase` — sealed hierarchy

`TConsoleAnswerPhrase` (`ConsoleGenericU.pas:85-100`) is a discriminated union faked with a
plain class: fields valid only for certain `phrasetype` values. In Java:

```java
public sealed interface AnswerPhrase
    permits Prompt, Halt, ExamineResult, OtherLine { }
```

with pattern matching at the use sites. Note `otherline: shortstring` truncates at 255 chars
today and Java will not — a behaviour change, almost certainly harmless, worth knowing.

### `ConsoleScanner`

Port the restartable-lexer pattern as-is (`ConsoleGenericU.pas:108-126`): incremental buffer,
`markParsePosition`/`restoreParsePosition` one-deep backtrack, and two control-flow
exceptions — `ScannerInputIncompleteException` (rewind, wait for bytes) and
`ScannerUnknownExpressionException` (discard, resync). This design is sound and is the
reason the protocol layer tolerates a byte-at-a-time serial link.

Two cleanups while porting:

- Token types are currently untyped `integer` constants, different per console
  (`ConsolePDP11ODTU.pas:125-129`, `CurSymType: integer` at `ConsoleGenericU.pas:112`).
  Make them per-console enums.
- Console variants differ by boolean flags threaded through the scanner — `isK1630`
  (`ConsolePDP11ODTU.pas:76`), `GobbleExtraSpaceAfterPrompt` (`:75, 601, 639, 654`). Replace
  with a `ConsoleDialect` value object.

**Watch the translation of `ConsolePDP11ODTU.pas:579-698`:** a single `with RcvScanner do`
wraps the entire 120-line `DecodeNextAnswerPhrase` body including its `try/except`. Every
bare `NxtSym`, `CurSymTxt`, `CurSymType`, `CleanupInput` inside is a scanner member. It is
mechanical but you cannot miss one. Same hazard, smaller, at `MemoryCellU.pas:315-321`,
where `addr` is the *cell's* field while `min_addr`/`max_addr` in the same expression are the
*group's*.

Also: Pascal strings are 1-based throughout the scanners, and `Copy(s, 2, maxint)` is used as
"drop first char" (`SerialIoHubU.pas:835`, `ConsoleGenericU.pas:323`) — note `Copy` past the
end returns `''` where `substring` throws.

### Logging

`BitFieldU`, `MemoryLoaderU` and `MediaImageDevicesU` each `uses FormMainU` solely for the
four global `Log*` procedures (`FormMainU.pas:314-345`), which call `FormMain.FormLog.*`.
So even the otherwise-clean units are coupled to the main form. Introduce a `Logger`
interface in `pdp11-core.util` in phase 1, before porting any of them; the Log window
becomes one implementation.

---

## 3. UI architecture for free-floating windows

### `ToolWindow` and `WindowManager`

Today all ~26 windows are created eagerly in `TFormMain.FormCreate`
(`FormMainU.pas:349-521`), never destroyed, and identified **by caption** via
`ChildFormByCaption` (`FormMainU.pas:993-1005`) matching `String2ID` on the part before
`" - "`. Replace with typed keys and lazy creation.

```java
public abstract class ToolWindow extends JFrame {
    protected ToolWindow(WindowKey key, AppContext ctx) { … }
    public    WindowKey key();
    protected void onFirstShow()  { }   // replaces OnAfterShow
    protected void onHiding()     { }   // replaces OnBeforeHide
}

public record WindowKey(WindowType type, String instanceId) { }   // instanceId "" if singleton

public class WindowManager {
    public ToolWindow open(WindowKey key);   // create-or-raise
    public void       raise(WindowKey key);  // toFront + deiconify + requestFocus
    public void       closeAll();
    public List<ToolWindow> openWindows();
}
```

`instanceId` covers the two dynamic cases uniformly: unlimited memory views
(`MEMORY_VIEW/"3"`) and the register-group windows the machine description creates
(`REGISTER_GROUP/"MMU"`). The latter already exist — `LoadMachineDescription`
(`FormMainU.pas:608-645`) builds one window plus one menu item per group and frees them in
`UnloadMachineDescription` (`:648-675`) — so dynamic windows are a requirement regardless.

`ToolWindow` is deliberately thin — `TFormChild` only ever provided geometry persistence,
hide-without-destroy and two hooks (`FormChildU.pas:53-113`). With real top-level windows,
hide-without-destroy is just `setVisible(false)` and all the `FormStyle` machinery goes away.

Two latent Pascal bugs not to reproduce: several subclasses declare `constructor Create`
*without* `override` (`FormMemoryTableU.pas:76`, `FormMemoryDumperU.pas:90`,
`FormMemoryTestU.pas:86`, `FormMacro11CodeU.pas:59`), and `TControl.Show`/`Hide` are not
virtual in the LCL so `TFormChild.Show` shadows rather than overrides — the current code
works by luck.

**Behaviour change to accept deliberately:** the assembler windows currently clear their
editor on hide (`FormMacro11SourceU.pas:187-192`, `FormMacro11ListingU.pas:199-204`) purely
because the `FormStyle` flip destroyed the handle, so content is re-read from disk on every
reopen. With real windows, content simply persists. That is better; just be aware it is an
observable difference.

### Geometry persistence

Keys stay per-window but move from MDI-client-relative to **screen coordinates**, keyed by
`WindowKey` rather than form name. Today's `JH_Utilities.pas:1718-1767` stores
`<FormName>.Left/.Top/.Width/.Height/.Visible/.WindowState` and restores size only when
`BorderStyle in [bsSizeable, bsSizeToolWin]` — drop that rule; every window is resizable in
the new UI.

Add what does not exist today: **multi-monitor validation.** On restore, verify the saved
bounds still intersect a `GraphicsEnvironment.getScreenDevices()` bound; if not, clamp onto
the primary display. Without this, unplugging a monitor loses windows off-screen.

Relatedly, three windows currently clamp their height to the MDI client
(`FormMacro11CodeU.pas:110-119`, `FormMemoryDumperU.pas:238-247`,
`FormMemoryLoaderU.pas:258-267`, all `if h > FormMain.ClientHeight-100`). These must clamp to
the window's own `GraphicsConfiguration` bounds instead.

### Windows menu

The MDI commands — Cascade (`FormMainU.pas:942-961`, with a fixed `array[0..100] of TPoint`
that silently overflows), ArrangeIcons (`:937-940`), MinimizeAll (`:963-968`),
MinimizeAllButActive (`:970-976`, whose `ActiveMDIChild` has no nil check at `:986`),
RestoreAll (`:979-988`) — are all discarded. The window-list menu was already removed during
the Linux port.

Replacement: a live list of currently-open windows that raises the chosen one, plus
Show All / Hide All. Built from `WindowManager.openWindows()` on menu-open — which also
retires the **100 ms timer** that exists today (`UpdateGUI`, `FormMainU.pas:1111-1133`) only
to sync menu checkmarks to window visibility.

### Main window

Hosts the JediTerm terminal, a connection status bar (state, port/host, machine type,
reconnect), and the menu bar. Quitting it quits the app.

**`TTerminalSettings` is a prerequisite for VT100 emulation, not something it replaces.**
These consoles are not ANSI devices, and they disagree about line endings: ODT sends CR+LF
(`ConsolePDP11ODTU.pas:312-321` — `Receive_CRisNewline := false`, `LFisNewline := true`)
while the 11/44 console sends a **lone CR** (`ConsolePDP1144U` — `CRisNewline := true`, "LF
ignorieren"). Feed a lone-CR stream to a conforming VT100 emulator and every line overwrites
the previous one. So keep `Console.terminalProfile()` and apply it as a **pre-filter in front
of the emulator** — CR→CRLF translation when the profile says so, stray-LF suppression,
backspace/rubout mapping, tab expansion — rather than as emulator configuration.

**Wrap JediTerm behind a small `TerminalView` interface from day one**, and spike it before
committing in phase 5. It is the riskiest dependency in the stack: maintained primarily as an
IntelliJ component, recent releases are Kotlin (pulling in the Kotlin stdlib), the Swing UI
module has had API churn between versions, and its `TtyConnector` assumes a PTY-shaped
blocking stream. Pin an exact version. The fallback is more attractive than it sounds — the
consoles are dumb TTYs, and full ANSI matters only for programs *running on* the PDP-11, so a
hand-written glass-TTY plus VT100 subset on a `JTextPane` is a bounded ~1.5–2k lines.

Keep the `tosPDP`/`tosUser`/`tosSystem` colouring the current terminal has (via SGR
injection): today the terminal shows the *entire* byte stream including the console's own
automated commands and their replies (`SerialIoHubU.pas:901-902`), which is how you debug a
flaky console. Merging it into the main window must not mean showing less.

On macOS set `apple.laf.useScreenMenuBar=true`; the menu bar then follows the focused window,
which is the platform-correct behaviour for a multi-window app.

### Connection configuration — decompose it

`FormSettingsU.pas` hardcodes **24 flat combo entries** that are really a cross product of
{console protocol} × {transport}: e.g. "Physical PDP-11 ODT 18 bit (11/23) over serial port"
vs "…over telnet", each duplicated. Model this properly as
`ConnectionProfile { ConsoleProtocol protocol; TransportConfig transport; }` with two
independent selectors, plus saved named profiles. This is squarely within the agreed UX
redesign and removes a combinatorial list that grows every time either axis gains a member.

### Windows to build

**Main:** terminal + status + menu.

**Tool windows:** Assembler (Source/Listing/Code tabs — merges three), Memory view
(unlimited), Memory Loader, Memory Dumper, Memory Test, Disassembler, Bitfields, I/O Page
Scanner, MMU, Execution Control, Blinkenlight Execution, Microcode, Number Converter, Log,
SimH Console, SimH Remote Console Log, dynamic register-group windows, and (last phase) Disc
Image.

**Dialogs:** Settings, About, no-console-prompt, progress/busy.

**Reusable components** (today `TFrame`s used by 7 forms — `FormMacro11CodeU`,
`FormMemoryListU`, `FormMemoryLoaderU`, `FormIoPageScannerU`, `FormMemoryDumperU`,
`FormMemoryTableU`, `FormMemoryTestU`): port
`FrameMemoryCellGroupGridU` → `MemoryCellGroupTable` and `FrameMemoryCellGroupListU` →
`MemoryCellGroupList` as `JPanel`s early, in phase 3. They carry the `pdpOverwritesEdit`
semantics described above.

**Dropped:** the PDP-11/70 panel — `FormPdp1170PanelU` plus all of `pdp1170panel/`, ~2.9k
lines. Its IO-Warrior USB binding is already inert (`iowkit.pas:4-6`: "always returns false
here"). `FormExecuteBlinkenlightU`/`BlinkenlightInstructionsU` are *not* the panel — they
generate instructions for keying memory in on a real front panel — and stay.

**Not ported at all:** dead legacy alternates (`MemoryLoaderU.org.pas`, `.org2.pas`,
`SerialIoHubU.indy.pas`, `FormTerminalU.emulvt.pas`, `FormTerminalU.richedit.org.pas`, ~3k
lines, excluded from the build by unit-name collision) and `common/JH_Utilities.pas` (2,659
lines of junk drawer — cherry-pick the ~15 functions with real callers). Do **not** port
`JH_Utilities.CorrectPath` (`:618-650`): it is entirely `\`-based with drive-letter checks.
Use `java.nio.file.Path` and delete the concept.

---

## 4. Settings and persistence

`TJH_Registry` (`JH_Utilities.pas:46-130`) descends from `TRegistry` and is overloaded
**per widget type** — `Save(TComboBox)`, `Save(TListBox)`, `Save(TCheckBox)`,
`Save(TRadioButton)`, `Save(TEdit)`, `Save(TMenuItem)`, `Save(TMaskEdit)`,
`Save(TPageControl)` — with a `Loading` re-entrancy flag (`:61`) because loading a control
fires its `OnChange`, which saves again. None of this survives.

Replacement: a typed settings object tree serialized to JSON under the platform config dir
(`~/.config/pdp11gui/settings.json` on Linux, `%APPDATA%` on Windows,
`~/Library/Application Support` on macOS), with a thin `SettingsBinding` helper for widgets
that genuinely need it. Versioned with a schema field from day one.

No migration from the Lazarus install: its window coordinates are MDI-client-relative and
meaningless as screen positions, and the window set is changing anyway.

---

## 5. Phased delivery

Each phase ends with something runnable and testable.

**Phase 0 — Scaffolding. DONE.** Maven multi-module skeleton, the `--limit-modules java.base`
ban on Swing/AWT in `pdp11-core` (see the module layout section — not an enforcer rule),
FlatLaf shell that opens an empty main window, CI on the three platforms. **Upgrade Maven first** — `mvn` on `PATH` is 3.5.4 (2018), which predates proper Java 11+
toolchain support and will fight current compiler/enforcer plugin versions. Resolved with a
Maven wrapper pinning 3.9.11 plus a `requireMavenVersion` floor of 3.9, rather than by
touching the user's `~/bin/mvn`: the wrapper is what CI uses on all three platforms anyway.
Build with `./mvnw`. JDK 21 and 25 are both
installed; target 21 (LTS). `macro11`, `m4` and SimH's `pdp11` are on `PATH` (`/home/jal/bin/`), so phase 4 and 6
integration tests can run locally. Two toolchain gaps found during phase 1 have since been
closed and both tools now work: `pdp11` was linked against a `libvdeplug.so.2` Ubuntu no
longer packages under that name, and Free Pascal was not installed at all, so the Lazarus
reference build could not be compiled for cross-checking. `tools/gen-disas-corpus.sh` still
carries the `libvdeplug` workaround in case the binary is replaced.

**CI has no SimH, no `macro11` and no Free Pascal**, and is not going to get them. Anything
that needs one is either a committed fixture (the disassembler corpus) or a script under
`tools/` run by hand — see `tools/pascal-disas-diff.sh`. Keep that split; a cross-check that
only runs on one machine must not be able to break the build on the others. **Extract `machines/*.ini` from the retrocmp.com installer and commit them** —
they drive bitfield definitions, register-group windows, the I/O page scanner and the fakes'
valid-address map, and they are not in the Pascal repo. **Done:** recovered from
`PDP11GUI.msi` 1.48.6 (GitHub releases → `Data1.cab`) and committed to
`pdp11-app/src/main/resources/machines/` — `pdp11.ini` plus the eight `*.modules` libraries
it includes, ISO-8859-1 with CRLF. *Small.*

**Phase 1 — Pure core. DONE.** `Address`, `MemoryAddressType`, octal formatting, `BitfieldDef*`,
`Disassembler` (from `common/Pdp11DisasU.pas`, 628 lines — take `byte[]` instead of the
`PAnsiChar` that exists only for retired-DLL ABI compatibility, `:56-58`), plus the `Logger`
and `ProgressMonitor` abstractions. ~1,500 lines, no dependencies, immediately unit-testable.
**Done when:** `Address.withWidth` round-trips across all four width conversions, and
disassembler output matches on a corpus assembled from `mac/*.mac`. Validate the
disassembler against **SimH itself** (`examine -m`) rather than against the Pascal port —
`Pdp11DisasU.pas` explicitly mirrors SimH's `pdp11_sys.c` operand classes (`:104-106`), SimH
is already installed, and checking against the authority beats checking against another port
of it. *Small–medium.*

**Outcome.** The cross-check went **exhaustive** rather than sampled — all 65536 words, via
`tools/gen-disas-corpus.sh`, committed as a test fixture so the test needs no SimH. Going
exhaustive rather than sampling is what turned up half of what follows.

Three implementations of this instruction set are available on this machine — the Java port,
the Pascal, and SimH — plus `macro11`, which encodes rather than decodes and so is an
independent fourth opinion. Every disagreement between the first three was settled by
assembling the disputed instruction with `macro11`. **That technique is the transferable
result of this phase**; remember it for phase 4, where the same three-way disagreement will
happen about console protocol behaviour rather than opcodes.

- **Two bugs in the Pascal, not reproduced.** Both confirmed by *running* it, not just reading
  it — `tools/pascal-disas-diff.sh` rebuilds the Pascal unit with `fpc -Mdelphi` and diffs all
  65536 words.
  - `cls3B` reads SPL's level from bits 8..6 instead of 2..0 (`Pdp11DisasU.pas:513`, reusing
    the `reg3` field meant for RSOP/SOPR). Those bits are part of SPL's own opcode and are
    always `010`, so it prints `SPL 2` for *every* SPL; only `000232` is right, by accident.
  - Mode 0 of a float operand passes the full 3-bit register field to `FacName`, which masks
    it to 2 bits (`:410`, `:342`), so `CLRF AC4`/`AC5` print as `AC0`/`AC1`. 176 words.
  - **Those 183 words are the *only* difference between the two implementations**, word counts
    included. That is the phase-1 statement of a faithful port.
- **Two bugs in SimH, not adopted.** Its `opcode[]` table has the same copy-paste duplicate in
  each half of the condition-code group: `000256` repeats `000255`'s text and `000276` repeats
  `000275`'s. Bit 0 is clear in both, so the decodes are `CLN CLZ CLV` and `SEN SEZ SEV`. The
  Pascal header documents only the first, and the Pascal gets both right; the second surfaced
  *because* the sweep was exhaustive. Still present in the current SimH build.
- **A third SimH bug, since fixed upstream.** Build `8ed26d30` decoded `LDEXP` for AC0 only,
  though `STEXP` — identical encoding format — worked for all four; `macro11` assembles
  `LDEXP R0,AC1` to `176500`. Build `a1f57fa3` fixes it and now agrees with us. The corpus was
  regenerated against the newer build and the exclusion removed. A disagreement that resolves
  itself in your favour is the strongest confirmation available.

**Phase 2 — Model.** `MemoryCell`/`MemoryCellGroup`/`MemoryCellGroups` with the new listener
bus and address index; `Pdp11Mmu`; machine-description `.ini` parsing with the m4 replacement.
Cut `MemoryCellU`'s dependency on `ConsolePDP1144U` and `FormMainU` (`:185-192`) behind
interfaces. Decide `ChangeAdddressWidth` (`MemoryCellU.pas:841-857`, called from
`FormMainU.pas:762,769,776`): with immutable `Address` it becomes a rebuild rather than an
in-place mutation. **Done when:** a real `machines/pdp11.ini` loads into groups and bitfield
defs, and propagation tests cover the three storm guards. *Medium.*

**Phase 3 — Transports and fakes.** `PhysicalTransport` + serial (jSerialComm), telnet
(Socket + IAC state machine, ~150 lines replacing `OverbyteIcsTnCnx.pas`), SimH child process,
and the ported `Fake*` simulators (~2,500 lines). Keep the fake's memory a **flat array**,
not a sparse map: `Mem: array[0..FakePDP11_max_addr-1] of word` (`FakePDP11GenericU.pas:71`)
is indexed by *byte* address and so wastes half, but the Java fix is one line — `short[]`
indexed by `addr >> 1`, 4 MB, nothing on a JVM. A `HashMap` would be slower, allocate per
access, and obscure exactly the code you most want diffable against the Pascal.
**Preserve verbatim** the state-transition
table at `FakePDP11ODTU.pas:60-83` and the real-hardware behaviour notes at `:86-130`
(verified on an actual 11/23: odd-address rejection, non-existent memory, echo-then-`?`
semantics) — those comments are test specifications. **Done when:** a fake responds correctly
to hand-written byte sequences in tests. *Medium–large.*

**Phase 4 — Console layer.** The threading model from §1, `ConsoleScanner`, `AnswerPhrase`,
and the console implementations — **SimH direct first**, then ODT, then M9301/M9312, 11/44,
K1630. Preserve `FindFreeSimhPorts` exactly (`SerialIoHubU.pas:622-639`): it probes upward
from 4000/4001 *deliberately without* `SO_REUSEADDR` so a `TIME_WAIT` port correctly counts as
busy, matching what SimH itself can bind. Rationale at `:256-272` and in `CHANGES.md`; this
was hard-won. Also preserve the event-driven `TSimhCpuState` tracking
(`ConsolePDP11SimHU.pas:98, 542-551`) — an earlier timeout-based version was a real hazard.
**Done when:** headless integration tests examine and deposit against every fake, and against
a real SimH launched by the test. *Large — the heart of the port.*

**Phase 5 — First usable app.** Main window with terminal and connection status, Settings
with the decomposed `ConnectionProfile` model, `WindowManager` + `ToolWindow` + geometry
persistence, Log window, Memory view (with `MemoryCellGroupTable`/`MemoryCellGroupList`),
Execution Control, Disassembler. **Done when:** you can connect to SimH direct, see the
console, examine and deposit memory, run and single-step, and disassemble. *Large.*

**Do the `AppContext` service extraction here, not later.** Lazy window creation is what
forces it: today `FormMain.FormExecute.StartPCEdit.Text := …` works because every window
exists from startup, and with create-on-demand those ~120 `FormMain.X` reads and the direct
sibling reach-ins (`FormDiscImageU.pas:389, 1038, 1043`; `FormMemoryLoaderU.pas:277-278`;
`SerialXferU.pas:653-749`) simply have no target. `AppContext` bundles
`ConnectionManager`, `MemoryCellGroups`, `MachineDescription`, `WindowManager`,
`SettingsStore`, `Logger`, `dataDir()` and the shared failure handler. Retrofitting it in a
later phase means reworking every window built before it.

**Phase 6 — Assembler and remaining tools.** The merged Assembler window (RSyntaxTextArea +
MACRO-11 JFlex mode + external `macro11` on `PATH` via `ProcessBuilder` — two runs,
`[src,'-l',lst]` then `['-e','listhex',…]` with the second allowed to fail, per
`FormMacro11SourceU.pas:335-360`), Memory Loader, Memory Dumper, Memory Test, Bitfields, I/O
Page Scanner, MMU, Microcode, Number Converter, Blinkenlight Execution, SimH Console and
Remote Log windows. **Done when:** feature parity minus disc images. *Large.*

**Phase 7 — Disc images.** `MediaImageDevicesU` (1,189), `SerialXferU` (939),
`DiscImageBadBlockU` (746), `MediaImageBufferU` (373) and `FormDiscImageU` (2,061) — ~5,300
lines, the hardest and least testable code. Rewrite `SerialXferU.pas:100-104`'s two codec
methods (three simultaneous `var` cursors through a stateful RLE encoder) as a proper codec
object rather than translating them literally.

**Do not port `SerialXferU.TransmitCharBuffer`'s timing voodoo literally.** It contains a
self-described "mystery sleep" (`:521-534`) with the alternatives left in as comments —
`sleep(1000)` "OK", `sleep(100)` "NICHT OK", `sleep(250)` "NICHT OK nach 90 minuten" — added
to work around an unexplained hang ("mysterious endless `WaitForSingleObject()` in telnet
stack") in the *Windows ICS telnet component that no longer exists*; the current telnet
client is the hand-written `OverbyteIcsTnCnx.pas`. It also asserts
`Physical_Poll_Disable > 0`, i.e. an implicit "caller must have locked" contract that the new
threading model replaces outright. Port the protocol, then re-derive any delays that turn out
to be genuinely necessary from observed behaviour. Redesign `FormDiscImageU`'s `OnDeactivate`
guard (`:331-359`), which today pops a modal query on *focus loss* and may `BringToFront` to
refuse deactivation — unworkable with floating windows where focus changes constantly;
replace with a visible "driver loaded" state plus confirmation on close/disconnect.
Devices: RL01/RL02, RX01/RX02 SD/DD, RM02/03, RK05, RK06/07, MSCP, plus PC11/PC05 high-speed
paper tape. Note `TMediaImage_TapeController_TM11` (`MediaImageDevicesU.pas:481-493`) is
unfinished scaffolding — commented out, with `_` and `??` placeholders, and its
`driver/pdp11gui_tm11.mac` does not exist. Do not port it; drop TM11 or implement it fresh
if tape support is wanted. *Large.*

**Phase 8 — Packaging.** `jpackage` installers per platform, signing/notarization as needed.

**Rough total: ~16–19 weeks of focused work, ~25–28k lines of Java.** Java is more verbose
per statement, but the port sheds roughly 9k lines outright: the 11/70 panel, the dead legacy
alternates, all of `JH_Utilities` bar ~15 functions, all `.dfm` layout, all the MDI
machinery, and `CorrectPath`. Phase 4 (the console layer and its threading model) is the
keystone — budget it generously, because if the threading model is wrong everything after it
is rework.

---

## 6. Verification

- **Unit tests** on everything in `pdp11-core`; the enforcer ban keeps it possible. Back it
  with an ArchUnit test (`noClasses().should().dependOnClassesThat().resideInAnyPackage(
  "javax.swing..", "java.awt..")`) so the layering violation is a build failure rather than a
  code-review question — given 31 `FormMain.`/`BusyForm.` references inside `SerialXferU.pas`
  alone, this is the single highest-value test in the plan.
- **Scanner byte-split invariance** — the highest-value test *type* here. Take a recorded
  byte stream and feed it through the receiver split at every possible chunk boundary,
  asserting an identical phrase sequence each time. This directly exercises the
  incomplete-input rewind, `mark()`/`restore()` and buffer cleanup, and is what catches
  the one-deep-backtrack hazard in `ConsoleGenericU.pas:327-339`.
- **Timeouts on all tests** (`junit.jupiter.execution.timeout.default`) so a threading
  deadlock fails CI instead of hanging it.
- **Fakes as the integration harness.** The ported `Fake*` simulators drive the real console
  implementations end to end, headlessly, with no PDP-11 and no SimH. This is the primary
  safety net, per the agreed strategy.
- **SimH integration tests** in phase 4+, launching a real `pdp11` from the test and
  exercising examine/deposit/run/halt/step.
- **Disassembler cross-checks, two of them, with different jobs.** The committed SimH corpus
  (`pdp11-core/src/test/resources/disas/simh-corpus.txt`, all 65536 words) is the permanent
  regression test: SimH is the authority both implementations were written against, and the
  fixture means CI needs no SimH. The Pascal diff (`tools/pascal-disas-diff.sh`) is **not** a
  committed test — pinning a regression test to an implementation with known bugs would pin
  the bugs — but is run by hand when the disassembler changes, to confirm the places the two
  part company are still exactly the 183 deliberate ones.
- **Cross-checking against the Lazarus build** by hand for each window as it lands — the old
  app remains runnable via `./Pdp11gui/run.sh` throughout.
- **Golden transcripts are deferred** by decision, but keep the door open: because
  `PhysicalTransport` is a two-method interface, a `RecordingTransport` decorator can be added
  later in a few dozen lines to capture byte streams for replay.

Run the app during development with
`mvn -pl pdp11-app exec:java`, or `java -jar pdp11-app/target/pdp11gui.jar`.

---

## 7. Risks and open items

1. ~~**Machine `.ini` files are not in the repo.**~~ **Closed in phase 0.** Recovered from the
   1.48.6 MSI and committed; see `pdp11-app/src/main/resources/machines/README.md`.

   **The m4 subset is now measured, and it is tiny** — across all nine files: `define` ×15,
   `include` ×11, `eval(expr, 8)` ×3, and `$1`/`$2` ×46. Nothing else. No `ifelse`, `ifdef`,
   `dnl`, `changequote`, `divert`, `shift`, `incr`, `len`, `substr`, `translit`, `patsubst`,
   `format` or `syscmd`; no `$0`, `$#`, `$@` or `$*`; `eval` needs only `+`, `-`, `*`,
   parentheses, a C-style leading-`0`-means-octal literal, and radix-8 output. That is a few
   hundred lines of Java, so the fallback of converting the files to a simpler format is not
   needed — build the preprocessor.

   `pdp11-app/src/test/resources/machines/pdp11.expected.ini` is a byte-for-byte golden
   fixture of GNU m4 1.4.21's output over `pdp11.ini`; the phase 2 preprocessor must reproduce
   it exactly.

   **Related, and worth knowing before planning cross-checks:** machine-description loading is
   **currently broken on Linux**. `MemoryCellU.pas:599` hardcodes `m4.bat` — a Windows batch
   file — and raises if it is absent; there is no `m4.sh` (only `macro11.bat`, `m4.bat` and
   `run.sh` exist). So in the Lazarus Linux build, Bitfields, the I/O page scanner and the
   register-group windows cannot work at all. Consequences: the Java port *fixes* a real
   broken feature rather than merely modernizing it; and those features cannot be
   cross-checked against the running Lazarus Linux build — only against Windows, or against a
   quick local `m4.sh` written for the purpose.

   The fakes are **not** blocked by this: `CalcIoPageValidMap`
   (`FakePDP11GenericU.pas:232-266`) seeds R0–R7 and the PSW unconditionally and merely *adds*
   machine-description addresses, so with no `.ini` a fake still works with a minimal I/O
   page. Phase 3 can therefore proceed before the `.ini` files are recovered.
2. **CLAUDE.md is wrong** about m4: it says m4 preprocesses "driver templates", but
   `PreprocessIniFile` is called only from `AddGroupsFromIniFile` (`MemoryCellU.pas:681`) and
   the driver `.mac` files contain no m4 macros. Fix that note in whichever repo it lands in.
3. **Threading is the highest-risk rewrite.** The Pascal design depends on cooperative
   reentrancy in at least two documented places. Getting the command-executor ordering wrong
   will produce intermittent, hard-to-reproduce protocol bugs. Build phase 4 against the fakes
   with tests before touching real hardware.
4. **`macro11` on `PATH` is a user-visible regression on Windows and macOS**, where it is not
   normally present. Acceptable per decision, but the app should detect its absence at startup
   and say so clearly rather than failing at first assemble.
5. **Two codebases until parity.** The Lazarus version is actively developed (recent SimH
   direct work, per `CHANGES.md`). Decide explicitly whether Pascal-side development freezes
   during the port; if not, phases 4–7 will be chasing a moving target.

6. **Freeze the scope of "redesign the UX" for v1.** A faithful logic port and a UI redesign
   are two projects, and phases 5–6 will expand without bound if "redesign" stays open-ended.
   Recommend limiting v1 to the *structural* changes already decided — floating windows,
   assembler tabs, unlimited memory views, terminal in the main window, decomposed connection
   profiles, geometry-only persistence — and deferring visual and interaction redesign until
   the app is behaviourally complete.

7. **The running Pascal app is not a reliable oracle for timing.** `Physical_ReadByte` pumps
   `Application.ProcessMessages` *inside itself* on the telnet path (`SerialIoHubU.pas:829`)
   before checking the buffer, so a console command's byte-read can run arbitrary UI code
   including another command. Behaviour under load is therefore not deterministic. For
   timing-sensitive cases, treat the documented hardware behaviour in the `Fake*` units as the
   specification, not observed behaviour of the running app.

8. **Translate Pascal `assert()` to real checks, not Java `assert`.** Both are off by default
   (Pascal release builds; Java without `-ea`), and several encode genuine invariants
   (`AddressU.pas:134`, `FakePDP11GenericU.pas:123, 257-260`,
   `ConsolePDP11ODTU.pas:719-722, 760-764`). Every one sits on a human-timescale operation, so
   a real `IllegalArgumentException`/`IllegalStateException` costs nothing.
