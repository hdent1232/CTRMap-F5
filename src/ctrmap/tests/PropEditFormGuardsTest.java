package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.formats.containers.AD;
import ctrmap.formats.containers.GR;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.GRProp;
import ctrmap.gamedef.ArchiveType;
import ctrmap.humaninterface.CM3DRenderable;
import ctrmap.humaninterface.H3DRenderingPanel;
import ctrmap.humaninterface.PropEditForm;
import ctrmap.humaninterface.TileMapPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.JSpinner;

/**
 * What the prop editor writes into a map region, driven with no window,
 * against retail FieldData region 7 (two props).
 *
 * <p>These are CHARACTERIZATION checks. PropEditForm is the least-covered file
 * anybody is about to modify (1.3% of its branches), and its whole job is to
 * turn ten widgets into a 44-byte record inside subfile 3 of a GR container. So
 * the assertions here are the bytes of that subfile: after every drive the
 * suite decodes the propdata the form wrote and compares it, field by field,
 * with the propdata that was read in.
 *
 * <p>What is pinned, INCLUDING the parts that look wrong (see the report; a
 * characterization test that asserted the improvement would silently stop
 * guarding the refactor it exists for):
 * <ol>
 * <li>The ten widgets show the record on open, and Save reads them back into a
 *     brand-new {@link GRProp} that replaces it in the list.</li>
 * <li>Save reads the coordinate widgets as TEXT, and the widgets format to two
 *     decimal places. So a number written into the record without refreshing
 *     the form is REVERTED to what the widget still shows, and a number finer
 *     than two decimals is rounded on its way to the file. (No retail prop is
 *     finer than that - measured across all 857 regions - so opening a region
 *     and pressing Save writes nothing at all, which is checked first.)</li>
 * <li>The record Save builds is fresh, so the 4-byte {@code unknown} tail the
 *     format carries is written as zero whatever it held. {@code equalsData},
 *     which decides whether anything is written at all, does not compare it
 *     either.</li>
 * <li>Typing in X, Y or Z moves the prop in the live record IMMEDIATELY, before
 *     Save, through the field's own document listener - and marks the propdata
 *     modified. Typing in a scale or rotation field does not.</li>
 * <li>Save-with-dialog: Yes writes, No throws the edit away without writing and
 *     without saying so, and closing the dialog cancels and keeps it.</li>
 * <li>Changing the model spinner commits at once, and a model this area cannot
 *     texture is refused registration with a reason - while the record is
 *     written pointing at it anyway.</li>
 * <li>New entry and Remove entry, and what the propdata holds afterwards - and
 *     that both throw on the single-region path, which passes no registry.</li>
 * <li>{@code unload()} leaves nothing of the old region behind.</li>
 * </ol>
 *
 * <p>THIS SUITE USED TO ARM {@code loaded} BY HAND, and said so here, because
 * showProp could not finish: updateModel fell through its own out-of-range
 * branch into an IndexOutOfBounds, and showProp's catch-all swallowed it before
 * {@code loaded = true}. That is fixed, the workaround is gone, and
 * {@link #openingARegionLeavesTheFormLive} is what would notice it coming back.
 * The single-region fixture no longer sets the flag at all.
 *
 * Usage: java ctrmap.tests.PropEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class PropEditFormGuardsTest {


	/** The 3D scene the map view shares: a recorder, so what it was told can be read. */
	static final RecordingScene SCENE = new RecordingScene();

	/** The 3D gizmo these forms move, so what they told it can be read back. */
	static final RecordingNavi NAVI = new RecordingNavi();
	/** The redraw the forms here are handed: what frame.repaint() was, but readable. */
	static final Redraws REDRAW = new Redraws();


	/** The zone owner every panel and form built here shares, as the window's would. */
	static final LoadedZone LOADED = new LoadedZone();
	/** The tool this suite holds: its own, so another suite may hold another. */
	static final ctrmap.humaninterface.tools.ToolSelection TOOLS = new ctrmap.humaninterface.tools.ToolSelection();


	/** Two props, a real map model and one collision layer - the smallest region that has all three. */
	static final int REGION = 7;
	/** The one AreaData whose prop registry knows both of region 7's models (uids 10 and 17). */
	static final int AREA = 9;
	/** Bytes per prop record: uid, 3 scales, 3 rotations, 3 positions, unknown. */
	static final int RECORD = 44;

	static int fails = 0;

	/**
	 * The suite exits even when a section throws.
	 *
	 * <p>WHY THIS WRAPPER EXISTS. This suite builds a GL preview and a 3D view,
	 * both of which keep non-daemon threads alive, and it relies on the
	 * {@code System.exit} at the end of {@link #run} to stop them. An exception
	 * escaping a section skipped that exit, so the JVM stayed up for ever with
	 * the verdict already printed - and from outside, a suite that hangs and a
	 * suite that is merely slow look exactly the same. One planted defect sat
	 * for twenty minutes looking like a slow pass before anyone asked.
	 */
	public static void main(String[] args) throws Exception {
		try {
			run(args);
		} catch (Throwable thrown) {
			System.out.println("  FAIL: a section threw, which is a failure and not a hang: " + thrown);
			thrown.printStackTrace();
			System.out.println("FAILURES PRESENT (" + (fails + 1) + ")");
			System.exit(1);
		}
	}

	static void run(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "no-dump-given");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the prop form checks need FieldData region " + REGION);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		ScratchGame.open(dump);
		ctrmap.formats.text.LocationNames.loadFromGarc(Workspace.session());
		//a map view with no matrix: that is what sends the save to the region's
		//own GR rather than through the matrix distributor
		CtrmapMainframe.mTilemapScrollPane = new javax.swing.JScrollPane();
		CtrmapMainframe.mTileMapPanel = new TileMapPanel(LOADED, TOOLS, SCENE, new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(TOOLS), null);
		//the 3D view the form pushes every edit into. Constructible with no
		//display; the window it lives in is not. The form no longer needs
		//loaded armed by hand - see openingARegionLeavesTheFormLive.
		CtrmapMainframe.m3DDebugPanel = new H3DRenderingPanel(new ArrayList<CM3DRenderable>(), TOOLS);

		theRegionIsTheOneTheseChecksDescribe();
		openingARegionShowsTheFirstProp();
		openingARegionLeavesTheFormLive();
		theGizmoFollowsThePropTheFormShows();
		saveWithNothingTypedWritesNothing();
		saveWritesTheWidgetTextNotTheRecord();
		saveWritesTheTypedNumbersAndZeroesTheUnknownTail();
		equalsDataIgnoresTheUnknownTail();
		typingACoordinateMovesThePropBeforeSave();
		movingAPropAsksForARedraw();
		saveWithDialogHasThreeAnswers();
		newEntryAndRemoveEntry();
		removingTheFirstOfTwoLeavesTheSurvivorShowing();
		savingWithNowhereToWriteRefusesAndSaysSo();
		withoutARegistryNewAndRemoveThrow();
		unloadLeavesNothingBehind();

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//the form owns a GL preview and the 3D view owns an animator, both of
		//which keep non-daemon threads alive; the exit code is still the verdict
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The corpus these checks describe, asserted rather than assumed. */
	static void theRegionIsTheOneTheseChecksDescribe() throws Exception {
		Fixture f = open(false);
		check(f.form.props.props.size() == 2, "FieldData region " + REGION + " holds 2 props (found " + f.form.props.props.size() + ")");
		check(f.before.length == 4 + 2 * RECORD, "its propdata is a count plus two 44-byte records: " + f.before.length + " bytes");
		check(Arrays.equals(f.form.props.assemblePropData(), f.before),
				"and rebuilds byte for byte, so a difference below is the form's doing");
		boolean clean = true;
		String first = "";
		for (GRProp p : f.form.props.props) {
			float[] all = {p.scaleX, p.scaleY, p.scaleZ, p.rotateX, p.rotateY, p.rotateZ, p.x, p.y, p.z};
			for (float v : all) {
				if (v != round2(v) && clean) {
					clean = false;
					first = String.valueOf(v);
				}
			}
		}
		//measured over all 857 regions: no retail prop carries a float the
		//two-decimal widgets cannot show, so the rounding below never bites
		//shipped data - it bites what a person or an importer puts in
		check(clean, "every number in its propdata survives two decimal places" + (clean ? "" : " - but " + first + " does not"));
		ADPropRegistry reg = registry();
		boolean known = true;
		for (GRProp p : f.form.props.props) {
			known &= reg.entries.containsKey(p.uid);
		}
		check(known, "area " + AREA + "'s prop registry knows both of its models");
	}

	/** Opening the region selects prop 0 and puts its ten numbers in the ten widgets. */
	static void openingARegionShowsTheFirstProp() throws Exception {
		Fixture f = open(false);
		GRProp p0 = f.form.props.props.get(0);
		check(f.form.prop == p0 && f.form.propIndex == 0, "prop 0 is the selected record");
		check(entries(f.form) == 2, "the dropdown lists one item per prop: " + entries(f.form));
		check(((Integer) ((JSpinner) field(f.form, "mdlNum")).getValue()) == p0.uid, "the model spinner shows uid " + p0.uid);
		check(text(f.form, "x").equals(fmt(p0.x)) && text(f.form, "y").equals(fmt(p0.y)) && text(f.form, "z").equals(fmt(p0.z)),
				"the position fields show " + text(f.form, "x") + "," + text(f.form, "y") + "," + text(f.form, "z"));
		check(text(f.form, "sx").equals(fmt(p0.scaleX)) && text(f.form, "sy").equals(fmt(p0.scaleY)) && text(f.form, "sz").equals(fmt(p0.scaleZ)),
				"the scale fields show " + text(f.form, "sx") + "," + text(f.form, "sy") + "," + text(f.form, "sz"));
		check(text(f.form, "rx").equals(fmt(p0.rotateX)) && text(f.form, "ry").equals(fmt(p0.rotateY)) && text(f.form, "rz").equals(fmt(p0.rotateZ)),
				"the rotation fields show " + text(f.form, "rx") + "," + text(f.form, "ry") + "," + text(f.form, "rz"));
	}

	/** Open a region, press Save, and the file must be the one that was read. */
	static void saveWithNothingTypedWritesNothing() throws Exception {
		Fixture f = open(true);
		GRProp p0 = f.form.props.props.get(0);
		f.form.saveProp();
		check(f.form.props.props.get(0) == p0, "Save with nothing typed keeps the very same record");
		check(!f.form.props.modified, "and does not mark the propdata modified");
		check(f.form.store(false), "so a store afterwards succeeds");
		check(Arrays.equals(f.propdata(), f.before), "and the propdata on disk is untouched");
	}

	/**
	 * How the save actually works, which is the thing a refactor has to keep:
	 * Save reads the coordinate widgets' TEXT, not their value and not the
	 * record. So
	 * <ul>
	 * <li>a number written straight into the record - anything that moves a
	 *     prop without refreshing the form - is REVERTED to whatever the widget
	 *     still says, and</li>
	 * <li>a number finer than the widget's two decimals is rounded on its way
	 *     to the file.</li>
	 * </ul>
	 * Both asserted on the bytes.
	 */
	static void saveWritesTheWidgetTextNotTheRecord() throws Exception {
		Fixture f = open(true);
		GRProp p0 = f.form.props.props.get(0);
		float shown = p0.x;
		p0.x = shown + 123.456f; //moved in the record alone, as nothing that refreshes the form would
		check(text(f.form, "x").equals(fmt(shown)), "the X widget still reads " + text(f.form, "x"));
		f.form.saveProp();
		check(f.form.props.props.get(0).x == shown, "Save put the prop back to what the widget said: " + f.form.props.props.get(0).x);
		check(f.form.store(false), "the region stores");
		check(f32(records(f.propdata())[0], 28) == shown, "and the file holds " + f32(records(f.propdata())[0], 28) + ", not " + (shown + 123.456f));

		Fixture g = open(true);
		type(g.form, "x", 1773.456f);
		check(text(g.form, "x").equals("1773.46"), "typing 1773.456 leaves the widget reading " + text(g.form, "x"));
		g.form.saveProp();
		check(g.form.store(false), "the region stores");
		byte[][] now = records(g.propdata());
		check(f32(now[0], 28) == 1773.46f, "and the file holds " + f32(now[0], 28) + " - two decimals is all the form can carry");
		check(sameRecord(now[1], record(g.before, 1)), "prop 1, which was never selected, is byte for byte as it was read");
	}

	/**
	 * The whole record, from the widgets, in the order the file holds it - and
	 * the four bytes at the end that no widget shows, which Save always writes
	 * as zero because it builds a fresh record rather than editing the old one.
	 */
	static void saveWritesTheTypedNumbersAndZeroesTheUnknownTail() throws Exception {
		Fixture f = open(true);
		//the model spinner commits on every change, and a model this area cannot
		//texture is refused registration - with the record still pointed at it
		int registered = f.form.reg.entries.size();
		List<String> said = ctrmap.Ui.record();
		try {
			((JSpinner) field(f.form, "mdlNum")).setValue(321);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(said.size() == 1 && said.get(0).contains("it was not registered"),
				"typing an unregistered model says it was not registered: " + said);
		check(f.form.reg.entries.size() == registered && !f.form.reg.entries.containsKey(321),
				"and no entry is invented for it");
		check(f.form.regentry == null, "so the form has no registry entry to edit");
		type(f.form, "x", 111.25f);
		type(f.form, "y", -222.5f);
		type(f.form, "z", 333.75f);
		type(f.form, "sx", 2.5f);
		type(f.form, "sy", 3.25f);
		type(f.form, "sz", 4.75f);
		type(f.form, "rx", 10.5f);
		type(f.form, "ry", -20.25f);
		type(f.form, "rz", 30.75f);
		//no retail prop carries a non-zero tail, so the loss has to be staged - on
		//the record Save is about to read, after the spinner already replaced one
		f.form.prop.unknown = 0x12345678;
		f.form.saveProp();
		check(f.form.store(false), "the region stores");

		byte[] r = records(f.propdata())[0];
		check(u32(r, 0) == 321, "the model uid is the spinner's: " + u32(r, 0));
		check(f32(r, 4) == 2.5f && f32(r, 8) == 3.25f && f32(r, 12) == 4.75f,
				"then scale X, Y, Z: " + f32(r, 4) + "," + f32(r, 8) + "," + f32(r, 12));
		check(f32(r, 16) == 10.5f && f32(r, 20) == -20.25f && f32(r, 24) == 30.75f,
				"then rotation X, Y, Z: " + f32(r, 16) + "," + f32(r, 20) + "," + f32(r, 24));
		check(f32(r, 28) == 111.25f && f32(r, 32) == -222.5f && f32(r, 36) == 333.75f,
				"then position X, Y, Z: " + f32(r, 28) + "," + f32(r, 32) + "," + f32(r, 36));
		check(u32(r, 40) == 0, "and the unknown tail, written as 0 where the record held 0x12345678");
		check(u32(r, 0) == 321 && !f.form.reg.entries.containsKey(321),
				"the record names model 321 in the file while the area's registry still does not - see the report");
	}

	/**
	 * The test that decides whether anything is written at all compares nine
	 * numbers and the uid - not the unknown tail. Two records that differ only
	 * there are "the same prop" to the form.
	 */
	static void equalsDataIgnoresTheUnknownTail() throws Exception {
		Fixture f = open(false);
		GRProp a = new GRProp();
		GRProp b = new GRProp();
		check(f.form.equalsData(a, b), "two default props are equal data");
		b.unknown = 0x0BADF00D;
		check(f.form.equalsData(a, b), "and still are when only the unknown tail differs");
		b.unknown = 0;
		b.scaleY = 1.5f;
		check(!f.form.equalsData(a, b), "but not when a scale does");
		b.scaleY = 1f;
		b.uid = 1;
		check(!f.form.equalsData(a, b), "nor when the model uid does");
	}

	/**
	 * X, Y and Z carry document listeners, so the prop follows the caret: the
	 * live record moves and the propdata is marked modified before Save is
	 * pressed at all. Scale and rotation have none.
	 */
	static void typingACoordinateMovesThePropBeforeSave() throws Exception {
		Fixture f = open(true);
		GRProp p0 = f.form.props.props.get(0);
		f.form.props.modified = false;
		type(f.form, "x", 640.5f);
		check(p0.x == 640.5f, "typing in X moved the live record to " + p0.x + " with no Save");
		check(f.form.props.modified, "and marked the propdata modified");
		check(f.form.props.props.get(0) == p0, "the record object itself was edited, not replaced");

		f.form.props.modified = false;
		float scale = p0.scaleX;
		type(f.form, "sx", 9.5f);
		check(p0.scaleX == scale && !f.form.props.modified, "typing in Scale X changes nothing until Save (record still " + p0.scaleX + ")");
		f.form.saveProp();
		check(f.form.props.props.get(0).scaleX == 9.5f, "and Save then takes it: " + f.form.props.props.get(0).scaleX);
	}

	/**
	 * store(true) asks before writing. Yes writes; No writes nothing AND drops
	 * the modified flag, so the edit is gone with nothing said; closing the
	 * dialog cancels, which keeps the edit and refuses the store.
	 */
	static void saveWithDialogHasThreeAnswers() throws Exception {
		Fixture yes = open(true);
		type(yes.form, "x", 12.5f);
		yes.form.saveProp();
		List<String> said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		boolean ok;
		try {
			ok = yes.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.size() == 1 && said.get(0).contains("Prop data has been modified"), "Yes: the form asks once - " + said);
		check(f32(records(yes.propdata())[0], 28) == 12.5f, "and writes the edit: X is " + f32(records(yes.propdata())[0], 28));

		Fixture no = open(true);
		byte[] untouched = no.propdata();
		type(no.form, "x", 12.5f);
		no.form.saveProp();
		said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = no.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok, "No: the store reports success");
		check(Arrays.equals(no.propdata(), untouched), "and writes nothing");
		check(!no.form.props.modified, "but clears the modified flag, so the edit is gone with nothing said");

		Fixture cancel = open(true);
		untouched = cancel.propdata();
		type(cancel.form, "x", 12.5f);
		cancel.form.saveProp();
		said = ctrmap.Ui.record(); //no answer at all: the same as closing the dialog
		try {
			ok = cancel.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!ok, "Closing the dialog: the store reports failure");
		check(Arrays.equals(cancel.propdata(), untouched), "writes nothing");
		check(cancel.form.props.modified && cancel.form.props.props.get(0).x == 12.5f, "and keeps the edit to be saved later");
	}

	/**
	 * New entry copies the selected prop's model uid onto a fresh record at the
	 * viewport centre and selects it; Remove entry takes the selected slot.
	 * Both are asserted on what the region then holds.
	 */
	static void newEntryAndRemoveEntry() throws Exception {
		Fixture f = open(true);
		int uid = f.form.props.props.get(0).uid;
		invoke(f.form, "btnNewEntryActionPerformed");
		check(f.form.props.props.size() == 3, "New entry added a prop: " + f.form.props.props.size());
		GRProp added = f.form.props.props.get(2);
		check(added.uid == uid, "with the selected prop's model uid " + uid);
		check(added.scaleX == 1f && added.scaleY == 1f && added.scaleZ == 1f, "at scale 1");
		check(added.y == 0f && added.rotateX == 0f && added.rotateY == 0f && added.rotateZ == 0f, "flat on the ground, unrotated");
		check(entries(f.form) == 3 && f.form.propIndex == 2 && f.form.prop == added, "and it is the selected entry");
		check(f.form.props.modified, "and the propdata is marked modified");

		f.form.loaded = true;
		check(f.form.store(false), "the region stores");
		byte[][] now = records(f.propdata());
		check(now.length == 3 && u32(f.propdata(), 0) == 3, "the propdata counts three records");
		check(u32(now[2], 0) == uid && f32(now[2], 4) == 1f, "the third is the new one");

		f.form.loaded = true;
		f.form.setProp(1);
		f.form.loaded = true;
		invoke(f.form, "btnRemEntryActionPerformed");
		check(f.form.props.props.size() == 2, "Remove entry took one back out: " + f.form.props.props.size());
		check(f.form.props.props.get(1) == added, "and the one it took was the selected slot, not the last");
		f.form.loaded = true;
		check(f.form.store(false), "the region stores");
		check(u32(f.propdata(), 0) == 2 && records(f.propdata()).length == 2, "and the propdata counts two records again");
	}

	/**
	 * The single-region path - File &gt; Open GR, and the map view whenever it is
	 * not showing a matrix - hands the form a null registry, and both entry
	 * buttons dereference it. Pinned as it stands; see the report.
	 */
	static void withoutARegistryNewAndRemoveThrow() throws Exception {
		Fixture f = openSingleRegion();
		check(f.form.reg == null, "loadDataFile(GR, textures) gives the form no prop registry at all");
		Throwable add = null;
		try {
			invoke(f.form, "btnNewEntryActionPerformed");
		} catch (Throwable t) {
			add = t;
		}
		check(add instanceof NullPointerException, "New entry throws " + add);
		check(f.form.props.props.size() == 3, "having already added the record before it threw: " + f.form.props.props.size() + " props");

		Fixture g = openSingleRegion();
		Throwable rem = null;
		try {
			invoke(g.form, "btnRemEntryActionPerformed");
		} catch (Throwable t) {
			rem = t;
		}
		check(rem instanceof IndexOutOfBoundsException, "Remove entry throws " + rem);
		check(g.form.props.props.size() == 2, "and removes nothing: " + g.form.props.props.size() + " props");
	}

	/** Closing a region must leave the form holding nothing of it. */
	static void unloadLeavesNothingBehind() throws Exception {
		Fixture f = open(true);
		f.form.unload();
		check(!f.form.loaded && f.form.prop == null && f.form.props == null, "unload dropped the propdata and the selection");
		check(f.form.reg == null && f.form.regentry == null && f.form.models.isEmpty(), "the registry and the models with it");
		check(f.form.propIndex == -1 && entries(f.form) == 0, "and the dropdown is empty");
		check(f.form.store(false), "storing an unloaded form succeeds and writes nothing");
	}

	/**
	 * Removing the first of two props leaves the survivor selected and showing.
	 *
	 * <p>{@code DefaultComboBoxModel.removeElementAt} picks the replacement
	 * selection BEFORE it removes the item, so the box fired its ActionEvent
	 * while it still held both entries. Removing prop 0 of 2 therefore re-entered
	 * {@code showProp} with index 1 against a list already down to one: showProp
	 * took its "no such prop" early return, left {@code prop} null and
	 * {@code propIndex} past the end, and the corrective setSelectedIndex that
	 * used to stand there fired nothing because the index was already in range.
	 *
	 * <p>What that cost: the form went on displaying the prop that had just been
	 * deleted, the gizmo went on following it, and the SURVIVING prop could not
	 * be reached - not from the box, where it was already the selection, and not
	 * from either prop tool, whose setProp is setSelectedIndex. It also ran
	 * saveProp against a prop already out of the list, which is
	 * {@code props.props.set(-1, prop)} whenever a coordinate had been typed and
	 * not saved. Three or more props hid it, because the event landed in range.
	 */
	static void removingTheFirstOfTwoLeavesTheSurvivorShowing() throws Exception {
		System.out.println("--- removing the first of two props leaves the other one showing");
		Fixture f = open(true);
		check(f.form.props.props.size() == 2, "the region holds two props");
		GRProp survivor = f.form.props.props.get(1);
		f.form.setProp(0);
		f.form.loaded = true;
		invoke(f.form, "btnRemEntryActionPerformed");
		check(f.form.props.props.size() == 1, "one prop left: " + f.form.props.props.size());
		check(f.form.props.props.get(0) == survivor, "and it is the one that was second");
		check(f.form.prop == survivor, "the form is showing THAT prop, not the deleted one");
		check(f.form.propIndex == 0, "at index 0, which is where it now lives: " + f.form.propIndex);
		check(entries(f.form) == 1, "with one entry in the dropdown: " + entries(f.form));
		check(f.form.props.modified, "and the propdata is marked modified");
		//the survivor must be REACHABLE, which is the half the old code lost:
		//setProp is setSelectedIndex, a no-op for an index already selected
		f.form.setProp(0);
		check(f.form.prop == survivor, "and picking it from a tool still finds it");
		f.form.loaded = true;
		check(f.form.store(false), "the region stores");
		check(u32(f.propdata(), 0) == 1 && records(f.propdata()).length == 1,
			"and the propdata counts one record");
	}

	/**
	 * Answering "save" with nowhere to write refuses, and says so.
	 *
	 * <p>The two write branches are the only ways these props reach a file:
	 * through the open matrix's regions, or through the single GR the form was
	 * handed. With neither - which is what a matrix load that failed part-way
	 * leaves behind, because the failure path calls unload() and that nulls the
	 * matrix while this form keeps the previous zone's props - the switch fell
	 * through to "modified = false", so every prop the user had moved, added or
	 * removed was discarded AND marked clean, with nothing said anywhere.
	 */
	static void savingWithNowhereToWriteRefusesAndSaysSo() throws Exception {
		System.out.println("--- answering save with nowhere to write refuses, in words");
		Fixture f = open(true);
		type(f.form, "x", 123.5f);
		f.form.saveProp();
		check(f.form.props.modified, "there is an edit to lose");
		f.form.gr = null;
		ctrmap.humaninterface.TileMapPanel map = CtrmapMainframe.mTileMapPanel;
		ctrmap.formats.mapmatrix.MapMatrix was = map.mm;
		map.mm = null;
		List<String> said = ctrmap.Ui.record(JOptionPane.YES_OPTION);
		boolean answered;
		try {
			answered = f.form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
			map.mm = was;
		}
		check(!answered, "the save refuses rather than reporting success");
		check(said.size() == 2, "and two things were said - the question, then the refusal: " + said.size());
		check(said.size() == 2 && said.get(1).contains("were NOT saved"),
			"the refusal says the edits were not saved: " + (said.size() == 2 ? said.get(1) : said));
		check(said.size() == 2 && said.get(1).contains("no map"),
			"and why - there is no map open to write them into");
		check(f.form.props.modified,
			"and the edits are still there to be saved, not marked clean and dropped");
	}

	// ---- fixture -------------------------------------------------------

	/** A loaded prop form on a scratch copy of region {@link #REGION}. */
	static class Fixture {

		PropEditForm form;
		GR gr;
		/** The propdata as it was read in, unpadded. */
		byte[] before;

		/** The propdata the container holds now, trimmed of the GR's 128-byte padding. */
		byte[] propdata() {
			byte[] raw = gr.getFile(3);
			return Arrays.copyOf(raw, 4 + count(raw) * RECORD);
		}
	}

	/** A fresh scratch copy of region {@link #REGION} - these checks write to it. */
	static GR scratchRegion() throws Exception {
		File onDisk = Scratch.file("ctrmap_propform");
		java.nio.file.Files.write(onDisk.toPath(),
				Workspace.getArchive(ArchiveType.FIELD_DATA).getDecompressedEntry(REGION));
		return new GR(onDisk, Workspace.session());
	}

	/**
	 * The region in a form with no window, holding the area's prop registry -
	 * which is what the entry buttons need and what the map view supplies.
	 *
	 * <p>The application never combines exactly these two: its single-region
	 * path passes a GR and no registry, its matrix path a registry and no GR.
	 * The fixture puts both on the form, so the entry buttons can be driven and
	 * the result read back out of the region's own container; what the bytes
	 * assert is {@code assemblePropData}, which is what both paths write.
	 *
	 * @param arm true to set {@code loaded}, the state a real main window would
	 * leave the form in; see the class comment
	 */
	static Fixture open(boolean arm) throws Exception {
		Fixture f = new Fixture();
		f.gr = scratchRegion();
		f.form = new PropEditForm(LOADED, TOOLS, REDRAW, NAVI, CtrmapMainframe.mTileMapPanel);
		CtrmapMainframe.mPropEditForm = f.form;
		f.form.loadDataFile(new ctrmap.formats.propdata.GRPropData(f.gr), registry(), null);
		f.form.gr = f.gr;
		f.before = f.propdata();
		f.form.loaded = arm;
		return f;
	}

	/**
	 * Opening a region leaves the form LIVE, without the suite arming it.
	 *
	 * <p>WHY THIS IS THE PROOF. This suite used to set {@code loaded = true} by
	 * hand, and its own javadoc explained why: showProp could not finish. It
	 * called updateModel, which decided the prop had no model slot, loaded a null
	 * model, and then carried on into models.set() with the very index it had
	 * just rejected. IndexOutOfBounds, straight into showProp's catch-all - so
	 * redraw and {@code loaded = true} never ran, while propIndex, prop and all
	 * twelve widgets already held the new prop. A form that looks switched and
	 * does nothing.
	 *
	 * <p>With loaded false the entry box stops switching props, Save is a no-op,
	 * the coordinate fields stop writing through, and both prop tools stop
	 * selecting and dragging - so the workaround in this suite was standing in
	 * for a form the user could not have used either.
	 */
	static void openingARegionLeavesTheFormLive() throws Exception {
		System.out.println("--- opening a region leaves the form live, with nothing armed by hand");
		Fixture f = openSingleRegion();
		check(f.form.loaded, "showProp finished on its own and the form reports itself loaded");
		check(f.form.prop != null, "and it is holding the prop it showed");
		check(f.form.propIndex == 0, "which is the one it was asked for (" + f.form.propIndex + ")");
	}

	/**
	 * The 3D gizmo stands over the prop the form is showing.
	 *
	 * <p>THIS COULD NOT BE ASSERTED AT ALL until the gizmo became something the
	 * form is handed. It was a reach into the main window for a JOGL panel, so a
	 * headless suite got a null, an unguarded dereference, and an exception
	 * swallowed by showProp's catch-all - and every other assertion about the
	 * form still passed while the editor sat inert.
	 *
	 * <p>It also pins WHICH prop. The bind used to index the list a second time
	 * through the selection box while the rest of the method used the parameter
	 * it was given: two sources for one identity, so when they disagreed the
	 * gizmo stood over a different prop than the one on screen.
	 */
	static void theGizmoFollowsThePropTheFormShows() throws Exception {
		System.out.println("--- the 3D gizmo follows the prop the form is showing");
		NAVI.reset();
		Fixture f = openSingleRegion();
		check(!NAVI.followed.isEmpty(), "opening a region tells the gizmo what to follow");
		check(NAVI.following() == f.form.prop,
				"and it is the very prop the form is holding, not another one at the same index");

		f.form.showProp(1);
		check(NAVI.following() == f.form.prop,
				"showing a different prop moves it to that one (" + f.form.propIndex + ")");
		check(NAVI.following() != null, "which is a record, not nothing");
	}

	/** The region the way File &gt; Open GR opens it: no registry at all. */
	static Fixture openSingleRegion() throws Exception {
		Fixture f = new Fixture();
		f.gr = scratchRegion();
		f.form = new PropEditForm(LOADED, TOOLS, REDRAW, NAVI, CtrmapMainframe.mTileMapPanel);
		CtrmapMainframe.mPropEditForm = f.form;
		f.form.loadDataFile(f.gr, null);
		f.before = f.propdata();
		//NOT armed by hand any more: showProp finishes now, so the form arms
		//itself exactly as a real window leaves it. See
		//openingARegionLeavesTheFormLive for what this line was standing in for.
		return f;
	}

	/** Area {@link #AREA}'s prop registry, without its models: the meshes are not what is under test. */
	static ADPropRegistry registry() throws Exception {
		return new ADPropRegistry(new AD(Workspace.getWorkspaceFile(ArchiveType.AREA_DATA, AREA), Workspace.session()));
	}

	/** Types into one of the form's number fields the way the widget itself would. */
	static void type(PropEditForm form, String widget, float value) throws Exception {
		((JFormattedTextField) field(form, widget)).setValue(value);
	}

	/** What a widget's document actually holds - which is what Save reads. */
	static String text(PropEditForm form, String widget) throws Exception {
		return ((JFormattedTextField) field(form, widget)).getText();
	}

	/** A float as the form's own formatter writes it: two decimals. */
	static String fmt(float v) {
		return new java.text.DecimalFormat("#0.00").format(v);
	}

	/** The value a two-decimal round trip through the widget leaves behind. */
	static float round2(float v) {
		return Float.parseFloat(fmt(v).replace(',', '.'));
	}

	static int count(byte[] propdata) {
		return u32(propdata, 0);
	}

	/** The propdata split into its 44-byte records. */
	static byte[][] records(byte[] propdata) {
		byte[][] out = new byte[count(propdata)][];
		for (int i = 0; i < out.length; i++) {
			out[i] = record(propdata, i);
		}
		return out;
	}

	static byte[] record(byte[] propdata, int i) {
		return Arrays.copyOfRange(propdata, 4 + i * RECORD, 4 + (i + 1) * RECORD);
	}

	static boolean sameRecord(byte[] a, byte[] b) {
		return Arrays.equals(a, b);
	}

	static int entries(PropEditForm form) throws Exception {
		return ((JComboBox<?>) field(form, "entryBox")).getItemCount();
	}

	static int u32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	static float f32(byte[] b, int o) {
		return Float.intBitsToFloat(u32(b, o));
	}

	static Object field(Object o, String name) throws Exception {
		Field fl = o.getClass().getDeclaredField(name);
		fl.setAccessible(true);
		return fl.get(o);
	}

	/** Calls one of the generated button handlers. */
	static void invoke(Object o, String name) throws Exception {
		Method m = o.getClass().getDeclaredMethod(name, java.awt.event.ActionEvent.class);
		m.setAccessible(true);
		try {
			m.invoke(o, (java.awt.event.ActionEvent) null);
		} catch (InvocationTargetException ex) {
			if (ex.getCause() instanceof Exception) {
				throw (Exception) ex.getCause();
			}
			throw ex;
		}
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}

	/**
	 * A prop that moved asks for the editor to be drawn again - and a field
	 * write that moved nothing does not.
	 *
	 * <p>WHY THIS IS THE PROOF. The seven places in this form that said "what I
	 * changed is on screen somewhere" said it to nobody: they called the bare
	 * inherited {@code JComponent.firePropertyChange}, and nothing has ever
	 * listened to this form, so with changeSupport null the call returned at
	 * once. The only listener that property has anywhere is on the MAP VIEW,
	 * which is why the identical line works from PaintForm, Selector and
	 * TileUndo - they spell it with the panel in front. It cost the user a
	 * picture they could see was wrong: TileMapPanel.paintComponent composites
	 * the 3D scene, props included, over the tilemap at 50% alpha and only
	 * while the panel is being painted, so typing a new X moved the prop in the
	 * 3D view (which has its own animator) and left the 2D map drawing it where
	 * it was, until hovering the map made Selector ask for a repaint.
	 *
	 * <p>BOTH HALVES MATTER. Handed a counting {@link Redraws}, the first check
	 * fails if the request goes back to reaching nobody. The second fails if the
	 * request is moved back OUTSIDE the {@code loaded && prop != null && it
	 * actually changed} guard it now sits in - which is what would put a
	 * full-window repaint behind every keystroke that changes nothing at all,
	 * including the twelve fields this form fills while opening a region.
	 */
	static void movingAPropAsksForARedraw() throws Exception {
		Fixture f = open(true);
		GRProp p0 = f.form.props.props.get(0);
		int moved = REDRAW.mark();
		type(f.form, "x", 640.5f);
		check(p0.x == 640.5f, "typing a new X moved the prop to " + p0.x);
		check(REDRAW.askedSince(moved), "and asked for a redraw, so the map view's prop overlay is not left stale");

		//a form that is not live is one being filled in, not one being edited
		Fixture g = open(false);
		GRProp q0 = g.form.props.props.get(0);
		float held = q0.x;
		int quiet = REDRAW.mark();
		type(g.form, "x", held + 100f);
		check(q0.x == held, "writing X on a form that is not live moves nothing: " + q0.x);
		check(!REDRAW.askedSince(quiet), "and asks for no redraw - the request is inside the guard, not beside it");

		//and the same for Save, which has always had a "something changed" guard
		//of its own and only ever needed the receiver
		Fixture h = open(true);
		int nothingTyped = REDRAW.mark();
		h.form.saveProp();
		check(!REDRAW.askedSince(nothingTyped), "Save that writes nothing asks for no redraw either");
		type(h.form, "sx", 9.5f);
		int beforeSave = REDRAW.mark();
		h.form.saveProp();
		check(h.form.props.props.get(0).scaleX == 9.5f, "Save took the new scale: " + h.form.props.props.get(0).scaleX);
		check(REDRAW.askedSince(beforeSave), "and asked for a redraw, because the record it replaced is what the views draw");
	}
}
