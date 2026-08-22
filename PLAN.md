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
| 2 — Model | **Done** | `MemoryCell*` + listener bus with the three storm guards, `Pdp11Mmu`, ini parsing, and an m4 replacement that matches GNU m4 byte for byte. 99 tests. The shipped machine description loads clean: 17 groups, 62 bitfield defs, 233 cells. |
| 3 — Transports and fakes | **Done** | `PhysicalTransport` with fake, serial, telnet and SimH-process implementations, all tested; every fake ported - ODT (both dialects), 11/44, 11/44 V3.40C, M9312, M9301 - plus `FakeSimh`, which the Pascal does not have. 80 tests over the four ported fakes; `FakeSimh` is driven by the SimH console's own. |
| 4 — Console layer | **Partly done** | Threading model, `AnswerPhrase`, `ConsoleScanner`, `ConsoleConnection`, and two of the five consoles: SimH direct (with bulk examine and run control, verified by `SimhConsoleIT` against a real SimH), ODT in both dialects, and the 11/44 in both firmwares. **Deferred: the M9301/M9312 boot-ROM console** - nothing in phase 5 needs it, and its fakes are ported and waiting. |
| 5 — First usable app | **Partly done** | `AppContext` first, as this section insists; settings as versioned JSON in the platform config dir; `WindowKey`/`ToolWindow`/`WindowManager` with multi-monitor clamping; `ConnectionProfile`/`ConnectionManager`; terminal behind a `TerminalView` interface; main window and Log window. It starts, connects to any of the seven protocols against a simulated machine, and shows the conversation. 331 tests. **Still to do: the Settings dialog, Memory view, Execution Control and Disassembler.** |
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

**Phase 2 — Model. DONE.** `MemoryCell`/`MemoryCellGroup`/`MemoryCellGroups` with the new listener
bus and address index; `Pdp11Mmu`; machine-description `.ini` parsing with the m4 replacement.
Cut `MemoryCellU`'s dependency on `ConsolePDP1144U` and `FormMainU` (`:185-192`) behind
interfaces. Decide `ChangeAdddressWidth` (`MemoryCellU.pas:841-857`, called from
`FormMainU.pas:762,769,776`): with immutable `Address` it becomes a rebuild rather than an
in-place mutation. **Done when:** a real `machines/pdp11.ini` loads into groups and bitfield
defs, and propagation tests cover the three storm guards. *Medium.*

**Outcome.** Both criteria met, and the shipped description loads with **zero warnings**: 17
device groups, 62 bitfield definitions, 233 cells. Remember this feature has never worked on
Linux at all, so nothing had ever checked what is actually in those files.

- **The m4 replacement reproduces GNU m4 1.4.21 byte for byte** over the shipped description.
  Two properties of m4 turned out not to be optional. A macro's expansion must be *rescanned* —
  `Module_SLU`'s body contains `_offset($1,0)`, so a regex pass cannot work — and a builtin
  name is only a macro when it is actually *called*: these files are full of English prose
  containing the words `include`, `index`, `format` and `len`, and expanding those would
  corrupt the descriptions. 215 expansions for `pdp11.ini`.
- **Two tightenings I added were wrong, and the real data said so.** I rejected overlapping
  bitfields and duplicate addresses within a group as obvious corruption. Both are deliberate:
  the DZ11 defines a register's read and write meanings in one section with a name prefix (41
  overlapping fields), and the RX211 declares its single data buffer under six names at one
  address because the controller reinterprets it at each stage of a transfer. Loading the real
  file before trusting a validation rule is the lesson.
