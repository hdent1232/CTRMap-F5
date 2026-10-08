package ctrmap.formats.zone;

import ctrmap.util.LittleEndianDataInputStream;
import ctrmap.util.LittleEndianDataOutputStream;
import ctrmap.formats.GameFiles;
import ctrmap.formats.containers.AD;
import ctrmap.formats.containers.MM;
import ctrmap.formats.garc.LZ11;
import ctrmap.formats.h3d.BCHFile;
import ctrmap.formats.h3d.texturing.H3DTexture;
import ctrmap.formats.npcreg.NPCRegistry;
import ctrmap.gamedef.ArchiveType;
import ctrmap.gamedef.GameProfile;
import ctrmap.gamedef.GameType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import ctrmap.util.LittleEndian;

/**
 * From pk3DS source
 */
public class ZoneHeader {

	public int mapType;
	public int mapMove;
	public int areadataID;
	public AD areadata;
	public List<H3DTexture> propTextures = new ArrayList<>();
	public List<H3DTexture> worldTextures = new ArrayList<>();
	public NPCRegistry npcreg;
	public int mapmatrixID;
	public MM mapmatrix;
	public int textID; //normal text is also stored in script, game text is only UI stuff
	public int script; //handles script texts so it's read like a gametext file except that it's not from GAMETEXT. Will be implemented alongside scripting, if ever.
	public int townMapGroup;

	public int BGMSpring; //needs additional BCSAR research to get the numbers, the best way is probably to hack BrawlBox?
	public int BGMSummer;
	public int BGMAutumn;
	public int BGMWinter;

	public int parentMap; //useful for getting location names
	public int OLvalue;

	public int weather;
	public int battleBG;
	public int mapChange;

	public boolean enableSkybox;
	public boolean enableRollerSkates;
	public boolean enableCycling;
	public boolean enableRunning;
	public boolean enableEscapeRope;
	public boolean enableFlyFrom;
	public boolean enableCyclingBGM;
	public boolean unknownFlag;

	public int camera1;
	public int camera2;
	public int cameraFlags;

	public int X;
	public int Z;
	public int Y;
	public int X2;
	public int Z2;
	public int Y2;

	public int PX;
	public int PX2;
	public int PY;
	public int PY2;

	public int unknownFlags;

	public GameType game;

	public boolean enableDowsingMachine;
	public boolean enableBreathFX;
	public boolean enableSpecialWalking = false;
	public boolean enableFlashableDarkness = false;
	public boolean enableGhosting;
	public boolean enable3D;
	public int OAZoneNumber;

	public ZoneHeader(byte[] data, GameType game) {
		this.game = game;
		parse(data);
	}

	/**
	 * The parent map - the location a zone is named by - out of a raw zone header: the low 10 bits
	 * of the word at 0x1C (its high 6 are the OL value). The one spelling of that field: the
	 * repurpose scanner and the atmosphere picker each spelled it themselves (F225, lead 55).
	 */
	public static int parentMapOf(byte[] header) {
		return LittleEndian.u16(header, 0x1C) & 0x3FF;
	}

