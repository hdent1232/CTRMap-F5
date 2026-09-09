package ctrmap.tests;

import ctrmap.formats.tilemap.TileTemplate;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.humaninterface.Selector;
import ctrmap.util.Bytes;
import java.util.Arrays;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JRadioButton;

/**
 * The tile inspector's own suite: its category palette, and what it does with a
 * cursor that belongs to a map which is no longer loaded.
 *
 * <p>WHY THIS EXISTS. TileEditForm had no coverage at all, and the commit that
 * repaired the missing guards from the twenty-two-defect fix said so in as many
 * words - "TileEditForm has no suite at all and its constructor needs a tileset
 * and a map view". It needs neither of those from the game: {@link EditorBench}
 * hands it the Workspace default tileset and a bench map of one 40x40 region,
 * and has built one since the tool suites were written. Nothing was in the way
 * any more.
 *
 * <p>WHAT IS GUARDED. {@code Selector.selTileX/selTileY} are statics that
 * outlive the map they were set on. showListModel reads that pair and asks the
 * map view for the region under it; the three other readers of the same lookup
 * in that file fetch the answer into a local and test it, and this one did not.
 * So a tile picked on a wide matrix, a switch to a narrower one, and then a
 * click on any category radio threw on the event thread out of a listener -
 * ArrayIndexOutOfBounds when the stale coordinate names a region column the new
 * map has not got, NullPointerException when it names a cell that holds no
 * region - leaving the form half-updated with a stack trace on stderr and the
 * user told nothing. Both arms are driven here through the gesture that reached
 * them: a click on a category radio button.
 *
 * <p>REFLECTION, AND WHY. The category radios, the palette list and the twelve
 * list models are private fields of a NetBeans-generated form. Widening them so
 * a suite could see them would make the generated layout part of the form's API,
 * which is the opposite of what this campaign is for, so the checks reach them
 * through {@link EditorBench#field} and this paragraph is the record. A field
 * renamed away stops the suite dead rather than letting it quietly assert less.
 *
 * <p>WHAT THIS DOES NOT COVER, named rather than left implied: the byte
 * spinners' change listener and its three write paths (Edit, Set, Fill), the
 * palette's pick-a-brush tool switch, the swatch renderer, and showTile's
 * " - Void" label. This suite is the one fix that shipped without a guard, plus
 * the pin below, and nothing else.
 *
 * <p>Needs no dump, no game and no display.
 *
 * Usage: java ctrmap.tests.TileEditFormGuardsTest
 */
public class TileEditFormGuardsTest {

	static int fails = 0;

	public static void main(String[] args) throws Exception {
		EditorBench.install();
		try {
			aCursorLeftOverFromAnotherMapIsNotFollowed();
			deselectingATilePinnedAsItStands();
		} finally {
			EditorBench.shutdown();
		}
		System.out.println(fails == 0 ? "ALL PASS" : "FAILURES PRESENT (" + fails + ")");
		//Explicit, because EditorBench builds a JFrame and a PropEditForm and
		//either can hold a non-daemon AWT thread open past the last check.
		System.exit(fails > 0 ? 1 : 0);
	}

