# The main window

[← Getting started](01-getting-started.md) · [Manual index](README.md) · [Next: Connecting →](03-connecting.md)

---

The main window is the machine's own terminal, a status bar, and the menu bar. Closing it quits
the program.

## The terminal

The large area is a **glass teletype** connected to the PDP-11's console — the same wire an
operator's VT100 would have been on. Whatever the machine prints appears here, and what you type
is sent to it.

* **Typing is not echoed locally.** The machine echoes; echoing here as well would double every
  character.
* **Typing is enabled only when there is a machine console to type at.** On a SimH connection
  that PDP11GUI did not launch there is no such wire — only SimH's administrative `sim>` channel,
  which is not a place to put keystrokes — and the terminal says so when you connect.
* There is no ANSI emulation, and none is needed: no console protocol emits escape sequences. The
  consoles disagree about line endings, and that is handled for you.
* The scrollback is bounded.

What the terminal does **not** show is the protocol conversation PDP11GUI itself has with the
console — the examines and deposits it issues on your behalf. That is in
[the Log window](14-terminal-log-simh.md#the-log-window), and for SimH in
[the SimH console window](14-terminal-log-simh.md#the-simh-console-window).

## The status bar

Two fields: the connection state — `Not connected`, `Connecting…`, `Connected` (green), or
`Connection failed` (red) — and a detail line saying which machine, over what.

A connection that dies under you also writes a line into the terminal. Without that, the windows
would simply grey out, which on its own looks like the program broke.

## The File menu

| Item | Shortcut | What it does |
|---|---|---|
| **Connect** | Ctrl/Cmd+K | Connect using the currently selected profile |
| **Connection settings …** | | Open [the connection dialog](03-connecting.md) |
| **Connect to simulated ▸** | | One entry per console protocol, connecting to a machine simulated inside this program |
| **Disconnect** | | Close the connection |
| **Quit** | Ctrl/Cmd+Q | Save everything and go |

**Connect** stays available while connected — connecting again is how you move to another machine.
**Disconnect** is offered only when there is something to disconnect from. While a connection is
being made or torn down, all three are greyed out and the cursor is a wait cursor: connecting
launches processes and opens ports, and a second attempt would race the first.

**Quit** asks before discarding unsaved assembler source, remembers where every window was, closes
the windows, saves the settings, and only then tears the connection down.

## The Windows menu

Rebuilt every time it is opened, so it is never out of date. It has three parts:

1. **One entry per kind of tool window.** Choosing one opens it, or raises it if it is already
   open.
   * **New memory window** rather than "Memory": there can be any number of these, and looking at
     two parts of memory at once is the ordinary way to work.
   * **Device registers ▸** is a submenu built from
     [the loaded machine description](16-machine-descriptions.md) — one entry per device it
     declares, with the device's description as a tooltip. With no description loaded it says so.
2. **The windows that exist**, by title. Choosing one raises it. Windows you have closed are
   listed too, marked `(closed)`; choosing one brings it back. This matters for memory windows:
   `New memory window` builds a *new* one, so a closed `Memory - 1` would otherwise be
   unreachable while still holding its range and its edits.
3. **Show all** and **Hide all**.

## The Help menu

| Item | Shortcut | What it does |
|---|---|---|
| **User manual** | F1 | Open this manual on GitHub, in your browser |
| **About PDP11GUI** | | The version, where the settings file is, and which Java is running it |

**User manual** opens the manual **for the release you are running**: a 1.2.0 jar opens the manual
as it was at the `v1.2.0` tag, not whatever `main` has become since, so it cannot describe windows
your copy does not have. A build made from a working copy opens `main`. If no browser can be
opened here, the address is shown in a dialog and copied to the clipboard.

**About PDP11GUI** says *development build* rather than claiming a version it does not have when
the jar was not made by the release workflow.

## How tool windows behave

* They are **created when first asked for**, not all at once on startup.
* They **remember their position and size** between runs, and the ones that were open when you
  quit are reopened when you start.
* **Closing hides.** The window keeps its state — its range, its values, your uncommitted edits —
  and comes back the same.
* **No window tells another window anything.** Shared state — whether the machine is running,
  where the PC is, which cell you have selected — lives in one place that windows watch. A window
  that is not open hears nothing and needs to hear nothing. This is why the disassembler follows
  the PC whether or not the execution window is open.
* A window that is not visible **does not read memory**. Every stop would otherwise cost a
  screenful of examines over a serial line for a window nobody is looking at.

## Long operations

Anything that talks to the machine for more than a second puts up a progress dialog with a
**Cancel** button; **Escape** cancels too. Under a second nothing appears — a dialog that flashes
up and vanishes is worse than no dialog. Cancelling stops the operation at the next whole console
command; it does not leave a half-written word.

---

[← Getting started](01-getting-started.md) · [Manual index](README.md) · [Next: Connecting →](03-connecting.md)
