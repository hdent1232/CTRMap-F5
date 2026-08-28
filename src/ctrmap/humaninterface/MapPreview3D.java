package ctrmap.humaninterface;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.FPSAnimator;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DTexture;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;

/**
 * Renders a map REGION model with CTRMap's real 3D engine - the same mesh +
 * material + texture path the main map view uses - so the tile painter can show
 * how the painted map actually looks, not a top-down approximation. The camera
 * orbits over the ground (drag to rotate, wheel to zoom); the zone's decoded
 * world textures are bound to the model's materials by name, exactly like the
 * main view. This is a faithful render of the generated model (its real UVs,
 * textures and inherited vertex-color shading); the emulator is the final word.
 */
public class MapPreview3D extends GLJPanel implements GLEventListener {

	private H3DModel model;
	private final FPSAnimator animator;
	private float yaw = 0.6f;      // radians, orbit around Y
	private float pitch = 1.0f;    // radians, look-down angle (0 = horizon, PI/2 = straight down)
	private float dist = 1300f;    // camera distance (region is 720 units wide)
	private int lastX, lastY;
	// per-area fog (from AreaData) so the preview shows the zone's atmosphere
	private boolean fogOn = false;
	private float[] fogColor = {0.53f, 0.70f, 0.92f, 1f};
	private float fogNear = 800f, fogFar = 4000f;

	public MapPreview3D() {
		super(new GLCapabilities(GLProfile.get(GLProfile.GL2)));
		addGLEventListener(this);
		animator = new FPSAnimator(this, 40);
		animator.start();
		MouseAdapter ma = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				lastX = e.getX();
				lastY = e.getY();
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				yaw += (e.getX() - lastX) * 0.01f;
				pitch = clamp(pitch - (e.getY() - lastY) * 0.01f, 0.2f, 1.5f);
				lastX = e.getX();
				lastY = e.getY();
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				dist = clamp(dist * (e.getWheelRotation() > 0 ? 1.12f : 0.89f), 250f, 6000f);
			}
		};
		addMouseListener(ma);
		addMouseMotionListener(ma);
		addMouseWheelListener(ma);
	}

	/** Parses the region model bytes, binds the world textures, and shows it. */
	public void setRegion(byte[] modelBytes, List<H3DTexture> worldTextures) {
		try {
			BCHFile bch = new BCHFile(modelBytes);
			if (bch.errorlevel == 0 && !bch.models.isEmpty()) {
				H3DModel m = bch.models.get(0);
				if (worldTextures != null) {
					m.setMaterialTextures(worldTextures);
				}
				this.model = m;
			}
		} catch (Exception ex) {
			this.model = null;
		}
	}

	/** Enables the area's fog in the preview (color + near/far draw distance). */
	public void setFog(float r, float g, float b, float near, float far) {
		fogColor = new float[]{r, g, b, 1f};
		fogNear = near;
		fogFar = far;
		fogOn = far > near && far > 0;
	}

	public void stop() {
		if (animator.isStarted()) {
			animator.stop();
		}
	}

	@Override
	public void init(GLAutoDrawable d) {
		GL2 gl = d.getGL().getGL2();
		gl.glShadeModel(GL2.GL_SMOOTH);
		gl.glClearColor(0.53f, 0.70f, 0.92f, 1f); // sky
		gl.glClearDepth(1.0);
		gl.glEnable(GL2.GL_DEPTH_TEST);
		gl.glEnable(GL2.GL_TEXTURE_2D);
		gl.glDisable(GL2.GL_CULL_FACE);
		gl.glDepthFunc(GL2.GL_LEQUAL);
		gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
	}

	@Override
	public void display(GLAutoDrawable d) {
		GL2 gl = d.getGL().getGL2();
		if (fogOn) {
			gl.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f); // sky = fog color
			gl.glEnable(GL2.GL_FOG);
			gl.glFogi(GL2.GL_FOG_MODE, GL2.GL_LINEAR);
			gl.glFogfv(GL2.GL_FOG_COLOR, fogColor, 0);
			gl.glFogf(GL2.GL_FOG_START, fogNear);
			gl.glFogf(GL2.GL_FOG_END, fogFar);
		} else {
			gl.glClearColor(0.53f, 0.70f, 0.92f, 1f);
			gl.glDisable(GL2.GL_FOG);
		}
		gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
		gl.glMatrixMode(GL2.GL_MODELVIEW);
		gl.glLoadIdentity();
		// orbit camera over the ground centre (0,0,0); the region spans -360..360
		float ex = (float) (dist * Math.sin(yaw) * Math.cos(pitch));
		float ey = (float) (dist * Math.sin(pitch));
		float ez = (float) (dist * Math.cos(yaw) * Math.cos(pitch));
		new GLU().gluLookAt(ex, ey, ez, 0, 0, 0, 0, 1, 0);
		if (model != null) {
			if (model.meshes.size() > 0 && model.meshes.get(0).vbo == null) {
				model.makeAllBOs();
			}
			for (int i = 0; i < model.meshes.size(); i++) {
				model.meshes.get(i).uploadVBO(gl);
				model.meshes.get(i).render(gl, model.materials.size() > model.meshes.get(i).materialId
						? model.materials.get(model.meshes.get(i).materialId) : null);
			}
		}
		gl.glFlush();
	}

	@Override
	public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {
		GL2 gl = d.getGL().getGL2();
		if (h <= 0) {
			h = 1;
		}
		gl.glViewport(0, 0, w, h);
		gl.glMatrixMode(GL2.GL_PROJECTION);
		gl.glLoadIdentity();
		new GLU().gluPerspective(45.0, (float) w / h, 1.0, 15000.0);
		gl.glMatrixMode(GL2.GL_MODELVIEW);
		gl.glLoadIdentity();
	}

	@Override
	public void dispose(GLAutoDrawable d) {
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