- **Four bugs in `Pdp11MmuU.pas`, not reproduced.** No oracle exists for these — SimH's console
  `examine` does not relocate, so it cannot be asked — so each has a test stating the handbook
  rule it follows rather than just a number. (1) The displacement mask is `$1777`, not `$1FFF`;
  that is not a contiguous field, so bits 3, 7 and 11 drop out of the middle of every offset.
  (2) The page-length check is off by one block, so a `PLF` of 0 — a legal one-block page —
  rejects every address in it. (3) Downward-expanding pages raise an exception, and those are
  stack pages, the first thing you meet in a running kernel. (4) Four of the twelve
  register-dispatch branches are copy-paste wrong: kernel instruction PAR and PDR both write
  the *user* arrays, and three PDR branches re-test the PAR range above them and so are
  unreachable, leaving those PDR sets zero forever. The Java drives that dispatch off a table
  of twelve blocks rather than a ladder of near-identical range tests, which is what stops it
  recurring.
- **Divergence from §2's sketch, deliberately.** The sketch puts `addListener` on
  `MemoryCellGroups`; listeners are on `MemoryCellGroup` instead, because all eight Pascal
  subscription sites are per-group and the `pdpOverwritesEdit` opt-out is per-group too. A
  single global list would make every window filter events for 25 groups it does not care
  about. The *address index* is on `MemoryCellGroups`, as the sketch has it.
- **The index is keyed on the address normalised to 22 bits**, not on the raw value. The MMU
  builds its group at 22 bits while descriptions load at 16, and `0177776` and `017777776` are
  the same processor status word; comparing raw values, as the Pascal does, gets that wrong in
  both directions.

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

**Outcome.** All four transports are in and tested, and every fake is ported: ODT in both
dialects, the 11/44 and its V3.40C firmware, the M9312 and the M9301 - plus `FakeSimh`, which
has no Pascal counterpart and exists because the Pascal tests its SimH console against SimH,
which CI cannot do. 80 tests over the four ported fakes, all headless; `FakeSimh` is driven by
the SimH console's own tests rather than having a set of its own.

- **The K1630 fake needed no port at all.** `FakePDP11ODTK1630U` is four lines: an 18-bit ODT
  with `isK1630 := true`. Making the dialect a constructor argument rather than a mutable flag
  had already absorbed it, which is the second time that decision has paid - the console layer's
  `OdtDialect` is the first.
- **The two 11/44 fakes are one class and a subclass.** The Pascal duplicates the whole 450-line
  unit for the V3.40C firmware; roughly a hundred lines differ, and every one of them is
  something a console driver has to be told about - console/program mode, backspace instead of
  RUBOUT, worded errors instead of numbered ones, an address space class printed with every
  examine, addresses masked rather than rejected. As a subclass those hundred lines *are* the
  class, and the shared 350 have one copy.
- **Three Pascal bugs, none reproduced.**
  - `TFakePDP1144.SerialReadByte` prints a pending error by *replacing* the output buffer rather
    than appending to it, so anything printed and not yet read is lost. The path turns out to be
    unreachable - the only things that set that error run inside the carriage-return handler, and
    the prompt at the end of it always clears the error first.
  - `TFakePDP11M9312.doSTART` calls `doConsoleEmulatorErrorHALT` for an invalid address and then
    runs the machine anyway: no `Exit`. So a bad start prints two halt messages and starts
    regardless.
  - `isLoadedAddrValid` guards the register space with `< 177700 + 7`, so R7 at `177707` is not
    caught where the other seven are. **Kept**, and commented: nothing here knows what the real
    emulator does, and a driver that examines R7 through this would break if it were "fixed".
- **A trap in our own `MemoryAddressType.getMaxAddress()`**, found by a test. It is the highest
  *word* address - `2^bits - 2` - which is right for what it is named after and wrong as an
  address mask: masking with it clears the low bit that tells one byte-spaced global register
  from the next, so `D/G 1` wrote to R0. The 11/44 fake has its own `ADDRESS_SPACE_MASK`, and the
  same care is needed anywhere else that reaches for it.
- **The M9312 validates as you type, which is unusual and load-bearing.** The first character
  after a prompt is always accepted; the *second* decides whether the line can still become
  something, and if it cannot the line is thrown away then and there with a register dump. That
  is why `DL` is a boot command and not a malformed deposit - and why a driver cannot batch
  characters at it and read the answer afterwards.
