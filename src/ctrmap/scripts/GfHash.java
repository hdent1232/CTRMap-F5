package ctrmap.scripts;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The GameFreak field-script native-name hash (ORAS/XY Pawn/AMX engine) and the
 * bundled ORAS native-name table.
 *
 * <p>Reverse-engineered from code.bin + DllField.cro and verified against 191
 * observed script hashes and 9 independent ground-truths (StartBattle,
 * PlayerSetBP, DelTrainerObj, ...): a zone script's {@code natives[]} prefix
 * holds {@code {0, nameHash}} records, {@code SYSREQ_N(index, nargs*4)} indexes
 * it, and the engine binds each record's hash to a C function registered BY
 * NAME at CRO load (no static hash-&gt;function table exists - the hashes never
 * appear as constants). The hash is a base-131 XOR rolling hash:
 * <pre>
 *   h = 0;  for each ASCII byte c of the name:  h = (h * 0x83) ^ c;   // mod 2^32
 * </pre>
 * seed 0, no final mix, case-sensitive. This lets CTRMap show and author
 * {@code SYSREQ_N} calls by NAME instead of a magic number, and add a native by
 * name (write {@code {0, hash(name)}} into the table).
 */
public class GfHash {

	private static Map<Integer, String> NAME_BY_HASH;
	private static Map<String, Integer> HASH_BY_NAME;

	/** The 32-bit native-name hash: h=0; for c in name: h=(h*0x83)^c (mod 2^32). */
	public static int hash(String name) {
		int h = 0;
		for (int i = 0; i < name.length(); i++) {
			h = (h * 0x83) ^ (name.charAt(i) & 0xFF);
		}
		return h;
	}

	/** The registered ORAS native name for a hash, or null if unknown. */
	public static synchronized String nameForHash(int hash) {
		ensureLoaded();
		return NAME_BY_HASH.get(hash);
	}

	/** The hash for a known native name (from the table), or hash(name) if absent. */
	public static synchronized int hashForName(String name) {
		ensureLoaded();
		Integer h = HASH_BY_NAME.get(name);
		return h != null ? h : hash(name);
	}

	/** The bundled name-&gt;hash table (ORAS field natives). Read-only. */
	public static synchronized Map<String, Integer> table() {
		ensureLoaded();
		return HASH_BY_NAME;
	}

	private static synchronized void ensureLoaded() {   //the lock its callers hold, said where the tables are written (F016)
		if (NAME_BY_HASH != null) {
			return;
		}
		Map<Integer, String> byHash = new HashMap<>();
		Map<String, Integer> byName = new HashMap<>();
		//REFUSED, NOT EMPTY (F130): a table that was missing or that one row stopped read as "no
		//native has a name", every hash printed bare, with a line on a console the build does not have
		try (InputStream in = ctrmap.resources.ResourceAccess.getStream("oras_natives.tsv")) {
			read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), byHash, byName);
		} catch (java.io.IOException ex) {
			throw new IllegalStateException("The native-name table built into the program could not be read: "
					+ ctrmap.util.Bytes.reason(ex), ex);
		}
		NAME_BY_HASH = byHash;
		HASH_BY_NAME = byName;
	}

	/**
	 * Reads a native-name table: one {@code HASH<tab>name} row per line, the hash in hex; the first
	 * name given a hash is its canonical one. STRICT: a row that is not that throws, naming its line.
	 */
	static void read(BufferedReader r, Map<Integer, String> byHash, Map<String, Integer> byName) throws java.io.IOException {
		int lineNo = 0;
		for (String line; (line = r.readLine()) != null;) {
			lineNo++;
			if (line.trim().isEmpty()) {
				continue;
			}
			int tab = line.indexOf('\t');
			int h;
			try {
				h = (int) Long.parseLong(tab <= 0 ? "" : line.substring(0, tab).trim(), 16);
			} catch (NumberFormatException notHex) {
				throw new java.io.IOException("line " + lineNo + " is not a hex hash, a tab and a name: \"" + line + "\"");
			}
			String name = line.substring(tab + 1).trim();
			byName.putIfAbsent(name, h);
			byHash.putIfAbsent(h, name); // first (canonical) name wins on collisions
		}
	}
}
