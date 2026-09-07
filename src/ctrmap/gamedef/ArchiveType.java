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
	SOUND_BCSAR
}