- **The M9301 is the M9312 with a `$` prompt followed by a NUL** - an M9301-YA sends the fill
  character and an M9301-YF does not. The Pascal's comment is "With NUL it is more difficult to
  parse!" That fake is the one that proves the scanners' NUL filtering works.

**The SimH-direct handshake, established live.** Four things have to be right before SimH's
remote console will answer anything, and each was found by it silently not working. Phase 4
needs all four; `SimhProcessTransportIT.enterMasterMode` is the worked example.

1. **Connect *both* telnet channels.** With only the remote channel open, SimH sends its
   banner and then answers nothing at all — no prompt, no echo, no reply, however long you
   wait and whatever you send, `^E` included. Connect the console port as well and it comes
   to life. The Pascal connects both (`SerialIoHubU.pas:726-727`) but from two unrelated
   places, so nothing there records that one depends on the other.
2. **Send `^E`.** Multiple-command mode is what produces a `sim>` prompt at all, whatever
   `set remote master` is in the `.ini`. SimH echoes the `^E` back as though it were a command
   and answers it `Unknown command` — harmless in itself, but see the warning below.

**Getting past that reliably is phase 4's job, not phase 3's, and it is harder than it looks.**
A worked exchange was reached live several times and is quoted below, but attempts to pin it
down as an integration test kept passing and then failing on the next run. The reason is
structural rather than a missing sleep: SimH prints its prompt *before* echoing the command it
is about to run, so no amount of waiting for `sim>` tells you whether the previous command
finished — and the `Unknown command` the `^E` provokes is indistinguishable, by text alone,
from one a real command provokes. What is needed is exactly what §2 already specifies for the
console layer: a restartable scanner that classifies a prompt, an echo and an answer as
different things. `SimhProcessTransportIT` therefore asserts only what phase 3 owns — launch,
port probe, both channels, IAC stripping, banner — and this is the raw material for phase 4:

```
sim> ^E
Unknown command
sim> show cpu
CPU     11/70, FPP, RH70, autoconfiguration enabled, idle disabled
        256KB
sim> deposit 1000 123456
sim> examine 1000
1000:	123456
sim>
```

`SimhProcessTransport` also drains SimH's own stdout on a daemon thread, but **not** because
not draining it breaks anything — an earlier version of this section claimed that and was
wrong. SimH prints far too little to fill a 64 KB pipe, and the test that would have shown it
found `getProcessOutput()` simply empty. Its stdout is a pipe rather than a terminal, so its
writes are block-buffered and have usually not arrived at all while it runs. It is drained
because it is the only place SimH reports a failed port bind or a bad configuration line, and
that is worth having when a launch goes wrong. **Do not synchronise on it.**

Two more things phase 4's scanner has to know, both confirmed live:

- **SimH prints the prompt *before* echoing the command**, so waiting for `sim>` after sending
  something returns too early. Key on the reply's shape instead: `examine 1000` answers
  `1000:\t123456`.
- **Every command is echoed back over the same line before it is acted on.** So an accepted
  command and a rejected one differ only in the text that follows the echo, which is the
  consequence the Pascal documents at `ConsolePDP11SimHU.pas:640-652`: prompt detection alone
  cannot tell them apart, and the echo must be excluded explicitly or every command looks
  rejected. SimH does keep a command whitelist (`allowed_remote_cmds` /
  `allowed_master_remote_cmds` in `sim_console.c`), but `show`, `examine` and `deposit` are all
  on it — an earlier version of this section claimed `show` was not, from misreading a
  rejection that was actually SimH answering the `^E`. Two wrong readings of this transcript in
  one sitting is a fair warning about how easy it is to misattribute a line here.

