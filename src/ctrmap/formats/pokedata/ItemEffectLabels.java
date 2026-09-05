package ctrmap.formats.pokedata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Readable names for the item effect ids, derived from the data instead of
 * written down.
 *
 * <p>THE PROBLEM. The game ships no name table for the 183 hold effects, the
 * field and battle use routines, the natural-gift effects or the fling effects.
 * So an editor showing "hold effect: 77" is asking the user to know something
 * nobody wrote down, and the obvious fix - hand-authoring 183 labels - is the
 * kind of table that is wrong the day someone edits an item and stays wrong
 * silently, because nothing recomputes it.
 *
 * <p>THE FIX IS FREE. Effect ids are SHARED between items: 77 is carried by
 * Mystic Water, Sea Incense and Wave Incense. So the retail items carrying an id
 * ARE its definition, and listing them tells the user exactly what the id does
 * in the only vocabulary that matters - "77 - Mystic Water, Sea Incense, Wave
 * Incense". It is computed from the same records being edited, so it cannot go
 * stale, and it needs no maintenance when a game is added: point it at that
 * game's table and it describes that game.
 *
 * <p>ONE HONEST LIMIT. An id carried by exactly one item is described by that
 * item's name alone, which is informative but not a definition; and an id no
 * retail item carries cannot be described at all. Both are reported as such
 * rather than papered over, because a confident wrong label is worse than an
 * admitted blank - the user would reassign an effect believing it does something
 * it does not.
 */
public final class ItemEffectLabels {

	/** Which field of the record an id came from. Each is its own numbering. */
	public enum Kind {
		HELD_EFFECT,
		FIELD_ROUTINE,
		BATTLE_ROUTINE,
		NATURAL_GIFT_EFFECT,
		FLING_EFFECT
	}

	private final Map<Integer, List<String>> itemsByEffect = new LinkedHashMap<>();
	private final Map<Integer, List<Integer>> idsByEffect = new LinkedHashMap<>();
	private final Kind kind;

	private ItemEffectLabels(Kind kind) {
		this.kind = kind;
	}

	/**
	 * Builds the table for one field.
	 *
	 * <p>{@code names} is indexed by item id, the way the game's own text file
	 * is; a null or short list simply yields ids instead of names, because a
	 * missing name table must degrade to something usable rather than throw.
	 * Effect 0 is skipped throughout - it is "no effect", carried by hundreds of
	 * items, and listing them would say nothing.
	 */
	public static ItemEffectLabels build(List<ItemData> records, List<String> names, Kind kind) {
		ItemEffectLabels out = new ItemEffectLabels(kind);
		if (records == null) {
			return out;
		}
		for (int id = 0; id < records.size(); id++) {
			ItemData d = records.get(id);
			if (d == null) {
				continue;
			}
			int eff = valueOf(d, kind);
			if (eff == 0) {
				continue;
			}
			List<String> byName = out.itemsByEffect.get(eff);
			if (byName == null) {
				byName = new ArrayList<>();
				out.itemsByEffect.put(eff, byName);
				out.idsByEffect.put(eff, new ArrayList<Integer>());
			}
			byName.add(nameOf(names, id));
			out.idsByEffect.get(eff).add(id);
		}
		return out;
	}

	private static int valueOf(ItemData d, Kind kind) {
		switch (kind) {
			case HELD_EFFECT:
				return d.heldEffect();
			case FIELD_ROUTINE:
				return d.fieldRoutine();
			case BATTLE_ROUTINE:
				return d.battleRoutine();
			case NATURAL_GIFT_EFFECT:
				return d.naturalGiftEffect();
			case FLING_EFFECT:
				return d.flingEffect();
			default:
				return 0;
		}
	}

	private static String nameOf(List<String> names, int id) {
		if (names == null || id < 0 || id >= names.size()) {
			return "item " + id;
		}
		String s = names.get(id);
		//the retail table uses ??? for the unused slots; showing that as a name
		//would read as a real item
		if (s == null || s.trim().isEmpty() || "???".equals(s.trim())) {
			return "item " + id;
		}
		return s.trim();
	}

	/** Every effect id that at least one item carries, ascending. */
	public List<Integer> ids() {
		List<Integer> out = new ArrayList<>(itemsByEffect.keySet());
		Collections.sort(out);
		return out;
	}

	/** The items carrying this id, in id order. Empty when nothing carries it. */
	public List<String> itemsFor(int effect) {
		List<String> l = itemsByEffect.get(effect);
		return l == null ? new ArrayList<String>() : new ArrayList<>(l);
	}

	/**
	 * What to put in a dropdown for this id.
	 *
	 * <p>Names the carriers when there are any, and says plainly when there are
	 * none rather than inventing a label. Long lists are trimmed - past a few
	 * examples the extra names stop informing and start filling the control.
	 */
	public String label(int effect) {
		List<String> items = itemsFor(effect);
		if (items.isEmpty()) {
			//an id nothing carries is one this table genuinely cannot describe,
			//and saying so is the honest thing: the user is about to point an
			//item at a behaviour with no known example of what it does
			return effect + " - no retail item uses this, so what it does is unknown";
		}
		StringBuilder sb = new StringBuilder().append(effect).append(" - ");
		int show = Math.min(items.size(), 4);
		for (int i = 0; i < show; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(items.get(i));
		}
		if (items.size() > show) {
			sb.append(" (+").append(items.size() - show).append(" more)");
		}
		if (items.size() == 1) {
			//one carrier names the id but does not define it - the user is
			//reading an example, not a description, and should know that
			sb.append("  [only item]");
		}
		return sb.toString();
	}

	/** Labels for every id in use, ready for a dropdown, ascending. */
	public List<String> labels() {
		List<String> out = new ArrayList<>();
		for (Integer id : ids()) {
			out.add(label(id));
		}
		return out;
	}

	public Kind kind() {
		return kind;
	}

	/** How many ids are described by a single item, i.e. named but not defined. */
	public int singletonCount() {
		int n = 0;
		for (List<String> l : itemsByEffect.values()) {
			if (l.size() == 1) {
				n++;
			}
		}
		return n;
	}
}
