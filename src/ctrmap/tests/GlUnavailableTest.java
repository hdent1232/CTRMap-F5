package ctrmap.tests;

import com.jogamp.opengl.GLAutoDrawable;
import ctrmap.LoadedZone;
import ctrmap.humaninterface.CollEditPanel;
import ctrmap.humaninterface.CustomH3DPreview;
import ctrmap.humaninterface.GLPanel;
import ctrmap.humaninterface.GlSupport;
import ctrmap.humaninterface.PanelScene3D;
import ctrmap.humaninterface.TileMapPanel;
import ctrmap.humaninterface.tools.ToolSelection;
import java.awt.Component;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.GroupLayout;
import javax.swing.JLabel;
import javax.swing.JScrollPane;

/**
 * The editor opens 2D-only where OpenGL cannot start, and every way into OpenGL asks first (F077).
 *
 * <p>WHY THIS SUITE EXISTS. Every 3D surface asked OpenGL for a profile as it was built, and on a machine
 * whose JOGL natives do not load that is an UnsatisfiedLinkError out of the constructor. The main window
 * builds the map view, the collision view, the 3D view and the prop form's model preview before it is
 * shown, so it did not open at all - the 2D editor, the forms and the Game Data tab went with it, on a
 * machine where none of them draws in 3D. Measured here, on Linux amd64 with the bundled lib/ (Windows
 * natives only): seven suites of the battery died in a GL constructor before testing anything.
 *
 * <p>THE RULE, by bytecode: every application method that touches a way into OpenGL - a static of
 * GLProfile or GLDrawableFactory, or constructing anything that is a GLAutoDrawable - branches on
 * {@link GlSupport#available} in the same method. Which statics are ways in is read from the JOGL jar,
 * and which classes are drawables is asked of the classes themselves (loaded, not initialised), so a new
 * preview class or a new JOGL call is covered without being named here. Exempt: GlSupport, which is the
 * asking, and a drawable's own constructor, which runs only where a guarded construction put it.
 *
 * <p>ORDER: needs no dump and no display. The runtime half proves the placeholders only where OpenGL
 * cannot start, and says it skipped where it can - a drawable cannot be built without a display.
 *
 * Usage: java ctrmap.tests.GlUnavailableTest &lt;compiled classes root&gt;
 */
public class GlUnavailableTest {

	static int fails = 0;
	static int skips = 0;

