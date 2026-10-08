package ctrmap.tests;

import ctrmap.humaninterface.TileMapPanel;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The map view reads its per-region arrays through one bounds-checked accessor wherever a loop is
 * bounded by the matrix (F111).
 *
 * <p>WHY THIS SUITE EXISTS. The view's tilemap, model and tall-grass arrays are sized from the
 * matrix when it loads, and the matrix editor's Add row/column grows the SAME matrix in place, so
 * every loop bounded by mm.width/mm.height can outrun them. The bound was added call site by call
 * site and missed two: every zoom and every window resize threw once the matrix had grown.
 *
 * <p>The methods held to it are FOUND, not listed: every method of the view whose body reads
 * mm.width. A loop added tomorrow is held the day it exists.
 *
 * <p>ORDER: needs no dump and no display. The view itself needs a GL context no suite here can
 * make, so the accessor is called by reflection and the loops are read in its source.
 *
 * Usage: java ctrmap.tests.MapViewCellsTest
 */
public class MapViewCellsTest {

	static int fails = 0;
	static final Pattern DIRECT = Pattern.compile("\\b(tilemaps|models|tallgrass)\\[[A-Za-z_]\\w*\\]\\[[A-Za-z_]\\w*\\]");
	static final Pattern MEMBER = Pattern.compile("(?m)^\\t(?:public |private |protected |static |final |synchronized )*[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{");

	public static void main(String[] args) throws Exception {
		System.out.println("--- the accessor answers null past the array, never throws (F111)");
		String[][] grid = {{"a", "b"}, {"c"}};
		check("b".equals(cell(grid, 0, 1)) && "c".equals(cell(grid, 1, 0)), "control: cells inside the arrays are answered");
		Object past;
		try {
			past = cell(grid, 2, 0) == null && cell(grid, 1, 1) == null && cell(grid, -1, 0) == null && cell(grid, 0, -1) == null
					&& cell(null, 0, 0) == null;
		} catch (java.lang.reflect.InvocationTargetException threw) {
			past = threw.getCause();
		}
		check(Boolean.TRUE.equals(past), "past either edge, on a short row, negative or with no array at all: null ("
				+ past + ")");

		System.out.println("--- every loop bounded by the matrix reads the arrays through it (F111)");
		String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(
				new File("src/ctrmap/humaninterface/TileMapPanel.java").toPath()), StandardCharsets.UTF_8));
		Map<String, String> bounded = new LinkedHashMap<>();
		Matcher m = MEMBER.matcher(src);
		while (m.find()) {
			String body = CollisionReadTest.body(src.substring(m.start()), src.substring(m.start(), m.end() - 1));
			if (body != null && body.contains("mm.width")) {
				bounded.put(m.group(1), body);
			}
		}
		check(bounded.containsKey("readRegions") && bounded.containsKey("renderTileMap")
				&& bounded.containsKey("deleteGLInstanceBuffers") && bounded.size() >= 7,
				"control: the methods bounded by the matrix are found, the two that threw among them: " + bounded.keySet());
		List<String> direct = new ArrayList<>();
		for (Map.Entry<String, String> e : bounded.entrySet()) {
			if (e.getKey().equals("readRegions")) {
				continue;   //where the arrays are made from the matrix, so they fit it
			}
			Matcher d = DIRECT.matcher(e.getValue());
			while (d.find()) {
				direct.add(e.getKey() + ": " + d.group());
			}
		}
		check(direct.isEmpty(), "and none of them but the one that sizes the arrays indexes them directly: " + direct);
		String region = CollisionReadTest.body(src, "public Tilemap getRegionForTile(int x, int y)");
		String perf = CollisionReadTest.body(src, "public void perfScale(double scale, int changedRegionX, int changedRegionY)");
		check(region != null && region.contains("cell(") && !DIRECT.matcher(region).find()
				&& perf != null && perf.contains("cell(") && !perf.contains("tilemaps[changedRegionX]"),
				"nor do the two that index by a cell they are handed");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static Object cell(Object[][] grid, int x, int y) throws Exception {
		Method m = TileMapPanel.class.getDeclaredMethod("cell", Object[][].class, int.class, int.class);
		m.setAccessible(true);
		return m.invoke(null, grid, x, y);
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
