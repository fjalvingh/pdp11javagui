# Machine descriptions

A machine description tells PDP11GUI which devices a particular PDP-11 has and where they are
jumpered. It drives the bitfield definitions, the register-group windows, the I/O page scanner
and the fakes' valid-address map.

## Where these came from

They are **not** in the Pascal repository — they ship only in the Windows installer. These
were extracted from `PDP11GUI.msi` release 1.48.6
(<https://github.com/j-hoppe/PDP11GUI/releases>), whose `Data1.cab` contains them verbatim.

`pdp11_no_m4.ini` is a 2009 snapshot of a fully expanded description, kept as documentation of
what the final `.ini` format looks like. It is not an input to anything and is much older than
the other files here, so do not diff it against a fresh expansion.

## Format

Windows `.ini` syntax, **preprocessed by m4** before parsing. `pdp11.ini` is a list of module
instances; each `Module_XXX(baseaddr)` call expands to the register definitions of one device
at that base address, from the `*.modules` macro libraries.

Two comment conventions overlap: `;` for the `.ini` layer, `#` for the m4 layer, hence the
`;#` used throughout. m4 quoting is `` `...' ``, which is why the module libraries contain no
apostrophes.

## The m4 subset actually used

Verified across all files here — this is the whole surface the Java preprocessor has to
implement (PLAN.md §7 lists this as an open question; it is now answered):

| Feature | Uses | Notes |
|---|---|---|
| `define(NAME,`body')` | 15 | Macro definition. |
| `include(`file')` | 11 | Resolved against this directory. |
| `$1`, `$2` | 46 | Positional parameters. No `$0`, `$#`, `$@`, `$*`. |
| `eval(expr, 8)` | 3 | Integer arithmetic, output radix 8. Only `+`, `-`, `*` and parentheses appear. |

Not used anywhere: `ifelse`, `ifdef`, `dnl`, `changequote`, `changecom`, `divert`, `shift`,
`incr`, `len`, `substr`, `translit`, `patsubst`, `format`, `syscmd`.

`to.etc.pdp11.core.machine.M4Preprocessor` implements exactly that subset and **reproduces GNU
m4 1.4.21's output over these files byte for byte** (`MachineDescriptionLoadTest`). Anything
outside the subset that is genuinely *called* raises rather than being emitted as text, since
for a machine description silence would mean a wrong I/O page rather than an error.

Two things about m4 that are not optional here, both learned the hard way:

- **A macro's expansion is rescanned.** `Module_SLU`'s body contains `_offset($1,0)`, so after
  `$1` is substituted the result has to go back through the scanner. A regex pass cannot do it.
- **A builtin name is only a macro when it is called.** These files are full of English prose
  in register info strings — "does not include UNIBUS addresses", "within 3 index pulses" —
  and `include`, `index`, `format` and `len` are all m4 builtins. GNU m4 leaves such a name
  alone unless it is followed by `(`, and so must we, or the descriptions get corrupted.

Note `eval` needs the C convention that a leading `0` means octal: `_offset` is defined as
``define(_offset,`eval(0$1+0$2,8)')`` precisely so that its octal arguments, written without
the prefix, get it back. Drop the zero and `eval(177560+2,8)` answers `532632` instead of the
register at `0177562` — a plausible-looking address in the wrong place entirely.

## Encoding

ISO-8859-1 with CRLF line endings. The only non-ASCII byte is `0xB5` (µ, in "µCode"), in
`pdp11_cpu.modules`, `pdp11_disc.modules` and `pdp11_no_m4.ini`. Read these as ISO-8859-1 —
decoding as UTF-8 fails. `.gitattributes` marks them `-text` so Git leaves the line endings
alone; `pdp11-app/src/test/resources/machines/pdp11.expected.ini` is a byte-for-byte golden
fixture of GNU m4's output over `pdp11.ini` and depends on that.

## What is actually in here

Loading the shipped `pdp11.ini` yields **17 device groups, 62 bitfield definitions and 233
cells**, with no warnings. Two shapes in the data are worth knowing about before they look
like corruption:

- **Overlapping bitfields are deliberate.** Many device registers mean one thing read and
  another written, and a single `[Bits.*]` section defines both with a name prefix: the DZ11's
  `Bits.M7819.RBUF_LPR` carries `RBUF.PAR ERR<12>` and `LPR.RX ON<12>` together. 41 fields
  across this description overlap another.
- **Several register names may share one address.** The RX211 floppy controller declares
  `RX2TA`, `RX2SA`, `RX2WC`, `RX2BA`, `RX2DB` and `RX2ES` all at `0177172`, because that is one
  register the controller reinterprets six ways during a transfer.

## The Pascal version cannot load these on Linux

`MemoryCellU.pas:599` hardcodes `m4.bat`, a Windows batch file, and raises if it is missing.
There is no `m4.sh`. So in the Lazarus Linux build the bitfields, the I/O page scanner and the
register-group windows do not work at all, and cannot be used to cross-check the Java port —
only the Windows build can. See PLAN.md §7.
