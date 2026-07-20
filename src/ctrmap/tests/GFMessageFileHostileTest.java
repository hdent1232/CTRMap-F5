package ctrmap.tests;

import ctrmap.formats.text.GFMessageFile;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Adversarial tests for GFMessageFile. Run with:
 * java -cp build/classes ctrmap.tests.GFMessageFileHostileTest
 */
public class GFMessageFileHostileTest {

	private static int passed = 0;
	private static int failed = 0;

	public static void main(String[] args) {
		//S. surrogate pairs / astral chars
		roundTrip("S1 emoji", Arrays.asList("Hi 😀🎉 bye"));
		roundTrip("S2 lone high surrogate", Arrays.asList("x\uD83Dx"));
		roundTrip("S3 lone low surrogate", Arrays.asList("x\uDE00x"));
		roundTrip("S4 emoji at both ends", Arrays.asList("😀mid😀"));

		//V. variables at line boundaries, adjacency
		roundTrip("V1 var at line start", Arrays.asList("[VAR TRNAME] trailing"));
		roundTrip("V2 var at line end", Arrays.asList("leading [VAR NUM9(1234)]"));
		roundTrip("V3 var alone", Arrays.asList("[VAR TRNAME]"));
		roundTrip("V4 adjacent vars", Arrays.asList("[VAR NUM1(0001)][VAR NUM2(0002)][VAR 0FFF]"));
		roundTrip("V5 wait at end", Arrays.asList("pause[WAIT 65535]"));
		roundTrip("V6 escapes at both ends", Arrays.asList("\\rmid\\c", "\\nmid\\n", "\\[x\\["));

		//B. bracket literals
		roundTrip("B1 close brackets", Arrays.asList("]", "]]", "a]b]c"));
		roundTrip("B2 escaped open then junk", Arrays.asList("\\[VAR TRNAME] not a var"));
		roundTrip("B3 close before var", Arrays.asList("][VAR TRNAME]"));
		check("B4 bare open bracket nonvar throws", throwsOnWrite(Arrays.asList("a[b]")));
		check("B5 uncapped open bracket throws", throwsOnWrite(Arrays.asList("a[b")));

		//L. long lines
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 65533; i++) {
			big.append((char) ('A' + (i % 26)));
		}
		roundTrip("L1 65533-char line (length field 0xFFFE)", Arrays.asList(big.toString()));
		StringBuilder big2 = new StringBuilder(big);
		big2.append('Z'); //65534 chars + terminator = 65535 u16s, but the pad-to-4 bumps it to 65536
		byte[] l2b = GFMessageFile.write(Arrays.asList(big2.toString()));
		List<String> l2p = GFMessageFile.parse(l2b);
		if (big2.toString().equals(l2p.get(0))) {
			pass("L2 65534-char line");
		} else {
			fail("L2 65534-char line", "SILENT CORRUPTION: stored u16 length field = 0x"
					+ Integer.toHexString(readU16(l2b, 0x10 + 8)).toUpperCase()
					+ ", 'unused' u16 after it = 0x" + Integer.toHexString(readU16(l2b, 0x10 + 10)).toUpperCase()
					+ ", reparsed line0 length " + l2p.get(0).length() + " (expected 65534)");
		}
		//65535 chars + terminator = 65536 u16s -> length field overflows u16
		StringBuilder over = new StringBuilder(big2);
		over.append('Z');
		List<String> overLines = Arrays.asList(over.toString());
		try {
			byte[] ob = GFMessageFile.write(overLines);
			List<String> reparsed = GFMessageFile.parse(ob);
			if (overLines.equals(reparsed)) {
				pass("L3 65535-char line round trips");
			} else {
				fail("L3 65535-char line round trips", "SILENT CORRUPTION: wrote " + ob.length
						+ " bytes, reparsed line0 length " + reparsed.get(0).length()
						+ " (expected 65535); stored u16 length field = 0x"
						+ Integer.toHexString(readU16(ob, 0x10 + 8)).toUpperCase()
						+ ", bytes after it = 0x" + Integer.toHexString(readU16(ob, 0x10 + 10)).toUpperCase());
			}
		} catch (RuntimeException ex) {
			pass("L3 65535-char line rejected loudly (" + ex.getClass().getSimpleName() + ")");
		}

