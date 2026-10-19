package ctrmap.gamedef;

/**
 * The archives the editor opens, named independently of where any one game
 * keeps them. {@link GameProfile#archivePath} turns a name into a RomFS path,
 * and a profile returning null means "this game does not have it, or nobody
 * has verified where it is" - never a guess.
 *
 * <p>Lives here rather than on {@code Workspace} for the reason in
 * {@link GameType}: naming an archive is not the same as having a workspace
 * open, and while this enum was nested in the global, 85 files imported the
 * god object to name one.
 */
public enum ArchiveType {
	AREA_DATA,
	FIELD_DATA,
	TRAINER_DATA,
	TRAINER_CLASS,
	TRAINER_POKE,
	MAISON_SET_POOL_A,
	MAISON_CLASS_LIST_A,
	MAISON_SET_POOL_B,
	MAISON_CLASS_LIST_B,
	MAISON_SET_POOL_C,
	MAP_MATRIX,
	GAMETEXT,
	STORYTEXT,
	ZONE_DATA,
	BUILDING_MODELS,
	NPC_REGISTRIES,
	MOVE_MODELS,
	/** Species base stats/types/abilities (read-only reference data). */
	PERSONAL,
	/** Move type/category/power mini-container (read-only reference data). */
	MOVE_DATA,
	/** Item records - 776 x 36 bytes in Gen 6, id == entry index. */
	ITEM_DATA,
	SOUND_BCSAR,
	/**
	 * The scripts no one zone owns, each entry a bare Pawn script (read-only here). They send the player
	 * to zones too, so a scan for zones nothing reaches must read them (F225).
	 */
	SHARED_SCRIPTS;

	/**
	 * Whether CTRMap can make a new, empty entry of this archive - a map matrix of one cell, a blank region.
	 * The type's own answer (census M.136): the raw browser's Add new file knew two types by name and did
	 * nothing at all, saying nothing, for the other nineteen. A type added later answers no until someone
	 * writes how to make one.
	 */
	public boolean canMakeNewEntry() {
		return this == MAP_MATRIX || this == FIELD_DATA;
	}
}
