package ctrmap.tests;

import ctrmap.formats.Triangle;
import ctrmap.formats.WavefrontOBJ;
import ctrmap.formats.gfcollision.GRCollisionMesh;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * OBJ to collisions reads what real tools write, and every floor face it reads stays a floor (F044).
 *
 * <p>WHY THIS SUITE EXISTS. Map > OBJ to collisions replaces the open collision meshes with the OBJ's
 * faces. Its quad branch wrote (v0, v1, v1) - a triangle with no area - so a Blender export with
 * quads imported half its walkable floor as holes; a face before any object line crashed; relative
 * indices and faces of more than four corners were dropped silently; and an OBJ with no usable face
 * replaced the zone's collision with sixteen empty meshes, because the refusal for that case could
 * never be reached.
 *
 * <p>ORDER: needs no dump and no display.
 *
 * Usage: java ctrmap.tests.WavefrontOBJTest
 */
public class WavefrontOBJTest {

	static int fails = 0;

	/** A unit square on the ground plane, corners in order round it. */
	static final String SQUARE = "v 0 0 0\nv 1 0 0\nv 1 0 1\nv 0 0 1\n";

	public static void main(String[] args) throws Exception {
		aQuadIsTwoWholeTriangles();
		facesWithNoObjectLineAreRead();
		relativeIndicesAndBiggerFacesAreRead();
		wallsAreLeftOut();
		malformedInputIsRefusedNamingTheLine();
		anObjWithNoFloorGivesNothingToReplaceCollisionWith();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** How many triangles an OBJ reads as, or -1 when it is refused - so a check says FAIL rather than ending the suite. */
	static int count(String text) {
		try {
			return tris(WavefrontOBJ.parse(text)).size();
		} catch (IOException refused) {
			System.out.println("    (refused: " + refused.getMessage() + ")");
			return -1;
		}
	}

	static List<Triangle> tris(WavefrontOBJ obj) {
		List<Triangle> out = new ArrayList<>();
		for (GRCollisionMesh m : obj.getGfCollision()) {
			out.addAll(m.tris);
		}
		return out;
	}

	/** Twice the ground-plane area, signed: the same measure the reader uses to tell a wall. */
	static float area(Triangle t) {
		return t.x[0] * (t.z[1] - t.z[2]) + t.x[1] * (t.z[2] - t.z[0]) + t.x[2] * (t.z[0] - t.z[1]);
	}

	static void aQuadIsTwoWholeTriangles() throws Exception {
		System.out.println("--- a quad is two whole triangles, not one and a sliver");
		List<Triangle> t = tris(WavefrontOBJ.parse("o floor\n" + SQUARE + "f 1 2 3 4\n"));
		check(t.size() == 2, "a quad face reads as 2 triangles (" + t.size() + ")");
		float covered = 0;
		boolean noSliver = true;
		for (Triangle tri : t) {
			covered += Math.abs(area(tri));
			noSliver &= Math.abs(area(tri)) > 0.5f;
		}
		check(noSliver && Math.abs(covered - 2f) < 1e-4,
				"and together they cover the whole square, neither with no area (twice the area: " + covered + ")");
		check(t.size() == 2 && Math.signum(area(t.get(0))) == Math.signum(area(t.get(1))),
				"and both keep the face's winding");
	}

	static void facesWithNoObjectLineAreRead() throws Exception {
		System.out.println("--- faces before any object or group line are read, not a crash");
		try {
			check(tris(WavefrontOBJ.parse(SQUARE + "f 1 2 3\n")).size() == 1, "a face with no 'o' before it is read");
		} catch (RuntimeException crash) {
			check(false, "a face with no 'o' before it is read (it threw " + crash + ")");
		}
	}

	static void relativeIndicesAndBiggerFacesAreRead() throws Exception {
		System.out.println("--- relative indices and faces of more than four corners are read");
		check(count("o a\n" + SQUARE + "f -4 -3 -2 -1\n") == 2,
				"a face written with relative indices is read");
		check(count("o a\nv 0 0 0\nv 2 0 0\nv 3 0 1\nv 1 0 2\nv -1 0 1\nf 1/1/1 2/2/2 3/3/3 4/4/4 5/5/5\n") == 3,
				"a pentagon with texture and normal indices reads as 3 triangles");
	}

	static void wallsAreLeftOut() throws Exception {
		System.out.println("--- a wall, with no area on the ground, is left out");
		check(tris(WavefrontOBJ.parse("o w\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n")).isEmpty(),
				"a vertical quad gives no collision triangle");
	}

	static void malformedInputIsRefusedNamingTheLine() throws Exception {
		System.out.println("--- malformed input is refused, naming the line");
		check(said("o a\n" + SQUARE + "f 1 2 9\n").contains("line 6") && said("o a\n" + SQUARE + "f 1 2 9\n").contains("9"),
				"a face naming a vertex that does not exist: " + said("o a\n" + SQUARE + "f 1 2 9\n"));
		check(said("v 1 2\n").contains("line 1"), "a vertex with two coordinates: " + said("v 1 2\n"));
		check(said("v 1 x 2\n").contains("'x' is not a number"), "a vertex coordinate that is not a number: "
				+ said("v 1 x 2\n"));
	}

	static void anObjWithNoFloorGivesNothingToReplaceCollisionWith() throws Exception {
		System.out.println("--- an OBJ with no floor gives nothing to replace the collision with");
		check(WavefrontOBJ.parse("o w\nv 0 0 0\nv 1 0 0\nv 1 1 0\nf 1 2 3\n").getGfCollision().length == 0,
				"no meshes at all - sixteen empty ones would wipe the zone's collision");
		check(WavefrontOBJ.parse("o a\n" + SQUARE + "f 1 2 3\n").getGfCollision().length == 16,
				"while an OBJ with a floor fills the sixteen slots");
	}

	static String said(String obj) {
		try {
			WavefrontOBJ.parse(obj);
			return "(it was read)";
		} catch (IOException refused) {
			return refused.getMessage();
		} catch (RuntimeException crash) {
			return "(it threw " + crash + ")";
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
