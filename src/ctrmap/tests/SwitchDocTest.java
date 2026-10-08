package ctrmap.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every JVM switch the program reads is written down where a user can find it (F092).
 *
 * <p>WHY THIS SUITE EXISTS. The terrain painter read five switches nobody had written down -
 * three of them deleted the geometry that seals a painted map ({@code -Dnobacking},
 * {@code -Dnoposts}, {@code -Dnogusset}), two printed diagnostics - and the one real option among
 * them ({@code -Dterrain}, an alternative terrain catalogue) was documented only in a javadoc. A
 * switch nobody can find is either dead or a trap. The switches were removed or documented; this
 * holds every {@code System.getProperty} in the program to a {@code -D<name>} in a root .md file.
 *
 * <p>Read from the source tree, every production file, the argument resolved when it is a
 * constant of the same class. An argument this cannot resolve is refused, not skipped: "I could
 * not read which switch" is not "there is no switch".
 *
 * <p>ORDER: needs no dump.
 *
 * Usage: java ctrmap.tests.SwitchDocTest [repo root]
 */
public class SwitchDocTest {

	static int fails = 0;

	/** Properties the platform defines; the program reads them, it does not own them. */
	static final String[] PLATFORM = {"java.", "os.", "user.", "file.", "line.", "path.", "jpackage.", "sun.", "awt."};

	static final Pattern READ = Pattern.compile(
			"(?:System\\.getProperty|Boolean\\.getBoolean|Integer\\.getInteger|Long\\.getLong)\\s*\\(\\s*([^,)]+)");

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : ".");
		Map<String, String> switches = new TreeMap<>();
		List<String> unread = new ArrayList<>();
		int files = 0;
		for (File f : DialogSeamTest.javaSources(new File(root, "src/ctrmap"))) {
			if (f.getPath().replace('\\', '/').contains("/ctrmap/tests/")) {
				continue;
			}
			files++;
			String src = SourceSeamTest.stripComments(DialogSeamTest.read(f));
			Matcher m = READ.matcher(src);
			while (m.find()) {
				String arg = m.group(1).trim();
				String name = null;
				if (arg.startsWith("\"") && arg.endsWith("\"")) {
					name = arg.substring(1, arg.length() - 1);
				} else if (arg.matches("[A-Za-z_][A-Za-z0-9_]*")) {
					Matcher c = Pattern.compile("\\b" + arg + "\\s*=\\s*\"([^\"]+)\"").matcher(src);
					name = c.find() ? c.group(1) : null;
				}
				if (name == null) {
					unread.add(f.getName() + ": " + arg);
				} else if (!platform(name)) {
					switches.put(name, f.getName());
				}
			}
		}
		check(files > 200 && switches.containsKey("ctrmap.vault.root") && switches.containsKey("ctrmap.log.root"),
				"control: " + files + " production files read, and both a literal switch (Vault's) and one named"
				+ " by a constant (ErrorSink's) are found: " + switches.keySet());
		check(unread.isEmpty(), "every switch read is one this can name" + (unread.isEmpty() ? "" : " - not " + unread));
		StringBuilder docs = new StringBuilder();
		File[] mds = root.listFiles((d, n) -> n.endsWith(".md"));
		for (File md : mds == null ? new File[0] : mds) {
			docs.append(DialogSeamTest.read(md)).append('\n');
		}
		check(docs.length() > 1000, "control: the root .md files were read (" + docs.length() + " chars)");
		List<String> undocumented = new ArrayList<>();
		for (Map.Entry<String, String> s : switches.entrySet()) {
			if (!Pattern.compile("-D" + Pattern.quote(s.getKey()) + "(?![A-Za-z0-9_.])").matcher(docs).find()) {
				undocumented.add("-D" + s.getKey() + " (" + s.getValue() + ")");
			}
		}
		check(undocumented.isEmpty(), "every one of the " + switches.size() + " switches is written down as -D<name>"
				+ " in a root .md" + (undocumented.isEmpty() ? "" : " - not " + undocumented));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static boolean platform(String name) {
		for (String p : PLATFORM) {
			if (name.startsWith(p)) {
				return true;
			}
		}
		return false;
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