**Something must read the console channel.** Nothing does until the SimH Console window lands
in phase 6, and an emulated console that is never drained will eventually block SimH the same
way its stdout did.
- **PDP11GUI addresses R0..R7 at eight *consecutive byte* addresses** `017700..017707`, not
  word-spaced — its own convention, visible in the shipped machine description (`R0=177700`,
  `R1=177701`). So the fake's main memory is indexed by `addr >> 1` as §3 says, but its I/O
  page must stay byte-indexed or every register pair aliases onto one word.

**Phase 4 — Console layer.** The threading model from §1, `ConsoleScanner`, `AnswerPhrase`,
and the console implementations — **SimH direct first**, then ODT, then M9301/M9312, 11/44,
K1630. Preserve `FindFreeSimhPorts` exactly (`SerialIoHubU.pas:622-639`): it probes upward
from 4000/4001 *deliberately without* `SO_REUSEADDR` so a `TIME_WAIT` port correctly counts as
busy, matching what SimH itself can bind. Rationale at `:256-272` and in `CHANGES.md`; this
was hard-won. Also preserve the event-driven `TSimhCpuState` tracking
(`ConsolePDP11SimHU.pas:98, 542-551`) — an earlier timeout-based version was a real hazard.
**Done when:** headless integration tests examine and deposit against every fake, and against
a real SimH launched by the test. *Large — the heart of the port.*

**Outcome so far — SimH direct.** The threading model of §1 is in and is what §1 said it
would be: `ConsoleConnection` owns a reader thread and a single-threaded command executor, and
the executor being the critical section deletes `BeginCriticalSection`, its nesting counter, the
100 ms monitor timer and the inter-phrase `ProcessMessages` outright. Nothing replaced them. The
one deadlock trap §1 named is now an exception rather than a hazard: `call()` from the command
thread refuses.

- **The prompt is not a synchronisation point. The echo is.** This is the phase-3 problem, and
  it had a clean answer once stated properly: of everything SimH sends, exactly one thing cannot
  have been produced before the command was sent, and that is SimH's echo of the command itself.
  `sendCommand` waits for that echo and returns its position; the prompt check, the examine
  replies and the "did SimH reject this" scan all read strictly after it. `SimhConsoleIT` drives
  examine, deposit, run, halt and single step against a real SimH and has been run repeatedly
  without drifting. **The general lesson is worth carrying to the other consoles:** when a
  protocol repeats itself, synchronise on the token that is unique to this exchange, not on the
  one that merely tends to arrive next.
- **Two things the echo anchor needed before it was actually reliable**, both found by running
  the suite repeatedly rather than by reading the code.
  - **The first `^E` can arrive before anyone is listening.** A connected socket does not mean
    SimH's remote console session is ready, and a `^E` that lands early is simply gone. The
    Pascal covers this by sleeping a flat second in `Init` before doing anything, which is a
    guess that costs a second on every connection. Asking again is better than waiting longer:
    `resync` now sends `^E` up to four times within the same budget, and a repeat is harmless -
    outside a run it draws another `Unknown command`, and inside one the first stops the
    simulation and the prompt ends the loop.
  - **The echo has to be matched on the end of a line, not on the whole of it.** SimH prints
    "Simulator Running..." with no line ending, so a command sent while that fragment is still
    unconsumed comes back as `Simulator Running...E PC`. Requiring equality loses the anchor and
    the command waits out its full eight seconds for an echo that had already arrived. This is
    the *same* property the decoder already handles for the prompt, and missing it in the second
    place cost a five-second intermittent failure that only appeared when the class ran as a
    whole. Anything unterminated in the buffer breaks framing for whatever follows it; that is
    worth remembering for the consoles still to come.
