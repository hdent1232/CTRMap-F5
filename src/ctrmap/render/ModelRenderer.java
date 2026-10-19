package ctrmap.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.model.H3DVertex;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import java.nio.ByteBuffer;

/**
 * Draws BCH models with OpenGL: uploads a mesh's buffers and texture, draws it, frees them.
 *
 * <p>WHY IT IS HERE. The model reader drew itself - {@code H3DModel.render}, {@code H3DMesh.uploadVBO},
 * {@code BCHFile.render} - so a byte format could not be loaded without JOGL on the classpath, and the
 * format package sat in a cycle with OpenGL (F214). The drawing moved here unchanged, call for call;
 * OffscreenRenderTest holds every path it takes to the frames it drew before the move. What a mesh keeps
 * between frames - the GL names it was given, per context - stays on the mesh as plain ints.
 *
 * <p>AN OBJECT, NOT STATICS, so a view holds the one it draws with and a suite can count what a view asks
 * of it; it keeps no state of its own.
 *
 * <p>Not: {@link CollisionRenderer} - that draws a region's collision triangles and the move gizmo, which
 * are lists of coloured vertices with no buffers, materials or textures.
 */
public class ModelRenderer {

	/** Every model of the file. */
	public void render(GL2 gl, BCHFile file) {
		for (int i = 0; i < file.models.size(); i++) {
			render(gl, file.models.get(i));
		}
	}

	/** The model at its world place, turn and scale. */
	public void render(GL2 gl, H3DModel model) {
		gl.glPushMatrix();
		gl.glTranslatef(model.worldLocX, model.worldLocY, model.worldLocZ);
		gl.glRotatef(model.rotationZ, 0f, 0f, 1f);
		gl.glRotatef(model.rotationX, 1f, 0f, 0f);
		gl.glRotatef(model.rotationY, 0f, 1f, 0f);
		gl.glScalef(model.scaleX, model.scaleY, model.scaleZ);
		for (int i = 0; i < model.meshes.size(); i++) {
			render(gl, model.meshes.get(i), materialOf(model, i));
		}
		gl.glPopMatrix();
	}

	/** The material mesh {@code i} names, or null when the model has no such material. */
	public static H3DMaterial materialOf(H3DModel model, int i) {
		return (model.materials.size() > model.meshes.get(i).materialId) ? model.materials.get(model.meshes.get(i).materialId) : null;
	}

	/** The model's bounding box in red lines, as calculateBounds left it. */
	public void renderBox(GL2 gl, H3DModel model) {
		gl.glPushMatrix();
		gl.glTranslatef(model.worldLocX, model.worldLocY, model.worldLocZ);
		gl.glRotatef(model.rotationZ, 0f, 0f, 1f);
		gl.glRotatef(model.rotationX, 1f, 0f, 0f);
		gl.glRotatef(model.rotationY, 0f, 1f, 0f);
		gl.glScalef(model.scaleX, model.scaleY, model.scaleZ);
		gl.glDisable(GL2.GL_TEXTURE_2D);
		gl.glBegin(GL2.GL_LINES);

		gl.glColor3f(1f, 0f, 0f);

		for (int i = 0; i < model.boxVectors.length; i++) {
			gl.glVertex3fv(model.boxVectors[i], 0);
		}

		gl.glEnd();
		gl.glPopMatrix();
	}

	/** Every mesh's buffers into this context. */
	public void upload(GL2 gl, H3DModel model) {
		for (int i = 0; i < model.meshes.size(); i++) {
			upload(gl, model.meshes.get(i));
		}
	}

	/** Every mesh's buffers out of this context. */
	public void destroy(GL2 gl, H3DModel model) {
		for (int i = 0; i < model.meshes.size(); i++) {
			destroy(gl, model.meshes.get(i));
		}
	}

	/** The mesh's buffer and texture freed from this context. */
	public void destroy(GL2 gl, H3DModel.H3DMesh mesh) {
		if (mesh.glBufPtrs.get(gl) != null) {
			gl.glDeleteBuffers(1, mesh.glBufPtrs.get(gl), 0);
			gl.glDeleteTextures(1, mesh.glTexturePtrs.get(gl), 0);
			mesh.glBufPtrs.remove(gl);
			mesh.glTexturePtrs.remove(gl);
		}
	}

