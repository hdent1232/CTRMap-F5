package ctrmap.humaninterface;


/**
 * What the battle-challenge wizard was filled in with.
 *
 * <p>One of the ten classes that lived inside {@link NPCEditForm}, which was
 * 2840 lines. Each was an inner class reading the editor it sat in through
 * synthetic bridges, so none could be built without one; each is handed what
 * it needs now and is an ordinary class in a file of its own.
 *
 * <p>What the battle-challenge form collects, as values.
 */
public final class ChallengeInput {

	public final java.util.List<Integer> trainerIds = new java.util.ArrayList<>();
	public int bpPerWin = 3;
	public int milestone = 0;
	public int milestoneBonus = 20;
	/** The streak save variable as typed: hex, with or without 0x. */
	public String streakWorkHex = Integer.toHexString(ctrmap.formats.scripts.GauntletScriptWizard.DEFAULT_STREAK_WORK);
	public boolean loseWhiteout = false;
	public String intro = "", win = "", lose = "";
	public int model = -1;
}