- **An open item, stated rather than glossed: SimH occasionally does not come up.** Roughly one
  launch in thirty ends with SimH having accepted both telnet connections and sent its remote
  console banner, and then saying nothing whatsoever - no prompt, no echo, no master-mode
  notice, nothing on the emulated machine's own console either, both channels still open and its
  own stdout empty. It never enters master mode, and no amount of `^E` rescues it. What is known:
  - **It is not the protocol.** A probe that launched SimH fifteen times over and did the
    handshake got a prompt every time, both with a `^E` and with nothing sent at all.
  - **`^E` is not usually what produces the prompt.** `set remote master` plus both channels
    connected gets one on its own, fifteen out of fifteen. The `^E` is there for a plain telnet
    connection to a SimH somebody else started, which has no such configuration.
  - A theory that a bare `^E` sits unread in a line-buffered single-command mode was tested and
    **disproved**: terminating it with a return changes nothing.
  - The suspicion is a race in SimH's own startup - `set remote master` is the last line of the
    generated configuration, and it is the line that has to see a remote connection - but that
    has not been confirmed, and confirming it means reading SimH's source rather than guessing
    at it again.

  The console layer's behaviour in that case is already right: `resync` reports a console that
  will not answer instead of pretending otherwise, which in the application is the "no console
  prompt" a user reconnects from. `SimhConsoleIT` launches once more when it happens, prints the
  first attempt's evidence either way, and carries on - what it is there to test is the console
  protocol, not SimH's startup.
- **`AnswerCollector` therefore has positions, not just "the last one of this type".** The
  Pascal's `GetLastAnswer` searches the whole list backwards, which is what let a stale prompt
  satisfy a check. Both forms are available; the SimH console uses the positional one everywhere.
- **A fake SimH now exists (`FakeSimh`), and the Pascal has no such thing.** It tests its SimH
  console against SimH, which CI cannot do. It reproduces exactly three things - nothing works
  before `^E`, every command is echoed character by character, and the prompt is printed at the
  end of the *previous* command - because those are the three the protocol layer has to cope
  with. A fake that printed the prompt after the reply would quietly validate a synchronisation
  that does not work against the real thing.
- **Two deliberate divergences.** `Console.examine` returns a `CellValue`, not the `int` §1
  sketched: §2 had already decided the sentinel does not survive the port, and a UNIBUS timeout
  is precisely the case it was used for. And the base bulk deposit skips a cell whose edit value
  was never set - the Pascal sends `MEMORYCELL_ILLEGALVAL` to the machine as a value, depositing
  `177777` into whatever that cell names. With `CellValue` that is not expressible.
- **One hardening.** `while not examineAddrList(...) do ;` terminates in the Pascal because a
  comment says every call marks at least one more cell answered. That holds only while a failure
  can be attributed to a cell; against a SimH answering about an address nobody asked for it
  spins forever. The Java counts what is left and stops a pass that achieves nothing.
**Outcome so far — ODT.** The second console, and the first one that talks to real hardware.
It exercises the half of the scanner SimH does not use: the symbol lexer, the one-deep mark and
restore, and both control-flow exceptions. `FakePdp11Odt` was already ported in phase 3, so this
had a full transcript from a real PDP-11/23 to be tested against from the first line of code.

- **ODT is a terminal, and that is what shapes the parser.** Everything sent is echoed and the
  reply is glued to the echo - `1000/` comes back as `1000/000000 `, of which five
  characters are our own. So there is nothing here to anchor on the way SimH anchors on its
  echo, because here the echo *is* the answer. What makes it sound instead is that the phrase
  grammar says unambiguously where a reply starts: a prompt or a line end, then an address, then
  a slash. Two consoles, two different reasons a naive "send and wait" is wrong.
- **The one-symbol lookahead survives between decode calls, and has to.** "Leave the `@`
  standing" in the Pascal means the character is consumed from the buffer but stays as the
  scanner's current symbol, so the next call parses `@addr/val` without re-reading the prompt.
  That is the part of the restartable-lexer design that is easy to lose in translation and
  impossible to notice until a reply arrives split across two reads.
