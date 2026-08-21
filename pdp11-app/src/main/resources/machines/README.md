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
`incr`, `len`, `substr`, `translit`, `patsubst`, `format`, `syscmd`. A macro processor this
small is a few hundred lines of Java, so converting the files to a simpler format — the
fallback PLAN.md §7 offers — is not necessary.

Note `eval` needs the C convention that a leading `0` means octal: `_offset` is defined as
``define(_offset,`eval(0$1+0$2,8)')`` precisely so that its octal arguments, written without
the prefix, get it back.

## Encoding

ISO-8859-1 with CRLF line endings. The only non-ASCII byte is `0xB5` (µ, in "µCode"), in
`pdp11_cpu.modules`, `pdp11_disc.modules` and `pdp11_no_m4.ini`. Read these as ISO-8859-1 —
decoding as UTF-8 fails. `.gitattributes` marks them `-text` so Git leaves the line endings
alone; `pdp11-app/src/test/resources/machines/pdp11.expected.ini` is a byte-for-byte golden
fixture of GNU m4's output over `pdp11.ini` and depends on that.

## The Pascal version cannot load these on Linux

`MemoryCellU.pas:599` hardcodes `m4.bat`, a Windows batch file, and raises if it is missing.
There is no `m4.sh`. So in the Lazarus Linux build the bitfields, the I/O page scanner and the
register-group windows do not work at all, and cannot be used to cross-check the Java port —
only the Windows build can. See PLAN.md §7.
