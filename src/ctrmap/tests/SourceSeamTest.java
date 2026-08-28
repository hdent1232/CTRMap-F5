package ctrmap.tests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The game-seam guard: fails when game-specific knowledge leaks outside its
 * one allowed home. Scans the editor SOURCE tree (string literals only -
 * comments are stripped) and flags:
 * <ul>
 * <li>RomFS GARC paths ("a/0/3/9"-shaped literals) anywhere outside
 *     ctrmap.gamedef - per-game paths belong in a GameProfile;</li>
 * <li>absolute paths into the developer's machine ("C:/Users", "C:\Users")
 *     or the pristine-dump folder name in NON-test sources - a shared tool
 *     must never hardcode one person's dump location.</li>
 * </ul>
 * Test sources are exempt (they run against the pristine dump by design).
 *
 * Usage: java ctrmap.tests.SourceSeamTest [src-root]   (default "src")
 */
public class SourceSeamTest {

	private static final Pattern GARC_PATH = Pattern.compile("\"[^\"]*\\ba/\\d/\\d/\\d");
	private static final Pattern USER_PATH = Pattern.compile("\"[^\"]*(C:/Users|C:\\\\\\\\Users|RomFS_original_garcs)");

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		if (!new File(root, "ctrmap").isDirectory()) {
			System.out.println("FAIL: source root not found: " + root.getAbsolutePath());
			System.exit(1);
		}
		List<String> violations = new ArrayList<>();
		int scanned = scan(new File(root, "ctrmap"), violations);
		for (String v : violations) {
			System.out.println("  LEAK: " + v);
		}
		System.out.println("seam guard: " + scanned + " sources scanned, " + violations.size() + " leak(s)");
		System.out.println(violations.isEmpty() ? "ALL PASS" : "FAILURES PRESENT");
		if (!violations.isEmpty()) {
			System.exit(1);
		}
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
			if (isTest) {
				continue;
			}
			n++;
			String src = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			String[] lines = src.split("\n", -1);
			for (int i = 0; i < lines.length; i++) {
				String line = lines[i];
				if (!isGamedef && GARC_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " GARC path literal outside gamedef: " + line.trim());
				}
				if (USER_PATH.matcher(line).find()) {
					out.add(f.getName() + ":" + (i + 1) + " machine-specific path: " + line.trim());
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
