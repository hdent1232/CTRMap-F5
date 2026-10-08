package ctrmap.tests;

import ctrmap.formats.containers.MM;
import ctrmap.formats.mapmatrix.MapMatrix;
import java.io.File;
import java.nio.file.Files;

/**
 * A map matrix is read whole or refused, a new one carries the camera boundary it is built with,
 * and its layers refuse to lose rows or columns they do not have (F080).
 *
 * <p>WHY THIS SUITE EXISTS. The whole parse sat in a catch that logged: a short or damaged subfile
 * left the grid half filled - or null - and the camera bounds cut short, and write() then saved
 * that back over the map. A camera boundary that ran out read as zeros. The builder's blank matrix
 * built a camera boundary and dropped it, so a new map shipped with none. And the layers removed
 * rows and columns with no check, so the matrix form's Remove row past the last one threw out of
 * the window.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.MapMatrixTest
 */
public class MapMatrixTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dir = Scratch.dir("ctrmap_mapmatrix");
		FakeGameFiles game = new FakeGameFiles();

		System.out.println("--- a matrix is read whole or refused");
		MapMatrix whole = new MapMatrix(new MM(mm(dir, "whole", grid(0, 2, 1, 7, 8), bounds(1, 1)), game), null);
		check(whole.width == 2 && whole.height == 1 && whole.ids.get(1, 0) == 8 && whole.cambounds.size() == 1,
				"control: a whole 2x1 matrix with one camera boundary reads (" + whole.width + "x" + whole.height
				+ ", " + whole.cambounds.size() + " boundary)");
		refused("a grid cut short", mm(dir, "short", grid(0, 2, 2, 7, 8, 9), bounds(0, 0)), "could not be read");
		refused("a zone grid that is not there", mm(dir, "nolod", grid(1, 1, 1, 7), bounds(0, 0)), "could not be read");
		refused("camera bounds cut short", mm(dir, "cam", grid(0, 1, 1, 7), bounds(2, 1)), "could not be read");

		System.out.println("--- a new matrix carries the camera boundary it is built with");
		MapMatrix blank = new MapMatrix(new MM(new File(dir, "blank"), 2, MM.MM_MAP_MATRIX, game), 1, 1, 0);
		byte[] cam = blank.assembleCamData();
		check(blank.cambounds.size() == 1 && cam.length >= 4 && (cam[0] & 0xFF) == 1,
				"the builder's blank matrix has one camera boundary, and writes it ("
				+ blank.cambounds.size() + " kept, count " + (cam.length >= 4 ? cam[0] & 0xFF : -1) + " written)");

		System.out.println("--- a save carries the camera section's bytes it does not understand (F160)");
		byte[] section = java.util.Arrays.copyOf(bounds(1, 1), 324);
		for (int i = 24; i < section.length; i++) {
			section[i] = (byte) (i * 7 + 1);
		}
		MapMatrix tail = new MapMatrix(new MM(mm(dir, "tail", grid(0, 1, 1, 7), section), game), null);
		check(tail.cambounds.size() == 1 && section[300] != 0, "control: one boundary read, and 300 bytes after it"
				+ " that are not zero");
		check(java.util.Arrays.equals(tail.assembleCamData(), section), "an unchanged matrix writes its camera section"
				+ " back as it read it, all " + section.length + " bytes, not just the 24 its one boundary needs");
		tail.cambounds.add(new ctrmap.formats.mapmatrix.MatrixCameraBoundaries());
		byte[] grown = tail.assembleCamData();
		check(grown.length == section.length && grown[0] == 2
				&& java.util.Arrays.equals(java.util.Arrays.copyOfRange(grown, 44, grown.length),
				java.util.Arrays.copyOfRange(section, 44, section.length)),
				"a boundary added is written over the next 20 bytes, and every byte after it is kept");
		tail.cambounds.clear();
		byte[] none = tail.assembleCamData();
		check(none.length == section.length && none[0] == 0 && none[30] == section[30],
				"and with none left the count says 0 and the section keeps its length and its bytes");

		System.out.println("--- a layer refuses to lose rows or columns it does not have");
		MapMatrix.ResizeableMatrix<Short> layer = new MapMatrix.ResizeableMatrix<>(1, 1, (short) -1);
		layerRefuses("removing 2 rows of 1", () -> layer.removeRows(2));
		layerRefuses("removing 2 columns of 1", () -> layer.removeColumns(2));
		layer.removeRows(1);
		check(layer.getHeight() == 0 && layer.getWidth() == 1, "control: removing the 1 row it has empties it");

		System.out.println("--- a raw matrix's grid is read in one place (F118)");
		byte[] lod = MapResizeTest.container(0x4D4D, grid(1, 2, 1, 7, 8), bounds(0, 0));
		lod = java.util.Arrays.copyOf(lod, lod.length + 8 * 4 * 2);   //room for the 8x4 zone layer
		MapMatrix.Grid g = MapMatrix.Grid.of(lod);
		check(g.width == 2 && g.height == 1 && g.id(1) == 8 && g.id(1, 0) == 8 && g.hasZoneLayer
				&& g.zoneLayerOffset() == g.idOffset(2), "a 2x1 grid with a zone layer reads: ids, cells, where the layer starts");
		gridRefuses("a container too short", new byte[4], "too short");
		gridRefuses("a grid larger than its subfile", MapResizeTest.container(0x4D4D, grid(0, 9, 9, 1), bounds(0, 0)), "does not fit");
		int hand = 0, scanned = 0;
		//BY SHAPE, NOT BY NAME (F248): this looked for the variable sub0, and GfEnvPicker called it s0 - so
		//its hand-parsed grid passed. Two shapes: a grid header read (width at +4, then h/height = the +6
		//read of the same buffer and base) and a cell read (<offset> + 8 + <index> * 2)
		java.util.regex.Pattern parse = java.util.regex.Pattern.compile(
				"u16\\(\\s*(\\w+)\\s*,\\s*((?:\\w+\\s*\\+\\s*)?)4\\s*\\)[^;\\n]*(?:;[^\\n]*\\n[^\\n]*)?"
				+ "\\b(?:h|height)\\s*=\\s*u16\\(\\s*\\1\\s*,\\s*\\2\\s*6\\s*\\)"
				+ "|\\b\\w+\\s*\\+\\s*8\\s*\\+\\s*\\(?\\s*\\w+(?:\\s*\\*\\s*\\w+\\s*\\+\\s*\\w+\\s*\\)?)?\\s*\\)?\\s*\\*\\s*2\\b");
		for (String probe : new String[]{"u16(mat, sub0 + 8 + k * 2)", "u16(mm, s0 + 8 + k * 2)",
				"int w = u16(mm, s0 + 4), h = u16(mm, s0 + 6);", "int w = u16(grid, 4);\n\t\tint h = u16(grid, 6);"}) {
			check(parse.matcher(probe).find(), "control: the scan matches a hand-parsed grid whatever its names: " + probe);
		}
		check(!parse.matcher("mon.level = u16(e, o + 4); mon.form = u16(e, o + 6);").find()
				&& !parse.matcher("int w = u16(a, 4), h = u16(b, 6);").find(),
				"control: and not a record's +4/+6 fields, nor two buffers' words");
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new java.util.ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator) || f.getName().equals("MapMatrix.java")) {
				continue;
			}
			scanned++;
			java.util.regex.Matcher m = parse.matcher(new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
			while (m.find()) {
				hand++;
				System.out.println("    parses a matrix grid by hand: " + f);
			}
		}
		check(hand == 0 && scanned > 100, "no production file but MapMatrix parses a matrix grid by hand (" + hand + " in "
				+ scanned + " files)");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void gridRefuses(String what, byte[] mm, String why) {
		try {
			MapMatrix.Grid.of(mm);
			check(false, what + " is refused");
		} catch (IllegalArgumentException said) {
			check(said.getMessage().contains(why), what + " is refused: " + said.getMessage());
		}
	}

	/** Subfile 0: hasLOD, unknown, width, height, then the ids given (as many as are given). */
	static byte[] grid(int hasLod, int width, int height, int... ids) {
		byte[] b = new byte[8 + ids.length * 2];
		MapResizeTest.putU16(b, 0, hasLod);
		MapResizeTest.putU16(b, 4, width);
		MapResizeTest.putU16(b, 6, height);
		for (int i = 0; i < ids.length; i++) {
			MapResizeTest.putU16(b, 8 + i * 2, ids[i]);
		}
		return b;
	}

	/** Subfile 1: the count it declares, then as many 20-byte boundaries as are present. */
	static byte[] bounds(int declared, int present) {
		byte[] b = new byte[4 + present * 20];
		MapResizeTest.putI32(b, 0, declared);
		return b;
	}

	static File mm(File dir, String name, byte[] grid, byte[] bounds) throws Exception {
		File f = new File(dir, name);
		Files.write(f.toPath(), MapResizeTest.container(0x4D4D, grid, bounds));
		return f;
	}

	static void refused(String what, File f, String why) throws Exception {
		try {
			MapMatrix m = new MapMatrix(new MM(f, new FakeGameFiles()), null);
			check(false, what + " is refused (it read " + m.width + "x" + m.height + ", "
					+ m.cambounds.size() + " boundary)");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains(why) && said.getMessage().contains(f.getName()),
					what + " is refused, naming the matrix: " + said.getMessage());
		}
	}

	static void layerRefuses(String what, Runnable r) {
		try {
			r.run();
			check(false, what + " is refused (it went through)");
		} catch (IllegalArgumentException said) {
			check(true, what + " is refused: " + said.getMessage());
		} catch (RuntimeException crash) {
			check(false, what + " is refused in words (it threw " + crash + ")");
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
