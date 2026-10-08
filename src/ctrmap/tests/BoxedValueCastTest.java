package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A field's value is read through {@code Number}, and the casts of one to a boxed type only fall.
 *
 * <p>WHY THIS SUITE EXISTS (F096). The map-matrix Multizone tool threw on its normal use: one
 * field held a Short after one path and an Integer after another, and one reader cast it to Short
 * while two cast it to Integer, so every path threw on a value another had put there. A cast like
 * {@code (Short) field.getValue()} is right only while every writer of that field agrees on the
 * type, which nothing checks - a formatter that pins the value class makes it safe, a field with
 * none makes it a coin toss. {@code ((Number) field.getValue()).intValue()} is right whatever the
 * writers did.
 *
 * <p>A CEILING, NOT A BAN. Most of the remaining casts sit on fields whose formatter pins the
 * class, and telling which do not is a reading of every form - so the count is held and may only
 * fall, and a new cast fails here until it reads through Number instead.
 *
 * <p>ORDER: reads source only, under {@code src}. Needs no build, no dump and no display.
 *
 * Usage: java ctrmap.tests.BoxedValueCastTest [src-root]
 */
public class BoxedValueCastTest {

	/**
	 * Casts of a field's value to a boxed number type in production source, measured by this suite
	 * over src: 2026-10-07, 148 in 20 files (grep -o over the same pattern agrees: 148), once the
	 * Multizone tool's four reads of its zone field went through Number; then 119 in 18 files
	 * (grep -o agrees: 119, 18), the camera form 21 of them down to 12 once its save read its
	 * spinners through Number (F123), with the rest of this batch's form work; then 118 on
	 * 2026-10-08, a cast gone with the 2026-10-06 audit's low fixes; then 117 the same day, the NPC
	 * form's second motion-code read gone into one (F104); then 116 the same day, the Zone tab's second
	 * read of its flag-word box gone (F221).
	 */
	private static final int CEILING = 116;

	static final Pattern CAST = Pattern.compile(
			"\\((Short|Integer|Float|Double|Long|Byte)\\)\\s*[A-Za-z_][A-Za-z0-9_.]*\\.getValue\\(\\)");

	static int fails = 0;

	static int count(String source) {
		int n = 0;
		Matcher m = CAST.matcher(SourceSeamTest.stripComments(source));
		while (m.find()) {
			n++;
		}
		return n;
	}

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		System.out.println("--- a field's value is read through Number; the boxed casts only fall");
		//THE SCAN READS WHAT IT SAYS: one cast, one read through Number, one cast inside a comment
		String sample = "short a = (Short) chunkId.getValue();\n"
				+ "int b = ((Number) f.getValue()).intValue();\n"
				+ "/* (Integer) x.getValue() */\n";
		check(count(sample) == 1, "the scan counts a cast, and not a read through Number or a comment ("
				+ count(sample) + " of 1)");

		Map<String, Integer> byFile = new TreeMap<>();
		int total = 0;
		boolean zoneField = false;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String body = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			int n = count(body);
			if (n > 0) {
				byFile.put(f.getName(), n);
				total += n;
			}
			zoneField |= SourceSeamTest.stripComments(body).matches(
					"(?s).*\\((Short|Integer)\\)\\s*zoneRefNumber\\.getValue\\(\\).*");
		}
		check(total > 0, "and it read something: " + byFile.size() + " file(s) hold a cast");
		check(!zoneField, "the Multizone tool's zone field is read through Number, nowhere cast (F096)");
		System.out.println("  " + total + " cast(s) in " + byFile.size() + " file(s): " + byFile);
		check(total <= CEILING, "boxed cast(s) of a field's value: " + total + ", against a ceiling of " + CEILING
				+ ". This may fall and may not rise: read a new one as ((Number) x.getValue()).intValue()");
		check(total == CEILING, "and the recorded ceiling is the measured count (measured " + total
				+ ", recorded " + CEILING + ") - a slot of slack is a cast this would not notice");
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
