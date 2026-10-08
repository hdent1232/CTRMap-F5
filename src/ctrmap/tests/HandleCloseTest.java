package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.TextEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Every file the program opens is closed on every path, every whole-file read reads the whole
 * file, and a text file that cannot be read or written is said (F254, F131, F166).
 *
 * <p>WHY THIS SUITE EXISTS. Twenty handles were closed only as the last statement of their try,
 * so any exception before it left the file open - on Windows, locked until the collector ran,
 * which is the "something is holding the archive" failure the container code names, caused by
 * the editor itself. Six whole-file reads sized their buffer by available() and read once: exact
 * from a directory, which is all the battery ever loads from, and from the shipped jar the right
 * length with zeros at the end. And the text editor showed a file it could not read as an empty
 * table that took typing, then wrote the typing over it.
 *
 * <p>ORDER: needs no dump and no display. The first half reads the production source, so a
 * handle added later is held to it without being named here.
 *
 * Usage: java ctrmap.tests.HandleCloseTest
 */
public class HandleCloseTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			everyHandleIsClosedOnEveryPath();
			noBufferIsSizedByAvailable();
			aResourceReadsWholeFromAJar();
			aTextFileThatDoesNotReadIsNotOffered();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------ the classifier

	static final Pattern SITE = Pattern.compile("\\bnew\\s+(?:java\\.(?:io|util\\.zip)\\.)?(FileInputStream|FileOutputStream|"
			+ "RandomAccessFile|FileReader|FileWriter|ZipFile|ZipInputStream|ZipOutputStream)\\s*\\(|\\b(?:java\\.nio\\.file\\.)?"
			+ "Files\\.(newInputStream|newOutputStream|newBufferedReader|newBufferedWriter)\\s*\\(");
	static final Pattern METHOD = Pattern.compile("(?m)^[ \\t]*(?:(?:public|private|protected|static|final|synchronized)\\s+)+"
			+ "[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*\\([^;{)]*\\)\\s*(?:throws [\\w., ]+)?\\s*\\{");

	/** Comments to spaces and string contents to spaces, every offset and line kept. */
	static String blank(String t) {
		StringBuilder out = new StringBuilder(t.length());
		int i = 0;
		int n = t.length();
		while (i < n) {
			if (t.startsWith("//", i)) {
				int j = t.indexOf('\n', i);
				j = j < 0 ? n : j;
				for (int k = i; k < j; k++) {
					out.append(' ');
				}
				i = j;
			} else if (t.startsWith("/*", i)) {
				int j = t.indexOf("*/", i + 2);
				j = j < 0 ? n : j + 2;
				for (int k = i; k < j; k++) {
					out.append(t.charAt(k) == '\n' ? '\n' : ' ');
				}
				i = j;
			} else if (t.charAt(i) == '"' || t.charAt(i) == '\'') {
				char q = t.charAt(i);
				int j = i + 1;
				while (j < n && t.charAt(j) != q) {
					j += t.charAt(j) == '\\' ? 2 : 1;
				}
				out.append(q);
				for (int k = i + 1; k < Math.min(j, n); k++) {
					out.append(' ');
				}
				if (j < n) {
					out.append(q);
				}
				i = j + 1;
			} else {
				out.append(t.charAt(i));
				i++;
			}
		}
		return out.toString();
	}

	static int matchClose(String t, int k, char open, char close) {
		int d = 0;
		for (int j = k; j < t.length(); j++) {
			if (t.charAt(j) == open) {
				d++;
			} else if (t.charAt(j) == close) {
				d--;
				if (d == 0) {
					return j;
				}
			}
		}
		return t.length();
	}

	/**
	 * Each handle site in one source, as "method: CLASS" - TWR (in a try-with-resources header),
	 * FINALLY (its variable closed in a finally), SUCCESS-ONLY (closed, but not on every path),
	 * NOT-CLOSED, or INLINE (constructed as an argument or a return, no variable of its own).
	 */
	static List<String> classify(String source) {
		String t = blank(source);
		List<int[]> spans = new ArrayList<>();
		List<String> names = new ArrayList<>();
		Matcher mm = METHOD.matcher(t);
		while (mm.find()) {
			int s = mm.end() - 1;
			spans.add(new int[]{s, matchClose(t, s, '{', '}')});
			names.add(mm.group(1));
		}
		List<String> out = new ArrayList<>();
		Matcher m = SITE.matcher(t);
		while (m.find()) {
			int pos = m.start();
			int lo = 0;
			int hi = t.length();
			String name = "?";
			for (int k = 0; k < spans.size(); k++) {
				if (spans.get(k)[0] < pos && pos < spans.get(k)[1]) {
					lo = spans.get(k)[0];
					hi = spans.get(k)[1];
					name = names.get(k);
				}
			}
			out.add(name + ": " + kind(t, pos, lo, hi));
		}
		return out;
	}

	static String kind(String t, int pos, int lo, int hi) {
		Matcher tw = Pattern.compile("\\btry\\s*\\(").matcher(t.substring(lo, pos));
		while (tw.find()) {
			int a = lo + tw.end() - 1;
			if (a < pos && pos < matchClose(t, a, '(', ')')) {
				return "TWR";
			}
		}
		int st = Math.max(Math.max(t.lastIndexOf(';', pos), t.lastIndexOf('{', pos)), t.lastIndexOf('}', pos)) + 1;
		st = Math.max(st, lo);
		Matcher va = Pattern.compile("(\\w+)\\s*=\\s*(?:new\\s+[\\w.<>]+\\s*\\(\\s*)*$").matcher(t.substring(st, pos));
		if (!va.find()) {
			return "INLINE";
		}
		Matcher close = Pattern.compile("\\b" + Pattern.quote(va.group(1)) + "\\s*\\.\\s*close\\s*\\(").matcher(t.substring(pos, hi));
		List<Integer> closes = new ArrayList<>();
		while (close.find()) {
			closes.add(pos + close.start());
		}
		if (closes.isEmpty()) {
			return "NOT-CLOSED";
		}
		Matcher fin = Pattern.compile("\\bfinally\\s*\\{").matcher(t.substring(lo, hi));
		while (fin.find()) {
			int a = lo + fin.end() - 1;
			int b = matchClose(t, a, '{', '}');
			for (int c : closes) {
				if (a <= c && c <= b) {
					return "FINALLY";
				}
			}
		}
		return "SUCCESS-ONLY";
	}

	/**
	 * The two handles that are not closed in the method that opens them, each for a reason that
	 * is checked rather than trusted: the updater's run lock is held for the life of the process
	 * on purpose, and the terrain catalog's opener hands its stream to callers that each close it
	 * in a try-with-resources header.
	 */
	static final String RUN_LOCK = "Updater.java holdRunLock: NOT-CLOSED";
	static final String CATALOG = "TerrainCatalog.java openCatalog: INLINE";

	static void everyHandleIsClosedOnEveryPath() throws Exception {
		System.out.println("--- every file the program opens is closed on every path (F254)");
		String control = "class C {\n"
				+ "\tstatic void a() throws Exception { try (java.io.FileInputStream in = new java.io.FileInputStream(\"x\")) { } }\n"
				+ "\tstatic void b() throws Exception { java.io.FileInputStream in = new java.io.FileInputStream(\"x\"); try { in.read(); } finally { in.close(); } }\n"
				+ "\tstatic void c() throws Exception { java.io.FileInputStream in = new java.io.FileInputStream(\"x\"); in.read(); in.close(); }\n"
				+ "\tstatic void d() throws Exception { java.io.FileInputStream in = new java.io.FileInputStream(\"x\"); in.read(); }\n"
				+ "\tstatic java.io.InputStream e() throws Exception { return new java.io.FileInputStream(\"x\"); }\n"
				+ "\tstatic void f() { String s = \"new FileInputStream(\"; } // new FileOutputStream(y)\n"
				+ "}\n";
		List<String> got = classify(control);
		check(got.equals(Arrays.asList("a: TWR", "b: FINALLY", "c: SUCCESS-ONLY", "d: NOT-CLOSED", "e: INLINE")),
				"control: one of each, and none in a string or a comment: " + got);
		List<String> wrong = new ArrayList<>();
		int sites = 0;
		boolean lockSeen = false;
		boolean catalogSeen = false;
		String catalogSource = null;
		int files = 0;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			files++;
			String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			for (String site : classify(src)) {
				sites++;
				String key = f.getName() + " " + site;
				if (key.equals(RUN_LOCK)) {
					lockSeen = true;
				} else if (key.equals(CATALOG)) {
					catalogSeen = true;
					catalogSource = src;
				} else if (!site.endsWith(": TWR") && !site.endsWith(": FINALLY")) {
					wrong.add(key);
				}
			}
		}
		check(files > 250 && sites > 50 && lockSeen && catalogSeen, "control: " + sites + " handles in " + files
				+ " files, the run lock and the catalog opener among them");
		check(wrong.isEmpty(), "and each is opened in a try-with-resources header or closed in a finally: " + wrong);
		if (catalogSource != null) {
			String t = blank(catalogSource);
			Matcher call = Pattern.compile("\\bopenCatalog\\s*\\(\\s*\\)").matcher(t);
			int calls = 0;
			int owned = 0;
			while (call.find()) {
				//a call, not the declaration: what precedes it assigns it to a stream
				if (t.substring(Math.max(0, call.start() - 60), call.start()).matches("(?s).*InputStream\\s+\\w+\\s*=\\s*$")) {
					calls++;
					if (kind(t, call.start(), 0, t.length()).equals("TWR")) {
						owned++;
					}
				}
			}
			//ONE CALLER SINCE F211: the catalog's three readers became one strict one (TerrainCatalog.rows),
			//so the floor that proved this looked at the calls is one, and every one must close it
			check(calls >= 1 && owned == calls, "the catalog opener's every caller closes it in a try-with-resources header: "
					+ owned + " of " + calls);
		}
	}

	static void noBufferIsSizedByAvailable() throws Exception {
		System.out.println("--- no whole-file read sizes its buffer by available() (F131)");
		Pattern sized = Pattern.compile("new\\s+byte\\s*\\[\\s*[\\w.]+\\s*\\.\\s*available\\s*\\(\\s*\\)\\s*\\]");
		check(sized.matcher("byte[] b = new byte[in.available()];").find(), "control: the shape is seen");
		List<String> found = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			if (sized.matcher(blank(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))).find()) {
				found.add(f.getName());
			}
		}
		check(found.isEmpty(), "none in production: " + found);
	}

	static void aResourceReadsWholeFromAJar() throws Exception {
		System.out.println("--- a bundled resource reads whole from a jar, as the shipped program loads it (F166)");
		File dir = new File("src/ctrmap/resources");
		File jar = new File(Scratch.dir("ctrmap_resjar"), "resources.jar");
		List<File> resources = new ArrayList<>();
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
			for (File f : dir.listFiles()) {
				if (f.isFile() && !f.getName().endsWith(".java")) {
					resources.add(f);
					out.putNextEntry(new JarEntry("ctrmap/resources/" + f.getName()));
					out.write(Files.readAllBytes(f.toPath()));
					out.closeEntry();
				}
			}
		}
		URL classes = ctrmap.resources.ResourceAccess.class.getProtectionDomain().getCodeSource().getLocation();
		List<String> wrong = new ArrayList<>();
		int trapped = 0;
		try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL(), classes}, null)) {
			Class<?> ra = Class.forName("ctrmap.resources.ResourceAccess", true, loader);
			Method read = ra.getMethod("getByteArray", String.class);
			check(ra.getClassLoader() == loader, "control: the reader under test is loaded beside the jar, not the battery's copy");
			for (File f : resources) {
				byte[] want = Files.readAllBytes(f.toPath());
				if (!Arrays.equals(want, (byte[]) read.invoke(null, f.getName()))) {
					wrong.add(f.getName());
				}
				//THE TRAP, measured: what one available()-sized read of the jar's copy gives
				try (InputStream in = loader.getResourceAsStream("ctrmap/resources/" + f.getName())) {
					byte[] once = new byte[in.available()];
					in.read(once);
					if (!Arrays.equals(want, once)) {
						trapped++;
					}
				}
			}
		}
		check(resources.size() > 20 && trapped > 0, "control: from the jar, one available()-sized read gets " + trapped
				+ " of " + resources.size() + " resources wrong - the trap is there to be caught");
		check(wrong.isEmpty(), "and the program's reader gets every one right: " + wrong);
	}

	static void aTextFileThatDoesNotReadIsNotOffered() throws Exception {
		System.out.println("--- a text file that does not read is not offered for editing; a save that fails is said");
		File root = Scratch.dir("ctrmap_textedit");
		File archives = new File(root, "archives");
		archives.mkdirs();
		File gt = new File(archives, "gametext");
		HandedGameTest.writeGarc(gt, GFMessageFile.write(Arrays.asList("hello")), new byte[]{1, 2, 3});
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.GAMETEXT, new GARC(gt));
		ws.prepareDirectories();
		Workspace.install(ws);
		List<String> said = ctrmap.Ui.record();
		TextEditor te = new TextEditor();
		JTable table = (JTable) MatrixEditFormGuardsTest.field(te, "table");
		DefaultTableModel model = (DefaultTableModel) table.getModel();

		te.loadFile(1);
		File bad = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, 1);
		byte[] badBefore = Files.readAllBytes(bad.toPath());
		check(said.size() == 1 && said.get(0).contains("could not be read") && !table.isEnabled()
				&& (Integer) MatrixEditFormGuardsTest.field(te, "loadedIdx") == -1,
				"a file the decoder rejects is said and not offered: " + said);
		model.addRow(new Object[]{0, "typed over it"});
		check(te.store(false) && Arrays.equals(badBefore, Files.readAllBytes(bad.toPath())),
				"and a save writes nothing over it");

		said.clear();
		te.loadFile(0);
		check(said.isEmpty() && table.isEnabled() && model.getRowCount() == 1 && "hello".equals(model.getValueAt(0, 1)),
				"a file that reads is shown and offered: " + said);
		model.setValueAt("goodbye", 0, 1);
		File good = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, 0);
		byte[] goodBefore = Files.readAllBytes(good.toPath());
		//the file becomes a folder with something in it, which no write can replace
		good.delete();
		good.mkdirs();
		Files.write(new File(good, "blocker").toPath(), goodBefore);
		check(!te.store(false) && said.size() == 1 && said.get(0).contains("was not saved"),
				"a save that cannot write says so, where it used to log: " + said);
		new File(good, "blocker").delete();
		good.delete();
		Files.write(good.toPath(), goodBefore);
		said.clear();
		check(te.store(false) && said.isEmpty() && new GFMessageFile(Files.readAllBytes(good.toPath())).getLines().equals(Arrays.asList("goodbye")),
				"and once it can, the edit is written");
		ctrmap.Ui.stopRecording();
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
