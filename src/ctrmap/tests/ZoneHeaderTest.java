package ctrmap.tests;

import ctrmap.LoadedZone;
import ctrmap.Workspace;
import ctrmap.WorkspaceSession;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneHeader;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * A zone header is written back as it was read, and the Zone tab writes back only what the user
 * changed (F115).
 *
 * <p>WHY THIS SUITE EXISTS. The parser dropped the bits it does not name - 0x1E bits 14-15 and
 * 0x20 bits 5-9 - so a zone holding any of them read as modified the moment it opened and was saved
 * stripped. And the Zone tab wrote every field back from its boxes: an ORAS map type 1 came back as
 * 3, a type the box cannot show as 0xFF, an unknown XY weather as 29, and the 6-bit OL value as 0 or
 * 1 - on every save, edited or not. A raw value a box could not hold threw out of the load.
 *
 * <p>ORDER: needs no dump and no display; writes only under {@link Scratch}.
 *
 * Usage: java ctrmap.tests.ZoneHeaderTest
 */
public class ZoneHeaderTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		Workspace.reset();
		try {
			theParserCarriesWhatItDoesNotName();
			theZoneTabWritesBackOnlyWhatChanged();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/** A header holding the unnamed bits, an ORAS type 1, an unknown weather and OL value 5. */
	static byte[] header() {
		byte[] h = new byte[ZoneHeader.SIZE];
		h[0] = 1;                                      //map type 1
		int x1C = 3 | (5 << 10);                       //parent map 3, OL value 5
		h[0x1C] = (byte) x1C;
		h[0x1D] = (byte) (x1C >> 8);
		int x1E = 12 | (1 << 5) | (4 << 7) | 0xC000;   //weather 12 (no box row for it), skybox, battle BG 4, bits 14-15
		h[0x1E] = (byte) x1E;
		h[0x1F] = (byte) (x1E >> 8);
		int x20 = 9 | 0x03E0 | (1 << 11);              //transition 9 (no box row for it), bits 5-9, running
		h[0x20] = (byte) x20;
		h[0x21] = (byte) (x20 >> 8);
		return h;
	}

	static void theParserCarriesWhatItDoesNotName() {
		System.out.println("--- a header is written back as it was read (F115)");
		byte[] h = header();
		for (GameType g : new GameType[]{GameType.ORAS, GameType.XY}) {
			byte[] back = new ZoneHeader(h, g).assembleData();
			check(Arrays.equals(back, h), g + ": the unnamed bits of 0x1E and 0x20 survive a read and a write");
		}
		byte[] longer = Arrays.copyOf(h, h.length + 4);
		longer[longer.length - 1] = 0x7E;
		check(Arrays.equals(new ZoneHeader(longer, GameType.ORAS).assembleData(), longer), "and so do bytes past the 0x38 the parser reads");
		try {
			new ZoneHeader(Arrays.copyOf(h, 0x20), GameType.ORAS);
			check(false, "a header cut short is refused");
		} catch (IllegalStateException said) {
			check(said.getMessage().contains("0x38") || said.getMessage().contains("56"), "a header cut short is refused, in words: " + said.getMessage());
		} catch (RuntimeException other) {
			check(false, "a header cut short is refused in words (it threw " + other + ")");
		}
	}

	static void theZoneTabWritesBackOnlyWhatChanged() throws Exception {
		System.out.println("--- the Zone tab writes back only the boxes the user changed (F115)");
		WorkspaceSession ws = MapResizeTest.game("zonetab");
		check(Workspace.game() == GameType.ORAS, "control: the game the tab maps through is ORAS");
		File f = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		Files.write(f.toPath(), HandedGameTest.container("ZO", header(), entities, amx));
		Zone z = new Zone(new ZO(f, ws), GameType.ORAS);
		LoadedZone lz = new LoadedZone();
		ZoneLoadingPanel tab = new ZoneLoadingPanel(lz, MatrixEditFormGuardsTest.TOOLS, MatrixEditFormGuardsTest.EDITORS,
				MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI);
		ctrmap.Ui.record();
		tab.loadZone(z);
		check(loaded(tab), "the zone loads into the tab, a weather and a type its boxes cannot all show included");
		tab.store(false);
		check(z.header.mapType == 1, "an ORAS map type 1 is still 1, not 3 (" + z.header.mapType + ")");
		check(z.header.OLvalue == 5, "the 6-bit OL value is still 5, not 0 or 1 (" + z.header.OLvalue + ")");
		check(z.header.weather == 12 && z.header.mapChange == 9, "a weather and a transition no box can show are as read ("
				+ z.header.weather + ", " + z.header.mapChange + ")");
		check(Arrays.equals(z.header.assembleData(), header()), "and the header the tab saves is the header it read");
	}

	static boolean loaded(ZoneLoadingPanel tab) throws Exception {
		java.lang.reflect.Field f = ZoneLoadingPanel.class.getDeclaredField("loaded");
		f.setAccessible(true);
		return f.getBoolean(tab);
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
