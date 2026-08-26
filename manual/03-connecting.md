# Connecting to a machine

[← The main window](02-main-window.md) · [Manual index](README.md) · [Next: Memory →](04-memory.md)

---

A connection is two independent choices: **which console dialect** is on the other end, and **how
you reach it**. Any protocol can arrive over any transport that can carry it, so they are chosen
separately rather than from one long list of sentences.

## Console protocols

| Protocol | What it is | Address width |
|---|---|---|
| **SimH** | SimH's remote console (`sim>`), driven by PDP11GUI | 22 bit |
| **PDP-11 ODT, 16 bit** | Microcode ODT on a 16-bit machine | 16 bit |
| **PDP-11 ODT, 18 bit (11/23)** | Microcode ODT on an 18-bit machine | 18 bit |
| **PDP-11 ODT, 22 bit (11/73, 11/93)** | Microcode ODT on a 22-bit machine | 22 bit |
| **Robotron K1630 ODT, 18 bit** | The Robotron A6402's ODT, which spells things its own way | 18 bit |
| **PDP-11/44 console** | The 11/44's console processor | 22 bit |
| **PDP-11/44 console, V3.40C firmware** | The same, running the undocumented V3.40C firmware | 22 bit |

**ODT** is the microcode console built into an LSI-11-family processor: the `@` prompt you get
when the machine halts. It is what you talk to on an 11/23, 11/73 or 11/93 with no front panel.

If you have an 11/44 and are not sure which firmware it has, try **PDP-11/44 console** first. The
V3.40C entry exists because that firmware prints nothing at all while a program has the terminal,
which the ordinary driver waits for.

## Transports

| Transport | What it means |
|---|---|
| **SimH, launched by us** | PDP11GUI starts `pdp11` as a child process and drives its remote console |
| **Telnet** | A telnet port somebody else is listening on — a SimH started elsewhere, a terminal server, a console concentrator |
| **Serial port** | A real serial line to a real machine |
| **Simulated machine (no hardware)** | A PDP-11 simulated inside this JVM. Needs nothing at all |

Two combinations are refused, and the dialog says why:

* **SimH over a serial port** — SimH is a program on this computer, not something at the end of a
  cable.
* **Any machine console launched as a process** — an ODT or 11/44 console is a machine, so it
  cannot be started as a program.

## The connection dialog

**File → Connection settings …**

* **Saved profile** — a named connection, with **Save** and **Delete** beside it. Saved profiles
  are how you keep "the 11/23 on the bench" and "SimH with the RSX config" side by side.
* **Name** — what to call this one.
* **Console** — the protocol, from the table above.
* **Reached over** — the transport. The fields below change to match:

| Transport | Fields |
|---|---|
| SimH, launched by us | **SimH executable** (found on `PATH` unless you give an absolute path; defaults to `pdp11`) and **Configuration** — a SimH command file, with a **Browse …** button |
| Telnet | **Host** and **Port** (default 23) |
| Serial port | **Port** (`/dev/ttyUSB0`, `COM3`), **Baud** (default 9600) and **Format** (8N1 by default; the seven-bit formats are there for old hardware that needs them) |
| Simulated machine | Nothing to configure |

A line under the fields says why the current configuration cannot be connected, if it cannot, and
the **Connect** button is disabled until it can. **Close** (or Escape) leaves the dialog; the
profiles you saved are kept.

## Simulated machines

**File → Connect to simulated ▸** connects to a machine simulated inside this program, one entry
per console protocol. These are not toys bolted on the side: they are the same simulated machines
the program's own test suite runs against, and they exercise the real protocol code end to end.
They need no hardware, no SimH and no serial port, which makes them the right thing to try first
when something is not working — if a simulated machine behaves and yours does not, the problem is
on the wire or in the machine.

## What happens when you connect

Connecting runs in the background — launching SimH or opening a port takes as long as it takes —
with the menu greyed out and a wait cursor. When it succeeds:

* the status bar turns green and names the connection;
* the window title becomes `PDP11GUI - <console name>`;
* the terminal gets a `[connected: …]` line;
* on a SimH connection, [the SimH console window](14-terminal-log-simh.md#the-simh-console-window)
  opens by itself, because that is where SimH's side of the conversation is;
* the profile is remembered as the one to offer next time.

If it fails, the terminal says so and a dialog gives the reason. See
[Troubleshooting](18-troubleshooting.md).

---

[← The main window](02-main-window.md) · [Manual index](README.md) · [Next: Memory →](04-memory.md)