- **`OdtDialect` is the `ConsoleDialect` value object §2 asked for.** The Pascal has two
  independent booleans whose own comment says the K1630 "needs also
  GobbleExtraSpaceAfterPrompt" - a constraint nothing there enforces. Note it is deliberately
  *not* the same type as the fake's identically named enum: the transports depend on the fakes
  and the console layer depends on the transports, so sharing one would put a cycle between the
  packages. The fake's says what a machine prints; this says how a driver reads it.
- **One decode-loop fix.** A pass that consumes input without recognising a phrase now reports
  progress rather than "nothing found". The K1630 prefixes its halt report with `ESC S`, which
  matches no rule; the Pascal stops there with input still in the buffer and looks no further
  until the next byte arrives - and if that byte was the last of the reply, never. Every such
  pass consumes at least one character, so the loop still terminates.
- **What is left of phase 4:** the M9301 and M9312 boot-ROM console, which is one class and a
  prompt. It is the awkward one - no halt, no reset, no init, and an error stops the machine
  rather than reporting itself, so the driver has to notice an *absence* of output and say so.
  The K1630 needs no console of its own; it is a dialect of this one.

**Outcome so far — the 11/44.** The third console, and the one that reads a block per command:
`E/N:100 <addr>` is sixty-four words for one round trip, which is what makes a memory window over
a serial line bearable at all. Both firmwares are one class and a `Pdp1144Firmware` enum, the
same treatment `OdtDialect` got, replacing a boolean and a four-line subclass.

- **A stop report is also an examine answer, and has to be both.** `17777707 000114` means "the
  CPU stopped at 000114" and is also exactly what `E/G 7` answers, so one line becomes two
  phrases - and in that order, because it is the prompt after them that fires the stop event and
  the prompt looks *two* back for it, past the examine. The Pascal does this by parsing the line,
  keeping the halt, then deliberately pretending it found nothing so the same line gets parsed
  again. Not a trick worth improving on: any console whose stop report is a valid reading of a
  register has the same problem.
- **`17777707` only counts at the start of the line.** Anywhere else it is just an address - and
  the console echoes everything typed at it, so `E 17777707` would otherwise report a halt every
  time somebody examined the PC.
- **A stop event could be lost, and that was ours, not the Pascal's.** The task posted to the
  command thread re-read the pending PC from a field, and a second prompt arriving with no halt
  in front of it clears that field first. It is reachable whenever a command answers with two
  prompts. The address is now captured when the task is created; the fix is in the shared base
  and applies to all three consoles.
- **The fakes needed three commands the Pascal's never grew.** Its 11/44 fake stops at `E`, `D`,
  `S`, `I` and `^C` - its own header says so - so `H`, `N 1` and `C` had nowhere to go, and halt,
  single step and continue had nothing to be tested against. The shipped driver is the evidence
  for adding them: it sends all three and parses the first two's answers as `17777707 <pc>`, so
  the machine it was written against accepts them. **The format is the Pascal's and came from
  real hardware; only the acceptance is inferred**, and it is worth keeping that distinction in
  mind when reading those tests.
- **The V3.40C fake's `^P` reported no PC**, which is where that firmware actually halts, so the
  driver's halt had nothing to report and would have failed. Same conclusion: the fake was
  incomplete rather than the driver wrong.
- **One divergence of ours, removed.** The classic 11/44 fake had been given a "ignore the
  console while a program runs" gate that the Pascal does not have. It is the V3.40C firmware
  that stops listening and says so; the classic one goes on answering. Faithful now, and the
  difference is a test.

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

**Outcome so far — the shell that connects.** It runs: `java -jar pdp11gui.jar` gives a window
with a terminal, a connection status bar and a Windows menu, and connecting to a simulated
machine of any of the seven protocols works from a menu item with nothing installed. What is not
there yet is the Settings dialog and the three windows that make it *useful* - Memory view,
Execution Control, Disassembler.

- **`AppContext` was built first, and it was the right call.** Nothing reaches for a service; a
  window is handed what it needs and there is no static instance to reach for. That is cheap now
  and would have been a rewrite of every window later, which is exactly what this section
  predicted.
