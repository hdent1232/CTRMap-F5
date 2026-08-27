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

	/** A display label for a hash: the name, or "0x&lt;hash&gt;" when unknown. */
	public static String label(int hash) {
		String n = nameForHash(hash);
		return n != null ? n : String.format("0x%08X", hash);
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

	private static void ensureLoaded() {
		if (NAME_BY_HASH != null) {
			return;
		}
		Map<Integer, String> byHash = new HashMap<>();
		Map<String, Integer> byName = new HashMap<>();
		try (InputStream in = GfHash.class.getClassLoader()
				.getResourceAsStream("ctrmap/resources/oras_natives.tsv")) {
			if (in != null) {
				BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				String line;
				while ((line = r.readLine()) != null) {
					int tab = line.indexOf('\t');
					if (tab <= 0) {
						continue;
					}
					int h = (int) Long.parseLong(line.substring(0, tab).trim(), 16);
					String name = line.substring(tab + 1).trim();
					byName.putIfAbsent(name, h);
					byHash.putIfAbsent(h, name); // first (canonical) name wins on collisions
				}
			}
		} catch (Exception ex) {
			System.err.println("GfHash: native table load failed: " + ex);
		}
		NAME_BY_HASH = byHash;
		HASH_BY_NAME = byName;
	}
}