		//D. differential test vs a literal char-as-ushort port of the pk3DS pipeline
		differential("D1 differential simple", Arrays.asList("Hello", "world", ""));
		List<String> many = new ArrayList<>();
		Random rng = new Random(0x7C89);
		for (int i = 0; i < 64; i++) {
			StringBuilder sb = new StringBuilder();
			int len = rng.nextInt(37);
			for (int j = 0; j < len; j++) {
				//printable BMP chars, avoiding the codec's special chars entirely
				char c = (char) (0x20 + rng.nextInt(0x2000));
				if (c == '[' || c == ']' || c == '\\') {
					c = 'x';
				}
				sb.append(c);
			}
			many.add(sb.toString());
		}
		differential("D2 differential 64 random lines (key wraparound + both pad parities)", many);
		StringBuilder longLine = new StringBuilder();
		for (int i = 0; i < 5000; i++) {
			longLine.append((char) (0x3041 + (i % 0x50)));
		}
		differential("D3 differential 5000-char line (in-line ROL schedule)", Arrays.asList(longLine.toString()));

		//W. whitespace is written verbatim - pk3DS trims each line, which destroys the trailing
		//spaces that genuinely occur in ORAS text files
		byte[] w1 = GFMessageFile.write(Arrays.asList("　x "));
		List<String> w1p = GFMessageFile.parse(w1);
		check("W1 whitespace at both line edges preserved verbatim", w1p.get(0).length() == 3);

		//X. raw control chars that collide with codec keys are now rejected outright instead of
		//being silently misparsed the way pk3DS does
		try {
			GFMessageFile.write(Arrays.asList("a\0b"));
			check("X1 embedded NUL rejected", false);
		} catch (IllegalArgumentException ex) {
			check("X1 embedded NUL rejected", true);
		}
		try {
			byte[] x2 = GFMessageFile.write(Arrays.asList("a\020zzzb"));
			List<String> x2p = GFMessageFile.parse(x2);
			System.out.println("INFO X2 embedded U+0010: reparses as \"" + escape(x2p.get(0)) + "\" (KEY_VARIABLE collision, same as pk3DS)");
		} catch (RuntimeException ex) {
			System.out.println("INFO X2 embedded U+0010: write() accepted it but parse() threw " + ex
					+ " (KEY_VARIABLE collision, pk3DS also throws on its own output here)");
		}

