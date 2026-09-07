package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Proves that {@link ClassFileScanner} sees what a regular expression over
 * source text cannot, measured against this repository's own compiled classes.
 *
 * <p>WHY THIS SUITE EXISTS. Every structural guard here except the public
 * static ceiling was a grep, and a grep reads one spelling of a reference. The
 * two dodges are not hypothetical, and neither was written to dodge anything -
 * they are ordinary Java:
 * <ul>
 * <li>A static import. A file that says {@code import static
 *     ctrmap.CtrmapMainframe.*} then names the window's widgets bare, and a
 *     {@code CtrmapMainframe.} pattern never matches it. The class file
 *     records the same {@code getstatic ctrmap/CtrmapMainframe.field} either
 *     way, which is why the two counts below differ by so much.</li>
 * <li>A folded constant. javac inlines a {@code static final String} into
 *     every user and folds constant concatenation, so a literal path can sit
 *     in the constant pool of a class whose source never spells one.
 *     {@code Workspace} and {@code WorkspaceSession} carry "/a/3/0/0" for
 *     exactly that reason, through {@code OrasProfile.DEMO_PROBE}, and
 *     SourceSeamTest's GARC pattern finds nothing in either source.</li>
 * </ul>
 *
 * <p>HOW IT PROVES IT WITHOUT A COMPILER. A suite must run from the battery
 * with no JDK present, so nothing here is compiled at test time. The
 * measurements are taken from the real {@code build/classes} and the real
 * {@code src}, which BatteryHygieneTest already holds to being one tree built
 * by build.ps1 from those sources. The claims are therefore about this
 * repository as it stands, and any of them can be checked by hand.
 *
 * <p>Nothing here is a new rule about the program. It asserts what the scanner
 * can see, not what the code may do, so it cannot fail because somebody wrote
 * an ordinary class.
 *
 * <p>ORDER: needs no dump, no workspace and no scratch space, and writes no
 * file. It can run first, last, or alone.
 *
 * Usage: java ctrmap.tests.ClassFileScannerTest [src-root] [classes-root]
 */
public class ClassFileScannerTest {

	/** The window whose widgets half the program reaches into. */
	private static final String MAINFRAME = "ctrmap/CtrmapMainframe";
	/** What a grep for the mainframe looks like, as the other guards spell it. */
	private static final Pattern SOURCE_MENTION = Pattern.compile("CtrmapMainframe\\s*\\.");
	/** ...and the one line that names it without using it. */
	private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*import\\b");
	private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+ctrmap\\.CtrmapMainframe\\s*\\.");
	/** A romfs archive path, the shape SourceSeamTest keeps out of the program. */
	private static final Pattern GARC_PATH = Pattern.compile("(^|/)a/\\d/\\d/\\d");

	static int fails = 0;

	static void check(boolean cond, String what) {
		if (!cond) {
			System.out.println("  FAIL: " + what);
			fails++;
		} else {
			System.out.println("  ok: " + what);
		}
	}

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		File classes = new File(args.length > 1 ? args[1] : "build/classes");

