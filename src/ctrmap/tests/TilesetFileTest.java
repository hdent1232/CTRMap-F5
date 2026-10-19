package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.tilemap.EditorTileset;
import ctrmap.resources.ResourceAccess;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JFormattedTextField;
import javax.swing.JTextField;

/**
 * A tileset reads whole or not at all, writes what it read, and the tileset editor says when it
 * cannot do either (F026, F254, census M.9).
 *
 * <p>WHY THIS SUITE EXISTS. The format had two readers and two writers. The editor's wrote a
 * name's length in characters before its UTF-8 bytes, so one non-ASCII name made a file neither
 * reader could follow; a tile that failed to write was logged after the count was already out;
 * opening a file that did not read cleared the open set first; Previous threw on a binary that
 * was not hex. The map view's reader logged a file it could not read and handed back no tiles, or
 * a half-read set with nulls in it, and never closed the file.
 *
 * <p>ORDER: needs no dump and no display; the editor is the real one.
 *
 * Usage: java ctrmap.tests.TilesetFileTest
 */
public class TilesetFileTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		boolean wasDefault = Workspace.TILESET_DEFAULT;
		String wasPath = Workspace.TILESET_PATH;
		try {
			theFileReadsWholeOrNotAtAll();
			theReaderClosesWhatItReads();
			aWorkspaceTilesetThatDoesNotReadIsSaid();
			theEditorWritesWhatItShowsAndSaysWhenItCannot();
			theEditorsEditsAndSavesAreTakenBack();
			theOpenButtonOpensOnlyWhatWasChosen();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.TILESET_DEFAULT = wasDefault;
			Workspace.TILESET_PATH = wasPath;
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static List<EditorTileset.Record> two() {
		return new ArrayList<>(Arrays.asList(new EditorTileset.Record(0x12, "Gräs", 0xFF00FF00, 0, 1),
				new EditorTileset.Record(0xFFFFFFFF, "Wall", 0x123456, 2, 3)));
	}

	static String refusal(byte[] file) {
		try {
			EditorTileset.read(file);
			return null;
		} catch (IOException refused) {
			return refused.getMessage();
		}
	}

	static void theFileReadsWholeOrNotAtAll() throws Exception {
		System.out.println("--- a tileset reads whole or is refused, and writes back what it read (F026, M.9)");
		byte[] bundled = ResourceAccess.getByteArray("DefaultTileset.mets");
		List<EditorTileset.Record> tiles = EditorTileset.read(bundled);
		check(tiles.size() == 77 && Arrays.equals(EditorTileset.write(tiles), bundled),
				"the bundled tileset reads as its 77 tiles and writes back byte for byte (" + tiles.size() + ")");

		byte[] file = EditorTileset.write(two());
		String unread = refusal(file);
		check(unread == null && file[8] == 5, "a non-ASCII name is stored as its 5 UTF-8 bytes and reads back: length byte "
				+ file[8] + (unread == null ? "" : ", refused: " + unread));
		if (unread != null) {
			return;   //said above; everything below reads this file
		}
		List<EditorTileset.Record> back = EditorTileset.read(file);
		check(back.size() == 2 && back.get(0).name.equals("Gräs"), "and its name reads back: " + back.get(0).name);
		check(back.get(1).binary == 0xFFFFFFFF && back.get(1).cat2 == 3 && back.get(0).color == 0xFF00FF00,
				"every field reads back as written");

		List<String> missed = new ArrayList<>();
		for (int cut = 0; cut < file.length; cut++) {
			if (refusal(Arrays.copyOf(file, cut)) == null) {
				missed.add(String.valueOf(cut));
			}
		}
		check(refusal(file) == null && missed.isEmpty(), "control: the whole file reads, and every one of its "
				+ file.length + " shorter cuts is refused: read anyway at " + missed);
		byte[] longer = Arrays.copyOf(file, file.length + 1);
		check(refusal(longer) != null && refusal(longer).contains("follow the last"), "a byte after the last tile is refused: " + refusal(longer));
		byte[] magic = file.clone();
		magic[0] = 'X';
		check(refusal(magic) != null && refusal(magic).contains("METS"), "so is another magic: " + refusal(magic));
		byte[] count = file.clone();
		count[4] = 0x7F;
		check(refusal(count) != null && refusal(count).contains("cannot be right"), "and a count the bytes cannot hold: " + refusal(count));

		//WHAT THE OLD EDITOR WROTE: the length in characters, then the bytes
		byte[] old = file.clone();
		old[8] = 4;
		check(refusal(old) != null, "a file the old editor wrote with a non-ASCII name is refused, not misread: " + refusal(old));

		char[] max = new char[255];
		Arrays.fill(max, 'a');
		List<EditorTileset.Record> longest = two();
		longest.get(0).name = new String(max);
		check(EditorTileset.read(EditorTileset.write(longest)).get(0).name.length() == 255, "a 255-byte name is written and read");
		longest.get(0).name = new String(max) + "ä";
		String said = null;
		try {
			EditorTileset.write(longest);
		} catch (IOException refused) {
			said = refused.getMessage();
		}
		check(said != null && said.contains("257 bytes"), "and a 257-byte one is refused before anything is produced: " + said);
	}

	/** A stream that remembers being closed. */
	static final class Watched extends ByteArrayInputStream {

		boolean closed;

		Watched(byte[] b) {
			super(b);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	static void theReaderClosesWhatItReads() throws Exception {
		System.out.println("--- the reader closes what it reads and refuses what it cannot (F254, M.9)");
		Watched good = new Watched(EditorTileset.write(two()));
		EditorTileset ts = new EditorTileset(good);
		check(ts.tiles.length == 2 && good.closed, "a tileset read from a stream closes it");
		Watched bad = new Watched(new byte[]{1, 2, 3});
		Throwable thrown = null;
		try {
			new EditorTileset(bad);
		} catch (IOException refused) {
			thrown = refused;
		}
		check(thrown != null && bad.closed, "and one it refuses is closed too: " + thrown);
		thrown = null;
		try {
			new EditorTileset(new File(Scratch.dir("ctrmap_tileset"), "missing.mets"));
		} catch (IOException refused) {
			thrown = refused;
		}
		check(thrown != null, "a file that is not there is refused rather than read as no tiles: " + thrown);
		thrown = null;
		try {
			new EditorTileset((InputStream) null);
		} catch (IOException refused) {
			thrown = refused;
		}
		check(thrown != null, "and so is a missing stream: " + thrown);
	}

	static void aWorkspaceTilesetThatDoesNotReadIsSaid() throws Exception {
		System.out.println("--- a workspace tileset that does not read is said, and the default is used (M.9)");
		File corrupt = new File(Scratch.dir("ctrmap_tileset"), "corrupt.mets");
		Files.write(corrupt.toPath(), Arrays.copyOf(EditorTileset.write(two()), 30));   //inside the second tile
		Workspace.TILESET_DEFAULT = false;
		Workspace.TILESET_PATH = corrupt.getPath();
		List<String> said = ctrmap.Ui.record();
		EditorTileset ts = Workspace.getTileset();
		check(ts.tiles.length == 77 && Workspace.TILESET_DEFAULT && said.size() == 1
				&& said.get(0).contains("could not be read") && said.get(0).contains("ends inside tile"),
				"a half-written tileset is said, with why, and the 77-tile default is used: " + said);
		ctrmap.Ui.stopRecording();
	}

	/** A tile edited and a save made in the tileset editor are each taken back by its Undo and put again (F209c). */
	static void theEditorsEditsAndSavesAreTakenBack() throws Exception {
		System.out.println("--- the tileset editor's edits and saves are taken back and put again (F209c)");
		File dir = Scratch.dir("ctrmap_tileset_undo");
		File in = new File(dir, "in.mets");
		Files.write(in.toPath(), EditorTileset.write(two()));
		ctrmap.TileDBWriter form = new ctrmap.TileDBWriter();
		form.withUndo();
		java.lang.reflect.Method open = ctrmap.TileDBWriter.class.getDeclaredMethod("open", File.class);
		java.lang.reflect.Method save = ctrmap.TileDBWriter.class.getDeclaredMethod("save", File.class);
		java.lang.reflect.Method commit = ctrmap.TileDBWriter.class.getDeclaredMethod("commitCurrent");
		open.setAccessible(true);
		save.setAccessible(true);
		commit.setAccessible(true);
		JTextField name = (JTextField) MatrixEditFormGuardsTest.field(form, "name");
		open.invoke(form, in);
		ctrmap.humaninterface.UndoHistory history = form.history();
		history.settle();   //what the file holds, before any gesture
		name.setText("Grass");
		commit.invoke(form);
		history.settle();   //the gesture's end
		check(history.canUndo(), "control: renaming a tile is an edit the history holds");
		history.undo();
		check(firstName(form).equals("Gräs") && name.getText().equals("Gräs"),
				"Undo puts the tile's name back, and shows it: " + firstName(form) + ", " + name.getText());
		history.redo();
		check(firstName(form).equals("Grass") && name.getText().equals("Grass"), "and Redo renames it again: " + firstName(form));

		File out = new File(dir, "out.mets");
		check((Boolean) save.invoke(form, out) && out.isFile(), "control: it saves");
		history.undo();
		check(!out.exists() && firstName(form).equals("Grass"),
				"Undo takes the save back - a file that was not there is gone - and leaves the tiles as they are");
		history.redo();
		check(out.isFile() && EditorTileset.read(Files.readAllBytes(out.toPath())).get(0).name.equals("Grass"),
				"and Redo writes it again");
		open.invoke(form, in);
		history.settle();
		check(!history.canUndo(), "another tileset opened starts the history again: nothing of the last one is taken back");
	}

	/**
	 * The tileset editor's Open opens the file the chooser answered, and nothing on a cancel.
	 *
	 * <p>Measured by the 2026-10-12 sweep (TileDBWriter 383, negated, SURVIVED): nothing pressed Open. Inverted, a
	 * file chosen was never opened - the button did nothing the owner could see - and a cancel went on to open
	 * whatever the chooser still held.
	 */
	static void theOpenButtonOpensOnlyWhatWasChosen() throws Exception {
		System.out.println("--- the tileset editor's Open opens the file chosen, and nothing on a cancel");
		File in = new File(Scratch.dir("ctrmap_tileset_open"), "chosen.mets");
		Files.write(in.toPath(), EditorTileset.write(two()));
		ctrmap.TileDBWriter form = new ctrmap.TileDBWriter();
		JTextField name = (JTextField) MatrixEditFormGuardsTest.field(form, "name");
		String before = name.getText();
		List<String> said = ctrmap.Ui.record();   //nobody answers the chooser: a cancel
		MatrixEditFormGuardsTest.invoke(form, "openfileActionPerformed");
		check(said.size() == 1 && said.get(0).startsWith("Open file: ") && name.getText().equals(before)
				&& MatrixEditFormGuardsTest.field(form, "f") == null,
				"a cancelled Open asks once and opens nothing: " + said + ", \"" + name.getText() + "\"");
		said = ctrmap.Ui.record(in);
		MatrixEditFormGuardsTest.invoke(form, "openfileActionPerformed");
		check(said.size() == 1 && name.getText().equals("Gräs") && in.equals(MatrixEditFormGuardsTest.field(form, "f")),
				"and Open with a file chosen opens that file, on its first tile: \"" + name.getText() + "\" " + said);
		ctrmap.Ui.stopRecording();
	}

	/** The first tile's name, as the editor holds it. */
	static String firstName(ctrmap.TileDBWriter form) throws Exception {
		return ((EditorTileset.Record) ((List<?>) MatrixEditFormGuardsTest.field(form, "tiles")).get(0)).name;
	}

	static void theEditorWritesWhatItShowsAndSaysWhenItCannot() throws Exception {
		System.out.println("--- the tileset editor writes what it shows, and says when it cannot (F026)");
		File dir = Scratch.dir("ctrmap_tileset");
		File in = new File(dir, "in.mets");
		Files.write(in.toPath(), EditorTileset.write(two()));
		List<String> said = ctrmap.Ui.record();
		ctrmap.TileDBWriter form = new ctrmap.TileDBWriter();
		java.lang.reflect.Method open = ctrmap.TileDBWriter.class.getDeclaredMethod("open", File.class);
		java.lang.reflect.Method save = ctrmap.TileDBWriter.class.getDeclaredMethod("save", File.class);
		open.setAccessible(true);
		save.setAccessible(true);
		JTextField name = (JTextField) MatrixEditFormGuardsTest.field(form, "name");
		JFormattedTextField binary = (JFormattedTextField) MatrixEditFormGuardsTest.field(form, "binary");
		open.invoke(form, in);
		check(name.getText().equals("Gräs") && said.isEmpty(), "a tileset opens on its first tile: " + name.getText() + " " + said);

		name.setText("Grass");   //typed, never entered
		File out = new File(dir, "out.mets");
		check((Boolean) save.invoke(form, out), "it saves");
		List<EditorTileset.Record> back = EditorTileset.read(Files.readAllBytes(out.toPath()));
		check(back.size() == 2 && back.get(0).name.equals("Grass") && back.get(1).binary == 0xFFFFFFFF,
				"with the name typed and not entered, and the rest as read: " + back.get(0).name);

		byte[] before = Files.readAllBytes(out.toPath());
		File broken = new File(dir, "broken.mets");
		Files.write(broken.toPath(), new byte[]{'M', 'E', 'T', 'S', 0, 0, 0, 9});
		open.invoke(form, broken);
		int kept = ((List<?>) MatrixEditFormGuardsTest.field(form, "tiles")).size();
		check(said.size() == 1 && said.get(0).contains("could not be read") && name.getText().equals("Grass") && kept == 2,
				"a file that does not read is said and the open set is kept: " + kept + " tiles, " + said);
		save.invoke(form, out);
		check(Arrays.equals(before, Files.readAllBytes(out.toPath())), "and saving it again writes the same bytes");

		said.clear();
		binary.setText("XYZ");
		MatrixEditFormGuardsTest.invoke(form, "btnNextTileActionPerformed");
		check(said.size() == 1 && said.get(0).contains("not a hexadecimal") && name.getText().equals("Grass"),
				"Next with a binary that is not hex says so and stays on the tile: " + said);
		said.clear();
		MatrixEditFormGuardsTest.invoke(form, "btnPrevTileActionPerformed");
		check(said.size() == 1 && said.get(0).contains("not a hexadecimal"), "and so does Previous, which threw: " + said);
		binary.setText("FFFFFFFF");
		said.clear();
		MatrixEditFormGuardsTest.invoke(form, "btnNextTileActionPerformed");
		check(said.isEmpty() && name.getText().equals("Wall"), "a binary with its top bit set is taken: " + said);

		said.clear();
		check(!(Boolean) save.invoke(form, dir) && said.size() == 1 && said.get(0).contains("not saved"),
				"a save that cannot write says so: " + said);
		said.clear();
		char[] big = new char[300];
		Arrays.fill(big, 'b');
		name.setText(new String(big));
		check(!(Boolean) save.invoke(form, out) && said.size() == 1 && said.get(0).contains("at most 255")
				&& Arrays.equals(before, Files.readAllBytes(out.toPath())),
				"a name too long for the file is said and nothing is written: " + said);

		name.setText("Wall");
		@SuppressWarnings("unchecked")
		List<EditorTileset.Record> tiles = (List<EditorTileset.Record>) MatrixEditFormGuardsTest.field(form, "tiles");
		MatrixEditFormGuardsTest.invoke(form, "btnRemoveTileActionPerformed");
		check(tiles.size() == 1 && name.getText().equals("Grass"), "removing the last tile shows the one before it,"
				+ " not a new blank: " + tiles.size() + " left, showing " + name.getText());
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
