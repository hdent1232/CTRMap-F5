package ctrmap.formats;

import ctrmap.formats.gfcollision.GRCollisionMesh;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * The part of the Wavefront OBJ format that collision mesh generation needs: objects or groups,
 * vertices and faces. Anything else in the file is ignored; anything in those three it cannot read
 * is refused, naming the line.
 *
 * <p>F044. The quad branch wrote (v0, v1, v1) - a triangle with no area - so a Blender export with
 * quads imported half its walkable floor as holes the player falls through; a face before any
 * {@code o}/{@code g} line was an IndexOutOfBoundsException the menu action's IOException catch did
 * not cover; relative (negative) indices and faces of more than four corners were dropped without a
 * word; and the reader was never closed. Every face is now a fan of triangles from its first corner
 * - a triangle once, a quad twice, an n-gon n - 2 times - in the face's own winding, which the old
 * second quad triangle reversed.
 */
public class WavefrontOBJ {

	private final ArrayList<OBJMesh> meshes = new ArrayList<>();

	/**
	 * Reads {@code f}.
	 *
	 * @throws IOException when the file cannot be read, or holds a vertex or face that cannot be -
	 * naming the file and the line
	 */
	public WavefrontOBJ(File f) throws IOException {
		try (BufferedReader src = Files.newBufferedReader(f.toPath(), StandardCharsets.ISO_8859_1)) {
			read(src, f.getName());
		}
	}

	private WavefrontOBJ() {
	}

	/** The same reading, from text - for a suite that has no file to hand. */
	public static WavefrontOBJ parse(String text) throws IOException {
		WavefrontOBJ obj = new WavefrontOBJ();
		obj.read(new BufferedReader(new StringReader(text)), "the OBJ text");
		return obj;
	}

	private void read(BufferedReader src, String name) throws IOException {
		ArrayList<float[]> vertices = new ArrayList<>();
		OBJMesh current = null;
		int lineNo = 0;
		String line;
		while ((line = src.readLine()) != null) {
			lineNo++;
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] read = trimmed.split("\\s+");
			switch (read[0]) {
				case "o":
				case "g":
					current = new OBJMesh(read.length > 1 ? read[1] : "mesh " + meshes.size());
					meshes.add(current);
					break;
				case "v":
					if (read.length < 4) {
						throw bad(name, lineNo, "a vertex needs x, y and z");
					}
					vertices.add(new float[]{number(read[1], name, lineNo), number(read[2], name, lineNo),
						number(read[3], name, lineNo)});
					break;
				case "f":
					if (read.length < 4) {
						throw bad(name, lineNo, "a face needs at least three corners");
					}
					if (current == null) {
						//faces with no object or group before them belong to one of their own
						current = new OBJMesh("default");
						meshes.add(current);
					}
					float[][] corners = new float[read.length - 1][];
					for (int k = 1; k < read.length; k++) {
						corners[k - 1] = corner(vertices, read[k], name, lineNo);
					}
					for (int k = 1; k + 1 < corners.length; k++) {
						//a face with no area on the ground plane is a wall, which collision leaves out
						if (!flat(corners[0], corners[k], corners[k + 1])) {
							current.addTri(corners[0], corners[k], corners[k + 1]);
						}
					}
					break;
				default:
					break;
			}
		}
	}

	/** The vertex a face corner names: "i", "i/t" or "i/t/n", 1-based, or negative counting back from the last vertex. */
	private static float[] corner(ArrayList<float[]> vertices, String token, String name, int lineNo) throws IOException {
		int i;
		try {
			i = Integer.parseInt(token.split("/")[0]);
		} catch (NumberFormatException notAnIndex) {
			throw bad(name, lineNo, "'" + token + "' is not a vertex index");
		}
		int at = i < 0 ? vertices.size() + i : i - 1;
		if (i == 0 || at < 0 || at >= vertices.size()) {
			throw bad(name, lineNo, "vertex " + i + " does not exist (" + vertices.size() + " read so far)");
		}
		return vertices.get(at);
	}

	private static float number(String token, String name, int lineNo) throws IOException {
		try {
			return Float.parseFloat(token);
		} catch (NumberFormatException notANumber) {
			throw bad(name, lineNo, "'" + token + "' is not a number");
		}
	}

	private static IOException bad(String name, int lineNo, String what) {
		return new IOException(name + ", line " + lineNo + ": " + what + ".");
	}

	/** Twice the area of the triangle's shadow on the ground plane is zero: a wall. */
	private static boolean flat(float[] v0, float[] v1, float[] v2) {
		return ctrmap.util.Bytes.impreciseFloatEquals(
			v0[0] * (v1[2] - v2[2]) + v1[0] * (v2[2] - v0[2]) + v2[0] * (v0[2] - v1[2]), 0f);
	}

	/** How many triangles were read, across every mesh. */
	public int triangleCount() {
		int n = 0;
		for (OBJMesh m : meshes) {
			n += m.tris.size();
		}
		return n;
	}

	/**
	 * Get collision data than can be stored into a GRCollisionFile: the meshes dealt round sixteen
	 * slots. EMPTY when no triangle was read - sixteen empty meshes would replace a zone's whole
	 * collision with nothing, and the caller's "holds no faces" refusal could never be reached.
	 *
	 * @return Array of raw collision mesh data.
	 */
	public GRCollisionMesh[] getGfCollision() {
		if (triangleCount() == 0) {
			return new GRCollisionMesh[0];
		}
		GRCollisionMesh[] ret = new GRCollisionMesh[16];
		for (int i = 0; i < 16; i++) {
			ret[i] = new GRCollisionMesh();
		}
		for (int i = 0, j = 0; i < meshes.size(); i++, j++) {
			ret[j].tris.addAll(meshes.get(i).tris);
			if (j == 15) {
				j = -1;
			}
		}
		return ret;
	}
}

class OBJMesh {

	public ArrayList<Triangle> tris = new ArrayList<>();
	public String name;

	public OBJMesh(String name) {
		this.name = name;
	}

	public void addTri(float[] v0, float[] v1, float[] v2) {
		tris.add(new Triangle(new float[]{v0[0], v1[0], v2[0]}, new float[]{v0[1], v1[1], v2[1]}, new float[]{v0[2], v1[2], v2[2]}));
	}
}
