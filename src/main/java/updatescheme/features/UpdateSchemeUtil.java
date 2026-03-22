package updatescheme.features;

import arc.Core;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import arc.util.Strings;
import mindustry.game.Schematic;
import mindustry.game.Schematics;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static mindustry.Vars.schematics;

final class UpdateSchemeUtil {

    private static final Pattern base64PayloadPattern = Pattern.compile("bXNja[0-9A-Za-z+/=_\\-\\s]{64,}");
    private static final Pattern wrappedPayloadPattern = Pattern.compile("(?s)^\\s*\\[METADATA\\]\\s*(.*?)\\s*\\[SchemeCode\\]\\s*(.+?)\\s*$");
    private static final Pattern metadataLinePattern = Pattern.compile("(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*=\\s*\"((?:\\\\.|[^\"])*)\"\\s*$");

    static final String keyAuthorName = "us-author-name";
    static final String keyAuthorIndexRef = "us-author-index-ref";
    static final String keyRegistryToken = "us-registry-token";
    static final String keyRegistryRepo = "us-registry-repo";

    private UpdateSchemeUtil() {
    }

    static Schematic parseDownloadedSchematic(String rawText) {
        PublishedPayload payload = parsePublishedPayload(rawText);
        return payload == null ? null : payload.schematic;
    }

    static PublishedPayload parsePublishedPayload(String rawText) {
        if (rawText == null || rawText.isEmpty()) return null;

        StringMap metadata = parseMetadata(rawText);
        String content = normalizeShareContent(rawText);
        if (content.isEmpty() || !content.startsWith("bXNja")) return null;

        Schematic direct = tryReadBase64(content);
        if (direct != null) {
            applyMetadataTags(direct, metadata);
            return new PublishedPayload(direct, metadata, content, rawText);
        }

        String padded = padBase64(content);
        if (!padded.equals(content)) {
            Schematic retry = tryReadBase64(padded);
            if (retry != null) {
                applyMetadataTags(retry, metadata);
                return new PublishedPayload(retry, metadata, padded, rawText);
            }
        }
        return null;
    }

    static String normalizeShareContent(String rawText) {
        String trimmed = rawText == null ? "" : rawText.trim();
        Matcher wrapped = wrappedPayloadPattern.matcher(trimmed);
        if (wrapped.matches()) {
            return sanitizeBase64Candidate(wrapped.group(2));
        }

        String direct = sanitizeBase64Candidate(trimmed);
        if (isLikelySchematicBase64(direct)) return direct;

        if (trimmed.indexOf('%') >= 0) {
            try {
                String decoded = URLDecoder.decode(trimmed, "UTF-8");
                String decodedDirect = sanitizeBase64Candidate(decoded);
                if (isLikelySchematicBase64(decodedDirect)) return decodedDirect;
                String embeddedDecoded = extractEmbeddedBase64(decoded);
                if (isLikelySchematicBase64(embeddedDecoded)) return embeddedDecoded;
            } catch (Throwable ignored) {
            }
        }

        return extractEmbeddedBase64(trimmed);
    }

    static String buildRootUploadText(Schematic schematic, String author, UpdateSchemeDefs.ParsedRef authorIndexRef) {
        return buildPublishedText(schematic, author, authorIndexRef, true);
    }

    static String buildUpdateUploadText(Schematic schematic, String author, UpdateSchemeDefs.ParsedRef authorIndexRef) {
        return buildPublishedText(schematic, author, authorIndexRef, false);
    }

    private static String buildPublishedText(Schematic schematic, String author, UpdateSchemeDefs.ParsedRef authorIndexRef, boolean root) {
        String base64 = exportBase64ForPublish(schematic);
        if (base64.isEmpty()) return "";

        StringBuilder out = new StringBuilder(base64.length() + 256);
        out.append("[METADATA]\n");
        appendMetadataLine(out, "author", author);
        if (root) appendMetadataLine(out, "createTime", formatNow());
        else appendMetadataLine(out, "updateTime", formatNow());
        if (authorIndexRef != null) appendMetadataLine(out, "authorIndex", UpdateSchemeDefs.shareText(authorIndexRef));
        out.append("[SchemeCode]\n");
        out.append(base64);
        return out.toString();
    }

