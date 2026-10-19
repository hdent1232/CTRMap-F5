package ctrmap.tests;

import ctrmap.formats.text.GFMessageFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static ctrmap.util.LittleEndian.i32;
import static ctrmap.util.LittleEndian.u16;

/**
 * Headless round-trip test for GFMessageFile. Run with:
 * java -cp build/classes ctrmap.tests.GFMessageFileRoundTripTest
 * Exits 0 when all cases pass, 1 otherwise.
 */
public class GFMessageFileRoundTripTest {

	private static int passed = 0;
	private static int failed = 0;

	public static void main(String[] args) {
		//A. plain ASCII
		roundTrip("A1 ascii hello", Arrays.asList("Hello, world!"));
		roundTrip("A2 ascii symbols", Arrays.asList("0123456789 ~!@#$%^&*()"));
		roundTrip("A3 lone close bracket", Arrays.asList("]"));
		roundTrip("A4 multi", Arrays.asList("Hello, world!", "0123456789 ~!@#$%^&*()", "]"));

		//B. unicode
		roundTrip("B1 accented", Arrays.asList("Émile déjà vu"));
		roundTrip("B2 japanese", Arrays.asList("ポケモンだいすき"));
		roundTrip("B3 raw PUA chars", Arrays.asList("", "middle"));
		remapRoundTrip("B4 remapped chars", Arrays.asList("nbsp sep", "dot dot dot…", "♂ vs ♀"));

		//C. escapes
		roundTrip("C1 newline escape", Arrays.asList("Line1\\nLine2"));
		roundTrip("C2 backslash escape", Arrays.asList("back\\\\slash"));
		roundTrip("C3 bracket escape", Arrays.asList("\\[bracket"));
		roundTrip("C4 scroll and clear", Arrays.asList("scroll\\rclear\\cdone"));
		roundTrip("C5 all escapes", Arrays.asList("a\\nb\\\\c\\[d\\re\\cf"));

		//D. variables
		roundTrip("D1 named var", Arrays.asList("[VAR TRNAME]"));
		roundTrip("D2 named var with arg", Arrays.asList("[VAR PKNAME(0001)]"));
		roundTrip("D3 unknown var with args", Arrays.asList("[VAR 0123(0001,ABCD,FFFF)]"));
		roundTrip("D4 mixed vars in text", Arrays.asList("[VAR COLOR(0002)]Hi[VAR 01A5]"));
		roundTrip("D5 wait", Arrays.asList("[WAIT 30]"));
		roundTrip("D6 null line marker", Arrays.asList("[~ 7]"));
		roundTrip("D7 every var name", allVarNamesLines());

		//E. empty lines interleaved
		roundTrip("E1 empty lines", Arrays.asList("", "not empty", "", "also not empty", ""));

		//F. many lines - exercises the 16-bit key advance wraparound and both padding parities
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			many.add("line " + i + " [VAR NUM1(" + String.format("%04X", i) + ")]");
		}
		roundTrip("F1 300 lines", many);

		//G. empty file
		byte[] empty = GFMessageFile.write(new ArrayList<String>());
		byte[] emptyExpected = {
			0x01, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00,
			0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00
		};
		check("G1 empty file bytes", Arrays.equals(empty, emptyExpected));
		check("G2 empty file parses to 0 lines", GFMessageFile.parse(empty, false).isEmpty());
		check("G3 null lines writes empty file", Arrays.equals(GFMessageFile.write(null), emptyExpected));

		//adversarial: 0xFFFF-adjacent and PUA-boundary chars
		roundTrip("H1 high chars", Arrays.asList("�￾￿", "", "low"));

