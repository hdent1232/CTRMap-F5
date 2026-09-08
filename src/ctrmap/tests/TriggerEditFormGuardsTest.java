package ctrmap.tests;

import ctrmap.CtrmapMainframe;
import ctrmap.Workspace;
import ctrmap.formats.containers.ZO;
import ctrmap.formats.garc.GARC;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameType;
import ctrmap.humaninterface.TriggerEditForm;
import ctrmap.humaninterface.ZoneLoadingPanel;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;

/**
 * What the script-trigger editor writes into a zone, driven with no window,
 * against retail zone 48 (three interaction triggers, four step-on ones).
 *
 * <p>These are CHARACTERIZATION checks: they state what the form does today so
 * that moving it cannot change what a saved zone contains without something
 * going red. The form had no suite at all, and its save is twelve widget reads
 * copied into a fresh record - a field left out of that copy, or read from the
 * wrong widget, produces a zone that loads and a trigger that fires the wrong
 * script, which nothing else in this battery would notice.
 *
 * <p>What is pinned:
 * <ol>
 * <li>The twelve numbers a trigger holds go into the twelve fields on open and
 *     come back out of them on Save, in the file's own order. The assertion is
 *     the ZO container's entity subfile, byte for byte: the record's 24 bytes
 *     change and nothing else in the zone does.</li>
 * <li>{@code uA} is the one field with no widget. Save carries it across from
 *     the record being replaced; a copy that forgot it would silently zero a
 *     byte pair the game reads.</li>
 * <li>Save builds a NEW record and puts it at the combo box's index rather than
 *     at {@code indexOf} the old one - two identical triggers in one zone (zone
 *     48 has none, so this check makes a pair) would otherwise overwrite the
 *     first. And when the selected slot no longer holds the record the form is
 *     on, Save writes nothing at all.</li>
 * <li>Save with nothing typed leaves the record object itself in place and does
 *     not mark the zone modified, so opening a zone and closing it writes no
 *     bytes.</li>
 * <li>The script dropdown lists the ids the zone's dispatch defines and puts
 *     the chosen one into the Script field; the field and the dropdown agree
 *     after every showEntry.</li>
 * </ol>
 *
 * <p>Type 2 (step-on), New entry and Remove entry go through
 * {@code CtrmapMainframe.frame.repaint()}, so they need a main window to exist.
 * The suite makes a JFrame when the JVM has a display (the battery runs with
 * one) and says out loud which checks it had to skip when it has not.
 *
 * Usage: java ctrmap.tests.TriggerEditFormGuardsTest &lt;pristine dump root&gt;
 */
public class TriggerEditFormGuardsTest {

	/** Petalburg Gym: 3 interaction triggers, 4 step-on ones, 2 props, 25 NPCs, 3 warps. */
	static final int ZONE = 48;

	static int fails = 0;
	/** True when a JFrame could be made, so the form's repaint calls do not throw. */
	static boolean windowed = false;

	public static void main(String[] args) throws Exception {
		File dump = new File(args.length > 0 ? args[0] : "no-dump-given");
		if (!dump.isDirectory()) {
			System.out.println("  skip: no dump at " + dump + " - the trigger form checks need zone " + ZONE);
			System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
			return;
		}
		Workspace.GAMEDIR_PATH = dump.getAbsolutePath();
		//the script dropdown only reads the zone inside a workspace; the form is
		//handed its zone by hand, so no archive has to be opened through the session
		Sessions.bare(Scratch.dir("ctrmap_trigform_ws"), dump, GameType.ORAS);
		GARC zo = new GARC(new File(Workspace.GAMEDIR_PATH + Workspace.getArchivePath(ArchiveType.ZONE_DATA, Workspace.game())));

		try {
			CtrmapMainframe.frame = new javax.swing.JFrame();
			windowed = true;
		} catch (Throwable headless) {
			System.out.println("  skip: no display (" + headless.getClass().getSimpleName()
					+ ") - Type 2, New entry and Remove entry repaint the main window and cannot be driven");
		}

		theZoneIsTheOneTheseChecksDescribe(zo);
		openingAZoneShowsTheFirstTrigger(zo);
		saveWithNothingTypedWritesNothing(zo);
		saveWritesTheTypedNumbersAndKeepsUA(zo);
		saveGoesToTheSelectedSlotNotTheFirstEqualOne(zo);
		saveOnAStaleSelectionWritesNothing(zo);
		theScriptDropdownAndTheScriptFieldAgree(zo);
		stepOnTriggersAreASecondList(zo);
		newEntryLandsAtTheViewportCentre(zo);
		removeEntryTakesTheSelectedSlot(zo);

		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		if (fails > 0) {
			System.exit(1);
		}
	}

