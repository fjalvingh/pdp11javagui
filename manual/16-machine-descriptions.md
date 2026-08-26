# Machine descriptions

[← Number converter](15-number-converter.md) · [Manual index](README.md) · [Next: Settings and files →](17-settings-and-files.md)

---

A **machine description** tells PDP11GUI which devices a particular PDP-11 has and where they are
jumpered. It is what produces:

* the **Device registers** submenu and [its windows](07-registers-and-bitfields.md);
* the named bits in [the Bitfields window](07-registers-and-bitfields.md#the-bitfields-window);
* the names [the I/O page scanner](12-io-page-scanner.md) puts on the addresses that answer.

Without one the program works fine — it simply has no register windows and no names.

## Where they live

The descriptions shipped with the application are **installed into your data directory on the way
up**, and `pdp11.ini` is loaded from there:

| Platform | Directory |
|---|---|
| Linux | `~/.local/state/pdp11gui/machines` (or `$XDG_STATE_HOME/pdp11gui/machines`) |
| macOS | `~/Library/Application Support/pdp11gui/data/machines` |
| Windows | `%APPDATA%\pdp11gui\data\machines` |

They are ordinary files, and **an existing one is never overwritten**. Edit a description to
describe your own machine and your edit survives an upgrade. (What you give up is that version's
improvements to that file; delete it and restart to get the shipped copy back.)

The shipped `pdp11.ini` describes a generic PDP-11 with seventeen device groups — CPU, MMU, cache,
console SLU, RL11, UDA50, KLESI, and so on.

## The format

Windows `.ini` syntax, preprocessed by **m4**. You do not need m4 installed: the preprocessor is
implemented inside the application.

Sections whose name begins with `Bits.` define the named bits of a register; every other section
is a device.

```ini
[Console Terminal]
Info=Serial console terminal
Enabled=0                                       ; optional, drops the whole section
RCSR= 177560 ;"Receiver Control/Status Register";Bits.SLU.RCSR
BOOT= 173000:173776 ;"Boot ROM"                 ; a range becomes BOOT[0], BOOT[1], ...

[Bits.SLU.RCSR]
Done=7;"Receiver done"
Priority=7:5;"Current level of processor priority"
```

Two comment conventions overlap — `;` for the `.ini` layer and `#` for the m4 layer — which is why
the shipped files use `;#` throughout.

### Addresses are always 16-bit I/O page addresses

By the descriptions' own convention, every address is written as a 16-bit address in the range
`160000`–`177776`, **so that one definition serves a 16, 18 or 22-bit machine**. You do not have
to do anything about that: each console normalises addresses to its own width, and a 16-bit
register group reaches the right register on a 22-bit machine untouched.

If a register seems to be at the wrong address on a wider machine, the cause is an address being
compared at the wrong width somewhere — not the description.

### Modules

A machine is a list of **module instances**. `Module_SLU(177560)` expands to the four register
definitions of a standard serial line unit jumpered at that base address, from the `*.modules`
macro libraries beside the `.ini` file:

```ini
[Console Terminal]
Info=Serial console terminal
Module_SLU(177560)
```

becomes

```ini
[Console Terminal]
Info=Serial console terminal
RCSR= 177560 ;"Receiver Control/Status Register";Bits.CIM.RCSR
RBUF= 177562 ;"Receiver Data Buffer";Bits.CIM.RBUF
XCSR= 177564 ;"Transmitter Control/Status Register";Bits.CIM.TerminalXCSR
XBUF= 177566 ;"Transmitter Buffer Register";Bits.CIM.XBUF
```

To override something a module sets, put your line **after** the macro call:

```ini
[LTC]
Module_LTC
Info = "Line Time Clock on DL11-W (M7856)"
```

### The m4 you can use

Only what the shipped descriptions actually use is implemented: `define`, `include`, `eval(expr,
8)`, and the positional parameters `$1` and `$2`. Quoting is m4's `` `…' ``, so **module bodies
cannot contain apostrophes**.

Anything outside that subset that is genuinely *called* raises an error rather than being passed
through as text — for a machine description, silence would mean a wrong I/O page rather than a
complaint.

One trap worth knowing if you write your own macros: `eval` uses the C convention that a leading
`0` means octal, which is why the shipped `_offset` is defined as
`` define(_offset,`eval(0$1+0$2,8)') ``. Drop that zero and `eval(177560+2,8)` answers `532632` —
a plausible-looking address in entirely the wrong place.

## Writing a description for your own machine

1. Connect to the machine and run [the I/O page scanner](12-io-page-scanner.md).
2. Take the generated `.ini` section from the right-hand pane and paste it into a copy of
   `pdp11.ini` in the machines directory.
3. Rename `device_177560.reg_0` and friends to what they really are, and add `Bits.` sections for
   the registers whose bits you care about.
4. Restart. The device gets its own window in **Windows → Device registers**.

A description that will not load costs you the register windows and nothing else — the failure is
reported and the application carries on. Warnings about individual lines go to
[the log](14-terminal-log-simh.md#the-log-window).

---

[← Number converter](15-number-converter.md) · [Manual index](README.md) · [Next: Settings and files →](17-settings-and-files.md)
