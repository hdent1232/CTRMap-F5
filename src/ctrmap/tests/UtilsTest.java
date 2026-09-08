package ctrmap.tests;

import ctrmap.Ui;
import ctrmap.Utils;
import ctrmap.formats.vectors.Vec3f;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Characterization of {@link Utils}, which is five unrelated toolkits sharing a
 * class name: byte and magic helpers, dialog wrappers, Swing helpers, the JOGL
 * picking maths, and a few odds and ends. A later step SPLITS it, and a split is
 * exactly the kind of move that silently swaps two arguments or drops a helper's
 * last caller, so this pins what each one returns today.
 *
 * <p>Several of these behave oddly, and the odd behaviour is pinned AS IT IS -
 * see the notes on {@link #ba2intIsBigEndian}, {@link #trimmedArray} and
 * {@link #distanceAddsTheVectors}. A characterization test that quietly asserted
 * the improvement would break the refactor it exists to protect.
 *
 * <p>Everything here is headless. The two methods that cannot be reached without
 * the whole main window ({@code switchToolUI}, {@code setGraphicUI}) say so and
 * are skipped out loud rather than left as a silent hole.
 *
 * Usage: java ctrmap.tests.UtilsTest
 */
public class UtilsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		padding();
		ba2intIsBigEndian();
		floatComparison();
		magicBytes();
		magicOnDisk();
		capitalLetters();
		trimmedArray();
		distanceAddsTheVectors();
		rotation();
		makeMissingDirectories();
		floatOutOfATextField();
		iconsAndButtons();
		pickingMaths();
		whatTheDialogWrappersSay();
		theTwoThatNeedTheWholeWindow();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Bytes needed to reach the next 128-byte boundary from offset+length. */
	private static void padding() {
		check(Utils.getPadding(0, 0).length == 0, "nothing at offset 0 needs no padding");
		check(Utils.getPadding(0, 1).length == 127, "one byte is padded out to 128");
		check(Utils.getPadding(0, 128).length == 0, "a full block needs no padding");
		check(Utils.getPadding(0, 129).length == 127, "129 bytes are padded out to 256");
		check(Utils.getPadding(100, 28).length == 0, "a record that ends exactly on the boundary needs none");
		check(Utils.getPadding(100, 29).length == 127, "one byte over the boundary costs another whole block");
		check(Utils.getPadding(5, 0).length == 123,
				"an empty record at offset 5 still pads to the boundary (" + Utils.getPadding(5, 0).length + ")");
	}

	/**
	 * ba2int reads BIG-endian, alone among its neighbours in a codebase whose
	 * every other integer helper is little-endian (see the u16/u32 readers in
	 * ZoneManager, ZoneRepurposeScanner and the container classes). Pinned here
	 * because a split that "harmonised" it would corrupt every caller silently.
	 */
	private static void ba2intIsBigEndian() {
		check(Utils.ba2int(new byte[]{0, 0, 1, 2}) == 0x0102,
				"ba2int(00 00 01 02) == " + Utils.ba2int(new byte[]{0, 0, 1, 2}) + ", big-endian");
		check(Utils.ba2int(new byte[]{1, 2, 3, 4}) == 0x01020304,
				"ba2int(01 02 03 04) == 0x" + Integer.toHexString(Utils.ba2int(new byte[]{1, 2, 3, 4})));
		check(Utils.ba2int(new byte[]{0, 0, 0, (byte) 0xFF}) == 255, "the LAST byte is the least significant");
		check(Utils.ba2int(new byte[]{-1, -1, -1, -1}) == -1, "all ones is -1");
		check(Utils.ba2int(new byte[]{(byte) 0x80, 0, 0, 0}) == Integer.MIN_VALUE,
				"the top bit of the FIRST byte is the sign bit");
		check(Utils.ba2int(new byte[]{1, 2, 3, 4, 5}) == 0x01020304, "bytes past the fourth are ignored");
		try {
			Utils.ba2int(new byte[]{1, 2, 3});
			check(false, "fewer than four bytes fails rather than reading a short value");
		} catch (ArrayIndexOutOfBoundsException ex) {
			check(true, "fewer than four bytes throws ArrayIndexOutOfBoundsException");
		}
	}

	private static void floatComparison() {
		check(Utils.impreciseFloatEquals(1f, 1.05f), "0.05 apart counts as equal");
		check(Utils.impreciseFloatEquals(0f, 0.09f), "0.09 apart counts as equal");
		check(!Utils.impreciseFloatEquals(0f, 0.1f), "exactly 0.1 apart does NOT (the test is strictly less than)");
		check(Utils.impreciseFloatEquals(0f, -0.05f), "the comparison is on the absolute difference");
		check(!Utils.impreciseFloatEquals(Float.NaN, Float.NaN), "NaN equals nothing, itself included");
	}

	private static void magicBytes() {
		check(Utils.checkBCHMagic(new byte[]{'B', 'C', 'H', 0}), "BCH is recognised");
		check(!Utils.checkBCHMagic(new byte[]{'B', 'C', 'X'}), "BCX is not");
		check(!Utils.checkBCHMagic(new byte[]{'B', 'C'}), "two bytes are too few to say");
		check(Utils.checkMagic(new byte[]{'Z', 'O', 1}, "ZO"), "a leading magic string is found");
		check(!Utils.checkMagic(new byte[]{'Z', 'X', 1}, "ZO"), "a different one is not");
		check(!Utils.checkMagic(new byte[]{'Z'}, "ZO"), "data shorter than the magic is refused, not read past");
		check(Utils.checkMagic(new byte[]{1}, ""), "an empty magic matches anything");
		check(Utils.checkMagic(new byte[0], ""), "including nothing at all");
	}

	private static void magicOnDisk() throws IOException {
		File tmp = Scratch.dir("ctrmap_utils_magic");
		File two = new File(tmp, "two");
		Files.write(two.toPath(), new byte[]{0x4F, 0x5A});
		check(Utils.checkMagicLE16(two, 0x5A4F), "the first two bytes are read LITTLE-endian (4F 5A is 0x5A4F)");
		check(!Utils.checkMagicLE16(two, 0x4F5A), "so the big-endian reading of the same bytes does not match");
		File high = new File(tmp, "high");
		Files.write(high.toPath(), new byte[]{(byte) 0xFF, (byte) 0xFE});
		check(Utils.checkMagicLE16(high, 0xFEFF), "bytes above 0x7F are read unsigned");
		File one = new File(tmp, "one");
		Files.write(one.toPath(), new byte[]{0x4F});
		check(!Utils.checkMagicLE16(one, 0x5A4F), "a one-byte file cannot match and does not throw");
		File none = new File(tmp, "none");
		Files.write(none.toPath(), new byte[0]);
		check(!Utils.checkMagicLE16(none, 0x5A4F), "an empty file cannot match and does not throw");
		check(!Utils.checkMagicLE16(new File(tmp, "absent"), 0x5A4F), "a file that is not there is simply false");
	}

	private static void capitalLetters() {
		check(Utils.isUTF8Capital((byte) 'A') && Utils.isUTF8Capital((byte) 'Z'), "A to Z are capitals");
		check(!Utils.isUTF8Capital((byte) 0x40) && !Utils.isUTF8Capital((byte) 0x5B),
				"the bytes either side of the range are not");
		check(!Utils.isUTF8Capital((byte) 'a'), "lower case is not");
		check(!Utils.isUTF8Capital((byte) 0xC1), "a high byte is compared unsigned, so it is not a capital");
	}

	/**
	 * SUSPECTED DEFECTS, PINNED NOT FIXED. Two of them live here.
	 *
	 * <p>The scan stops at index 1, so index 0 is never examined: an array whose
	 * only non-zero byte is its first comes back EMPTY.
	 *
	 * <p>The kept length is {@code i + (4 - i % 4)}, which for an i that is
	 * already a multiple of four adds a whole four bytes - past the end of the
	 * input when the input is not itself a multiple of four, and the copy throws.
	 */
	private static void trimmedArray() {
		check(Arrays.equals(Utils.getTrimmedArray(new byte[]{1, 2, 3, 0, 0}), new byte[]{1, 2, 3, 0}),
				"trailing zeroes are dropped and the length rounded up to a multiple of four");
		check(Arrays.equals(Utils.getTrimmedArray(new byte[]{0, 0, 0, 1}), new byte[]{0, 0, 0, 1}),
				"a value in the last slot keeps the whole array");
		check(Utils.getTrimmedArray(new byte[8]).length == 0, "an all-zero array trims to nothing");
		check(Utils.getTrimmedArray(new byte[]{1, 0, 0, 0}).length == 0,
				"a value in slot 0 ALONE also trims to nothing - the scan never looks at index 0");
		check(Arrays.equals(Utils.getTrimmedArray(new byte[]{0, 0, 0, 0, 0, 0, 0, 9}),
				new byte[]{0, 0, 0, 0, 0, 0, 0, 9}), "an eight-byte array ending in a value is kept whole");
		try {
			Utils.getTrimmedArray(new byte[]{0, 0, 0, 0, 9});
			check(false, "a value at index 4 of a five-byte array overruns");
		} catch (ArrayIndexOutOfBoundsException ex) {
			check(true, "a value at index 4 of a five-byte array throws ArrayIndexOutOfBoundsException"
					+ " (the rounding asks for eight bytes from a five-byte input)");
		}
	}

	/**
	 * SUSPECTED DEFECT, PINNED NOT FIXED. This is named like a distance between
	 * two points but it ADDS the two vectors and takes the magnitude of the sum,
	 * so opposite points are zero apart and (1,0,0) and (2,0,0) are three.
	 */
	private static void distanceAddsTheVectors() {
		double a = Utils.getDistanceFromVector(new Vec3f(1, 0, 0), new Vec3f(2, 0, 0));
		check(Math.abs(a - 3.0) < 1e-9, "'distance' from (1,0,0) to (2,0,0) is " + a + ", the magnitude of the SUM");
		double b = Utils.getDistanceFromVector(new Vec3f(1, 0, 0), new Vec3f(-1, 0, 0));
		check(Math.abs(b) < 1e-9, "opposite vectors are " + b + " apart");
		double c = Utils.getDistanceFromVector(new Vec3f(3, 4, 0), new Vec3f(0, 0, 0));
		check(Math.abs(c - 5.0) < 1e-9, "against the origin it does give the magnitude: " + c);
	}

	private static void rotation() {
		Vec3f y90 = Utils.noGlRotatef(new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), Math.toRadians(90));
		check(near(y90.x, 0) && near(y90.y, 0) && near(y90.z, -1),
				"X turned 90 degrees about Y lands on -Z (" + y90.x + "," + y90.y + "," + y90.z + ")");
		Vec3f z180 = Utils.noGlRotatef(new Vec3f(0, 1, 0), new Vec3f(0, 0, 1), Math.toRadians(180));
		check(near(z180.x, 0) && near(z180.y, -1) && near(z180.z, 0),
				"Y turned 180 degrees about Z lands on -Y (" + z180.x + "," + z180.y + "," + z180.z + ")");
		Vec3f none = Utils.noGlRotatef(new Vec3f(2, 3, 4), new Vec3f(1, 0, 0), 0);
		check(near(none.x, 2) && near(none.y, 3) && near(none.z, 4), "a zero angle changes nothing");
		Vec3f onAxis = Utils.noGlRotatef(new Vec3f(0, 5, 0), new Vec3f(0, 1, 0), Math.toRadians(37));
		check(near(onAxis.x, 0) && near(onAxis.y, 5) && near(onAxis.z, 0),
				"a vector along the axis is unmoved by any angle");
	}

	private static boolean near(float got, double want) {
		return Math.abs(got - want) < 1e-5;
	}

	private static void makeMissingDirectories() throws IOException {
		File tmp = Scratch.dir("ctrmap_utils_mkdirs");
		new File(tmp, "already").mkdir();
		Utils.mkDirsIfNotContains(tmp, new String[]{"already", "fresh"});
		check(new File(tmp, "already").isDirectory() && new File(tmp, "fresh").isDirectory(),
				"the missing subdirectory was created and the existing one left alone");
		check(tmp.list().length == 2, "and nothing else was made (" + Arrays.toString(tmp.list()) + ")");
		try {
			Utils.mkDirsIfNotContains(new File(tmp, "not_there"), new String[]{"x"});
			check(false, "asked about a directory that does not exist, it fails rather than creating one");
		} catch (NullPointerException ex) {
			check(true, "a container directory that does not exist throws NullPointerException"
					+ " (File.list() returns null and is wrapped without a check)");
		}
	}

	private static void floatOutOfATextField() {
		JFormattedTextField tf = new JFormattedTextField();
		tf.setText("3.25");
		check(Utils.getFloatFromDocument(tf) == 3.25f, "a plain decimal is read");
		tf.setText("1,5");
		check(Utils.getFloatFromDocument(tf) == 1.5f, "a comma is accepted as the decimal point");
		tf.setText("-2.5");
		check(Utils.getFloatFromDocument(tf) == -2.5f, "a negative is read");
		tf.setText("");
		check(Utils.getFloatFromDocument(tf) == 0f, "an empty field reads as zero");
		tf.setText("-");
		check(Utils.getFloatFromDocument(tf) == 0f, "a lone minus - what a field looks like mid-typing - is zero");
		tf.setText("abc");
		check(Utils.getFloatFromDocument(tf) == 0f, "text that is not a number is zero, not an exception");
	}

	private static void iconsAndButtons() {
		javax.swing.ImageIcon icon = Utils.getImageIconFromResource("_tool_edit_stale.png");
		check(icon != null && icon.getIconWidth() == 25 && icon.getIconHeight() == 25,
				"a packaged icon loads at " + icon.getIconWidth() + "x" + icon.getIconHeight());
		JRadioButton b = Utils.createGraphicalButton("_tool_edit");
		check(b.getIcon() != null && b.getRolloverIcon() != null && b.getPressedIcon() != null
				&& b.getSelectedIcon() != null,
				"a graphical tool button carries all four of its states");
		check(b.getPressedIcon() != b.getIcon(), "the pressed state is not the idle one");
		check(b.isRolloverEnabled(), "and rollover is switched on, or the rollover icon would never show");
	}

	/**
	 * The picking maths, which decides whether a click landed on an object. It
	 * needs no GL context - GLU projects on the CPU - so it runs headless here
	 * with a hand-built modelview, projection and viewport.
	 */
	private static void pickingMaths() {
		float[] mv = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		//an orthographic-shaped projection: window x = 100 + 50 * world x,
		//window y (after the flip the method does) = 100 - 50 * world y
		float[] proj = new float[]{0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
		int[] view = new int[]{0, 0, 200, 200};
		JPanel panel = new JPanel();
		panel.setSize(200, 200);
		Component parent = panel;
		float[][] unitQuad = new float[][]{{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}};
		Vec3f origin = new Vec3f(0, 0, 0);
		Vec3f unitScale = new Vec3f(1, 1, 1);
		Vec3f noRotation = new Vec3f(0, 0, 0);

		check(Utils.isBoxSelected(unitQuad, click(panel, 100, 100), parent, origin, unitScale, noRotation, mv, proj, view),
				"a click in the middle of the projected quad selects it");
		check(!Utils.isBoxSelected(unitQuad, click(panel, 5, 5), parent, origin, unitScale, noRotation, mv, proj, view),
				"a click in the corner of the window does not");
		check(!Utils.isBoxSelected(unitQuad, click(panel, 120, 120), parent, origin, new Vec3f(0.1f, 0.1f, 0.1f),
				noRotation, mv, proj, view),
				"scale is applied: the same click misses a quad shrunk to a tenth");
		check(Utils.isBoxSelected(unitQuad, click(panel, 250, 100), parent, new Vec3f(3, 0, 0), unitScale,
				noRotation, mv, proj, view),
				"position is applied: the quad moved three units right is hit three units right");
		check(!Utils.isBoxSelected(unitQuad, click(panel, 100, 100), parent, new Vec3f(3, 0, 0), unitScale,
				noRotation, mv, proj, view),
				"and is no longer hit where it used to be");
		check(Utils.isBoxSelected(unitQuad, click(panel, 100, 100), parent, origin, unitScale,
				new Vec3f(0, 0, 180), mv, proj, view),
				"rotation is applied: turning the quad 180 degrees in its own plane leaves it selectable");
	}

	private static MouseEvent click(Component on, int x, int y) {
		return new MouseEvent(on, MouseEvent.MOUSE_CLICKED, 0L, 0, x, y, 1, false);
	}

	/**
	 * The four wrappers that put words in front of the user. What each one says,
	 * and which argument ends up as the title, is asserted through the recording
	 * seam - the argument order of showErrorMessage/showInfoMessage is title
	 * first, message second, which is the reverse of {@link Ui}'s own.
	 */
	private static void whatTheDialogWrappersSay() {
		List<String> said = Ui.record();
		try {
			Utils.showErrorMessage("A title", "A message");
			Utils.showInfoMessage("Info title", "Info message");
		} finally {
			Ui.stopRecording();
		}
		check(said.size() == 2, said.size() + " messages were shown");
		check(said.contains("A title: A message"),
				"showErrorMessage(title, message) reaches the user as title then message: " + said.get(0));
		check(said.contains("Info title: Info message"),
				"and showInfoMessage the same way: " + said.get(1));

		said = Ui.record(Integer.valueOf(JOptionPane.OK_OPTION));
		boolean answered;
		try {
			answered = Utils.confirmOpenWithoutWorkspace("Open zone");
		} finally {
			Ui.stopRecording();
		}
		check(answered, "OK on the no-workspace question means open the loose file anyway");
		check(said.size() == 1 && said.get(0).startsWith("No workspace loaded: "),
				"the question is titled 'No workspace loaded'");
		check(said.get(0).contains("\"Open zone\""),
				"and names the action the user picked: " + firstLine(said.get(0)));
		check(said.get(0).contains("Options > Workspace settings"),
				"and tells them where to set the paths");

		said = Ui.record();
		try {
			answered = Utils.confirmOpenWithoutWorkspace("Open zone");
		} finally {
			Ui.stopRecording();
		}
		check(!answered, "a question nobody answers means do nothing, not consent");

		//showSaveConfirmationDialog(String) is now askToKeep(boolean, String): the
		//same question, asked in the same words, answered with SAVE/DISCARD/CANCEL
		//instead of JOptionPane's raw integer. "Hands the answer back unchanged" is
		//therefore no longer a thing that CAN be true - the whole point of the
		//replacement is that the four integers are mapped once, here - so what is
		//pinned is that the user's NO still reaches the caller as its own answer.
		//DialogSeamTest pins all four of the mappings.
		said = Ui.record(Integer.valueOf(JOptionPane.NO_OPTION));
		Utils.Keep answer;
		try {
			answer = Utils.askToKeep(true, "The zone");
		} finally {
			Ui.stopRecording();
		}
		check(answer == Utils.Keep.DISCARD, "the save question hands the user's NO back as DISCARD, not as consent");
		check(said.size() == 1 && said.get(0).equals(
				"Save changes: The zone has been modified. Do you want to keep the changes?"),
				"the save question names what changed: " + said.get(0));
	}

	private static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl) + " ...";
	}

	/**
	 * switchToolUI and setGraphicUI drive the main window's split panes through
	 * CtrmapMainframe's static fields, and adjustSplitPanes() dereferences four
	 * more of them. Reaching either means building the whole window, which is a
	 * structural change to production code, so they are skipped here rather than
	 * left as a hole nobody knows about.
	 */
	private static void theTwoThatNeedTheWholeWindow() {
		System.out.println("  skip: switchToolUI/setGraphicUI need CtrmapMainframe's live split panes"
				+ " (jsp, frame, mCamScrollPane, mCamEditForm, tileEditMasterPnl) - not reachable without"
				+ " constructing the main window");
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
