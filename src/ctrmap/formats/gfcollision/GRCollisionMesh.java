package ctrmap.formats.gfcollision;

import com.jogamp.opengl.GL2;

import ctrmap.formats.Triangle;
import java.util.ArrayList;

public class GRCollisionMesh {

	public ArrayList<Triangle> tris = new ArrayList<>();

	public GRCollisionMesh() {
	}

	public void render(GL2 gl) {
		for (int i = 0; i < tris.size(); i++) {
			tris.get(i).render(gl);
		}
	}
}