	/**
	 * Reads every field the bytes hold over this header's own, leaving the area, registry and matrix
	 * it opened: what it is made with, and what a discard puts back (F106).
	 *
	 * @throws IllegalStateException when the bytes are shorter than a header; nothing is read then
	 */
	public void parse(byte[] data) {
		if (data == null || data.length < SIZE) {
			//REFUSED, NOT LOGGED (F115): a short header left every field past the end at its default,
			//and the next save wrote those defaults over the zone
			throw new IllegalStateException("A zone header is " + SIZE + " bytes; this one is "
					+ (data == null ? "absent" : data.length + " byte(s)") + ", so the zone was not read.");
		}
		tail = java.util.Arrays.copyOfRange(data, SIZE, data.length);
		//as declared: each game reads only its own of the two
		enableSpecialWalking = false;
		enableFlashableDarkness = false;
		try {
			LittleEndianDataInputStream dis = new LittleEndianDataInputStream(new ByteArrayInputStream(data));
			mapType = dis.read();
			mapMove = dis.read();
			areadataID = dis.readShort();
			mapmatrixID = dis.readShort();
			textID = dis.readShort();

			BGMSpring = dis.readInt();
			BGMSummer = dis.readInt();
			BGMAutumn = dis.readInt();
			BGMWinter = dis.readInt();

			script = dis.readShort();
			townMapGroup = dis.readShort();

			int H0x1C = dis.readShort();
			parentMap = parentMapOf(data);
			OLvalue = H0x1C >> 10;

			int H0x1E = dis.readShort();
			weather = H0x1E & 0x1F;
			enableSkybox = ((H0x1E >> 5) & 1) == 1;
			enableRollerSkates = ((H0x1E >> 6) & 1) == 1;
			battleBG = (H0x1E >> 7) & 0x7F;
			unnamed1E = H0x1E & 0xC000;

			int H0x20 = dis.readShort();
			mapChange = H0x20 & 0x1F;
			unnamed20 = H0x20 & 0x03E0;
			enableCycling = ((H0x20 >> 10) & 1) == 1;
			enableRunning = ((H0x20 >> 11) & 1) == 1;
			enableEscapeRope = ((H0x20 >> 12) & 1) == 1;
			enableFlyFrom = ((H0x20 >> 13) & 1) == 1;
			enableCyclingBGM = ((H0x20 >> 14) & 1) == 1;
			unknownFlag = ((H0x20 >> 15) & 1) == 1;

			camera1 = dis.readShort();
			camera2 = dis.readShort();
			cameraFlags = dis.readShort();

			unknownFlags = dis.readInt();
			//WHERE EACH FLAG SITS IS THE GAME'S, asked of its profile (F207): this was "X/Y, or else
			//ORAS", so a game nobody measured was read with ORAS's bits. Unmeasured, none is decoded
			GameProfile.ZoneFlagLayout l = flagLayout();
			if (l != null) {
				enableGhosting = bit(unknownFlags, l.ghosting);
				enableBreathFX = bit(unknownFlags, l.breathFx);
				enableDowsingMachine = bit(unknownFlags, l.dowsing);
				enable3D = bit(unknownFlags, l.stereo3d);
				enableSpecialWalking = l.specialWalkingOff >= 0 && !bit(unknownFlags, l.specialWalkingOff);
				enableFlashableDarkness = bit(unknownFlags, l.flashableDarkness);
			}
			//OA exclusive zone ID
			OAZoneNumber = (unknownFlags >> 21);

			X = dis.readShort();
			Z = dis.readShort();
			Y = dis.readShort();
			X2 = dis.readShort();
			Z2 = dis.readShort();
			Y2 = dis.readShort();
		} catch (IOException ex) {
			Logger.getLogger(ZoneHeader.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	/**
	 * Sets the zone's music (F221). The header carries a track for each season and the Zone tab showed
	 * and wrote Spring's alone, so a change could miss the slot the game plays. Every slot that held
	 * Spring's track moves with it; a slot set to a different track was set apart, and stays.
	 */
	public void setMusic(int track) {
		if (BGMSummer == BGMSpring) {
			BGMSummer = track;
		}
		if (BGMAutumn == BGMSpring) {
			BGMAutumn = track;
		}
		if (BGMWinter == BGMSpring) {
			BGMWinter = track;
		}
		BGMSpring = track;
	}

	public byte[] assembleData() {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(baos);
			dos.write(mapType);
			dos.write(mapMove);
			dos.writeShort((short) areadataID);
			dos.writeShort((short) mapmatrixID);
			dos.writeShort((short) textID);
			dos.writeInt(BGMSpring);
			dos.writeInt(BGMSummer);
			dos.writeInt(BGMAutumn);
			dos.writeInt(BGMWinter);
			dos.writeShort((short) script);
			dos.writeShort((short) townMapGroup);

			short H0x1C = (short) parentMap;
			H0x1C = (short) (H0x1C | ((OLvalue) << 10));
			dos.writeShort(H0x1C);

			short H0x1E = (short) weather;
			H0x1E = (short) (H0x1E | ((enableSkybox) ? 1 : 0) << 5);
			H0x1E = (short) (H0x1E | ((enableRollerSkates) ? 1 : 0) << 6);
			H0x1E = (short) (H0x1E | (battleBG << 7));
			H0x1E = (short) (H0x1E | unnamed1E);
			dos.writeShort(H0x1E);

			short H0x20 = (short) mapChange;
			H0x20 = (short) (H0x20 | ((enableCycling) ? 1 : 0) << 10);
			H0x20 = (short) (H0x20 | ((enableRunning) ? 1 : 0) << 11);
			H0x20 = (short) (H0x20 | ((enableEscapeRope) ? 1 : 0) << 12);
			H0x20 = (short) (H0x20 | ((enableFlyFrom) ? 1 : 0) << 13);
			H0x20 = (short) (H0x20 | ((enableCyclingBGM) ? 1 : 0) << 14);
			H0x20 = (short) (H0x20 | ((unknownFlag) ? 1 : 0) << 15);
			H0x20 = (short) (H0x20 | unnamed20);
			dos.writeShort(H0x20);

			dos.writeShort((short) camera1);
			dos.writeShort((short) camera2);
			dos.writeShort((short) cameraFlags);

			calculateFlags();

			dos.writeInt(unknownFlags);

			dos.writeShort((short) X);
			dos.writeShort((short) Z);
			dos.writeShort((short) Y);

			dos.writeShort((short) X2);
			dos.writeShort((short) Z2);
			dos.writeShort((short) Y2);
			dos.write(tail);

			return baos.toByteArray();
		} catch (IOException ex) {
			Logger.getLogger(ZoneHeader.class.getName()).log(Level.SEVERE, null, ex);
			return null;
		}
	}

	/** A zone header's length; the parser reads exactly this much. */
	public static final int SIZE = 0x38;

	/**
	 * The bits the parser does not name - 0x1E bits 14-15 and 0x20 bits 5-9 - and any bytes past
	 * {@link #SIZE}, carried through to the write (F115): they were dropped, so a zone holding any
	 * of them read as modified the moment it opened and was saved stripped.
	 */
	private int unnamed1E, unnamed20;
	private byte[] tail = new byte[0];

	/**
	 * Writes the named flags into the flag word at the game's bit positions (F207). For a game with
	 * no measured layout the word is carried as read - none was decoded from it - and a flag set
	 * anyway is refused: there is no bit it could be written to that is not a guess.
	 */
	public void calculateFlags() {
		GameProfile.ZoneFlagLayout l = flagLayout();
		if (l == null) {
			if (enableGhosting || enableBreathFX || enableDowsingMachine || enable3D || enableSpecialWalking
					|| enableFlashableDarkness) {
				throw new IllegalStateException("A zone flag (ghosting, cold breath, dowsing, 3D, special walking or"
						+ " flashable darkness) was set, and where " + (game == null ? "a zone opened with no game"
						: GameProfile.of(game).displayName()) + " keeps it in the header was never recorded - it was"
						+ " not written. Edit the raw flag word instead.");
			}
			return;
		}
		unknownFlags = put(unknownFlags, l.ghosting, enableGhosting);
		unknownFlags = put(unknownFlags, l.breathFx, enableBreathFX);
		unknownFlags = put(unknownFlags, l.dowsing, enableDowsingMachine);
		unknownFlags = put(unknownFlags, l.stereo3d, enable3D);
		unknownFlags = put(unknownFlags, l.specialWalkingOff, !enableSpecialWalking);
		unknownFlags = put(unknownFlags, l.flashableDarkness, enableFlashableDarkness);
	}

	/** This header's game's flag layout, or null when the game is not known or its layout was never recorded. */
	public GameProfile.ZoneFlagLayout flagLayout() {
		return game == null ? null : GameProfile.of(game).zoneFlagLayout();
	}

	private static boolean bit(int word, int bit) {
		return bit >= 0 && (word >>> bit & 1) == 1;
	}

	private static int put(int word, int bit, boolean on) {
		return bit < 0 ? word : on ? word | 1 << bit : word & ~(1 << bit);
	}

	/**
	 * Opens the area, NPC registry and map matrix this header names, from the
	 * staged copies of the game it is handed.
	 *
	 * <p>Handed, rather than fetched: this used to take all three from the
	 * application's global session, so a header could only ever open the
	 * application's game, and a suite wanting to see which area a header
	 * resolved to had to install a workspace first.
	 * {@code ctrmap.tests.HandedGameTest} hands it two games.
	 *
	 * @param files the game whose staged entries are opened; null is refused
	 * in words, because a header that opened nothing and said nothing is a
	 * zone the loader reports as empty
	 */
	public void fetchArchives(GameFiles files) {
		if (files == null) {
			throw new IllegalArgumentException("a zone header must be handed the game its area, registry and"
					+ " matrix are staged in; handed null it would open nothing and say nothing");
		}
		areadata = new AD(files.staged(ArchiveType.AREA_DATA, areadataID), files);
		byte[] adbch1 = areadata.getFile(1);
		if (ctrmap.formats.h3d.BchSections.hasMagic(adbch1)) {
			BCHFile adbch1bch = new BCHFile(adbch1);
			if (adbch1bch.errorlevel == 0) {
				propTextures.addAll(adbch1bch.textures);
			}
		}
		byte[] wTex = areadata.getFile(11);
		if (wTex[0] == 0x11) {
			//LZ11 COMPRESSED THOT ALERT
			byte[] dec = LZ11.decompress(wTex);
			worldTextures.addAll(new BCHFile(dec).textures);
		} else {
			worldTextures.addAll(new BCHFile(wTex).textures);
		}
		npcreg = new NPCRegistry(files.staged(ArchiveType.NPC_REGISTRIES, areadataID), files);
		mapmatrix = new MM(files.staged(ArchiveType.MAP_MATRIX, mapmatrixID), files);
	}

	public void freeArchives() {
		areadata = null;
		propTextures.clear();
		worldTextures.clear();
		npcreg = null;
		mapmatrix = null;
	}
}
