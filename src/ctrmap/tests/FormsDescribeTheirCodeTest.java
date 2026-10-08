package ctrmap.tests;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every NetBeans form describes the code generated from it (F122).
 *
 * <p>NetBeans regenerates a form's {@code initComponents} and its variables
 * block from the {@code .form} file on every designer save. Ten components -
 * the NPC editor's whole Dialogue section and the Zone tab's Clone and Add-zones
 * buttons - were added by hand inside the generated block and never to the
 * forms, so opening either form in the designer would have deleted those
 * features and left code calling fields that no longer exist. Nothing compared
 * the two; this does, for every form in the tree.
 *
 * <p>Two sets per form, read from the files: the components the form declares
 * (its {@code Component}, {@code Container} and menu elements, nested ones
 * included) against the fields of the Java's generated variables block; and the
 * handlers the form wires against the Java's {@code GEN-FIRST:event_} markers.
 * A handler wired by hand, without the marker, is one the designer would write
 * a second copy of.
 *
 * <p>Known answers first: a form read against its own code matches, and the
 * same form read against another form's code does not.
 *
 * Usage: java ctrmap.tests.FormsDescribeTheirCodeTest [src-root]
 */
public class FormsDescribeTheirCodeTest {

	static int fails = 0;

	static void check(boolean cond, String what) {
		System.out.println((cond ? "  ok: " : "  FAIL: ") + what);
		if (!cond) {
			fails++;
		}
	}

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		System.out.println("--- every form describes the code generated from it (F122)");
		List<File> forms = new ArrayList<>();
		collect(src, forms);
		check(forms.size() >= 15, "the walk found the tree's forms (" + forms.size() + ")");

		File known = new File(src, "ctrmap/humaninterface/LoadingDialog.form");
		File other = new File(src, "ctrmap/humaninterface/WarpEditForm.java");
		check(known.isFile() && differences(known, javaOf(known)).isEmpty() && !differences(known, other).isEmpty(),
				"control: a form read against its own code matches, and against another form's code does not");

		List<String> wrong = new ArrayList<>();
		for (File form : forms) {
			List<String> d = differences(form, javaOf(form));
			if (!d.isEmpty()) {
				wrong.add(form.getName() + " " + d);
			}
		}
		check(wrong.isEmpty(), "every form declares exactly the components and handlers its generated code holds"
				+ (wrong.isEmpty() ? "" : " - these do not: " + wrong + ". A component added by hand inside"
				+ " initComponents is deleted by the next designer save; add it to the .form, and give its"
				+ " handler the GEN-FIRST/GEN-LAST markers"));

		everySpinnerIsBounded(src);
		aTileByteTypedPastFFIsRefused();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	private static final java.util.regex.Pattern SPINNER_MADE = java.util.regex.Pattern.compile(
			"\\b(\\w+)\\s*=\\s*new\\s+(?:javax\\.swing\\.)?JSpinner\\s*\\(\\s*(new\\s+(?:javax\\.swing\\.)?SpinnerNumberModel\\s*\\(([^()]*)\\))?\\s*\\)");
	private static final java.util.regex.Pattern SPINNER_MODEL = java.util.regex.Pattern.compile(
			"\\b(\\w+)\\.setModel\\s*\\(\\s*new\\s+(?:javax\\.swing\\.)?SpinnerNumberModel\\s*\\(([^()]*)\\)\\s*\\)");
	private static final java.util.regex.Pattern SPINNER_BOUND = java.util.regex.Pattern.compile(
			"\\bForms\\.bound\\s*\\(\\s*(\\w+)\\s*,");
	private static final java.util.regex.Pattern SPINNER_LOOP = java.util.regex.Pattern.compile(
			"for\\s*\\(\\s*(?:javax\\.swing\\.)?JSpinner\\s+(\\w+)\\s*:\\s*new\\s+(?:javax\\.swing\\.)?JSpinner\\[\\]\\s*\\{([^}]*)\\}\\s*\\)\\s*\\{\\s*Forms\\.bound\\s*\\(\\s*\\1\\s*,");