	public static void main(String[] args) throws Exception {
		File root = new File(args.length > 0 ? args[0] : "build/classes");
		theAnswerIsAskedOnce();
		aModelPreviewStandsWithoutGl();
		theMapViewStandsWithoutGl();
		everyWayIntoGlAsksFirst(root);
		onlyHeadlessSetsTheSwitch(root);
		libShipsNativesForEveryPlatformTheLaunchersStart(root.getAbsoluteFile().getParentFile().getParentFile());
		if (fails == 0 && skips > 0) {
			System.out.println("PASS WITH " + skips + " SKIP(S)");
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * lib/ ships JOGL's natives for every platform the portable zip tells a user to run it on, both jars'
	 * natives for each, all of one release (F077). Measured 2026-10-08: lib/ held JOGL 2.3.2 with Windows
	 * natives only while README offered run.sh for macOS and Linux - there the library did not load, and
	 * 2.3.2's Linux natives, tried, crashed the JVM on a modern Mesa. JOGL went to 2.6.0 on every platform
	 * on 2026-10-10. The families are read from README's launcher line, not listed here, and the rule is
	 * asked of a broken scratch lib/ both ways before this one.
	 */
	static void libShipsNativesForEveryPlatformTheLaunchersStart(File repo) throws Exception {
		System.out.println("--- lib/ ships JOGL's natives for every platform a launcher starts, all one release");
		String launchers = null;
		for (String line : new String(java.nio.file.Files.readAllBytes(new File(repo, "README.md").toPath()),
				java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
			if (line.contains("run.sh") && line.contains("run.bat")) {
				launchers = line;
			}
		}
		check(launchers != null, "control: README has the line naming run.bat and run.sh");
		if (launchers == null) {
			return;
		}
		java.util.Map<String, String> families = new java.util.LinkedHashMap<>();
		families.put("Windows", "windows-");
		families.put("macOS", "macosx-");
		families.put("Linux", "linux-");
		List<String> wanted = new ArrayList<>();
		for (java.util.Map.Entry<String, String> f : families.entrySet()) {
			if (launchers.contains(f.getKey())) {
				wanted.add(f.getValue());
			}
		}
		check(wanted.size() == 3, "control: README offers the editor on Windows, macOS and Linux: " + wanted);

		File broken = Scratch.dir("gl-lib-broken");
		File skewed = Scratch.dir("gl-lib-skewed");
		for (File dir : new File[]{broken, skewed}) {
			for (String jar : new String[]{"jogl-all", "gluegen-rt"}) {
				fakeJar(new File(dir, jar + ".jar"), "2.6.0");
				for (String p : new String[]{"windows-amd64", "linux-amd64", "macosx-universal"}) {
					if (!(dir == broken && jar.equals("gluegen-rt") && p.equals("linux-amd64"))) {
						fakeJar(new File(dir, jar + "-natives-" + p + ".jar"), dir == skewed && p.equals("macosx-universal") ? "2.3.2" : "2.6.0");
					}
				}
			}
		}
		List<String> said = libProblems(broken, wanted);
		check(said.toString().contains("gluegen-rt has no linux- natives"), "control: a lib/ missing one platform's natives is refused: " + said);
		said = libProblems(skewed, wanted);
		check(said.toString().contains("more than one release"), "control: a lib/ mixing releases is refused: " + said);
		said = libProblems(new File(repo, "lib"), wanted);
		check(said.isEmpty(), "this lib/ ships both jars' natives for every platform README offers, of one release: " + said);
	}

	/** What is wrong with a lib/ for the platform families {@code wanted}, each a natives-jar prefix. */
	static List<String> libProblems(File lib, List<String> wanted) throws Exception {
		List<String> out = new ArrayList<>();
		Set<String> versions = new TreeSet<>();
		File[] jars = lib.listFiles((d, n) -> n.endsWith(".jar"));
		if (jars == null || jars.length == 0) {
			out.add(lib + " holds no jar");
			return out;
		}
		for (File j : jars) {
			try (java.util.jar.JarFile jf = new java.util.jar.JarFile(j)) {
				String v = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes().getValue("Implementation-Version");
				versions.add(v == null ? "UNKNOWN (" + j.getName() + " says no version)" : v);
			}
		}
		if (versions.size() != 1) {
			out.add("lib/ holds more than one release: " + versions);
		}
		for (String jar : new String[]{"jogl-all", "gluegen-rt"}) {
			if (!new File(lib, jar + ".jar").isFile()) {
				out.add("no " + jar + ".jar");
			}
			for (String family : wanted) {
				boolean has = false;
				for (File j : jars) {
					has |= j.getName().startsWith(jar + "-natives-" + family);
				}
				if (!has) {
					out.add(jar + " has no " + family + " natives");
				}
			}
		}
		return out;
	}

	static void fakeJar(File f, String version) throws Exception {
		java.util.jar.Manifest m = new java.util.jar.Manifest();
		m.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
		m.getMainAttributes().put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, version);
		try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(f), m)) {
			out.flush();
		}
	}

	static void check(boolean ok, String what) {
		System.out.println((ok ? "  ok: " : "  FAIL: ") + what);
		if (!ok) {
			fails++;
		}
	}

	static void theAnswerIsAskedOnce() {
		System.out.println("--- whether OpenGL starts is asked once and kept, and is the truth");
		String first = GlSupport.unavailable();
		check(GlSupport.unavailable() == first && GlSupport.available() == (first == null),
				"control: the second answer is the first (" + (first == null ? "OpenGL starts here" : first) + ")");
		//ASKED AGAIN, HERE, OF JOGL ITSELF: a probe that said "it starts" without asking would turn every
		//section below into a skip, and the suite would pass on a machine where the window cannot open
		boolean starts;
		try {
			com.jogamp.opengl.GLProfile.get(com.jogamp.opengl.GLProfile.GL2);
			starts = true;
		} catch (LinkageError | RuntimeException cannot) {
			starts = false;
		}
		check(starts == GlSupport.available(), "and it is what JOGL answers when this suite asks it: "
				+ (starts ? "starts" : "does not start") + ", GlSupport says " + (GlSupport.available() ? "starts" : "does not"));
		//ASKED, NOT ASSUMED (the 2026-10-14 shared sweep, GlSupport 36, negate-if, SURVIVED): where OpenGL starts, an
		//answer of "starts" given without asking JOGL reads exactly like one that asked - so the asking is read too
		boolean asked;
		try {
			java.lang.reflect.Field f = GlSupport.class.getDeclaredField("asked");
			f.setAccessible(true);
			asked = f.getBoolean(null);
		} catch (ReflectiveOperationException unreadable) {
			asked = false;
		}
		check(asked, "and JOGL was asked by the first question, its answer kept - not answered without asking");
	}

