package ctrmap.tests;

import com.jogamp.opengl.GL2;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.humaninterface.CustomH3DPreview;
import ctrmap.humaninterface.ShownLoop;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JPanel;

/**
 * A render loop runs only while its panel is seen, and a model preview uploads its model once
 * rather than every frame (F098, F099).
 *
 * <p>WHY THIS SUITE EXISTS. Five panels started a frame loop in their constructor: the legacy
 * collision viewer at 300 frames a second from application start, whether or not its tab was
 * ever opened; every model preview at 60, the registry editors' and the prop form's never
 * stopped; and the preview deleted and re-uploaded every GPU buffer of its model on every one of
 * those frames.
 *
 * <p>ORDER: needs no dump, no display and no GL. The loop's rule is driven through a panel that
 * says whether it is showing; the preview is made without its GL surface and its drawing is
 * driven directly, with meshes that count what is done to them instead of touching GL.
 *
 * Usage: java ctrmap.tests.RenderLoopTest
 */
public class RenderLoopTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		aLoopFollowsItsPanel();
		noLoopIsStartedOutsideTheRule();
		aPreviewUploadsItsModelOnce();
		aTriangleColourIsMadeOncePerHeight();
		anOffscreenDrawableIsEndedWhenReplaced();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** A panel that says whether it is showing, since a suite has no screen to show it on. */
	static final class Seen extends JPanel {

		boolean showing;

		@Override
		public boolean isShowing() {
			return showing;
		}

		void become(boolean now) {
			showing = now;
			for (HierarchyListener h : getHierarchyListeners()) {
				h.hierarchyChanged(new HierarchyEvent(this, HierarchyEvent.HIERARCHY_CHANGED, this, null,
						HierarchyEvent.SHOWING_CHANGED));
			}
		}
	}

	static final class Counted implements ShownLoop.Loop {

		boolean started;
		int starts;

		@Override
		public void start() {
			started = true;
			starts++;
		}

		@Override
		public void stop() {
			started = false;
		}

		@Override
		public boolean isStarted() {
			return started;
		}
	}

	static void aLoopFollowsItsPanel() {
		System.out.println("--- a render loop runs exactly while its panel is showing (F098, F099)");
		Seen panel = new Seen();
		Counted loop = new Counted();
		ShownLoop shown = new ShownLoop(panel, loop);
		check(!loop.started, "a panel built and never shown draws nothing");
		panel.become(true);
		check(loop.started && shown.running(), "shown, it draws");
		panel.become(false);
		check(!loop.started, "hidden, it stops");
		panel.become(true);
		shown.end();
		check(!loop.started, "ended, it stops while still shown");
		panel.become(false);
		panel.become(true);
		check(!loop.started && loop.starts == 2, "and an ended loop is not started again by being shown: " + loop.starts + " starts");
	}

	static void noLoopIsStartedOutsideTheRule() throws Exception {
		System.out.println("--- no panel starts a frame loop of its own (F098, F099)");
		Pattern own = Pattern.compile("new\\s+(?:[\\w.]+\\.)?(?:FPSAnimator|Animator)\\s*\\(");
		check(own.matcher("animator = new FPSAnimator(this, 60);").find(), "control: the shape is seen");
		List<String> found = new ArrayList<>();
		int files = 0;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			files++;
			Matcher m = own.matcher(SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
			while (m.find()) {
				found.add(f.getName());
			}
		}
		check(files > 250 && found.size() == 1 && found.get(0).equals("ShownLoop.java"),
				"the one in " + files + " files is ShownLoop's: " + found);
	}

	/** A mesh that counts what is done to it instead of doing it. */
	static final class CountingMesh extends H3DModel.H3DMesh {

		int uploads;
		int renders;
		int destroys;

		@Override
		public void makeBOs(H3DMaterial mat) {
			vbo = FloatBuffer.allocate(1);
		}

		@Override
		public void uploadVBO(GL2 gl) {
			uploads++;
		}

		@Override
		public void render(GL2 gl, H3DMaterial mat) {
			renders++;
		}

		@Override
		public void destroyGl(GL2 gl) {
			destroys++;
		}
	}

	/** An instance made without running its constructors - the preview's would open a GL surface. */
	static Object bare(Class<?> c) throws Exception {
		Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
		Field f = unsafeClass.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		Object unsafe = f.get(null);
		return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, c);
	}

	static void aPreviewUploadsItsModelOnce() throws Exception {
		System.out.println("--- a model preview uploads its model once, not every frame (F098)");
		CustomH3DPreview preview = (CustomH3DPreview) bare(CustomH3DPreview.class);
		H3DModel model = (H3DModel) bare(H3DModel.class);
		model.meshes = new ArrayList<>();
		model.materials = new ArrayList<>();
		model.minVector = new Vec3f();
		model.maxVector = new Vec3f();
		CountingMesh a = new CountingMesh();
		CountingMesh b = new CountingMesh();
		model.meshes.add(a);
		model.meshes.add(b);
		Object context = new Object();
		preview.loadModel(model);
		for (int i = 0; i < 3; i++) {
			preview.drawModel(null, context);
		}
		check(a.renders == 3 && b.renders == 3, "control: three frames draw each mesh three times (" + a.renders + ", " + b.renders + ")");
		check(a.uploads == 1 && b.uploads == 1, "and three frames upload each mesh once: " + a.uploads + ", " + b.uploads);
		preview.loadModel(model);
		preview.drawModel(null, context);
		check(a.uploads == 2, "the model handed again is uploaded again, in case it changed: " + a.uploads);
		preview.drawModel(null, new Object());
		check(a.uploads == 3, "and so is it into another context: " + a.uploads);

		H3DModel other = (H3DModel) bare(H3DModel.class);
		other.meshes = new ArrayList<>();
		other.materials = new ArrayList<>();
		other.minVector = new Vec3f();
		other.maxVector = new Vec3f();
		CountingMesh c = new CountingMesh();
		other.meshes.add(c);
		Object last = new Object();
		preview.drawModel(null, last);
		int freedBefore = a.destroys;
		preview.loadModel(other);
		preview.drawModel(null, last);
		preview.drawModel(null, last);
		check(c.uploads == 1 && a.destroys == freedBefore + 1, "a model switched to is uploaded once and the one before it"
				+ " freed from the context it was in: " + c.uploads + " upload(s), " + (a.destroys - freedBefore) + " freed");
		preview.release(null);
		check(c.destroys == 1, "and a context going away takes the shown model's buffers with it: " + c.destroys);
	}

	static void aTriangleColourIsMadeOncePerHeight() {
		System.out.println("--- a collision triangle's colour is made once per height, not per triangle per frame (F099)");
		List<String> differ = new ArrayList<>();
		for (long h = -300; h <= 300; h += 7) {
			java.util.Random r = new java.util.Random(h);
			float[] want = {r.nextFloat(), r.nextFloat(), r.nextFloat()};
			if (!java.util.Arrays.equals(want, ctrmap.formats.Triangle.heightColour(h))) {
				differ.add(String.valueOf(h));
			}
		}
		check(differ.isEmpty(), "every height draws in the colour it always did: differ at " + differ);
		check(ctrmap.formats.Triangle.heightColour(42) == ctrmap.formats.Triangle.heightColour(42),
				"and the second triangle at a height reuses the first one's colour");
	}

	/** Zero, false or null for a method's return type: what a stand-in that does nothing answers. */
	static Object zero(Class<?> t) {
		if (t == boolean.class) {
			return false;
		}
		if (t == int.class || t == long.class || t == float.class || t == double.class || t == short.class
				|| t == byte.class || t == char.class) {
			return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(t, 1), 0);
		}
		return null;
	}

	static void anOffscreenDrawableIsEndedWhenReplaced() throws Exception {
		System.out.println("--- an offscreen drawable is ended when a resize replaces it (F110)");
		final int[] destroyed = {0};
		java.util.function.Supplier<com.jogamp.opengl.GLAutoDrawable> make = () -> (com.jogamp.opengl.GLAutoDrawable) java.lang.reflect.Proxy
				.newProxyInstance(com.jogamp.opengl.GLAutoDrawable.class.getClassLoader(),
						new Class<?>[]{com.jogamp.opengl.GLAutoDrawable.class}, (p, m, a) -> {
							if (m.getName().equals("destroy")) {
								destroyed[0]++;
							}
							return zero(m.getReturnType());
						});
		ctrmap.humaninterface.OffscreenSlot slot = new ctrmap.humaninterface.OffscreenSlot();
		Object first = slot.replace(make);
		slot.replace(make);
		slot.replace(make);
		check(destroyed[0] == 2 && slot.current() != first, "three sizes in a row end the two drawables they replace: "
				+ destroyed[0] + " ended");
		slot.end();
		check(destroyed[0] == 3 && slot.current() == null, "and the last is ended with the slot: " + destroyed[0]);

		Pattern made = Pattern.compile("createOffscreenAutoDrawable\\s*\\(");
		Pattern slotted = Pattern.compile("\\.replace\\s*\\(\\s*\\(\\s*\\)\\s*->\\s*[\\w.]*createOffscreenAutoDrawable\\s*\\(");
		check(slotted.matcher("CM2DDrawable = offscreen.replace(() -> factory.createOffscreenAutoDrawable(d, caps));").find(),
				"control: a drawable made inside a slot is seen as one");
		int all = 0;
		int inSlot = 0;
		for (File f : CollisionReadTest.javaFiles(new File("src/ctrmap"), new ArrayList<>())) {
			if (f.getPath().contains(File.separator + "tests" + File.separator)) {
				continue;
			}
			String src = SourceSeamTest.stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			Matcher m = made.matcher(src);
			while (m.find()) {
				all++;
			}
			m = slotted.matcher(src);
			while (m.find()) {
				inSlot++;
			}
		}
		check(all >= 1 && all == inSlot, "every offscreen drawable the program makes is made in a slot: " + inSlot + " of " + all);
	}

	static void check(boolean ok, String what) {
		if (ok) {
			System.out.println("  ok: " + what);
		} else {
			System.out.println("  FAIL: " + what);
			fails++;
		}
	}
}
