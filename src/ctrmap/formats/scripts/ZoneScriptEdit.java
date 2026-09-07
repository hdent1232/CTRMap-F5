package ctrmap.formats.scripts;

import ctrmap.formats.text.GFMessageFile;
import ctrmap.formats.zone.Zone;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A scripted addition to a zone that lands whole or not at all.
 *
 * <p>Every "Add ..." wizard in the NPC editor changes the same three things
 * in the same order: it may transplant a display routine the zone's script
 * lacks, it adds a script case (a talker, a sign, an item giver, a battle
 * challenge), and it appends the story-text lines that case shows. Each of
 * those can fail, and a failure after a partial change is a zone that
 * freezes in game - a case pointing at a line that was never written, a
 * routine half-copied. So the surgery runs on a COPY of the script, the text
 * is stored where the game reads it first, and only then does the copy
 * become the zone's script. Nothing that fails leaves a mark: the copy is
 * dropped, the lines are taken back out of the story file.
 *
 * <p>This used to be written out four times, once per wizard, inside the
 * dialog handlers, and none of the four could be tested: each sat behind a
 * modal form. It is one thing now, with no window in it, and the wizards
 * hand it their surgery and their lines.
 */
public final class ZoneScriptEdit {

	/** The addition itself: adds a case to work and returns its case id. Lines it shows start at firstNewLine. */
	public interface Surgery {

		int addCase(GFLPawnScript work, int firstNewLine);
	}

	/** Writes a story-text file where the game will read it; throws with the reason when it cannot. */
	public interface Store {

		void store(int textID, GFMessageFile msg) throws IOException;
	}

	/** Nothing was changed; {@link #reason()} is the sentence the user should see. */
	public static final class Refused extends RuntimeException {

		public Refused(String message) {
			super(message);
		}

		/**
		 * What to tell the user. Never null - every refusal here is built
		 * from a sentence - which is why callers report this rather than
		 * {@code getMessage()}: a report handed only a message that may be
		 * null is the shape DialogSeamTest refuses.
		 */
		public String reason() {
			return getMessage();
		}
	}

	private final Zone zone;
	private final String what;
	private GFLPawnScript msgDonor;
	private GFLPawnScript signDonor;
	private GFMessageFile msg;
	private int textID = -1;
	private final List<String> lines = new ArrayList<>();

	/** An edit to zone's script; {@code what} names it in a refusal ("the talker script"). */
	public ZoneScriptEdit(Zone zone, String what) {
		this.zone = zone;
		this.what = what;
	}

	/** Transplants the message-display routine from donor when the zone's script has none. */
	public ZoneScriptEdit injectMsgWrapperFrom(GFLPawnScript donor) {
		msgDonor = donor;
		return this;
	}

	/** Transplants the sign-display routine from donor when the zone's script has none. */
	public ZoneScriptEdit injectSignWrapperFrom(GFLPawnScript donor) {
		signDonor = donor;
		return this;
	}

	/**
	 * The story-text lines the addition shows, appended to msg (story text
	 * file textID) and stored before the script is committed. The surgery is
	 * told the index the first of them will have.
	 */
	public ZoneScriptEdit withText(GFMessageFile msg, int textID, List<String> newLines) {
		this.msg = msg;
		this.textID = textID;
		lines.clear();
		lines.addAll(newLines);
		return this;
	}

	/** The index the first added line will have: where the story file ends now. */
	public int firstNewLine() {
		return msg == null ? 0 : msg.getLineCount();
	}

	/**
	 * Runs the addition and returns the new case id. On any failure throws
	 * {@link Refused} with the sentence the user should see, having changed
	 * nothing: the zone keeps its script, the story file its lines.
	 */
	public int apply(Surgery surgery, Store store) {
		if (!lines.isEmpty() && msg == null) {
			//refused before anything moves: text with nowhere to go
			throw new Refused("Could not add " + what + ": it shows text, but the zone's story text file is not loaded.");
		}
		GFLPawnScript work;
		try {
			work = new GFLPawnScript(zone.s.getScriptBytes());
			work.decompressThis();
		} catch (RuntimeException ex) {
			throw new Refused("Could not copy the zone script:\n" + reason(ex));
		}
		if (msgDonor != null && ZoneScriptAnalyzer.findMsgWrapper(work) == null) {
			try {
				MsgWrapperInjector.injectMsgWrapper(work, msgDonor);
				if (ZoneScriptAnalyzer.findMsgWrapper(work) == null) {
					throw new MsgWrapperInjector.InjectionException("The injected routine did not verify.");
				}
			} catch (RuntimeException ex) {
				throw new Refused("Could not inject the message routine:\n" + reason(ex));
			}
		}
		if (signDonor != null && ZoneScriptAnalyzer.findSignWrapper(work) == null) {
			try {
				SignWrapperInjector.injectSignWrapper(work, signDonor);
				if (ZoneScriptAnalyzer.findSignWrapper(work) == null) {
					throw new SignWrapperInjector.InjectionException("The injected routine did not verify.");
				}
			} catch (RuntimeException ex) {
				throw new Refused("Could not transplant the sign routine:\n" + reason(ex));
			}
		}
		int firstNew = firstNewLine();
		int caseId;
		try {
			caseId = surgery.addCase(work, firstNew);
		} catch (RuntimeException ex) {
			throw new Refused("Could not add " + what + ":\n" + reason(ex));
		}
		if (!lines.isEmpty()) {
			for (String line : lines) {
				msg.addLine(line);
			}
			try {
				store.store(textID, msg);
			} catch (IOException | RuntimeException ex) {
				//keep the cached story file consistent with disk; the zone's
				//script is still the untouched original
				for (int i = lines.size() - 1; i >= 0; i--) {
					msg.removeLine(firstNew + i);
				}
				throw new Refused("Could not store story text file " + textID + ":\n" + reason(ex));
			}
		}
		zone.s = work; //commit - nothing before this point touched the zone
		return caseId;
	}

	private static String reason(Throwable ex) {
		return ex.getMessage() != null ? ex.getMessage() : ex.toString();
	}
}
