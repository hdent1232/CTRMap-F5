package ctrmap.tests;

import ctrmap.formats.containers.GR;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.gfcollision.GfColl;
import ctrmap.humaninterface.CollEditPanel;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.tools.ToolSelection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A region's collision is read by one strict parser, keeps every triangle it holds, and a
 * collision that cannot be read is never opened or saved over (F058, F061).
 *
 * <p>WHY THIS SUITE EXISTS. The collision editor read GR subfile 2 with a parser of its own: a
 * bad magic, a length past the end or a missing "term" was a line on a console the shipped build
 * does not have, input that ran out left the buckets part-filled, and the region opened and saved
 * normally - writing whatever part had been read over its real collision. It also dropped every
 * repeated triangle, where the spec-exact reader keeps the ones the source mesh genuinely
 * repeats, and that reader identified triangles by a 64-bit hash, so two distinct triangles whose
 * hashes met were merged.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}. The corpus half -
 * every retail region's collision reads strictly, and its editor save is the retail-exact
 * rebuild - is GfCollTest and GfCollLegacyBridgeTest on a machine with the dump.
 *
 * Usage: java ctrmap.tests.CollisionReadTest
 */
public class CollisionReadTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dir = Scratch.dir("ctrmap_collision_read");
		FakeGameFiles game = new FakeGameFiles();

		System.out.println("--- a repeated triangle survives open and save (F061)");
		float[] floor = {-300f, 0f, -300f, 300f, 0f, -300f, -300f, 0f, 300f};
		float[] other = {-300f, 5f, 300f, 300f, 5f, -300f, 300f, 5f, 300f};
		byte[] repeated = GfColl.build(Arrays.asList(floor, floor, other), null);
		check(new GfColl(repeated).uniqueTris.size() == 3, "the parser keeps the repeat: 3 triangles, one of them twice");
		GR region = region(dir, "repeat", repeated, game);
		GRCollisionFile coll = GRCollisionFile.read(region);
		check(triangles(coll) == 3, "and the editor's model holds all 3 (it holds " + triangles(coll) + ")");
		coll.write();
		check(Arrays.equals(new GR(region.getOriginFile(), game).getFile(2), repeated),
				"and saved unedited it is the same bytes, the retail-exact rebuild of what was read");

		System.out.println("--- two triangles whose hashes meet stay two (F061)");
		float[] a = {10f, 1f, 20f, 30f, 1f, 40f, 50f, 1f, 60f};
		float[] b = a.clone();
		//one ulp up in the eighth coordinate and thirty-one down in the ninth: every
		//"31 * h + bits" hash of the nine is unchanged, and the triangle is not the same
		b[7] = Float.intBitsToFloat(Float.floatToIntBits(a[7]) + 1);
		b[8] = Float.intBitsToFloat(Float.floatToIntBits(a[8]) - 31);
		check(!Arrays.equals(a, b) && polyHash(a) == polyHash(b),
				"control: the two triangles differ and their 31-polynomial hashes are equal");
		GfColl both = new GfColl(GfColl.build(Arrays.asList(a, b), null));
		boolean bothKept = both.uniqueTris.size() == 2
				&& (Arrays.equals(both.uniqueTris.get(0), a) && Arrays.equals(both.uniqueTris.get(1), b)
				|| Arrays.equals(both.uniqueTris.get(0), b) && Arrays.equals(both.uniqueTris.get(1), a));
		//a count alone does not do: merged, the one key is seen twice in a bucket and read back twice
		check(bothKept, "both are kept, each as itself (" + both.uniqueTris.size() + " read back)");

		System.out.println("--- the editor shows each triangle once, in the bucket it was first seen in");
		GfColl spanning = new GfColl(GfColl.build(Arrays.asList(floor, other), null));
		int stored = 0;
		for (int k = 0; k < 16; k++) {
			stored += spanning.bucketCnt[k] / 3;
		}
		check(stored > 2, "control: the subfile stores the spanning triangles more than once (" + stored + " entries)");
		GRCollisionFile spanned = GRCollisionFile.read(region(dir, "span", GfColl.build(Arrays.asList(floor, other), null), game));
		check(triangles(spanned) == 2, "but the editor holds each once (" + triangles(spanned) + ")");
		check(spanned.meshes[spanning.uniqueTriBucket.get(0)].tris.size() >= 1
				&& spanning.uniqueTriBucket.size() == spanning.uniqueTris.size(),
				"in the bucket the parser saw it first: " + spanning.uniqueTriBucket);

		System.out.println("--- what is not a whole collision is refused, in words (F058)");
		byte[] good = GfColl.build(Arrays.asList(floor, other), null);
		int verts = 0;
		for (int k = 0; k < 16; k++) {
			verts += i32(good, 0x2A0 + k * 8 + 4);
		}
		int termAt = 0x320 + verts * 16;
		check(new String(good, termAt, 4, StandardCharsets.US_ASCII).equals("term"),
				"control: the built collision has its term marker at 0x" + Integer.toHexString(termAt));
		refused("a collision cut off inside its triangles", Arrays.copyOf(good, termAt - 40), "run past the end");
		refused("a collision cut off inside its bucket table", Arrays.copyOf(good, 0x300), "too short");
		byte[] noTerm = good.clone();
		noTerm[termAt] = 'x';
		refused("a collision with no term marker where its triangles end", noTerm, "\"term\" marker");
		byte[] ragged = good.clone();
		putI32(ragged, 0x2A0 + 4, i32(good, 0x2A0 + 4) + 1);
		refused("a bucket whose count is not whole triangles", ragged, "not a whole number of triangles");
		byte[] astray = good.clone();
		int full = 0;
		while (i32(good, 0x2A0 + full * 8 + 4) == 0) {
			full++;
		}
		//a bucket that holds triangles, pointed at where the table's vertices end
		putI32(astray, 0x2A0 + full * 8, verts);
		refused("a bucket naming vertices past the table's end", astray, "outside the");

		System.out.println("--- a collision that cannot be read is never opened or saved over (F058)");
		GR damaged = region(dir, "damaged", noTerm, game);
		try {
			GRCollisionFile.read(damaged);
			check(false, "reading a damaged collision is refused");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("cannot be read") && said.getMessage().contains("term"),
					"reading a damaged collision is refused, naming why: " + said.getMessage());
		}
		GRCollisionFile standIn = GRCollisionFile.readOrRefuse(damaged);
		check(standIn.unreadable != null && triangles(standIn) == 0,
				"for the map view it is a stand-in holding nothing: " + standIn.unreadable);
		check(Float.isNaN(standIn.getHeightAtPoint(0f, 0f)),
				"whose heights read as unknown, not as ground at 0 (it read " + standIn.getHeightAtPoint(0f, 0f) + ")");
		byte[] before = new GR(damaged.getOriginFile(), game).getFile(2);
		try {
			standIn.write();
			check(false, "and that refuses to be written over the bytes it could not read");
		} catch (IllegalStateException said) {
			check(true, "and that refuses to be written over the bytes it could not read: " + said.getMessage());
		}
		check(Arrays.equals(new GR(damaged.getOriginFile(), game).getFile(2), before),
				"and the region's collision on disk is the damaged bytes still, not an empty mesh");
		try {
			new CollEditPanel(new ToolSelection()).loadCollision(standIn, "Region 0x0");
			check(false, "the collision editor is not handed it");
		} catch (IllegalArgumentException said) {
			check(true, "the collision editor is not handed it: " + said.getMessage());
		}

		System.out.println("--- one damaged region costs the map view that region's collision, not the map (F058)");
		List<String> refusals = new ArrayList<>();
		GRCollisionFile kept = TileMapPanel.collisionOf(region(dir, "fine", good, game), "Region 0x0", refusals);
		GRCollisionFile lost = TileMapPanel.collisionOf(damaged, "Region 1x2", refusals);
		check(kept.unreadable == null && lost.unreadable != null, "the good region is read and the damaged one is not");
		check(refusals.size() == 1 && refusals.get(0).startsWith("Region 1x2: ") && refusals.get(0).contains("term"),
				"and the damaged one is named with its reason, for the load to report: " + refusals);
		String src = new String(Files.readAllBytes(new File("src/ctrmap/humaninterface/TileMapPanel.java").toPath()),
				StandardCharsets.UTF_8);
		String matrix = body(src, "public void loadMatrix(");
		String single = body(src, "public void loadTileMap(GR file)");
		//the matrix load reads in one half and shows in the other (F220): collision is READ in the first
		String regions = body(src, "public static Regions readRegions(");
		String shown = body(src, "public void showRegions(");
		check(matrix != null && matrix.contains("awaitLoad(worker);") && !matrix.contains("public static Regions readRegions(")
				&& single != null && single.contains("scene.frameSingleRegion();") && !single.contains("public void loadProps(")
				&& regions != null && regions.contains("return r;") && !regions.contains("public void showRegions(")
				&& shown != null && shown.contains("scene.frameMatrix(") && !shown.contains("public void awaitLoad("),
				"control: the four load methods are found, each whole and alone");
		check(regions != null && regions.contains("collisionOf(") && single != null && single.contains("collisionOf("),
				"both loads read collision through collisionOf");
		check(matrix != null && matrix.contains("collisionRefused(") && single != null && single.contains("collisionRefused("),
				"and both tell the user which regions they could not read");
		int direct = 0, control = 0, scanned = 0;
		for (File f : javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			scanned++;
			String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			boolean reads = text.contains("GRCollisionFile.read(") || text.contains("new GRCollisionFile(");
			if (f.getName().equals("GRCollisionFile.java")) {
				control += reads ? 1 : 0;
			} else if (reads) {
				direct++;
				System.out.println("    reads collision past collisionOf: " + f);
			}
		}
		check(control == 1 && scanned > 100, "control: the scan sees GRCollisionFile's own construction, over "
				+ scanned + " production files");
		check(direct == 0, "no other production file reads a region's collision any other way (" + direct + " do)");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** A region container in scratch whose subfile 2 is {@code coll}. */
	static GR region(File dir, String name, byte[] coll, FakeGameFiles game) throws Exception {
		File f = new File(dir, name + ".gr");
		Files.deleteIfExists(f.toPath());
		GR gr = new GR(f, 3, game);
		gr.storeFile(2, coll);
		return new GR(f, game);
	}

	/** A method's text from its signature to the brace that closes its body; null when absent. */
	static String body(String src, String signature) {
		int at = src.indexOf(signature);
		if (at < 0) {
			return null;
		}
		int i = src.indexOf('{', at);
		int depth = 0;
		do {
			char c = src.charAt(i++);
			depth += c == '{' ? 1 : c == '}' ? -1 : 0;
		} while (depth > 0 && i < src.length());
		return src.substring(at, i);
	}

	static int triangles(GRCollisionFile coll) {
		int n = 0;
		for (int k = 0; k < 16; k++) {
			n += coll.meshes[k].tris.size();
		}
		return n;
	}

	static void refused(String what, byte[] coll, String why) {
		try {
			GfColl c = new GfColl(coll);
			check(false, what + " is refused (it read " + c.uniqueTris.size() + " triangles)");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains(why), what + " is refused: " + said.getMessage());
		} catch (RuntimeException crash) {
			check(false, what + " is refused in words (it threw " + crash + ")");
		}
	}

	static long polyHash(float[] t) {
		long h = 1125899906842597L;
		for (float v : t) {
			h = 31 * h + Float.floatToIntBits(v);
		}
		return h;
	}

	static List<File> javaFiles(File dir, List<File> out) {
		File[] kids = dir.listFiles();
		if (kids != null) {
			for (File k : kids) {
				if (k.isDirectory()) {
					javaFiles(k, out);
				} else if (k.getName().endsWith(".java")) {
					out.add(k);
				}
			}
		}
		return out;
	}

	static int i32(byte[] b, int at) {
		return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
	}

	static void putI32(byte[] b, int at, int v) {
		b[at] = (byte) v;
		b[at + 1] = (byte) (v >> 8);
		b[at + 2] = (byte) (v >> 16);
		b[at + 3] = (byte) (v >> 24);
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
