package windowcheck;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Preferences held in memory only, so the editor run by the window check can never write the owner's
 * registry. Seeded by Driver before the editor starts. Every write is printed, so the run shows
 * what the editor would have saved.
 */
public final class MemPrefs extends AbstractPreferences {

	private final Map<String, String> values = new HashMap<>();
	private final Map<String, MemPrefs> kids = new HashMap<>();

	MemPrefs(MemPrefs parent, String name) {
		super(parent, name);
	}

	static final MemPrefs USER = new MemPrefs(null, "");
	static final MemPrefs SYSTEM = new MemPrefs(null, "");

	/** The factory the JVM is pointed at with -Djava.util.prefs.PreferencesFactory. */
	public static final class Factory implements PreferencesFactory {

		@Override
		public Preferences userRoot() {
			return USER;
		}

		@Override
		public Preferences systemRoot() {
			return SYSTEM;
		}
	}

	@Override
	protected void putSpi(String key, String value) {
		if (Driver.seeded) {
			System.out.println("PREF WRITE " + absolutePath() + " " + key + " = " + value);
		}
		values.put(key, value);
	}

	@Override
	protected String getSpi(String key) {
		return values.get(key);
	}

	@Override
	protected void removeSpi(String key) {
		values.remove(key);
	}

	@Override
	protected void removeNodeSpi() {
	}

	@Override
	protected String[] keysSpi() {
		return values.keySet().toArray(new String[0]);
	}

	@Override
	protected String[] childrenNamesSpi() {
		return kids.keySet().toArray(new String[0]);
	}

	@Override
	protected AbstractPreferences childSpi(String name) {
		return kids.computeIfAbsent(name, n -> new MemPrefs(this, n));
	}

	@Override
	protected void syncSpi() {
	}

	@Override
	protected void flushSpi() {
	}
}
