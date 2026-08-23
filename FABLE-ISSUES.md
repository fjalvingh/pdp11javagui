# FABLE-ISSUES — review findings

Review of the full `java11gui` tree (pdp11-core, pdp11-ui, pdp11-app; ~28k lines of main
source) for bugs, code quality, internal inconsistency, and the user-interaction model,
measured against the project's own rules in CLAUDE.md and PLAN.md. The build and the full
test suite pass; everything below is latent. Findings are ordered by severity. All
Critical/High findings and the load-bearing Medium ones were verified directly against the
source; items marked *(needs confirmation)* are plausible from reading but not reproduced.

## Status

Each finding carries a status line under its heading as it is dealt with:

- **FIXED** — changed, with a test that fails without the change where one is possible.
- **WON'T FIX** — deliberately left, with the reason.
- **SUPERSEDED** — no longer applies after another fix.

No status line means not yet looked at. Anything found *after* the review is added under
"Found while fixing" at the end, keeping the severity-ordered list's numbers stable.

---

## Critical

### 1. Connect/disconnect is unserialized — a racing attempt can tear down the live connection and strand the UI in a stale state
`pdp11-core/.../conn/ConnectionManager.java:193`, `pdp11-ui/.../MainWindow.java:247`

**FIXED.** `ConnectionManager` now serialises connect/disconnect with a generation counter taken
under a lock the blocking part deliberately does not hold, so a disconnect never waits on an
attempt that is waiting on a machine. An attempt builds transport, drain thread, console and
connection into locals and publishes all of them in one guarded step at the end; one that has
been overtaken by then closes only what it built and changes no state at all, because both
`setState` and the unpublish step check the generation (and the MMU register group is now removed
by identity rather than by usage tag, which was the other way a stale attempt reached into the
live connection). Being overtaken is reported as `ConnectionSupersededException`, which the main
window logs rather than showing as a failure. `MainWindow` disables Connect, "Connect to
simulated" and Disconnect while a connection is being made, with a flag covering the gap between
the click and CONNECTING arriving back on the EDT. Regression test:
`ConnectionManagerTest.anOvertakenAttemptDoesNotTakeTheLiveConnectionWithIt` — it reproduces the
reported symptom exactly against the old code (`expected: <CONNECTED> but was: <FAILED>`) — plus
`disconnectingDuringAnAttemptEndsDisconnectedRatherThanFailed`. A side effect worth knowing about
for the findings below: `getConsole()` no longer returns a console that has not completed its
handshake, so the window state during CONNECTING is now simply "not connected".

The finding as reported: `MainWindow.connect()` spawns a fresh, unguarded worker thread per
menu click, and nothing disables Connect while state is CONNECTING. `ConnectionManager.connect()` is not
synchronized: its fields are individually `volatile` but the close → CONNECTING → build →
CONNECTED sequence is not atomic. If a second connect (or a Disconnect) runs while the
first is still blocked launching SimH or handshaking, the second's `close()` destroys the
first's half-built transport; the first attempt's catch block then calls `close()` again —
now reading and closing the *second* connection's transport and console — and fires
`setState(FAILED)` **after** the second attempt's `setState(CONNECTED)`. The result is a
working command thread with the manager stuck in FAILED: MmuPanel's
`updateDisplay()` sees `isConnected() == false` and shows "Not connected to a machine",
MainPanel shows "Connection failed", until some later transition. This is the most likely
root cause of the observed "MMU window stays Not connected after a successful reconnect" —
the panel's own listener pairing was checked and is correct; the staleness is in the state
machine. Fix: serialize connect/disconnect (single connection-management executor, or a
generation counter so a stale attempt's failure path can neither close nor set state over
a newer attempt), and disable Connect while CONNECTING.

### 2. A dropped connection never changes ConnectionManager state — the UI stays "Connected" after SimH dies or the line drops
`pdp11-core/.../console/AbstractConsole.java:167` (no path to ConnectionManager)

**FIXED.** `ConsoleConnection` now carries a `LostListener`, set by `ConnectionManager` before
`attach()` alongside the terminal sink, and the reader thread's `finally` calls it — after
`onDisconnected`, so anything blocked waiting for an answer is already unblocked — but only when
the reader did not stop because somebody called `close()`. `ConnectionManager.onConnectionLost`
ignores a connection that is no longer the published one (it was replaced or closed
deliberately, and whoever did that has already said what the state is) and otherwise takes over
the generation, tears the connection down and reports FAILED with the cause: "The connection to
X was closed at the other end", or "... was lost: <exception>". `ConsoleConnection.close()` no
longer joins the reader thread when the reader is the one closing it, which is now a real path
and would otherwise wait out its own 2-second timeout. `MainWindow` also prints the reason in
the terminal on a CONNECTED → FAILED transition only, so a machine that goes away says so where
the user is looking, without duplicating what a failed *attempt* already reports in a dialog.
Regression tests: `ConnectionManagerTest.aConnectionThatDropsBecomesFailedRatherThanStayingConnected`
(times out waiting for the drop to be noticed against the old code),
`aDeliberateDisconnectIsNotReportedAsADroppedConnection`,
`aReplacedConnectionDyingDoesNotDisturbTheLiveOne`, and
`WindowsBuildTest.aDroppedConnectionSaysSoInTheTerminalAndTheStatusBar`.

The finding as reported: when the reader thread ends, `ConsoleConnection.readerLoop` calls
`m_receiver.onDisconnected(cause)`, and `AbstractConsole.onDisconnected` only closes the
answer queue. Nothing notifies `ConnectionManager`, so `m_state` stays CONNECTED,
`isConnected()` stays true, the status bar keeps saying "Connected", terminal input stays
live, and every panel's connected-check passes against a dead wire; each subsequent
operation then fails one at a time with write/timeout errors. The inverse stale-state twin
of issue 1. Fix: route the reader-death callback to ConnectionManager and transition to
FAILED with the cause.

### 3. I/O page scan on a 16/18-bit machine runs to completion, then throws away all its results
`pdp11-core/.../machine/IoPageScanner.java:99,130-136`, `pdp11-ui/.../scan/IoPageScannerPanel.java:63`

**FIXED.** `scan()` now retypes the target to the console's width before storing anything in it —
`target.shiftRange(Address.of(type, base), 0, false)`, which is how a group is emptied *and*
re-expressed; `clear()` keeps the type the group was created with, which was the whole bug. The
window's comment now describes what happens. Regression test:
`IoPageScannerTest.aNarrowMachineRetypesTheTargetRatherThanLosingTheWholeScan` creates the target
at 22 bits exactly as the window does, scans an 18-bit ODT machine, and against the old code
fails with `IllegalArgument Cell address 760000/PHYSICAL18 does not match this group's PHYSICAL22`
after all 4096 examines — the full failure, reproduced. The existing tests kept their fixture,
which now goes through a two-argument helper so the width the window uses can be asked for
explicitly.

The finding as reported: `scan()` examines 4096 addresses typed `console.physicalAddressType()` (ODT_16/ODT_18 on a
real ODT machine — verified: `OdtConsole.physicalAddressType()` returns the profile width,
and ODT advertises `NON_FATAL_UNIBUS_TIMEOUT` so the scan is allowed to run), then stores
them back with `target.clear()` + `target.add(addr)`. The panel creates the target group
hard-coded at `PHYSICAL22`, and `MemoryCellGroup.add(Address)` throws
`IllegalArgumentException` on a type mismatch — so on real hardware the scan performs all
4096 serial examines (minutes over a serial line) and then crashes while storing, losing
everything. The panel comment "The group's type follows the machine, and the scan just
rebuilt it" states the intent; `clear()` never retypes a group, so `clear()+add()` cannot
deliver it. The app-level test hides this by creating its group at the protocol's own
type. Fix: retype/rebuild the target at the console's width inside `scan()` before adding.

### 4. The shared grid's dynamic overwrite policy silently overrides the loader/dumper/assembler groups' permanent `pdpOverwritesEdit(false)` — a loaded file can be clobbered before deposit
`pdp11-ui/.../mem/MemoryCellGroupTable.java:261-264`; victims: `load/MemoryLoaderPanel.java:89`, `dump/MemoryDumperPanel.java:85`, `macro11/AssemblerModel.java:111`

**FIXED.** The dynamic policy is now opt-in: `MemoryCellGroupTable.OverwritePolicy` is either
`GROUP_DECIDES` (the default — the group's own `pdpOverwritesEdit` is left exactly as its owner
set it) or `FOLLOW_EDITS` (the old behaviour). `MemoryPanel` asks for `FOLLOW_EDITS`, because it
is a view of the machine that happens to be typeable; the loader, dumper and assembler grids get
the default and their permanent opt-out is no longer overruled. `rebuild()` now updates the
policy too, so a `FOLLOW_EDITS` grid does not carry a stale decision across a range change —
which was the other half of the reported sequence. Regression test:
`MemoryLoaderPanelTest.aLoadedFileIsNotOverwrittenByAnotherWindowReadingTheSameAddresses` walks
the exact failure — load A, deposit, load B, let another window examine those addresses — and
against the old code fails with `expected: <219> but was: <73>`, that is file B's `0333` replaced
by the machine's `0111`. The `FOLLOW_EDITS` half stays covered by
`MemoryPanelTest.typingAValueMarksTheCellChangedAndStopsOtherWindowsOverwritingIt` and
`anotherWindowExaminingTheSameAddressDoesNotEatAnUncommittedEdit`.

`MemoryCellGroupList` has the same dynamic policy at `:223` and was left alone deliberately: its
two users (the I/O page scanner and the register-group windows) do not declare a permanent
policy, so there is nothing there for it to override.

