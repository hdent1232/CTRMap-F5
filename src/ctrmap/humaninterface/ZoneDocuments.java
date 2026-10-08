package ctrmap.humaninterface;

import ctrmap.LoadedZone;
import ctrmap.formats.tilemap.Tilemap;
import ctrmap.formats.zone.Zone;
import ctrmap.formats.zone.ZoneEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the open zone's undo history watches (F209): the documents its editors change, each read and
 * put back whole.
 *
 * <p>ONE DOCUMENT PER THING AN EDIT CAN CHANGE ALONE: the zone's entities are one, and each map region's
 * tiles are one - so a stroke across one region keeps that region's tiles twice, not the whole map's.
 *
 * <p>Not: {@code TileUndo} - it recorded tiles alone, statically, and only where a tool remembered to
 * call it; these are read, so whatever changed them is undone.
 */
public final class ZoneDocuments {

	private ZoneDocuments() {
	}

	/** The open zone's entities, as they are when asked: one document, or none with no zone open. */
	public static Supplier<List<UndoHistory.Document<?>>> entities(LoadedZone zone, Runnable shown) {
		return () -> {
			List<UndoHistory.Document<?>> out = new ArrayList<>();
			Zone open = zone.isOpen() ? zone.open() : null;
			if (open != null && open.entities != null) {
				out.add(new Entities(open.entities, shown));
			}
			return out;
		};
	}

	/** Each region the map view holds when asked, its tiles one document. */
	public static Supplier<List<UndoHistory.Document<?>>> tiles(Supplier<TileMapPanel> map, Runnable shown) {
		return () -> {
			List<UndoHistory.Document<?>> out = new ArrayList<>();
			TileMapPanel m = map.get();
			Tilemap[][] regions = m == null ? null : m.tilemaps;
			if (regions != null) {
				for (int x = 0; x < regions.length; x++) {
					for (int y = 0; y < regions[x].length; y++) {
						if (regions[x][y] != null) {
							out.add(new Tiles(regions[x][y], x, y, shown));
						}
					}
				}
			}
			return out;
		};
	}

	/**
	 * The open area's prop registry (F209): its entries in their order, each copied, and the models drawn
	 * for them. Put back into the entry objects held under each reference - the registry editor edits
	 * the one it shows in place - and marked changed from what was saved.
	 */
	public static UndoHistory.Document<Object[]> propRegistry(final ctrmap.formats.propdata.ADPropRegistry reg, final Runnable shown) {
		return new UndoHistory.Document<Object[]>() {
			@Override
			public Object subject() {
				return reg;
			}

			@Override
			public Object[] take() {
				List<Integer> keys = new ArrayList<>(reg.entries.keySet());
				List<ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry> entries = new ArrayList<>();
				for (Integer k : keys) {
					entries.add(FlatCopy.copy(reg.entries.get(k)));
				}
				return new Object[]{keys, entries, new java.util.HashMap<>(reg.models)};
			}

			@Override
			@SuppressWarnings("unchecked")
			public void put(Object[] state) {
				List<Integer> keys = (List<Integer>) state[0];
				List<ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry> entries =
						(List<ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry>) state[1];
				java.util.Map<Integer, ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry> held =
						new java.util.LinkedHashMap<>(reg.entries);
				reg.entries.clear();
				for (int i = 0; i < keys.size(); i++) {
					ctrmap.formats.propdata.ADPropRegistry.ADPropRegistryEntry live = held.get(keys.get(i));
					if (live != null) {
						FlatCopy.into(entries.get(i), live);
					} else {
						live = FlatCopy.copy(entries.get(i));
					}
					reg.entries.put(keys.get(i), live);
				}
				reg.models.clear();
				reg.models.putAll((java.util.Map<Integer, ctrmap.formats.h3d.model.H3DModel>) state[2]);
				reg.modified = true;
				shown.run();
			}

			@Override
			public boolean same(Object[] a, Object[] b) {
				return a[0].equals(b[0]) && FlatCopy.sameAll((List<?>) a[1], (List<?>) b[1]) && a[2].equals(b[2]);
			}

			@Override
			public String what() {
				return "the area's prop registry";
			}
		};
	}

	/**
	 * The NPC registry (F209): its entries in file order, each copied, and the models drawn for them. Put
	 * back through the registry, which indexes them again, and marked changed from what was saved.
	 */
	public static UndoHistory.Document<Object[]> npcRegistry(final ctrmap.formats.npcreg.NPCRegistry reg, final Runnable shown) {
		return new UndoHistory.Document<Object[]>() {
			@Override
			public Object subject() {
				return reg;
			}

			@Override
			public Object[] take() {
				return new Object[]{FlatCopy.copyAll(reg.inFileOrder()), new java.util.HashMap<>(reg.models)};
			}

			@Override
			@SuppressWarnings("unchecked")
			public void put(Object[] state) {
				reg.restore(FlatCopy.copyAll((List<ctrmap.formats.npcreg.NPCRegistry.NPCRegistryEntry>) state[0]));
				reg.models.clear();
				reg.models.putAll((java.util.Map<Integer, ctrmap.formats.h3d.model.H3DModel>) state[1]);
				shown.run();
			}

			@Override
			public boolean same(Object[] a, Object[] b) {
				return FlatCopy.sameAll((List<?>) a[0], (List<?>) b[0]) && a[1].equals(b[1]);
			}

			@Override
			public String what() {
				return "the NPC registry";
			}
		};
	}

