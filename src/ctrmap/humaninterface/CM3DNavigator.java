package ctrmap.humaninterface;

import com.jogamp.opengl.GL2;
import ctrmap.formats.CMVD;
import ctrmap.resources.ResourceAccess;
import java.io.IOException;
import ctrmap.formats.MapObject;

/**
 * Used to move models around the world visually.
 */
public class CM3DNavigator {

	public CMVD ax;
	public CMVD ay;
	public CMVD az;

	public float scale = 1f;

	public MapObject target;
	private H3DRenderingPanel parent;
	public double distFromTarget = 0;
	public TargetAxis targetAxis = TargetAxis.NULL;

	public CM3DNavigator(H3DRenderingPanel parent) {
		this.parent = parent;
		ax = arrow("MoveArrowX.cmvd");
		ay = arrow("MoveArrowY.cmvd");
		az = arrow("MoveArrowZ.cmvd");
	}

	/**
	 * One of the gizmo's bundled arrows, its stream closed (F130): a missing or damaged one was
	 * logged and left null - a NullPointerException at the next bind - or read as an empty mesh,
	 * an arrow nobody could see. Each ships in the build, so failing to read one is said as that.
	 */
	private static CMVD arrow(String name) {
		try (java.io.InputStream in = ResourceAccess.getStream(name)) {
			return new CMVD(in);
		} catch (IOException ex) {
			throw new IllegalStateException("the move gizmo's arrow " + name + " built into the program could not be"
					+ " read: " + ctrmap.util.Bytes.reason(ex), ex);
		}
	}

	public void bindModel(MapObject m) {
		target = m;
		synchronizeNavi();
	}

	public void synchronizeNavi() {
		if (target == null) {
			return;
		}
		ax.worldLocX = target.getX();
		ax.worldLocZ = target.getZ();
		ax.worldLocY = target.getY();
		ay.worldLocX = target.getX();
		ay.worldLocZ = target.getZ();
		ay.worldLocY = target.getY();
		az.worldLocX = target.getX();
		az.worldLocZ = target.getZ();
		az.worldLocY = target.getY();
	}

	public void renderNavigator(GL2 gl) {
		if (target == null) {
			return;
		}
		measure(parent.translateX, parent.translateY, parent.translateZ);
		ax.render(gl);
		ay.render(gl);
		az.render(gl);
	}

	/**
	 * Moves the target along the held axis by a mouse drag of (dx, dy) pixels, seen from a view
	 * turned (rotateX, rotateY) degrees, and answers whether it moved (F148): the 3D view tells the
	 * tool in hand only then. The step scales with the gizmo's distance from the camera.
	 */
	boolean drag(float rotateX, float rotateY, int dx, int dy) {
		float x = target.getX();
		float y = target.getY();
		float z = target.getZ();
		double xAddition;
		double yAddition;
		switch (targetAxis) {
			case X:
				xAddition = Math.cos(Math.toRadians(rotateY)) * dx;
				yAddition = Math.cos(Math.toRadians(rotateY + 90f)) * dy;
				target.setX(target.getX() + (float) (distFromTarget / 1200f * (xAddition + yAddition)));
				break;
			case Z:
				xAddition = Math.cos(Math.toRadians(rotateY - 90f)) * dx;
				yAddition = Math.cos(Math.toRadians(rotateY)) * dy;
				target.setZ(target.getZ() + (float) (distFromTarget / 1200f * (xAddition + yAddition)));
				break;
			case Y:
				yAddition = Math.cos(Math.toRadians(rotateX)) * dy;
				target.setY(target.getY() + (float) (distFromTarget / 1200f * (-yAddition)));
				break;
			default:
				break;
		}
		synchronizeNavi();
		return target.getX() != x || target.getY() != y || target.getZ() != z;
	}

	/**
	 * The gizmo's distance from a camera the view has translated by (tx, ty, tz) - the camera stands
	 * at minus that - and the arrow scale it sets; the drag step reads the same distance (F244).
	 * Apart from the draw so that it can be asked without a GL context.
	 */
	void measure(float tx, float ty, float tz) {
		distFromTarget = distance(new float[]{target.getX(), target.getY(), target.getZ()}, new float[]{-tx, -ty, -tz});
		scale = (float) (distFromTarget / 1000d);
		ax.scale = scale;
		ay.scale = scale;
		az.scale = scale;
	}

	/**
	 * How far apart two points are: the length of their difference (F244). This was the root of
	 * the summed squares of |a| - |b| per axis, which is the distance only while both points sit on
	 * the same side of every axis - an object at x = +300 and a camera at x = -300 counted 0 on
	 * that axis instead of 600, and the gizmo shrank and dragging slowed in proportion.
	 */
	static double distance(float[] a, float[] b) {
		double dx = a[0] - b[0];
		double dy = a[1] - b[1];
		double dz = a[2] - b[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public enum TargetAxis {
		NULL,
		X,
		Y,
		Z
	}
}
