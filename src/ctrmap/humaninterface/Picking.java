package ctrmap.humaninterface;

import com.jogamp.opengl.glu.gl2.GLUgl2;
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
			Vec3f vec = new Vec3f(box[j][0] * scale.x, box[j][1] * scale.y, box[j][2] * scale.z);
			Vec3f rotatedVec = noGlRotatef(noGlRotatef(noGlRotatef(vec,
					new Vec3f(0f, 1f, 0f), Math.toRadians(rotate.y)),
					new Vec3f(1f, 0f, 0f), Math.toRadians(rotate.x)),
					new Vec3f(0f, 0f, 1f), Math.toRadians(rotate.z));
			glu.gluProject(rotatedVec.x + position.x, rotatedVec.y + position.y, rotatedVec.z + position.z, mvMatrix, 0, projMatrix, 0, view, 0, winPosArray[j], 0);
			winPosArray[j][1] = parent.getHeight() - winPosArray[j][1];
		}
		List<Polygon> polys = new ArrayList<>();
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
				//GLU is buggy and sometimes completely fucks up the maths in certain camera angles. We can work around this by checking if the actual object is seen by the camera.
				return true;
			}
		}
		return false;
	}

	/** The distance between two points, both offset from the origin the caller has in mind. */
	public static double getDistanceFromVector(Vec3f loc, Vec3f comp) {
		double dist = Math.pow((Math.pow(loc.x + comp.x, 2)
				+ Math.pow(loc.y + comp.y, 2)
				+ Math.pow(loc.z + comp.z, 2)
				* 1.0), 0.5);
		return Math.abs(dist);
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
