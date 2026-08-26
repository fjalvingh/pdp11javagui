# Settings and files

[← Machine descriptions](16-machine-descriptions.md) · [Manual index](README.md) · [Next: Troubleshooting →](18-troubleshooting.md)

---

## Where things are kept

Two directories, deliberately separate: one is worth backing up, the other is worth deleting.

### Configuration

| Platform | Directory |
|---|---|
| Linux | `$XDG_CONFIG_HOME/pdp11gui`, or `~/.config/pdp11gui` |
| macOS | `~/Library/Application Support/pdp11gui` |
| Windows | `%APPDATA%\pdp11gui` |

It holds one file, `settings.json`. **Help → About** shows the exact path.

### Data

| Platform | Directory |
|---|---|
| Linux | `$XDG_STATE_HOME/pdp11gui`, or `~/.local/state/pdp11gui` |
| macOS | `~/Library/Application Support/pdp11gui/data` |
| Windows | `%APPDATA%\pdp11gui\data` |

It holds `machines/` — [the machine descriptions](16-machine-descriptions.md) — and working files
such as the SimH configuration the program generates.

To run against a throwaway configuration on Linux, point `XDG_CONFIG_HOME` somewhere else.

## What is remembered

`settings.json` is pretty-printed JSON, meant to be readable and diffable. It holds:

* the **schema version** of the file;
* **window geometry** — position and size, per window, including which were open;
* **saved connection profiles**, and which one to offer next time;
* the **MACRO-11 source file** the assembler had open;
* which **microcode** the [Microcode window](13-microcode.md) was showing, and any listing files
  you opened for each.

It is written when you quit, and at a few points in between. Writes go through a temporary file
and an atomic rename, so an interrupted write loses the *new* settings rather than the old ones.

## Nothing in settings may stop the program starting

The settings file can be missing, empty, truncated by a crash, hand-edited into something that is
not JSON at all, or written by a version that does not exist yet. **Every one of those carries on
with defaults and says so in the log.** A program that will not start because it cannot remember
where its windows were is worse than one that forgets.

The same principle applies piecemeal: a remembered source file that has been deleted opens an
empty editor, a remembered microcode listing that has moved falls back to the packaged document,
and a window whose remembered position is off-screen is placed somewhere sensible.

## Files the program reads and writes

| Thing | Where |
|---|---|
| MACRO-11 source | Wherever you put it, usually `.mac` |
| MACRO-11 listing | Beside the source, as `.lst` — `macro11` has no option to put it elsewhere, so the source must be in a writable directory |
| [Memory files](10-load-and-dump.md) | Wherever you choose; the suggested extensions are `.bin`, `.txt` and `.ptap` |
| SimH `DO` script export | Wherever you choose, from the memory window's right-click menu |
| Machine descriptions | The data directory, as `.ini` and `.modules` |

---

[← Machine descriptions](16-machine-descriptions.md) · [Manual index](README.md) · [Next: Troubleshooting →](18-troubleshooting.md)
