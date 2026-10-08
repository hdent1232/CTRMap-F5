package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.cameradata.CameraData;
import ctrmap.formats.containers.AD;
import ctrmap.formats.containers.GR;
import ctrmap.formats.containers.MM;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.formats.propdata.ADPropRegistry;
import ctrmap.formats.propdata.GRProp;
import ctrmap.formats.propdata.GRPropData;
import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.CameraEditForm;
import ctrmap.humaninterface.MatrixEditForm;
import ctrmap.humaninterface.TextEditor;
import ctrmap.humaninterface.ZoneEditors;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/**
 * Every "keep the changes?" question's No puts the editor back to what was last saved (F106).
 *
 * <p>WHY THIS SUITE EXISTS. Thirteen places asked whether to keep an editor's changes, and No meant
 * three things there: two forgot that the edit was made and kept it in memory, five cleared a flag
 * and left the edit drawn, four did nothing at all. A save is a chain of these questions that stops
 * at the first Cancel, so No to one and Cancel to a later one left the discarded edit open on screen
 * and marked clean - and the next save wrote it.
 *
 * <p>ORDER: needs no dump and no display. The question is driven through the dialog seam, the
 * restores on containers built here, and the forms that can be built headless through their own
 * store(); the ones that need GL are read in their source.
 *
 * Usage: java ctrmap.tests.DiscardRevertsTest
 */