	/**
	 * The category radios with a stale cursor: the two ways the lookup used to
	 * fail, and the highlight that must still happen when it does not.
	 *
	 * <p>The third block is the half a guard that only watched for exceptions
	 * would miss. Deleting the highlight altogether would silence both crashes
	 * too, and leave the palette never showing which template the picked tile
	 * is - so the fix is asserted from both sides.
	 */
	static void aCursorLeftOverFromAnotherMapIsNotFollowed() throws Exception {
		System.out.println("--- a cursor left over from another map does not follow the category radios onto this one");
		JList<?> palette = (JList<?>) EditorBench.field(EditorBench.tiles, "tileList");
		DefaultListModel[] models = (DefaultListModel[]) EditorBench.field(EditorBench.tiles, "models");
		Tilemap[][] real = EditorBench.map.tilemaps;
		Object shown;

		//the bench map is ONE 40x40 region, so tile (40,0) is off it entirely
		//and the lookup indexes a region column that does not exist
		EditorBench.tiles.makeTile();
		click(radio("normal"));
		Selector.selTileX = 40;
		Selector.selTileY = 0;
		String threw = click(radio("cat2b1"));
		check(threw == null, "a category radio click with the cursor left off the new map does not throw"
				+ (threw == null ? "" : ": " + threw));
		shown = palette.getModel();
		check(shown == models[0], "and the palette still switched to the category that was picked");

		//on the map, but in a cell that holds no region: the other half of the
		//same lookup, and the one that answers null instead of throwing
		try {
			EditorBench.map.tilemaps = new Tilemap[][]{{null}};
			EditorBench.tiles.makeTile();
			click(radio("water"));
			Selector.selTileX = 0;
			Selector.selTileY = 0;
			threw = click(radio("cat2b2"));
			check(threw == null, "a category radio click with the cursor on a cell that holds no region does not throw"
					+ (threw == null ? "" : ": " + threw));
			shown = palette.getModel();
			check(shown == models[5], "and the palette still switched to that category too");
		} finally {
			EditorBench.map.tilemaps = real;
		}

		//and with a cursor that IS on this map, the highlight still happens
		TileTemplate under = EditorBench.tiles.tileset.getTemplate(Bytes.ba2int(EditorBench.region.getTileData(0, 0)));
		check(!"Unknown".equals(under.name),
				"fixture: the bench region's first tile is a palette entry (" + under.name + ")");
		EditorBench.tiles.makeTile();
		click(radio(new String[]{"normal", "water", "action"}[under.cat1]));
		Selector.selTileX = 0;
		Selector.selTileY = 0;
		threw = click(radio("cat2b" + (under.cat2 + 1)));
		check(threw == null, "picking that tile's own category does not throw either"
				+ (threw == null ? "" : ": " + threw));
		check(under.name.equals(String.valueOf(palette.getSelectedValue())),
				"and the palette highlights the template the picked tile actually is: " + palette.getSelectedValue());
		Selector.unfocus(EditorBench.map);
	}

	/**
	 * PIN, AND A FIX THAT WAS NEVER APPLIED. The twenty-two-defect commit lists,
	 * among the defects it says it fixed, "Clicking the already-selected tile
	 * unlocked the inspector without re-showing anything." That commit does not
	 * touch Selector and nothing since has: acqCurTile's same-tile branch still
	 * unlocks the inspector, drops the cursor and returns. The claim is a record
	 * of a fix that is not in the tree, and this is what the code does instead,
	 * written where the next reader will meet it.
	 *
	 * <p>PINNED AND NOT FIXED, deliberately. This branch is only reached with the
	 * mouse ON the tile that is already picked, so the tile a re-show would put up
	 * is the tile the inspector is already showing; and the unlock it does perform
	 * means the next mouse-move - which the router turns into Selector.select on
	 * every motion - shows it again anyway. There is nothing a user can see
	 * between the two, so adding the call would be a change with no defect behind
	 * it.
	 *
	 * <p>The inspector here is a {@link RecordingInspector} and not the real form
	 * for exactly that reason: re-showing the tile produces the display the form
	 * already had, so only the CALL RECORD can tell "showed it again" from "did
	 * nothing". If the re-show is ever added, this check fails and names what
	 * changed, which is what a pin is for.
	 */
	static void deselectingATilePinnedAsItStands() {
		System.out.println("--- and what clicking the already-picked tile does, pinned as it stands");
		RecordingInspector rec = new RecordingInspector();
		Selector.unfocus(EditorBench.map);
		EditorBench.hilight(5, 6);
		Selector.acqCurTile(rec, EditorBench.map);
		check(rec.calls.equals(Arrays.asList("lock false", "show 5,6", "lock true")),
				"picking a tile unlocks the inspector, shows the tile and locks it again: " + rec.calls);

		rec.reset();
		Selector.acqCurTile(rec, EditorBench.map);
		check(rec.calls.equals(Arrays.asList("lock false")),
				"PIN: clicking it again unlocks the inspector and shows NOTHING - the re-show the "
				+ "twenty-two-defect commit claims was never applied: " + rec.calls);
		check(Selector.selTileX == -1 && Selector.selTileY == -1, "and the picked tile is dropped");
		EditorBench.hilight(-1, -1);
	}

	// ---- plumbing ----------------------------------------------------------

	/** One of the form's private radios, by field name. */
	static JRadioButton radio(String name) throws Exception {
		return (JRadioButton) EditorBench.field(EditorBench.tiles, name);
	}

	/**
	 * Presses a button the way a user does, and reports what came back out
	 * instead of letting it end the suite.
	 *
	 * <p>doClick() calls the listener on this thread, so whatever the handler
	 * throws arrives here - which is the point, because the defect this suite
	 * guards was an exception out of exactly this listener. It also leaves the
	 * button's model armed when that happens, so this puts it down again.
	 */
	static String click(AbstractButton b) {
		try {
			b.doClick();
			return null;
		} catch (Throwable t) {
			b.getModel().setArmed(false);
			return t.toString();
		}
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
