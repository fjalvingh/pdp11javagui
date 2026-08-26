# Terminal, log and SimH console

[← Microcode](13-microcode.md) · [Manual index](README.md) · [Next: Number converter →](15-number-converter.md)

---

Three windows show you the wire, and they show three different things. Knowing which is which is
most of the skill of debugging a flaky console.

| Window | Shows |
|---|---|
| The main window's **terminal** | The PDP-11's own console, as an operator would see it |
| **SimH console** | SimH's administrative `sim>` channel, in full |
| **Log** | Everything, in columns, including PDP11GUI's own protocol conversation |

## The terminal

Described under [the main window](02-main-window.md#the-terminal). It is the machine's console:
what the machine prints, and what you type at it.

It does **not** show PDP11GUI's own commands — the examines and deposits it issues on your behalf.
Those are in the Log.

## The SimH console window

**Windows → SimH console**, and it opens by itself when you connect to SimH.

A SimH connection has two wires:

* the **console** channel — the emulated PDP-11's own serial console. That goes to the main
  window's terminal, because the rule this program follows is that the main terminal is the
  machine's console, whatever the machine is;
* the **remote** channel — SimH's `sim>` prompt, which no real PDP-11 ever had, and which
  PDP11GUI drives to examine and deposit memory.

This window is the second one. It shows the raw channel exactly as it arrives — PDP11GUI's own
commands, SimH's replies, everything — which is how a misbehaving connection gets diagnosed.

| Control | What it does |
|---|---|
| **Halt (^E)** | Stop the running simulation, as `^E` does at a real `sim>` prompt |
| **Clear** | Clear the transcript. What SimH has already said is not kept anywhere else |
| The `sim>` command line | Type a SimH command — `show dev`, `att rl0 disk.dsk`, `set throttle 5M`. Up and Down walk the history |

**Halt** is on a button rather than left to the command line for a reason: a command that starts
the machine does not come back to a prompt, so there would be nowhere to type the command that
stops it again.

Commands you type are marked with a `>` line before they are sent, because SimH echoes everything
and an echo of something *you* typed is otherwise indistinguishable from an echo of something the
program issued.

Connected to something that is not SimH, the window says so and the controls are dead — it drives
nothing else.

## The Log window

**Windows → Log.**

One **column per channel**, and that layout is the whole point. A console conversation logged into
a single stream is unreadable: the bytes going out, the bytes coming back and the phrases decoded
from them interleave, and telling them apart is the job you opened the log to do. Side by side,
they line up.

| Column | What it holds |
|---|---|
| **Time** | When |
| **Other** | Anything without a channel of its own — startup, machine descriptions, warnings |
| **Write** | Bytes written to the transport, one line per byte |
| **Read** | Bytes read from the transport, one line per byte |
| **Protocol** | Answer phrases decoded from the byte stream |
| **Command** | Console commands entering and leaving the command queue |
| **Execution** | CPU run/halt transitions |

Each column has a checkbox above it that turns that channel on and off. **Write** and **Read** are
one line per byte and are off unless you are debugging the wire itself — they are what you turn on
when a console is answering nonsense and you need to see the actual octets.

**Clear** empties it. The log follows the tail unless you have scrolled up to read something, and
holds the most recent 20,000 lines.

The buffer exists from the first line logged, long before you open the window, so opening it shows
the history rather than starting from now. Closing it stops the drawing, not the logging.

---

[← Microcode](13-microcode.md) · [Manual index](README.md) · [Next: Number converter →](15-number-converter.md)
