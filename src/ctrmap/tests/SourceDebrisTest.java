package ctrmap.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a removal leaves behind, in every Java file under the source root: a javadoc that
 * documents nothing, and a run of three or more blank lines (F092).
 *
 * <p>WHY THIS SUITE EXISTS. The painter's split left an orphaned two-quad javadoc and nine runs
 * of blank lines a hundred lines long in one file, and the audit that found them was reading for
 * something else. Measured across the tree when this was written: 71 javadocs documenting
 * nothing - 47 in production, 24 in suites - and 17 runs of three or more blank lines in 7
 * files. Almost every orphan had the same history: a member was INSERTED between an existing
 * doc and the declaration it described, so the old doc now sat above the new one, and the
 * member it was written for went on undocumented a screen further down. A reader takes the doc
 * as the newcomer's. Each was found by reading, and nothing would have found the next one.
 *
 * <p>WHAT COUNTS. A javadoc whose next text, past whitespace, is another javadoc, a closing
 * brace or the end of the file. A doc before an annotation, a block comment before a doc, and a
 * {@code /**} inside a string or a char literal are not. A run of THREE or more blank lines -
 * two is a separator some files use between groups of members; three has only ever been what a
 * deletion left behind.
 *
 * <p>AND TWO WAYS A DOC SITS ON THE WRONG MEMBER (F202). A javadoc parted from its declaration
 * by a blank line: the one there was {@code NPCEditForm}'s "a form's widgets stacked top to
 * bottom", whose helper had moved to {@code Forms}, sitting four blank lines above the script
 * dropdown's handler, which javac took it for. And a NetBeans {@code Creates new form X} stub
 * anywhere but on X's constructor: ten sat over the fields that had been inserted below them.
 * A doc on the wrong member that has neither shape - the .bch import's doc on the mod deploy
 * action was one - only a reader can see.
 *
 * <p>AND AN IMPORT NOTHING CAN JUDGE (F090): a wildcard. The same split left each of its seven
 * files importing the other six whole, so an edge the file had and one it did not read the same,
 * and no import check could tell them apart. Every import names what it brings in.
 *
 * <p>SCOPE is the tree, walked: production and suites alike, because a suite's doc that
 * describes the wrong check misleads the same way.
 *
 * Usage: java ctrmap.tests.SourceDebrisTest [src-root]   (default "src")
 */
public class SourceDebrisTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "src");
		controls();
		List<File> files = DialogSeamTest.javaSources(new File(root, "ctrmap"));
		boolean self = false, painter = false;
		for (File f : files) {
			self |= f.getName().equals("SourceDebrisTest.java");
			painter |= f.getName().equals("PaintedRegionBuilder.java");
		}
		check(files.size() > 400 && self && painter, "control: the walk found " + files.size()
				+ " Java files, this suite and the painter among them");
		List<String> lost = new ArrayList<>();
		List<String> gaps = new ArrayList<>();
		List<String> astray = new ArrayList<>();
		List<String> unusedImports = new ArrayList<>();
		List<String> wildcards = new ArrayList<>();
		List<String> commentedOut = new ArrayList<>();
		List<String> csharp = new ArrayList<>();
		for (File f : files) {
			String src = DialogSeamTest.read(f);
			for (int[] c : commentedOutCode(src)) {
				commentedOut.add(f.getName() + ":" + c[0] + " (" + c[1] + " lines of code)");
			}
			for (int line : csharpDocs(src)) {
				csharp.add(f.getName() + ":" + line);
			}
			for (String u : unusedImports(src)) {
				unusedImports.add(f.getName() + ": " + u);
			}
			for (int line : wildcardImports(src)) {
				wildcards.add(f.getName() + ":" + line);
			}
			for (int[] o : orphans(src)) {
				lost.add(f.getName() + ":" + o[0] + " (before " + KINDS[o[1]] + ")");
			}
			for (int[] r : blankRuns(src)) {
				gaps.add(f.getName() + ":" + r[0] + " (" + r[1] + " blank lines)");
			}
			for (int[] m : misplaced(src)) {
				astray.add(f.getName() + ":" + m[0] + " (" + ASTRAY[m[1]] + ")");
			}
		}
		check(lost.isEmpty(), "no javadoc in the " + files.size() + " files documents nothing"
				+ (lost.isEmpty() ? "" : " - these do: " + lost + ". Move each onto the member it describes,"
				+ " merge it into that member's newer doc, or delete it when the member is gone"));
		check(gaps.isEmpty(), "no file holds a run of three or more blank lines"
				+ (gaps.isEmpty() ? "" : " - these do: " + gaps));
		check(astray.isEmpty(), "every javadoc touches its declaration, and every form stub is on its constructor"
				+ (astray.isEmpty() ? "" : " - these are not: " + astray));
		check(unusedImports.isEmpty(), "no file imports a name it never uses"
				+ (unusedImports.isEmpty() ? "" : " - " + unusedImports.size() + " do: " + unusedImports + ". Delete the"
				+ " import: one the file does not use is a dependency edge nothing has, and a bare name it"
				+ " would make available is one keystroke from a reach the file was meant not to have"));
		check(wildcards.isEmpty(), "no file imports by wildcard"
				+ (wildcards.isEmpty() ? "" : " - these do: " + wildcards + ". Import each name the file uses: a"
				+ " wildcard declares an edge to everything in the class or package, so neither this suite nor"
				+ " a reader can tell which of them the file has"));
		check(commentedOut.isEmpty(), "no comment is code put away"
				+ (commentedOut.isEmpty() ? "" : " - these are: " + commentedOut + ". Delete it: the history keeps it,"
				+ " and a reader takes code in a comment for something that still runs, or was meant to"));
		check(csharp.isEmpty(), "no doc comment is C#'s '///', which javadoc does not read"
				+ (csharp.isEmpty() ? "" : " - these are: " + csharp + ". Write it as a javadoc"));
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	static final String[] KINDS = {"another javadoc", "a closing brace", "the end of the file"};

	private static final java.util.regex.Pattern CODE_LINE = java.util.regex.Pattern.compile("[;{}]\\s*$");

	/**
	 * {line, code lines} of each comment that is code put away (F071, F079): a block comment - not
	 * a javadoc - or a run of line comments each on a line of its own, at least three of whose lines
	 * end in ';', '{' or '}'. Read as javac reads comments, so '//*35ms' is a line comment and a
	 * comment opener inside a string is text. Measured when this was written: nine in 501 files -
	 * five commented-out C# methods in the PICA command reader, a BCH material dump, a collision
	 * render, an ETC1 debug print and the mesh optimiser - and none in a suite.
	 */
	static List<int[]> commentedOutCode(String src) {
		List<int[]> out = new ArrayList<>();
		int n = src.length();
		int line = 1;
		boolean codeBefore = false; //something other than whitespace earlier on this line
		int runFirst = 0, runLast = -10, runCode = 0;
		for (int i = 0; i < n; i++) {
			char c = src.charAt(i);
			if (c == '\n') {
				line++;
				codeBefore = false;
			} else if (c == '"' || c == '\'') {
				codeBefore = true;
				int j = i + 1;
				while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
					j += src.charAt(j) == '\\' ? 2 : 1;
				}
				i = j < n && src.charAt(j) == '\n' ? j - 1 : j;
			} else if (src.startsWith("//", i)) {
				int end = src.indexOf('\n', i);
				end = end < 0 ? n : end;
				if (!codeBefore) {
					if (runLast != line - 1) {
						if (runCode >= 3) {
							out.add(new int[]{runFirst, runCode});
						}
						runFirst = line;
						runCode = 0;
					}
					runLast = line;
					runCode += CODE_LINE.matcher(src.substring(i + 2, end).trim()).find() ? 1 : 0;
				}
				i = end - 1;
			} else if (src.startsWith("/*", i)) {
				int end = src.indexOf("*/", i + 2);
				end = end < 0 ? n : end;
				if (!src.startsWith("/**", i) || src.startsWith("/**/", i)) {
					int code = 0;
					for (String l : src.substring(i + 2, end).split("\n")) {
						String t = l.trim();
						while (t.startsWith("*")) {
							t = t.substring(1);
						}
						code += CODE_LINE.matcher(t.trim()).find() ? 1 : 0;
					}
					if (code >= 3) {
						out.add(new int[]{line, code});
					}
				}
				for (int k = i; k < end; k++) {
					line += src.charAt(k) == '\n' ? 1 : 0;
				}
				codeBefore = true;
				i = end + 1;
			} else if (!Character.isWhitespace(c)) {
				codeBefore = true;
			}
		}
		if (runCode >= 3) {
			out.add(new int[]{runFirst, runCode});
		}
		return out;
	}

	/** The lines that open with C#'s XML doc comment, '///', which javadoc does not read (F071). */
	static List<Integer> csharpDocs(String src) {
		List<Integer> out = new ArrayList<>();
		String[] lines = src.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("///")) {
				out.add(i + 1);
			}
		}
		return out;
	}

	private static final java.util.regex.Pattern IMPORT = java.util.regex.Pattern.compile(
			"(?m)^import\\s+(?:static\\s+)?[\\w.]+\\.(\\w+)\\s*;[ \\t]*$");

	private static final java.util.regex.Pattern WILDCARD = java.util.regex.Pattern.compile(
			"(?m)^[ \\t]*import\\s+(?:static\\s+)?[\\w.]+\\.\\*\\s*;");

	/**
	 * The line of each wildcard import (F090) - read with comments and string literals blanked, so
	 * a suite's sample text is not one. A wildcard names no type, so {@link #unusedImports} cannot
	 * judge what it brings in: the painter's split gave each of its seven files a wildcard of the
	 * other six, 42 declared edges of which 24 carried nothing, and that is how a stage depending on
	 * its driver through a cycle read as a tidy split. Measured when this was written: 49 static
	 * wildcards in those seven files and 3 type wildcards elsewhere, all now explicit.
	 */
	static List<Integer> wildcardImports(String src) {
		String code = SourceSeamTest.stripComments(src).replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"");
		List<Integer> out = new ArrayList<>();
		java.util.regex.Matcher m = WILDCARD.matcher(code);
		while (m.find()) {
			int line = 1;
			for (int i = 0; i < m.start(); i++) {
				line += code.charAt(i) == '\n' ? 1 : 0;
			}
			out.add(line);
		}
		return out;
	}

	/**
	 * The single-type and static-member imports whose simple name the file never uses outside its
	 * imports, comments and string literals (F203) - a javadoc {@code {@link X}} counts as a use.
	 * Measured when this was written: 213 in 80 files, 63 of the audit's naming a ctrmap type, 11
	 * of them the window. A wildcard import names no type and is refused by {@link #wildcardImports}.
	 */
	static List<String> unusedImports(String src) {
		StringBuilder links = new StringBuilder();
		java.util.regex.Matcher l = java.util.regex.Pattern.compile("\\{@link(?:plain)?\\s+([\\w.#$]+)").matcher(src);
		while (l.find()) {
			links.append(' ').append(l.group(1));
		}
		String body = SourceSeamTest.stripComments(src.replaceAll("(?m)^import[^\\n]*\\n", ""))
				.replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"") + links;
		List<String> out = new ArrayList<>();
		java.util.regex.Matcher m = IMPORT.matcher(src);
		while (m.find()) {
			if (!java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(m.group(1)) + "\\b")
					.matcher(body).find()) {
				out.add(m.group(1));
			}
		}
		return out;
	}

	/** The known answers: every shape that counts is found, and every one that does not, is not. */
	static void controls() {
		check(commentedOutCode("/*\n a();\n b();\n c();\n*/\n").size() == 1
				&& commentedOutCode("short t; //*35ms\nint a;\nint b;\nint c;\n").isEmpty()
				&& commentedOutCode("/* this is prose; with one semicolon\n and more words\n*/\n").isEmpty()
				&& commentedOutCode("\t//a();\n\t//b();\n\t//c();\nx();\n").size() == 1
				&& commentedOutCode("String s = \"/* a(); b(); c(); */\";\n").isEmpty()
				&& commentedOutCode("/** a();\n b();\n c(); */\nint x;\n").isEmpty()
				&& commentedOutCode("int a; //a();\nint b; //b();\nint c; //c();\n").isEmpty(),
				"control: a block of three statements and a run of three // statements are code put away; '//*35ms',"
				+ " prose, an opener in a string, a javadoc and trailing comments after code are not");
		check(csharpDocs("class A {\n\t/// <summary>\n\t/** ok */\n\tint x; // ///\n}\n").equals(java.util.Arrays.asList(2)),
				"control: a line opening with /// is C#'s doc comment; /// after code is not");
		check(unusedImports("import java.util.List;\nimport java.util.Map;\nclass A { List<String> a; }\n")
				.equals(java.util.Arrays.asList("Map"))
				&& unusedImports("import java.util.Map;\n/** {@link Map} */\nclass A { }\n").isEmpty()
				&& unusedImports("import java.util.Map;\nclass A { String s = \"Map\"; }\n").equals(java.util.Arrays.asList("Map")),
				"control: an unused import is found, a used one and one a javadoc links are not, and a name in a string"
				+ " is not a use");
		check(wildcardImports("package a;\n\nimport java.util.List;\nimport static a.B.*;\nimport java.util.*;\n"
				+ "/*\nimport x.*;\n*/\nclass A { String s = \"\\nimport y.*;\"; }\n").equals(java.util.Arrays.asList(4, 5)),
				"control: a static and a type wildcard are found on their lines; a single-type import, one in a"
				+ " comment and one in a string are not");
		Object[][] docs = {
			{"class A {\n\t/** lost */\n\t/** kept */\n\tint x;\n}\n", "2:0"},
			{"class A {\n\tint x;\n\t/** lost */\n}\n", "3:1"},
			{"class A {}\n/** lost */\n", "2:2"},
			{"/* licence */\n/** doc */\nclass A {}\n", ""},
			{"class A {\n\t/** doc */\n\t@Override\n\tpublic String toString() {\n\t\treturn \"/** no */ }\";\n\t}\n}\n", ""},
			{"class A {\n\tchar q = '\"';\n\t/** doc */\n\tint x;\n}\n", ""},
			{"class A {\n\tString s = \"\\\"/** no */\";\n\t/** doc */\n\tint x;\n}\n", ""},
			{"class A {\n\t/** lost */\n\t//a note\n\t/** kept */\n\tint x;\n}\n", "2:0"},
			{"class A {\n\t/** doc */\n\t//a note\n\tint x;\n}\n", ""},
		};
		int right = 0;
		for (Object[] c : docs) {
			StringBuilder got = new StringBuilder();
			for (int[] o : orphans((String) c[0])) {
				got.append(got.length() == 0 ? "" : ",").append(o[0]).append(':').append(o[1]);
			}
			if (got.toString().equals(c[1])) {
				right++;
			} else {
				System.out.println("    control text " + docs.length + ": wanted [" + c[1] + "], got [" + got + "]");
			}
		}
		check(right == docs.length, "control: " + right + " of " + docs.length + " javadoc shapes answered as known");
		Object[][] placed = {
			{"class A {\n\t/** gap */\n\n\tint a;\n}\n", "2:0"},
			{"class A {\n\t/**\n\t * Creates new form A\n\t */\n\tint a;\n\n\tA() {\n\t}\n}\n", "2:1"},
			{"class A {\n\t/** Creates new form A */\n\tpublic A() {\n\t}\n}\n", ""},
			{"class A {\n\t/** doc */\n\t//its history\n\tint a;\n}\n", ""},
			{"class A {\n\t/** doc */\n\t@Deprecated\n\tint a;\n}\n", ""},
			{"class A {\n\t/** Says what a {@code Creates new form X} stub is. */\n\tint a;\n}\n", ""},
		};
		int placedRight = 0;
		for (Object[] c : placed) {
			StringBuilder got = new StringBuilder();
			for (int[] m : misplaced((String) c[0])) {
				got.append(got.length() == 0 ? "" : ",").append(m[0]).append(':').append(m[1]);
			}
			if (got.toString().equals(c[1])) {
				placedRight++;
			} else {
				System.out.println("    control text: wanted [" + c[1] + "], got [" + got + "]");
			}
		}
		check(placedRight == placed.length, "control: " + placedRight + " of " + placed.length
				+ " placements answered as known - a gap and a stray stub found; a stub on its constructor,"
				+ " a line comment and an annotation between a doc and its member not");
		boolean runs = blankRuns("a\n\n\n\nb\n").size() == 1 && blankRuns("a\n\n\n\nb\n").get(0)[0] == 5
				&& blankRuns("a\n\n\nb\n").isEmpty() && blankRuns("a\n \t\n\n\nb\n").size() == 1;
		check(runs, "control: three blank lines are a run, two are not, and a line of spaces is blank");
	}

	static final String[] ASTRAY = {"a blank line before its declaration", "a form stub off its constructor"};

	/**
	 * {line, kind} of each javadoc on the wrong member by shape; kind indexes {@link #ASTRAY}. A doc
	 * whose next line is blank and whose next text is a declaration, and a {@code Creates new form X}
	 * whose declaration is not X's constructor. Orphans are {@link #orphans}' and are left alone.
	 */
	static List<int[]> misplaced(String src) {
		List<int[]> out = new ArrayList<>();
		int n = src.length();
		for (int[] d : docs(src)) {
			int k = d[1];
			boolean gap = false;
			int lineEnd = src.indexOf('\n', k);
			while (lineEnd >= 0 && src.substring(k, lineEnd).trim().isEmpty()) {
				int next = src.indexOf('\n', lineEnd + 1);
				String line = src.substring(lineEnd + 1, next < 0 ? n : next).trim();
				if (!line.isEmpty()) {
					break;
				}
				gap = true;
				k = lineEnd + 1;
				lineEnd = next;
			}
			int at = d[1];
			while (at < n && Character.isWhitespace(src.charAt(at))) {
				at++;
			}
			if (at >= n || src.startsWith("/**", at) || src.charAt(at) == '}') {
				continue;
			}
			int eol = src.indexOf('\n', at);
			String decl = src.substring(at, eol < 0 ? n : eol).trim();
			if (gap) {
				out.add(new int[]{lineOf(src, d[0]), 0});
			}
			//a STUB is a doc that says nothing else; a doc that quotes the phrase, as this suite's do, is not one
			String text = src.substring(d[0] + 3, Math.max(d[0] + 3, d[1] - 2)).replaceAll("(?m)^\\s*\\*?", " ").trim();
			java.util.regex.Matcher stub = java.util.regex.Pattern.compile("Creates new form (\\w+)\\.?").matcher(text);
			if (stub.matches() && !decl.matches("(public |protected |private )?" + stub.group(1) + "\\s*\\(.*")) {
				out.add(new int[]{lineOf(src, d[0]), 1});
			}
		}
		return out;
	}

	/** {start, end} of every javadoc, string and char literals and other comments skipped. */
	static List<int[]> docs(String src) {
		List<int[]> docs = new ArrayList<>();
		int i = 0, n = src.length();
		while (i < n) {
			char c = src.charAt(i);
			if (c == '"' || c == '\'') {
				i++;
				while (i < n && src.charAt(i) != c) {
					i += src.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
			} else if (src.startsWith("//", i)) {
				int j = src.indexOf('\n', i);
				i = j < 0 ? n : j;
			} else if (src.startsWith("/*", i)) {
				int j = src.indexOf("*/", i + 2);
				j = j < 0 ? n : j + 2;
				if (src.startsWith("/**", i) && !src.startsWith("/**/", i)) {
					docs.add(new int[]{i, j});
				}
				i = j;
			} else {
				i++;
			}
		}
		return docs;
	}

	/** {line, kind} of each javadoc that documents nothing; kind indexes {@link #KINDS}. */
	static List<int[]> orphans(String src) {
		List<int[]> docs = docs(src);
		int n = src.length();
		List<int[]> out = new ArrayList<>();
		for (int d = 0; d < docs.size(); d++) {
			int k = docs.get(d)[1];
			//PAST LINE COMMENTS TOO (F158): a javadoc, a line comment, then another javadoc was
			//stacked exactly like two javadocs and read as fine, because the comment was the
			//"next text"; a line comment documents nothing and hides nothing
			while (k < n) {
				if (Character.isWhitespace(src.charAt(k))) {
					k++;
				} else if (src.startsWith("//", k)) {
					int j = src.indexOf('\n', k);
					k = j < 0 ? n : j;
				} else {
					break;
				}
			}
			int kind = k >= n ? 2 : d + 1 < docs.size() && docs.get(d + 1)[0] == k ? 0 : src.charAt(k) == '}' ? 1 : -1;
			if (kind >= 0) {
				out.add(new int[]{lineOf(src, docs.get(d)[0]), kind});
			}
		}
		return out;
	}

	/** {line after the run, length} of each run of three or more blank lines. */
	static List<int[]> blankRuns(String src) {
		List<int[]> out = new ArrayList<>();
		String[] lines = src.split("\n", -1);
		int run = 0;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().isEmpty()) {
				run++;
				continue;
			}
			if (run >= 3) {
				out.add(new int[]{i + 1, run});
			}
			run = 0;
		}
		return out;
	}

	static int lineOf(String src, int at) {
		int line = 1;
		for (int i = 0; i < at; i++) {
			if (src.charAt(i) == '\n') {
				line++;
			}
		}
		return line;
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
