# Number converter

[← Terminal, log and SimH console](14-terminal-log-simh.md) · [Manual index](README.md) · [Next: Machine descriptions →](16-machine-descriptions.md)

---

**Windows → Number converter.** One number in octal, hex and decimal at once. Type into any of
them and the other two follow.

Everything in PDP-11 land is octal, and everything in the rest of computing is not. This is the
window for the moment those two facts meet.

## What it shows

| Row | What it is |
|---|---|
| **Width** | 8, 16, 18, 22 or 32 bits. **16** by default — a PDP-11 word — and the others are the address widths |
| **Octal** | With a binary line under it, grouped in threes so the columns line up with the octal digits |
| **Hex** | With a binary line grouped in fours |
| **Decimal** | Unsigned |
| **Signed** | The same bits read as a two's complement number of the chosen width |

The **Signed** row is worth the window on its own: `177777` is `-1`, and working that out by hand
is exactly the sort of thing this exists to stop.

**Clear** sets the value back to zero.

## Typing

Each field takes only digits of its own base, and only values that **fit the chosen width** — both
when typed and when pasted. A digit too many is **refused**; the number you are looking at is not
silently changed into a different one.

Narrowing the width truncates the value to fit, and says so in a note underneath, so a value that
changed under you does not do it quietly.

| Key | Does |
|---|---|
| **Alt+O** | Jump to the octal field |
| **Alt+H** | Jump to the hex field |
| **Alt+D** | Jump to the decimal field |

Escape does nothing here, deliberately. It is the key people press to mean "never mind", and it is
not one that should destroy the number you are inspecting.

---

[← Terminal, log and SimH console](14-terminal-log-simh.md) · [Manual index](README.md) · [Next: Machine descriptions →](16-machine-descriptions.md)