	/**
	 * The spinners a source makes that it never bounds on both sides (F124, F125): made with no
	 * model or with a SpinnerNumberModel whose minimum or maximum is null, and neither given a model
	 * with both bounds nor handed to Forms.bound (one at a time, or in a for-each over an array of
	 * them). A spinner with an open side takes whatever is typed: -1 in an unsigned 16-bit field
	 * saved as 0xFFFF, 1FF in a tile byte stored as FF.
	 */
	static List<String> unboundedSpinners(String src) {
		java.util.Map<String, Boolean> made = new java.util.TreeMap<>();
		java.util.regex.Matcher m = SPINNER_MADE.matcher(src);
		while (m.find()) {
			made.put(m.group(1), m.group(2) != null && bothBounds(m.group(3)));
		}
		m = SPINNER_MODEL.matcher(src);
		while (m.find()) {
			if (made.containsKey(m.group(1)) && bothBounds(m.group(2))) {
				made.put(m.group(1), true);
			}
		}
		m = SPINNER_BOUND.matcher(src);
		while (m.find()) {
			if (made.containsKey(m.group(1))) {
				made.put(m.group(1), true);
			}
		}
		m = SPINNER_LOOP.matcher(src);
		while (m.find()) {
			for (String name : m.group(2).split(",")) {
				if (made.containsKey(name.trim())) {
					made.put(name.trim(), true);
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (java.util.Map.Entry<String, Boolean> e : made.entrySet()) {
			if (!e.getValue()) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	private static boolean bothBounds(String args) {
		String[] p = args.split(",");
		return p.length == 4 && !p[1].trim().equals("null") && !p[2].trim().equals("null");
	}

	static void everySpinnerIsBounded(File src) throws Exception {
		System.out.println("--- every spinner a form makes is bounded on both sides (F124, F125)");
		String demo = "a = new javax.swing.JSpinner();\nb = new JSpinner();\nc = new JSpinner();\nd = new JSpinner();\n"
				+ "e = new JSpinner();\na.setModel(new javax.swing.SpinnerNumberModel(0, 0, 9, 1));\n"
				+ "b.setModel(new javax.swing.SpinnerNumberModel(0, null, 9, 1));\nForms.bound(d, 0, 9);\n"
				+ "for (JSpinner x : new JSpinner[]{e}) {\n\tForms.bound(x, 0, 9);\n}\n";
		check(unboundedSpinners(demo).equals(java.util.Arrays.asList("b", "c")), "control: a spinner with an open"
				+ " minimum and one with no model are unbounded; one with both bounds, one handed to Forms.bound and"
				+ " one bound in a loop are not (" + unboundedSpinners(demo) + ")");
		List<String> open = new ArrayList<>();
		int spinners = 0;
		for (File f : DialogSeamTest.javaSources(new File(src, "ctrmap"))) {
			if (f.getPath().replace('\\', '/').contains("/ctrmap/tests/")) {
				continue;
			}
			String text = DialogSeamTest.read(f);
			java.util.regex.Matcher each = SPINNER_MADE.matcher(text);
			while (each.find()) {
				spinners++;
			}
			for (String s : unboundedSpinners(text)) {
				open.add(f.getName() + ":" + s);
			}
		}
		check(spinners >= 40 && open.isEmpty(), "every one of the " + spinners + " spinners the program makes is bounded on"
				+ " both sides" + (open.isEmpty() ? "" : " - these are not: " + open + ". Call Forms.bound where the form"
				+ " is built, or give its SpinnerNumberModel both bounds in the designer"));
	}

	/**
	 * A tile byte typed past FF, or below 0, is refused as text, not committed for the listener to
	 * cut to its low byte (F125): typed 1FF was stored as FF, -1 as FF, with nothing said. The form
	 * is built on its own - a suite with no GL can build it - and asked through its own field.
	 */
	static void aTileByteTypedPastFFIsRefused() throws Exception {
		System.out.println("--- a tile byte typed past FF is refused, not cut to its low byte (F125)");
		ctrmap.humaninterface.TileEditForm form = new ctrmap.humaninterface.TileEditForm(
				new ctrmap.humaninterface.tools.ToolSelection(), () -> {
				}, null);
		java.lang.reflect.Field f = ctrmap.humaninterface.TileEditForm.class.getDeclaredField("byte0");
		f.setAccessible(true);
		javax.swing.JSpinner b = (javax.swing.JSpinner) f.get(form);
		javax.swing.JFormattedTextField text = ((javax.swing.JSpinner.DefaultEditor) b.getEditor()).getTextField();
		text.setText("7F");
		text.commitEdit();
		check(Integer.valueOf(0x7F).equals(b.getValue()), "control: 7F typed is taken (" + b.getValue() + ")");
		for (String typed : new String[]{"1FF", "-1"}) {
			String refused;
			try {
				text.setText(typed);
				text.commitEdit();
				refused = null;
			} catch (java.text.ParseException ex) {
				refused = ex.getMessage();
			}
			check(refused != null && Integer.valueOf(0x7F).equals(b.getValue()), typed + " typed is refused and the byte"
					+ " keeps 7F (" + (refused == null ? "taken as " + b.getValue() : refused) + ")");
		}
	}

	static File javaOf(File form) {
		String p = form.getPath();
		return new File(p.substring(0, p.length() - ".form".length()) + ".java");
	}

	private static final Pattern FORM_PART = Pattern.compile(
			"<(?:Component|Container|Menu|MenuItem)\\s+class=\"[^\"]+\"\\s+name=\"([^\"]+)\"");
	private static final Pattern FORM_HANDLER = Pattern.compile("<EventHandler\\s[^>]*handler=\"([^\"]+)\"");
	private static final Pattern VARIABLES = Pattern.compile("//GEN-BEGIN:variables(.*?)//GEN-END:variables",
			Pattern.DOTALL);
	private static final Pattern FIELD = Pattern.compile("\\b(\\w+)\\s*;");
	private static final Pattern MARKER = Pattern.compile("//GEN-FIRST:event_(\\w+)");

	/** What the form declares and the code does not, and the reverse; empty when they agree. */
	static List<String> differences(File form, File java) throws IOException {
		List<String> out = new ArrayList<>();
		if (!java.isFile()) {
			out.add("no " + java.getName() + " beside it");
			return out;
		}
		String f = read(form);
		String j = read(java);
		Matcher v = VARIABLES.matcher(j);
		if (!v.find()) {
			out.add(java.getName() + " has no generated variables block");
			return out;
		}
		Set<String> formParts = all(FORM_PART, f);
		Set<String> codeParts = all(FIELD, v.group(1));
		Set<String> formHandlers = all(FORM_HANDLER, f);
		Set<String> codeHandlers = all(MARKER, j);
		gap(out, "in the code, not the form", codeParts, formParts);
		gap(out, "in the form, not the code", formParts, codeParts);
		gap(out, "handled in the code without the designer's marker", formHandlers, codeHandlers);
		gap(out, "marked in the code, wired by no form", codeHandlers, formHandlers);
		return out;
	}

	private static void gap(List<String> out, String what, Set<String> have, Set<String> lacking) {
		Set<String> d = new TreeSet<>(have);
		d.removeAll(lacking);
		if (!d.isEmpty()) {
			out.add(what + ": " + d);
		}
	}

	private static Set<String> all(Pattern p, String text) {
		Set<String> out = new TreeSet<>();
		Matcher m = p.matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	private static void collect(File dir, List<File> out) {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File k : kids) {
			if (k.isDirectory()) {
				collect(k, out);
			} else if (k.getName().endsWith(".form")) {
				out.add(k);
			}
		}
	}
}
