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
		System.setProperty("java.awt.headless", "true");
		Workspace.reset();
		try {
			run();
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
		//one location name, empty: header, section length, one line entry, the line's single
		//u16 encrypted to 0 under the first key (0x7C89)
		byte[] names = new byte[30];
		names[0] = 1;
		names[2] = 1;
		names[12] = 0x10;
		names[20] = 0;
		names[24] = 1;
		names[28] = (byte) 0x89;
		names[29] = 0x7C;
		java.io.File text = new java.io.File(Scratch.dir("ctrmap_zonelist_text"), "names.bin");
		java.nio.file.Files.write(text.toPath(), names);
		ctrmap.formats.text.LocationNames.load(ws, text);
		ZoneLoadingPanel tab = new ZoneLoadingPanel(new ctrmap.LoadedZone(), MatrixEditFormGuardsTest.TOOLS,
				MatrixEditFormGuardsTest.EDITORS, MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI);
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