public class DiscardRevertsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		theQuestionRunsWhatNoDoes();
		everyQuestionSaysWhatNoDoes();
		noKeepQuestionIsAskedAroundTheSeam();
		aTilemapGoesBack();
		aMatrixGoesBackInPlace();
		aMatrixsPropsAreReadInOnePlace();
		aZoneHeaderAndItsEntitiesGoBack();
		theNpcRegistryGoesBack();
		thePropRegistryGoesBack();
		theCameraFormGoesBack();
		theMatrixFormGoesBack();
		theTextEditorGoesBack();
		theEditorsThatNeedAScreenPutBackWhatTheyHold();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------ the question

	static void theQuestionRunsWhatNoDoes() {
		System.out.println("--- the one question runs what No does, on No and only on No (F106)");
		int[] putBack = {0};
		List<String> said = ctrmap.Ui.record(JOptionPane.NO_OPTION, JOptionPane.YES_OPTION, JOptionPane.CLOSED_OPTION);
		try {
			ctrmap.Ui.askToKeep(true, "Camera data", () -> putBack[0]++);
			check(putBack[0] == 1, "No runs what it was handed: " + putBack[0]);
			ctrmap.Ui.askToKeep(true, "Camera data", () -> putBack[0]++);
			ctrmap.Ui.askToKeep(true, "Camera data", () -> putBack[0]++);
			ctrmap.Ui.askToKeep(false, "Camera data", () -> putBack[0]++);
			check(putBack[0] == 1 && said.size() == 3, "and Yes, a closed dialog and no dialog wanted run nothing: "
					+ putBack[0] + " run(s), " + said.size() + " asked");
		} finally {
			ctrmap.Ui.stopRecording();
		}
		String refused = null;
		try {
			ctrmap.Ui.askToKeep(false, "Camera data", null);
		} catch (IllegalArgumentException ex) {
			refused = ex.getMessage();
		}
		check(refused != null && refused.contains("Camera data"),
				"and asked without saying what No does, it refuses - even with no dialog wanted: " + refused);
	}

	/** One call of askToKeep in a source: where it is, its arguments, and what its discard does. */
	static final class Ask {

		final String where;
		final List<String> args;
		final String discard;

		Ask(String where, List<String> args, String discard) {
			this.where = where;
			this.args = args;
			this.discard = discard;
		}
	}

	static final Pattern ASK = Pattern.compile("\\baskToKeep\\s*\\(");
	static final Pattern FLAG = Pattern.compile("[\\w.\\[\\]]*\\b(?:modified|unsaved|dirty)\\s*=\\s*(?:true|false)\\s*;?");
	static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\(");
	static final List<String> NOT_CALLS = Arrays.asList("if", "for", "while", "switch", "catch", "synchronized", "return");

	/** Every askToKeep call in {@code src}, the declaration itself left out. */
	static List<Ask> asks(String name, String src) {
		List<Ask> out = new ArrayList<>();
		Matcher m = ASK.matcher(src);
		while (m.find()) {
			List<String> args = arguments(src, m.end() - 1);
			if (args.size() == 3 && args.get(2).startsWith("Runnable ")) {
				continue;   //the declaration in Ui
			}
			int line = 1;
			for (int i = 0; i < m.start(); i++) {
				line += src.charAt(i) == '\n' ? 1 : 0;
			}
			out.add(new Ask(name + ":" + line, args, args.size() == 3 ? discardBody(src, args.get(2)) : null));
		}
		return out;
	}

	/** The arguments of the call whose '(' is at {@code open}, split at top-level commas. */
	static List<String> arguments(String src, int open) {
		List<String> out = new ArrayList<>();
		int depth = 0;
		StringBuilder cur = new StringBuilder();
		for (int i = open + 1; i < src.length(); i++) {
			char c = src.charAt(i);
			if (c == '"' || c == '\'') {
				int j = i + 1;
				while (j < src.length() && src.charAt(j) != c) {
					j += src.charAt(j) == '\\' ? 2 : 1;
				}
				cur.append(src, i, Math.min(j + 1, src.length()));
				i = j;
				continue;
			}
			if (c == '(' || c == '{' || c == '[') {
				depth++;
			} else if (c == ')' || c == '}' || c == ']') {
				if (depth == 0) {
					out.add(cur.toString().trim());
					return out;
				}
				depth--;
			} else if (c == ',' && depth == 0) {
				out.add(cur.toString().trim());
				cur.setLength(0);
				continue;
			}
			cur.append(c);
		}
		return out;
	}

	/**
	 * What a discard argument runs: a lambda's body, or the body of the method a this:: reference
	 * names in the same source. Null when it cannot be read - which is refused, not passed.
	 */
	static String discardBody(String src, String arg) {
		Matcher lambda = Pattern.compile("^\\(\\s*\\)\\s*->\\s*(.*)$", Pattern.DOTALL).matcher(arg);
		if (lambda.matches()) {
			return lambda.group(1).trim();
		}
		Matcher ref = Pattern.compile("^this::(\\w+)$").matcher(arg);
		if (ref.matches()) {
			Matcher decl = Pattern.compile("\\b(?:void|boolean)\\s+" + ref.group(1) + "\\s*\\(\\s*\\)\\s*\\{").matcher(src);
			return decl.find() ? CollisionReadTest.body(src, decl.group()) : null;
		}
		return null;
	}

	/** True when a discard does something besides clearing a flag: it calls something. */
	static boolean putsBack(String discard) {
		if (discard == null) {
			return false;
		}
		String rest = FLAG.matcher(discard).replaceAll("");
		int open = rest.indexOf('{');
		if (rest.matches("(?s)^\\s*(?:private|public|protected)?\\s*(?:void|boolean)\\s+\\w+\\s*\\(\\s*\\)\\s*\\{.*")) {
			rest = rest.substring(open + 1);
		}
		Matcher m = CALL.matcher(rest);
		while (m.find()) {
			if (!NOT_CALLS.contains(m.group(1))) {
				return true;
			}
		}
		return false;
	}

	static void everyQuestionSaysWhatNoDoes() throws Exception {
		System.out.println("--- every keep question in the program says what No does, and No puts something back (F106)");
		//controls: the four cheap ways out are each seen, and a real one passes
		check(asks("c", "x = ctrmap.Ui.askToKeep(dialog, \"Prop data\");").get(0).args.size() == 2,
				"control: a question with no discard is seen as one");
		check(!putsBack(asks("c", "ctrmap.Ui.askToKeep(dialog, \"Prop data\", () -> {});").get(0).discard),
				"control: an empty discard does not put back");
		check(!putsBack(asks("c", "ctrmap.Ui.askToKeep(dialog, \"Prop data\", () -> f.modified = false);").get(0).discard),
				"control: a discard that clears a flag does not put back");
		check(!putsBack(asks("c", "ctrmap.Ui.askToKeep(dialog, \"P\", this::d); private void d() {\n\t\tprops.modified = false;\n\t}").get(0).discard),
				"control: a method that clears a flag does not put back");
		check(putsBack(asks("c", "ctrmap.Ui.askToKeep(dialog, \"a, (b)\", () -> {\n\tzone.discardHeader();\n});").get(0).discard),
				"control: a discard that reads something again puts back, a comma inside a string left alone");
		check(putsBack(asks("c", "ctrmap.Ui.askToKeep(dialog, \"P\", this::d); private void d() {\n\t\tloadFrom(f.area());\n\t}").get(0).discard),
				"control: a method that reads something again puts back");
		check(asks("c", "public static Keep askToKeep(boolean dialog, String changeSubject, Runnable discard) {").isEmpty(),
				"control: the declaration is not a question");

		List<Ask> all = new ArrayList<>();
		int files = 0;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			files++;
			all.addAll(asks(f.getName(), SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))));
		}
		List<String> wrong = new ArrayList<>();
		for (Ask a : all) {
			if (a.args.size() != 3) {
				wrong.add(a.where + " asks with " + a.args.size() + " argument(s)");
			} else if (a.discard == null) {
				wrong.add(a.where + " hands a discard this cannot read: " + a.args.get(2));
			} else if (!putsBack(a.discard)) {
				wrong.add(a.where + " hands a discard that puts nothing back: " + a.discard.replaceAll("\\s+", " "));
			}
		}
		//THIRTEEN is a floor, for a scan gone blind; more questions are welcome and held to the same
		check(files > 250 && all.size() >= 13, "control: the walk reads " + files + " files and finds " + all.size()
				+ " questions, at least the thirteen F106 counted");
		check(wrong.isEmpty(), "every one says what No does, and it puts something back: " + wrong);
	}

	/**
	 * Files that may ask a three-way question of their own, and why it is not "keep the changes?".
	 * GeoEditForm and TextEditor each asked one that was, outside the seam.
	 */
	static final Map<String, String> THREE_WAY = new LinkedHashMap<>();

	static {
		THREE_WAY.put("Workspace.java", "pack, clean or leave the workspace: a choice of action, not whether to keep an edit");
	}

	static void noKeepQuestionIsAskedAroundTheSeam() throws Exception {
		System.out.println("--- no keep question is asked around the seam (F106)");
		Pattern three = Pattern.compile("\\bYES_NO_CANCEL_OPTION\\b");
		check(three.matcher("ctrmap.Ui.confirm(this, \"Save?\", \"t\", JOptionPane.YES_NO_CANCEL_OPTION);").find(),
				"control: a three-way question is seen");
		List<String> found = new ArrayList<>();
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("Ui.java")) {
				continue;
			}
			String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			if (three.matcher(src).find()) {
				found.add(f.getName());
			}
		}
		List<String> unexplained = new ArrayList<>(found);
		unexplained.removeAll(THREE_WAY.keySet());
		check(unexplained.isEmpty(), "every three-way question outside the seam is one that is not about keeping an edit: "
				+ unexplained + " ask one of their own");
		//3 until F152 took the shop and icon editors' merge questions away: code.ips is merged, never asked about
		check(THREE_WAY.size() <= 1 && found.containsAll(THREE_WAY.keySet()),
				"and the list of them is no longer than the one it holds, still there: " + found);
	}

	// ------------------------------------------------------------ the formats

	/** A 40x40 tilemap whose tile (x, y) is {x, y, 0, 1}. */
	static byte[] tilemap() {
		byte[] b = new byte[4 + 40 * 40 * 4];
		MapResizeTest.putU16(b, 0, 40);
		MapResizeTest.putU16(b, 2, 40);
		for (int y = 0; y < 40; y++) {
			for (int x = 0; x < 40; x++) {
				int at = 4 + (y * 40 + x) * 4;
				b[at] = (byte) x;
				b[at + 1] = (byte) y;
				b[at + 3] = 1;
			}
		}
		return b;
	}

	static void aTilemapGoesBack() throws Exception {
		System.out.println("--- a region's tiles go back to what it holds (F106)");
		FakeGameFiles fake = new FakeGameFiles().plant(ArchiveType.FIELD_DATA, 0,
				MapResizeTest.container(0x4752, tilemap(), new byte[4]));
		File file = fake.staged(ArchiveType.FIELD_DATA, 0);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		Tilemap tm = new Tilemap(new GR(file, fake));
		tm.setTileData(3, 4, new byte[]{9, 9, 9, 9});
		check(tm.modified && tm.getTileData(3, 4)[0] == 9, "control: a painted tile is held and marked");
		tm.discard();
		check(Arrays.equals(tm.getTileData(3, 4), new byte[]{3, 4, 0, 1}) && !tm.modified,
				"discarded, the tile is what the region holds and nothing is marked: " + Arrays.toString(tm.getTileData(3, 4)));
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");
	}

	/** A width x 1 matrix naming these regions, with no camera bounds. */
	static byte[] matrix(int... regions) {
		byte[] grid = new byte[8 + regions.length * 2];
		MapResizeTest.putU16(grid, 4, regions.length);
		MapResizeTest.putU16(grid, 6, 1);
		for (int k = 0; k < regions.length; k++) {
			MapResizeTest.putU16(grid, 8 + k * 2, regions[k]);
		}
		return MapResizeTest.container(0x4D4D, grid, new byte[4]);
	}

	static void aMatrixGoesBackInPlace() throws Exception {
		System.out.println("--- a matrix goes back to what its file holds, in place, keeping the regions it has open (F106)");
		FakeGameFiles fake = new FakeGameFiles()
				.plant(ArchiveType.FIELD_DATA, 3, MapResizeTest.region("three"))
				.plant(ArchiveType.FIELD_DATA, 5, MapResizeTest.region("five"))
				.plant(ArchiveType.MAP_MATRIX, 0, matrix(3, 5));
		File file = fake.staged(ArchiveType.MAP_MATRIX, 0);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		MapMatrix mm = new MapMatrix(new MM(file, fake), fake);
		GR three = mm.regions.get(0, 0);
		GR five = mm.regions.get(1, 0);
		check(three != null && five != null, "control: both regions are open");
		mm.ids.set(0, 0, (short) 5);
		mm.width = 3;
		mm.ids.addColumn();
		mm.regions.addColumn();
		mm.LOD.addColumn();
		mm.zones.addColumns(4);
		mm.discard();
		check(mm.width == 2 && mm.ids.getWidth() == 2 && mm.ids.get(0, 0) == 3 && mm.ids.get(1, 0) == 5,
				"discarded, the grid is the file's: " + mm.width + " wide, cells " + mm.ids.get(0, 0) + ", " + mm.ids.get(1, 0));
		check(mm.regions.get(0, 0) == three && mm.regions.get(1, 0) == five && mm.regions.getWidth() == 2,
				"and the regions it had open are the same objects, the ones the map view drew");
		check(Arrays.equals(mm.assembleData(), new MM(file, fake).getFile(0)), "and what it would write is what the file holds");

		mm.width = 1;
		mm.ids.removeColumn();
		mm.regions.removeColumn();
		mm.discard();
		GR again = mm.regions.get(1, 0);
		check(again != null && new String(again.getFile(0), StandardCharsets.US_ASCII).equals("five"),
				"a region the edit had dropped is opened again from its game");
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");
	}

	/** A region container whose props subfile holds {@code count} props. */
	static byte[] regionWithProps(int count) throws Exception {
		GRPropData data = new GRPropData();
		for (int i = 0; i < count; i++) {
			GRProp p = new GRProp();
			p.uid = i;
			data.props.add(p);
		}
		return MapResizeTest.container(0x4752, tilemap(), new byte[4], new byte[4], data.assemblePropData());
	}

	static void aMatrixsPropsAreReadInOnePlace() throws Exception {
		System.out.println("--- a matrix's props are read in one place, every region's, for the load and the discard (F106)");
		FakeGameFiles fake = new FakeGameFiles()
				.plant(ArchiveType.FIELD_DATA, 3, regionWithProps(1))
				.plant(ArchiveType.FIELD_DATA, 5, regionWithProps(2))
				.plant(ArchiveType.MAP_MATRIX, 0, matrix(3, 5));
		MapMatrix mm = new MapMatrix(new MM(fake.staged(ArchiveType.MAP_MATRIX, 0), fake), fake);
		GRPropData all = GRPropData.combined(mm);
		check(all.props.size() == 3 && all.unreadable == null && !all.modified,
				"both regions' props, three in all: " + all.props.size());
	}

	static void aZoneHeaderAndItsEntitiesGoBack() throws Exception {
		System.out.println("--- a zone's header and entities go back to what the file holds (F106)");
		FakeGameFiles fake = new FakeGameFiles().plant(ArchiveType.ZONE_DATA, 3, HandedGameTest.zoneBytes());
		File file = fake.staged(ArchiveType.ZONE_DATA, 3);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		Zone z = new Zone(new ZO(file, fake), GameType.ORAS);
		AD kept = CameraFormTest.area(fake, new CameraData());
		z.header.areadata = kept;
		z.header.weather = 3;
		z.header.X = 77;
		check(z.changed().contains(Zone.Part.HEADER), "control: an edited header is changed");
		check(z.discardHeader() && z.header.weather == 0 && z.header.X == 0 && z.changed().isEmpty(),
				"discarded, the header holds the file's fields: weather " + z.header.weather + ", X " + z.header.X);
		check(z.header.areadata == kept, "and keeps the area it had opened");
		check(!z.discardHeader(), "and after it there is nothing to put back");

		ZoneEntities before = z.entities;
		z.entities.warps.add(new ZoneEntities.Warp());
		z.entities.modified = true;
		check(z.discardEntities() && z.entities != before && z.entities.warps.isEmpty() && !z.entities.modified,
				"discarded, the entities are read again from the file: " + z.entities.warps.size() + " warp(s)");
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");
	}

	static void theNpcRegistryGoesBack() throws Exception {
		System.out.println("--- an NPC registry goes back to what the file holds, with the models it had (F106)");
		FakeGameFiles fake = HandedGameTest.npcGame("hero", "rival").plant(ArchiveType.NPC_REGISTRIES, 3,
				HandedGameTest.registryBytes(HandedGameTest.npcEntry(7, 1)));
		File file = fake.staged(ArchiveType.NPC_REGISTRIES, 3);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		NPCRegistry reg = new NPCRegistry(file, fake);
		H3DModel drawn = (H3DModel) RenderLoopTest.bare(H3DModel.class);
		int added = reg.registerModel(0);
		reg.models.put(added, drawn);
		NPCRegistry.NPCRegistryEntry edited = reg.entries.get(7).copy();
		edited.model = 0;
		reg.replace(7, edited);
		check(reg.modified && reg.entries.size() == 2, "control: an added and an edited entry, marked");
		check(reg.discard() && !reg.modified && reg.entries.keySet().equals(new HashSet<>(Arrays.asList(7)))
				&& reg.entries.get(7).model == 1, "discarded, the entries are the file's: " + reg.entries.keySet());
		check(!reg.models.containsKey(added), "and the added entry's model is not drawn");
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");

		added = reg.registerModel(0);
		reg.models.put(added, drawn);
		reg.store();
		reg.remove(added);
		reg.models.remove(added);
		reg.modified = true;
		check(reg.discard() && reg.entries.containsKey(added) && reg.models.get(added) == drawn,
				"after a store, a discard goes back to what was stored, with its model");
	}

	static void thePropRegistryGoesBack() throws Exception {
		System.out.println("--- a prop registry goes back to what the area holds, with the models it had (F106)");
		FakeGameFiles fake = HandedGameTest.propGame(0, "pc01", "tree01", "sign01");
		File file = fake.staged(ArchiveType.AREA_DATA, 0);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		ADPropRegistry reg = new ADPropRegistry(new AD(file, fake), null, fake);
		H3DModel drawn = (H3DModel) RenderLoopTest.bare(H3DModel.class);
		int added = reg.register(new ADPropRegistry.ADPropRegistryEntry(), 9);
		reg.models.put(added, drawn);
		ADPropRegistry.ADPropRegistryEntry edited = reg.entries.get(5).copy();
		edited.model = 2;
		reg.replace(5, edited);
		check(reg.modified && reg.entries.size() == 2, "control: an added and an edited entry, marked");
		check(reg.discard() && !reg.modified && reg.entries.keySet().equals(new HashSet<>(Arrays.asList(5)))
				&& reg.entries.get(5).model == 1, "discarded, the entries are the area's: " + reg.entries.keySet());
		check(!reg.models.containsKey(added), "and the added entry's model is not drawn");
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");

		added = reg.register(new ADPropRegistry.ADPropRegistryEntry(), 9);
		reg.models.put(added, drawn);
		reg.modified = true;
		reg.write();
		reg.entries.remove(added);
		reg.models.remove(added);
		reg.modified = true;
		check(reg.discard() && reg.entries.containsKey(added) && reg.models.get(added) == drawn,
				"after a write, a discard goes back to what was written, with its model");
	}

	// ------------------------------------------------------------ the forms

	static void theCameraFormGoesBack() throws Exception {
		System.out.println("--- the camera form's No puts the cameras back, on the camera shown (F106)");
		MapResizeTest.game("discardcam");
		List<String> said = ctrmap.Ui.record();
		CameraEditForm form = new CameraEditForm(DataSafetyGuardsTest.REDRAW, UndoHistoryTest.overOneNumber(new int[]{0}));
		JComboBox<?> entries = (JComboBox<?>) MatrixEditFormGuardsTest.field(form, "entryBox");
		CameraData second = CameraFormTest.oddCamera();
		byte[] secondBytes = second.bytes();
		AD area = CameraFormTest.area(new CameraData(), second);
		byte[] table = area.getFile(3);
		form.loadFrom(area);
		entries.setSelectedIndex(1);
		JFormattedTextField pitch = (JFormattedTextField) MatrixEditFormGuardsTest.field(form, "pitch1");
		Object shownPitch = pitch.getValue();
		pitch.setValue(44.5f);
		ctrmap.Ui.stopRecording();
		said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		boolean ok;
		try {
			ok = form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.size() == 1 && said.get(0).contains("Camera data has been modified"), "control: No was asked and answered: " + said);
		check(form.f != null && !form.f.modified && Arrays.equals(form.f.camData.get(1).bytes(), secondBytes),
				"the camera holds what the area holds again, not the moved pitch");
		check(form.camIndex == 1 && String.valueOf(shownPitch).equals(String.valueOf(pitch.getValue())),
				"and the form shows it, on the camera that was shown: camera " + form.camIndex + ", pitch " + pitch.getValue());
		check(Arrays.equals(table, area.getFile(3)), "and nothing was written");
	}

	static void theMatrixFormGoesBack() throws Exception {
		System.out.println("--- the matrix form's No puts the matrix it shares back (F106)");
		//regions 0 and 1: the open game here (the camera section's) has two, and the form checks a cell against it
		FakeGameFiles fake = new FakeGameFiles().plant(ArchiveType.MAP_MATRIX, 0, matrix(0, 1));
		File file = fake.staged(ArchiveType.MAP_MATRIX, 0);
		byte[] onDisk = Files.readAllBytes(file.toPath());
		MapMatrix mm = new MapMatrix(new MM(file, fake), null);
		MatrixEditForm form = new MatrixEditForm(new LoadedZone(), MatrixEditFormGuardsTest.CANVAS, UndoHistoryTest.overOneNumber(new int[]{0}));
		form.loadMatrix(mm);
		mm.ids.set(1, 0, (short) 9);
		List<String> said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		boolean ok;
		try {
			ok = form.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.size() == 1 && said.get(0).contains("Map matrix has been modified"), "control: No was asked and answered: " + said);
		check(form.mm == mm && mm.ids.get(1, 0) == 1, "the matrix it shares with the map view is the file's again: cell " + mm.ids.get(1, 0));
		check(Arrays.equals(onDisk, Files.readAllBytes(file.toPath())) && fake.edited().isEmpty(), "and nothing was written");
	}

	static void theTextEditorGoesBack() throws Exception {
		System.out.println("--- the text editor's No reads the file again (F106)");
		File root = Scratch.dir("ctrmap_discardtext");
		File archives = new File(root, "archives");
		archives.mkdirs();
		File gt = new File(archives, "gametext");
		HandedGameTest.writeGarc(gt, GFMessageFile.write(Arrays.asList("hello")));
		new File(root, "workspace").mkdirs();
		WorkspaceSession ws = Sessions.bare(new File(root, "workspace"), new File(root, "game"), GameType.ORAS)
				.withArchive(ArchiveType.GAMETEXT, new GARC(gt));
		ws.prepareDirectories();
		Workspace.install(ws);
		TextEditor te = new TextEditor();
		DefaultTableModel model = (DefaultTableModel) ((JTable) MatrixEditFormGuardsTest.field(te, "table")).getModel();
		te.loadFile(0);
		File f = Workspace.getWorkspaceFile(ArchiveType.GAMETEXT, 0);
		byte[] onDisk = Files.readAllBytes(f.toPath());

		model.setValueAt("goodbye", 0, 1);
		List<String> said = ctrmap.Ui.record();
		boolean ok;
		try {
			ok = te.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(!ok && "goodbye".equals(model.getValueAt(0, 1)), "control: a closed question keeps the typed line and stops the save");

		said = ctrmap.Ui.record(JOptionPane.NO_OPTION);
		try {
			ok = te.store(true);
		} finally {
			ctrmap.Ui.stopRecording();
		}
		check(ok && said.size() == 1 && said.get(0).contains("Text file 0 has been modified"), "No was asked through the one question: " + said);
		check("hello".equals(model.getValueAt(0, 1)) && !((Boolean) MatrixEditFormGuardsTest.field(te, "modified")),
				"and the table shows the file again, unmarked: " + model.getValueAt(0, 1));
		check(Arrays.equals(onDisk, Files.readAllBytes(f.toPath())), "and nothing was written");
	}

	static String source(String path) throws Exception {
		return SourceSeamTest.stripComments(new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8));
	}

	/**
	 * The editors whose store() cannot run here - the map view, the prop form and the geometry form
	 * need GL, the zone tab a loaded game - read in their source, as GeoSaveTest reads the geometry
	 * form's save; the restores they call are each driven above.
	 */
	static void theEditorsThatNeedAScreenPutBackWhatTheyHold() throws Exception {
		System.out.println("--- the editors that need a screen put back what they hold (F106)");
		String tiles = CollisionReadTest.body(source("src/ctrmap/humaninterface/TileMapPanel.java"), "private void discardTiles(");
		check(tiles != null && tiles.contains("tm.discard()") && tiles.contains("mapReplaced.run()") && tiles.contains("perfScale("),
				"the map view reads each changed region's tiles again, redraws them and lets their undo go");

		String prop = source("src/ctrmap/humaninterface/PropEditForm.java");
		String props = CollisionReadTest.body(prop, "private void discardProps(");
		String propReg = CollisionReadTest.body(prop, "private void discardRegistry(");
		check(props != null && props.contains("GRPropData.combined(map.mm)") && props.contains("new GRPropData(gr)") && props.contains("reshow("),
				"the prop form reads its props again from the matrix or the region, and shows them");
		check(propReg != null && propReg.contains("reg.discard()") && propReg.contains("reshow(props)"),
				"and its registry again, drawing the props with it");
		check(propReg != null && propReg.contains("before.contains(p.uid)") && propReg.contains("ctrmap.Ui.message("),
				"saying which kept props name an entry that went with it");

		String npc = CollisionReadTest.body(source("src/ctrmap/humaninterface/NPCEditForm.java"), "private void discardRegistry(");
		check(npc != null && npc.contains("reg.discard()"), "the NPC form puts its registry back");
		check(npc != null && npc.contains("before.contains(n.model)") && npc.contains("ctrmap.Ui.message("),
				"saying which NPCs name an entry that went with it");

		String zlp = source("src/ctrmap/humaninterface/ZoneLoadingPanel.java");
		String store = CollisionReadTest.body(zlp, "private boolean storeZone(");
		check(store != null && store.matches("(?s).*zone\\.discardHeader\\(\\);\\s*showHeader\\(zone\\.header\\);.*"),
				"the zone tab reads the header again and fills its boxes from it");
		check(store != null && store.matches("(?s).*zone\\.discardEntities\\(\\);\\s*zoneEditors\\.showEntities\\(zone\\);.*"),
				"and the entities, showing them in the editors that show entities");
		String load = CollisionReadTest.body(zlp, "public void loadZone(");
		check(load != null && load.contains("showHeader(z.header)") && !load.contains("cam1.setValue("),
				"which fill their boxes the one way opening a zone does");

		String geo = source("src/ctrmap/humaninterface/GeoEditForm.java");
		String select = CollisionReadTest.body(geo, "public void setSelection(");
		String record = CollisionReadTest.body(geo, "private void recordTile(");
		String discard = CollisionReadTest.body(geo, "private void discard(");
		check(select != null && select.indexOf("discard();") >= 0 && select.indexOf("discard();") < select.indexOf("gr = target;"),
				"the geometry form puts the region it leaves back before it picks another");
		check(record != null && record.contains("tilesAsSaved.putIfAbsent("),
				"keeps each tile it changes as it was before its first change");
		check(discard != null && discard.contains("tm.setTileData(") && discard.contains("reloadRegionModel(") && discard.contains("readRegion()"),
				"and on a discard puts those tiles, its model and the map view's model back");

		String mf = source("src/ctrmap/CtrmapMainframe.java");
		String views = CollisionReadTest.body(mf, "private ZoneEditors buildZoneEditors(");
		List<String> missed = new ArrayList<>();
		int shown = 0;
		for (String view : views == null ? new String[0] : views.split("new ZoneEditors\\.ZoneView\\(\\)")) {
			String show = CollisionReadTest.body(view, "public void show(");
			String again = CollisionReadTest.body(view, "public void showEntities(");
			if (show == null) {
				continue;
			}
			shown++;
			Matcher m = Pattern.compile("m\\w+EditForm\\.loadFromEntities\\([^;]*;").matcher(show);
			while (m.find()) {
				if (again == null || !again.contains(m.group())) {
					missed.add(m.group());
				}
			}
		}
		check(shown >= 9 && missed.isEmpty(), "every one of the " + shown + " zone views that shows entities shows them again: missed " + missed);

		List<String> log = new ArrayList<>();
		new ZoneEditors(Arrays.asList(OpenEditorsTest.view(log, "a"), OpenEditorsTest.view(log, "b"))).showEntities(null);
		check(log.equals(Arrays.asList("entities:a", "entities:b")), "and the list asks every view, in order: " + log);
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