		System.out.println();
		System.out.println("Passed: " + passed + ", Failed: " + failed);
		System.exit(failed > 0 ? 1 : 0);
	}

	//---- literal pk3DS reimplementation using char as ushort ----

	private static byte[] refWrite(List<String> lines) {
		int n = lines.size();
		char key = (char) 0x7C89;
		byte[][] enc = new byte[n][];
		int[] lengths = new int[n];
		int bytesUsed = 0;
		for (int i = 0; i < n; i++) {
			//no trim, and the stored length excludes the 4-byte alignment padding: this is what the
			//real ORAS files do (pk3DS trims and counts the padding, and does not match the games)
			byte[] dec = refGetLineData(lines.get(i));
			enc[i] = refCrypt(dec, key);
			lengths[i] = enc[i].length / 2;
			if (enc[i].length % 4 == 2) {
				enc[i] = Arrays.copyOf(enc[i], enc[i].length + 2);
			}
			bytesUsed += enc[i].length;
			key += 0x2983; //char arithmetic wraps at 16 bits exactly like C# ushort
		}
		int total = 0x10 + 4 + 8 * n + bytesUsed;
		byte[] out = new byte[total];
		out[0] = 1; //textSections
		putU16(out, 2, n);
		putS32(out, 4, total - 0x10);
		putS32(out, 0xC, 0x10);
		putS32(out, 0x10, total - 0x10);
		int rel = 4 + 8 * n;
		for (int i = 0; i < n; i++) {
			putS32(out, 0x14 + i * 8, rel);
			putS32(out, 0x18 + i * 8, lengths[i]);
			System.arraycopy(enc[i], 0, out, 0x10 + rel, enc[i].length);
			rel += enc[i].length;
		}
		return out;
	}

	private static byte[] refCrypt(byte[] data, char key) {
		byte[] result = new byte[data.length];
		for (int i = 0; i < result.length; i += 2) {
			char v = (char) (((data[i] & 0xFF) | ((data[i + 1] & 0xFF) << 8)) ^ key);
			result[i] = (byte) v;
			result[i + 1] = (byte) (v >>> 8);
			key = (char) (key << 3 | key >>> 13); //ushort ROL3 exactly as C# writes it
		}
		return result;
	}

	private static byte[] refGetLineData(String line) {
		//plain text only (test corpus avoids specials)
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			out.write(c & 0xFF);
			out.write((c >>> 8) & 0xFF);
		}
		out.write(0);
		out.write(0);
		return out.toByteArray();
	}

	private static void differential(String name, List<String> lines) {
		try {
			byte[] actual = GFMessageFile.write(lines);
			byte[] expected = refWrite(lines);
			if (Arrays.equals(actual, expected)) {
				pass(name);
			} else {
				int at = -1;
				int max = Math.min(actual.length, expected.length);
				for (int i = 0; i < max; i++) {
					if (actual[i] != expected[i]) {
						at = i;
						break;
					}
				}
				fail(name, "byte mismatch, lengths " + actual.length + "/" + expected.length + ", first diff at " + at);
			}
		} catch (Exception ex) {
			fail(name, "exception " + ex);
		}
	}

	//---- helpers ----

	private static void roundTrip(String name, List<String> lines) {
		try {
			byte[] b1 = GFMessageFile.write(lines);
			List<String> reparsed = GFMessageFile.parse(b1);
			List<String> trimmed = new ArrayList<>();
			for (String s : lines) {
				trimmed.add(s.trim());
			}
			if (!trimmed.equals(reparsed)) {
				String exp = trimmed.toString();
				String got = reparsed.toString();
				if (exp.length() > 80) {
					exp = exp.substring(0, 80) + "...(len " + exp.length() + ")";
				}
				if (got.length() > 80) {
					got = got.substring(0, 80) + "...(len " + got.length() + ")";
				}
				fail(name + " (lines)", "expected " + escape(exp) + " got " + escape(got));
				return;
			}
			byte[] b2 = GFMessageFile.write(reparsed);
			if (!Arrays.equals(b1, b2)) {
				fail(name + " (bytes)", "write(parse(b)) differs from b");
				return;
			}
			pass(name);
		} catch (Exception ex) {
			fail(name, "exception " + ex);
		}
	}

	private static void check(String name, boolean cond) {
		if (cond) {
			pass(name);
		} else {
			fail(name, "condition false");
		}
	}

	private static boolean throwsOnWrite(List<String> lines) {
		try {
			GFMessageFile.write(lines);
			return false;
		} catch (RuntimeException ex) {
			return true;
		}
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 0x20 && c < 0x7F) {
				sb.append(c);
			} else {
				sb.append(String.format("\\u%04X", (int) c));
			}
		}
		return sb.toString();
	}

	private static void pass(String name) {
		passed++;
		System.out.println("PASS " + name);
	}

	private static void fail(String name, String diag) {
		failed++;
		System.out.println("FAIL " + name + ": " + diag);
	}

	private static int readU16(byte[] data, int off) {
		return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
	}

	private static void putU16(byte[] d, int off, int v) {
		d[off] = (byte) v;
		d[off + 1] = (byte) (v >>> 8);
	}

	private static void putS32(byte[] d, int off, int v) {
		d[off] = (byte) v;
		d[off + 1] = (byte) (v >>> 8);
		d[off + 2] = (byte) (v >>> 16);
		d[off + 3] = (byte) (v >>> 24);
	}
}