	/**
	 * A region's prop placements (F209): its props, each a flat record, put back into the records the
	 * prop form holds, and marked changed from what was saved.
	 */
	public static UndoHistory.Document<List<ctrmap.formats.propdata.GRProp>> propPlacements(
			final ctrmap.formats.propdata.GRPropData data, final Runnable shown) {
		return new UndoHistory.Document<List<ctrmap.formats.propdata.GRProp>>() {
			@Override
			public Object subject() {
				return data;
			}

			@Override
			public List<ctrmap.formats.propdata.GRProp> take() {
				return FlatCopy.copyAll(data.props);
			}

			@Override
			public void put(List<ctrmap.formats.propdata.GRProp> state) {
				FlatCopy.putAll(state, data.props);
				data.modified = true;
				shown.run();
			}

			@Override
			public boolean same(List<ctrmap.formats.propdata.GRProp> a, List<ctrmap.formats.propdata.GRProp> b) {
				return FlatCopy.sameAll(a, b);
			}

			@Override
			public String what() {
				return "the region's props";
			}
		};
	}

	/** What a zone history is the history of: the open zone and the map's grid of regions. */
	public static Supplier<List<Object>> scope(LoadedZone zone, Supplier<TileMapPanel> map) {
		return () -> {
			List<Object> out = new ArrayList<>();
			out.add(zone.isOpen() ? zone.open() : null);
			TileMapPanel m = map.get();
			out.add(m == null ? null : m.tilemaps);
			return out;
		};
	}

	/** The zone's props, NPCs, warps and triggers: five lists of flat records. */
	static final class Entities implements UndoHistory.Document<List<List<?>>> {

		private final ZoneEntities e;
		private final Runnable shown;

		Entities(ZoneEntities e, Runnable shown) {
			this.e = e;
			this.shown = shown;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private List<List> live() {
			List<List> out = new ArrayList<>();
			out.add(e.furniture);
			out.add(e.npcs);
			out.add(e.warps);
			out.add(e.triggers1);
			out.add(e.triggers2);
			return out;
		}

		@Override
		public Object subject() {
			return e;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public List<List<?>> take() {
			List<List<?>> out = new ArrayList<>();
			for (List l : live()) {
				out.add(FlatCopy.copyAll(l));
			}
			return out;
		}

		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public void put(List<List<?>> state) {
			List<List> live = live();
			for (int i = 0; i < live.size(); i++) {
				FlatCopy.putAll((List) state.get(i), live.get(i));
			}
			e.modified = true;   //the zone is not what was saved: whether it once was is the save's question
			shown.run();
		}

		@Override
		public boolean same(List<List<?>> a, List<List<?>> b) {
			for (int i = 0; i < a.size(); i++) {
				if (!FlatCopy.sameAll(a.get(i), b.get(i))) {
					return false;
				}
			}
			return true;
		}

		@Override
		public String what() {
			return "the zone's NPCs, warps, triggers and props";
		}
	}

	/** One map region's tiles: forty by forty, four bytes each. */
	static final class Tiles implements UndoHistory.Document<byte[][][]> {

		private final Tilemap region;
		private final int x;
		private final int y;
		private final Runnable shown;

		Tiles(Tilemap region, int x, int y, Runnable shown) {
			this.region = region;
			this.x = x;
			this.y = y;
			this.shown = shown;
		}

		@Override
		public Object subject() {
			return region;
		}

		@Override
		public byte[][][] take() {
			byte[][][] d = region.rawTileData;
			byte[][][] out = new byte[d.length][][];
			for (int i = 0; i < d.length; i++) {
				out[i] = new byte[d[i].length][];
				for (int j = 0; j < d[i].length; j++) {
					out[i][j] = d[i][j].clone();
				}
			}
			return out;
		}

		@Override
		public void put(byte[][][] state) {
			for (int i = 0; i < state.length; i++) {
				for (int j = 0; j < state[i].length; j++) {
					region.setTileData(i, j, state[i][j].clone());   //which marks the region modified
				}
			}
			region.updateImage();
			shown.run();
		}

		@Override
		public boolean same(byte[][][] a, byte[][][] b) {
			return java.util.Arrays.deepEquals(a, b);
		}

		@Override
		public String what() {
			return "the tiles of region (" + x + ", " + y + ")";
		}
	}
}
