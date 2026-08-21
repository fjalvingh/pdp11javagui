#!/bin/bash
#
# Diffs this disassembler against the Pascal reference implementation over all 65536 words.
#
# PLAN.md §6 lists a "disassembler corpus test against the Pascal implementation output". The
# committed SimH corpus is the permanent test -- SimH is the authority both implementations
# were written against, and the Pascal has known bugs, so it is the wrong thing to pin a
# regression test to. This script is the other half: run it when the disassembler changes, to
# see the full list of places the two implementations part company and check that every one of
# them is deliberate.
#
# Expected output as of phase 1: 183 differing words, in exactly two groups.
#   - 000230..000237 except 000232: the Pascal reads SPL's level from bits 8..6, which are part
#     of SPL's own opcode, so it prints "spl 2" for every SPL.
#   - 176 float-operand words: the Pascal masks the accumulator field to two bits, so AC4/AC5
#     print as AC0/AC1.
# Anything else is a porting error in the Java.
#
# Requires Free Pascal (fpc). Both dumps use the same layout as tools/gen-disas-corpus.sh:
# the instruction at octal 1000, followed by extension words 123456 and 154321.
#
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(dirname "$here")
pascal_src=${PASCAL_SRC:-$root/../pdp11gui/common}

if ! command -v fpc >/dev/null; then
	echo "Free Pascal (fpc) is not installed; apt-get install fpc" >&2
	exit 1
fi
if [ ! -f "$pascal_src/Pdp11DisasU.pas" ]; then
	echo "Cannot find Pdp11DisasU.pas under $pascal_src; set PASCAL_SRC" >&2
	exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# -Mdelphi: the unit uses "out" parameters and is built in Delphi mode by the Lazarus project.
cp "$here/pascal-disas-dump.pas" "$work/"
( cd "$work" && fpc -Mdelphi -Fu"$pascal_src" -O2 pascal-disas-dump.pas >/dev/null )
"$work/pascal-disas-dump" > "$work/pascal.txt"

cat > "$work/JavaDisasDump.java" <<'JAVA'
import to.etc.pdp11.core.disas.DecodedInstruction;
import to.etc.pdp11.core.disas.Disassembler;
import to.etc.pdp11.core.disas.MemoryImage;

public class JavaDisasDump {
	public static void main(String[] args) {
		StringBuilder sb = new StringBuilder(4 << 20);
		for(int w = 0; w < 65536; w++) {
			MemoryImage m = MemoryImage.ofWords(01000, w, 0123456, 0154321);
			DecodedInstruction d = Disassembler.disassemble(m, 01000);
			sb.append(Integer.toOctalString(w)).append('\t').append(d.words())
				.append('\t').append(d.textTrimmed()).append('\n');
		}
		System.out.print(sb);
	}
}
JAVA

classes=$root/pdp11-core/target/classes
if [ ! -d "$classes" ]; then
	echo "$classes does not exist; run ./mvnw -pl pdp11-core compile first" >&2
	exit 1
fi
java -cp "$classes" "$work/JavaDisasDump.java" > "$work/java.txt"

echo "Pascal vs Java, all 65536 words (word / word-count / text):"
if diff "$work/pascal.txt" "$work/java.txt" > "$work/d.txt"; then
	echo "  identical - which means the two SPL and float-operand bugs are back."
	exit 1
fi
n=$(grep -c '^<' "$work/d.txt")
echo "  $n differing words"
echo
grep '^<' "$work/d.txt" | awk -F'\t' '{print $3}' \
	| sed -E 's/(ac|\?)[0-9]/ACn/g; s/[0-7]{3,}/N/g; s/ +/ /g' \
	| sort | uniq -c | sort -rn
echo
echo "Full diff (< Pascal, > Java):"
cat "$work/d.txt"