		List<ClassFileScanner.ClassFile> app = theProgramWasRead(src, classes);
		if (!app.isEmpty()) {
			bytecodeSeesMoreReadersThanAGrep(src, app);
			innerClassesFoldToTheirOuterClass(app);
			aFoldedConstantIsVisibleWhereTheSourceIsSilent(src, app);
			callSitesAreFoundByOwnerAndMethod(app);
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * There is a built program to measure, and the suites are not part of it.
	 * A scanner that quietly returns nothing would make every check below pass
	 * for the worst possible reason, which is the failure shape this whole
	 * battery is built to refuse.
	 */
	static List<ClassFileScanner.ClassFile> theProgramWasRead(File src, File classes) throws Exception {
		if (!classes.isDirectory() || !new File(src, "ctrmap").isDirectory()) {
			check(false, "there is a compiled program and a source tree to measure - looked in "
					+ classes + " and " + src + " (run build.ps1, or pass both roots as arguments)");
			return new ArrayList<>();
		}
		List<ClassFileScanner.ClassFile> all = ClassFileScanner.scan(classes);
		List<ClassFileScanner.ClassFile> app = ClassFileScanner.application(classes);
		int refs = 0, strings = 0;
		for (ClassFileScanner.ClassFile cf : app) {
			refs += cf.refs.size();
			strings += cf.strings.size();
		}
		System.out.println("  scanned " + all.size() + " classes, " + app.size()
				+ " outside ctrmap.tests, holding " + refs + " references and " + strings + " string constants");
		check(app.size() >= 400 && refs >= 10000 && strings >= 1000,
				"the scanner read a whole program (" + app.size() + " classes, " + refs
				+ " references, " + strings + " strings)");

		Set<String> tests = new LinkedHashSet<>();
		for (ClassFileScanner.ClassFile cf : all) {
			if (cf.isTest()) {
				tests.add(cf.name);
			}
		}
		check(tests.contains("ctrmap/tests/ClassFileScannerTest") && tests.size() + app.size() == all.size(),
				"the test filter separates the battery from the program (" + tests.size()
				+ " suite classes, this one among them)");
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.isTest()) {
				check(false, "application() returned a suite class: " + cf.name);
				break;
			}
		}
		return app;
	}

	/**
	 * The gap this scanner exists to close: the bytecode names more readers of
	 * the main window than any pattern over the sources can, because a static
	 * import leaves nothing to match on.
	 */
	static void bytecodeSeesMoreReadersThanAGrep(File src, List<ClassFileScanner.ClassFile> app) throws Exception {
		Set<String> readers = new TreeSet<>(ClassFileScanner.readersOf(app, MAINFRAME));
		readers.remove(MAINFRAME); //itself, and every listener that folded into it

		Set<String> grepped = new TreeSet<>();
		Set<String> staticImporters = new TreeSet<>();
		for (File f : sources(new File(src, "ctrmap"))) {
			String path = f.getPath().replace('\\', '/');
			if (path.contains("/ctrmap/tests/")) {
				continue; //the program, not its battery
			}
			String internal = internalName(src, f);
			if (internal.equals(MAINFRAME)) {
				continue;
			}
			String text = SourceSeamTest.stripComments(read(f));
			if (STATIC_IMPORT.matcher(text).find()) {
				staticImporters.add(internal);
			}
			for (String line : text.split("\n", -1)) {
				if (!IMPORT_LINE.matcher(line).find() && SOURCE_MENTION.matcher(line).find()) {
					grepped.add(internal);
					break;
				}
			}
		}

		Set<String> gap = new TreeSet<>(readers);
		gap.removeAll(grepped);
		Set<String> byStaticImport = new TreeSet<>(gap);
		byStaticImport.retainAll(staticImporters);

		System.out.println("  readers of CtrmapMainframe: " + readers.size() + " in the bytecode, "
				+ grepped.size() + " files a \"CtrmapMainframe.\" grep finds; " + staticImporters.size()
				+ " sources static-import it");
		System.out.println("  the grep misses " + gap.size() + ", of which " + byStaticImport.size()
				+ " static-import the window: " + first(byStaticImport, 5));
		check(readers.size() > grepped.size(),
				"the scanner reports more readers of the main window than a source grep finds files ("
				+ readers.size() + " > " + grepped.size() + ")");
		check(!byStaticImport.isEmpty(), "and the difference is the static importers, which a grep for"
				+ " \"CtrmapMainframe.\" cannot see at all (" + byStaticImport.size() + " of them)");
		check(gap.equals(byStaticImport), "every reader the grep misses is one of them"
				+ (gap.equals(byStaticImport) ? "" : " - these are missed for some other reason: "
						+ minus(gap, byStaticImport)));
	}

	/**
	 * An anonymous listener is not a separate reader of anything. Folding
	 * {@code Foo$1} to {@code Foo} is what makes a reader count mean "files
	 * that would have to be edited". Measured by taking the fold out: the
	 * window's reader count went from 45 to 68, twenty-four anonymous and
	 * inner classes counted as readers in their own right, fourteen of them
	 * the window's own listeners.
	 */
	static void innerClassesFoldToTheirOuterClass(List<ClassFileScanner.ClassFile> app) {
		Set<String> readers = ClassFileScanner.readersOf(app, MAINFRAME);
		List<String> dollars = new ArrayList<>();
		for (String r : readers) {
			if (r.indexOf('$') >= 0) {
				dollars.add(r);
			}
		}
		check(dollars.isEmpty(), "no inner or anonymous class is reported as a reader in its own right"
				+ (dollars.isEmpty() ? "" : " - these were: " + first(new TreeSet<>(dollars), 8)));

		//...and there was really something to fold, so the check above cannot
		//pass merely because the tree has no inner classes touching the window
		List<String> folded = new ArrayList<>();
		List<String> lost = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : app) {
			if (cf.name.indexOf('$') < 0) {
				continue;
			}
			for (ClassFileScanner.Ref r : cf.refs) {
				if (r.owner.equals(MAINFRAME)) {
					folded.add(cf.name);
					if (!readers.contains(cf.topLevel())) {
						lost.add(cf.name);
					}
					break;
				}
			}
		}
		System.out.println("  inner and anonymous classes touching the main window: " + folded.size()
				+ " (" + first(new TreeSet<>(folded), 4) + ")");
		check(folded.size() >= 10, "there are inner classes touching the window for the fold to apply to ("
				+ folded.size() + ")");
		check(lost.isEmpty(), "and each is counted against the file it lives in, not dropped"
				+ (lost.isEmpty() ? "" : " - these vanished: " + lost));
	}

	/**
	 * The second dodge: a literal that only exists in the bytecode. javac
	 * inlines {@code OrasProfile.DEMO_PROBE} and folds constant concatenation,
	 * so Workspace and WorkspaceSession both carry a romfs path that neither
	 * source contains. A guard that greps the sources for GARC paths cannot see
	 * these; the scanner reads them out of the constant pool.
	 */
	static void aFoldedConstantIsVisibleWhereTheSourceIsSilent(File src, List<ClassFileScanner.ClassFile> app) throws Exception {
		Set<String> holders = ClassFileScanner.holdersOfString(app, GARC_PATH);
		check(holders.contains("ctrmap/gamedef/OrasProfile"),
				"a class that spells its archive paths out is found by its strings ("
				+ holders.size() + " classes hold a romfs path)");

		String[] inliners = {"ctrmap/Workspace", "ctrmap/WorkspaceSession"};
		List<String> unseen = new ArrayList<>();
		List<String> alsoInSource = new ArrayList<>();
		for (String name : inliners) {
			boolean hasIt = false;
			for (ClassFileScanner.ClassFile cf : app) {
				if (cf.name.equals(name) && cf.strings.contains("/a/3/0/0")) {
					hasIt = true;
					break;
				}
			}
			if (!hasIt) {
				unseen.add(name);
			}
			File source = new File(src, name + ".java");
			if (source.isFile() && GARC_PATH.matcher(SourceSeamTest.stripComments(read(source))).find()) {
				alsoInSource.add(name);
			}
		}
		check(unseen.isEmpty(), "the scanner finds the constant javac inlined into "
				+ java.util.Arrays.toString(inliners) + " (DEMO_PROBE, \"/a/3/0/0\")"
				+ (unseen.isEmpty() ? "" : " - not in " + unseen));
		check(alsoInSource.isEmpty(), "...which is a path neither source spells, so no grep over src could"
				+ " have found it" + (alsoInSource.isEmpty() ? "" : " - but " + alsoInSource + " does spell one"));
	}

	/**
	 * The call-site query the dialog seam wants: who holds a method reference
	 * to an owner, matched by a name pattern rather than by how the call was
	 * written. Asserted only against {@link ctrmap.Ui}, which is where the
	 * seam's own dialogs live; the rule about everybody else stays
	 * DialogSeamTest's.
	 */
	static void callSitesAreFoundByOwnerAndMethod(List<ClassFileScanner.ClassFile> app) {
		Set<String> callers = ClassFileScanner.callersOf(app, "javax/swing/JOptionPane", "show*Dialog");
		List<String> dollars = new ArrayList<>();
		for (String c : callers) {
			if (c.indexOf('$') >= 0) {
				dollars.add(c);
			}
		}
		System.out.println("  classes calling JOptionPane.show*Dialog: " + callers.size()
				+ " " + first(new TreeSet<>(callers), 6));
		check(callers.contains("ctrmap/Ui"), "the dialog seam itself is found by owner and method pattern");
		check(dollars.isEmpty(), "and call sites fold to their outer class too"
				+ (dollars.isEmpty() ? "" : " - these did not: " + dollars));
	}

	// ------------------------------------------------------------------- helpers

	static String internalName(File srcRoot, File java) {
		String rel = srcRoot.toURI().relativize(java.toURI()).getPath();
		return rel.substring(0, rel.length() - ".java".length());
	}

	static List<File> sources(File dir) {
		List<File> out = new ArrayList<>();
		File[] kids = dir.listFiles();
		if (kids == null) {
			return out;
		}
		for (File f : kids) {
			if (f.isDirectory()) {
				out.addAll(sources(f));
			} else if (f.getName().endsWith(".java")) {
				out.add(f);
			}
		}
		return out;
	}

	static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	/** The first few of a set, named so a failure says which ones. */
	static String first(Set<String> s, int n) {
		StringBuilder sb = new StringBuilder("[");
		int i = 0;
		for (String v : s) {
			if (i == n) {
				sb.append(", ...").append(s.size() - n).append(" more");
				break;
			}
			sb.append(i++ > 0 ? ", " : "").append(v);
		}
		return sb.append(']').toString();
	}

	static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> out = new TreeSet<>(a);
		out.removeAll(b);
		return out;
	}
}