The finding as reported: MemoryLoaderPanel, MemoryDumperPanel and AssemblerModel each set
`pdpOverwritesEdit(false)` permanently in their constructors (with comments saying so).
But all three display through `MemoryCellGroupTable`, whose `refresh()`/`setValueAt()`
call `updateOverwritePolicy()`, which unconditionally sets
`m_group.setPdpOverwritesEdit(getEditedCells().isEmpty())`. After "Deposit all" (edits now
equal machine values), `refresh()` flips the flag to true, and a subsequent "Load file"
does not flip it back (`rebuild()` never calls `updateOverwritePolicy()`). Concrete
failure: load file A, deposit it, load file B, then let any other window examine
overlapping addresses — propagation now writes machine values into the loader group and
the grid's listener copies pdp→edit, silently replacing the freshly loaded file B before
it is deposited; Verify then compares machine against machine and reports agreement. This
is exactly the failure the flag exists to prevent per the class's own Javadoc. Fix: make
the dynamic policy an opt-in (constructor flag used by MemoryPanel only) and leave groups
that declared a permanent `false` alone.

---

## High

### 5. Assembly worker mutates the live, bus-registered code group off both sanctioned threads
`pdp11-ui/.../macro11/AssemblerModel.java:274-311`; `pdp11-core/.../macro11/Macro11ListingParser.java:112-150`

**FIXED.** Parsing and installing are now two steps. `Macro11ListingParser.parse(lines, MemoryAddressType)`
reads the listing into a detached `Parsed` - a plain list of address/value/listing-line triples,
carrying no reference to any group - and `Parsed.installInto(group)` empties the group and refills
it in one go. The worker does the first half, the existing `AppContext.onUi` block does the second,
so the only thread that ever writes to the bus-registered code group is the event thread. The
detached build keeps the group's own "first cell declared at an address wins" lookup rule, because
the odd-byte merge depends on it; a test asserts the two routes produce identical cells. The
two-argument `parse(..., MemoryCellGroup)` overloads remain and do both halves on the caller's
thread, which is right for `loadListing` and for every test.

Overlapping assemblies are refused rather than serialised: `AssemblerModel` carries an
event-thread-only `m_assembling`, set before the worker starts and cleared in both `onUi` paths,
and a second `assemble()` fails with "An assembly is already running" instead of starting a second
worker that would install into the same group. `isAssembling()` is public so the Assembler
window's Compile button stays disabled for the duration; `canAssemble()` deliberately still means
only "there is something worth assembling", because the Execution window's "New program" uses it
to decide whether to say *open a source first*.

`assemble()` runs `Macro11ListingParser.parse(result.listing(), group)` on an ad-hoc
`macro11-assemble` thread. `parse` does `group.clear()` and repopulates it — mutating the
group's plain `ArrayList` and the `MemoryCellGroups` propagation index from a third thread
while the EDT can be painting the Code-tab grid connected to that same group and the
command thread can be walking the index in `syncMemoryCells`. None of
MemoryCell/MemoryCellGroup/MemoryCellGroups is synchronized. Nothing prevents two
concurrent assemblies either (AssemblerPanel Compile + ExecutionPanel "New program").
Failure: ConcurrentModificationException in a renderer or mid-examine console job, or a
half-cleared index that mis-routes propagation. Fix: parse into a detached list on the
worker and install it into the group inside the existing `AppContext.onUi` block; guard
against overlapping assemblies.

### 6. The assembler's "Verify" destroys the comparison it claims to make
`pdp11-ui/.../macro11/AssemblerPanel.java:118,256-257`; mechanism at `mem/MemoryCellGroupTable.java:279-291`

**FIXED.** The assembler got a real verify path rather than a relabel: the button promised the
right thing and the Loader beside it already did it. `MemoryCellGroupTable` now has
`verifyAll(owner, whenDone)` next to `examineAll` - the same examine without the
`setEditValue(getPdpValue())` loop, followed by a count of the cells the machine disagreed about,
handed to the caller on the event thread. `examineAll`'s Javadoc now says why its edit-copy is
right for *Examine all* and wrong for *Verify*, since that one line is the whole difference.

All three Verify buttons go through it now. The Memory Loader and the memory window's popup were
already doing the right thing by hand, in three near-identical copies of the same five lines;
they delegate, and the Loader keeps its "n words differ from the file" message through the
callback. The assembler says the same thing in its own words in the Code tab's status line, and
counts against the assembled total: *"3 words of 4 differ from the assembled program"*.

The regression test loads a listing, verifies against an empty machine, and asserts both halves -
that the disagreement is reported **and** that `cell(0)` still holds the assembled `012706` rather
than the machine's zero. It expects 3 of 4, not 4 of 4: the fourth word is the `halt`, which is
`000000` and genuinely agrees with an empty machine. Without the fix the test times out, because
the status line it waits for is never written at all.

The Memory Loader's Verify deliberately examines raw so that disagreements colour
themselves (the documented `pdpOverwritesEdit` design). The assembler Code tab's button is
also labelled "Verify", tooltip "Read the same addresses back off the machine and
compare" — but it calls `m_grid.examineAll(false, …)`, and `examineAll` unconditionally
loops `mc.setEditValue(mc.getPdpValue())` after the examine, overwriting the assembled
program in the grid with the machine's contents. Nothing can ever show as differing, and
the assembled words are silently discarded from the grid. Same label as the Loader,
opposite semantics; the memory window's popup "Verify against the machine" matches the
Loader. Fix: give the assembler a real verify path (examine without the edit-copy), or
relabel.

### 7. Unsaved assembler source is discarded without confirmation — by New, Open, and Quit
`pdp11-ui/.../macro11/AssemblerModel.java:181-187`, `AssemblerPanel.java:198-199,273-282`, `MainWindow.java:337-345`

**FIXED.** All three ask now. `AppContext` grew a `DiscardConfirmer` alongside the failure
handler and for the same reason: the window that can put a dialog on the screen installs it, and
nothing below has to know a dialog exists. `MainWindow` installs a Yes/No `JOptionPane`; the
default says yes, so a context with no window over it — a test, a headless run — never blocks on
an answer nobody can give, and behaves exactly as before.

The dirty check and the wording live in one place, `AssemblerModel.confirmDiscard(action)`,
because the three callers are in two different classes: New and Open in the Assembler window,
Quit in the main one. It returns true at once when nothing is unsaved, so a clean editor still
gets no dialog. The question names the file — *"p.mac has changes that have not been saved.
Discard them and open another file?"* — since "the MACRO-11 source" is not enough to decide with
after several files in one session; text that was never saved at all says so instead. Open asks
*before* the file chooser rather than after: there is no point picking a file only to be told the
one you have is in the way.

Four tests, all headless. New asks and keeps the text on *no*, discards on *yes*, and asks nothing
when there is nothing to lose; Open and Quit are checked through the model, since a file chooser
and `System.exit` are not things a test can click. Without the fix the first fails with
"it asked ==> expected: <1> but was: <0>".

The one line not covered by a headless test is `MainWindow.quit()`'s call to it — `MainWindow` is
a `JFrame`, and `WindowsBuildTest` already skips itself without a display.

The panel tracks dirty state (`isChanged()`, "*" in the status line, Save enablement), yet
"New" wipes the editor, file association and assembled code with no prompt; "Open ..."
replaces unsaved text the same way; and `MainWindow.quit()` exits without checking
`AssemblerModel.isChanged()`. A window that visibly models "changed since saved" but never
asks before destroying those changes is internally inconsistent — and a regression against
the rewrite's own goal that editor content survive window close. Fix: confirm on New/Open
when dirty, and check on quit.

### 8. Disconnect (and Quit) runs transport teardown on the EDT — up to 2 s+ freeze, deadlock-adjacent
`pdp11-ui/.../MainWindow.java:99-103,337-345`; `pdp11-core/.../console/ConsoleConnection.java:290-301`

**FIXED.** Disconnect now runs on a worker with the same shape as connect: the guard flag, the
menu items disabled for the duration, and `onConnectionState` on the way back. The flag was
`m_connecting` and is now `m_changingConnection`, which is what it always meant — the comment on
`setConnectionControlsEnabled` already said a Disconnect racing a connect was the thing being
prevented, and only half of it was. The terminal's "[disconnected]" moved from before the call to
after it: before is a claim, after is a fact.

Quit keeps its teardown too, but the order changed. Geometry, `closeAll` and `saveSettings` are
the event thread's own work and are quick; `dispose()` now happens before the connection is
closed, so the machine is torn down behind a screen that is already empty rather than in front of
one that has stopped repainting. That runs on a non-daemon `pdp11-shutdown` thread whose last
statement is the exit — non-daemon deliberately, since a daemon could be killed halfway through
and leave SimH orphaned. Both teardown paths are bounded already (`reader.join(2000)`, SimH's
`waitFor(2, SECONDS)` then `destroyForcibly`), so nothing here can hang the exit indefinitely.

Tested in `WindowsBuildTest` by asking the connection listener which thread reached DISCONNECTED -
listeners fire on the calling thread, so that is the property itself rather than a proxy for it.
Without the fix it reports `expected: <[false]> but was: <[true]>`. Like everything else in that
class it skips without a display, since `MainWindow` is a `JFrame`; there is no headless seam for
a menu item's action listener.

`connect()` is carefully run on a worker (its own comment explains the freeze/deadlock);
the Disconnect menu action calls `ConnectionManager.disconnect()` directly from the EDT.
`close()` does `reader.join(2000)`, closes the SimH console channel and the transport
(process teardown / serial port close, both potentially slow). A wedged transport freezes
the whole UI for seconds on Disconnect; same on quit, where it is more tolerable. Fix:
run disconnect on a worker exactly like connect.

### 9. NumberFormatException escapes the fake ODT and kills the command thread
`pdp11-core/.../fake/FakePdp11Odt.java:363` (parse sites `:234,:299,:329`)

**FIXED**, both halves of it.

The fake's three parse sites go through a `parseOctal` helper that converts a
`NumberFormatException` into the `FakePdp11Exception` `serialWriteByte` already knows how to
answer, so `1R2/` and `R3G` print `?` and a fresh prompt like an 11/23 - the same answer `12X4/`
already gave, since `X` is caught by the state machine before it ever reaches a parse. Reproduced
first: both threw straight out of `serialWriteByte`. The sibling fakes were checked and already
guard every `Octal.parse` (`FakePdp1144.parseOctalOr`, three try/catch blocks in
`FakePdp11M9312`); this was the only one that did not.

