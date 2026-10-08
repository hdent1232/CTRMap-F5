package ctrmap.tests;

import ctrmap.formats.h3d.BchMapModel;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Growing a mesh and replacing it are one rewrite (F062), checked on the map model the build
 * ships ({@code DummyBCH3DModel.bch}: one mesh, 24 vertices, 36 indices, an 8-bit index buffer) -
 * so with no dump, where BchMapModelTest's corpus checks have nothing to read.
 *
 * <p>appendGeometry was setMeshGeometry written out a second time: the index-buffer lookup, the
 * width upgrade, the encoding and the relocation flag flip. It is that with the mesh's own buffers
 * first now; this pins what both answer, the 8-to-16-bit upgrade included, and that the rewrite
 * exists once in the source.
 *
 * Usage: java ctrmap.tests.BchMeshEditTest [src-root]
 */
public class BchMeshEditTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File src = new File(args.length > 0 ? args[0] : "src");
		byte[] dummy = Files.readAllBytes(new File(src, "ctrmap/resources/DummyBCH3DModel.bch").toPath());
		BchMapModel m = new BchMapModel(dummy);
		BchMapModel.MeshGeom g = m.geometry().get(0);
		int[] tris = m.getTriangles(0);
		check(m.meshCount == 1 && g.posOk && g.vertexCount == 24 && tris != null && tris.length == 36
				&& m.idxBuffers.get(0)[2] == 1, "control: the shipped model is one mesh of 24 vertices and 36 indices,"
				+ " in an 8-bit buffer (" + m.meshCount + " mesh(es), " + g.vertexCount + " vertices, "
				+ (tris == null ? "no" : tris.length) + " indices, " + m.idxBuffers.get(0)[2] + "-byte)");

		System.out.println("--- a grown mesh is the old one with the new after it (F062)");
		byte[] four = Arrays.copyOfRange(m.raw, g.vtxAbs, g.vtxAbs + 4 * g.stride);
		BchMapModel grown = new BchMapModel(m.appendGeometry(0, four, new int[]{24, 25, 26, 24, 26, 27}));
		int[] grownTris = grown.getTriangles(0);
		check(grown.validate().isEmpty() && grown.geometry().get(0).vertexCount == 28,
				"it parses clean, 28 vertices: " + grown.validate());
		check(grownTris.length == 42 && Arrays.equals(Arrays.copyOf(grownTris, 36), tris)
				&& Arrays.equals(Arrays.copyOfRange(grownTris, 36, 42), new int[]{24, 25, 26, 24, 26, 27}),
				"its faces are the old 36 indices, then the new six");
		BchMapModel.MeshGeom gg = grown.geometry().get(0);
		check(Arrays.equals(Arrays.copyOfRange(grown.raw, gg.vtxAbs, gg.vtxAbs + 24 * g.stride),
				Arrays.copyOfRange(m.raw, g.vtxAbs, g.vtxAbs + 24 * g.stride)), "and its old vertices are untouched");

		System.out.println("--- a mesh grown past 255 vertices gets a 16-bit index buffer (F062)");
		byte[] many = new byte[240 * g.stride];
		for (int k = 0; k < 240; k++) {
			System.arraycopy(m.raw, g.vtxAbs, many, k * g.stride, g.stride);
		}
		BchMapModel wide = new BchMapModel(m.appendGeometry(0, many, new int[]{0, 1, 263}));
		int[] wideTris = wide.getTriangles(0);
		check(wide.validate().isEmpty() && wide.idxBuffers.get(0)[2] == 2,
				"the buffer is upgraded to 16 bits and the model parses clean: " + wide.idxBuffers.get(0)[2] + "-byte, "
				+ wide.validate());
		check(wideTris.length == 39 && wideTris[38] == 263 && Arrays.equals(Arrays.copyOf(wideTris, 36), tris),
				"and index 263 reads back as itself, the old faces before it");

		System.out.println("--- replacing a mesh is the same rewrite (F062)");
		check(Arrays.equals(m.setMeshGeometry(0, Arrays.copyOfRange(m.raw, g.vtxAbs, g.vtxAbs + 24 * g.stride), tris.clone()),
				m.appendGeometry(0, new byte[0], new int[0])),
				"growing a mesh by nothing writes what replacing it with itself writes");
		severalMeshesInOneRebuild(dummy, g);

		String body = new String(Files.readAllBytes(new File(src, "ctrmap/formats/h3d/BchMapModel.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		int flips = count(body, "0x27 << 25");
		check(flips == 1, "the relocation flag of an upgraded index buffer is flipped in one place: " + flips);

		//F239: a mesh whose positions do not decode is left alone by every edit, and the Geometry
		//tool's selection counted vertices, faces and meshes - never the meshes it was leaving behind
		ctrmap.formats.h3d.GeoBoxOps.Box everywhere = new ctrmap.formats.h3d.GeoBoxOps.Box(-1e6f, -1e6f, 1e6f, 1e6f);
		ctrmap.formats.h3d.GeoBoxOps.Selection whole = ctrmap.formats.h3d.GeoBoxOps.query(m, everywhere);
		check(m.undecodedMeshes().isEmpty() && whole.undecodedMeshes == 0 && whole.touchedMeshes == 1,
				"control: the shipped mesh decodes, and a box over all of it selects it and leaves nothing undecoded");
		byte[] garbled = dummy.clone();
		ctrmap.util.LittleEndian.putF32(garbled, g.vtxAbs + g.posOffset, Float.NaN);
		BchMapModel u = new BchMapModel(garbled);
		ctrmap.formats.h3d.GeoBoxOps.Selection left = ctrmap.formats.h3d.GeoBoxOps.query(u, everywhere);
		check(u.undecodedMeshes().equals(java.util.Arrays.asList(0)) && left.undecodedMeshes == 1 && left.touchedMeshes == 0,
				"and a mesh whose first position is not a number is named undecoded and counted by the selection ("
				+ u.undecodedMeshes() + ", " + left.undecodedMeshes + " undecoded, " + left.touchedMeshes + " selected)");

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/**
	 * The geometry is decoded once per model and what is kept cannot be changed by a caller (F068):
	 * the box tools asked every mesh's geometry several times per mesh. Whether one rebuild of several
	 * meshes writes what one rebuild per mesh writes needs a model of two or more meshes - the shipped
	 * one is a single mesh and the appender will not take it as a donor - so GeoBoxOpsTest asks that of
	 * the game's own regions.
	 */
	static void severalMeshesInOneRebuild(byte[] dummy, BchMapModel.MeshGeom g) {
		System.out.println("--- the geometry is decoded once per model (F068)");
		BchMapModel m = new BchMapModel(dummy);
		check(m.geometry() == m.geometry(), "the geometry is decoded once for the model, not per ask");
		boolean frozen;
		try {
			m.geometry().clear();
			frozen = false;
		} catch (UnsupportedOperationException expected) {
			frozen = true;
		}
		check(frozen && m.geometry().size() == 1, "and what is kept cannot be changed by whoever asked for it");
		float[][] p = m.getVertexPositions(0);
		for (float[] v : p) {
			v[1] += 5;
		}
		check(Arrays.equals(m.setVertexPositions(java.util.Collections.singletonMap(0, p)), m.setVertexPositions(0, p)),
				"positions written for several meshes at once are the one-mesh write when there is one");
	}

	static int count(String s, String what) {
		int n = 0;
		for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) {
			n++;
		}
		return n;
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		fails += ok ? 0 : 1;
	}
}
