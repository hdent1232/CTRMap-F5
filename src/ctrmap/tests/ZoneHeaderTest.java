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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
			theFlagBitsAreTheGamesOwn();
			theZoneTabWritesBackEveryFlagItShows();
			theRawFlagsAndTheMusicSayWhatTheyAre();
			theZonesPropertiesAreADocumentOfTheZonesHistory();
		} finally {
			ctrmap.Ui.stopRecording();
			Workspace.reset();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		System.exit(fails == 0 ? 0 : 1);
	}

	/**
	 * Raw bytes are said to be raw and folded away, and the zone's music is every season slot that held
	 * it (F221). The tab showed pk3DS's 'UnknownFlag' and 'UnknownFlags' among the named fields, and
	 * wrote the music to Spring's slot alone of the header's four.
	 */
	static void theRawFlagsAndTheMusicSayWhatTheyAre() throws Exception {
		System.out.println("--- the Zone tab folds its raw flags, and the music moves in every slot that held it (F221)");
		ZoneHeader h = new ZoneHeader(header(), GameType.ORAS);
		h.BGMSpring = 7;
		h.BGMSummer = 7;
		h.BGMAutumn = 7;
		h.BGMWinter = 3;
		h.setMusic(9);
		check(h.BGMSpring == 9 && h.BGMSummer == 9 && h.BGMAutumn == 9 && h.BGMWinter == 3,
				"setting the music moves every slot that held Spring's track and leaves a different one: "
				+ h.BGMSpring + "/" + h.BGMSummer + "/" + h.BGMAutumn + "/" + h.BGMWinter);
		h.setMusic(11);
		check(h.BGMSummer == 11 && h.BGMAutumn == 11 && h.BGMWinter == 3, "and a second change moves them again");

		WorkspaceSession ws = MapResizeTest.game("zoneraw");
		File f = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		byte[] hb = header();
		MapResizeTest.putI32(hb, 0x08, 7);
		MapResizeTest.putI32(hb, 0x0C, 7);
		MapResizeTest.putI32(hb, 0x10, 7);
		MapResizeTest.putI32(hb, 0x14, 3);
		Files.write(f.toPath(), HandedGameTest.container("ZO", hb, entities, amx));
		Zone z = new Zone(new ZO(f, ws), GameType.ORAS);
		ZoneLoadingPanel tab = new ZoneLoadingPanel(new LoadedZone(), MatrixEditFormGuardsTest.TOOLS, MatrixEditFormGuardsTest.EDITORS,
				MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		ctrmap.Ui.record();
		tab.loadZone(z);
		javax.swing.JLabel label = (javax.swing.JLabel) field(tab, "uFlabel");
		javax.swing.JComponent number = (javax.swing.JComponent) field(tab, "unknownFlags");
		javax.swing.JComponent bit = (javax.swing.JComponent) field(tab, "unknownFlag");
		check(label.isVisible() && !number.isVisible() && !bit.isVisible(),
				"the raw flag fields are folded behind their label when the tab opens: " + label.getText());
		check(label.getToolTipText() != null && label.getToolTipText().contains("0x28")
				&& bit.getToolTipText() != null && bit.getToolTipText().contains("written back exactly"),
				"and label and fields say what the bytes are and that they are written back as read");
		label.dispatchEvent(new java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, false));
		check(number.isVisible() && bit.isVisible() && label.getText().startsWith("[-]"), "a click on the label shows them");
		javax.swing.JFormattedTextField bgm = (javax.swing.JFormattedTextField) field(tab, "bgmSpring");
		check(Integer.valueOf(7).equals(bgm.getValue()), "control: the tab shows Spring's track, 7: " + bgm.getValue());
		bgm.setValue(9);
		tab.store(false);
		check(z.header.BGMSpring == 9 && z.header.BGMSummer == 9 && z.header.BGMAutumn == 9 && z.header.BGMWinter == 3,
				"the tab writes the new track to every slot that held the old one, and leaves Winter's: "
				+ z.header.BGMSpring + "/" + z.header.BGMSummer + "/" + z.header.BGMAutumn + "/" + z.header.BGMWinter);
		check(bgm.getToolTipText() != null && bgm.getToolTipText().contains("four music slots"),
				"and the music box says the header holds four");
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
				MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
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

	/** The header fixture with its flag word, at 0x28, set to {@code word}. */
	static byte[] withFlags(int word) {
		byte[] h = header();
		MapResizeTest.putI32(h, 0x28, word);
		return h;
	}

	/**
	 * Where each flag sits is the game's, asked of its profile (F207). The header read and wrote the
	 * flag word as "X/Y, or else ORAS", so a loose zone opened with no game - and any game nobody
	 * measured - was handed ORAS's bits on read and on write.
	 */
	static void theFlagBitsAreTheGamesOwn() {
		System.out.println("--- a zone header's flag bits are where its own game keeps them (F207)");
		int oras = 1 | 1 << 1 | 1 << 12 | 1 << 13 | 1 << 16;
		ZoneHeader o = new ZoneHeader(withFlags(oras), GameType.ORAS);
		check(o.enableGhosting && o.enableFlashableDarkness && o.enableBreathFX && o.enableDowsingMachine && o.enable3D
				&& !o.enableSpecialWalking, "ORAS: ghosting, flashable darkness, breath, dowsing and 3D at bits 0, 1, 12, 13, 16");
		ZoneHeader x = new ZoneHeader(withFlags(1 | 1 << 11 | 1 << 12 | 1 << 15), GameType.XY);
		check(x.enableGhosting && x.enableBreathFX && x.enableDowsingMachine && x.enable3D && x.enableSpecialWalking
				&& !x.enableFlashableDarkness, "X/Y: breath, dowsing and 3D one bit lower, and special walking on while bit 2 is clear");
		for (GameType g : new GameType[]{GameType.ORAS, GameType.XY}) {
			byte[] h = withFlags(g == GameType.ORAS ? oras : 1 << 2 | 1 << 15 | 0x5A00000);
			check(Arrays.equals(new ZoneHeader(h, g).assembleData(), h), g + ": the flag word round-trips");
		}
		o.enable3D = false;
		o.enableFlashableDarkness = false;
		check(MapResizeTest.i32(o.assembleData(), 0x28) == (1 | 1 << 12 | 1 << 13),
				"and clearing two ORAS flags clears exactly their bits: 0x" + Integer.toHexString(MapResizeTest.i32(o.assembleData(), 0x28)));
		List<GameType> unmeasured = new ArrayList<>();
		for (GameType g : GameType.values()) {
			if (ctrmap.gamedef.GameProfile.of(g).zoneFlagLayout() == null) {
				unmeasured.add(g);
			}
		}
		unmeasured.add(null);
		check(unmeasured.size() >= 2 && !unmeasured.contains(GameType.ORAS) && !unmeasured.contains(GameType.XY),
				"control: ORAS and X/Y have a layout, and the others and a zone with no game do not: " + unmeasured);
		for (GameType g : unmeasured) {
			byte[] h = withFlags(oras);
			ZoneHeader u = new ZoneHeader(h, g);
			boolean none = !(u.enableGhosting || u.enableFlashableDarkness || u.enableBreathFX || u.enableDowsingMachine
					|| u.enable3D || u.enableSpecialWalking);
			check(none && Arrays.equals(u.assembleData(), h), (g == null ? "no game" : g.toString())
					+ ": no flag is read with somebody else's bits, and the word is carried as read");
			u.enable3D = true;
			String why;
			try {
				u.assembleData();
				why = null;
			} catch (IllegalStateException refused) {
				why = refused.getMessage();
			}
			check(why != null && why.contains("never recorded"), "and a flag set anyway is refused, not written to a guessed bit: " + why);
		}
	}

	/**
	 * Every flag the Zone tab shows from a header is written back to it (F207). The flashable-darkness
	 * box was shown and editable on ORAS, and its value never reached the header: unticking it did
	 * nothing. Asked twice - of the tab itself, and of its source for every box it shows from a header.
	 */
	static void theZoneTabWritesBackEveryFlagItShows() throws Exception {
		System.out.println("--- the Zone tab writes back every flag box it shows (F207)");
		WorkspaceSession ws = MapResizeTest.game("zoneflags");
		File f = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		Files.write(f.toPath(), HandedGameTest.container("ZO", withFlags(1 << 1), entities, amx));
		Zone z = new Zone(new ZO(f, ws), GameType.ORAS);
		ZoneLoadingPanel tab = new ZoneLoadingPanel(new LoadedZone(), MatrixEditFormGuardsTest.TOOLS, MatrixEditFormGuardsTest.EDITORS,
				MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, UndoHistoryTest.overOneNumber(new int[]{0}), new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		ctrmap.Ui.record();
		tab.loadZone(z);
		javax.swing.JCheckBox flash = (javax.swing.JCheckBox) field(tab, "flash");
		check(flash.isSelected() && flash.isEnabled(), "control: an ORAS zone with flashable darkness shows it ticked and editable");
		flash.setSelected(false);
		tab.store(false);
		check(!z.header.enableFlashableDarkness && (MapResizeTest.i32(z.header.assembleData(), 0x28) & 1 << 1) == 0,
				"and unticking it clears the header's flag and its bit");

		String src = new String(Files.readAllBytes(new File("src/ctrmap/humaninterface/ZoneLoadingPanel.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		java.util.regex.Matcher shown = java.util.regex.Pattern.compile("(\\w+)\\.setSelected\\(h\\.(\\w+)\\)").matcher(src);
		List<String> pairs = new ArrayList<>();
		List<String> dropped = new ArrayList<>();
		while (shown.find()) {
			pairs.add(shown.group(1));
			if (!src.contains("zone.header." + shown.group(2) + " = " + shown.group(1) + ".isSelected()")) {
				dropped.add(shown.group(1) + " -> " + shown.group(2));
			}
		}
		check(pairs.containsAll(Arrays.asList("flash", "specWalk", "coldbreath", "run")), "control: the boxes the tab shows from"
				+ " a header are read off its source (" + pairs.size() + ", flash, specWalk, coldbreath and run among them)");
		check(dropped.isEmpty(), "and every one is written back to the header" + (dropped.isEmpty() ? "" : " - these are not: " + dropped));
	}

	static Object field(Object on, String name) throws Exception {
		java.lang.reflect.Field f = on.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(on);
	}

	/**
	 * The Zone tab's boxes are a document of the zone's history while a zone is shown (F209): a box changed
	 * is taken back by the same Undo as everything else. The tab writes them into the header only on save,
	 * so until then the edit lives in the boxes - and the document is exactly the boxes store() reads.
	 */
	static void theZonesPropertiesAreADocumentOfTheZonesHistory() throws Exception {
		System.out.println("--- the Zone tab's boxes are a document of the zone's history (F209)");
		WorkspaceSession ws = MapResizeTest.game("zoneundo");
		File f = ws.getWorkspaceFile(ArchiveType.ZONE_DATA, 0);
		byte[] amx = HandedGameTest.amx();
		byte[] entities = new byte[12 + amx.length];
		MapResizeTest.putI32(entities, 0, 8);
		System.arraycopy(amx, 0, entities, 12, amx.length);
		Files.write(f.toPath(), HandedGameTest.container("ZO", header(), entities, amx));
		Zone z = new Zone(new ZO(f, ws), GameType.ORAS);
		LoadedZone lz = new LoadedZone();
		lz.table(new Zone[]{z});
		lz.open(0, z);
		String said = "(nothing was thrown)";
		try {
			new ZoneLoadingPanel(lz, MatrixEditFormGuardsTest.TOOLS, MatrixEditFormGuardsTest.EDITORS,
					MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, null, new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		} catch (RuntimeException refused) {
			said = refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
		check(said.startsWith("IllegalArgumentException") && said.contains("must be handed the zone's history"),
				"a Zone tab built without the zone's history is refused: " + said);
		int[] npc = {0};
		ctrmap.humaninterface.UndoHistory zone = UndoHistoryTest.overOneNumber(npc);
		ZoneLoadingPanel tab = new ZoneLoadingPanel(lz, MatrixEditFormGuardsTest.TOOLS, MatrixEditFormGuardsTest.EDITORS,
				MatrixEditFormGuardsTest.ZONE_EDITORS, MatrixEditFormGuardsTest.NAVI, () -> { }, () -> { }, zone, new ctrmap.humaninterface.SaveHistory(() -> java.util.Collections.<Object>emptyList()));
		ctrmap.Ui.record();
		tab.loadZone(z);
		zone.settle();
		check(loaded(tab) && !zone.canUndo(), "control: a zone shown is noted, not an edit");
		java.lang.reflect.Field runField = ZoneLoadingPanel.class.getDeclaredField("run");
		runField.setAccessible(true);
		javax.swing.JCheckBox run = (javax.swing.JCheckBox) runField.get(tab);
		boolean was = run.isSelected();
		run.setSelected(!was);
		check(zone.settle() && "the zone's properties".equals(zone.undoName()),
				"a box ticked is an edit of the zone's history: " + zone.undoName());
		check(zone.undo() && run.isSelected() == was, "Undo puts the tick back");
		check(zone.redo() && run.isSelected() != was, "and Redo ticks it again");
		check(z.header.enableRunning == was, "control: the header itself is untouched until the tab is saved");
		//the document is exactly the boxes store() reads
		String src = new String(Files.readAllBytes(new File("src/ctrmap/humaninterface/ZoneLoadingPanel.java").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		String store = CollisionReadTest.body(src, "public boolean store(boolean dialog) {");
		String list = CollisionReadTest.body(src, "javax.swing.JComponent[] headerWidgets() {");
		java.util.Set<String> read = new java.util.TreeSet<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9]*)\\.(?:getValue|isSelected|getSelectedIndex)\\(\\)")
				.matcher(store == null ? "" : store);
		while (m.find()) {
			read.add(m.group(1));
		}
		java.util.Set<String> listed = new java.util.TreeSet<>();
		java.util.regex.Matcher l = java.util.regex.Pattern.compile("\\b([a-zA-Z][a-zA-Z0-9]*)\\b").matcher(
				list == null ? "" : list.substring(list.indexOf("{", list.indexOf("JComponent[]{")) + 1));
		while (l.find()) {
			listed.add(l.group(1));
		}
		check(read.size() >= 30 && read.contains("run"), "control: store() is read, and reads " + read.size() + " boxes, run among them");
		java.util.Set<String> missing = new java.util.TreeSet<>(read);
		missing.removeAll(listed);
		java.util.Set<String> extra = new java.util.TreeSet<>(listed);
		extra.removeAll(read);
		check(missing.isEmpty() && extra.isEmpty(), "and the document holds exactly those boxes - missing " + missing + ", not read " + extra);
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
