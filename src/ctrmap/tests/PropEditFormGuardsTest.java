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
 * <p>The form cannot finish opening a prop without the application's main
 * window: {@code showProp} ends with {@code CtrmapMainframe.frame.repaint()}
 * inside its own catch-all, so {@code loaded} stays false. The suite therefore
 * arms {@code loaded} by hand - it is a public field, and true is the state a
 * real window leaves it in. Everything else is the form's own code.
 *
 * Usage: java ctrmap.tests.PropEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class PropEditFormGuardsTest {

	/** The zone owner every panel and form built here shares, as the window's would. */
	static final LoadedZone LOADED = new LoadedZone();

	/** Two props, a real map model and one collision layer - the smallest region that has all three. */
	static final int REGION = 7;
	/** The one AreaData whose prop registry knows both of region 7's models (uids 10 and 17). */
	static final int AREA = 9;
	/** Bytes per prop record: uid, 3 scales, 3 rotations, 3 positions, unknown. */
	static final int RECORD = 44;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
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
		CtrmapMainframe.mTileMapPanel = new TileMapPanel(LOADED);
		//the 3D view the form pushes every edit into. Constructible with no
		//display; the window it lives in is not, which is why loaded is armed
		//by hand below.
		CtrmapMainframe.m3DDebugPanel = new H3DRenderingPanel(new ArrayList<CM3DRenderable>());

		theRegionIsTheOneTheseChecksDescribe();
		openingARegionShowsTheFirstProp();
		saveWithNothingTypedWritesNothing();
		saveWritesTheWidgetTextNotTheRecord();
		saveWritesTheTypedNumbersAndZeroesTheUnknownTail();
		equalsDataIgnoresTheUnknownTail();
		typingACoordinateMovesThePropBeforeSave();
		saveWithDialogHasThreeAnswers();
		newEntryAndRemoveEntry();
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
		f.form = new PropEditForm(LOADED);
		CtrmapMainframe.mPropEditForm = f.form;
		f.form.loadDataFile(new ctrmap.formats.propdata.GRPropData(f.gr), registry(), null);
		f.form.gr = f.gr;
		f.before = f.propdata();
		f.form.loaded = arm;
		return f;
	}

	/** The region the way File &gt; Open GR opens it: no registry at all. */
	static Fixture openSingleRegion() throws Exception {
		Fixture f = new Fixture();
		f.gr = scratchRegion();
		f.form = new PropEditForm(LOADED);
		CtrmapMainframe.mPropEditForm = f.form;
		f.form.loadDataFile(f.gr, null);
		f.before = f.propdata();
		f.form.loaded = true;
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
}