	/** The corpus this suite describes, asserted rather than assumed. */
	static void theZoneIsTheOneTheseChecksDescribe(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		check(e.triggers1.size() == 3 && e.trigger1Count == 3, "zone " + ZONE + " holds 3 interaction triggers (found " + e.triggers1.size() + ")");
		check(e.triggers2.size() == 4 && e.trigger2Count == 4, "and 4 step-on triggers (found " + e.triggers2.size() + ")");
		check(e.furniture.size() == 2 && e.npcs.size() == 25 && e.warps.size() == 3,
				"behind 2 props, 25 NPCs and 3 warps - the offsets the byte checks below count from");
		check(Arrays.equals(e.assembleData(), zone.file.getFile(1)),
				"and the entity section rebuilds byte for byte, so a difference below is the form's doing");
	}

	/** Opening the zone selects trigger 0 and puts its twelve numbers in the twelve fields. */
	static void openingAZoneShowsTheFirstTrigger(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		ZoneEntities.Trigger first = e.triggers1.get(0);
		check(form.loaded && form.e == e, "the form is loaded on the zone");
		check(entries(form) == 3, "the dropdown lists one item per interaction trigger: " + entries(form));
		check(itemAt(form, 0).equals("0 - script " + first.script), "and names each by its script: " + itemAt(form, 0));
		check(form.trigger == first, "trigger 0 is the selected record");
		check(shown(form).equals(numbers(first)), "and the fields show it: " + shown(form) + " vs " + numbers(first));
	}

	/**
	 * Save with nothing typed: the record OBJECT stays in the list (Save only
	 * replaces it when a number differs), the zone is not marked modified, and
	 * the assembled bytes are the ones that were read.
	 */
	static void saveWithNothingTypedWritesNothing(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		byte[] before = zone.file.getFile(1);
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		ZoneEntities.Trigger first = e.triggers1.get(0);
		form.saveEntry();
		check(e.triggers1.get(0) == first && form.trigger == first, "Save with nothing typed keeps the very same record");
		check(!e.modified, "and does not mark the zone modified");
		check(zone.store() != null, "so a store afterwards succeeds");
		check(Arrays.equals(zone.file.getFile(1), before), "and the entity section on disk is untouched");
	}

