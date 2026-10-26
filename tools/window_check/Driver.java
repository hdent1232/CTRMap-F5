package windowcheck;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * THE EDITOR, DRIVEN THROUGH ITS OWN WINDOW THE WAY THE OWNER DRIVES IT - the release's own check (tools/window_check.py).
 *
 * <p>WHY. 1.0.4 passed 219 suites and could not be used: a zone tool covered the zone list with no visible way back, a
 * refusal dialog opened on every launch, and every refill of the zone list froze the window 13.8 s. The owner found all
 * three by downloading the release. No suite started the packaged program on a real workspace, loaded a zone, opened a
 * tool and came back. This does, on COPIES of the owner's workspace and game, with every setting held in memory
 * (MemPrefs) and the log and backup folders in scratch: it answers each dialog with its safe answer, samples every
 * 50 ms whether a window of the editor is on screen while the event thread is stalled, waits for the zone list, opens
 * a zone through "Load this zone" - and in trap mode first opens a zone tool over the list and comes back by the way
 * back that is on screen. What it saw is printed; tools/window_check.py judges it.
 *
 * <p>args: run folder, mode (load | trap), workspace copy, game copy
 */
public final class Driver {

	static volatile boolean seeded;
	static File shots;
	static int shot;
	/** "load" opens a zone once the window settles; "trap" first opens a zone tool over the zone list and comes back. */
	static String mode = "load";
	/** Whether the log has said the harness activated the main window - said once, done every poll it is needed. */
	static volatile boolean activationSaid;
	/** When a window of the editor was first on screen, ms after the sampler started; -1 until then. */
	static volatile long firstOnScreen = -1;
	/** The start of an event-thread stall seen while a window was on screen, or -1. */
	static volatile long seenFrozenFrom = -1;
	/** The longest event-thread stall while a window of the editor was on screen, ms. */
	static volatile long worstSeenFrozen;
	/** The same, split at the moment Load was pressed: start-up and the zone list, then the zone load. */
	static volatile long worstFrozenBeforeLoad;
	static volatile long worstFrozenInLoad;
	/** Where the event thread was, each 50 ms sample of a freeze while on screen: innermost editor frames -> count. */
	static final java.util.Map<String, Integer> seenFrozenAt = new java.util.concurrent.ConcurrentHashMap<>();
	/** Before any window is on screen, each 50 ms: the innermost editor frames each start-up thread is in -> count. */
	static final java.util.Map<String, Integer> beforeByWhere = new java.util.concurrent.ConcurrentHashMap<>();

	public static void main(String[] args) throws Exception {
		File root = new File(args[0]).getAbsoluteFile();
		mode = args[1];
		File workspace = new File(args[2]).getAbsoluteFile();
		File game = new File(args[3]).getAbsoluteFile();
		shots = new File(root, "shots-" + mode);
		shots.mkdirs();
		logFile = new File(root, "live-" + mode + ".log");
		logFile.delete();
		//A HARD END: whatever the watcher is waiting on, the run stops at 6 minutes and says so
		Thread deadline = new Thread(() -> {
			try {
				Thread.sleep(360_000);
			} catch (InterruptedException e) {
				return;
			}
			say("HARNESS DEADLINE: 360 s passed; stopping. Worst stalls by editor frame " + stalls);
			Runtime.getRuntime().halt(3);
		}, "deadline");
		deadline.setDaemon(true);
		deadline.start();
		System.setProperty("ctrmap.log.root", new File(root, "logs").getPath());
		System.setProperty("ctrmap.vault.root", new File(root, "vault").getPath());
		Preferences ws = Preferences.userRoot().node("ctrmap.Workspace");
		ws.put("WORKSPACE_PATH", workspace.getPath());
		ws.put("GAMEDIR_PATH", game.getPath());
		ws.put("ESPICA_PATH", "");
		ws.putBoolean("TILESET_DEFAULT", true);
		ws.put("TILESET_PATH", "");
		Preferences.userRoot().node("ctrmap.CtrmapMainframe").putBoolean("ZONE_HINT_SHOWN", true);
		Preferences.userRoot().node("ctrmap.setup").putBoolean("SKIP_SETUP_ON_STARTUP", false);
		seeded = true;
		say("HARNESS mode " + mode + ", workspace " + workspace + ", game " + game);
		Thread w = new Thread(Driver::watch, "harness");
		w.setDaemon(true);
		w.start();
		Thread sampler = new Thread(Driver::sample, "lag-sampler");
		sampler.setDaemon(true);
		sampler.start();
		ctrmap.CtrmapMainframe.main(new String[0]);
	}