- **Decomposing the connection removed the cross product twice, not once.** §3 says to replace
  `FormSettingsU`'s 24 flat combo entries with {protocol} × {transport}. It also removes the
  *second* doubling nobody had counted: `TConsoleType` lists every console twice, once real and
  once `consoleSelftest*` ({@code ConsoleGenericU.pas:55-74}). Making the simulated machine a
  **transport** collapses that too - seven protocols and four transports, and the fakes cost one
  entry instead of seven. It also means the whole application can be driven with no hardware, no
  SimH and no serial port, which is what `ConnectionManagerTest` does for every protocol.
- **The terminal pre-filter earned its place immediately, and diverges from the Pascal.**
  `TerminalProfile` is applied in front of the view, as §3 requires, because ODT means its LF and
  the 11/44 means a lone CR. The divergence: the Pascal treats CR and LF independently, and SimH
  sets *both* flags and sends them together - so its transcript double-spaces
  ({@code FormTerminalU.pas:248-253, 278-279}). A CR LF pair is one line ending here. The
  terminal redesign is an agreed decision and every terminal ever built collapses the pair.
- **Multi-monitor clamping does not exist in the Pascal and now does.** A window restored onto a
  monitor that is no longer there is a window the user cannot reach or close. The rule is a pure
  function over a list of screen rectangles, so all of it is testable on a build machine with one
  screen and no display.
- **The phase-3 warning is discharged.** "Something must read SimH's console channel" - something
  does: `ConnectionManager` drains it and keeps the last 256 KB, so the SimH Console window in
  phase 6 will find a transcript waiting rather than a bug to fix.
- **Gson, not Jackson**, for settings: one jar with no transitive dependencies, records supported,
  and nothing here needs the configurability of the larger binder. Writes go through a temporary
  file and an atomic move, a file from a newer schema is left alone rather than silently
  truncated, and nothing about a bad settings file can stop the application starting.
- **One bug caught by writing the test first.** `SettingsStore.getLastProblem()` returned null
  before anything had been loaded - and the only caller asks on the way up, before anything has
  touched the settings, so an unreadable settings file would have been silently ignored forever.
  It loads first now.
- **JediTerm is still deferred, deliberately.** §3 calls it the riskiest dependency in the stack;
  `TerminalView` exists and `GlassTerminalView` implements it, which is the fallback §3 describes
  as more attractive than it sounds. The consoles are dumb TTYs and none of the protocols emit an
  escape sequence; full ANSI only matters for programs *running on* the PDP-11.
- **Layout is tested without a display, and rendered to a PNG for eyes.** The windows are thin
  frames around a `JPanel` - `MainPanel`, `LogPanel` - because a panel can be sized, laid out and
  painted into an image on a machine with no screen, and a `JFrame` cannot. So the layout
  assertions run on CI, and `target/ui-render/*.png` gets written on every build for the part
  that needs looking at rather than asserting. This is also the only polite way to check a layout
  when the development machine's display belongs to somebody who is using it.
  - **It found two things immediately.** A six-pixel seam of panel background between the
    terminal and the status bar, because `insets 0` does not imply `gap 0` in MigLayout. And a
    deadlock - in the *test*, not the application: laying out a component tree takes the AWT tree
    lock and then the document's read lock, while appending to the terminal takes the document's
    write lock and then wants the tree lock. Two threads, opposite order, reliable hang. The
    harness does all of it on the event thread now, which is the ordinary Swing rule and nothing
    to do with rendering offscreen.
  - `Xvfb` would have been the other answer and is not installed; it is also the worse one, since
    it needs a package on every machine that builds this and the offscreen render needs nothing.
- **Known gap: settings are saved when the main window closes, and only then.** Killing the
  process loses them. The Pascal is the same. A shutdown hook would cover it and has not been
  added, because a save on the way out of a crash is not obviously the right thing to want.

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