	static void aModelPreviewStandsWithoutGl() {
		System.out.println("--- a model preview is built where OpenGL cannot start, and says why");
		if (GlSupport.available()) {
			skips++;
			Skip.section("OpenGL starts on this machine, so the placeholder is not what is built");
			return;
		}
		CustomH3DPreview preview;
		try {
			preview = new CustomH3DPreview();
		} catch (LinkageError | RuntimeException died) {
			check(false, "building a model preview without OpenGL throws: " + died);
			return;
		}
		Component held = preview.getComponentCount() == 1 ? preview.getComponent(0) : null;
		check(held instanceof JLabel && "gl-unavailable".equals(held.getName()),
				"it holds the placeholder, not a GL surface: " + held);
		String said = held instanceof JLabel ? ((JLabel) held).getText() : "";
		check(said.contains("The model preview") && said.contains(ctrmap.Ui.html(GlSupport.unavailable())),
				"which names what is missing and why: " + said);
		//what the generated forms do to it: a GroupLayout holding nothing but a size
		GroupLayout l = new GroupLayout(preview);
		preview.setLayout(l);
		l.setHorizontalGroup(l.createParallelGroup(GroupLayout.Alignment.LEADING).addGap(0, 300, Short.MAX_VALUE));
		l.setVerticalGroup(l.createParallelGroup(GroupLayout.Alignment.LEADING).addGap(0, 190, Short.MAX_VALUE));
		preview.setSize(320, 200);
		preview.doLayout();
		check(preview.getPreferredSize().width == 300 && held.getWidth() == 320 && held.getHeight() == 200,
				"under the forms' layout it keeps their size and the placeholder fills it: preferred "
				+ preview.getPreferredSize().width + ", held " + held.getWidth() + "x" + held.getHeight());
		try {
			preview.loadModel(null);
			preview.stop();
			check(true, "and handing it a model and stopping it do nothing, quietly");
		} catch (RuntimeException e) {
			check(false, "handing it a model or stopping it throws: " + e);
		}
	}

	static void theMapViewStandsWithoutGl() {
		System.out.println("--- the 2D map view is built where OpenGL cannot start, and resizes");
		if (GlSupport.available()) {
			skips++;
			Skip.section("OpenGL starts on this machine, so the map view keeps its GL overlay");
			return;
		}
		ToolSelection tools = new ToolSelection();
		TileMapPanel map;
		try {
			map = new TileMapPanel(new LoadedZone(), tools, new PanelScene3D(() -> null, new ArrayList<>()),
					new JScrollPane(), new CollEditPanel(tools, UndoHistoryTest.overOneNumber(new int[]{0})), null);
		} catch (LinkageError | RuntimeException died) {
			check(false, "building the map view without OpenGL throws: " + died);
			return;
		}
		check(true, "control: the map view is built");
		int heard = 0;
		try {
			for (ComponentListener l : map.getComponentListeners()) {
				l.componentResized(new ComponentEvent(map, ComponentEvent.COMPONENT_RESIZED));
				heard++;
			}
			check(heard > 0, "and a resize, which made its GL overlay, is heard and does nothing (" + heard + " listener(s))");
		} catch (LinkageError | RuntimeException died) {
			check(false, "a resize without OpenGL throws: " + died);
		}
	}

