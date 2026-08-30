package ctrmap.update;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.prefs.Preferences;

/**
 * Asks GitHub whether a newer release of CTRMap-F5 has been published.
 *
 * <p>Deliberately dependency-free: one HTTPS GET against the releases API and a
 * few field reads out of the JSON, because pulling a JSON library into a Java 8
 * Swing app to read four strings is not worth it. Only the fields below are
 * read, and each is treated as untrusted text - the tag decides the version, the
 * download URL is checked to be an https github.com address before it is used,
 * and the release notes are only ever displayed, never executed.
 *
 * <p>Everything here is best-effort and silent on failure: no network, a rate
 * limit, an API change or an offline user must never interrupt someone who just
 * wants to edit a map.
 */
public class UpdateChecker {

	public static final String REPO = "hdent1232/CTRMap-F5";
	private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
	/** Where the user is sent when there is no downloadable asset. */
	public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

	private static final String PREF_NODE = "ctrmap.update";
	private static final String PREF_ENABLED = "CHECK_ON_STARTUP";
	private static final String PREF_SKIPPED = "SKIPPED_VERSION";

	/** A published release that is newer than the running build. */
	public static class Release {

		public String version;      //tag, e.g. "1.2.0"
		public String notes;        //release body, shown to the user
		public String downloadUrl;  //the .zip asset, or null when none was published
		public String assetName;
		public long assetSize;
		/** SHA-256 of the asset, lowercase hex. An update is REFUSED without it. */
		public String sha256;
	}

	/** True when the startup check is switched on (default: on). */
	public static boolean checkOnStartup() {
		return prefs().getBoolean(PREF_ENABLED, true);
	}

	public static void setCheckOnStartup(boolean on) {
		prefs().putBoolean(PREF_ENABLED, on);
	}

	/** The user asked not to be told about this version again. */
	public static void skip(String version) {
		prefs().put(PREF_SKIPPED, version == null ? "" : version);
	}

	public static boolean isSkipped(String version) {
		return version != null && version.equals(prefs().get(PREF_SKIPPED, ""));
	}

	private static Preferences prefs() {
		return Preferences.userRoot().node(PREF_NODE);
	}

	/**
	 * The latest published release when it is newer than the running build, else
	 * null. Never throws: any failure means "no update to report".
	 */
	public static Release check() {
		try {
			String json = get(API);
			if (json == null) {
				return null;
			}
			String tag = field(json, "tag_name");
			if (tag == null || !AppVersion.isNewerThanCurrent(tag)) {
				return null;
			}
			Release r = new Release();
			r.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
			r.notes = field(json, "body");
			//the first .zip asset is the packaged app
			int assets = json.indexOf("\"assets\"");
			if (assets >= 0) {
				String tail = json.substring(assets);
				String url = field(tail, "browser_download_url");
				String name = field(tail, "name");
				if (url != null && url.toLowerCase().endsWith(".zip") && isGithubHttps(url)) {
					r.downloadUrl = url;
					r.assetName = name;
					r.assetSize = longField(tail, "size");
					r.sha256 = sha256Of(tail);
				}
			}
			return r;
		} catch (Exception ex) {
			return null; //offline, rate-limited, or the API moved: say nothing
		}
	}

	/**
	 * The asset's SHA-256, from GitHub's own {@code "digest": "sha256:..."}
	 * field. Returned lowercase without the prefix, or null when absent - and a
	 * null makes {@link Updater} refuse the update rather than install something
	 * it cannot verify.
	 */
	public static String sha256Of(String assetsJson) {
		String d = field(assetsJson, "digest");
		if (d == null) {
			return null;
		}
		String s = d.trim().toLowerCase();
		if (s.startsWith("sha256:")) {
			s = s.substring("sha256:".length());
		}
		return s.matches("[0-9a-f]{64}") ? s : null;
	}

	/** Only ever download from GitHub over TLS. */
	public static boolean isGithubHttps(String url) {
		try {
			URL u = new URL(url);
			String host = u.getHost().toLowerCase();
			return "https".equalsIgnoreCase(u.getProtocol())
					&& (host.equals("github.com") || host.endsWith(".github.com")
					|| host.equals("objects.githubusercontent.com"));
		} catch (Exception ex) {
			return false;
		}
	}

	private static String get(String url) throws Exception {
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("Accept", "application/vnd.github+json");
		c.setRequestProperty("User-Agent", "CTRMap-F5/" + AppVersion.current());
		c.setConnectTimeout(6000);
		c.setReadTimeout(8000);
		if (c.getResponseCode() != 200) {
			return null;
		}
		try (InputStream in = c.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				if (out.size() > 4 << 20) {
					break; //a releases payload is never this big
				}
			}
			return new String(out.toByteArray(), "UTF-8");
		}
	}

	/** First string value of {@code "name": "..."}, with JSON escapes undone. */
	public static String field(String json, String name) {
		String key = "\"" + name + "\"";
		int k = json.indexOf(key);
		if (k < 0) {
			return null;
		}
		int colon = json.indexOf(':', k + key.length());
		if (colon < 0) {
			return null;
		}
		int i = colon + 1;
		while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
			i++;
		}
		if (i >= json.length() || json.charAt(i) != '"') {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (i++; i < json.length(); i++) {
			char ch = json.charAt(i);
			if (ch == '\\' && i + 1 < json.length()) {
				char e = json.charAt(++i);
				switch (e) {
					case 'n':
						sb.append('\n');
						break;
					case 'r':
						break;
					case 't':
						sb.append('\t');
						break;
					case 'u':
						if (i + 4 < json.length()) {
							try {
								sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
							} catch (NumberFormatException ignore) {
							}
							i += 4;
						}
						break;
					default:
						sb.append(e);
				}
			} else if (ch == '"') {
				return sb.toString();
			} else {
				sb.append(ch);
			}
		}
		return null;
	}

	/** First numeric value of {@code "name": 123}, or 0. */
	public static long longField(String json, String name) {
		String key = "\"" + name + "\"";
		int k = json.indexOf(key);
		if (k < 0) {
			return 0;
		}
		int colon = json.indexOf(':', k + key.length());
		if (colon < 0) {
			return 0;
		}
		int i = colon + 1;
		while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
			i++;
		}
		int start = i;
		while (i < json.length() && Character.isDigit(json.charAt(i))) {
			i++;
		}
		try {
			return start == i ? 0 : Long.parseLong(json.substring(start, i));
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
