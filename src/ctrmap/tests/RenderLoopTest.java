package ctrmap.tests;

import com.jogamp.opengl.GL2;
import ctrmap.formats.h3d.model.H3DModel;
import ctrmap.formats.h3d.texturing.H3DMaterial;
import ctrmap.formats.vectors.Vec3f;
import ctrmap.humaninterface.CustomH3DPreview;
import ctrmap.humaninterface.ShownLoop;
import ctrmap.render.ModelRenderer;
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
		aStoppedPreviewEndsItsLoop();
		aStoppedViewEndsItsLoop();
		noLoopIsStartedOutsideTheRule();
		aPreviewUploadsItsModelOnce();
		aTriangleColourIsMadeOncePerHeight();
		anOffscreenDrawableIsEndedWhenReplaced();
		aMeshWithNoMaterialUploadsAndDraws();
		aMaterialIsAlphaTestedAsItSays();
		theWalkerSeesAKeyLetGo();
		aMovementKeyStartsTheWalker();
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * The 3D camera walker - its own thread - sees the keys held and the speed the event thread sets (census L.6).
	 * They were a plain list and a plain float: the walker had no sight of a release and could go on stepping, and a
	 * remove racing a contains could throw out of it. The walker needs a GL window to run, so its fields are read.
	 */
	static void theWalkerSeesAKeyLetGo() throws Exception {
		System.out.println("--- the camera walker sees a key let go (census L.6)");
		Class<?> c = Class.forName("ctrmap.humaninterface.CM3DInputManager");
		Field keys = c.getDeclaredField("keycodes");
		Field speed = c.getDeclaredField("speed");
		boolean concurrent = java.util.Set.class.isAssignableFrom(keys.getType())
				&& !java.util.List.class.isAssignableFrom(keys.getType());
		String src = new String(java.nio.file.Files.readAllBytes(new java.io.File(
				"src/ctrmap/humaninterface/CM3DInputManager.java").toPath()), java.nio.charset.StandardCharsets.UTF_8);
		check(concurrent && src.contains("keycodes = java.util.concurrent.ConcurrentHashMap.newKeySet()"),
				"the keys held are a concurrent set the walker thread can read: " + keys.getType().getSimpleName());
		check(java.lang.reflect.Modifier.isVolatile(speed.getModifiers()), "and the walking speed is volatile");
	}

	/**
	 * A movement key held starts the one walker, a second key leaves it walking, and a lost focus stops it. Measured by
	 * the 2026-10-14 shared sweep (CM3DInputManager 154, negated, SURVIVED): the walker's fields were read and no key
	 * was ever pressed, so a press that never started it - a camera W, A, S and D cannot move - went unseen. The walker
	 * is a Swing timer, which runs with no screen; the panel is built as PropEditFormGuardsTest builds one.
	 */
	static void aMovementKeyStartsTheWalker() throws Exception {
		System.out.println("--- a movement key held starts the camera walker, and a lost focus stops it");
		ctrmap.humaninterface.H3DRenderingPanel panel = new ctrmap.humaninterface.H3DRenderingPanel(
				new ArrayList<ctrmap.humaninterface.CM3DRenderable>(), new ctrmap.humaninterface.tools.ToolSelection());
		ctrmap.humaninterface.CM3DInputManager in = new ctrmap.humaninterface.CM3DInputManager(panel,
				new ctrmap.humaninterface.tools.ToolSelection());
		Field w = ctrmap.humaninterface.CM3DInputManager.class.getDeclaredField("walker");
		w.setAccessible(true);
		javax.swing.Timer walker = (javax.swing.Timer) w.get(in);
		boolean before = walker.isRunning();
		in.keyPressed(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
				java.awt.event.KeyEvent.VK_W, 'w'));
		boolean started = walker.isRunning();
		in.keyPressed(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_PRESSED, 0, 0,
				java.awt.event.KeyEvent.VK_D, 'd'));
		boolean still = walker.isRunning();
		//a tick while a key is held walks on, and the tick after the last key is let go stops it (CM3DInputManager
		//161, negated, SURVIVED the same sweep: no tick was ever run, so one that stopped a held walk went unseen)
		java.lang.reflect.Method tick = ctrmap.humaninterface.CM3DInputManager.class.getDeclaredMethod("tick");
		tick.setAccessible(true);
		tick.invoke(in);
		boolean ticked = walker.isRunning();
		in.keyReleased(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_RELEASED, 0, 0,
				java.awt.event.KeyEvent.VK_W, 'w'));
		in.keyReleased(new java.awt.event.KeyEvent(panel, java.awt.event.KeyEvent.KEY_RELEASED, 0, 0,
				java.awt.event.KeyEvent.VK_D, 'd'));
		tick.invoke(in);
		boolean letGo = !walker.isRunning();
		check(ticked && letGo, "a tick with a key held walks on (" + ticked + "), and the tick after the last key is let go"
				+ " stops it (" + letGo + ")");
		walker.start();
		in.focusLost(new java.awt.event.FocusEvent(panel, java.awt.event.FocusEvent.FOCUS_LOST));
		boolean stopped = !walker.isRunning();
		check(!before && started, "control: idle before; W held starts the walker (" + before + " -> " + started + ")");
		check(still && stopped, "a second key leaves it walking (" + still + "), and a lost focus stops it (" + stopped + ")");
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

	/**
	 * A model preview stopped ends its render loop, shown or not, and one with no loop stops quietly (the
	 * 2026-10-14 shared sweep, CustomH3DPreview 72, negate-if, SURVIVED: every suite that stopped one asked
	 * nothing after, and the suite that stops one with no loop skips that where OpenGL starts). Its loop is
	 * a counted one over a panel this suite shows and hides, so it is asked on every machine.
	 */
	static void aStoppedPreviewEndsItsLoop() throws Exception {
		System.out.println("--- a model preview stopped ends its render loop, shown or not");
		CustomH3DPreview preview = (CustomH3DPreview) bare(CustomH3DPreview.class);
		Seen panel = new Seen();
		Counted loop = new Counted();
		ShownLoop shown = new ShownLoop(panel, loop);
		Field animator = CustomH3DPreview.class.getDeclaredField("animator");
		animator.setAccessible(true);
		animator.set(preview, shown);
		panel.become(true);
		check(loop.started, "CONTROL: shown, the preview's loop draws");
		String said = "nothing";
		try {
			preview.stop();
		} catch (RuntimeException e) {
			said = e.toString();
		}
		check(!loop.started && !shown.running(), "stopped, its loop ends while it is still shown (it said " + said + ")");
		panel.become(false);
		panel.become(true);
		check(!loop.started, "and is not started again by being shown");
		animator.set(preview, null);
		said = "nothing";
		try {
			preview.stop();
		} catch (RuntimeException e) {
			said = e.toString();
		}
		check(said.equals("nothing"), "and a preview with no loop - OpenGL could not start - stops quietly: " + said);
	}

	/**
	 * The 3D view stopped ends its frame clock, shown or not, and one with no clock stops quietly (the 2026-10-14
	 * shared sweep, H3DRenderingPanel 247, negate-if, SURVIVED: every suite that stopped one asked nothing after).
	 */
	static void aStoppedViewEndsItsLoop() throws Exception {
		System.out.println("--- the 3D view stopped ends its frame clock, shown or not");
		ctrmap.humaninterface.H3DRenderingPanel view = (ctrmap.humaninterface.H3DRenderingPanel) bare(
				ctrmap.humaninterface.H3DRenderingPanel.class);
		Seen panel = new Seen();
		Counted loop = new Counted();
		ShownLoop shown = new ShownLoop(panel, loop);
		Field animator = ctrmap.humaninterface.H3DRenderingPanel.class.getDeclaredField("animator");
		animator.setAccessible(true);
		animator.set(view, shown);
		panel.become(true);
		check(loop.started, "CONTROL: shown, the view's clock runs");
		String said = "nothing";
		try {
			view.stop();
		} catch (RuntimeException e) {
			said = e.toString();
		}
		check(!loop.started && !shown.running(), "stopped, the view's clock ends while it is still shown (it said " + said + ")");
		animator.set(view, null);
		said = "nothing";
		try {
			view.stop();
		} catch (RuntimeException e) {
			said = e.toString();
		}
		check(said.equals("nothing"), "and a view with no clock stops quietly: " + said);
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

	/** A mesh whose buffers are made without its vertices - the counting is the renderer's. */
	static final class CountingMesh extends H3DModel.H3DMesh {

		int uploads;
		int renders;
		int destroys;

		@Override
		public void makeBOs(H3DMaterial mat) {
			vbo = FloatBuffer.allocate(1);
		}
	}

	/**
	 * A renderer that counts what a view asks of it for each mesh instead of doing it - the preview draws
	 * through the one it holds since the drawing left the model classes (F214).
	 */
	static final class CountingRenderer extends ctrmap.render.ModelRenderer {

		@Override
		public void upload(GL2 gl, H3DModel.H3DMesh mesh) {
			((CountingMesh) mesh).uploads++;
		}

		@Override
		public void render(GL2 gl, H3DModel.H3DMesh mesh, H3DMaterial mat) {
			((CountingMesh) mesh).renders++;
		}

		@Override
		public void destroy(GL2 gl, H3DModel.H3DMesh mesh) {
			((CountingMesh) mesh).destroys++;
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
		Field draw = CustomH3DPreview.class.getDeclaredField("draw");
		draw.setAccessible(true);
		draw.set(preview, new CountingRenderer());
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

	/**
	 * A material is alpha-tested as it says, and a "blc" or tall-grass one drops exactly its transparent texels
	 * (the 2026-10-15 shared sweep, ModelRenderer 174, negate-if, SURVIVED: no retail frame could tell - see
	 * ModelRenderer.alphaFunction). Inverted, every other material was alpha-tested, a material with no test too.
	 */
	static void aMaterialIsAlphaTestedAsItSays() throws Exception {
		System.out.println("--- a material is alpha-tested as it says; blc and tall grass drop their transparent texels");
		String plain = name(ModelRenderer.alphaFunction(material("mat_a", "tex_a", false, H3DMaterial.TestFunction.greater)));
		String tested = name(ModelRenderer.alphaFunction(material("mat_b", "tex_b", true, H3DMaterial.TestFunction.greaterOrEqual)));
		String grass = name(ModelRenderer.alphaFunction(material("su_enc01", "enc_grass", true, H3DMaterial.TestFunction.greater)));
		String untestedGrass = name(ModelRenderer.alphaFunction(material("su_enc01", "enc_grass", false, H3DMaterial.TestFunction.greater)));
		String blc = name(ModelRenderer.alphaFunction(material("mat_blc", "tex_c", true, H3DMaterial.TestFunction.always)));
		check("none".equals(plain), "a material with no alpha test is drawn with none: " + plain);
		check("GEQUAL".equals(tested), "one with a test is drawn with its own function: " + tested);
		check("NOTEQUAL".equals(grass) && "NOTEQUAL".equals(untestedGrass),
				"the tall grass drops its transparent texels, tested or not: " + grass + ", " + untestedGrass);
		check("NOTEQUAL".equals(blc), "and so does a blc material, whatever its own test says: " + blc);
	}

	/** A material named and textured so, alpha-tested by {@code test} when {@code tests}. */
	static H3DMaterial material(String name, String texture, boolean tests, H3DMaterial.TestFunction test) throws Exception {
		H3DMaterial m = (H3DMaterial) bare(H3DMaterial.class);
		m.name = name;
		m.name0 = texture;
		m.params = new H3DMaterial.Params();
		m.params.alphaTest = new H3DMaterial.AlphaTest();
		m.params.alphaTest.isTestEnabled = tests;
		m.params.alphaTest.testFunction = test;
		return m;
	}

	/** A GL alpha function by name, or "none". */
	static String name(int func) {
		switch (func) {
			case ModelRenderer.NO_ALPHA_TEST:
				return "none";
			case GL2.GL_NOTEQUAL:
				return "NOTEQUAL";
			case GL2.GL_GEQUAL:
				return "GEQUAL";
			case GL2.GL_GREATER:
				return "GREATER";
			case GL2.GL_ALWAYS:
				return "ALWAYS";
			default:
				return "0x" + Integer.toHexString(func);
		}
	}

	/**
	 * A mesh with no material uploads and draws, and an upload keeps its GL names (F078). makeBOs
	 * leaves a material-less mesh with no texture coordinates; uploadVBO rewound them before asking,
	 * a NullPointerException on the render thread, and the immediate-mode path read the null
	 * material's name. Each upload also deleted its buffer and texture and bound the deleted names.
	 * Driven on a GL2 proxy that counts what is asked of it.
	 */
	static void aMeshWithNoMaterialUploadsAndDraws() throws Exception {
		System.out.println("--- a mesh with no material uploads and draws, and keeps its GL names (F078)");
		H3DModel.H3DMesh mesh = new H3DModel.H3DMesh();
		for (int i = 0; i < 3; i++) {
			mesh.vertices.add(new ctrmap.formats.h3d.model.H3DVertex());
		}
		mesh.hasColor = true;
		mesh.makeBOs(null);
		java.lang.reflect.Method rewound = H3DModel.H3DMesh.class.getDeclaredMethod("rewound");
		java.lang.reflect.Method coloured = H3DModel.H3DMesh.class.getDeclaredMethod("vertexColoured", H3DMaterial.class);
		rewound.setAccessible(true);
		coloured.setAccessible(true);
		String upload;
		long bytes = -1;
		try {
			bytes = (Long) rewound.invoke(mesh);
			upload = null;
		} catch (java.lang.reflect.InvocationTargetException ex) {
			upload = String.valueOf(ex.getCause());
		}
		check(upload == null && bytes == 3 * 3 * Float.BYTES + 3 * 4, "a mesh with no material is made ready to upload:"
				+ " positions and colours, no texture coordinates (" + (upload == null ? bytes + " bytes" : upload) + ")");
		String drawn;
		try {
			drawn = "coloured " + coloured.invoke(mesh, (Object) null);
		} catch (java.lang.reflect.InvocationTargetException ex) {
			drawn = "threw " + ex.getCause();
		}
		check(drawn.equals("coloured true"), "and it draws without a material, its vertices coloured (" + drawn + ")");

		String src = new String(Files.readAllBytes(new File("src/ctrmap/render/ModelRenderer.java").toPath()),
				StandardCharsets.UTF_8);
		String upload_ = body(src, "public void upload(GL2 gl, H3DModel.H3DMesh mesh) {");
		String destroy = body(src, "public void destroy(GL2 gl, H3DModel.H3DMesh mesh) {");
		check(destroy != null && destroy.contains("glDeleteBuffers"), "control: the mesh's teardown is read and deletes its"
				+ " buffer");
		check(upload_ != null && !upload_.contains("glDelete"), "and its upload deletes nothing: it deleted the buffer and"
				+ " texture it had been given and bound the deleted names");
	}

	/** The text of the method whose header is {@code head}, to its matching brace; null when absent. */
	static String body(String src, String head) {
		int at = src.indexOf(head);
		if (at < 0) {
			return null;
		}
		int depth = 0;
		for (int i = at + head.length() - 1; i < src.length(); i++) {
			char c = src.charAt(i);
			depth += c == '{' ? 1 : c == '}' ? -1 : 0;
			if (depth == 0) {
				return src.substring(at, i + 1);
			}
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
