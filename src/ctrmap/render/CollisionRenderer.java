package ctrmap.render;

import com.jogamp.opengl.GL2;
import ctrmap.formats.CMVD;
import ctrmap.formats.Triangle;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.gfcollision.GRCollisionMesh;

/**
 * Draws a region's collision and the move gizmo's arrows with OpenGL.
 *
 * <p>WHY IT IS HERE. Each of these drew itself - {@code Triangle.render}, {@code GRCollisionMesh.render},
 * {@code GRCollisionFile.render}, {@code CMVD.render} - so reading a region's collision needed JOGL on the
 * classpath (F214). The drawing moved here unchanged; OffscreenRenderTest holds both to the frames they
 * drew before the move. Nothing is kept between frames, so these are statics.
 *
 * <p>Not: {@link ModelRenderer} - that draws BCH models, with buffers, materials and textures.
 */
public final class CollisionRenderer {

	private CollisionRenderer() {
	}

	/** Every bucket's triangles - inside a GL_TRIANGLES begin/end the caller opened. */
	public static void render(GL2 gl, GRCollisionFile collision) {
		for (int i = 0; i < 16; i++) {
			render(gl, collision.meshes[i]);
		}
	}

	/** One bucket's triangles - inside a GL_TRIANGLES begin/end the caller opened. */
	public static void render(GL2 gl, GRCollisionMesh mesh) {
		for (int i = 0; i < mesh.tris.size(); i++) {
			render(gl, mesh.tris.get(i));
		}
	}

	/**
	 * One triangle in the colour of its height, and a selected one's edges raised and outlined in white -
	 * inside a GL_TRIANGLES begin/end the caller opened.
	 */
	public static void render(GL2 gl, Triangle t) {
		float averageheight = (Math.max(t.getY(0), Math.max(t.getY(1), t.getY(2))) + Math.min(t.getY(0), Math.min(t.getY(1), t.getY(2)))) / 2f;
		float[] colour = Triangle.heightColour((long) averageheight);
		gl.glColor3f(colour[0], colour[1], colour[2]);
		for (int i = 0; i < 3; i++) {
			gl.glVertex3f(t.getX(i), t.getY(i), t.getZ(i));
		}
		if (t.isSelected()) {
			gl.glColor3f(1f, 1f, 1f);
			for (int i = 0; i < 3; i++) {
				int c0 = i;
				int c1 = (i + 1 == 3) ? 0 : i + 1;
				gl.glVertex3f(t.getX(c0), t.getY(c0) + 1f, t.getZ(c0));
				gl.glVertex3f(t.getX(c1), t.getY(c1) + 1f, t.getZ(c1));
				gl.glVertex3f(t.getX(c1), t.getY(c1) + 1f, t.getZ(c1) + 1f);
				gl.glVertex3f(t.getX(c0), t.getY(c0) + 1f, t.getZ(c0));
				gl.glVertex3f(t.getX(c1), t.getY(c1) + 1f, t.getZ(c1) + 1f);
				gl.glVertex3f(t.getX(c0), t.getY(c0) + 1f, t.getZ(c0) + 1f);
			}
		}
	}

	/** A gizmo arrow at its place and scale, in its vertices' colours. */
	public static void render(GL2 gl, CMVD arrow) {
		gl.glPushMatrix();
		gl.glTranslatef(arrow.worldLocX, arrow.worldLocY, arrow.worldLocZ);
		gl.glScalef(arrow.scale, arrow.scale, arrow.scale);
		gl.glDisable(GL2.GL_TEXTURE_2D);
		gl.glBegin(GL2.GL_TRIANGLES);
		for (int i = 0; i < arrow.faces.size(); i++) {
			int[] face = arrow.faces.get(i);
			for (int j = 0; j < 3; j++) {
				CMVD.CMVDVertex v = arrow.vertices.get(face[j]);
				gl.glColor3ub((byte) v.col.getRed(), (byte) v.col.getGreen(), (byte) v.col.getBlue());
				gl.glVertex3f(v.x, v.y, v.z);
			}
		}
		gl.glEnd();
		gl.glPopMatrix();
	}
}
