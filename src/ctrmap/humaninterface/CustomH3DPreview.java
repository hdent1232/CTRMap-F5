package ctrmap.humaninterface;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.glu.GLU;
import ctrmap.formats.h3d.model.H3DModel;
import java.awt.BorderLayout;
import java.awt.Component;
import javax.swing.JPanel;

/**
 * Used for showing single H3D models in a window
 *
 * <p>A HOST, NOT THE GL SURFACE (F077). It was a GLJPanel, so building one asked OpenGL to start, and
 * on a machine where it cannot that was an UnsatisfiedLinkError out of the constructor. Three of the
 * places that build one are NetBeans-generated blocks - the prop form, both registry editors - so the
 * prop form, and the main window that builds it, could not open at all. The host builds the surface
 * only where {@link GlSupport#available} says it can, and holds a placeholder saying why where not.
 */
public class CustomH3DPreview extends JPanel implements GLEventListener {

	private static final long serialVersionUID = -6824913720967100278L;

	private H3DModel model;
	/** Null when OpenGL cannot start: there is nothing to loop over. */
	private final ShownLoop animator;

	/** The model whose buffers are in the GL context below, or null: uploaded once, not every frame (F098). */
	private H3DModel uploaded;
	private Object uploadedIn;
	/** Set by loadModel: the model handed is uploaded again on the next frame, in case it changed. */
	private boolean reupload;

	public CustomH3DPreview() {
		super(new BorderLayout());
		if (GlSupport.available()) {
			GLJPanel surface = new GLJPanel(new GLCapabilities(GLProfile.get(GLProfile.GL2)));
			surface.addGLEventListener(this);
			add(surface, BorderLayout.CENTER);
			animator = ShownLoop.of(surface, 60);   //while it is seen (F098)
		} else {
			add(GlSupport.placeholder("The model preview"), BorderLayout.CENTER);
			animator = null;
		}
	}

	/**
	 * What this holds fills it, whatever layout it was given: the generated forms set a GroupLayout on
	 * it holding nothing but a size, and under that the surface would be laid out at 0 by 0.
	 */
	@Override
	public void doLayout() {
		super.doLayout();
		for (Component held : getComponents()) {
			held.setBounds(0, 0, getWidth(), getHeight());
		}
	}

	/**
	 * Stops the render loop. Call this when a transient preview (e.g. one placed
	 * in a modal dialog) is no longer needed, so its animator thread and GL
	 * context are released instead of leaking.
	 */
	public void stop() {
		if (animator != null) {
			animator.end();
		}
	}

	@Override
	public void init(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();
		gl.glShadeModel(GL2.GL_SMOOTH);
		gl.glClearColor(0f, 0f, 0f, 0f);
		gl.glClearDepth(1.0f);
		gl.glEnable(GL2.GL_DEPTH_TEST);
		gl.glEnable(GL2.GL_TEXTURE_2D);
		gl.glDisable(GL2.GL_CULL_FACE);
		gl.glDepthFunc(GL2.GL_LEQUAL);
		gl.glHint(GL2.GL_PERSPECTIVE_CORRECTION_HINT, GL2.GL_NICEST);
		uploaded = null;   //a new context holds nothing yet
	}

	public void loadModel(H3DModel model) {
		this.model = model;
		reupload = true;
	}

	@Override
	public void dispose(GLAutoDrawable drawable) {
		release(drawable.getGL().getGL2());
	}

	/** Frees the shown model's buffers from the context going away. */
	public void release(GL2 gl) {
		if (uploaded != null) {
			uploaded.destroyAllBOs(gl);
		}
		uploaded = null;
	}

	@Override
	public void display(GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();
		gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
		gl.glLoadIdentity();
		if (model != null) {
			float maxTransl0 = Math.max(0.5f * model.maxVector.x - model.minVector.x, 0.5f * model.maxVector.y - model.minVector.y);
			gl.glTranslatef((model.minVector.x + model.maxVector.x) / -2f, -model.maxVector.y / 2f, -maxTransl0 * 3f - 5f);
//			gl.glRotatef(90f, 0.0f, 1.0f, 0.0f);

			drawModel(gl, drawable.getContext());
		}
		gl.glFlush();
	}

	/**
	 * Draws the model, uploading it first when this context does not hold it yet.
	 *
	 * <p>ONCE PER MODEL AND CONTEXT, NOT EVERY FRAME (F098): display() deleted and re-uploaded every
	 * buffer sixty times a second. The buffers are kept per GL context on the mesh, so a model shown
	 * in two previews is uploaded once into each; a model handed in again is uploaded again, in case
	 * it changed; and one switched away from is freed from the context it was in.
	 */
	public void drawModel(GL2 gl, Object context) {
		if (reupload || uploaded != model || uploadedIn != context) {
			if (uploaded != null && uploaded != model && uploadedIn == context) {
				uploaded.destroyAllBOs(gl);
			}
			if (model.meshes.size() > 0 && model.meshes.get(0).vbo == null) {
				model.makeAllBOs();
			}
			for (int i = 0; i < model.meshes.size(); i++) {
				model.meshes.get(i).uploadVBO(gl);
			}
			uploaded = model;
			uploadedIn = context;
			reupload = false;
		}
		for (int i = 0; i < model.meshes.size(); i++) { //direct rendering to bypass translation
			model.meshes.get(i).render(gl, (model.materials.size() > model.meshes.get(i).materialId) ? model.materials.get(model.meshes.get(i).materialId) : null);
		}
	}

	@Override
	public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
		GL2 gl = drawable.getGL().getGL2();
		if (height <= 0) {
			height = 1;
		}
		float h = (float) width / (float) height;
		gl.glViewport(0, 0, width, height);
		gl.glMatrixMode(GL2.GL_PROJECTION);
		gl.glLoadIdentity();

		new GLU().gluPerspective(45.0f, h, 1f, 15000.0);
		gl.glMatrixMode(GL2.GL_MODELVIEW);
		gl.glLoadIdentity();
	}

}