	/** The mesh's buffers and texture into this context, names made once per context and kept. */
	public void upload(GL2 gl, H3DModel.H3DMesh mesh) {
		if (mesh.vbo == null) {
			mesh.useVBO = false;
			return;
		}
		if (!mesh.glBufPtrs.containsKey(gl)) {
			int[] ptr = new int[1];
			gl.glGenBuffers(1, ptr, 0);
			mesh.glBufPtrs.put(gl, ptr);
		}
		if (!mesh.glTexturePtrs.containsKey(gl)) {
			int[] textureptr = new int[1];
			gl.glGenTextures(1, textureptr, 0);
			mesh.glTexturePtrs.put(gl, textureptr);
		}
		//THE NAMES ARE KEPT (F078): each upload deleted the buffer and texture it had been given
		//and then bound the deleted names; glBufferData and glTexImage2D below re-specify both
		int[] vboPtr = mesh.glBufPtrs.get(gl);
		long cap = mesh.rewound();
		gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vboPtr[0]);
		gl.glBufferData(GL2.GL_ARRAY_BUFFER, cap, null, GL2.GL_STATIC_DRAW);
		gl.glBufferSubData(GL2.GL_ARRAY_BUFFER, 0, mesh.vbo.capacity() * Float.BYTES, mesh.vbo);
		gl.glBufferSubData(GL2.GL_ARRAY_BUFFER, mesh.vbo.capacity() * Float.BYTES, mesh.vcbo.capacity(), mesh.vcbo);
		if (mesh.tcbo[0] != null) {
			gl.glBufferSubData(GL2.GL_ARRAY_BUFFER, mesh.vbo.capacity() * Float.BYTES + mesh.vcbo.capacity(), mesh.tcbo[0].capacity() * Double.BYTES, mesh.tcbo[0]);
		}
		gl.glBindTexture(GL2.GL_TEXTURE_2D, mesh.glTexturePtrs.get(gl)[0]);
		if (mesh.textureData != null) {
			gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA, mesh.textureWidth, mesh.textureHeight, 0, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, ByteBuffer.wrap(mesh.textureData));
		}
	}

	/** One mesh with its material, uploading it into this context first if it is not there yet. */
	public void render(GL2 gl, H3DModel.H3DMesh mesh, H3DMaterial mat) {
		/*
		Stuff involving checking for "blc" in material name is a workaround for tall grass rendering, which uses weird vertex coloring and alpha testing
		 */
		boolean mirrorx = false;
		if (!mesh.glTexturePtrs.containsKey(gl) || !mesh.glBufPtrs.containsKey(gl)) {
			upload(gl, mesh);
		}
		//A MESH WITH NOTHING TO TEXTURE IS DRAWN UNTEXTURED. Texturing stayed as the last draw left it,
		//so such a mesh sampled the previous mesh's texture, or - with none bound - a texture OpenGL
		//leaves undefined: measured 2026-10-10, an untextured map model gave 3 different frames in 5
		//runs (OffscreenRenderTest). A mesh that has a texture enables texturing itself, below.
		boolean textured = mat != null && !(mat.texture0 == null && mat.texture1 == null && mat.texture2 == null)
				&& mesh.textureData != null;
		if (!textured) {
			gl.glDisable(GL2.GL_TEXTURE_2D);
		}
		if (textured) {
			{
				gl.glEnable(GL2.GL_TEXTURE_2D);
				gl.glBindTexture(GL2.GL_TEXTURE_2D, mesh.glTexturePtrs.get(gl)[0]);
				int twX = glWrap(mat.mappers[mesh.texindex].wrapU);
				if (twX == GL2.GL_MIRRORED_REPEAT) {
					mirrorx = true;
				}
				int twY = glWrap(mat.mappers[mesh.texindex].wrapV);
				gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, twX);
				gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, twY);
				gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
				gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
				if (mat.params.blendop.mode == H3DMaterial.BlendOperation.BlendMode.blend) {
					gl.glBlendFunc(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA); //forces to 1-a to prevent drunk framebuffer. renders fine.
					gl.glBlendEquation(GL2.GL_FUNC_ADD);
					gl.glEnable(GL2.GL_BLEND);
				}
				int alpha = alphaFunction(mat);
				if (alpha == NO_ALPHA_TEST) {
					gl.glDisable(GL2.GL_ALPHA_TEST);
				} else {
					gl.glAlphaFunc(alpha, 0); //forcing to 0 prevents some meshes not appearing, with references like 58 etc. no glitches afaik caused by this.
					gl.glEnable(GL2.GL_ALPHA_TEST);
				}
			}
		}

		if (mesh.useVBO) {
			gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, mesh.glBufPtrs.get(gl)[0]);
			gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
			gl.glEnableClientState(GL2.GL_COLOR_ARRAY);

			gl.glVertexPointer(3, GL2.GL_FLOAT, 0, 0);
			gl.glColorPointer(4, GL2.GL_UNSIGNED_BYTE, 0, mesh.vbo.capacity() * Float.BYTES);

			if (mesh.tcbo[0] != null) {
				gl.glEnableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
				gl.glTexCoordPointer(2, GL2.GL_DOUBLE, 0, mesh.vbo.capacity() * Float.BYTES + mesh.vcbo.capacity());
			}

			gl.glDrawArrays(GL2.GL_TRIANGLES, 0, mesh.vbo.capacity() / 3);
			gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);  // disable vertex arrays
			gl.glDisableClientState(GL2.GL_COLOR_ARRAY);
			gl.glDisableClientState(GL2.GL_TEXTURE_COORD_ARRAY);
		} else {
			gl.glBegin(GL2.GL_TRIANGLES);
			for (int i = 0; i < mesh.vertices.size(); i++) {
				H3DVertex v = mesh.vertices.get(i);
				if (mesh.vertexColoured(mat)) {
					gl.glColor4ub((byte) (v.diffuseColor >> 16 & 0xFF), (byte) (v.diffuseColor >> 8 & 0xFF), (byte) (v.diffuseColor & 0xFF), (byte) (v.diffuseColor >> 24 & 0xFF));
				} else {
					gl.glColor3f(1f, 1f, 1f);
				}
				if (mat != null) {
					gl.glTexCoord2d((mirrorx ? 1 : 0) - v.texture0.x, v.texture0.y);
				}
				gl.glVertex3f(v.position.x, v.position.y, v.position.z);
			}
			gl.glEnd();
		}
	}

	/** The GL wrap mode a BCH wrap mode draws as; repeat for one the port did not list. */
	public static int glWrap(H3DMaterial.TextureMapper.TextureWrap wrap) {
		switch (wrap) {
			default:
			case repeat:
				return GL2.GL_REPEAT;
			case mirroredRepeat:
				return GL2.GL_MIRRORED_REPEAT;
			case clampToEdge:
				return GL2.GL_CLAMP_TO_EDGE;
			case clampToBorder:
				return GL2.GL_CLAMP_TO_BORDER;
		}
	}

	/** What {@link #alphaFunction} answers for a material drawn with no alpha test. */
	public static final int NO_ALPHA_TEST = -1;

	/**
	 * The GL alpha function a textured mesh's material is drawn with, against a reference of 0, or
	 * {@link #NO_ALPHA_TEST}: the material's own test, except that a "blc" material and the tall grass
	 * ("enc_grass") drop exactly their transparent texels. Apart from the draw so a suite can ask it: the
	 * 2026-10-15 shared sweep (ModelRenderer 174, negate-if) SURVIVED, and no retail frame could tell - no
	 * region or building material names "blc", and all 7 tall-grass ones already test GREATER 0 (measured
	 * over the dump, 12,481 region and 745 building materials) - while inverted it alpha-tested every other
	 * material, dropping texels a material without a test draws.
	 */
	public static int alphaFunction(H3DMaterial mat) {
		int func = NO_ALPHA_TEST;
		if (mat.params.alphaTest.isTestEnabled && !mat.name.contains("blc")) {
			func = glTestFunc(mat.params.alphaTest);
		}
		if (mat.name.contains("blc") || mat.name0.equals("enc_grass")) {
			func = GL.GL_NOTEQUAL;
		}
		return func;
	}

	/** The GL alpha function a BCH alpha test draws as. */
	public static int glTestFunc(H3DMaterial.AlphaTest test) {
		switch (test.testFunction) {
			case never:
				return GL2.GL_NEVER;
			case always:
				return GL2.GL_ALWAYS;
			case equal:
				return GL2.GL_EQUAL;
			case notEqual:
				return GL2.GL_NOTEQUAL;
			case less:
				return GL2.GL_LESS;
			case lessOrEqual:
				return GL2.GL_LEQUAL;
			case greater:
				return GL2.GL_GREATER;
			case greaterOrEqual:
				return GL2.GL_GEQUAL;
			default:
				return GL2.GL_ALWAYS;
		}
	}
}