	/**
	 * The save path, asserted on the bytes. Every widget is typed into, Save is
	 * pressed, the zone is stored, and the entity section must differ from the
	 * one that was read in exactly the 24 bytes of that one trigger - carrying
	 * uA, which no widget shows, across from the record being replaced.
	 */
	static void saveWritesTheTypedNumbersAndKeepsUA(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		byte[] before = zone.file.getFile(1);
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		ZoneEntities.Trigger old = e.triggers1.get(0);
		//zone 48's triggers all carry uA 0, so a save that dropped it would look
		//identical; stage one the way a zone from another region would hold it
		old.uA = 0x4321;
		int keptUA = old.uA;

		int[] typed = {101, 202, 303, 404, 505, 11, 12, 13, 14, 606, 707};
		String[] widgets = {"script", "u2", "constant", "u6", "u8", "x", "y", "w", "h", "u14", "u16f"};
		for (int i = 0; i < widgets.length; i++) {
			((JFormattedTextField) field(form, widgets[i])).setValue(typed[i]);
		}
		form.saveEntry();

		ZoneEntities.Trigger now = e.triggers1.get(0);
		check(now != old, "Save put a NEW record in the list");
		check(form.trigger == now, "and the form is on it");
		check(e.modified, "and the zone is marked modified");
		check(now.script == 101 && now.u2 == 202 && now.constant == 303 && now.u6 == 404 && now.u8 == 505,
				"the five header numbers are the typed ones: " + numbers(now));
		check(now.x == 11 && now.y == 12 && now.w == 13 && now.h == 14, "the tile rectangle is the typed one: " + numbers(now));
		check(now.u14 == 606 && now.u16val == 707, "the two trailing unknowns are the typed ones: " + numbers(now));
		check(now.uA == keptUA, "and uA - the one field with no widget - is carried over as " + keptUA);

		byte[] want = before.clone();
		int at = 12 + e.furniture.size() * 0x14 + e.npcs.size() * 0x30 + e.warps.size() * 0x18;
		check((want[at + 10] & 0xFF) == 0 && (want[at + 11] & 0xFF) == 0, "the file held uA 0 before the stage");
		short[] record = {101, 202, 303, 404, 505, (short) keptUA, 11, 12, 13, 14, 606, 707};
		for (int i = 0; i < record.length; i++) {
			want[at + i * 2] = (byte) (record[i] & 0xFF);
			want[at + i * 2 + 1] = (byte) ((record[i] >> 8) & 0xFF);
		}
		check(zone.store() != null, "the zone stores");
		byte[] after = zone.file.getFile(1);
		check(Arrays.equals(after, want), "and the entity section differs from the one read in exactly this trigger's 24 bytes"
				+ (Arrays.equals(after, want) ? "" : " - first difference at " + firstDiff(after, want) + " (record starts at " + at + ")"));
	}

	/**
	 * Two triggers with identical fields. Save must overwrite the SELECTED one,
	 * which is what the combo box index says - {@code indexOf} would find the
	 * first equal record and edit the wrong trigger.
	 */
	static void saveGoesToTheSelectedSlotNotTheFirstEqualOne(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		ZoneEntities.Trigger twin = new ZoneEntities.Trigger();
		ZoneEntities.Trigger source = e.triggers1.get(0);
		twin.script = source.script;
		twin.u2 = source.u2;
		twin.constant = source.constant;
		twin.u6 = source.u6;
		twin.u8 = source.u8;
		twin.uA = source.uA;
		twin.x = source.x;
		twin.y = source.y;
		twin.w = source.w;
		twin.h = source.h;
		twin.u14 = source.u14;
		twin.u16val = source.u16val;
		e.triggers1.set(1, twin);
		check(e.triggers1.get(0).equals(e.triggers1.get(1)), "triggers 0 and 1 now hold identical fields");

		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		form.setTrigger(1);
		check(form.trigger == twin, "the form is on trigger 1");
		((JFormattedTextField) field(form, "script")).setValue(999);
		form.saveEntry();
		check(e.triggers1.get(1).script == 999, "Save wrote script 999 into trigger 1");
		check(e.triggers1.get(0) == source && e.triggers1.get(0).script != 999, "and left its identical twin at index 0 alone");
	}

	/**
	 * The slot the form is on no longer holds the form's record - another edit
	 * replaced it. Save must write nothing rather than overwrite a stranger.
	 */
	static void saveOnAStaleSelectionWritesNothing(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		ZoneEntities.Trigger stranger = new ZoneEntities.Trigger();
		stranger.script = 4242;
		e.triggers1.set(0, stranger);
		((JFormattedTextField) field(form, "script")).setValue(777);
		form.saveEntry();
		check(e.triggers1.get(0) == stranger && stranger.script == 4242, "Save on a slot that changed under it writes nothing");
		check(!e.modified, "and does not mark the zone modified");
	}