The second half is the one that made it dangerous rather than merely wrong.
`ConsoleConnection.execute` ran its task raw, so any `RuntimeException` out of a queued job
reached the single-threaded executor, which terminates the worker and silently starts another -
the work lost, the fake left mid-command, nothing logged anywhere. It is now wrapped: the
exception is logged with the connection it came from and the command thread carries on. `Error`
is deliberately not caught.

Two tests, both failing before. The ODT ones assert the exact transcript
(`"1R2/?\r\n@"`) and that the console still answers afterwards; the connection one asserts that
the *same* worker thread takes the next task and that the failure was logged - without the fix it
reports `expected: <Thread[#48,pdp11-command,...]> but was: <Thread[#72,...]>` above an
`Exception in thread "pdp11-command"` on stderr, which is precisely the reported symptom.

`serialWriteByte` catches only `FakePdp11Exception` and answers "?", but the parse paths
call `Octal.parse`, which throws `NumberFormatException` — reachable from typed input like
`1R2/` or `R3G`. The exception propagates through `FakeTransport.write` and
`ConsoleConnection.write` (which wraps only `IOException`); for terminal keystrokes routed
via `sendUserInput` (which catches only `ConsoleException`) it kills the command-executor
worker with an unlogged stack trace, leaving the fake mid-state instead of printing "?"
like the real 11/23. Fix: catch `NumberFormatException` in `serialWriteByte` (or make the
fake's parsers throw `FakePdp11Exception`).

### 10. A closed multi-instance memory window is unreachable forever — but still alive on the propagation bus
`pdp11-ui/.../window/WindowManager.java:95-101`, `window/ToolWindow.java:44-53`, `MainWindow.java:178-198`

**FIXED** by listing them, not by disposing them. Disposing multi-instance windows on close would
have made "close" mean two different things depending on the window type, and it is the wrong one:
`ToolWindow`'s own comment and PLAN.md §3 both say a closed window comes back as it was, and a
memory view's range and edits are exactly the thing worth keeping.

`WindowManager.hiddenWindows()` is the counterpart to `openWindows()`, and the Windows menu now
lists both - the hidden ones marked "(closed)". Said in a word rather than shown with a checkmark:
choosing one of those brings it back and choosing one of the others only raises it, and two
gestures that read the same would be worse than the word. `showAll()` is there too, beside
`hideAll()`, which is the other half of what PLAN.md §3 specified and the second gap this finding
names; the menu offers it only when something is hidden.

`openNew` is unchanged and its comment corrected. It claimed closing the second of three freed
that id; it does not, and must not - the window still exists, keeps its contents, and its saved
geometry is keyed on that id. Ids come back when a window is really disposed of, which is
`closeAll` and nothing else.

The test opens "Memory - 1", closes it, opens another and checks it is "Memory - 2" (the id is
still held), that the menu names the closed one, and that choosing it brings back that window
rather than a third. Without the fix the menu reads `[New memory window, Memory - 2, Hide all]` -
the finding, printed. It lives in `WindowsBuildTest` and skips without a display, since both a
`ToolWindow` and the menu are `JFrame` work.

Closing a tool window only hides it. Singletons come back via their Windows-menu entry,
contents intact. But for MEMORY (any multi-instance type) the menu offers only "New memory
window" plus a list of *visible* windows. Close "Memory - 1" and no gesture ever shows it
again: `openNew` skips id 1 because the hidden window still occupies the key, so the user
gets a fresh "Memory - 2" while the hidden "Memory - 1" — with its range, its edits, and
its `MemoryCellGroup` still registered on the propagation bus — persists invisibly until
shutdown. Close therefore means "reopenable" for singletons and "lost, but not gone" for
memory views. Related: PLAN.md §3 specifies "Show All / Hide All"; only "Hide all" is
implemented (`MainWindow.java:199-203`) — Show All is precisely the gesture that would
have recovered them. Fix: list hidden windows in the menu (or implement Show All), or
truly dispose+unregister multi-instance windows on close.

### 11. Two incompatible disconnected-state models: half the windows grey out, the other half raise a modal error dialog
Grey-out: `load/MemoryLoaderPanel.java:297-303`, `dump/MemoryDumperPanel.java:281-286`, `memtest/MemoryTestPanel.java:357-364`, `scan/IoPageScannerPanel.java:156-165`, `mmu/MmuPanel.java:224-225`, `simh/SimhConsolePanel.java:187-190`, `macro11/AssemblerPanel.java:426-438`, exec. Never disabled: `mem/MemoryPanel.java:107-123`, `mem/RegisterGroupPanel.java:40-52`, `bits/BitfieldsPanel.java:176-181`

**FIXED** by giving the three holdouts the same connection-listener-driven `updateButtons` the
other eight have, which is the direction the finding asks for: the grey-out is the application's
one answer to "not yet", and eight windows against three is not a tie.

`MemoryPanel` holds its four buttons and the popup's Verify as fields and disables them off
`isConnected()`; Show, `<` and `>` deliberately stay live, because moving the shown range and
re-laying the grid is worth doing with nothing attached, and `applyRange` already examines only
when connected. `BitfieldsPanel` disables Examine and Deposit only - typing bits and reading off
what they mean is the rest of that window and needs no machine, and Enter in the address field
already checked `isConnected()` before examining. `RegisterGroupPanel` had no connection listener
at all: it now has `attach`/`detach` like every other panel, and `RegisterGroupWindow` calls them
from `onShowing`/`onHiding` and detaches on dispose - so it is no longer the one window that
never hears about a connection.

Three tests, one per panel, each failing without the change: the controls are dead on a fresh
panel, arm on `connect`, and go dead again on `disconnect`.

Loader, Dumper, Memory Test, Scanner, MMU, SimH Console, Assembler and Execution disable
their machine-touching buttons when disconnected. The plain Memory window, every Register
Group window and Bitfields never call `setEnabled` at all: clicking Examine/Deposit while
offline falls through `AppContext.onConsole` into `reportFailure("Not connected to a
machine")`, rendered as a **modal error dialog** plus a terminal line
(`MainWindow.java:318-324`). The same gesture greys out in one window and raises a modal
dialog in its sibling. RegisterGroupPanel additionally has no attach/detach and never
reacts to connection changes at all. Fix: give the three holdouts the same
connection-listener-driven `updateButtons` the others have.

### 12. ProgressDialog is single-use but is reused — phase 2 of every two-phase memory test runs with no dialog and cannot be cancelled
`pdp11-ui/.../ProgressDialog.java:52,61-74,92-95,107`; bitten at `memtest/MemoryTestPanel.java:269-296`

**FIXED** in `ProgressDialog`, not in its caller: a `ProgressMonitor` that cannot be used twice
is a broken monitor, and the memory test window is only the caller that noticed. `begin()` now
clears `m_finished`, stops whatever timer the previous phase left armed, and re-arms its own, so
phase 2 gets a dialog and a Cancel exactly like phase 1. A dialog somehow still standing at
`begin()` is relabelled rather than shut and rebuilt.

`m_cancelled` is deliberately **not** reset, and that is now written down on `isCancelled()`:
Cancel means "stop what I asked for", and what was asked for is the whole operation rather than
the phase that happened to be running. Every caller builds a fresh `ProgressDialog` at the point
of the button press, so "this monitor" and "this operation" are the same thing. `MemoryTestPanel`
already stops between phases on `cancelled()`, so the two agree.

The threshold still applies per phase - phase 2 waits its own second before appearing rather than
coming back instantly - which is the existing design, not a leftover: it is what stops a dialog
flashing for work that is over immediately, and a modal dialog flashed at a phase boundary would
be worse than the one-second wait.

`ProgressDialogTest` fails without the change. It runs the whole begin/done/begin sequence inside
one `Edt.run` block, which builds no dialog and needs no display, and which the show timer cannot
fire in the middle of because its action would need the event thread that block is holding.

Each `MemoryTester.testDataLines/testAddressLines/testDataBits` phase calls `pm.begin(…)`
… `finally pm.done()`, and MemoryTestPanel passes **one** ProgressDialog through two
chained phases. `done()` sets `m_finished = true`; `begin()` never resets it (verified),
so the second phase's `showNow()` bails at the `if(m_finished …)` guard. Over a slow
serial line the second — typically longer — phase runs with no progress display and no
Cancel; the UI just sits at "Testing ...". Independently found by two reviewers. Fix:
reset `m_finished` (and decide `m_cancelled` semantics) in `begin()`, or use one dialog
per phase.

### 13. Execution controls set MachineState to RUNNING optimistically with no rollback on failure
`pdp11-ui/.../exec/ExecutionPanel.java:247-260`

**FIXED** by the first of the two suggestions: both now call `running()` from inside the console
job, after the console call has returned. A refusal therefore changes no state at all, so there is
no failure path to restore anything on.

Nothing races it, and that is why this rather than a rollback. A stop the machine reports while
the job is in flight is posted to the *same* command thread by `AbstractConsole.signalExecutionStop`
(it does `c.execute(...)` from the reader thread rather than calling the listener there), so it
queues behind this job and lands after the RUNNING instead of under it. And neither
`resetAndStart` nor `continueCpu` waits for a prompt in any of the three consoles - they write and
return - so RUNNING appears as promptly as it did before. A rollback path, by contrast, would have
had to guess whether the state it captured was still the right one to put back after a disconnect.

`doResetAndSetPc` and `doSetPc` were already doing it this way; these two were the odd ones out.

Two tests. `continuingSaysRunningOnceTheConsoleHasTakenIt` records every state the machine passes
through and asserts RUNNING is among them, since the simulated machine may halt again immediately.
`aRefusedContinueLeavesTheMachineStateAlone` fails without the change: the operator moves the
physical ENABLE/HALT switch back to HALT, which nothing tells the window about, so Continue is
still enabled and ODT refuses it - and the state stays UNKNOWN instead of sticking at RUNNING.

`doResetAndStart`/`doContinue` call `machineState.running()` on the EDT *before* queueing
the console job. If `resetAndStart`/`continueCpu` throws (serial timeout, console
refusal), `onConsole` reports the failure but MachineState stays RUNNING forever: Reset,
Continue, Single step and Set/show all disable (`!running`), the disassembler stops
following, and the only way out is pressing Halt against a machine that never started.
Fix: flip to RUNNING from inside the job after the console call returns, or restore the
previous state on the failure path.

---

## Medium

### 14. `onConsole` can silently drop a job during a disconnect race, leaving panels stuck "working"
`pdp11-ui/.../AppContext.java:227-232`; `pdp11-core/.../console/ConsoleConnection.java:242-247`

**FIXED** on both sides, as suggested. `ConsoleConnection.execute` now returns false when the
executor refuses the task instead of swallowing the rejection, and `onConsole` turns that into the
same `reportFailure` + `false` it already gives for "not connected at all" - so a caller cannot
tell the two apart and does not have to. `MemoryTestPanel` was already written for it: it checks
the return and calls `failed(...)`, which puts its buttons back.

The rejection is still not an exception. A queued job whose connection has gone is an ordinary
outcome of a disconnect landing at the wrong moment, and the reader thread - the other caller of
`execute`, posting execution-stop events - has nowhere to put an exception and nothing that could
act on one.

`AppContextTest.aJobThatCannotBeQueuedIsReportedRatherThanVanishing` fails without the change. It
reproduces the race exactly rather than approximating it: connect, then close the *connection*
while the manager still hands out its console, which is the window between `onConsole`'s null
check and its submit.

`onConsole` null-checks the connection, then calls `connection.execute(…)` — which
swallows `RejectedExecutionException` after close. If a disconnect lands between check and
submit, the job vanishes and `onConsole` returns true; callers that flipped state up front
never hear back (e.g. MemoryTestPanel sets `m_running = true`/"Testing ..." and clears
them only from the job's own callbacks — its buttons stay disabled forever). Fix: have
`execute` report rejection and `onConsole` route it through `reportFailure`.

### 15. Disassembler "catch up on open" never examines: `isShowing()` is always false during `onShowing`
`pdp11-ui/.../disas/DisassemblerPanel.java:231-241,257-268`; `window/ToolWindow.java:91-101`

**FIXED** in the panel rather than in `ToolWindow`, by the first of the two suggestions: there is
now `showPc(pc, examine)` beside `showPc(pc)`, and `attach()` passes `isConnected()`. Moving
`setVisible(true)` before `onShowing()` would have fixed this one window by changing the order every
window's subscribe step runs in, and `onShowing`/`onHiding` pairing up is what that order is for.

The stop listener still asks `isShowing()`, which is the case the flag was written for and where it
is answered correctly: a window that is up and hidden must not spend twenty-one examines per stop.
"Being attached" and "being on screen" are simply not the same question, and the code now asks each
one where it belongs.

`DisassemblerPanelTest.openingItAfterTheMachineStoppedReadsAroundThePc` fails without the change -
it attaches the panel with no frame at all, which is the state every real open is in when
`onShowing()` runs - and `aHiddenWindowStillDoesNotReadOnEveryStop` holds the other half down.

`attach()` (from `DisassemblerWindow.onShowing`) calls `showPc(pc)`, which passes
`isShowing()` as the examine flag — but `ToolWindow.showWindow()` runs `onShowing()`
*before* `setVisible(true)`, so the flag is false on every open. Opening the disassembler
while connected and stopped shows "Nothing has been read from this range yet" (or a stale
listing) instead of reading around the PC; the comment "catch up rather than waiting for
the next stop" describes behaviour the code cannot deliver. Fix: pass an explicit
`examine = isConnected()` from `attach()`, or set visibility before `onShowing()`.

### 16. Pdp11Mmu's listener list is a plain ArrayList mutated on the EDT while iterated on the command thread
`pdp11-core/.../mmu/Pdp11Mmu.java:137,184-195`; used from `pdp11-ui/.../mmu/MmuPanel.java:144-171`

**FIXED**: `CopyOnWriteArrayList`, like every other notification bus in the project. The comment
on the field says which thread each end runs on, because that is what makes the choice obvious
rather than defensive.

`Pdp11MmuTest.aListenerMayComeAndGoWhileTheListIsBeingNotified` fails without the change. It does
the remove-and-re-add from inside the notification, which is the same interleaving the EDT and the
command thread produce and needs no threads to make it happen; a listener behind the mutating one
is what catches it, since a `for` over an `ArrayList` whose size did not change ends quietly if the
mutation happens on the last element.

MmuPanel's `attach()/detach()/rebind()` call add/removeChangeListener on the EDT; the MMU
fires the list on the command thread during every register examine. Hiding/showing/
reconnecting the MMU window while an examine runs can throw
ConcurrentModificationException on the command thread (surfacing as "Reading the MMU
registers failed"). Every comparable bus in the project (MachineState, CellSelection,
MemoryCellGroup, ConnectionManager) uses `CopyOnWriteArrayList`; this one was missed.

### 17. MmuPanel's `m_updatePending` coalescing flag has no happens-before edge — the MMU map can stop refreshing
`pdp11-ui/.../mmu/MmuPanel.java:69,189-197`

**FIXED**: `AtomicBoolean`, with the test-and-set collapsed into one `compareAndSet` so the flag
cannot be observed half-set either.

No test can be made to fail on this reliably - it is a permitted-but-unlikely reordering, not a
sequence of events - and inventing one that passes on this JVM would say nothing about the next.
`MmuPanelTest.everyRoundOfRegisterChangesReachesTheTable` instead pins down the behaviour a stuck
flag destroys: three rounds of register changes, each read back out of the machine, each reaching
the table.

`scheduleUpdate()` tests/sets the plain boolean on the command thread (it is the Pdp11Mmu
change listener); the queued EDT runnable clears it. Per the JMM the command thread may
keep seeing a stale `true` and never post another redraw — the panel silently stops
following register changes. Independently found by two reviewers. Fix: `AtomicBoolean`.

### 18. Bitfields never opts out of `pdpOverwritesEdit`, so any other window's examine wipes the user's in-progress bit edits
`pdp11-ui/.../bits/BitfieldsPanel.java:115-120,496-499`

**FIXED** by the first of the two suggestions - opt out while the cell is edited, not
permanently - which is what `MemoryCellGroupTable.updateOverwritePolicy` does for the grids and for
the same reason: with nothing being composed there is nothing to protect, and a Bitfields window
showing the PSW should follow the PSW like any other view.

The policy is set in `updateValueColour()`, which is not where a reader would look for it and is
therefore documented there. It is the one place every path that can change the edit value already
ends - typing the word, typing a field, being shown a cell, being pointed at an address, and
refreshing after an examine or a deposit - and the alternative was the same line repeated at five
call sites with nothing to keep the sixth from forgetting it.

`BitfieldsPanelTest.anExamineElsewhereDoesNotWipeTheBitsBeingComposed` fails without the change,
and goes on to check the other half: once the composition has been deposited the window tracks the
machine again.

The panel's one-cell group keeps the default `pdpOverwritesEdit = true`, and its cell
listener does `cell.setEditValue(cell.getPdpValue())`. While the user composes a register
value bit by bit (the window's whole purpose), an examine of the same address from any
other window propagates the machine value in and the listener silently overwrites the
composition. Every other edit-holding view protects itself. Fix: opt out while
`m_cell.isEdited()` (mirroring `updateOverwritePolicy`), or permanently.

### 19. The dumper's connection listener discards a captured dump on width change — including plain disconnect from a 16/18-bit machine
`pdp11-ui/.../dump/MemoryDumperPanel.java:302-311`; contrast `load/MemoryLoaderPanel.java:313-322`

**FIXED** by copying the Loader's guard, comment and all: the range is only re-expressed while
the group is empty. The dumper's group starts empty, so connecting to a 16 or 18-bit machine still
moves it to that machine's width; it stops being empty the moment something has been read, which is
exactly when there is something to lose.

That leaves a dump read from a 16-bit machine sitting at 16-bit addresses after connecting to a
22-bit one, which is the Loader's behaviour too and is the right way round: what is in the grid was
read from a particular machine, and silently re-labelling it as belonging to a different one would
be worse than leaving it alone. Reading a new range re-establishes the width.

`MemoryDumperPanelTest.disconnectingFromAnEighteenBitMachineDoesNotThrowTheDumpAway` fails without
the change. The existing "written after the machine has gone" test could not catch this: it uses
SimH, which is 22 bits, so the fallback width matched and nothing was thrown away.

The listener does `getGroup().shiftRange(…)` whenever the address type differs, with no
`isEmpty()` guard. `addressType()` falls back to PHYSICAL22 with no console, so
disconnecting from a 16-bit ODT machine destroys the words just read — the very data
`updateButtons()` promises survives ("a dump read earlier can be written after the machine
has gone away"). The sibling MemoryLoaderPanel guards the same operation with
`&& getGroup().isEmpty()` and a comment explaining why. Fix: copy the loader's guard.

### 20. DisassemblyListing: PC in range with an unread word silently discards every line before the PC
`pdp11-core/.../disas/DisassemblyListing.java:112-123`

**FIXED** as suggested: the listing built at the requested start is kept, and returned when the
realignment loop reaches the PC without ever landing on a line. `pcLine()` is then -1 and
`startAddress()` is where the caller asked, which is what the class Javadoc has always promised for
a PC it cannot find.

The loop is otherwise unchanged - a PC genuinely inside an instruction still moves the start, which
is the case it exists for.

`DisassemblyListingTest.aPcInRangeWhoseWordWasNeverReadKeepsTheLinesBeforeIt` fails without the
change: it shows a sparsely examined range whose PC word alone was never read, and asserts the
lines before the PC are still there.

The realignment loop advances `from` by 2 until `pcLine >= 0` or `from >= pc`. If the PC
lies inside the range but its own word was never examined, `atPc` can never be set, so the
loop walks `from` up to `pc` and returns the listing built from `pc` onward — every valid
line between `start` and `pc` vanishes and `startAddress()` reports `pc`. Contradicts the
class Javadoc ("Then pcLine() is -1 and the listing starts where it was asked to").
Scenario: sparsely examined 001000-001777, PC=001400 unread → window shows only 001400+
with no PC mark. Fix: when the loop ends without finding the PC, return the *first*
listing. The test covers PC-outside-range but not this case.

### 21. MemoryFileLoader: an out-of-width address aborts the whole "forgiving" load with an uncaught IllegalArgumentException
`pdp11-core/.../memfile/MemoryFileLoader.java:170-180,331-337`

**FIXED**, as suggested: bounds-checked and turned into warnings. `loadText` skips a value whose
address does not fit the group and counts it into a warning rather than letting `Address.of`
throw out of a load documented as forgiving; the two byte formats check how many words fit
*before* `clear()` and `shiftRange()` rather than throwing from inside the rebuild with the group
half filled. The over-wide *value* is still masked - the Pascal masks it and a file full of
seven-digit words is a real thing - but it says so now, which was the inconsistency. The paper
tape path needed nothing: its addresses come out of a 16-bit buffer and fit every width.

`loadText` catches only `NumberFormatException` around the address parse; `addCell` then
calls `Address.of(type, value)`, which throws `IllegalArgumentException` for a value wider
than the group (e.g. a pasted `7777777:` line against a 16-bit start address). A format
documented as forgiving (skips junk, collects warnings) instead aborts with a raw runtime
exception, losing all warnings and everything loaded so far. Same class of failure in
`loadByteStream`/`loadSplitBytes` when `startAddr + 2*words` runs off the top of the
address space (IAE mid-rebuild, group left partially filled). Also inconsistent: an
over-wide *value* is silently masked `& 0xFFFF` (line 177) while a non-octal value gets a
warning. Fix: bounds-check and convert to warnings/IOException.

### 22. Default-locale case conversion breaks machine descriptions and mnemonics under a Turkish locale
`pdp11-core/.../machine/MachineDescription.java:119-121`; also `disas/DecodedInstruction.java:27`, `bits/BitfieldsDefs.java:46,55`, `machine/IniFile.java:81-85`

**FIXED**: `Locale.ROOT` on every case conversion in the module, and on every `String.format`
with a digit conversion - several locales do not write `0` as `'0'`, which is the same bug with a
different letter. Two more sites turned up doing it that the review had not listed
(`MemoryTester.log`, `Logger.log`), because the fix is an ArchUnit rule rather than a sweep:
`DefaultLocaleTest` refuses `String.toUpperCase()`/`toLowerCase()` and
`String.format(String, Object...)` anywhere in `pdp11-core`, with the machine-description,
mnemonic and hex cases exercised under `tr-TR` beneath it so the rule is visibly guarding
something.

`isBitsSection` does `name().toUpperCase().startsWith("BITS.")`; under `tr-TR`,
`"Bits.…".toUpperCase()` yields `BİTS.…` (dotted İ) and every `[Bits.*]` section loads as
a bogus device group while no register gets named bits. `DecodedInstruction.text()`'s
`toLowerCase()` turns `"INC"` into `"ınc"`, breaking display and the byte-identical diff
against the Pascal. Fix: `Locale.ROOT` on every case conversion in the core (the codebase
already does this correctly in `Macro11.isWindows()`).

### 23. Pdp1144Console.makePrompt: non-atomic `size()`/`get(size-2)` can throw on the reader thread and tear down the connection
`pdp11-core/.../console/Pdp1144Console.java:268-275`

**FIXED** as suggested, as `AnswerCollector.getFromEnd(int)` - "the phrase n places from the
end", which is the operation `makePrompt` actually wanted, done under one lock. A race test
hammers `publish`/`getFromEnd` against `clear` on two threads.

Both calls are individually synchronized, but a command thread calling `clearAnswers()`
(every command does, without `m_decodeLock`) between them shrinks the list and `get`
throws `IndexOutOfBoundsException` on the reader thread; `readerLoop` treats any throwable
as transport failure and reports "Console connection lost", killing a live connection.
`OdtConsole`/`SimhConsole` use the null-safe `getLast()` and cannot crash this way. Fix:
add a synchronized `getFromEnd(int)` to AnswerCollector.

### 24. Scanner buffer read without the decode lock while the reader thread appends
`pdp11-core/.../console/OdtConsole.java:506`, `SimhConsole.java:644`

**FIXED**: `AbstractConsole.getUnconsumedInput()` takes the decode lock, `checkPromptAfter` uses
it too, and the two offending sites go through it. An ArchUnit rule in `LayeringTest` makes it
the only caller of `ConsoleScanner.getInput` - by target *owner* rather than by name, because
every scanner is a subclass and a call through `OdtScanner` is a call to `OdtScanner.getInput` as
far as the bytecode is concerned.

`OdtConsole.deposit` and `SimhConsole.enterMultipleCommandMode` build a
`NoConsolePromptException` from `m_scanner.getInput()` directly; the buffer is a plain
StringBuilder appended under `m_decodeLock` by the reader thread, and
`AbstractConsole.checkPromptAfter` takes that lock for exactly this read — these two sites
do not. A concurrent append during `toString()` can yield corrupt diagnostics or an
ArrayIndexOutOfBoundsException on the command thread. Fix: a locked accessor.

### 25. `haltCpu` on an already-halted V3.40C 11/44 throws "no answer" after a ~1 s stall
`pdp11-core/.../console/Pdp1144Console.java:699-717`

**FIXED** as suggested: `haltCpu` now waits for the stop report *or* the prompt, and a prompt
arriving first means there was nothing to stop, so it returns null per the interface. Note the two
firmwares genuinely differ and the test says so - the classic console answers `H` with an ordinary
stop report even when already halted, so only V3.40C returns null.

The `Console.haltCpu` contract says return null for "a machine that had already stopped",
and SimhConsole does. On V3.40C firmware `H` when halted draws `?Already halted` and no
stop report, so `waitFor(Halt, …)` times out and the code throws "Stopping the CPU failed:
no answer". The execution window calls halt unconditionally, so every redundant Halt click
surfaces a spurious error. Fix: recognise the no-Halt-but-prompt outcome and return null.

### 26. SimhConsole.continueCpu clears neither the execution-stop state nor a pending silent halt
`pdp11-core/.../console/SimhConsole.java:1083-1089`

**FIXED** by mirroring `resetAndStart`, as suggested. The test drives a silent halt, continues,
and asserts the scheduled resolution fires without sending anything - it used to send `E PC` at a
running machine.

`resetAndStart` does `m_silentHaltPending = false; clearExecutionStop();` and both other
consoles' `continueCpu` call `clearExecutionStop()`; SimhConsole's does neither. Stale
stop state survives into RUNNING, and a silent-halt resolution scheduled just before
Continue still fires — running `E PC` against a running machine, stalling the command
thread up to the 8 s timeout and logging a bogus failure. Fix: mirror `resetAndStart`.

### 27. Pdp1144Console commits `m_lastDepositAddr` before the deposit is confirmed — a failed deposit poisons the next `D +`
`pdp11-core/.../console/Pdp1144Console.java:415-432`

**FIXED** by the first of the two suggestions: the field is cleared before the command goes out
and set only after `checkPrompt` has confirmed it. The test needs a line that goes quiet
mid-conversation, so the 11/44 rig's transport can now be made deaf.

Line 427 sets `m_lastDepositAddr = physical` before `checkPrompt` confirms the command was
processed. If the prompt never comes and the caller carries on after catching the
exception, the next sequential deposit goes out as `D + value` — but the machine's own
last-address was never advanced, so the value lands at the wrong location silently. Fix:
set the field only after `checkPrompt` succeeds (or clear it on the failure path).

### 28. SimH launch-failure path leaks the remote-channel socket and discards the one diagnostic that explains the failure
`pdp11-core/.../io/SimhProcessTransport.java:389-405`

**FIXED**, all three parts: the drain starts immediately after `pb.start()`, the already-open
telnet is closed on the failure path, and `getProcessOutput()` is appended to the thrown message
after a short join on the drain thread - the process has just been killed, so waiting briefly is
what turns block-buffered output that has not arrived yet into output that has. The test stands a
shell script in for SimH: it prints a complaint and never listens.

If the remote channel connects but the console channel's `connectWithRetry` fails, the
catch destroys the process but never closes the already-open telnet transport (socket
leaks until GC). Worse, `startOutputDrain()` runs only after both connects succeed, so on
any launch failure SimH's stdout — which the field comment calls "the only diagnosis
available when a launch goes wrong" — is never read and never reaches the thrown
TransportException. Fix: close the telnet in the catch, start the drain right after
`pb.start()`, and append `getProcessOutput()` to the failure message.

### 29. ~180 lines of bulk-examine machinery duplicated near-verbatim between SimhConsole and Pdp1144Console
`pdp11-core/.../console/SimhConsole.java:805-1010` vs `Pdp1144Console.java:438-652`

**FIXED** as suggested. `AbstractConsole` holds `ExamineItem`, `runExamineList`,
`examineAddrList`, `collectBlock`, `anyUnanswered` and the `MAX_EXAMINE_BLOCK_LEN` both consoles
had picked independently; a `BulkExamineProtocol` supplies the two things that genuinely differ -
how a dialect phrases one block, and how it sends it (the 11/44 clears and writes, SimH sends and
answers from after its own echo). Each console is left with the classification loop, which is the
part that is really different. The block timeout now comes from `getCommandTimeoutMillis()` rather
than each console's own constant, which is the same value both set. `toPhysical` moved up too, and
the R0/R7/PSW offsets are `addr/CpuRegisters.java`, used by both consoles and both fakes.

`ExamineItem`, `runExamineList` (character-for-character identical), `anyUnanswered`,
`collectBlock` and the two-list `examine(MemoryCellGroup…)` bodies are copies; the
"no progress this pass" hardening has already been applied twice. Extract a shared helper
in AbstractConsole parameterised by the block-command builder. Smaller duplicates:
`toPhysical` (OdtConsole:443 / Pdp1144Console:144) and the R0/R7/PSW offset constants
repeated across OdtConsole, SimhConsole, FakePdp11 and FakeSimh.

### 30. Command-thread iteration of the live cell list races EDT `shiftRange` *(needs confirmation of a practical window)*
`pdp11-ui/.../mem/MemoryCellGroupTable.java:279-291` (same pattern `MemoryCellGroupList.java:242-254`)

**FIXED**, both halves, in `MemoryCellGroupTable` and `MemoryCellGroupList`. The job takes
`List.copyOf` of the cells, and `MemoryCellGroup.holdsExactly(List)` says whether the group is
still the one that was read - a group re-ranged mid-job is stale and its answers are not written
back. The related point too: the post-loop copies the machine value over the edit value only for
cells the machine actually answered about, and not at all after a cancel, so a word at a
nonexistent address no longer replaces something typed with `UNKNOWN`.

The practical window the finding asked about was not reproduced deterministically - it needs the
EDT to shift the range inside a live examine - so what the tests hold down is the mechanism:
`ShiftRangeTest` shows the live view throwing `ConcurrentModificationException` across a
`shiftRange` and the copy surviving it, and `MemoryPanelTest` covers the edit-wiping half end to
end against a simulated machine.

`examineAll`'s job iterates `group.getCells()` — an unmodifiable *view* over a plain
ArrayList — on the command thread, while the EDT can call `shiftRange`/`clear` (Show,
`<`/`>`, connection events) → ConcurrentModificationException surfacing as "Examining
memory failed". The core consoles defensively `List.copyOf`; the UI-side post-loop does
not. The modal ProgressDialog narrows but does not close the window (it appears only
after 1 s, and jobs can queue behind others while the UI is free). Fix: snapshot with
`List.copyOf` in the job and treat a group whose range changed mid-job as stale. Related:
the post-loop `setEditValue(getPdpValue())` also runs on cancel and for never-examined
cells, wiping typed edits the examine never touched.

### 31. WindowManager's dispose path skips `onHiding()` — a window disposed while visible leaks its subscriptions
`pdp11-ui/.../window/WindowManager.java:154,168`

**FIXED** as suggested: `ToolWindow.dispose()` is overridden and unsubscribes first. It is
driven by an `m_subscribed` flag rather than by `isVisible()`, so the pair is a pair whichever
way the window leaves the screen and cannot run twice - `hideWindow()` then `closeAll()` still
produces one `onHiding`. `RegisterGroupWindow` had already worked around this with a `dispose()`
of its own - it is the one window type `closeAll(WindowType)` is called on - and that override is
removed: it is the framework's job now.

`closeAll()`/`closeAll(WindowType)` call `w.dispose()` directly; `onHiding()` — the
documented unsubscribe point — runs only from `hideWindow()`. Today the damage is limited
(shutdown exits anyway; REGISTER_GROUP windows disposed on machine-description reload
happen to subscribe only to the group being discarded), but the framework invariant
"onShowing/onHiding pair up" is broken on this path, and the first listener-carrying
window added to a dispose flow will stay subscribed to ConnectionManager/MachineState as a
dead frame forever. Fix: run `onHiding` from an overridden `dispose()` when attached.

### 32. Five hard-coded colours in the terminal violate "Only UiColors names a colour"
`pdp11-ui/.../terminal/GlassTerminalView.java:60,61,84,85,86`

**FIXED**: all five are `UiColors.TERMINAL_*` now. The rule itself is what was really missing,
so `UiColorsTest` refuses any `new Color(...)` outside `UiColors` anywhere in pdp11-ui - the same
shape as pdp11-core's `LayeringTest`, and it needed archunit as a test dependency of that module.
`Pdp11Gui`'s javadoc had been asserting this was already true.

Background (0x121214), caret (0xE0E0E0) and the three semantic stream colours (PDP
0xD8D8D8, USER 0x7FC7FF, SYSTEM 0xB09050) are named inline — the stream colours are
exactly the "means something" kind CLAUDE.md says must live in UiColors, and `Pdp11Gui`'s
javadoc even asserts "nothing else in the UI names a colour". Verified the only violation
in pdp11-ui/pdp11-app. Fix: move all five to UiColors.

### 33. Window visibility is saved on every hide/quit and never restored — the tool-window layout does not survive a restart
`pdp11-ui/.../window/WindowManager.java:202-213`, `settings/WindowGeometry.java:19`, `window/ToolWindow.java:103-108`

**FIXED** by restoring it, which is what the Delphi original does and what the record was always
for. `WindowManager.restoreVisibleWindows()` runs after the main window is on screen; a saved
entry that cannot be understood or cannot be built is one window skipped and logged, never a
failed startup - a register-group window whose device the loaded description no longer declares
is the case that actually happens. All four sub-points went with it: `hideWindow` now saves
*after* `setVisible(false)` so a user-closed window records `visible=false`, the main window has
geometry persistence under its own key, `WindowKey.fromStorageKey` is the way back from the
settings file, and `WindowType.TERMINAL` is gone - it was registered nowhere and could only ever
have thrown "No window is registered for TERMINAL".

`rememberGeometry` persists `isVisible()` into `WindowGeometry.visible`, but nothing ever
reads `.visible()` — every launch opens only the main window, while the settings file
records exactly what the layout was (the Delphi original restores it). Also `hideWindow`
saves *before* `setVisible(false)`, so a user-closed window is recorded `visible=true`
(corrected only by the quit-path resave), the main window itself has no geometry
persistence at all, and `WindowType.TERMINAL` is registered nowhere (dead constant).
Either restore visibility on startup or stop recording it.

### 34. Vocabulary drift for the two core actions: four names for "read from the machine", and the assembler renames/reorders deposit
"Examine all"/"Examine cell" (`mem/MemoryPanel.java:106-110`, scan, register groups), "Read from machine" (`dump/MemoryDumperPanel.java:71`), "Read the MMU registers" (`mmu/MmuPanel.java:56`), "Examine" (`bits/BitfieldsPanel.java:176`)

**FIXED**. **Examine** wins for reading the machine, being what most windows already said and
what the PDP-11 console itself calls it: "Read from machine" (Dumper) and "Read the MMU
registers" (MMU) are both "Examine all" now, and Bitfields names its scope - "Examine cell",
"Deposit cell" - instead of leaving it to be guessed. "Examine register" is "Examine cell", the
majority of three. The Assembler's code tab stops renaming and reordering deposit: "Load into
machine" is "Deposit all" and sits after "Deposit changed", like every other window.

`VocabularyTest` is what keeps it: it lays out nine panels headlessly, collects every button, and
asserts that reading the machine is called examining everywhere, that the scope has exactly two
names, and that deposit-changed comes before deposit-all. This is the kind of thing that can only
otherwise be caught by opening nine windows and reading them side by side.

Deposit is likewise split: "Deposit all"/"Deposit changed" everywhere except the Assembler
code tab, where deposit-all is renamed "Load into machine" (`AssemblerPanel.java:114`) and
sits *before* "Deposit changed", while every other window orders
examine → deposit-changed → deposit-all. "Examine cell" vs "Examine register" is a smaller
instance. One vocabulary and one ordering should win.

### 35. Enter in an address field acts in five windows and does nothing in two
Works: `mem/MemoryPanel.java:126-127`, `disas/DisassemblerPanel.java:128-129`, `dump/MemoryDumperPanel.java:124-125`, `memtest/MemoryTestPanel.java:151-152`, `bits/BitfieldsPanel.java:163-166`. Dead: `exec/ExecutionPanel.java` (Start PC / Current PC fields), `load/MemoryLoaderPanel.java` ("Load at:")

**FIXED** as suggested. The Loader's "Load at:" reads the file, which is the button beside it and
touches no machine. Execution's two are less obvious and are not the same as each other: Current
PC writes R7, which is exactly what Set PC does; Start PC publishes to `MachineState` rather than
resetting, because the button beside *that* one resets a machine and a keystroke in a text field
should not.

Same widget shape, different Enter contract: in the Dumper, Enter even immediately reads
the machine; in Execution the user must find the "Set/show" button. Add ActionListeners to
the two holdouts.

### 36. Execution window: "Reset" and "Set/show" labels do not describe what the buttons do
`pdp11-ui/.../exec/ExecutionPanel.java:61,71,235-245,339-347`

**FIXED**: "Reset and set PC" and "Set PC". A button named after half of what it does was
silently writing a register, and the "show" half of the other one happens in a different window.

"Reset" also deposits the Start PC field into R7 (`doResetAndSetPc` — the docstring admits
the deposit happens "whether or not" the console needs it), i.e. it silently writes a
register. "Set/show" only ever *sets* the PC; the "show" half refers to the disassembler
jumping via MachineState, invisible from this window. Beside "Reset and start",
"Continue", "Single step" — all accurate verbs — these two are the odd ones out.

### 37. The Memory window shows its word count as octal in the field and decimal in the status line
`pdp11-ui/.../mem/MemoryPanel.java:81,103-104,209` vs `:263-269`

**FIXED** by making the field decimal, which is the direction the finding argues: it is a count,
like every other count the application prints, and the status line two rows below has always
printed this same quantity in decimal. The address beside it stays octal, because an address is.
The existing test asserting the octal behaviour said "the word count is octal, like everything
else" - true of a PDP-11's addresses and values, not of this program's counts - and is updated
with the reason.

The "Words:" field is parsed and re-written as octal (default 64 displays as "100")
with nothing labelling it octal, while `updateInfo()` two rows below prints the same
quantity as decimal ("64 words from ..."). Every other count in the app (test results,
loader status, scanner status) is decimal.

### 38. Verify is a hidden right-click item in the Memory window and a first-class button in its siblings
`pdp11-ui/.../mem/MemoryPanel.java:138-140,235-242` vs `load/MemoryLoaderPanel.java:75,131`

**FIXED**: it is a button on the Memory window's toolbar, beside Deposit all, like the Loader's
and the Assembler's. The context menu keeps the three items that have no button anywhere - Clear
data, Fill data with address, Export as SimH DO script.

"Verify against the machine" exists only in an undiscoverable grid context menu (with
"Clear data", "Fill data with address", "Export as SimH DO script ..."); Loader and
Assembler expose Verify as a toolbar button. No other window has a context menu at all, so
the only right-click surface in the application hides functionality its neighbours put on
buttons.

### 39. Three policies for whether opening a data window reads the machine
`mem/RegisterGroupWindow.java:37-41` (auto-examine on *first* show only), `mmu/MmuPanel.java` (never auto-reads), Memory windows (examine on Show/Enter only)

**FIXED** by picking the first: **a data window that is shown against a live machine reads it,
and reads it again when a machine arrives.** Register Group moves off `onFirstShow` (so a
reconnect to a different machine can no longer leave the previous one's values on screen), the
MMU window stops opening on a map built from registers nobody examined, and the Memory window
reads its unknown cells - unknown only, which is what keeps showing an already-read range free.

Note this changed several MMU tests, and the change is the point: they poked values into the
register cells straight after `attach()`, and the window's own read now lands on top of them.
They wait for it now.

A Register Group window examines the whole device as a side effect of first open — and
never again on later shows or reconnects, so stale values display silently after
reconnecting to a different machine. The MMU window opens showing a map built from
unexamined registers and waits for its button. Pick one policy (and refresh register
groups on reconnect).

### 40. Typed-input errors have three different surfaces, and the default is a modal dialog per typo
Modal: every octal `parse` failure in Memory/Disassembler/Execution/Dumper/Loader/MemTest/Bitfields routes through `reportFailure` → `JOptionPane` titled "PDP11GUI" (`MainWindow.java:318-324`). Inline status: `macro11/AssemblerPanel.java:66-69` (which explicitly argues against dialogs — "one keystroke of penance per typo"). Silent keystroke rejection: `numbers/NumberDocumentFilter.java`, `mem/OctalCellEditor.java:23-32`

**FIXED** the way the assembler's own comment argues, across all seven windows: a value that
cannot be parsed is reported in the window's status line, in `UiColors.ERROR_TEXT`, and clears
itself when the window next says anything about itself. The modal dialog stays for what it is
for - something that went wrong out in the world, which the user did not cause and cannot
otherwise see: a command the machine refused, a file that would not open.

`FieldStatus` is the shared piece, so the convention is one class rather than the same four lines
in seven panels. The keystroke-rejecting filters (`NumberDocumentFilter`, `OctalCellEditor`) are
left alone: they are on grid cells and value fields where every character is checkable as it is
typed, which is a different situation from an address field that is only wrong once it is
complete.

Field-level validation deserves one convention; the modal dialog is the most hostile of
the three and is the default. The assembler's own comment is the design argument for
fixing the rest.

### 41. Settings dialog ignores dialog conventions, and Close ≠ titlebar X
`pdp11-ui/.../settings/SettingsDialog.java:26-66`, `settings/ConnectionSettingsPanel.java:272-282`

No default button (Enter does not Connect), no Escape binding (non-standard for a modal
dialog), and asymmetric close paths: the "Close" button calls `saveSettings()` (persisting
profile edits) while the title-bar X (`DISPOSE_ON_CLOSE`) skips the save — the same
gesture-pair silently decides whether edits reach disk. "Delete" removes a saved profile
with no confirmation. ProgressDialog likewise has no Escape→Cancel.

### 42. Connect/Disconnect menu items ignore connection state; connecting has no busy affordance and no cancel
`pdp11-ui/.../MainWindow.java:91-103,240-260`

Both items are always enabled: Disconnect while disconnected silently appends
"[disconnected]"; a second Connect during a slow connect spawns another worker (see issue
1 for what that races). Feedback is a status-bar "Connecting…" and a terminal line — every
*other* long operation gets a ProgressDialog with Cancel.

### 43. Escape in the Number Converter silently zeroes the value
`pdp11-ui/.../numbers/NumberConverterPanel.java:162-170`

Window-wide Escape is bound to "clear to 0". Everywhere else Escape does nothing or is
Swing's cancel-cell-edit; here the same key destroys the number being inspected with no
feedback and no undo. A Pascal carry-over that now collides with normal Escape habits.

### 44. Settings object is mutated from the connect worker while the EDT reads and saves it
`pdp11-ui/.../MainWindow.java:250-252`

The worker calls `getSettings().setLastProfileName(...)` after connect; the EDT mutates
the same unsynchronized Settings (profiles, geometry) and Gson-serializes it in `save()`.
A benign-looking data race today; marshal the post-connect write to the EDT.

### 45. Listener exceptions during connect can convert a successful connection into FAILED
`pdp11-core/.../conn/ConnectionManager.java:173-180`

`setState` iterates listeners unguarded on the connecting worker; one RuntimeException
from an arbitrary panel lambda propagates into `connect()`'s catch, closes the just-built
connection, reports FAILED, and skips the remaining listeners. Also, `m_console`/
`m_connection` are published before `init()` runs (`:223-224`), so observers can reach a
console mid-handshake while state is CONNECTING; and `catch(ConsoleException |
RuntimeException)` misses `Error`, leaking an open transport on that path. Fix: per-
listener try/catch in `setState`; publish the fields only on success.

---

## Low

### 46. Stop event can be lost when `clearAnswers()` races the halt-then-prompt decode pair
`pdp11-core/.../console/OdtConsole.java:370-375`, `SimhConsole.java:466-482`, `Pdp1144Console.java:268-275`

All three decoders detect a stop by finding the preceding `Halt` from the prompt;
`clearAnswers()` synchronizes only on the collector, not `m_decodeLock`, so it can run
between the Halt's publish and the prompt's decode — the prompt then finds no Halt and
drops the stop (SimH partially recovers via the silent-halt path; ODT and 11/44 do not).
Millisecond window requiring a command issued exactly as the machine stops. Fix: snapshot-
and-clear under the decode lock.

### 47. SimH echo-fallback can misreport a successful command as rejected *(needs confirmation)*
`pdp11-core/.../console/SimhConsole.java:560-571`

When `sendCommand` misses the echo (returns -1), `checkPromptNoOutput(-1, …)` scans from
position 0 and treats any non-blank `OtherLine` — including the command's own late echo —
as a rejection. Requires the echo to arrive after the full timeout but before the prompt
check. Fix: exclude a line equal to the command, as `command()` (`:715`) already does.

### 48. Halt can queue up to 8 s behind an in-flight sim> command
`pdp11-ui/.../simh/SimhConsolePanel.java:236-244`; `SimhConsole.CMD_TIMEOUT_MS = 8000`

A user command like `go` holds the command thread waiting for a prompt that will not come
while the simulation runs; Halt — the control whose purpose is interrupting exactly that —
waits behind it. The Pascal writes ^E out-of-band for this reason. Consider writing the ^E
byte directly to the transport and letting the queued `haltCpu` do the bookkeeping.

### 49. TOCTOU on the live console across the EDT→command-thread boundary
`pdp11-ui/.../simh/SimhConsolePanel.java:216-228`, `mmu/MmuPanel.java:296-310`

`SimhConsolePanel.submit` checks `simh() != null` on the EDT but the job casts
`((SimhConsole) console)` on the command thread — a reconnect to a non-SimH machine in
between yields a ClassCastException surfaced as a confusing failure dialog. MmuPanel's
refresh captures the old Pdp11Mmu/register group on the EDT; after a reconnect the job
examines an evicted group against the new console and the results go nowhere. Both self-
heal on the next click; both are cured by re-resolving from the console argument inside
the job.

### 50. Bitfields examine/deposit jobs read `m_cell` at execution time, not capture time
`pdp11-ui/.../bits/BitfieldsPanel.java:445-476`

The queued job captures the local `addr` but writes results through the `m_cell` field; if
the user re-points the panel between queueing and execution, the old address's value is
written into the new cell. Every other panel captures the cell object. Fix: capture
`m_cell` into a local before `onConsole`.

### 51. Log window attach has a snapshot-to-subscribe gap; UiLogger delivers outside its lock
`pdp11-ui/.../log/LogPanel.java:93-97`, `log/UiLogger.java:63-75`

A line logged between `snapshot()` and `setListener(…)` is buffered but never shown until
reopen; and `UiLogger.log` invokes the listener after releasing the monitor, so concurrent
loggers can deliver out of buffer order. `TextChannel` solved exactly this (replay and
subscribe under one lock — its javadoc names the gap); UiLogger should do the same.

### 52. `FakePdp11.m_runMode` is written without the fake's monitor
`pdp11-core/.../fake/FakePdp11.java:96,172-174`; `conn/ConnectionManager.java:295-301`

`setSimulatedRunMode` writes the plain boolean from an arbitrary thread while keystroke
handlers read it under the fake's monitor — no happens-before edge, so the fake may
lawfully keep seeing the old RUN/HALT switch position. Make it synchronized (the class
convention) or volatile.

### 53. FakeTransport.delay() sleeps while holding the fake's monitor
`pdp11-core/.../io/FakeTransport.java:141-189`

With `byteDelayMillis` set, both directions and the scheduler's run-to-halt callback block
behind one direction's delay — a real serial line delays only its own direction. Off by
default; move the sleep outside the lock if the feature is ever used interactively.

### 54. Command-timeout setter is honoured by only half of each exchange
`pdp11-core/.../console/AbstractConsole.java` (`setCommandTimeoutMillis`) vs hard-coded `CMD_TIMEOUT_MS` at `SimhConsole.java:767`, `OdtConsole.java:470,503`, `Pdp1144Console.java:392,588`

`checkPromptAfter` and SimhConsole's `sendCommand`/`command()` use the getter; the
examine/deposit/step waits in all three consoles hard-code the static constant. Changing
the timeout via the setter therefore half-works. Use the getter throughout.

### 55. Dead code and dead plumbing (core)
- `AbstractConsole.waitForAnswer` (`:208`): no callers; the position-free form invites the
  stale-prompt bug the design fixed. Delete or annotate.
- `ConsoleScanner.take()/peek()` (`:278-293`): used by no decoder.
- `MemoryCellGroups.changeAddressWidth` chain (`:187-207`, `MemoryCellGroup.java:303-330`):
  fully ported, zero callers; `Pdp11Mmu`'s constructor doc relies on the call that never
  happens (`getPhysicalAddressType()` is therefore always PHYSICAL22 — numerically
  consistent with the Pascal, but the doc is wrong). CLAUDE.md itself says the routine "is
  not ported and is not needed". Delete or fix the docs; also note `isMapping22Bit()` is
  parsed but never affects `translate()` (same as the Pascal — worth a comment).
- `MemoryCell.assignFrom` (`:130-142`): public, dead, and rewrites the address without
  reindexing — a standing invariant violation awaiting its first caller.
- `Macro11.Run.timedOut` (`:66-70,246`): always false — a timeout throws before any Run is
  built. Remove or record the timed-out run.

### 56. OdtScanner honours `raiseIncompleteOnEof=false` for only one of its three incomplete-input throws
`pdp11-core/.../console/OdtScanner.java:442-470`

Octal digits running to buffer end (`:465`) and a trailing `R`/`$` (`:470`) still throw
unconditionally, against the `ConsoleScanner.nextSymbol` javadoc. Currently harmless (the
only `false` caller is `clear()` on an empty buffer); tighten the javadoc or honour the
flag.

### 57. M4 layer: doc/behaviour mismatches
- `M4Evaluator.java:14-18`: Javadoc claims bitwise and comparison operators are
  implemented; the grammar has only `+ - * / %`, unary `+ - ~`, parentheses. The failure
  is loud, but the comment misleads the next machine-description author.
- `M4Preprocessor.java:222-231`: `eval(expr, radix, width)` accepts the width argument and
  silently ignores it (GNU m4 zero-pads) — the "silently wrong I/O page" failure mode the
  class's own doc says it exists to prevent; the radix parse can also throw a bare
  NumberFormatException instead of M4Exception. Minor: `m_expansions` is cumulative across
  runs of a reused instance.

### 58. Paper-tape loader: the entry block's checksum byte is never verified and is re-scanned as data
`pdp11-core/.../memfile/MemoryFileLoader.java:270-283`

A zero-data entry block jumps straight back to state 0, so its checksum byte is re-parsed
as a potential header; when that byte happens to be 01 (entry addresses whose bytes sum to
248 mod 256, e.g. 000370) the following bytes are misparsed and a spurious "Skipped a
block ..." warning appears on a good tape. Data still loads correctly. Add a state that
consumes and verifies the entry-block checksum.

### 59. `Macro11Listing.listingLineOfAddress` can throw instead of returning its documented -1 *(needs confirmation)*
`pdp11-core/.../macro11/Macro11Listing.java:169-177`

For an address of a different type it re-types by raw value with `Address.of`, which
throws when the value exceeds the group width (e.g. an I/O-page physical PC against the
VIRTUAL code group); it would also silently match the wrong virtual cell rather than
rebasing. Guard and return -1.

### 60. FakePdp1144V340c global-register bound disagrees with its own comment *(needs confirmation)*
`pdp11-core/.../fake/FakePdp1144V340c.java:189-193`

Comment says "Sixteen global registers exist; a higher number is a firmware complaint",
but the guard fires only above 32, so registers 16..32 skip `?Too big` and draw a bus-
timeout instead. If that is faithful to the real firmware, say which in the comment; if
not, the bound is wrong (or octal/decimal got confused).

### 61. JTable minimum-width rule and window minimum sizes applied inconsistently
- `mem/MemoryCellGroupTable.java:182-189` and `log/LogPanel.java:63-66` set only preferred
  column widths, against CLAUDE.md's unconditional "set both" (mitigated today by
  AUTO_RESIZE_OFF; a resize-mode change regresses silently). Every other table sets both.
- MMU, Microcode, Log, SimH Console, Number Converter and Execution windows set no
  `setMinimumSize` while the other eight windows do, so those six can be squashed into
  unusable slivers (`MmuWindow.java:19-25`, `MicrocodeWindow.java:16-23`,
  `NumberConverterWindow.java:21-26`, `ExecutionWindow.java:17-22`, `LogWindow.java:24`,
  `SimhConsoleWindow.java:25`).

### 62. File I/O runs on the EDT for load/save/export
`dump/MemoryDumperPanel.java:252-265`, `load/MemoryLoaderPanel.java:230-256`, `mem/MemoryPanel.java:244-257`, `macro11/AssemblerModel.java:198-220,320-328`

Fine on local disks; on hung media (NFS, USB) the whole UI freezes with no progress or
cancel — in contrast to every console operation. MicrocodePanel documents its EDT read as
deliberate; the others don't. Related: `Macro11.isAvailable()` walks the PATH doing
filesystem checks on every `updateDisplay()`/`updateButtons()` call
(`exec/ExecutionPanel.java:174-175`, `macro11/AssemblerPanel.java:429`) — cache it. Also
`ConnectionSettingsPanel` enumerates serial ports (jSerialComm) on the EDT in the dialog's
constructor and on every `setProfile`.

### 63. Label, casing and layout polish (grouped)
- Browse button is "..." in `settings/ConnectionSettingsPanel.java:133` and "Browse ..."
  everywhere else; ellipsis spelling drifts ("Load listing..." at
  `microcode/MicrocodePanel.java:94` vs "Open ...", "Save as ..." with a space).
- "Load listing..." (Microcode) vs "Open listing ..." (Assembler) for the identical act;
  the Microcode chooser silently allows multi-selection for split-page listings (`:207`)
  with nothing saying so.
- Table-header casing: the memory grid's corner header is lowercase "start \ offset"
  (`mem/MemoryCellGroupTable.java:391`) while every other table uses Title case;
  "Addr" (`mem/MemoryCellGroupList.java:298-305`) vs spelled-out headers elsewhere.
- Field-label colons present in most windows, absent in MMU and Microcode
  (`mmu/MmuPanel.java:112`, `microcode/MicrocodePanel.java:136,140`).
- "Clear log" (`memtest/MemoryTestPanel.java:162`) vs "Clear" (`log/LogPanel.java:54`,
  `simh/SimhConsolePanel.java:67`).
- The Number Converter is the only place in the app with button mnemonics and shortcut
  bindings (`numbers/NumberConverterPanel.java:124-127`); no menu *item* elsewhere has a
  mnemonic. "Connection settings ..." is bound to Ctrl/Cmd+Comma (`MainWindow.java:96`) —
  the macOS preferences convention, surprising on Linux.
- Dumper: the entry-address field sits in the range bar but its visibility is toggled by
  the format selector in the file bar below (`dump/MemoryDumperPanel.java:113-127,176-177`);
  the Loader groups the equivalent field with the load controls.

---

## Found while fixing

Issues found after the review, while working through the list above. Numbering continues from
it rather than being folded into it: the list is ordered by severity and its numbers are already
referenced from `CHANGES.md` and from the commits.

### 64. Two connect attempts walk and mutate the one `MemoryCellGroups` at the same time — intermittent `ConcurrentModificationException` out of `connect()`
`pdp11-core/.../conn/ConnectionManager.java:288,359-365,640`; `pdp11-core/.../mmu/Pdp11Mmu.java:147`;
`pdp11-core/.../fake/FakePdp11.java:296-305`

Fixing #1 serialised the *publication* of a connection, deliberately not the blocking part of an
attempt — which is the whole point of that fix, since a disconnect must not wait on an attempt
that is waiting on a machine. But an attempt does more than block: `createConsole` builds a
`Pdp11Mmu`, which calls `groups.addGroup("MMU")` and fills it with register cells, and an attempt
that loses the race unpublishes by calling `m_groups.removeGroup(...)`, which clears that group's
cell list. Both of those mutate the application-wide `MemoryCellGroups` and one of its groups,
from the attempt's own thread. Meanwhile the *other* attempt is at line 288 walking
`groups.getGroups()` and `g.getCells()` inside `FakePdp11.resetIoPageValidMap`. Neither
`MemoryCellGroups` nor `MemoryCellGroup` is synchronised, and `getGroups()`/`getCells()` hand out
unmodifiable *views* of the live `ArrayList`s rather than copies — so the walk sees the other
thread's `add`/`clear` and throws.

Observed, not theorised: `ConnectionManagerTest.anOvertakenAttemptDoesNotTakeTheLiveConnectionWithIt`
errors with

```
java.util.ConcurrentModificationException
	at to.etc.pdp11.core.fake.FakePdp11.resetIoPageValidMap(FakePdp11.java:299)
	at to.etc.pdp11.core.conn.ConnectionManager.connect(ConnectionManager.java:288)
```

intermittently — reproduced on a clean `4dc1952` with everything else stashed, in one of two full
`mvn test` runs, and not at all when the class is run on its own. So it is a **flaky test in the
suite as it stands**, which is the part worth fixing first: a race that fails one run in two is
one that gets re-run until it passes and then believed.

The fake is where it lands but not where the problem is. `resetIoPageValidMap` iterating copies
would silence this trace and would be worth doing anyway — it is two short-lived lists — but the
next walk of `m_groups` from another thread finds the same hole. The real question is who owns
`MemoryCellGroups`: today it is touched from the EDT (every window), from the command thread
(`syncMemoryCells` during a bulk examine) and from every connect worker, with nothing coordinating
them. Same family as #16 (Pdp11Mmu's listener list), #30 (command-thread iteration versus EDT
`shiftRange`) and the one just fixed in #5, and it is the third of the four to be found by an
exception rather than by reading — which is the argument for settling the ownership rule once,
in `PLAN.md` §1, rather than fixing the traces one at a time.

Still open, and still flaky, after #21-#30. Re-measured with everything stashed: running the
single test five times on the clean tree failed twice - once as the `ConcurrentModificationException`
above, once as `nor remove its MMU group ==> expected: <1> but was: <2>`, which is the other side
of the same race and does not throw. So "not at all when the class is run on its own" no longer
holds; it fails on its own too, roughly two runs in five on this machine.

---

## Verified clean (for calibration)

Checked and found sound: the core Console/EDT rule (no console call or future-join
reachable from the EDT anywhere; all machine work via `AppContext.onConsole`, all
worker→UI mutation marshalled); no Swing/AWT in pdp11-core; charset discipline
(ISO-8859-1/7-bit throughout the protocol layer, no default-charset use); interrupted
flags restored at every catch; `ConsoleConnection.call()` refuses the command thread;
TelnetTransport's IAC state machine bounds; settings robustness ("nothing in settings may
stop the application starting" holds: load errors, empty/newer files, atomic writes all
handled); no static state, no window-to-window references, windows are thin frames over
testable panels; octal formatting centralized and uniform (zero-padded, "?" for unknown);
`ProgressMonitor.done()` in `finally` at all 8 core call sites; listener add/remove pairs
symmetric across all panels (`attach()` defensively detaches first); algorithms live in
core with tests as the rules require; and the memory-test/microcode/listing-parser
oddities checked against the Pascal reference turned out to be faithful ports of intended
behaviour.
