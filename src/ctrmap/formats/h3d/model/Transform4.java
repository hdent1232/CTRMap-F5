package ctrmap.formats.h3d.model;

/**
 * A 4x4 transform held column-major in a {@code float[16]}, as a BCH model stores its world and bone
 * matrices: starts as the identity, and {@link #scale}, {@link #rotate} and {@link #translate} each
 * multiply onto it from the right, OpenGL-style.
 *
 * <p>WHY IT IS HERE. The model reader held these in JOGL's {@code com.jogamp.opengl.math.Matrix4}, so three
 * format classes named an OpenGL type (F214) and a JOGL upgrade - 2.3.2 to 2.6.0, F077, whose math moved
 * to {@code com.jogamp.math} - broke the format layer. MEASURED 2026-10-10 against that class: every bone
 * of every retail model - 857 map, 380 building and 544 NPC models, 12,146 bones, 5,097 of them rotated -
 * comes out with the same 16 values both ways (scratch probe CompareBoneMatrices, whose control - the same
 * steps in another order - does differ). {@code ModelTransformTest} holds this class to that.
 *
 * <p>Not: {@link ctrmap.formats.mapmatrix.MapMatrix} - that is the world's grid of map regions, which
 * shares the word and nothing else.
 */
public final class Transform4 {

	private final float[] m = new float[16];

	public Transform4() {
		m[0] = m[5] = m[10] = m[15] = 1f;
	}

	/** The 16 values, column-major: element (row r, column c) is {@code [r + 4 * c]}. Live, not a copy. */
	public float[] cells() {
		return m;
	}

	/** This = this x scale(x, y, z). */
	public void scale(float x, float y, float z) {
		float[] s = new float[16];
		s[0] = x;
		s[5] = y;
		s[10] = z;
		s[15] = 1f;
		multiply(s);
	}

	/** This = this x translation(x, y, z). */
	public void translate(float x, float y, float z) {
		float[] t = new float[16];
		t[0] = t[5] = t[10] = t[15] = 1f;
		t[12] = x;
		t[13] = y;
		t[14] = z;
		multiply(t);
	}

	/** This = this x rotation of {@code radians} about the axis (x, y, z), normalised first. */
	public void rotate(float radians, float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		if (length == 0f) {
			return;
		}
		x /= length;
		y /= length;
		z /= length;
		float c = (float) Math.cos(radians), s = (float) Math.sin(radians), ic = 1f - c;
		float[] r = new float[16];
		r[0] = x * x * ic + c;
		r[1] = x * y * ic + z * s;
		r[2] = x * z * ic - y * s;
		r[4] = x * y * ic - z * s;
		r[5] = y * y * ic + c;
		r[6] = y * z * ic + x * s;
		r[8] = x * z * ic + y * s;
		r[9] = y * z * ic - x * s;
		r[10] = z * z * ic + c;
		r[15] = 1f;
		multiply(r);
	}

	/** This = this x b, both column-major. */
	void multiply(float[] b) {
		float[] a = m.clone();
		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 4; col++) {
				m[row + 4 * col] = a[row] * b[4 * col] + a[row + 4] * b[1 + 4 * col]
						+ a[row + 8] * b[2 + 4 * col] + a[row + 12] * b[3 + 4 * col];
			}
		}
	}
}