	/**
	 * The Script dropdown lists what the zone's dispatch defines, and choosing
	 * one types its id into the Script field. Showing an entry puts the
	 * dropdown back on the record's own id, or on nothing when the zone does
	 * not define it.
	 */
	static void theScriptDropdownAndTheScriptFieldAgree(GARC zo) throws Exception {
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		JComboBox<?> drop = (JComboBox<?>) field(form, "scriptDropdown");
		JFormattedTextField script = (JFormattedTextField) field(form, "script");
		check(drop.getItemCount() > 0, "zone " + ZONE + "'s dispatch offers " + drop.getItemCount() + " script ids");

		int own = e.triggers1.get(0).script;
		int ownIndex = -1;
		for (int i = 0; i < drop.getItemCount(); i++) {
			if (leading((String) drop.getItemAt(i)) == own) {
				ownIndex = i;
			}
		}
		check(ownIndex >= 0, "trigger 0's script " + own + " is one of them");
		check(drop.getSelectedIndex() == ownIndex, "and the dropdown opened on it: " + drop.getSelectedIndex() + " vs " + ownIndex);

		int other = -1, otherIndex = -1;
		for (int i = 0; i < drop.getItemCount() && other == -1; i++) {
			if (leading((String) drop.getItemAt(i)) != own) {
				other = leading((String) drop.getItemAt(i));
				otherIndex = i;
			}
		}
		if (other == -1) {
			System.out.println("  skip: zone " + ZONE + " defines only one script id, so choosing another cannot be driven");
			return;
		}
		drop.setSelectedIndex(otherIndex);
		check(((Integer) script.getValue()) == other, "choosing \"" + drop.getItemAt(otherIndex) + "\" types its id into Script: " + script.getValue() + " (wanted " + other + ")");
		check(e.triggers1.get(0).script == own, "and changes no record until Save");

		e.triggers1.get(0).script = 31337;
		form.showEntry(0);
		check(drop.getSelectedIndex() == -1, "a record on a script the zone does not define leaves the dropdown on nothing");
		check(((Integer) script.getValue()) == 31337, "with the raw id still in the field");
	}

	/** Type 2 is a second, independent list, with its own count byte. */
	static void stepOnTriggersAreASecondList(GARC zo) throws Exception {
		if (!windowed) {
			System.out.println("  skip: the Type dropdown repaints the main window - no display");
			return;
		}
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		byte[] before = zone.file.getFile(1);
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		form.selectTrigger(1, 0);
		check(entries(form) == 4, "switching to Type 2 lists the four step-on triggers: " + entries(form));
		check(form.trigger == e.triggers2.get(0), "and selects the first of them");
		check(shown(form).equals(numbers(e.triggers2.get(0))), "showing its numbers: " + shown(form));

		((JFormattedTextField) field(form, "script")).setValue(555);
		form.saveEntry();
		check(e.triggers2.get(0).script == 555, "Save on Type 2 writes into triggers2");
		check(e.triggers1.get(0).script != 555, "and not into triggers1");

		byte[] want = before.clone();
		int at = 12 + e.furniture.size() * 0x14 + e.npcs.size() * 0x30 + e.warps.size() * 0x18 + e.triggers1.size() * 0x18;
		want[at] = (byte) (555 & 0xFF);
		want[at + 1] = (byte) ((555 >> 8) & 0xFF);
		check(zone.store() != null, "the zone stores");
		check(Arrays.equals(zone.file.getFile(1), want), "and the step-on trigger's script is the only byte pair that moved");
	}

	/**
	 * New entry adds a 1x1 trigger on the tile at the viewport centre, bumps
	 * the count for the list being edited, and selects it.
	 */
	static void newEntryLandsAtTheViewportCentre(GARC zo) throws Exception {
		if (!windowed) {
			System.out.println("  skip: New entry repaints the main window - no display");
			return;
		}
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		CtrmapMainframe.mTilemapScrollPane = new javax.swing.JScrollPane();
		CtrmapMainframe.mTileMapPanel = new ctrmap.humaninterface.TileMapPanel();
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		int before = e.triggers1.size();
		invoke(form, "btnAddActionPerformed");
		check(e.triggers1.size() == before + 1, "New entry added an interaction trigger");
		check(e.trigger1Count == before + 1, "and the count byte follows");
		check(e.triggers2.size() == 4 && e.trigger2Count == 4, "leaving the step-on list alone");
		ZoneEntities.Trigger added = e.triggers1.get(before);
		check(added.w == 1 && added.h == 1, "the new trigger is one tile wide and one tall");
		check(added.script == 0 && added.u2 == 0 && added.constant == 0, "with every other number zero: " + numbers(added));
		check(form.trigger == added && entries(form) == before + 1, "and it is selected in the dropdown");
		check(e.modified, "and the zone is marked modified");
	}