    private static void appendMetadataLine(StringBuilder out, String key, String value) {
        if (out == null || key == null || value == null) return;
        String clean = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", " ").trim();
        if (clean.isEmpty()) return;
        out.append(key).append("=\"").append(clean).append("\"\n");
    }

    static StringMap parseMetadata(String rawText) {
        StringMap out = new StringMap();
        if (rawText == null || rawText.isEmpty()) return out;

        Matcher wrapped = wrappedPayloadPattern.matcher(rawText.trim());
        if (!wrapped.matches()) return out;

        String section = wrapped.group(1);
        Matcher lineMatcher = metadataLinePattern.matcher(section == null ? "" : section);
        while (lineMatcher.find()) {
            String key = lineMatcher.group(1);
            String value = lineMatcher.group(2);
            if (key == null || value == null) continue;
            out.put(key, value.replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out;
    }

    static void applyMetadataTags(Schematic schematic, StringMap metadata) {
        if (schematic == null || metadata == null || metadata.isEmpty()) return;
        if (schematic.tags == null) schematic.tags = new StringMap();

        putIfPresent(schematic.tags, UpdateSchemeDefs.tagAuthor, metadata.get("author", ""));
        putIfPresent(schematic.tags, UpdateSchemeDefs.tagCreateTime, metadata.get("createTime", ""));
        putIfPresent(schematic.tags, UpdateSchemeDefs.tagUpdateTime, metadata.get("updateTime", ""));

        UpdateSchemeDefs.ParsedRef authorIndex = UpdateSchemeDefs.parseRefFromText(metadata.get("authorIndex", ""));
        if (authorIndex == null) authorIndex = UpdateSchemeDefs.parseRefFromUrl(metadata.get("authorIndex", ""));
        putIfPresent(schematic.tags, UpdateSchemeDefs.tagAuthorIndexRef, authorIndex == null ? "" : UpdateSchemeDefs.shareText(authorIndex));
    }

    private static void putIfPresent(StringMap tags, String key, String value) {
        if (tags == null || key == null) return;
        if (value == null || value.trim().isEmpty()) tags.remove(key);
        else tags.put(key, value.trim());
    }

    static String getStoredAuthorName() {
        return Core.settings.getString(keyAuthorName, "").trim();
    }

    static void setStoredAuthorName(String author) {
        Core.settings.put(keyAuthorName, author == null ? "" : author.trim());
    }

    static UpdateSchemeDefs.ParsedRef getStoredAuthorIndexRef() {
        String raw = Core.settings.getString(keyAuthorIndexRef, "").trim();
        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(raw);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(raw);
        return ref;
    }

    static void setStoredAuthorIndexRef(UpdateSchemeDefs.ParsedRef ref) {
        Core.settings.put(keyAuthorIndexRef, ref == null ? "" : UpdateSchemeDefs.shareText(ref));
    }

    static String getRegistryToken() {
        return Core.settings.getString(keyRegistryToken, "").trim();
    }

    static void setRegistryToken(String token) {
        Core.settings.put(keyRegistryToken, token == null ? "" : token.trim());
    }

    static String getRegistryRepo() {
        return Core.settings.getString(keyRegistryRepo, "").trim();
    }

    static void setRegistryRepo(String repo) {
        Core.settings.put(keyRegistryRepo, UpdateSchemeDefs.normalizeRepo(repo));
    }

    static String formatNow() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR) + "/"
            + (calendar.get(Calendar.MONTH) + 1) + "/"
            + calendar.get(Calendar.DAY_OF_MONTH) + "/"
            + pad2(calendar.get(Calendar.HOUR_OF_DAY)) + ":"
            + pad2(calendar.get(Calendar.MINUTE));
    }