		//negative tests
		check("N1 garbage getStrings empty", GFMessageFile.getStrings(new byte[]{1, 2, 3, 4, 5}).isEmpty());
		check("N2 null getStrings empty", GFMessageFile.getStrings(null).isEmpty());
		byte[] mutated = GFMessageFile.write(Arrays.asList("hello"));
		mutated[0x08] = 1; //corrupt initialKey
		check("N3 bad key getStrings empty", GFMessageFile.getStrings(mutated).isEmpty());
		check("N4 uncapped variable throws", throwsOnWrite(Arrays.asList("[VAR")));
		check("N5 bad escape throws", throwsOnWrite(Arrays.asList("\\x")));
		check("N6 trailing backslash throws", throwsOnWrite(Arrays.asList("oops\\")));
		check("N7 unknown method throws", throwsOnWrite(Arrays.asList("[NOPE 1]")));
		check("N8 out of range arg throws", throwsOnWrite(Arrays.asList("[VAR TRNAME(FFFFF)]")));

		locationNamesAreReadByThisCodec();

		System.out.println();
		System.out.println("Passed: " + passed + ", Failed: " + failed);
		if (failed > 0) {
			System.exit(1);
		}
		System.exit(0);
	}

	private static List<String> allVarNamesLines() {
		String[] names = {
			"COLOR", "TRNAME", "PKNAME", "PKNICK", "TYPE", "LOCATION", "ABILITY", "MOVE",
			"ITEM1", "ITEM2", "sTRBAG", "BOX", "EVSTAT", "OPOWER", "RIBBON", "MIINAME",
			"WEATHER", "TRNICK", "1stchrTR", "SHOUTOUT", "BERRY", "REMFEEL", "REMQUAL", "WEBSITE",
			"CHOICECOS", "GSYNCID", "PRVIDSAY", "BTLTEST", "GENLOC", "CHOICEFOOD", "HOTELITEM", "TAXISTOP",
			"MAISTITLE", "ITEMPLUR0", "ITEMPLUR1", "GENDBR", "NUMBRNCH", "iCOLOR2", "iCOLOR3", "NUM1",
			"NUM2", "NUM3", "NUM4", "NUM5", "NUM6", "NUM7", "NUM8", "NUM9"
		};
		List<String> lines = new ArrayList<>();
		for (String name : names) {
			lines.add("[VAR " + name + "]");
		}
		return lines;
	}

	private static void roundTrip(String name, List<String> lines) {
		try {
			byte[] b1 = GFMessageFile.write(lines);
			List<String> reparsed = GFMessageFile.parse(b1, false);
			if (!lines.equals(reparsed)) {
				fail(name + " (lines)", "expected " + lines + " got " + reparsed);
				return;
			}
			byte[] b2 = GFMessageFile.write(reparsed);
			if (!Arrays.equals(b1, b2)) {
				fail(name + " (bytes)", "write(parse(b)) differs from b, lengths " + b1.length + "/" + b2.length);
				return;
			}
			String structError = structCheck(b1);
			if (structError != null) {
				fail(name + " (structure)", structError);
				return;
			}
			pass(name);
		} catch (Exception ex) {
			fail(name, "exception " + ex);
		}
	}

	private static void remapRoundTrip(String name, List<String> lines) {
		try {
			byte[] b1 = GFMessageFile.write(lines, true);
			List<String> reparsed = GFMessageFile.parse(b1, true);
			if (!lines.equals(reparsed)) {
				fail(name + " (lines)", "expected " + lines + " got " + reparsed);
				return;
			}
			//raw parse must yield PUA chars, not the remapped originals
			List<String> raw = GFMessageFile.parse(b1, false);
			for (String s : raw) {
				if (s.indexOf(0x202F) >= 0 || s.indexOf(0x2026) >= 0 || s.indexOf(0x2642) >= 0 || s.indexOf(0x2640) >= 0) {
					fail(name + " (raw)", "raw parse contains unmapped char in " + s);
					return;
				}
			}
			byte[] b2 = GFMessageFile.write(reparsed, true);
			if (!Arrays.equals(b1, b2)) {
				fail(name + " (bytes)", "write(parse(b)) differs from b");
				return;
			}
			pass(name);
		} catch (Exception ex) {
			fail(name, "exception " + ex);
		}
	}

	private static String structCheck(byte[] b) {
		if (u16(b, 0x00) != 1) {
			return "textSections != 1";
		}
		if (i32(b, 0x08) != 0) {
			return "initialKey != 0";
		}
		if (i32(b, 0x0C) != 0x10) {
			return "sectionDataOffset != 0x10";
		}
		if (i32(b, 0x04) != b.length - 0x10) {
			return "totalLength mismatch";
		}
		if (i32(b, 0x10) != b.length - 0x10) {
			return "sectionLength mismatch";
		}
		if (b.length % 4 != 0) {
			return "total length not a multiple of 4";
		}
		int n = u16(b, 0x02);
		for (int i = 0; i < n; i++) {
			int off = i32(b, 0x10 + 4 + i * 8);
			int len = u16(b, 0x10 + 8 + i * 8);
			int unused = u16(b, 0x10 + 10 + i * 8);
			if (off < 4 + 8 * n) {
				return "line " + i + " offset overlaps line table";
			}
			if (0x10 + off + len * 2 > b.length) {
				return "line " + i + " data out of bounds";
			}
			if (unused != 0) {
				return "line " + i + " unused u16 not zero";
			}
		}
		return null;
	}

	/**
	 * Whether the writer REFUSED this input - not merely whether it blew up.
	 * Every refusal {@link GFMessageFile#write} raises is an
	 * IllegalArgumentException; anything else means the guard that should have
	 * refused is gone and something downstream fell over instead. The whole
	 * reasoning, and the measurement behind it, is on
	 * GFMessageFileHostileTest.throwsOnWrite - this helper is its twin and must
	 * not drift from it.
	 */
	private static boolean throwsOnWrite(List<String> lines) {
		try {
			GFMessageFile.write(lines);
			return false;
		} catch (IllegalArgumentException ex) {
			return true;
		} catch (RuntimeException ex) {
			System.out.println("    (not a refusal: " + ex.getClass().getName() + ": "
					+ ex.getMessage() + " - the guard that should have refused is gone)");
			return false;
		}
	}

	/**
	 * Location names are read by THIS codec (F089). A second, legacy decoder read them, named the
	 * text variables differently from the text editor, appended the digits "9792" where a female
	 * sign belonged, and LocationNames answered "NullPointerException" for an id past the table.
	 * A table this codec wrote, loaded the way a suite stands it in for a game's own: its female
	 * sign comes back a female sign, and an id past it is named as one, in words.
	 */
	private static void locationNamesAreReadByThisCodec() {
		try {
			java.io.File f = java.io.File.createTempFile("locnames", ".bin");
			f.deleteOnExit();
			java.nio.file.Files.write(f.toPath(),
					GFMessageFile.write(Arrays.asList("Littleroot Town", "Route ♀"), true));
			FakeGameFiles game = new FakeGameFiles();
			ctrmap.formats.text.LocationNames.load(game, f);
			check("L1 a location name reads back", "Littleroot Town".equals(
					ctrmap.formats.text.LocationNames.getLocName(game, 0)));
			String female = ctrmap.formats.text.LocationNames.getLocName(game, 1);
			check("L2 a female sign in a location name is a female sign (" + female + ")",
					"Route ♀".equals(female));
			String past = ctrmap.formats.text.LocationNames.getLocName(game, 7);
			check("L3 an id past the table is named as one, in words (" + past + ")",
					ctrmap.formats.text.LocationNames.unknown(7).equals(past) && !past.contains("Exception"));
		} catch (Exception ex) {
			fail("L0 location names through GFMessageFile", String.valueOf(ex));
		}
	}

	private static void check(String name, boolean cond) {
		if (cond) {
			pass(name);
		} else {
			fail(name, "condition false");
		}
	}

	private static void pass(String name) {
		passed++;
		System.out.println("PASS " + name);
	}

	private static void fail(String name, String diag) {
		failed++;
		System.out.println("FAIL " + name + ": " + diag);
	}
}
