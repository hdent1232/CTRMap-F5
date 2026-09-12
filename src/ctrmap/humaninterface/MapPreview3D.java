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
	/**
	 * The smallest buffer a BCH header can occupy: the 4-byte magic, two
	 * compatibility bytes, a version short, and the ten ints of offsets and lengths
	 * that follow. Anything shorter cannot be read, let alone refused.
	 */
	public static final int BCH_MIN_HEADER = 0x30;

	/** Models swapped out, waiting for a GL thread to free their buffers. */
	private final java.util.List<H3DModel> retired = new java.util.ArrayList<>();

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
	/**
	 * The model in a region's bytes, or NULL when there is not one.
	 *
	 * <p>Its own method, and free of any GL, so a suite can ask the question that
	 * matters - does undecodable input answer "nothing"? - without a graphics
	 * context. The bug this replaces was not in the decoding but in what was done
	 * with a failure, and the only way to check that is to be able to produce one.
	 */
	public static H3DModel decode(byte[] modelBytes, List<H3DTexture> worldTextures) {
		//REFUSED BEFORE IT IS PARSED. BCHFile checks the magic and returns - but only
		//AFTER reading a string and eleven ints out of the buffer, so bytes too short
		//to hold a header never reach the check. Measured: decode(new byte[]{1,2,3,4})
		//and decode(new byte[0]) both die with OutOfMemoryError, which is an Error and
		//not caught below - so one truncated region takes the editor with it, from a
		//dialog whose whole job is to look at regions one after another.
		if (!looksLikeBch(modelBytes)) {
			return null;
		}
		try {
			BCHFile bch = new BCHFile(modelBytes);
			if (bch.errorlevel != 0 || bch.models.isEmpty()) {
				return null;
			}
			H3DModel m = bch.models.get(0);
			if (worldTextures != null) {
				m.setMaterialTextures(worldTextures);
			}
			return m;
		//NO CATCH ON Error HERE, deliberately. A draft had one, reasoning that a
		//buffer could pass the header check and then ask for an absurd allocation
		//deeper in - but no input could be built that reached it, and the plant for it
		//survived twice. Every buffer that actually blew up was one the check above
		//refuses. Catching an Error on a guess is worse than not catching one: it
		//turns an unproven fear into code nobody can test or remove.
		} catch (Exception notAModel) {
			return null;
		}
	}

	/**
	 * Whether these bytes can even be a BCH: the magic, and enough length for the
	 * header {@link BCHFile} reads before it is able to refuse one.
	 */
	public static boolean looksLikeBch(byte[] b) {
		return b != null && b.length >= BCH_MIN_HEADER
			&& b[0] == 'B' && b[1] == 'C' && b[2] == 'H' && b[3] == 0;
	}
	/**
	 * Shows a region's map model. Answers FALSE when there was nothing to show,
	 * and shows nothing - it does not keep what was there before.
	 *
	 * <p>IT USED TO KEEP THE PREVIOUS MODEL. The decode was guarded by
	 * {@code if (errorlevel == 0 && !models.isEmpty())} with no else, so a region
	 * that would not decode left the last one on screen. In a palette that is a
	 * cosmetic oddity; in a zone browser it is a lie - you click zone 214, the
	 * decode fails, and you are looking at zone 213 with 214's name under it. The
	 * caller is told so it can say which, because "blank" is honest and "the one
	 * before" is not.
	 *
	 * <p>THE MODEL IT REPLACES IS RETIRED, NOT DROPPED. Each model uploads vertex
	 * buffers on first draw and {@link H3DModel#destroyAllBOs} frees them; nothing
	 * called it, so every swap leaked a map's worth of GPU buffers - unnoticeable
	 * in a dialog opened once, and a browser is a dialog you scroll. They cannot be
	 * freed here (this runs on the caller's thread, and GL belongs to the render
	 * thread), so they are queued for the next {@code display}.
	 *
	 * @return true when there is now geometry on screen
	 */
	public boolean setRegion(byte[] modelBytes, List<H3DTexture> worldTextures) {
		H3DModel next = decode(modelBytes, worldTextures);
		synchronized (this) {
			if (model != null && model != next) {
				retired.add(model);
			}
			model = next;
		}
		repaint();
		return next != null;
	}

	/** Enables the area's fog in the preview (color + near/far draw distance). */
	public void setFog(float r, float g, float b, float near, float far) {
		fogColor = new float[]{r, g, b, 1f};
		fogNear = near;
		fogFar = far;
		fogOn = far > near && far > 0;
	}

	/**
	 * Back to the plain sky backdrop, for an area that draws NO fog at the time
	 * of day being previewed - which most routes do not, at night.
	 */
	public void clearFog() {
		fogOn = false;
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
		//Cull back faces, because the 3DS does. Drawing them made this preview
		//lie in the one way that matters: map geometry is single-sided, so with
		//culling off you see the INSIDE of every cliff wall - dark, mirrored
		//faces that the hardware discards. They read as untextured slabs and
		//sent a long hunt after a texture bug that did not exist.
		//Measured on retail maps this costs 0.01-0.07% of the frame, so retail
		//content is wound consistently and loses nothing worth seeing; geometry
		//that does vanish here is geometry that will vanish in game, which is
		//exactly what a preview is for.
		gl.glEnable(GL2.GL_CULL_FACE);
		gl.glCullFace(GL2.GL_BACK);
		gl.glFrontFace(GL2.GL_CCW);
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
		//free what a previous swap left behind, here, where there is a GL context
		synchronized (this) {
			for (H3DModel old : retired) {
				try {
					old.destroyAllBOs(gl);
				} catch (RuntimeException alreadyGone) {
					//a model that never drew has nothing to free
				}
			}
			retired.clear();
		}
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
		//the dialog is closing and this is the last moment the context exists
		GL2 gl = d.getGL().getGL2();
		synchronized (this) {
			retired.add(model);
			for (H3DModel old : retired) {
				if (old == null) {
					continue;
				}
				try {
					old.destroyAllBOs(gl);
				} catch (RuntimeException alreadyGone) {
					//nothing to free
				}
			}
			retired.clear();
			model = null;
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
