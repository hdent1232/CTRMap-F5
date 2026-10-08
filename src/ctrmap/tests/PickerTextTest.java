package ctrmap.tests;

import ctrmap.formats.tilemap.PaintedMaterials;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a material picker tells the user about its order is the order it has (F017).
 *
 * <p>WHY THIS SUITE EXISTS. The blank-canvas picker was reordered ground first, because on 312
 * of the first 400 retail regions the biggest mesh is a wall, a fence or the sea - and fifty lines
 * later the same dialog told the user "largest first - the top entry is almost always the
 * ground". The description now lives beside the order, as one constant, and this holds every
 * user-facing string to it.
 *
 * <p>ORDER: needs no dump; it reads the production source.
 *
 * Usage: java ctrmap.tests.PickerTextTest
 */
public class PickerTextTest {

	static int fails = 0;

	/** String literals that describe a size order, in comment-blanked source. */
	static final Pattern SIZE_ORDER = Pattern.compile("\"[^\"\\n]*(?:largest|biggest) first[^\"\\n]*\"");

	public static void main(String[] args) throws Exception {
		check(PaintedMaterials.GROUND_FIRST_ORDER.startsWith("this map's main ground first"),
				"the order's description puts the ground first: " + PaintedMaterials.GROUND_FIRST_ORDER);
		String control = "Object[] form = {\"materials, largest first - the top entry is almost always the\"}; // biggest first\n";
		check(literals(control).size() == 1, "control: a size-order string is seen, and a comment is not: " + literals(control));
		List<String> found = new ArrayList<>();
		int files = 0;
		boolean constantSeen = false;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			files++;
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			for (String lit : literals(src)) {
				if (f.getName().equals("PaintedMaterials.java") && src.contains("GROUND_FIRST_ORDER = " + lit)) {
					constantSeen = true;
				} else {
					found.add(f.getName() + ": " + lit);
				}
			}
		}
		check(files > 250 && constantSeen, "control: the constant itself is found among " + files + " files");
		check(found.isEmpty(), "and no other user-facing string describes a size order: " + found);
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static List<String> literals(String src) {
		List<String> out = new ArrayList<>();
		Matcher m = SIZE_ORDER.matcher(SourceSeamTest.stripComments(src));
		while (m.find()) {
			out.add(m.group());
		}
		return out;
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