	/** Remove entry deletes the selected slot, not the first field-identical one. */
	static void removeEntryTakesTheSelectedSlot(GARC zo) throws Exception {
		if (!windowed) {
			System.out.println("  skip: Remove entry repaints the main window - no display");
			return;
		}
		Zone zone = openZone(zo, ZONE);
		ZoneEntities e = zone.entities;
		TriggerEditForm form = new TriggerEditForm();
		form.loadFromEntities(e);
		List<ZoneEntities.Trigger> survivors = new ArrayList<>(e.triggers1);
		survivors.remove(1);
		form.setTrigger(1);
		invoke(form, "btnRemoveActionPerformed");
		check(e.triggers1.size() == 2 && e.trigger1Count == 2, "Remove dropped one interaction trigger and the count follows");
		check(e.triggers1.equals(survivors) && e.triggers1.get(0) == survivors.get(0) && e.triggers1.get(1) == survivors.get(1),
				"and the two that are left are the ones that were not selected");
		check(entries(form) == 2, "the dropdown lost one item");
		check(e.modified, "and the zone is marked modified");
	}

	// ---- fixture -------------------------------------------------------

	/**
	 * Zone index as the editor holds it: a ZoneLoadingPanel with that zone
	 * open, on a scratch copy of the ZO the suite is allowed to write to.
	 */
	static Zone openZone(GARC zo, int index) throws Exception {
		File f = Scratch.file("ctrmap_trigform");
		Files.write(f.toPath(), zo.getDecompressedEntry(index));
		ZoneLoadingPanel pnl = new ZoneLoadingPanel();
		pnl.zones = new Zone[index + 1];
		pnl.zones[index] = new Zone(new ZO(f), Workspace.game());
		pnl.zone = pnl.zones[index];
		pnl.zoneIndex = index;
		CtrmapMainframe.mZonePnl = pnl;
		return pnl.zone;
	}

	/** The twelve numbers of a record, in the order the file holds them. */
	static String numbers(ZoneEntities.Trigger t) {
		return t.script + "," + t.u2 + "," + t.constant + "," + t.u6 + "," + t.u8 + "," + t.uA
				+ "," + t.x + "," + t.y + "," + t.w + "," + t.h + "," + t.u14 + "," + t.u16val;
	}

	/** The same twelve, as the form's widgets currently show them (uA has none). */
	static String shown(TriggerEditForm form) throws Exception {
		return num(form, "script") + "," + num(form, "u2") + "," + num(form, "constant") + "," + num(form, "u6")
				+ "," + num(form, "u8") + "," + form.trigger.uA + "," + num(form, "x") + "," + num(form, "y")
				+ "," + num(form, "w") + "," + num(form, "h") + "," + num(form, "u14") + "," + num(form, "u16f");
	}

	static int num(TriggerEditForm form, String name) throws Exception {
		return (Integer) ((JFormattedTextField) field(form, name)).getValue();
	}

	static int entries(TriggerEditForm form) throws Exception {
		return ((JComboBox<?>) field(form, "entryBox")).getItemCount();
	}

	static String itemAt(TriggerEditForm form, int i) throws Exception {
		return (String) ((JComboBox<?>) field(form, "entryBox")).getItemAt(i);
	}

	/** The id a dropdown row starts with, the way the form itself parses it. */
	static int leading(String item) {
		int space = item.indexOf(' ');
		return Integer.parseInt(space == -1 ? item : item.substring(0, space));
	}

	static int firstDiff(byte[] a, byte[] b) {
		if (a.length != b.length) {
			return -a.length;
		}
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return i;
			}
		}
		return -1;
	}

	static Object field(Object o, String name) throws Exception {
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	static void invoke(Object o, String name) throws Exception {
		Method m = o.getClass().getDeclaredMethod(name, java.awt.event.ActionEvent.class);
		m.setAccessible(true);
		m.invoke(o, (java.awt.event.ActionEvent) null);
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
