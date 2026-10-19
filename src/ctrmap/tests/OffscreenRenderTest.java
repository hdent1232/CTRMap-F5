package ctrmap.tests;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import ctrmap.formats.CMVD;
import ctrmap.formats.containers.ContainerBytes;
import ctrmap.formats.containers.GR;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.gfcollision.GRCollisionFile;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.humaninterface.GlSupport;
import ctrmap.render.CollisionRenderer;
import ctrmap.render.ModelRenderer;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the editor's 3D surfaces draw, pixel for pixel, read back from an offscreen frame (F214).
 *
 * <p>WHY THIS SUITE EXISTS. Seven format classes draw themselves with OpenGL, so a byte format cannot be
 * loaded without JOGL on the classpath, and moving the drawing out of them was left undone because
 * nothing here could see what it drew. OpenGL starts on the owner's machine, so this draws the paths the
 * previews take - a map model immediate-mode and from buffers, a textured building with its bounding
 * box, a textured NPC model, a building drawn again after its buffers were freed, a region's collision
 * with one triangle selected, and the move gizmo's arrows - into an offscreen frame with the previews'
 * own GL state, and digests what came back.
 *
 * <p>A DIGEST IS A FACT ABOUT ONE RENDERER. Driver and GPU decide the pixels, so the record carries the
 * GL_RENDERER and GL_VERSION it was taken on, and a machine whose renderer is another prints its digests
 * and says the comparison is UNKNOWN there - never a pass. Each frame must also have drawn something: a
 * frame of nothing but the clear colour would digest the same before and after any change.
 *
 * <p>ORDER: needs the pristine dump's a/0/3/9, a/0/2/3 and a/0/2/1, and a machine where OpenGL starts.
 *
 * Usage: java ctrmap.tests.OffscreenRenderTest &lt;pristine-dump-root&gt;
 */
public class OffscreenRenderTest {

	static final int SIZE = 256;

	/**
	 * Where the digests below were measured: 2026-10-10, before the drawing left the format classes (F214),
	 * every one of them unchanged by that move. Two re-measured the same day when a mesh with nothing to
	 * texture began to be drawn untextured: the untextured map model, which had been undefined (3 digests
	 * in 5 runs, its meshes drawn under an enabled texture unit with nothing bound), and the textured map
	 * model, 7 of whose 19 meshes resolve no texture from area 9 and had each been drawn with the texture
	 * of the mesh before it (was d6642bc1...).
	 */
	static final String RECORDED_RENDERER = "Intel(R) Graphics | 4.6.0 - Build 32.0.101.6737";
	static final String[][] RECORDED = {
		{"map model, untextured, immediate", "4e355096460b41f3c9eb315c16e9ba4833852d5f3f4e3204848dab506b6ae49c"},
		{"textured map model, from buffers", "45372a4f8f84d6b0de29f09f265cb4ee527523e1597b48b337c84ea86685914b"},
		//measured 2026-10-15, two runs alike - and the same frame the buffers draw, as the one model drawn two ways
		{"textured map model, immediate", "45372a4f8f84d6b0de29f09f265cb4ee527523e1597b48b337c84ea86685914b"},
		{"textured building and its box", "e66d558a0af0f60a24c979d0f516d97cb52111725891e94fa8bd69e44c2a00d0"},
		{"textured NPC model, uploaded on first draw", "adf9f233f182f823cdc54eb0b5980ec5ac94eeb826cfd8724465d313cbf802c2"},
		//measured 2026-10-15, two runs alike - each the same frame its buffers draw
		{"textured NPC model, immediate", "adf9f233f182f823cdc54eb0b5980ec5ac94eeb826cfd8724465d313cbf802c2"},
		{"textured building, immediate", "7cf8311d5d9da58f743b3b4798ffadbf475a641ba9e924273673ae2c71c56785"},
		{"textured building, freed and drawn again", "7cf8311d5d9da58f743b3b4798ffadbf475a641ba9e924273673ae2c71c56785"},
		{"collision, one triangle selected", "150d91e12104131bfba1a320ace6ba5f6131067bec318fc0dd107f45eb78281d"},
		{"the move gizmo's arrows", "9ed826bf9cf3b861138086e8c65606f0118706ffd85ff803894b8a07a3bca628"},
	};