	/**
	 * Running without a screen is asked of {@link Headless}, which checks it took, and of nothing else.
	 *
	 * <p>Found by this fix: with OpenGL no longer killing it first, DataSafetyGuardsTest reached its
	 * progress-dialog checks with a screen attached - its switch, "the FIRST statement" of main, came
	 * after a static panel had already settled AWT's answer. Ten suites set the switch by hand and none
	 * asked whether it took. By the class files, so a constant folded from pieces is still seen.
	 */
	static void onlyHeadlessSetsTheSwitch(File root) throws Exception {
		System.out.println("--- only Headless switches AWT to headless, and it checks the switch took");
		java.util.regex.Pattern property = java.util.regex.Pattern.compile("^java\\.awt\\.headless$");
		Set<String> holders = ClassFileScanner.holdersOfString(ClassFileScanner.scan(root), property);
		check(holders.contains("ctrmap/tests/Headless"),
				"control: the property is found where it is named - in Headless: " + holders);
		holders.remove("ctrmap/tests/Headless");
		check(holders.isEmpty(), "and no other class names it, in the program or its suites: " + holders);

		//LAST, because it settles AWT's answer for this run: by now this suite has built components, so
		//where there is a screen AWT has decided so, and Headless must refuse rather than pretend
		boolean screen = !java.awt.GraphicsEnvironment.isHeadless();
		boolean refused;
		try {
			Headless.require();
			refused = false;
		} catch (IllegalStateException said) {
			refused = said.getMessage().contains("came too late");
		}
		if (screen) {
			check(refused, "and asked after AWT has decided there is a screen, it refuses, saying the switch came too late");
		} else {
			skips++;
			Skip.section("no screen here at all, so there is no too-late switch to refuse");
		}
	}

	// ------------------------------------------------------------------ the rule

	static final String PROBE = "ctrmap/humaninterface/GlSupport";
	static final String ASK = PROBE + "#available()Z";

