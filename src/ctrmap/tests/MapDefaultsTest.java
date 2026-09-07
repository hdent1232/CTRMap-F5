package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.formats.containers.MM;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.h3d.BchMapModel;
import ctrmap.formats.mapmatrix.MapMatrix;
import ctrmap.formats.tilemap.PaintedRegionBuilder;
import java.io.File;
import java.io.FileOutputStream;

/**
 * The two answers the map tools reach for when the user has not said which
 * region, or which mesh. Both used to be computed inside a modal dialog's
 * listener, so neither had ever been checked against the game.
 *
 * <h2>Which region is "this zone's map"</h2>
 * Export OBJ, Import OBJ and Blank map canvas all work on ONE FieldData
 * region, and all three offered the zone's first as the default. Two of them
 * carried their own copy of the byte arithmetic that reads it out of the map
 * matrix container. Import OBJ writes into the region the spinner is showing,
 * so a wrong default is not a cosmetic annoyance - it drops the user's edited
 * model into a region belonging to another town. The arithmetic now lives once,
 * in {@link MapMatrix#firstRegionId}, and this suite runs it over every map
 * matrix the game ships, cross-checked against MapMatrix's own stream parse.
 *
 * <h2>Which mesh is "the ground"</h2>
 * Blank map canvas blanks every region of the zone, but a mesh number chosen
 * while looking at one region means nothing in the next - the regions do not
 * share a numbering. Where the number did not fit, the fallback picked the mesh
 * with the most triangles, which is the exact heuristic
 * {@link PaintedRegionBuilder#defaultGroundMesh} exists to replace: indoors and
 * in most outdoor maps the biggest mesh is a wall, a fence or the sea, so the
 * new floor came out textured as one.
 *
 * <p>Measured over the first 400 retail regions, the two answers differ for
 * 312 of them. Region 1 - the tileset donor the painter suites build on - would
 * have been floored in {@code chip_wood_b} instead of {@code chip_kusa}.
 *
 * Usage: java ctrmap.tests.MapDefaultsTest &lt;romfs-root&gt;
 */
public class MapDefaultsTest {