    static String slugify(String text) {
        String raw = Strings.stripColors(text == null ? "" : text).trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) return "author";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9') out.append(ch);
            else if (ch == '-' || ch == '_' || ch == ' ') out.append('-');
        }
        String clean = out.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return clean.isEmpty() ? "author" : clean;
    }

    static String shortHash(String text) {
        String hex = sha256Hex(text == null ? UUID.randomUUID().toString() : text);
        return hex.length() > 10 ? hex.substring(0, 10) : hex;
    }

    static String randomId(String prefix) {
        String base = UUID.randomUUID().toString().replace("-", "");
        return (prefix == null || prefix.isEmpty() ? "" : prefix + "-") + base.substring(0, Math.min(base.length(), 12));
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String extractEmbeddedBase64(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = base64PayloadPattern.matcher(text);
        String best = "";
        while (m.find()) {
            String candidate = sanitizeBase64Candidate(m.group());
            if (isLikelySchematicBase64(candidate) && candidate.length() > best.length()) best = candidate;
        }
        if (!best.isEmpty()) return best;
        return extractFromLines(text);
    }

    private static boolean isLikelySchematicBase64(String text) {
        return text != null && text.length() >= 64 && text.startsWith("bXNja");
    }

    static String sanitizeBase64Candidate(String text) {
        if (text == null || text.isEmpty()) return "";

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '+' || c == '/' || c == '=') {
                out.append(c);
            } else if (c == '-') {
                out.append('+');
            } else if (c == '_') {
                out.append('/');
            } else if (c == ' ') {
                out.append('+');
            }
        }
        return out.toString();
    }

    private static String extractFromLines(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\\r?\\n");
        String best = "";
        for (int i = 0; i < lines.length; i++) {
            String candidate = sanitizeBase64Candidate(lines[i]);
            if (isLikelySchematicBase64(candidate) && candidate.length() > best.length()) best = candidate;
        }
        return best;
    }

    private static Schematic tryReadBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            return Schematics.readBase64(base64);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String padBase64(String text) {
        if (text == null || text.isEmpty()) return "";
        int mod = text.length() & 3;
        if (mod == 0) return text;
        StringBuilder out = new StringBuilder(text.length() + (4 - mod));
        out.append(text);
        for (int i = 0; i < 4 - mod; i++) out.append('=');
        return out.toString();
    }

    static String computeContentHash(Schematic schematic) {
        if (schematic == null || schematics == null) return "";

        Seq<Schematic.Stile> tiles = new Seq<>(schematic.tiles.size);
        for (int i = 0; i < schematic.tiles.size; i++) {
            Schematic.Stile t = schematic.tiles.get(i);
            tiles.add(t == null ? new Schematic.Stile() : t.copy());
        }

        Schematic tmp = new Schematic(tiles, new StringMap(), schematic.width, schematic.height);
        tmp.labels = new Seq<>();

        try {
            String base64 = schematics.writeBase64(tmp);
            return sha256Hex(base64);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String exportBase64ForPublish(Schematic schematic) {
        if (schematic == null || schematics == null) return "";

        Seq<Schematic.Stile> tiles = new Seq<>(schematic.tiles.size);
        for (int i = 0; i < schematic.tiles.size; i++) {
            Schematic.Stile t = schematic.tiles.get(i);
            tiles.add(t == null ? new Schematic.Stile() : t.copy());
        }

        StringMap tags = new StringMap();
        if (schematic.tags != null) {
            schematic.tags.entries().forEach(e -> {
                if (e.key == null) return;
                if (e.key.startsWith(UpdateSchemeDefs.tagPrefix)) return;
                if ("steamid".equals(e.key)) return;
                tags.put(e.key, e.value);
            });
        }

        Schematic tmp = new Schematic(tiles, tags, schematic.width, schematic.height);
        tmp.labels = schematic.labels == null ? new Seq<>() : new Seq<>(schematic.labels);

        try {
            return schematics.writeBase64(tmp);
        } catch (Throwable t) {
            Log.warn("UpdateScheme: export base64 failed", t);
            return "";
        }
    }

    static String sha256Hex(String text) {
        if (text == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static final class PublishedPayload {
        final Schematic schematic;
        final StringMap metadata;
        final String schemeCode;
        final String rawText;

        PublishedPayload(Schematic schematic, StringMap metadata, String schemeCode, String rawText) {
            this.schematic = schematic;
            this.metadata = metadata == null ? new StringMap() : metadata;
            this.schemeCode = schemeCode == null ? "" : schemeCode;
            this.rawText = rawText == null ? "" : rawText;
        }
    }
}
