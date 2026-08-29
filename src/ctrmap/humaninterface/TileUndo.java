package ctrmap.humaninterface;

import static ctrmap.CtrmapMainframe.*;
import ctrmap.formats.tilemap.Tilemap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Undo/redo for the World Editor's tile-byte edits (Set, Fill and Edit tool
 * changes). One BATCH per user gesture: a paint drag is one step, a fill is
 * one step, a spinner tick is one step. The toolbar's Undo/Redo buttons and
 * Ctrl+Z / Ctrl+Y drive it.
 */
public class TileUndo {

	private static final int LIMIT = 200;

	private static class Rec {

		Tilemap region;
		int x, y;
		byte[] before, after;
	}

	private static final Deque<List<Rec>> undoStack = new ArrayDeque<>();
	private static final Deque<List<Rec>> redoStack = new ArrayDeque<>();
	private static List<Rec> open = null;

	/** Opens a gesture batch (no-op when one is already open). */
	public static void begin() {
		if (open == null) {
			open = new ArrayList<>();
		}
	}

	public static boolean isOpen() {
		return open != null;
	}

	/**
	 * Records one tile's change. Coalesces repeat writes to the same tile
	 * within the open batch (a drag crossing a tile twice keeps the original
	 * "before"). Called with an already-CLONED before (getTileData returns the
	 * live array). Outside a batch, the record becomes its own step.
	 */
	public static void record(Tilemap region, int x, int y, byte[] before, byte[] after) {
		if (region == null || Arrays.equals(before, after)) {
			return;
		}
		boolean auto = open == null;
		if (auto) {
			begin();
		}
		for (Rec r : open) {
			if (r.region == region && r.x == x && r.y == y) {
				r.after = after.clone();
				if (auto) {
					end();
				}
				return;
			}
		}
		Rec r = new Rec();
		r.region = region;
		r.x = x;
		r.y = y;
		r.before = before.clone();
		r.after = after.clone();
		open.add(r);
		if (auto) {
			end();
		}
	}

	/** Closes the gesture batch and pushes it as one undo step. */
	public static void end() {
		if (open != null && !open.isEmpty()) {
			undoStack.push(open);
			while (undoStack.size() > LIMIT) {
				undoStack.removeLast();
			}
			redoStack.clear();
		}
		open = null;
		updateButtons();
	}

	/** Drops all history (a different zone was loaded). */
	public static void clear() {
		undoStack.clear();
		redoStack.clear();
		open = null;
		updateButtons();
	}

	public static boolean undo() {
		return apply(undoStack, redoStack, true);
	}

	public static boolean redo() {
		return apply(redoStack, undoStack, false);
	}

	private static boolean apply(Deque<List<Rec>> from, Deque<List<Rec>> to, boolean back) {
		if (open != null) {
			end();
		}
		if (from.isEmpty()) {
			return false;
		}
		List<Rec> batch = from.pop();
		Set<Tilemap> touched = new HashSet<>();
		for (int i = batch.size() - 1; i >= 0; i--) {
			Rec r = batch.get(i);
			r.region.setTileData(r.x, r.y, back ? r.before : r.after);
			touched.add(r.region);
		}
		to.push(batch);
		for (Tilemap tm : touched) {
			tm.updateImage();
		}
		mTileMapPanel.scaleImage(mTileMapPanel.tilemapScale);
		mTileMapPanel.firePropertyChange(TileMapPanel.PROP_REPAINT, false, true);
		if (Selector.selTileX != -1) {
			mTileEditForm.showTile(Selector.selTileX, Selector.selTileY, true);
		}
		updateButtons();
		return true;
	}

	public static boolean canUndo() {
		return !undoStack.isEmpty();
	}

	public static boolean canRedo() {
		return !redoStack.isEmpty();
	}

	private static void updateButtons() {
		if (btnUndoTile != null) {
			btnUndoTile.setEnabled(canUndo());
		}
		if (btnRedoTile != null) {
			btnRedoTile.setEnabled(canRedo());
		}
	}
}
