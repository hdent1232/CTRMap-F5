package ctrmap.tests;

import ctrmap.Ui;
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
 * Characterization of the helpers that were {@code ctrmap.Utils} - five unrelated
 * toolkits that shared a class name, since SPLIT into {@link ctrmap.util.Bytes},
 * the form and picking helpers and {@link Ui}. A split is exactly the kind of move
 * that silently swaps two arguments or drops a helper's last caller, so this pins
 * what each one returns.
 *
 * <p>Some of these behave oddly, and the odd behaviour is pinned AS IT IS - see
 * the note on {@link #ba2intIsBigEndian}. One pinned here as a suspected defect - the
 * picking distance that adds - was right all along and is pinned as what it is now (F250).
 * A trimmer pinned here with two defects had no caller in the program and was
 * deleted with its section (F165).
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
		cameraDistanceIsFromTheCamera();
		rotation();
		makeMissingDirectories();
		floatOutOfATextField();
		iconsAndButtons();
		pickingMaths();
		aPickedModelIsOneTheCameraSees();
		theFormHelpersEveryFormShares();
		theScriptDropdownAnswersOnlyTheUser();
		theMapViewsKeepOneFrameEach();
		theAtmosphereSaysWhatItIs();
		gizmoDistance();
		whatTheDialogWrappersSay();
		theTwoThatNeedTheWholeWindow();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** Bytes needed to reach the next 128-byte boundary from offset+length. */
	private static void padding() {
		check(ctrmap.util.Bytes.getPadding(0, 0).length == 0, "nothing at offset 0 needs no padding");
		check(ctrmap.util.Bytes.getPadding(0, 1).length == 127, "one byte is padded out to 128");
		check(ctrmap.util.Bytes.getPadding(0, 128).length == 0, "a full block needs no padding");
		check(ctrmap.util.Bytes.getPadding(0, 129).length == 127, "129 bytes are padded out to 256");
		check(ctrmap.util.Bytes.getPadding(100, 28).length == 0, "a record that ends exactly on the boundary needs none");
		check(ctrmap.util.Bytes.getPadding(100, 29).length == 127, "one byte over the boundary costs another whole block");
		check(ctrmap.util.Bytes.getPadding(5, 0).length == 123,
				"an empty record at offset 5 still pads to the boundary (" + ctrmap.util.Bytes.getPadding(5, 0).length + ")");
	}

	/**
	 * ba2int reads BIG-endian, alone among its neighbours in a codebase whose
	 * every other integer helper is little-endian (see the u16/u32 readers in
	 * ZoneManager, ZoneRepurposeScanner and the container classes). Pinned here
	 * because a split that "harmonised" it would corrupt every caller silently.
	 */
	private static void ba2intIsBigEndian() {
		check(ctrmap.util.Bytes.ba2int(new byte[]{0, 0, 1, 2}) == 0x0102,
				"ba2int(00 00 01 02) == " + ctrmap.util.Bytes.ba2int(new byte[]{0, 0, 1, 2}) + ", big-endian");
		check(ctrmap.util.Bytes.ba2int(new byte[]{1, 2, 3, 4}) == 0x01020304,
				"ba2int(01 02 03 04) == 0x" + Integer.toHexString(ctrmap.util.Bytes.ba2int(new byte[]{1, 2, 3, 4})));
		check(ctrmap.util.Bytes.ba2int(new byte[]{0, 0, 0, (byte) 0xFF}) == 255, "the LAST byte is the least significant");
		check(ctrmap.util.Bytes.ba2int(new byte[]{-1, -1, -1, -1}) == -1, "all ones is -1");
		check(ctrmap.util.Bytes.ba2int(new byte[]{(byte) 0x80, 0, 0, 0}) == Integer.MIN_VALUE,
				"the top bit of the FIRST byte is the sign bit");
		check(ctrmap.util.Bytes.ba2int(new byte[]{1, 2, 3, 4, 5}) == 0x01020304, "bytes past the fourth are ignored");
		try {
			ctrmap.util.Bytes.ba2int(new byte[]{1, 2, 3});
			check(false, "fewer than four bytes fails rather than reading a short value");
		} catch (ArrayIndexOutOfBoundsException ex) {
			check(true, "fewer than four bytes throws ArrayIndexOutOfBoundsException");
		}
	}

	private static void floatComparison() {
		check(ctrmap.util.Bytes.impreciseFloatEquals(1f, 1.05f), "0.05 apart counts as equal");
		check(ctrmap.util.Bytes.impreciseFloatEquals(0f, 0.09f), "0.09 apart counts as equal");
		check(!ctrmap.util.Bytes.impreciseFloatEquals(0f, 0.1f), "exactly 0.1 apart does NOT (the test is strictly less than)");
		check(ctrmap.util.Bytes.impreciseFloatEquals(0f, -0.05f), "the comparison is on the absolute difference");
		check(!ctrmap.util.Bytes.impreciseFloatEquals(Float.NaN, Float.NaN), "NaN equals nothing, itself included");
	}

	private static void magicBytes() {
		check(ctrmap.formats.h3d.BchSections.hasMagic(new byte[]{'B', 'C', 'H', 0}), "BCH is recognised");
		check(!ctrmap.formats.h3d.BchSections.hasMagic(new byte[]{'B', 'C', 'X'}), "BCX is not");
		check(!ctrmap.formats.h3d.BchSections.hasMagic(new byte[]{'B', 'C'}), "two bytes are too few to say");
		check(!ctrmap.formats.h3d.BchSections.hasMagic(new byte[]{'B', 'C', 'H', 1}), "BCH with no NUL after it is not (F066: three bytes was one reading, four the other)");
		check(ctrmap.util.Bytes.checkMagic(new byte[]{'Z', 'O', 1}, "ZO"), "a leading magic string is found");
		check(!ctrmap.util.Bytes.checkMagic(new byte[]{'Z', 'X', 1}, "ZO"), "a different one is not");
		check(!ctrmap.util.Bytes.checkMagic(new byte[]{'Z'}, "ZO"), "data shorter than the magic is refused, not read past");
		check(ctrmap.util.Bytes.checkMagic(new byte[]{1}, ""), "an empty magic matches anything");
		check(ctrmap.util.Bytes.checkMagic(new byte[0], ""), "including nothing at all");
	}

	private static void magicOnDisk() throws IOException {
		File tmp = Scratch.dir("ctrmap_utils_magic");
		File two = new File(tmp, "two");
		Files.write(two.toPath(), new byte[]{0x4F, 0x5A});
		check(ctrmap.util.Bytes.checkMagicLE16(two, 0x5A4F), "the first two bytes are read LITTLE-endian (4F 5A is 0x5A4F)");
		check(!ctrmap.util.Bytes.checkMagicLE16(two, 0x4F5A), "so the big-endian reading of the same bytes does not match");
		File high = new File(tmp, "high");
		Files.write(high.toPath(), new byte[]{(byte) 0xFF, (byte) 0xFE});
		check(ctrmap.util.Bytes.checkMagicLE16(high, 0xFEFF), "bytes above 0x7F are read unsigned");
		File one = new File(tmp, "one");
		Files.write(one.toPath(), new byte[]{0x4F});
		check(!ctrmap.util.Bytes.checkMagicLE16(one, 0x5A4F), "a one-byte file cannot match and does not throw");
		File none = new File(tmp, "none");
		Files.write(none.toPath(), new byte[0]);
		check(!ctrmap.util.Bytes.checkMagicLE16(none, 0x5A4F), "an empty file cannot match and does not throw");
		String absent;
		try {
			absent = "answered " + ctrmap.util.Bytes.checkMagicLE16(new File(tmp, "absent"), 0x5A4F);
		} catch (IOException ex) {
			absent = null;
		}
		//F211: it answered false, and the zone opener reported a file it could not read as "not a zone mini-pack"
		check(absent == null, "a file that cannot be read THROWS - it is not 'some other kind of file' ("
				+ (absent == null ? "threw" : absent) + ")");
	}

	private static void capitalLetters() {
		check(ctrmap.util.Bytes.isUTF8Capital((byte) 'A') && ctrmap.util.Bytes.isUTF8Capital((byte) 'Z'), "A to Z are capitals");
		check(!ctrmap.util.Bytes.isUTF8Capital((byte) 0x40) && !ctrmap.util.Bytes.isUTF8Capital((byte) 0x5B),
				"the bytes either side of the range are not");
		check(!ctrmap.util.Bytes.isUTF8Capital((byte) 'a'), "lower case is not");
		check(!ctrmap.util.Bytes.isUTF8Capital((byte) 0xC1), "a high byte is compared unsigned, so it is not a capital");
	}

	/**
	 * A picked point's distance from the camera, handed the scene's translation - the negative of
	 * the camera's position (F250). Pinned as a suspected defect for adding where a two-point
	 * distance subtracts; with the translation the callers pass, the sum is the camera-to-point
	 * vector, so a camera at (2,0,0) is 1 from (1,0,0) and 3 from (-1,0,0).
	 */
	private static void cameraDistanceIsFromTheCamera() {
		Vec3f camera = new Vec3f(2, 0, 0);
		Vec3f translation = new Vec3f(-camera.x, -camera.y, -camera.z);
		double near = ctrmap.humaninterface.Picking.cameraDistance(new Vec3f(1, 0, 0), translation);
		double far = ctrmap.humaninterface.Picking.cameraDistance(new Vec3f(-1, 0, 0), translation);
		check(Math.abs(near - 1.0) < 1e-9 && Math.abs(far - 3.0) < 1e-9, "a camera at (2,0,0) is " + near
				+ " from (1,0,0) and " + far + " from (-1,0,0)");
		double diagonal = ctrmap.humaninterface.Picking.cameraDistance(new Vec3f(3, 4, 0), new Vec3f(0, 0, 0));
		check(Math.abs(diagonal - 5.0) < 1e-9, "and with the camera at the origin it is the point's own length: " + diagonal);
	}

	private static void rotation() {
		Vec3f y90 = ctrmap.humaninterface.Picking.noGlRotatef(new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), Math.toRadians(90));
		check(near(y90.x, 0) && near(y90.y, 0) && near(y90.z, -1),
				"X turned 90 degrees about Y lands on -Z (" + y90.x + "," + y90.y + "," + y90.z + ")");
		Vec3f z180 = ctrmap.humaninterface.Picking.noGlRotatef(new Vec3f(0, 1, 0), new Vec3f(0, 0, 1), Math.toRadians(180));
		check(near(z180.x, 0) && near(z180.y, -1) && near(z180.z, 0),
				"Y turned 180 degrees about Z lands on -Y (" + z180.x + "," + z180.y + "," + z180.z + ")");
		Vec3f none = ctrmap.humaninterface.Picking.noGlRotatef(new Vec3f(2, 3, 4), new Vec3f(1, 0, 0), 0);
		check(near(none.x, 2) && near(none.y, 3) && near(none.z, 4), "a zero angle changes nothing");
		Vec3f onAxis = ctrmap.humaninterface.Picking.noGlRotatef(new Vec3f(0, 5, 0), new Vec3f(0, 1, 0), Math.toRadians(37));
		check(near(onAxis.x, 0) && near(onAxis.y, 5) && near(onAxis.z, 0),
				"a vector along the axis is unmoved by any angle");
	}

	private static boolean near(float got, double want) {
		return Math.abs(got - want) < 1e-5;
	}

	private static void makeMissingDirectories() throws IOException {
		File tmp = Scratch.dir("ctrmap_utils_mkdirs");
		new File(tmp, "already").mkdir();
		ctrmap.WorkspaceSession.mkDirsIfNotContains(tmp, new String[]{"already", "fresh"});
		check(new File(tmp, "already").isDirectory() && new File(tmp, "fresh").isDirectory(),
				"the missing subdirectory was created and the existing one left alone");
		check(tmp.list().length == 2, "and nothing else was made (" + Arrays.toString(tmp.list()) + ")");
		try {
			ctrmap.WorkspaceSession.mkDirsIfNotContains(new File(tmp, "not_there"), new String[]{"x"});
			check(false, "asked about a directory that does not exist, it fails rather than creating one");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("does not exist") && !new File(tmp, "not_there").exists(),
					"asked about a directory that does not exist, it refuses in words and makes nothing: "
					+ said.getMessage());
		} catch (RuntimeException crash) {
			check(false, "asked about a directory that does not exist, it refuses in words (it threw " + crash + ")");
		}
	}

	private static void floatOutOfATextField() {
		JFormattedTextField tf = new JFormattedTextField();
		tf.setText("3.25");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == 3.25f, "a plain decimal is read");
		tf.setText("1,5");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == 1.5f, "a comma is accepted as the decimal point");
		tf.setText("-2.5");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == -2.5f, "a negative is read");
		tf.setText("");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == 0f, "an empty field reads as zero");
		tf.setText("-");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == 0f, "a lone minus - what a field looks like mid-typing - is zero");
		tf.setText("abc");
		check(ctrmap.humaninterface.Forms.getFloatFromDocument(tf) == 0f, "text that is not a number is zero, not an exception");
	}

	private static void iconsAndButtons() {
		javax.swing.ImageIcon icon = ctrmap.humaninterface.Forms.getImageIconFromResource("_tool_edit_stale.png");
		check(icon != null && icon.getIconWidth() == 25 && icon.getIconHeight() == 25,
				"a packaged icon loads at " + icon.getIconWidth() + "x" + icon.getIconHeight());
		JRadioButton b = ctrmap.humaninterface.Forms.createGraphicalButton("_tool_edit");
		check(b.getIcon() != null && b.getRolloverIcon() != null && b.getPressedIcon() != null
				&& b.getSelectedIcon() != null,
				"a graphical tool button carries all four of its states");
		check(b.getPressedIcon() != b.getIcon(), "the pressed state is not the idle one");
		check(b.isRolloverEnabled(), "and rollover is switched on, or the rollover icon would never show");
	}

	/**
	 * The move gizmo's distance to the camera across an axis (F244), asked of a real navigator the
	 * way its draw asks it - with the view's translation, the camera standing at minus that - and
	 * the arrow scale that follows from it.
	 */
	private static void gizmoDistance() {
		try {
			ctrmap.humaninterface.CM3DNavigator navi = new ctrmap.humaninterface.CM3DNavigator(null);
			final float[] at = {300, 0, 0};
			navi.bindModel(new ctrmap.formats.MapObject() {
				public float getX() {
					return at[0];
				}

				public float getY() {
					return at[1];
				}

				public float getZ() {
					return at[2];
				}

				public void setX(float v) {
					at[0] = v;
				}

				public void setY(float v) {
					at[1] = v;
				}

				public void setZ(float v) {
					at[2] = v;
				}
			});
			java.lang.reflect.Method measure = ctrmap.humaninterface.CM3DNavigator.class.getDeclaredMethod("measure",
					float.class, float.class, float.class);
			measure.setAccessible(true);
			measure.invoke(navi, 300f, 0f, 0f);
			double across = navi.distFromTarget;
			check(Math.abs(across - 600) < 1e-6 && Math.abs(navi.ax.scale - 0.6f) < 1e-6,
					"an object and a camera on opposite sides of x are 600 apart, not 0, and the arrows scale by it ("
					+ across + ", scale " + navi.ax.scale + ")");
			at[1] = 40;
			measure.invoke(navi, -100f, -40f, 0f);
			check(Math.abs(navi.distFromTarget - 200) < 1e-6, "and on the same side, the plain difference ("
					+ navi.distFromTarget + ")");

			//F148: a drag is an edit only when it moved the target
			java.lang.reflect.Method drag = ctrmap.humaninterface.CM3DNavigator.class.getDeclaredMethod("drag",
					float.class, float.class, int.class, int.class);
			drag.setAccessible(true);
			navi.targetAxis = ctrmap.humaninterface.CM3DNavigator.TargetAxis.X;
			float before = at[0];
			boolean still = (Boolean) drag.invoke(navi, 0f, 0f, 0, 0);
			float afterStill = at[0];
			check(!still && afterStill == before, "a drag of no pixels moves nothing and is not an edit (" + still + ", x "
					+ before + " -> " + afterStill + ")");
			boolean moved = (Boolean) drag.invoke(navi, 0f, 0f, 10, 0);
			check(moved && at[0] > afterStill, "and a drag of ten along the held axis moves it and is (" + moved + ", x "
					+ afterStill + " -> " + at[0] + ")");
		} catch (ReflectiveOperationException ex) {
			check(false, "the gizmo's distance can be asked: " + ex);
		}
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

		check(ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 100, 100), parent, origin, unitScale, noRotation, mv, proj, view),
				"a click in the middle of the projected quad selects it");
		check(!ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 5, 5), parent, origin, unitScale, noRotation, mv, proj, view),
				"a click in the corner of the window does not");
		check(!ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 120, 120), parent, origin, new Vec3f(0.1f, 0.1f, 0.1f),
				noRotation, mv, proj, view),
				"scale is applied: the same click misses a quad shrunk to a tenth");
		check(ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 250, 100), parent, new Vec3f(3, 0, 0), unitScale,
				noRotation, mv, proj, view),
				"position is applied: the quad moved three units right is hit three units right");
		check(!ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 100, 100), parent, new Vec3f(3, 0, 0), unitScale,
				noRotation, mv, proj, view),
				"and is no longer hit where it used to be");
		check(ctrmap.humaninterface.Picking.isBoxSelected(unitQuad, click(panel, 100, 100), parent, origin, unitScale,
				new Vec3f(0, 0, 180), mv, proj, view),
				"rotation is applied: turning the quad 180 degrees in its own plane leaves it selectable");
	}

	/**
	 * The second half of picking (F113): a box that contains the click picks the model only when some
	 * vertex of the model, placed as the box is placed, lands on the panel. The NPC and prop forms each
	 * pasted this check in and placed the vertices with no rotation (and the prop copy with no scale),
	 * so a turned model was judged where it was not drawn. Same headless projection as above: window
	 * x = 100 + 50 * world x, and the panel is 200 wide, so world x beyond 2 is off it.
	 */
	private static void aPickedModelIsOneTheCameraSees() {
		float[] mv = new float[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		float[] proj = new float[]{0.5f, 0, 0, 0, 0, 0.5f, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1};
		int[] view = new int[]{0, 0, 200, 200};
		JPanel panel = new JPanel();
		panel.setSize(200, 200);
		float[][] unitQuad = new float[][]{{-1, -1, 0}, {1, -1, 0}, {1, 1, 0}, {-1, 1, 0}};
		Vec3f origin = new Vec3f(0, 0, 0), one = new Vec3f(1, 1, 1), still = new Vec3f(0, 0, 0);
		List<ctrmap.formats.h3d.model.H3DModel.H3DMesh> farRight = meshAt(3, 0, 0);
		List<ctrmap.formats.h3d.model.H3DModel.H3DMesh> inside = meshAt(0.5f, 0, 0);

		check(!ctrmap.humaninterface.Picking.isSeen(farRight, panel, origin, one, still, mv, proj, view)
				&& ctrmap.humaninterface.Picking.isSeen(inside, panel, origin, one, still, mv, proj, view),
				"control: a vertex three units right is off the panel, one half a unit right is on it");
		check(!ctrmap.humaninterface.Picking.isPicked(unitQuad, farRight, click(panel, 100, 100), panel, origin, one, still,
				mv, proj, view),
				"a box that contains the click does not pick a model none of whose vertices is on the panel");
		check(ctrmap.humaninterface.Picking.isPicked(unitQuad, inside, click(panel, 100, 100), panel, origin, one, still,
				mv, proj, view),
				"and does pick one that has a vertex on it");
		check(ctrmap.humaninterface.Picking.isSeen(farRight, panel, origin, one, new Vec3f(0, 90, 0), mv, proj, view),
				"the vertices are turned with the model: three units right, turned 90 degrees, is on the panel");
		check(!ctrmap.humaninterface.Picking.isSeen(inside, panel, origin, new Vec3f(10, 10, 10), still, mv, proj, view),
				"and scaled with it: half a unit right, scaled ten times, is off the panel");
		Vec3f placed = ctrmap.humaninterface.Picking.place(1, 0, 0, new Vec3f(5, 0, 0), new Vec3f(2, 2, 2), still);
		check(placed.x == 7f && placed.y == 0f && placed.z == 0f, "a point is scaled, then moved: (1,0,0) x2 at x=5 is x=7 ("
				+ placed.x + ")");
	}

	private static List<ctrmap.formats.h3d.model.H3DModel.H3DMesh> meshAt(float x, float y, float z) {
		ctrmap.formats.h3d.model.H3DModel.H3DMesh mesh = new ctrmap.formats.h3d.model.H3DModel.H3DMesh();
		ctrmap.formats.h3d.model.H3DVertex v = new ctrmap.formats.h3d.model.H3DVertex();
		v.position = new Vec3f(x, y, z);
		mesh.vertices.add(v);
		return Arrays.asList(mesh);
	}

	/**
	 * The helpers every entity form used to write for itself (F113): the number class a field holds,
	 * the integer a string starts with, and typed text escaped for a message file.
	 */
	private static void theFormHelpersEveryFormShares() {
		JFormattedTextField f = new JFormattedTextField(new javax.swing.text.NumberFormatter());
		f.setValue(1);
		ctrmap.humaninterface.Forms.numberClass(Float.class, f);
		check(((javax.swing.text.NumberFormatter) f.getFormatter()).getValueClass() == Float.class,
				"a field handed Float holds a Float");
		ctrmap.humaninterface.Forms.numberClass(Short.class, f);
		check(((javax.swing.text.NumberFormatter) f.getFormatter()).getValueClass() == Short.class,
				"and handed Short, a Short - what its saver casts to");
		check(ctrmap.util.Text.parseLeadingInt("25  Pikachu") == 25
				&& ctrmap.util.Text.parseLeadingInt("12 (talker: line 3)") == 12
				&& ctrmap.util.Text.parseLeadingInt("7") == 7
				&& ctrmap.util.Text.parseLeadingInt("-5 below") == -5,
				"the integer a string starts with is read from a table cell, a script item, a bare id and a negative");
		check(ctrmap.util.Text.parseLeadingInt("none") == -1 && ctrmap.util.Text.parseLeadingInt(null) == -1,
				"and a string with none, or no string, answers -1");
		check(ctrmap.formats.text.GFMessageFile.escapeTyped("a\r\nb\rc\nd").equals("a\\nb\\nc\\nd"),
				"typed text's line breaks, of every platform, become the message file's \\n escape");
	}

	/**
	 * The Script dropdown beside a script number (F113): showing a record's id selects its item, and
	 * the box's listener - which fires for that too - is told nothing; only a row the user chose
	 * reaches the number field.
	 */
	private static void theScriptDropdownAnswersOnlyTheUser() {
		javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
		ctrmap.humaninterface.ScriptIdDropdown ids = new ctrmap.humaninterface.ScriptIdDropdown(box);
		java.util.List<Integer> heard = new java.util.ArrayList<>();
		box.addActionListener(ev -> heard.add(ids.picked()));
		ids.fill(null);
		check(box.getItemCount() == 0 && box.getSelectedIndex() == -1, "with no zone the dropdown is empty");
		box.addItem("3");
		box.addItem("12 (talker: line 3)");
		heard.clear();
		ids.show(12);
		check(box.getSelectedIndex() == 1, "showing id 12 selects the talker item that names it");
		check(!heard.isEmpty() && heard.stream().allMatch(id -> id == -1),
				"and the listener, which fired for it, was told nothing: " + heard);
		heard.clear();
		box.setSelectedIndex(0);
		check(heard.contains(3), "a row the user chooses is answered as its id: " + heard);
		ids.show(99);
		check(box.getSelectedIndex() == -1, "an id the zone does not define selects nothing");
	}

	/**
	 * The map views' coordinate rules, each said once (F117): where a tile's centre is in the world,
	 * how many pixels the tile map and the matrix grid draw a world distance across, and how many bytes
	 * a region's tilemap is. Each was written out inline wherever it was needed, and the matrix grid's
	 * drawing and hit test already disagreed by a pixel.
	 */
	private static void theMapViewsKeepOneFrameEach() {
		check(ctrmap.formats.zone.WorldGrid.tileCentre(0) == 9 && ctrmap.formats.zone.WorldGrid.tileCentre(3) == 63
				&& ctrmap.formats.zone.WorldGrid.tileCentre(-1) == -9,
				"a tile's centre is 18 * tile + 9: tile 0 at 9, tile 3 at 63, tile -1 at -9");
		check(ctrmap.formats.zone.WorldGrid.tileOfCentre(63) == 3f && ctrmap.formats.zone.WorldGrid.tileOfCentre(18) == 0.5f,
				"and back: 63 is tile 3, and 18 is half way across tile 0");
		check(ctrmap.humaninterface.TileMapPanel.toScreen(720, 1) == 400d && ctrmap.humaninterface.TileMapPanel.toScreen(720, 2) == 800d
				&& ctrmap.humaninterface.TileMapPanel.toScreen(9, 1) == 5d,
				"the tile map draws a 720-unit region across 400 pixels at scale 1, 800 at scale 2");
		check(ctrmap.humaninterface.TileMapPanel.toWorld(400, 1) == 720f && ctrmap.humaninterface.TileMapPanel.toWorld(400, 2) == 360f,
				"and a distance in pixels goes back to the world units under it");
		check(ctrmap.humaninterface.MatrixCanvas.toPx(720) == 100d && ctrmap.humaninterface.MatrixCanvas.REGION_PX == 100
				&& ctrmap.humaninterface.MatrixCanvas.ZONE_CELL_PX * ctrmap.humaninterface.MatrixCanvas.ZONE_CELLS_PER_REGION
				== ctrmap.humaninterface.MatrixCanvas.REGION_PX,
				"the matrix grid draws a region across " + ctrmap.humaninterface.MatrixCanvas.REGION_PX
				+ " pixels and four zone cells inside it");
		check(ctrmap.formats.tilemap.Tilemap.REGION_BYTES == ctrmap.util.Bytes.align(4 + 40 * 40 * 4, 0x80),
				"a region's tilemap is its header and 40 x 40 four-byte tiles, padded to 0x80: "
				+ ctrmap.formats.tilemap.Tilemap.REGION_BYTES);
	}

	/**
	 * The area atmosphere says what it is (F236). viewFog's javadoc said the colour came mixed by
	 * strength, and the body returned it raw: the doc now says raw, and this holds the body to it. And
	 * AreaData lists its subfile 4 as the environment block AreaEnv decodes - it said "???" and typed
	 * it UNKNOWN after the block was proven, while three readers picked it by the bare number.
	 */
	private static void theAtmosphereSaysWhatItIs() throws Exception {
		ctrmap.formats.area.AreaEnv env = ctrmap.formats.area.AreaEnv.read(new byte[ctrmap.formats.area.AreaEnv.SUB4_LEN]);
		int day = ctrmap.formats.area.AreaEnv.TIME_DAY;
		env.fogStrength[day] = 0.1f;
		env.fogColor[day][0] = 0.2f;
		env.fogColor[day][1] = 0.4f;
		env.fogColor[day][2] = 0.6f;
		float[] thin = env.viewFog(day);
		check(thin != null && thin[0] == 0.2f && thin[1] == 0.4f && thin[2] == 0.6f,
				"a thin fog's view colour is the colour the block stores, not mixed by its strength: "
				+ java.util.Arrays.toString(thin));
		env.fogStrength[day] = 0f;
		check(env.viewFog(day) == null, "and a fog of strength 0 is no fog");
		File area = new File(Scratch.dir("ctrmap_adtypes"), "area.bin");
		byte[][] empty = new byte[12][0];
		java.nio.file.Files.write(area.toPath(), HandedGameTest.container("AD", empty));
		ctrmap.formats.containers.AD ad = new ctrmap.formats.containers.AD(area, new FakeGameFiles());
		check(ad.getDefaultContentType(ctrmap.formats.containers.AD.ENVIRONMENT)
				== ctrmap.formats.containers.ContentType.AREA_ENVIRONMENT,
				"AreaData types subfile " + ctrmap.formats.containers.AD.ENVIRONMENT + " as the environment block AreaEnv reads: "
				+ ad.getDefaultContentType(ctrmap.formats.containers.AD.ENVIRONMENT));
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
			Ui.error("A message", "A title");
			Ui.message("Info message", "Info title", JOptionPane.INFORMATION_MESSAGE);
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
			answered = ctrmap.Ui.confirmOpenWithoutWorkspace("Open zone");
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
			answered = ctrmap.Ui.confirmOpenWithoutWorkspace("Open zone");
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
		ctrmap.Ui.Keep answer;
		boolean[] putBack = {false};
		try {
			answer = ctrmap.Ui.askToKeep(true, "The zone", () -> putBack[0] = true);
		} finally {
			Ui.stopRecording();
		}
		check(answer == ctrmap.Ui.Keep.DISCARD && putBack[0],
				"the save question hands the user's NO back as DISCARD, not as consent, having put the editor back (F106)");
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
		Skip.section("switchToolUI/setGraphicUI need CtrmapMainframe's live split panes"
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
