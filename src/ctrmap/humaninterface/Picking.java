package ctrmap.humaninterface;

import com.jogamp.opengl.glu.gl2.GLUgl2;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.model.H3DVertex;
import ctrmap.formats.vectors.Vec3f;
import java.awt.Component;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Deciding what the mouse is over in the 3D view: a box projected to the
 * screen, a distance, a rotation.
 *
 * <p>These were part of {@code ctrmap.Utils}, which meant the format layer
 * depended on a class that pulls in JOGL and AWT. They need a viewport and a
 * projection, so they live with the view.
 */
public final class Picking {

	private Picking() {
	}

	/**
	 * True when the mouse is inside the projection of a box: each of its
	 * corners is scaled, rotated and projected to window coordinates, and the
	 * quads are tested against the pointer.
	 */
	public static boolean isBoxSelected(float[][] box, MouseEvent e, Component parent, Vec3f position, Vec3f scale, Vec3f rotate, float[] mvMatrix, float[] projMatrix, int[] view) {
		GLUgl2 glu = new GLUgl2();
		float[][] winPosArray = new float[box.length][3];
		for (int j = 0; j < box.length; j++) {
			Vec3f at = place(box[j][0], box[j][1], box[j][2], position, scale, rotate);
			glu.gluProject(at.x, at.y, at.z, mvMatrix, 0, projMatrix, 0, view, 0, winPosArray[j], 0);
			winPosArray[j][1] = parent.getHeight() - winPosArray[j][1];
		}
		for (int j = 0; j < winPosArray.length; j += 4) {
			Polygon polygon = new Polygon();
			List<Point> pts = new ArrayList<>();
			for (int k = 0; k < 4; k++) {
				pts.add(new Point((int) winPosArray[j + k][0], (int) winPosArray[j + k][1]));
			}
			for (int k = 0; k < 4; k++) {
				polygon.addPoint(pts.get(k).x, pts.get(k).y);
			}
			if (polygon.contains(e.getPoint())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a click picks a model: its selection box, placed as the model is, contains the click,
	 * AND some vertex of the model, placed the same way, lands inside the panel. The second half is a
	 * workaround - at some camera angles the projected box contains a click on an object the camera
	 * cannot see - and it was pasted into the NPC form and the prop form instead of living here (F113).
	 * Both copies placed the vertices without the model's rotation, and the prop copy without its
	 * scale, though each handed both to the box test; here box and vertices go through {@link #place}.
	 */
	public static boolean isPicked(float[][] box, List<H3DModel.H3DMesh> meshes, MouseEvent e, Component parent,
			Vec3f position, Vec3f scale, Vec3f rotate, float[] mvMatrix, float[] projMatrix, int[] view) {
		return isBoxSelected(box, e, parent, position, scale, rotate, mvMatrix, projMatrix, view)
				&& isSeen(meshes, parent, position, scale, rotate, mvMatrix, projMatrix, view);
	}

	/** Whether any vertex of the meshes, placed by {@link #place}, projects inside the panel. */
	public static boolean isSeen(List<H3DModel.H3DMesh> meshes, Component parent, Vec3f position, Vec3f scale,
			Vec3f rotate, float[] mvMatrix, float[] projMatrix, int[] view) {
		GLUgl2 glu = new GLUgl2();
		float[] win = new float[3];
		for (H3DModel.H3DMesh mesh : meshes) {
			for (H3DVertex v : mesh.vertices) {
				Vec3f at = place(v.position.x, v.position.y, v.position.z, position, scale, rotate);
				glu.gluProject(at.x, at.y, at.z, mvMatrix, 0, projMatrix, 0, view, 0, win, 0);
				if (win[0] > 0 && win[0] < parent.getWidth() && win[1] > 0 && win[1] < parent.getHeight()) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A model-space point placed in the world as a model is: scaled, turned about Y, then X, then Z
	 * (degrees), then moved to its position. The selection box and the model's own vertices are both
	 * placed by this, so the two halves of {@link #isPicked} agree on where the model is.
	 */
	public static Vec3f place(float x, float y, float z, Vec3f position, Vec3f scale, Vec3f rotate) {
		Vec3f turned = noGlRotatef(noGlRotatef(noGlRotatef(new Vec3f(x * scale.x, y * scale.y, z * scale.z),
				new Vec3f(0f, 1f, 0f), Math.toRadians(rotate.y)),
				new Vec3f(1f, 0f, 0f), Math.toRadians(rotate.x)),
				new Vec3f(0f, 0f, 1f), Math.toRadians(rotate.z));
		return new Vec3f(turned.x + position.x, turned.y + position.y, turned.z + position.z);
	}

	/**
	 * How far {@code point} is from the camera, given the scene's translation - which is the
	 * NEGATIVE of the camera's position, so the sum is the camera-to-point vector (F250). It was
	 * called getDistanceFromVector and pinned by a suite as a suspected defect for adding where a
	 * distance between two points subtracts; the arithmetic was right and the name was not.
	 */
	public static double cameraDistance(Vec3f point, Vec3f sceneTranslation) {
		double x = point.x + sceneTranslation.x;
		double y = point.y + sceneTranslation.y;
		double z = point.z + sceneTranslation.z;
		return Math.sqrt(x * x + y * y + z * z);
	}

	/*
	From: https://stackoverflow.com/questions/31225062/rotating-a-vector-by-angle-and-axis-in-java
	 */
	public static Vec3f noGlRotatef(Vec3f vec, Vec3f axis, double theta) {
		float x, y, z;
		float u, v, w;
		x = vec.x;
		y = vec.y;
		z = vec.z;
		u = axis.x;
		v = axis.y;
		w = axis.z;
		float xPrime = (float) (u * (u * x + v * y + w * z) * (1d - Math.cos(theta))
				+ x * Math.cos(theta)
				+ (-w * y + v * z) * Math.sin(theta));
		float yPrime = (float) (v * (u * x + v * y + w * z) * (1d - Math.cos(theta))
				+ y * Math.cos(theta)
				+ (w * x - u * z) * Math.sin(theta));
		float zPrime = (float) (w * (u * x + v * y + w * z) * (1d - Math.cos(theta))
				+ z * Math.cos(theta)
				+ (-v * x + u * y) * Math.sin(theta));
		return new Vec3f(xPrime, yPrime, zPrime);
	}
}
