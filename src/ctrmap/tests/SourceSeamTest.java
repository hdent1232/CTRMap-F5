package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The game-seam and privacy guard: fails when game-specific knowledge leaks
 * outside its one allowed home, or when anything in the repository names a
 * real person's machine. Comments are stripped from Java before scanning, so
 * only live string literals count; other text files are scanned whole.
 * <ul>
 * <li>RomFS GARC paths ("a/0/3/9"-shaped literals) anywhere outside
 *     ctrmap.gamedef - per-game paths belong in a GameProfile;</li>
 * <li>the pristine-dump folder name in NON-test sources - a shared tool must
 *     never hardcode where one person keeps their dump;</li>
 * <li>an absolute path into somebody's home directory ("C:\Users\someone",
 *     "/home/someone/", "/Users/someone/") in ANY file, tests included.</li>
 * </ul>
 *
 * <p>The last rule exists because the first two were not enough. Tests were
 * exempted wholesale on the reasoning that they run against a real dump by
 * design - so forty-five of them sat in the public repository with the
 * author's own home directory spelled out in full, and this guard passed
 * every time. Running against a dump justifies naming the dump folder; it
 * never justified naming the person. Tests are now exempt from the first two
 * rules only.
 *
 * <p>A line that genuinely must show an absolute path (documentation teaching
 * someone where their own files live) can carry the marker {@code SEAM-OK}.
 *
 * Usage: java ctrmap.tests.SourceSeamTest [src-root]   (default "src")
 */
public class SourceSeamTest {

	private static final Pattern GARC_PATH = Pattern.compile("\"[^\"]*\\ba/\\d/\\d/\\d");
	private static final Pattern DUMP_PATH = Pattern.compile("\"[^\"]*RomFS_original_garcs");
	/** Somebody's home directory, on any of the three platforms. */
	private static final Pattern HOME_PATH = Pattern.compile(
			"(?i)[a-z]:[\\\\/]+users[\\\\/]+[^\"\\\\/\\s*<>]+|/home/[^\"/\\s*<>]+/|/Users/[^\"/\\s*<>]+/");
	/** Text files worth scanning for home paths outside the source tree. */
	private static final String[] TEXT_EXT = {
		".md", ".txt", ".ps1", ".bat", ".cmd", ".sh", ".tsv", ".json", ".xml", ".properties", ".gitignore"
	};
	private static final String[] SKIP_DIRS = {".git", "build", "dist", "lib", "out", "target", "nbproject"};

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		List<String> violations = new ArrayList<>();
		int scanned = scan(new File(root, "ctrmap"), violations);
		File repo = root.getAbsoluteFile().getParentFile();
		int texts = scanText(repo, violations);
		for (String v : violations) {
			System.out.println("  LEAK: " + v);
		}
		System.out.println("seam guard: " + scanned + " sources + " + texts + " text files scanned, "
				+ violations.size() + " leak(s)");
		System.out.println(violations.isEmpty() ? "ALL PASS" : "FAILURES PRESENT");
		if (!violations.isEmpty()) {
			System.exit(1);
		}
	}

	/**
	 * Sweeps the repository's non-Java text - READMEs, build scripts, data
	 * tables - for home paths. The Java scan alone would have missed the
	 * documentation, which is where a path is most likely to be pasted.
	 */
	private static int scanText(File dir, List<String> out) throws Exception {
		int n = 0;
		File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				boolean skip = false;
				for (String s : SKIP_DIRS) {
					skip |= f.getName().equalsIgnoreCase(s);
				}
				if (!skip) {
					n += scanText(f, out);
				}
				continue;
			}
			boolean text = false;
			for (String e : TEXT_EXT) {
				text |= f.getName().toLowerCase().endsWith(e);
			}
			if (!text) {
				continue;
			}
			n++;
			String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				if (lines[i].contains("SEAM-OK")) {
					continue;
				}
				if (HOME_PATH.matcher(lines[i]).find()) {
					out.add(f.getName() + ":" + (i + 1) + " home directory in a shipped file: " + lines[i].trim());
				}
			}
		}
		return n;
	}

	private static int scan(File dir, List<String> out) throws Exception {
		int n = 0;
		File[] files = dir.listFiles();
		if (files == null) {
			return 0;
		}
		for (File f : files) {
			if (f.isDirectory()) {
				n += scan(f, out);
				continue;
			}
			if (!f.getName().endsWith(".java")) {
				continue;
			}
			String path = f.getPath().replace('\\', '/');
			boolean isTest = path.contains("/ctrmap/tests/");
			boolean isGamedef = path.contains("/ctrmap/gamedef/");
			//this guard spells the patterns out, so it cannot scan itself
			boolean isSelf = f.getName().equals("SourceSeamTest.java");
			n++;
			String src = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			String[] lines = src.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (line.contains("SEAM-OK") || isSelf) {
					continue;
				}
				if (!isTest && !isGamedef && GARC_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " GARC path literal outside gamedef: " + line.trim());
				}
				if (!isTest && DUMP_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " hardcoded dump location: " + line.trim());
				}
				//no exemption: a test may name the dump folder, never its owner
				if (HOME_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " home directory in a shipped file: " + line.trim());
				}
			}
		}
		return n;
	}

	/** Removes // and block comments while preserving line numbers and strings. */
	static String stripComments(String src) {
		StringBuilder out = new StringBuilder(src.length());
		boolean inStr = false, inChar = false, inLine = false, inBlock = false;
		for (int i = 0; i < src.length(); i++) {
			char c = src.charAt(i);
			char n = i + 1 < src.length() ? src.charAt(i + 1) : 0;
			if (inLine) {
				if (c == '\n') {
					inLine = false;
					out.append(c);
				}
				continue;
			}
			if (inBlock) {
				if (c == '*' && n == '/') {
					inBlock = false;
					i++;
				} else if (c == '\n') {
					out.append(c);
				}
				continue;
			}
			if (inStr) {
				out.append(c);
				if (c == '\\') {
					if (i + 1 < src.length()) {
						out.append(n);
						i++;
					}
				} else if (c == '"') {
					inStr = false;
				}
				continue;
			}
			if (inChar) {
				out.append(c);
				if (c == '\\') {
					if (i + 1 < src.length()) {
						out.append(n);
						i++;
					}
				} else if (c == '\'') {
					inChar = false;
				}
				continue;
			}
			if (c == '/' && n == '/') {
				inLine = true;
				i++;
				continue;
			}
			if (c == '/' && n == '*') {
				inBlock = true;
				i++;
				continue;
			}
			if (c == '"') {
				inStr = true;
			} else if (c == '\'') {
				inChar = true;
			}
			out.append(c);
		}
		return out.toString();
	}
}
