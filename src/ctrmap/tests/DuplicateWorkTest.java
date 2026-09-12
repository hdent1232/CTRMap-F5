package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A capability has ONE implementation, and a new class has to say what it is not.
 *
 * <p>WHY THIS EXISTS, and it is not a general theory. On 2026-09-12 the owner asked
 * why a zone preview had been built as a second, smaller copy of the editor's map
 * loader instead of using the one that has drawn zones correctly for years. Every
 * defect they reported about that preview - one region instead of the map, a region
 * belonging to the zone next door, four cells of a thirteen-cell map - was a hole in
 * the copy, not in the editor. The copy is deleted now.
 *
 * <p>The instruction "remove duplication" has been given to this project more than
 * once, and a sweep was run against it. That sweep counted public statics, coupling
 * to the window globals, god objects, dialogs and mutation coverage - every one of
 * them a number that could be ratcheted - and never counted implementations, which
 * is where duplication actually shows up. So the sweep could not have removed this
 * and the report did not say so. A rule with no counter behind it applies to
 * nothing; that has now been true here three times, for three different rules.
 *
 * <p>WHAT IT REFUSES:
 * <ul>
 * <li>A second file doing something a named class already owns. The table below is
 *     seeded with the capabilities that have actually been duplicated, each with the
 *     marker that gives it away and the files allowed to carry it. New entries are
 *     welcome; new UNARGUED users are not.</li>
 * <li>A production class that is not in {@code tools/guard/classes.txt} and does not
 *     say, in its own javadoc, which existing class does the nearest job and why it
 *     could not be used. That line is the search this project keeps skipping, made
 *     into something a build can check: you cannot write it without having looked.</li>
 * </ul>
 *
 * <p>IT RUNS IN THE BUILD, not only in the battery. A guard that runs forty minutes
 * later lets the wrong thing be written, run and shipped first; the point of action
 * for authoring is the build, and this is the whole reason it sits there.
 *
 * <p>ORDER: needs no game, no dump and no display; reads source and writes nothing.
 *
 * Usage: java ctrmap.tests.DuplicateWorkTest [src-root]   (default "src")
 */
public class DuplicateWorkTest {

	/**
	 * {capability, marker regex, comma-separated owners, why it has one owner}.
	 *
	 * <p>Seeded from what has actually gone wrong. A marker is chosen to be the
	 * thing only that capability does - not a word that could appear anywhere.
	 */
	private static final String[][] OWNED = {
		{"placing a map region in the world", "720f \\+ 360f|720 \\+ 360f|720 \\+ 360[^0-9]",
			"TileMapPanel.java",
			"a region sits at cell * 720 + 360, and the height lookups and picking divide by"
			+ " the same number. A second answer to where region (3,4) goes is a map whose"
			+ " pieces do not line up, which is what the owner was shown."},
		{"assembling a matrix into a drawable map", "loadRegions\\s*\\(",
			"TileMapPanel.java,ZonePreviewPane.java,CtrmapMainframe.java",
			"TileMapPanel.loadRegions is the body; the preview and the window CALL it."
			+ " Anything else naming it is fine - anything else DOING it is the defect this"
			+ " suite exists for."},
		{"preparing map geometry for drawing", "\\.makeAllBOs\\(\\)",
			"H3DModel.java,NPCRegistry.java,ADPropRegistry.java,CustomH3DPreview.java,"
			+ "MapPreview3D.java,PropEditForm.java,TileMapPanel.java",
			"the six views and registries that had this before the rule existed. The list may"
			+ " shrink for free; it grows only when somebody argues for a seventh."},
	};

	/** The snapshot of classes that predate the rule. */
	private static final String LIST = "tools/guard/classes.txt";

	/** What a new class must carry: the thing it is NOT, and why. */
	private static final Pattern NOT_LINE = Pattern.compile(
			"(?m)^\\s*\\*\\s*(?:<p>)?\\s*Not:\\s*\\{?@?l?i?n?k?\\s*([A-Za-z0-9_.#]+)[^-]*-\\s*(.{20,})");

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		File repo = root.getParentFile() == null ? new File(".") : root.getParentFile();
		aCapabilityHasOneImplementation(root);
		aNewClassSaysWhatItIsNot(root, new File(repo, LIST));

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Rule one: the marker of an owned capability appears only where it is owned. */
	static void aCapabilityHasOneImplementation(File root) throws Exception {
		System.out.println("--- a capability has one implementation");
		for (String[] owned : OWNED) {
			Pattern marker = Pattern.compile(owned[1]);
			List<String> allowed = Arrays.asList(owned[2].split(","));
			List<String> strays = new ArrayList<>();
			for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
				if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
					continue;
				}
				String src = SourceSeamTest.stripComments(
						new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
				Matcher m = marker.matcher(src);
				if (m.find() && !allowed.contains(f.getName())) {
					strays.add(f.getName());
				}
			}
			check(strays.isEmpty(), "only " + owned[2] + " does " + owned[0]
					+ (strays.isEmpty() ? "" : " - but so does " + strays + ". " + owned[3]
					+ " If this really is a different job, give it its own entry in OWNED;"
					+ " if it is the same job, call the owner."));
		}
	}

	/**
	 * Rule two: a class written after this rule names the nearest existing thing.
	 *
	 * <p>The snapshot is what existed when the rule arrived - 268 classes nobody is
	 * going back to annotate. Everything after it carries one line:
	 * {@code Not: SomeClass - why it could not be used}.
	 */
	static void aNewClassSaysWhatItIsNot(File root, File list) throws Exception {
		System.out.println("--- a class written since the rule says what it is not");
		if (!list.isFile()) {
			check(false, "no class snapshot at " + list.getPath() + " - regenerate it with"
					+ " tools/guard/snapshot_classes.py, deliberately, because everything"
					+ " missing from it is asked to justify itself");
			return;
		}
		List<String> known = Arrays.asList(new String(Files.readAllBytes(list.toPath()),
				StandardCharsets.UTF_8).split("\\R"));
		List<String> silent = new ArrayList<>();
		int newOnes = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "ctrmap"))) {
			if (f.getParentFile() != null && f.getParentFile().getName().equals("tests")) {
				continue;
			}
			String path = relative(root, f);
			if (known.contains(path)) {
				continue;
			}
			newOnes++;
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			if (!NOT_LINE.matcher(src).find()) {
				silent.add(path);
			}
		}
		check(silent.isEmpty(), newOnes + " class(es) written since the snapshot, each saying"
				+ " what it is not" + (silent.isEmpty() ? "" : " - except " + silent
				+ ". Add a javadoc line \"Not: <the nearest existing class> - <why it could not"
				+ " be used>\". The point is not the line; it is that you cannot write it"
				+ " without having looked, and not looking is how this tree got a second map"
				+ " loader."));
	}

	static String relative(File root, File f) {
		String r = root.getAbsolutePath();
		String p = f.getAbsolutePath();
		return (p.startsWith(r) ? p.substring(r.length() + 1) : p).replace('\\', '/');
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
