package ctrmap.formats;

import com.jogamp.opengl.GL2;
import ctrmap.LittleEndianDataOutputStream;
import java.awt.Polygon;
import java.awt.geom.Line2D;
import java.io.IOException;
import java.util.Random;

/**
 * Class used for storing vertex data in OOP triangles, mainly used for accessibility in the Collision editor (as it edits them at quite a low level).
 */
public class Triangle {
    public float[] x = new float[3];
    public float[] y = new float[3];
    public float[] z = new float[3];
    private boolean selected = false;
    public Triangle(float[] x, float[] y, float[] z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }
    public void setSelected(boolean b) {
        selected = b;
    }
    public void setX(int vertex, float value) {
        x[vertex] = value;
    }
    public void setY(int vertex, float value) {
        y[vertex] = value;
    }
    public void setZ(int vertex, float value) {
        z[vertex] = value;
    }
    public float getX(int vertex) {
        return x[vertex];
    }
    public float getY(int vertex) {
        return y[vertex];
    }
    public float getZ(int vertex) {
        return z[vertex];
    }
    public void write(LittleEndianDataOutputStream dos) {
        try {
            for (int i = 0; i < x.length; i++) {
                dos.writeFloat(x[i]);
                dos.writeFloat(y[i]);
                dos.writeFloat(z[i]);
                dos.writeFloat(1);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public Line2D getLine(int num){
        return new Line2D.Float(x[num], z[num], x[(num == 2) ? 0 : num + 1], z[(num == 2) ? 0 : num + 1]);
    }
	public Polygon getAWTPoly(){
		return new Polygon(new int[]{(int)x[0], (int)x[1], (int)x[2]}, new int[]{(int)z[0], (int)z[1], (int)z[2]}, 3);
	}
	/**
	 * The colour a height draws in: the three floats {@code new Random(height)} answers first, made
	 * once per height. render() built that Random for every triangle on every frame (F099) - a few
	 * thousand per frame for one region - for a colour that depends on nothing but the height.
	 */
	public static float[] heightColour(long height) {
		return HEIGHT_COLOURS.computeIfAbsent(height, h -> {
			Random r = new Random(h);
			return new float[]{r.nextFloat(), r.nextFloat(), r.nextFloat()};
		});
	}

	private static final java.util.Map<Long, float[]> HEIGHT_COLOURS = new java.util.concurrent.ConcurrentHashMap<>();

    public void render(GL2 gl) {
        float averageheight = (Math.max(y[0], Math.max(y[1], y[2])) + Math.min(y[0], Math.min(y[1], y[2])))/2f;
        float[] colour = heightColour((long) averageheight);
        gl.glColor3f(colour[0], colour[1], colour[2]);
        for (int i = 0; i < 3; i++) {
            gl.glVertex3f(x[i], y[i], z[i]);
        }
        if (selected){
            gl.glColor3f(1f, 1f, 1f);
            for (int i = 0; i < 3; i++){
                int c0 = i;
                int c1 = (i + 1 == 3) ? 0 : i + 1;
                gl.glVertex3f(x[c0], y[c0] + 1f, z[c0]);
                gl.glVertex3f(x[c1], y[c1] + 1f, z[c1]);
                gl.glVertex3f(x[c1], y[c1] + 1f, z[c1] + 1f);
                gl.glVertex3f(x[c0], y[c0] + 1f, z[c0]);
                gl.glVertex3f(x[c1], y[c1] + 1f, z[c1] + 1f);
                gl.glVertex3f(x[c0], y[c0] + 1f, z[c0] + 1f);
            }
        }
    }
}
