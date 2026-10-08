package ctrmap.tests;

import ctrmap.formats.scripts.GFLPawnScript;
import ctrmap.formats.zone.ZoneEntities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A zone script is read only when its header holds together, and refused in words when it does not
 * (F149).
 *
 * <p>WHY THIS SUITE EXISTS. The Pawn reader checked nothing in its header. A wrong magic, tables out
 * of order and a declared length past the end were all taken - the missing bytes read as zero code
 * - a table size of 0 divided by zero past every caller that catches IOException, and a header cut
 * short was logged and the script handed back half built. The script injectors rewrite what it
 * reads and the zone save writes it back. The zone's entity reader logged its own failures the same
 * way and handed back the records read so far.
 *
 * <p>ORDER: needs no dump. Every script here is built from the retail header layout.
 *
 * Usage: java ctrmap.tests.PawnHeaderTest
 */
public class PawnHeaderTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		System.out.println("--- a script whose header holds together reads");
		GFLPawnScript empty = new GFLPawnScript(HandedGameTest.amx());
		check((empty.magic & 0xFFFF) == 0xF1E0 && empty.publics.isEmpty() && empty.compCode.length == 0,
				"control: the empty retail-shaped script reads, magic F1E0, nothing in it");
		GFLPawnScript onePublic = new GFLPawnScript(withOnePublic());
		check(onePublic.publics.size() == 1 && onePublic.publics.get(0).data[0] == 0x1234 && onePublic.compCode.length == 4,
				"and one with a public and four bytes of code reads them");

		System.out.println("--- a script whose header does not is refused, naming why (F149)");
		byte[] b = HandedGameTest.amx();
		MapResizeTest.putU16(b, 4, 0);
		refused("a wrong magic", b, "magic is 0x0000");
		b = HandedGameTest.amx();
		MapResizeTest.putU16(b, 10, 0);
		refused("a table size of 0, which divided by zero", b, "entries are 0 bytes");
		b = HandedGameTest.amx();
		MapResizeTest.putU16(b, 10, 6);
		refused("a table size that is not whole cells", b, "entries are 6 bytes");
		b = HandedGameTest.amx();
		MapResizeTest.putI32(b, 0, 4000);
		refused("a declared length past the end, whose missing bytes read as zero code", b, "ends before the 4000 bytes");
		b = HandedGameTest.amx();
		MapResizeTest.putI32(b, 36, 200);
		refused("tables out of order", b, "not in order");
		b = HandedGameTest.amx();
		MapResizeTest.putI32(b, 32, 0x40);
		refused("a first table that does not start after the header", b, "not straight after");
		b = withOnePublic();
		MapResizeTest.putI32(b, 12, 0x3C);
		refused("code that starts inside the tables", b, "code starts at 60");
		refused("a header cut short, handed back half built", Arrays.copyOf(HandedGameTest.amx(), 30), "ends before");

		System.out.println("--- a script whose code has outgrown its code/data boundary is refused by the injectors (F146)");
		GFLPawnScript grown = new GFLPawnScript(withOnePublic());
		grown.decompressThis();
		check(boundaryHolds(grown), "control: a script as read ends its code where its boundary says");
		ctrmap.formats.scripts.PawnInstruction wired = new ctrmap.formats.scripts.PawnInstruction(0, 0x81, "WIRED");
		wired.argumentCount = 1;
		wired.argumentCells = new int[]{0};
		grown.instructions.add(wired);
		grown.updateRaw();
		String said = refusal(grown);
		check(said != null && said.contains("Inject this routine first"), "a routine wired in without the script"
				+ " being read again is refused before anything is written over it: " + said);
		GFLPawnScript reread = new GFLPawnScript(grown.getScriptBytes());
		reread.decompressThis();
		check(boundaryHolds(reread) && reread.instructions.size() == 1, "and the same script written and read again"
				+ " is accepted, its routine kept");
		java.util.regex.Pattern writesAtBoundary = java.util.regex.Pattern.compile(
				"int\\s+base\\s*=\\s*\\w+\\.dataStart\\s*-\\s*\\w+\\.instructionStart");
		List<String> writers = new ArrayList<>(), unasked = new ArrayList<>();
		for (java.io.File f : DialogSeamTest.javaSources(new java.io.File("src/ctrmap"))) {
			String code = SourceSeamTest.stripComments(new String(java.nio.file.Files.readAllBytes(f.toPath()),
					java.nio.charset.StandardCharsets.UTF_8));
			if (writesAtBoundary.matcher(code).find()) {
				writers.add(f.getName());
				//a CALL, qualified or not - the engine asks its own precondition by its plain name
				if (!java.util.regex.Pattern.compile("\\brequireCodeEndsAtDataBoundary\\s*\\(\\s*\\w+\\s*\\)")
						.matcher(code).find()) {
					unasked.add(f.getName());
				}
			}
		}
		//ONE WRITER (F087): both transplants write through PawnClosure.inject now, and neither injector
		//writes at the boundary itself
		check(writers.contains("PawnClosure.java") && !writers.contains("SignWrapperInjector.java")
				&& !writers.contains("MsgWrapperInjector.java"),
				"control: the writers at the code/data boundary are found (" + writers + ")");
		check(unasked.isEmpty(), "and every one asks the precondition before it writes" + (unasked.isEmpty() ? ""
				: " - these do not: " + unasked));

		System.out.println("--- a zone whose entities do not read is refused, not handed back short (F149)");
		byte[] script = HandedGameTest.amx();
		MapResizeTest.putU16(script, 4, 0);
		byte[] entities = new byte[12 + script.length];
		System.arraycopy(script, 0, entities, 12, script.length);
		String why = null;
		try {
			new ZoneEntities(entities);
		} catch (IllegalStateException ex) {
			why = ex.getMessage();
		}
		check(why != null && why.contains("magic"), "an entity block whose script does not read is refused with the script's reason: " + why);
		byte[] cutShort = new byte[12];
		cutShort[5] = 3;   //three NPCs declared, none there
		why = null;
		try {
			new ZoneEntities(cutShort);
		} catch (IllegalStateException ex) {
			why = ex.getMessage();
		}
		check(why != null && why.contains("entities could not be read"), "and one that ends before its records is refused: " + why);
		byte[] furnitureShort = new byte[12];
		furnitureShort[4] = 1;   //one piece of furniture declared, none there - its reader logged and went on
		why = null;
		try {
			new ZoneEntities(furnitureShort);
		} catch (IllegalStateException ex) {
			why = ex.getMessage();
		}
		check(why != null && why.contains("entities could not be read"), "as is one whose furniture ends short: " + why);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The retail-shaped script with one 8-byte public and four bytes of code. */
	static boolean boundaryHolds(GFLPawnScript s) {
		return refusal(s) == null;
	}

	/** What the injectors' precondition says about {@code s}, or null when it lets it through. */
	static String refusal(GFLPawnScript s) {
		try {
			ctrmap.formats.scripts.PawnClosure.requireCodeEndsAtDataBoundary(s);
			return null;
		} catch (ctrmap.formats.scripts.PawnClosure.Refused said) {
			return said.getMessage();
		}
	}

	static byte[] withOnePublic() {
		byte[] b = Arrays.copyOf(HandedGameTest.amx(), 0x3C + 8 + 4);
		for (int off = 12; off < 0x3C; off += 4) {
			MapResizeTest.putI32(b, off, 0x3C + 8);    //every table but publics, and the code, after the one entry
		}
		MapResizeTest.putI32(b, 28, 0);                //main entry point
		MapResizeTest.putI32(b, 32, 0x3C);             //publics straight after the header
		MapResizeTest.putI32(b, 0, 0x3C + 8 + 4);      //length: header, the entry, four bytes of code
		MapResizeTest.putI32(b, 20, 0x3C + 8 + 4);     //heap after the code
		MapResizeTest.putI32(b, 0x3C, 0x1234);
		return b;
	}

	static void refused(String what, byte[] script, String says) {
		String why = null;
		Throwable other = null;
		try {
			new GFLPawnScript(script);
		} catch (IllegalStateException ex) {
			why = ex.getMessage();
		} catch (RuntimeException ex) {
			other = ex;
		}
		check(why != null && why.contains(says), what + " is refused in words: " + (why != null ? why : other));
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
