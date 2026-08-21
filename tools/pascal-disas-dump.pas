program dall;
{ Dumps the Pascal reference disassembler's decode of every 16-bit word, for diffing
  against the Java port. Same layout as tools/gen-disas-corpus.sh: instruction at octal
  1000, extension words 123456 and 154321. }
uses SysUtils, Pdp11DisasU;
var
  mem, valid: array[0..65535] of AnsiChar;
  i, n: integer;
  txt: string;

procedure PutW(a, v: word);
begin
  mem[a] := AnsiChar(v and 255); mem[a+1] := AnsiChar(v shr 8);
  valid[a] := #1; valid[a+1] := #1;
end;

function OctStr(v: dword; digits: integer): string;
begin
  result := '';
  repeat
    result := char((v and 7) + ord('0')) + result;
    v := v shr 3;
  until v = 0;
  while length(result) < digits do result := '0' + result;
end;

begin
  FillChar(mem, sizeof(mem), 0); FillChar(valid, sizeof(valid), 0);
  PutW(514, 42798);   { octal 123456 }
  PutW(516, 55505);   { octal 154321 }
  for i := 0 to 65535 do begin
    PutW(512, word(i));
    n := DisassembleInstruction(@mem[0], @valid[0], 512, txt);
    writeln(OctStr(dword(i), 1), #9, n, #9, TrimRight(txt));
  end;
end.