	static void watch() {
		long start = System.currentTimeMillis();
		Set<Window> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		long quietSince = System.currentTimeMillis();
		int phase = 0;
		int rowsSeen = -1;
		try {
			while (System.currentTimeMillis() - start < 300_000) {
				Thread.sleep(500);
				List<Window> wins = onEdt(() -> {
					List<Window> out = new ArrayList<>();
					for (Window x : Window.getWindows()) {
						if (x.isShowing()) {
							out.add(x);
						}
					}
					return out;
				});
				Frame early = mainFrame(wins);
				if (early != null) {
					//THE OWNER IS AT THE EDITOR: a dialog nobody just asked for waits (Ui.untilInUse) until a window of
					//the program is activated, and on the unattended desktop nothing activates one. Posting the
					//activation the owner's click would make is what lets that dialog come up, as it does for them.
					onEdt(() -> {
						java.awt.Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
						if (active == null || !active.isShowing()) {
							if (!activationSaid) {
								activationSaid = true;
								say("ACTIVATING the main window at " + secs(start) + "s: no window of the editor was active");
							}
							java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
									new java.awt.event.WindowEvent(early, java.awt.event.WindowEvent.WINDOW_ACTIVATED));
						}
						return null;
					});
					int rows = onEdt(() -> {
						ctrmap.humaninterface.ZoneBrowserPane b = find(early, ctrmap.humaninterface.ZoneBrowserPane.class);
						return b == null ? -2 : b.zoneRows().getModel().getSize();
					});
					if (rows != rowsSeen) {
						say("ZONE LIST has " + rows + " rows at " + secs(start) + "s");
						rowsSeen = rows;
					}
				}
				boolean busy = false;
				for (Window x : wins) {
					if (!(x instanceof Dialog)) {
						continue;
					}
					busy = true;
					quietSince = System.currentTimeMillis();
					if (seen.add(x)) {
						String title = ((Dialog) x).getTitle();
						String text = onEdt(() -> texts(x));
						say("DIALOG at " + secs(start) + "s, '" + title + "':\n" + indent(text));
						snap(x, "dialog-" + title);
					}
					JOptionPane op = onEdt(() -> find(x, JOptionPane.class));
					if (op != null) {
						answer(x);
					}
				}
				long quiet = System.currentTimeMillis() - quietSince;
				if (!busy && quiet > 8000) {
					Frame f = mainFrame(wins);
					if (f == null) {
						continue;
					}
					if (phase == 0) {
						say("MAIN WINDOW settled at " + secs(start) + "s");
						inspect(f);
						snap(f, "main-before-load");
						if ("trap".equals(mode)) {
							trapCheck(f);
						}
						tryLoad(f);
						phase = 1;
						quietSince = System.currentTimeMillis();
					} else if (phase == 1) {
						say("AFTER LOAD settled at " + secs(start) + "s (" + (System.nanoTime() - loadPressedAt) / 1_000_000
								+ " ms after Load was pressed, 8 s of quiet included)");
						inspect(f);
						snap(f, "main-after-load");
						phase = 2;
						break;
					}
				}
			}
			say("STALLS BY EDITOR FRAME " + stalls);
			say("WINDOW FIRST ON SCREEN at " + firstOnScreen + " ms; WORST FREEZE WHILE ON SCREEN " + worstSeenFrozen
					+ " ms" + (seenFrozenFrom >= 0 ? " (one still running from " + seenFrozenFrom + " ms)" : ""));
			say("WORST FREEZE ON SCREEN BEFORE LOAD " + worstFrozenBeforeLoad + " ms; IN THE ZONE LOAD " + worstFrozenInLoad
					+ " ms" + (loadPressedAt == 0 ? " (Load was never pressed)" : ""));
			say("FROZEN-ON-SCREEN SAMPLES BY WHERE (50 ms each): " + seenFrozenAt);
			say("BEFORE-WINDOW SAMPLES BY WHERE (50 ms each): " + new java.util.TreeMap<>(beforeByWhere));
			if (phase < 2) {
				say("HARNESS TIMED OUT in phase " + phase);
				for (Window x : Window.getWindows()) {
					if (x.isShowing()) {
						snap(x, "timeout-" + x.getClass().getSimpleName());
					}
				}
				//EVERY THREAD: a start that never settled stopped somewhere, and the event thread alone does not say where
				for (java.util.Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()) {
					StringBuilder sb = new StringBuilder("THREAD " + t.getKey().getName() + " " + t.getKey().getState());
					int shown = 0;
					for (StackTraceElement e : t.getValue()) {
						sb.append("\n      ").append(e);
						if (++shown >= 25) {
							break;
						}
					}
					say(sb.toString());
				}
			}
			say("HARNESS DONE in phase " + phase);
		} catch (Throwable t) {
			say("HARNESS FAILED: " + t);
			t.printStackTrace(System.out);
		}
		System.out.flush();
		Runtime.getRuntime().halt(0);
	}

	/** When the event thread last ran the sampler's heartbeat, in nanoTime. */
	static volatile long beat = System.nanoTime();
	/** How many stalls of each top frame were seen, for the summary. */
	static final java.util.Map<String, Integer> stalls = new java.util.TreeMap<>();

	/**
	 * THE LAG, LOCATED: a heartbeat is posted to the event thread every 50 ms; when one has not run for 300 ms the event
	 * thread is stuck. What a person feels is a stall while a window of the editor is ON SCREEN - one before any window
	 * shows is start-up time, which is reported by where it went, not as a freeze.
	 */
	static void sample() {
		long start = System.nanoTime();
		boolean inStall = false;
		long stallFrom = 0;
		while (true) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				return;
			}
			SwingUtilities.invokeLater(() -> beat = System.nanoTime());
			long late = (System.nanoTime() - beat) / 1_000_000;
			boolean onScreen = false;
			for (java.awt.Frame f : java.awt.Frame.getFrames()) {
				if (f.isShowing()) {
					onScreen = true;
				}
			}
			long nowMs = (System.nanoTime() - start) / 1_000_000;
			if (!onScreen && firstOnScreen < 0) {
				for (java.util.Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()) {
					String who = t.getKey().getName();
					if (!who.equals("main") && !who.startsWith("AWT-EventQueue")) {
						continue;
					}
					String at = editorFrames(t.getValue(), 2);
					if (!at.isEmpty()) {
						beforeByWhere.merge((who.equals("main") ? "main " : "edt ") + at, 1, Integer::sum);
					}
				}
			}
			if (onScreen && firstOnScreen < 0) {
				firstOnScreen = nowMs;
				say("WINDOW FIRST ON SCREEN at " + nowMs + " ms" + (late > 300 ? " (the event thread " + late + " ms behind)" : ""));
			}
			if (late > 300 && onScreen) {
				for (java.util.Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()) {
					if (t.getKey().getName().startsWith("AWT-EventQueue")) {
						String at = editorFrames(t.getValue(), 3);
						seenFrozenAt.merge(at.isEmpty() ? "(no editor frame)" : at, 1, Integer::sum);
					}
				}
				if (seenFrozenFrom < 0) {
					//ONLY THE PART SOMEBODY COULD SEE: a stall that began before the window showed is counted from the
					//moment it showed (counting from the stall's start put 2.9 s of start-up into "frozen on screen")
					seenFrozenFrom = Math.max(nowMs - late, firstOnScreen);
				}
			} else if (seenFrozenFrom >= 0) {
				long froze = nowMs - seenFrozenFrom;
				worstSeenFrozen = Math.max(worstSeenFrozen, froze);
				if (loadPressedAt == 0) {
					worstFrozenBeforeLoad = Math.max(worstFrozenBeforeLoad, froze);
				} else {
					worstFrozenInLoad = Math.max(worstFrozenInLoad, froze);
				}
				say("  FROZEN WHILE ON SCREEN: " + froze + " ms, from " + seenFrozenFrom + " ms");
				seenFrozenFrom = -1;
			}
			if (late > 300 && !inStall) {
				Thread edt = null;
				for (Thread t : Thread.getAllStackTraces().keySet()) {
					if (t.getName().startsWith("AWT-EventQueue")) {
						edt = t;
					}
				}
				if (edt != null) {
					inStall = true;
					stallFrom = System.nanoTime();
					StackTraceElement[] st = edt.getStackTrace();
					String top = editorFrames(st, 1);
					stalls.merge(top.isEmpty() ? "?" : top, 1, Integer::sum);
					StringBuilder sb = new StringBuilder("STALL at " + (stallFrom - start) / 1_000_000_000 + "s, editor frame "
							+ (top.isEmpty() ? "?" : top) + ":");
					int shown = 0;
					for (StackTraceElement e : st) {
						if (shown++ > 18) {
							break;
						}
						sb.append("\n      ").append(e);
					}
					say(sb.toString());
				}
			} else if (late <= 300 && inStall) {
				inStall = false;
				say("  stall ended after " + (System.nanoTime() - stallFrom) / 1_000_000 + " ms");
			}
		}
	}

	/** The innermost {@code most} editor frames of a stack, innermost first, joined by " < "; empty when there are none. */
	static String editorFrames(StackTraceElement[] stack, int most) {
		StringBuilder at = new StringBuilder();
		int kept = 0;
		for (StackTraceElement e : stack) {
			if (e.getClassName().startsWith("ctrmap.") && kept++ < most) {
				at.append(kept == 1 ? "" : " < ").append(e.getClassName().replace("ctrmap.", "")).append('.')
						.append(e.getMethodName()).append(':').append(e.getLineNumber());
			}
		}
		return at.toString();
	}

	static Frame mainFrame(List<Window> wins) {
		for (Window x : wins) {
			if (x instanceof javax.swing.JFrame) {
				return (Frame) x;
			}
		}
		return null;
	}

	static void inspect(Frame f) throws Exception {
		onEdt(() -> {
			StringBuilder sb = new StringBuilder();
			sb.append("  frame '").append(f.getTitle()).append("' ").append(f.getWidth()).append("x").append(f.getHeight())
					.append("\n");
			JTabbedPane tabs = find(f, JTabbedPane.class);
			if (tabs != null) {
				sb.append("  selected tab: '").append(tabs.getTitleAt(tabs.getSelectedIndex())).append("' of [");
				for (int i = 0; i < tabs.getTabCount(); i++) {
					sb.append(i == 0 ? "" : ", ").append(tabs.getTitleAt(i));
				}
				sb.append("]\n");
			}
			ctrmap.humaninterface.ZoneBrowserPane b = find(f, ctrmap.humaninterface.ZoneBrowserPane.class);
			if (b == null) {
				sb.append("  NO ZoneBrowserPane in the window\n");
			} else {
				sb.append("  browser showing=").append(b.isShowing()).append(" at ").append(onFrame(b, f))
						.append(", rows=").append(b.zoneRows().getModel().getSize()).append(", highlighted zone=")
						.append(b.highlighted()).append("\n");
				JButton load = b.loadButton();
				sb.append("  '").append(load.getText()).append("' showing=").append(load.isShowing()).append(" enabled=")
						.append(load.isEnabled()).append(" at ").append(onFrame(load, f)).append("\n");
			}
			ctrmap.humaninterface.ZoneLoadingPanel p = find(f, ctrmap.humaninterface.ZoneLoadingPanel.class);
			if (p != null) {
				sb.append("  zone panel: dropdown items=").append(p.getLoadedZoneCount());
				Object lz = field(p, "loadedZone");
				if (lz instanceof ctrmap.LoadedZone) {
					ctrmap.LoadedZone z = (ctrmap.LoadedZone) lz;
					sb.append(", open zone=").append(z.open() == null ? "none" : "index " + z.index());
				} else {
					sb.append(", open zone=UNREADABLE (").append(lz).append(")");
				}
				sb.append("\n");
			}
			say(sb.toString());
			return null;
		});
	}

	/**
	 * THE TRAP, IN THE REAL WINDOW: a zone tool is opened over the zone list from the zone actions bar, the way back
	 * must be a visible button, and pressing it must put the zone list and its Load button back on screen.
	 */
	static void trapCheck(Frame f) throws Exception {
		String opened = onEdt(() -> {
			for (AbstractButton btn : all(f, AbstractButton.class)) {
				if ("Re-link zone".equals(btn.getText()) && btn.isShowing()) {
					SwingUtilities.invokeLater(btn::doClick);
					return "pressed 'Re-link zone' on the zone actions bar";
				}
			}
			return "NO visible 'Re-link zone' on the zone actions bar";
		});
		say("TRAP CHECK: " + opened);
		Thread.sleep(3000);
		snap(f, "trap-tool-open");
		String back = onEdt(() -> {
			ctrmap.humaninterface.ZoneBrowserPane b = find(f, ctrmap.humaninterface.ZoneBrowserPane.class);
			String browserNow = b == null ? "absent" : (b.isShowing() ? "SHOWING" : "hidden");
			for (AbstractButton btn : all(f, AbstractButton.class)) {
				if ("Back to the zone list".equals(btn.getText())) {
					String seen = "zone list " + browserNow + " with the tool open; way back showing=" + btn.isShowing()
							+ " bordered=" + btn.isBorderPainted() + " at " + onFrame(btn, f);
					SwingUtilities.invokeLater(btn::doClick);
					return seen;
				}
			}
			return "zone list " + browserNow + " with the tool open; NO 'Back to the zone list' anywhere in the window";
		});
		say("TRAP CHECK: " + back);
		Thread.sleep(2000);
		String after = onEdt(() -> {
			ctrmap.humaninterface.ZoneBrowserPane b = find(f, ctrmap.humaninterface.ZoneBrowserPane.class);
			return b == null ? "zone list absent" : "zone list showing=" + b.isShowing() + ", load showing="
					+ b.loadButton().isShowing();
		});
		say("TRAP CHECK after pressing it: " + after);
		snap(f, "trap-after-back");
	}

	static void tryLoad(Frame f) throws Exception {
		onEdt(() -> {
			ctrmap.humaninterface.ZoneBrowserPane b = find(f, ctrmap.humaninterface.ZoneBrowserPane.class);
			if (b == null || b.zoneRows().getModel().getSize() == 0) {
				say("LOAD ATTEMPT: nothing to pick - browser " + (b == null ? "absent" : "empty"));
				return null;
			}
			int row = Math.min(5, b.zoneRows().getModel().getSize() - 1);
			b.zoneRows().setSelectedIndex(row);
			say("LOAD ATTEMPT: highlighted row " + row + " (" + b.zoneRows().getSelectedValue() + "), zone "
					+ b.highlighted() + "; pressing '" + b.loadButton().getText() + "'");
			loadPressedAt = System.nanoTime();
			//QUEUED, NOT RUN HERE: the load shows modal dialogs, and a click run inside this wait would make the
			//harness wait on a dialog only the harness can answer (it did, 2026-10-23)
			SwingUtilities.invokeLater(b.loadButton()::doClick);
			return null;
		});
	}

	/** The safe answer: Not now, else Cancel, else No, else OK, else Close - a form is backed out of, a notice acknowledged. */
	static void answer(Window x) throws Exception {
		onEdt(() -> {
			//"Not now": an older build's "Update available" once a newer release is out - unanswered, a run waited out its
			//300 s on it and never loaded a zone (measured 2026-10-24 on 1.0.3, after 1.0.5 was published)
			for (String want : new String[]{"Not now", "Cancel", "No", "OK", "Close"}) {
				for (AbstractButton btn : all(x, AbstractButton.class)) {
					if (want.equals(btn.getText()) && btn.isShowing()) {
						say("  answering '" + want + "'");
						SwingUtilities.invokeLater(btn::doClick);
						return null;
					}
				}
			}
			say("  NO SAFE ANSWER button found");
			return null;
		});
	}

	static String texts(Container c) {
		StringBuilder sb = new StringBuilder();
		for (Component k : all(c, Component.class)) {
			String t = null;
			if (k instanceof JLabel) {
				t = ((JLabel) k).getText();
			} else if (k instanceof JTextComponent) {
				t = ((JTextComponent) k).getText();
			} else if (k instanceof AbstractButton) {
				t = "[" + ((AbstractButton) k).getText() + "]";
			}
			if (t != null && !t.trim().isEmpty()) {
				sb.append(t).append("\n");
			}
		}
		return sb.toString();
	}

	static String onFrame(Component c, Frame f) {
		Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), f);
		return r.x + "," + r.y + " " + r.width + "x" + r.height;
	}

	static Object field(Object o, String name) {
		try {
			java.lang.reflect.Field fl = o.getClass().getDeclaredField(name);
			fl.setAccessible(true);
			return fl.get(o);
		} catch (Exception e) {
			return "unreadable " + e;
		}
	}

	static <T> T find(Container c, Class<T> type) {
		List<T> l = all(c, type);
		return l.isEmpty() ? null : l.get(0);
	}

	static <T> List<T> all(Container c, Class<T> type) {
		List<T> out = new ArrayList<>();
		for (Component k : c.getComponents()) {
			if (type.isInstance(k)) {
				out.add(type.cast(k));
			}
			if (k instanceof Container) {
				out.addAll(all((Container) k, type));
			}
		}
		return out;
	}

	static void snap(Window w, String name) {
		try {
			onEdt(() -> {
				if (!(w instanceof RootPaneContainer) || w.getWidth() <= 0 || w.getHeight() <= 0) {
					return null;
				}
				javax.swing.JRootPane rp = ((RootPaneContainer) w).getRootPane();
				BufferedImage img = new BufferedImage(rp.getWidth(), rp.getHeight(), BufferedImage.TYPE_INT_RGB);
				java.awt.Graphics2D g = img.createGraphics();
				rp.paint(g);
				g.dispose();
				File out = new File(shots, String.format("%02d-%s.png", ++shot, name.replaceAll("[^A-Za-z0-9-]+", "_")));
				javax.imageio.ImageIO.write(img, "png", out);
				say("  picture: " + out.getName());
				return null;
			});
		} catch (Exception e) {
			say("  picture failed: " + e);
		}
	}

	interface Edt<T> {

		T run() throws Exception;
	}

	static <T> T onEdt(Edt<T> body) throws Exception {
		AtomicReference<T> got = new AtomicReference<>();
		AtomicReference<Exception> failed = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				got.set(body.run());
			} catch (Exception e) {
				failed.set(e);
			}
		});
		if (failed.get() != null) {
			throw failed.get();
		}
		return got.get();
	}

	static String secs(long start) {
		return String.valueOf((System.currentTimeMillis() - start) / 1000);
	}

	static String indent(String s) {
		return "    " + s.trim().replace("\n", "\n    ");
	}

	static volatile long loadPressedAt;
	static File logFile;

	/** Printed, and appended to the run's own log as it happens - a run that hangs keeps what it measured. */
	static synchronized void say(String s) {
		System.out.println(s);
		System.out.flush();
		if (logFile != null) {
			try {
				java.nio.file.Files.write(logFile.toPath(), (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			} catch (java.io.IOException ignored) {
				//the console copy stands
			}
		}
	}
}