	/** How many FieldData regions to score the ground choice over. */
	private static final int REGIONS = 400;
	/** The tileset donor the painter suites use; its ground is not its biggest mesh. */
	private static final int DONOR = 1;

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		unreadableMatricesAnswerMinusOne();

		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the corpus checks need MapMatrix and FieldData");
		} else {
			Workspace.game = Workspace.GameType.ORAS;
			//left invalid on purpose: MapMatrix opens a GR per populated cell
			//against the live workspace when it is valid, and this suite only
			//wants the numbers in the file
			Workspace.valid = false;
			everyRetailMatrixNamesItsFirstRegion(dump);
			theGroundIsNotJustTheBiggestMesh(dump);
			aPickedMeshIsHonouredWhereItFits(dump);
		}

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * A matrix that cannot be read must answer -1, not throw. The callers use
	 * the answer as a spinner default; an exception there is a dialog that does
	 * not open at all.
	 */
	static void unreadableMatricesAnswerMinusOne() {
		check(MapMatrix.firstRegionId(null) == -1, "no file at all -> no default region");
		check(MapMatrix.firstRegionId(new byte[0]) == -1, "an empty file -> no default region");
		check(MapMatrix.firstRegionId(new byte[]{1, 2, 3, 4, 5}) == -1, "a file too short to hold an offset -> no default region");
		//a well-formed header whose subfile offset points past the end
		byte[] bogus = new byte[16];
		put32(bogus, 4, 0x40000);
		check(MapMatrix.firstRegionId(bogus) == -1, "an offset past the end of the file -> no default region");
		//a real shape, 2x2, every cell empty
		byte[] empty = matrix(2, 2, new int[]{0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF});
		check(MapMatrix.firstRegionId(empty) == -1, "a matrix whose cells are all empty -> no default region");
		byte[] one = matrix(2, 2, new int[]{0xFFFF, 0xFFFF, 77, 0xFFFF});
		check(MapMatrix.firstRegionId(one) == 77, "the first cell that holds a region is the default (77)");
		byte[] two = matrix(2, 2, new int[]{0xFFFF, 12, 77, 0xFFFF});
		check(MapMatrix.firstRegionId(two) == 12, "and it is the FIRST one in reading order, not the last (12)");
		//a matrix claiming more cells than it carries must stop at the end
		byte[] cut = matrix(8, 8, new int[]{0xFFFF, 0xFFFF});
		check(MapMatrix.firstRegionId(cut) == -1, "a truncated matrix stops at the end of the data rather than reading past it");
	}

	/**
	 * Every map matrix the game ships, read twice: once by the byte arithmetic
	 * the dialogs use, once by MapMatrix's own stream parse. They must agree,
	 * and the answer must be a region the matrix really contains.
	 */
	static void everyRetailMatrixNamesItsFirstRegion(File dump) throws Exception {
		GARC mm = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.MAP_MATRIX, Workspace.game)));
		File tmp = Scratch.file("ctrmap_mapdefaults");
		int read = 0, populated = 0;
		StringBuilder bad = new StringBuilder();
		for (int i = 0; i < mm.length; i++) {
			byte[] raw = mm.getDecompressedEntry(i);
			if (raw == null || raw.length < 8) {
				continue;
			}
			FileOutputStream fos = new FileOutputStream(tmp);
			try {
				fos.write(raw);
			} finally {
				fos.close();
			}
			MapMatrix parsed;
			try {
				parsed = new MapMatrix(new MM(tmp));
			} catch (Exception ex) {
				continue;
			}
			if (parsed.width <= 0 || parsed.height <= 0) {
				continue;
			}
			read++;
			int oracle = -1;
			for (int y = 0; y < parsed.height && oracle < 0; y++) {
				for (int x = 0; x < parsed.width && oracle < 0; x++) {
					short id = parsed.ids.get(x, y);
					if ((id & 0xFFFF) != 0xFFFF) {
						oracle = id & 0xFFFF;
					}
				}
			}
			int got = MapMatrix.firstRegionId(raw);
			if (oracle >= 0) {
				populated++;
			}
			if (got != oracle && bad.length() < 600) {
				bad.append("\n    matrix ").append(i).append(" (").append(parsed.width).append("x")
						.append(parsed.height).append("): the dialogs read ").append(got)
						.append(", the matrix parser reads ").append(oracle);
			}
		}
		check(read >= 100, "read " + read + " retail map matrices (" + populated + " with a region in them)");
		check(bad.length() == 0, "every one names the same first region as the matrix parser does" + bad);
	}

	/**
	 * The check with teeth. For every region whose ground is NOT its biggest
	 * mesh, the blank-canvas fallback must choose the ground.
	 */
	static void theGroundIsNotJustTheBiggestMesh(File dump) throws Exception {
		GARC fd = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game)));
		int scored = 0, differ = 0, wrong = 0;
		StringBuilder first = new StringBuilder();
		for (int i = 0; i < Math.min(REGIONS, fd.length); i++) {
			BchMapModel m = modelOf(fd, i);
			if (m == null) {
				continue;
			}
			int naive = biggestMesh(m);
			int ground = PaintedRegionBuilder.defaultGroundMesh(m);
			if (naive < 0 || ground < 0) {
				continue;
			}
			scored++;
			//"the number the user picked does not fit this region" - the case
			//the fallback exists for
			int chosen = PaintedRegionBuilder.groundMeshOr(m, m.meshCount + 5);
			if (chosen != ground) {
				wrong++;
				if (first.length() < 400) {
					first.append("\n    region ").append(i).append(": fell back to mesh ").append(chosen)
							.append(", the ground is mesh ").append(ground);
				}
			}
			if (naive != ground) {
				differ++;
			}
		}
		check(scored >= 200, "scored the ground choice over " + scored + " retail regions");
		check(differ >= 100, differ + " of them have a ground that is NOT their biggest mesh, so the "
				+ "fallback has something to get wrong");
		check(wrong == 0, "on every one, a mesh number that does not fit falls back to the ground" + first);

		//and name one, so the failure above is readable rather than a count
		BchMapModel donor = modelOf(fd, DONOR);
		if (donor == null) {
			check(false, "the tileset donor region " + DONOR + " is a map model");
			return;
		}
		int naive = biggestMesh(donor);
		int ground = PaintedRegionBuilder.defaultGroundMesh(donor);
		check(naive != ground, "region " + DONOR + "'s biggest mesh (" + name(donor, naive)
				+ ") is not its ground (" + name(donor, ground) + ")");
		check(PaintedRegionBuilder.groundMeshOr(donor, -1) == ground,
				"so blanking it with no usable pick floors it in " + name(donor, ground)
				+ ", not " + name(donor, naive));
		check(PaintedRegionBuilder.groundMeshOr(donor, donor.meshCount) == ground,
				"and a mesh number past the end of this region does the same");
	}

	/** A pick that DOES fit this region must be honoured, not overridden. */
	static void aPickedMeshIsHonouredWhereItFits(File dump) throws Exception {
		GARC fd = new GARC(new File(dump.getAbsolutePath()
				+ Workspace.getArchivePath(Workspace.ArchiveType.FIELD_DATA, Workspace.game)));
		BchMapModel donor = modelOf(fd, DONOR);
		if (donor == null) {
			check(false, "the tileset donor region " + DONOR + " is a map model");
			return;
		}
		int honoured = 0, ignored = 0;
		for (BchMapModel.MeshGeom g : donor.geometry()) {
			if (!g.posOk) {
				continue;
			}
			if (PaintedRegionBuilder.groundMeshOr(donor, g.meshIndex) == g.meshIndex) {
				honoured++;
			} else {
				ignored++;
			}
		}
		check(honoured > 1 && ignored == 0, "every readable mesh of region " + DONOR
				+ " is honoured when the user picks it (" + honoured + " honoured, " + ignored + " overridden)");
		check(PaintedRegionBuilder.groundMeshOr(donor, biggestMesh(donor)) == biggestMesh(donor),
				"including the biggest one - the fallback must not second-guess a pick that fits");
	}

	private static BchMapModel modelOf(GARC fd, int region) {
		byte[] raw = fd.getDecompressedEntry(region);
		if (raw == null) {
			return null;
		}
		byte[] model = subfile(raw, 1);
		if (model == null || !BchMapModel.isMapModel(model)) {
			return null;
		}
		try {
			return new BchMapModel(model);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** The old fallback: whichever readable mesh has the most triangles. */
	private static int biggestMesh(BchMapModel m) {
		int best = -1;
		long tris = -1;
		for (BchMapModel.MeshGeom g : m.geometry()) {
			if (g.posOk && m.getTriangles(g.meshIndex).length > tris) {
				tris = m.getTriangles(g.meshIndex).length;
				best = g.meshIndex;
			}
		}
		return best;
	}

	private static String name(BchMapModel m, int mesh) {
		String n = mesh < 0 ? null : m.getMaterialName(m.getMeshMaterialIndex(mesh));
		return "mesh " + mesh + " [" + n + "]";
	}

	private static byte[] subfile(byte[] container, int idx) {
		if (container.length < 8 + idx * 4) {
			return null;
		}
		int off = i32(container, 4 + idx * 4);
		int end = i32(container, 8 + idx * 4);
		if (off < 0 || end > container.length || end <= off) {
			return null;
		}
		byte[] out = new byte[end - off];
		System.arraycopy(container, off, out, 0, out.length);
		return out;
	}

	/** A map matrix container: 2-byte magic, 2-byte count, offsets, subfile 0. */
	private static byte[] matrix(int w, int h, int[] cells) {
		int sub0 = 12;                       //magic + count + two offsets
		byte[] b = new byte[sub0 + 8 + cells.length * 2];
		b[0] = 'M';
		b[1] = 'M';
		b[2] = 1;
		put32(b, 4, sub0);
		put32(b, 8, b.length);
		b[sub0 + 4] = (byte) w;
		b[sub0 + 6] = (byte) h;
		for (int i = 0; i < cells.length; i++) {
			b[sub0 + 8 + i * 2] = (byte) cells[i];
			b[sub0 + 9 + i * 2] = (byte) (cells[i] >> 8);
		}
		return b;
	}

	private static void put32(byte[] b, int o, int v) {
		b[o] = (byte) v;
		b[o + 1] = (byte) (v >> 8);
		b[o + 2] = (byte) (v >> 16);
		b[o + 3] = (byte) (v >> 24);
	}

	private static int i32(byte[] b, int o) {
		return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
		if (!ok) {
			fails++;
		}
	}
}