	/**
	 * Frames drawn but not digested, because what they draw is undefined. None since 2026-10-10: the one
	 * there was - the untextured map model, 3 digests in 5 runs - is defined now that a mesh with nothing
	 * to texture is drawn untextured, and is recorded above. Kept so a frame that turns out undefined on
	 * another day is said, not recorded as whichever answer it gave first.
	 */
	static final java.util.Set<String> UNDEFINED = new java.util.HashSet<>();

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "../RomFS_original_garcs");
		for (ctrmap.gamedef.ArchiveType a : new ctrmap.gamedef.ArchiveType[]{ctrmap.gamedef.ArchiveType.FIELD_DATA, ctrmap.gamedef.ArchiveType.BUILDING_MODELS, ctrmap.gamedef.ArchiveType.MOVE_MODELS, ctrmap.gamedef.ArchiveType.AREA_DATA}) {
			if (!arc(dump, a).isFile()) {
				Skip.suite("no " + arc(dump, a) + " under " + dump.getAbsolutePath());
				return;
			}
		}
		if (!GlSupport.available()) {
			Skip.suite("OpenGL does not start on this machine, so nothing can be drawn to compare");
			return;
		}
		Scene scene = new Scene(dump);
		Map<String, String> got = scene.drawAll();
		String renderer = scene.renderer;
		System.out.println("  renderer: " + renderer);
		for (Map.Entry<String, String> e : got.entrySet()) {
			System.out.println("    {\"" + e.getKey() + "\", \"" + e.getValue() + "\"},");
		}
		for (Map.Entry<String, Integer> e : scene.drawn.entrySet()) {
			check(e.getValue() > 0, "control: '" + e.getKey() + "' drew something (" + e.getValue() + " pixel(s) off the clear colour)");
		}
		check("read twice, by one reader".equals(scene.overlayRead), "the map view's GL overlay reads its frame back, "
				+ "by one reader made the first time: " + scene.overlayRead);
		if (!renderer.equals(RECORDED_RENDERER)) {
			Skip.section("the digests were recorded on '" + RECORDED_RENDERER + "' and this is '" + renderer
					+ "' - whether these frames match is UNKNOWN here, so nothing is compared");
		} else {
			check(RECORDED.length + UNDEFINED.size() == got.size(), "control: every frame drawn (" + got.size()
					+ ") is recorded (" + RECORDED.length + ") or named undefined (" + UNDEFINED.size() + ")");
			for (String[] r : RECORDED) {
				check(r[1].equals(got.get(r[0])), "'" + r[0] + "' draws what it drew when recorded: " + got.get(r[0]) + " (recorded: " + r[1] + ")");
			}
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** The things drawn, and the frames they make. */
	static final class Scene implements GLEventListener {

		final Map<String, String> digests = new LinkedHashMap<>();
		final Map<String, Integer> drawn = new LinkedHashMap<>();
		String renderer = "";
		final BCHFile region, plainRegion, building, npc;
		final H3DModel regionModel, buildingModel, npcModel;
		final GRCollisionFile collision;
		final CMVD[] arrows = new CMVD[3];
		static final int AREA = 9;
		java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> worldTextures, propTextures;
		/** The 2D map view, for its GL overlay's read-back; and what that read-back did. */
		final ctrmap.humaninterface.TileMapPanel overlay;
		String overlayRead = "(not drawn)";

		/** The first entry of {@code arc} whose BCH (subfile {@code sub}) has a material naming one of {@code textures}. */
		static BCHFile firstNaming(GARC arc, int sub, java.util.List<ctrmap.formats.h3d.texturing.H3DTexture> textures) throws Exception {
			java.util.Set<String> names = new java.util.HashSet<>();
			for (ctrmap.formats.h3d.texturing.H3DTexture t : textures) {
				names.add(t.textureName);
			}
			for (int i = 0; i < arc.length; i++) {
				byte[] e = arc.getDecompressedEntry(i);
				byte[] b = e == null ? null : ContainerBytes.subfile(e, sub);
				if (b == null || b.length < 4 || b[0] != 'B' || b[1] != 'C' || b[2] != 'H') {
					continue;
				}
				BCHFile f = new BCHFile(b);
				if (f.models.isEmpty() || f.models.get(0).meshes.isEmpty()) {
					continue;
				}
				for (ctrmap.formats.h3d.texturing.H3DMaterial m : f.models.get(0).materials) {
					if (names.contains(m.name0)) {
						return f;
					}
				}
			}
			return null;
		}
		RuntimeException died;

		Scene(File dump) throws Exception {
			//TEXTURED AS THE EDITOR TEXTURES THEM (ZoneHeader.fetchArchives): a map from its area's world pack
			//(subfile 11), a building from the area's prop pack (subfile 1). Area 9; the first region and the
			//first building one of whose materials names a texture the pack holds.
			byte[] area = new GARC(arc(dump, ctrmap.gamedef.ArchiveType.AREA_DATA)).getDecompressedEntry(AREA);
			byte[] world = ContainerBytes.subfile(area, 11);
			if (world[0] == 0x11) {
				world = ctrmap.formats.garc.LZ11.decompress(world);
			}
			worldTextures = new BCHFile(world).textures;
			propTextures = new BCHFile(ContainerBytes.subfile(area, 1)).textures;
			check(!worldTextures.isEmpty() && !propTextures.isEmpty(),
					"control: area " + AREA + " has world and prop textures (" + worldTextures.size() + ", " + propTextures.size() + ")");
			region = firstNaming(new GARC(arc(dump, ctrmap.gamedef.ArchiveType.FIELD_DATA)), 1, worldTextures);
			//the single-region view's own path: a region's model as read, no textures set, drawn with no buffers
			plainRegion = new BCHFile(ContainerBytes.subfile(new GARC(arc(dump, ctrmap.gamedef.ArchiveType.FIELD_DATA)).getDecompressedEntry(1), 1));
			building = firstNaming(new GARC(arc(dump, ctrmap.gamedef.ArchiveType.BUILDING_MODELS)), 0, propTextures);
			check(region != null && building != null, "control: a region and a building name the area's textures");
			npc = new BCHFile(ContainerBytes.subfile(new GARC(arc(dump, ctrmap.gamedef.ArchiveType.MOVE_MODELS)).getDecompressedEntry(1), 0));
			regionModel = region.models.get(0);
			buildingModel = building.models.get(0);
			npcModel = npc.models.get(0);
			regionModel.setMaterialTextures(worldTextures);
			buildingModel.setMaterialTextures(propTextures);
			npcModel.setMaterialTextures(npc.textures);
			check(!npc.textures.isEmpty(), "control: the NPC model carries its own textures (" + npc.textures.size() + ")");
			File grFile = new File(Scratch.dir("offscreen-gr"), "region1");
			Files.write(grFile.toPath(), new GARC(arc(dump, ctrmap.gamedef.ArchiveType.FIELD_DATA)).getDecompressedEntry(1));
			collision = GRCollisionFile.read(new GR(grFile, new FakeGameFiles()));
			int tris = 0;
			for (int i = 0; i < collision.meshes.length; i++) {
				tris += collision.meshes[i].tris.size();
			}
			check(tris > 0, "control: region 1's collision has triangles (" + tris + ")");
			for (int i = 0; i < collision.meshes.length; i++) {
				if (!collision.meshes[i].tris.isEmpty()) {
					collision.meshes[i].tris.get(0).setSelected(true);
					break;
				}
			}
			ctrmap.humaninterface.tools.ToolSelection tools = new ctrmap.humaninterface.tools.ToolSelection();
			overlay = new ctrmap.humaninterface.TileMapPanel(new ctrmap.LoadedZone(), tools, new RecordingScene(),
					new javax.swing.JScrollPane(), new ctrmap.humaninterface.CollEditPanel(tools,
					UndoHistoryTest.overOneNumber(new int[]{0})), null);
			String[] names = {"MoveArrowX.cmvd", "MoveArrowY.cmvd", "MoveArrowZ.cmvd"};
			for (int i = 0; i < 3; i++) {
				try (java.io.InputStream in = ctrmap.resources.ResourceAccess.getStream(names[i])) {
					arrows[i] = new CMVD(in);
				}
			}
		}

		Map<String, String> drawAll() {
			if (!GlSupport.available()) {
				throw new IllegalStateException("OpenGL does not start here");
			}
			GLProfile profile = GLProfile.get(GLProfile.GL2);
			GLCapabilities caps = new GLCapabilities(profile);
			caps.setOnscreen(false);
			caps.setFBO(true);
			caps.setDoubleBuffered(false);
			caps.setDepthBits(24);
			caps.setAlphaBits(8);
			GLOffscreenAutoDrawable drawable = GLDrawableFactory.getFactory(profile).createOffscreenAutoDrawable(null, caps, null, SIZE, SIZE);
			drawable.addGLEventListener(this);
			drawable.display();
			drawable.destroy();
			if (died != null) {
				throw died;
			}
			return digests;
		}

		@Override
		public void init(GLAutoDrawable d) {
			GL2 gl = d.getGL().getGL2();
			renderer = gl.glGetString(GL2.GL_RENDERER) + " | " + gl.glGetString(GL2.GL_VERSION);
			gl.glShadeModel(GL2.GL_SMOOTH);
			gl.glClearColor(0f, 0f, 0f, 0f);
			gl.glClearDepth(1.0f);
			gl.glEnable(GL2.GL_DEPTH_TEST);
			gl.glEnable(GL2.GL_TEXTURE_2D);
			gl.glDisable(GL2.GL_CULL_FACE);
			gl.glDepthFunc(GL2.GL_LEQUAL);
		}

		@Override
		public void display(GLAutoDrawable d) {
			GL2 gl = d.getGL().getGL2();
			ModelRenderer draw = new ModelRenderer();
			try {
				H3DModel plain = plainRegion.models.get(0);
				frame(gl, "map model, untextured, immediate", plain, () -> draw.render(gl, plainRegion));
				regionModel.makeAllBOs();
				draw.upload(gl, regionModel);
				frame(gl, "textured map model, from buffers", regionModel, () -> draw.render(gl, region));
				//the same, drawn vertex by vertex: the one path that reads a texture's wrap to flip U for a mirrored one
				//(the 2026-10-15 shared sweep, ModelRenderer 155, negate-if, SURVIVED: no frame drew a texture this way)
				for (H3DModel.H3DMesh m : regionModel.meshes) {
					m.useVBO = false;
				}
				frame(gl, "textured map model, immediate", regionModel, () -> draw.render(gl, region));
				for (H3DModel.H3DMesh m : regionModel.meshes) {
					m.useVBO = true;
				}
				buildingModel.makeAllBOs();
				draw.upload(gl, buildingModel);
				frame(gl, "textured building and its box", buildingModel, () -> {
					draw.render(gl, buildingModel);
					draw.renderBox(gl, buildingModel);
				});
				npcModel.makeAllBOs();
				frame(gl, "textured NPC model, uploaded on first draw", npcModel, () -> draw.render(gl, npcModel));
				//and vertex by vertex: under REPEAT a U flipped by one draws the same texel, so the map's textures cannot
				//tell the mirror's flag; a model's clamped or mirrored ones can (ModelRenderer 155, as above)
				for (H3DModel.H3DMesh m : npcModel.meshes) {
					m.useVBO = false;
				}
				frame(gl, "textured NPC model, immediate", npcModel, () -> draw.render(gl, npcModel));
				for (H3DModel.H3DMesh m : npcModel.meshes) {
					m.useVBO = true;
				}
				for (H3DModel.H3DMesh m : buildingModel.meshes) {
					m.useVBO = false;
				}
				frame(gl, "textured building, immediate", buildingModel, () -> draw.render(gl, buildingModel));
				for (H3DModel.H3DMesh m : buildingModel.meshes) {
					m.useVBO = true;
				}
				draw.destroy(gl, buildingModel);
				frame(gl, "textured building, freed and drawn again", buildingModel, () -> draw.render(gl, buildingModel));
				frameAround(gl, "collision, one triangle selected", -400f, 400f, () -> {
					gl.glDisable(GL2.GL_TEXTURE_2D);
					gl.glBegin(GL2.GL_TRIANGLES);
					CollisionRenderer.render(gl, collision);
					gl.glEnd();
					gl.glEnable(GL2.GL_TEXTURE_2D);
				});
				frameAround(gl, "the move gizmo's arrows", -40f, 40f, () -> {
					for (CMVD a : arrows) {
						CollisionRenderer.render(gl, a);
					}
					gl.glEnable(GL2.GL_TEXTURE_2D);
				});
				//the 2D map view's GL overlay reads its frame back through one reader, made the first time (the
				//2026-10-15 shared sweep, TileMapPanel 908, negate-if, SURVIVED: no suite drew the overlay)
				try {
					java.awt.image.BufferedImage first = overlay.renderGL(gl);
					java.lang.reflect.Field reader = ctrmap.humaninterface.TileMapPanel.class.getDeclaredField("frameReader");
					reader.setAccessible(true);
					Object made = reader.get(overlay);
					java.awt.image.BufferedImage second = overlay.renderGL(gl);
					overlayRead = first == null || second == null ? "no frame"
							: first.getWidth() != SIZE || second.getHeight() != SIZE ? "a frame of " + first.getWidth() + "x" + second.getHeight()
							: made == null || reader.get(overlay) != made ? "a reader made again" : "read twice, by one reader";
				} catch (RuntimeException | ReflectiveOperationException threw) {
					overlayRead = "threw " + threw;
				}
				draw.destroy(gl, buildingModel);
				draw.destroy(gl, regionModel);
				draw.destroy(gl, npcModel);
				draw.destroy(gl, plain);
			} catch (RuntimeException e) {
				died = e;
			}
		}

		void frame(GL2 gl, String name, H3DModel m, Runnable draw) {
			float lo = Math.min(m.minVector.x, Math.min(m.minVector.y, m.minVector.z));
			float hi = Math.max(m.maxVector.x, Math.max(m.maxVector.y, m.maxVector.z));
			frameAround(gl, name, lo, hi, draw);
		}

		/** One frame: an orthographic view of the cube lo..hi, turned 30 degrees on x and y, then read back. */
		void frameAround(GL2 gl, String name, float lo, float hi, Runnable draw) {
			gl.glViewport(0, 0, SIZE, SIZE);
			gl.glMatrixMode(GL2.GL_PROJECTION);
			gl.glLoadIdentity();
			float half = Math.max(1f, (hi - lo) * 0.9f);
			gl.glOrtho(-half, half, -half, half, -4 * half, 4 * half);
			gl.glMatrixMode(GL2.GL_MODELVIEW);
			gl.glLoadIdentity();
			gl.glRotatef(30f, 1f, 0f, 0f);
			gl.glRotatef(30f, 0f, 1f, 0f);
			float mid = (lo + hi) / 2f;
			gl.glTranslatef(-mid, -mid, -mid);
			//A CLEAR COLOUR NOTHING DRAWS: on black, a model drawn black - an untextured mesh under an
			//enabled, incomplete texture unit, on this driver - read as nothing drawn at all
			gl.glClearColor(0.25f, 0.5f, 0.75f, 1f);
			gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
			byte[] empty = read(gl);
			gl.glClear(GL2.GL_COLOR_BUFFER_BIT | GL2.GL_DEPTH_BUFFER_BIT);
			gl.glDisable(GL2.GL_BLEND);
			gl.glDisable(GL2.GL_ALPHA_TEST);
			draw.run();
			byte[] bytes = read(gl);
			int lit = 0;
			for (int i = 0; i < bytes.length; i += 4) {
				if (bytes[i] != empty[0] || bytes[i + 1] != empty[1] || bytes[i + 2] != empty[2]) {
					lit++;
				}
			}
			drawn.put(name, lit);
			try {
				StringBuilder hex = new StringBuilder();
				for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
					hex.append(String.format("%02x", b));
				}
				digests.put(name, hex.toString());
			} catch (java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		/** The frame as it stands, read back RGBA. */
		static byte[] read(GL2 gl) {
			gl.glFinish();
			ByteBuffer px = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
			gl.glPixelStorei(GL2.GL_PACK_ALIGNMENT, 1);
			gl.glReadPixels(0, 0, SIZE, SIZE, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, px);
			byte[] bytes = new byte[SIZE * SIZE * 4];
			px.get(bytes);
			return bytes;
		}

		@Override
		public void dispose(GLAutoDrawable d) {
		}

		@Override
		public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {
		}
	}

	/** Where ORAS keeps {@code t} under the dump root {@code dump} - asked of the game profile. */
	static File arc(File dump, ctrmap.gamedef.ArchiveType t) {
		return new File(dump.getPath() + ctrmap.Workspace.getArchivePath(t, ctrmap.gamedef.GameType.ORAS));
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}
}