	/** The static methods of a JOGL class, read from the jar on the classpath - not listed here. */
	static Set<String> staticsOf(String internalName) throws Exception {
		File tmp = File.createTempFile("ctrmap_gl", ".class");
		try {
			try (InputStream in = GlUnavailableTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
				if (in == null) {
					throw new IllegalStateException(internalName + " is not on the classpath: the rule cannot be read");
				}
				Files.copy(in, tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			Set<String> out = new TreeSet<>();
			for (ClassFileScanner.Method m : ClassFileScanner.read(tmp, internalName).methods) {
				if ((m.flags & 0x0008) != 0 && !m.name.equals("<clinit>")) {   //ACC_STATIC
					out.add(internalName + "#" + m.name + m.descriptor);
				}
			}
			return out;
		} finally {
			tmp.delete();
		}
	}

	/** Whether a class is a GL drawable: loaded to ask, not initialised, so nothing of it runs. */
	static boolean isDrawable(String internalName) {
		try {
			Class<?> c = Class.forName(internalName.replace('/', '.'), false, GlUnavailableTest.class.getClassLoader());
			return GLAutoDrawable.class.isAssignableFrom(c);
		} catch (ClassNotFoundException | LinkageError unreadable) {
			//A CLASS THAT CANNOT BE LOADED IS NOT "NOT A DRAWABLE": refuse rather than pass it
			throw new IllegalStateException("cannot tell whether " + internalName + " is a GL drawable: " + unreadable);
		}
	}

	/** The ways into OpenGL one method takes: a JOGL static that is a way in, or constructing a drawable. */
	static List<String> waysIn(ClassFileScanner.ClassFile cf, ClassFileScanner.Method m, Set<String> entryStatics) {
		List<String> out = new ArrayList<>();
		for (String call : m.invokes) {
			if (entryStatics.contains(call)) {
				out.add(call);
			} else if (call.contains("#<init>(")) {
				String owner = call.substring(0, call.indexOf('#'));
				boolean ownSuper = m.name.equals("<init>") && (owner.equals(cf.superName) || owner.equals(cf.name));
				if (!ownSuper && isDrawable(owner)) {
					out.add(call);
				}
			}
		}
		return out;
	}

	/**
	 * Every way into OpenGL in {@code classes} that some path from its method's entry reaches without
	 * passing the "yes" of the ask, as "Class.method: [ways]". {@code sites} collects every method that
	 * takes one, asked or not.
	 */
	static List<String> unasked(List<ClassFileScanner.ClassFile> classes, Set<String> entryStatics, List<String> sites)
			throws java.io.IOException {
		List<String> out = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : classes) {
			if (cf.name.equals(PROBE)) {
				continue;
			}
			boolean drawable = isDrawable(cf.name);
			for (ClassFileScanner.Method m : cf.methods) {
				if (drawable && m.name.equals("<init>")) {
					continue;   //runs only where a guarded construction put it
				}
				List<String> ways = waysIn(cf, m, entryStatics);
				if (ways.isEmpty()) {
					continue;
				}
				//the nested name kept: "TileMapPanel$1" is the resize listener, not the panel
				String where = cf.name.substring(cf.name.lastIndexOf('/') + 1) + "." + m.name;
				sites.add(where);
				ways.retainAll(m.reachedUnless(ASK));
				if (!ways.isEmpty()) {
					out.add(where + ": " + ways + (m.invokes.contains(ASK) ? " (asks, but not on every path to it)" : ""));
				}
			}
		}
		return out;
	}

	/** Fixtures for the rule's known answers. Never run: only their bytecode is read. */
	static final class Asked {

		Object build() {
			return GlSupport.available() ? new GLPanel(null) : null;
		}
	}

	static final class AskedAndIgnored {

		Object build() {
			GlSupport.available();
			return new GLPanel(null);
		}
	}

	/** The cheap way past a rule that asked only whether the method branches: guard one, add one beside it. */
	static final class OneOfTwoAsked {

		Object[] build() {
			return new Object[]{GlSupport.available() ? new GLPanel(null) : null, new GLPanel(null)};
		}
	}

	/** A guard that returns early covers what follows it, and a loop or a catch does not undo it. */
	static final class AskedOnceAtTheTop {

		Object build(int n) {
			if (!GlSupport.available()) {
				return null;
			}
			Object last = null;
			for (int i = 0; i < n; i++) {
				try {
					last = new GLPanel(null);
				} catch (RuntimeException e) {
					last = e;
				}
			}
			return last;
		}
	}

	static final class NeverAsked {

		Object profile() {
			return com.jogamp.opengl.GLProfile.get(com.jogamp.opengl.GLProfile.GL2);
		}
	}

	static void everyWayIntoGlAsksFirst(File root) throws Exception {
		System.out.println("--- every way into OpenGL is taken only where GlSupport.available() decided it");
		Set<String> entry = new TreeSet<>();
		entry.addAll(staticsOf("com/jogamp/opengl/GLProfile"));
		entry.addAll(staticsOf("com/jogamp/opengl/GLDrawableFactory"));
		check(entry.contains("com/jogamp/opengl/GLProfile#get(Ljava/lang/String;)Lcom/jogamp/opengl/GLProfile;")
				&& !entry.contains("com/jogamp/opengl/GLProfile#getName()Ljava/lang/String;"),
				"control: the ways in are read from the jar - GLProfile.get is one, a profile's getName is not ("
				+ entry.size() + " statics)");
		check(isDrawable("ctrmap/humaninterface/GLPanel") && isDrawable("ctrmap/humaninterface/H3DRenderingPanel")
				&& isDrawable("ctrmap/humaninterface/MapPreview3D") && isDrawable("com/jogamp/opengl/awt/GLJPanel")
				&& !isDrawable("ctrmap/humaninterface/CustomH3DPreview") && !isDrawable("ctrmap/humaninterface/TileMapPanel"),
				"control: the 3D panels and JOGL's own are drawables; the preview host and the map view are not");

		List<ClassFileScanner.ClassFile> mine = new ArrayList<>();
		for (ClassFileScanner.ClassFile cf : ClassFileScanner.scan(root)) {
			if (cf.name.startsWith("ctrmap/tests/GlUnavailableTest$")) {
				mine.add(cf);
			}
		}
		List<String> fixtureSites = new ArrayList<>();
		List<String> fixtures = unasked(mine, entry, fixtureSites);
		check(fixtureSites.size() == 5 && fixtures.size() == 3
				&& fixtures.stream().anyMatch(f -> f.startsWith("GlUnavailableTest$AskedAndIgnored.build") && f.endsWith("every path to it)"))
				&& fixtures.stream().anyMatch(f -> f.startsWith("GlUnavailableTest$OneOfTwoAsked.build"))
				&& fixtures.stream().anyMatch(f -> f.startsWith("GlUnavailableTest$NeverAsked.profile")),
				"control: of five fixtures, the two whose every path asks first pass (a ternary; an early return"
				+ " before a loop and a catch); one that asks and ignores the answer, one that guards one of two"
				+ " constructions and one that never asks are refused: " + fixtures);

		List<String> sites = new ArrayList<>();
		List<String> bad = unasked(ClassFileScanner.application(root), entry, sites);
		check(sites.size() >= 7 && sites.contains("CtrmapMainframe.createAndShowGUI") && sites.contains("TileMapPanel.<init>")
				&& sites.contains("ZonePreviewPane.mapView") && sites.contains("CustomH3DPreview.<init>"),
				"control: the program's ways into OpenGL are found - " + sites.size() + " method(s): " + sites);
		check(bad.isEmpty(), "no method of the program takes a way into OpenGL without asking: " + bad);
	}
}
