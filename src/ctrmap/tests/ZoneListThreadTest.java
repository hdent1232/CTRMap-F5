package ctrmap.tests;

import ctrmap.Workspace;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

/**
 * The zone list is filled on the event thread (F116).
 *
 * <p>WHY THIS SUITE EXISTS. Rebuilding the zone list ran on a SwingWorker's background thread and
 * from there cleared and filled two combo boxes, replaced the models of two more and enabled a
 * button - firing their listeners on the worker while the event thread painted the progress
 * dialog. Swing components are not thread-safe; the symptoms are intermittent, which is exactly why
 * the thread each change arrives on is what this measures.
 *
 * <p>ORDER: needs no dump and no display; a three-zone game is written under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.ZoneListThreadTest
 */
public class ZoneListThreadTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		//NO DISPLAY, as the order line says, before anything touches AWT: the battery runs on one,
		//and a progress window made from this main thread is refused there (F220) - and would
		//flash on the owner's screen if it were not
		Headless.require();
		Workspace.reset();
		try {
			run();
			theArchivesOpenBeforeTheWindowShows();
			aLaunchOpensTheWizardOrTheGameNeverBoth();
			whatTheOpeningWorkerThrewIsSortedByKind();
			openGlIsNeverAskedBesideTheEventThread();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	static void run() throws Exception {
		System.out.println("--- the zone list is filled on the event thread (F116)");
		ctrmap.WorkspaceSession ws = MapResizeTest.game("zonelist");
		//whole zones: the resize game's carry only the header its suite needs
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		for (int z = 0; z < 3; z++) {
			java.nio.file.Files.write(ws.getWorkspaceFile(ctrmap.gamedef.ArchiveType.ZONE_DATA, z).toPath(),
					HandedGameTest.container("ZO", new byte[ctrmap.formats.zone.ZoneHeader.SIZE], entities, amx));
		}
		//one location name, empty, written by the one GameText codec the names are read with (F089):
		//the bytes were built by hand in the legacy decoder's layout, which that codec refuses
		byte[] names = ctrmap.formats.text.GFMessageFile.write(java.util.Arrays.asList(""), true);
		java.io.File text = new java.io.File(Scratch.dir("ctrmap_zonelist_text"), "names.bin");
		java.nio.file.Files.write(text.toPath(), names);
		ctrmap.formats.text.LocationNames.load(ws, text);
		ZoneLoadingPanel tab = new ZoneLoadingPanel(new ctrmap.LoadedZone(), MatrixEditFormGuardsTest.TOOLS,
				MatrixEditFormGuardsTest.EDITORS, MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		List<String> off = Collections.synchronizedList(new ArrayList<>());
		int[] heard = {0};
		for (String name : new String[]{"zoneList", "tmg", "type", "weather"}) {
			JComboBox<?> box = box(tab, name);
			box.getModel().addListDataListener(listener(name, off, heard));
			box.addPropertyChangeListener("model", e -> {
				heard[0]++;
				if (!SwingUtilities.isEventDispatchThread()) {
					off.add(name + " model replaced");
				}
			});
		}
		ctrmap.Ui.record();
		tab.loadEverything();
		SwingUtilities.invokeAndWait(() -> { });
		check(heard[0] > 0 && box(tab, "zoneList").getItemCount() == 3,
				"control: the list was filled - " + box(tab, "zoneList").getItemCount() + " zones, " + heard[0] + " changes heard");
		check(off.isEmpty(), "and every change to the boxes arrived on the event thread: " + off);
	}

	/**
	 * THE ARCHIVES ARE OPENED BEFORE THE WINDOW SHOWS, AND WHAT OPENING FOUND IS SAID AFTER (2026-10-24). Start-up ran
	 * Workspace.validate on the event thread after frame.setVisible(true), so every launch froze the freshly shown
	 * window while the workspace's archives were parsed: measured through the real window on copies of the owner's
	 * workspace and game, 310-316 ms on an ordinary launch, where 1.0.3 froze it 0 ms - and the ledger's reviewer
	 * failed the lag item for it. Validate is two halves now: open(), which opens and reads and says nothing, and
	 * validate(..., opening), which says what there is to say and installs. The window asks for the first before it
	 * shows and the second after, so its dialogs still have a window to stand over.
	 */
	static void theArchivesOpenBeforeTheWindowShows() throws Exception {
		System.out.println("--- the workspace's archives are opened before the window shows, and what was found is said after");
		Workspace.reset();
		check(Workspace.WORKSPACE_PATH == null && Workspace.GAMEDIR_PATH == null,
				"control: no workspace or game is configured, so opening touches no real folder");
		List<String> said = ctrmap.Ui.record();
		Workspace.Opening opening = Workspace.open();
		check(said.isEmpty() && Workspace.session() == null,
				"opening says nothing and installs nothing - its findings wait for the window: " + said);
		ctrmap.WorkspaceSession back = Workspace.validate(null, true, null, opening);
		check(back == null && said.size() == 1 && said.get(0).startsWith("Setup Error"),
				"and the second half says what opening found: " + said);
		String main = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File("src/ctrmap/CtrmapMainframe.java")));
		String gui = CollisionReadTest.body(main, "private void createAndShowGUI() {");
		int opened = gui == null ? -1 : gui.indexOf("Workspace.openBeside()");
		int built = gui == null ? -1 : gui.indexOf("new JFrame(");
		int waited = gui == null ? -1 : gui.indexOf("Workspace.await(opening)");
		int shown = gui == null ? -1 : gui.indexOf("frame.setVisible(true)");
		int told = gui == null ? -1 : gui.indexOf("Workspace.validate(frame, true, null, opened)");
		check(opened >= 0 && built > opened && waited > built && shown > waited && told > shown,
				"the window starts opening the archives (" + opened + ") before it is built (" + built + "), has them before"
				+ " it shows (" + waited + " < " + shown + ") and says what it found after (" + told + ")");
		check(gui != null && !gui.contains("Workspace.open()") && (shown < 0
				|| !gui.substring(shown).matches("(?s).*Workspace\\.validate\\(frame\\)[^;]*;.*")),
				"and nothing in start-up opens the archives on the event thread itself, before the show or after it");
		//BESIDE, NOT ON THE CALLER: run on the calling thread, openBeside would put the 400-525 ms back on the event
		//thread with every line above still true
		String ws = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File("src/ctrmap/Workspace.java")));
		String beside = CollisionReadTest.body(ws, "public static java.util.concurrent.Future<Opening> openBeside() {");
		check(beside != null && beside.contains("new javax.swing.SwingWorker") && beside.contains(".execute()")
				&& !beside.contains(".run()") && beside.contains("return opening(workspace, game);"),
				"and openBeside opens on a worker it starts - execute(), never run() on the thread that asked: " + beside);
		Workspace.reset();
		Workspace.Opening answered = Workspace.await(Workspace.openBeside());
		java.util.List<String> heard = ctrmap.Ui.record();
		check(answered != null && Workspace.validate(null, true, null, answered) == null && heard.size() == 1
				&& heard.get(0).startsWith("Setup Error"),
				"and what it opened on the worker is said, as open()'s own answer is: " + heard);
	}

	/**
	 * A LAUNCH OPENS THE SETUP WIZARD OR THE GAME, NEVER BOTH (mutation sweep of 2026-10-25): the choice sat inline in
	 * createAndShowGUI, which no suite reaches, and inverted - a first-time user handed the archive check, everyone
	 * else the wizard - it passed every suite. It is asked of openFirst now, handed both, and start-up asks it with
	 * the answer it read from SetupWizard.shouldRunOnStartup.
	 */
	static void aLaunchOpensTheWizardOrTheGameNeverBoth() throws Exception {
		System.out.println("--- a launch opens the setup wizard for a first-time user and the game for anyone else, never both");
		java.util.List<String> ran = new java.util.ArrayList<>();
		TestWindow.openFirst(true, () -> ran.add("the wizard"), () -> ran.add("the game"));
		TestWindow.openFirst(false, () -> ran.add("the wizard"), () -> ran.add("the game"));
		check(ran.equals(java.util.Arrays.asList("the wizard", "the game")),
				"a first-time user is shown the wizard and anyone else their game, one of the two each time: " + ran);
		String main = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File("src/ctrmap/CtrmapMainframe.java")));
		String gui = CollisionReadTest.body(main, "private void createAndShowGUI() {");
		int asked = gui == null ? -1 : gui.indexOf("boolean wizard = ctrmap.setup.SetupWizard.shouldRunOnStartup();");
		int chosen = gui == null ? -1 : gui.indexOf("openFirst(wizard, () -> ctrmap.setup.SetupWizard.show(");
		check(asked >= 0 && chosen > asked, "and start-up asks it with what SetupWizard.shouldRunOnStartup answered, the"
				+ " wizard first (" + asked + " < " + chosen + ")");
	}

	/**
	 * WHAT THE OPENING WORKER THREW IS SORTED BY KIND (mutation sweep of 2026-10-25): inverted, Workspace.await said an
	 * Error - the JVM failing, not the game - as a game that would not open, and died on a cast for anything else;
	 * every suite passed, because none had a worker that threw. An Error is thrown on; anything else is a game that
	 * could not be opened, said with why.
	 */
	static void whatTheOpeningWorkerThrewIsSortedByKind() throws Exception {
		System.out.println("--- what the opening worker threw is sorted: an Error is thrown on, anything else said as a game"
				+ " that could not be opened");
		Throwable thrown = null;
		try {
			Workspace.await(failing(new StackOverflowError("the worker's own")));
		} catch (StackOverflowError expected) {
			thrown = expected;
		}
		check(thrown != null && "the worker's own".equals(thrown.getMessage()),
				"an Error on the opening worker is thrown on, not said as a game that would not open: " + thrown);
		java.util.List<String> problems = new java.util.ArrayList<>();
		ctrmap.WorkspaceSession none = Workspace.validate(null, false, problems,
				Workspace.await(failing(new IllegalStateException("a damaged archive"))));
		check(none == null && problems.size() == 1 && problems.get(0).startsWith("The game could not be opened")
				&& problems.get(0).contains("a damaged archive"),
				"and anything else is a game that could not be opened, said with why: " + problems);
	}

	/** A finished opening whose worker threw {@code t}, as a SwingWorker's does. */
	static java.util.concurrent.Future<Workspace.Opening> failing(Throwable t) {
		java.util.concurrent.FutureTask<Workspace.Opening> task = new java.util.concurrent.FutureTask<>(() -> {
			if (t instanceof Error) {
				throw (Error) t;
			}
			throw (Exception) t;
		});
		task.run();
		return task;
	}

	/**
	 * OPENGL IS NEVER ASKED BESIDE THE EVENT THREAD (2026-10-24). Sampled every 50 ms before the window showed, JOGL's
	 * start-up - GlSupport.available, asked on the event thread at the 3D views - held about 1.4 s of every launch, and
	 * starting it on a thread of its own looked like the cure. Measured instead: the probe beside an event thread that
	 * then asked for the answer DEADLOCKED 12 runs of 12 - JOGL's JAWTUtil initialiser invokes and waits on the event
	 * thread, which was waiting on GlSupport's lock. Every launch would have hung. Doing the native-window part on the
	 * event thread first avoided it on Windows; on Linux and macOS it cannot be measured here. So the probe stays on the
	 * event thread, and nothing starts it anywhere else.
	 */
	static void openGlIsNeverAskedBesideTheEventThread() throws Exception {
		System.out.println("--- OpenGL is never asked on a thread beside the event thread, which JOGL's start-up waits on");
		String gl = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File("src/ctrmap/humaninterface/GlSupport.java")));
		check(gl.contains("GLProfile.get(GLProfile.GL2)"), "control: the probe is read where it asks JOGL for a profile");
		check(!gl.contains("new Thread") && !gl.contains("Executor") && !gl.contains("SwingWorker"),
				"GlSupport starts no thread of its own to ask on");
		String main = SourceSeamTest.stripComments(DialogSeamTest.read(new java.io.File("src/ctrmap/CtrmapMainframe.java")));
		String body = CollisionReadTest.body(main, "public static void main(String[] args) {");
		check(body != null && !body.contains("GlSupport"),
				"and main(), which runs off the event thread, does not ask it - the window asks, on the event thread");
	}

	static ListDataListener listener(String name, List<String> off, int[] heard) {
		return new ListDataListener() {
			@Override
			public void intervalAdded(ListDataEvent e) {
				note("added to");
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				note("removed from");
			}

			@Override
			public void contentsChanged(ListDataEvent e) {
				note("changed in");
			}

			void note(String what) {
				heard[0]++;
				if (!SwingUtilities.isEventDispatchThread()) {
					off.add(what + " " + name + " on " + Thread.currentThread().getName());
				}
			}
		};
	}

	static JComboBox<?> box(Object tab, String name) throws Exception {
		Field f = ZoneLoadingPanel.class.getDeclaredField(name);
		f.setAccessible(true);
		return (JComboBox<?>) f.get(tab);
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
