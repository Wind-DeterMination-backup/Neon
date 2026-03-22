package updatescheme.features;

import arc.util.Strings;
import mindustry.game.Schematic;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class UpdateSchemeDefs {

    static final String tagPrefix = "updatescheme.";
    static final String tagRef = tagPrefix + "ref";
    static final String tagSource = tagPrefix + "source";
    static final String tagRepo = tagPrefix + "repo";
    static final String tagId = tagPrefix + "id";
    static final String tagKey = tagPrefix + "key";
    static final String tagHash = tagPrefix + "hash";
    static final String tagRemoteHash = tagPrefix + "remoteHash";
    static final String tagLastCheckMs = tagPrefix + "lastCheckMs";
    static final String tagLastUpdateMs = tagPrefix + "lastUpdateMs";
    static final String tagLastError = tagPrefix + "lastError";
    static final String tagAuthor = tagPrefix + "author";
    static final String tagCreateTime = tagPrefix + "createTime";
    static final String tagUpdateTime = tagPrefix + "updateTime";
    static final String tagAuthorIndexRef = tagPrefix + "authorIndexRef";

    static final Pattern sharePattern = Pattern.compile("(?i)updatescheme\\s*[:：]\\s*(v2|ai)\\s*[:：]\\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\\s*[:：]\\s*([A-Za-z0-9_-]+)");
    static final Pattern shortSharePattern = Pattern.compile("(?i)\\b(v2|ai)\\s*[:：]\\s*([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\\s*[:：]\\s*([A-Za-z0-9_-]+)\\b");
    static final Pattern apiManifestPattern = Pattern.compile("(?i)https?://api\\.github\\.com/repos/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/contents/manifests/([A-Za-z0-9_-]+)\\.json(?:\\?.*)?$");
    static final Pattern apiAuthorPattern = Pattern.compile("(?i)https?://api\\.github\\.com/repos/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/contents/authors/([A-Za-z0-9_-]+)\\.json(?:\\?.*)?$");
    static final Pattern blobManifestPattern = Pattern.compile("(?i)https?://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/blob/[^/]+/manifests/([A-Za-z0-9_-]+)\\.json(?:\\?.*)?$");
    static final Pattern blobAuthorPattern = Pattern.compile("(?i)https?://github\\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/blob/[^/]+/authors/([A-Za-z0-9_-]+)\\.json(?:\\?.*)?$");

    private UpdateSchemeDefs() {
    }

    static String keyOf(Source source, String repo, String id) {
        if (source == null || repo == null || id == null) return null;
        String cleanRepo = normalizeRepo(repo);
        String cleanId = id.trim();
        if (cleanRepo.isEmpty() || cleanId.isEmpty()) return null;
        return source.shortCode + ":" + cleanRepo + ":" + cleanId;
    }

    static ParsedRef parseRefFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String plain = Strings.stripColors(text).replace('\u00A0', ' ').trim();
        if (plain.isEmpty()) return null;

        Matcher full = sharePattern.matcher(plain);
        if (full.find()) {
            Source source = Source.fromShortCode(full.group(1));
            String repo = normalizeRepo(full.group(2));
            String id = full.group(3);
            if (source != null && !repo.isEmpty() && id != null && !id.isEmpty()) {
                return new ParsedRef(source, repo, id);
            }
        }

        Matcher shortRef = shortSharePattern.matcher(plain);
        if (shortRef.find()) {
            Source source = Source.fromShortCode(shortRef.group(1));
            String repo = normalizeRepo(shortRef.group(2));
            String id = shortRef.group(3);
            if (source != null && !repo.isEmpty() && id != null && !id.isEmpty()) {
                return new ParsedRef(source, repo, id);
            }
        }

        return null;
    }

    static ParsedRef parseRefFromUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        String plain = Strings.stripColors(text).trim();
        if (plain.isEmpty()) return null;

        Matcher apiManifest = apiManifestPattern.matcher(plain);
        if (apiManifest.find()) return new ParsedRef(Source.manifest, normalizeRepo(apiManifest.group(1)), apiManifest.group(2));

        Matcher apiAuthor = apiAuthorPattern.matcher(plain);
        if (apiAuthor.find()) return new ParsedRef(Source.authorIndex, normalizeRepo(apiAuthor.group(1)), apiAuthor.group(2));

        Matcher blobManifest = blobManifestPattern.matcher(plain);
        if (blobManifest.find()) return new ParsedRef(Source.manifest, normalizeRepo(blobManifest.group(1)), blobManifest.group(2));

        Matcher blobAuthor = blobAuthorPattern.matcher(plain);
        if (blobAuthor.find()) return new ParsedRef(Source.authorIndex, normalizeRepo(blobAuthor.group(1)), blobAuthor.group(2));

        return null;
    }

    static String shareText(Source source, String repo, String id) {
        return shareText(new ParsedRef(source, repo, id));
    }

    static String shareText(ParsedRef ref) {
        if (ref == null || ref.source == null) return "";
        String cleanRepo = normalizeRepo(ref.repo);
        String cleanId = ref.id == null ? "" : ref.id.trim();
        if (cleanRepo.isEmpty() || cleanId.isEmpty()) return "";
        return "UpdateScheme:" + ref.source.shortCode + ":" + cleanRepo + ":" + cleanId;
    }

    static String normalizeRepo(String repo) {
        if (repo == null) return "";
        String clean = repo.trim().replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int slash = clean.indexOf('/');
        if (slash <= 0 || slash >= clean.length() - 1) return "";
        String owner = clean.substring(0, slash).trim();
        String name = clean.substring(slash + 1).trim();
        if (owner.isEmpty() || name.isEmpty()) return "";
        return owner + "/" + name;
    }

    enum Source {
        manifest("v2", "manifest", "manifests"),
        authorIndex("ai", "authorIndex", "authors");

        final String shortCode;
        final String internalName;
        final String directory;

        Source(String shortCode, String internalName, String directory) {
            this.shortCode = shortCode;
            this.internalName = internalName;
            this.directory = directory;
        }

        static Source fromShortCode(String code) {
            if (code == null) return null;
            String clean = code.trim().toLowerCase(Locale.ROOT);
            for (Source source : values()) {
                if (source.shortCode.equals(clean)) return source;
            }
            return null;
        }

        static Source fromInternal(String name) {
            if (name == null) return null;
            String clean = name.trim().toLowerCase(Locale.ROOT);
            for (Source source : values()) {
                if (source.internalName.toLowerCase(Locale.ROOT).equals(clean) || source.shortCode.equals(clean)) return source;
            }
            return null;
        }

        String filePath(String id) {
            return directory + "/" + id + ".json";
        }
    }

    static final class ParsedRef {
        final Source source;
        final String repo;
        final String id;
        final String secret;

        ParsedRef(Source source, String repo, String id) {
            this(source, repo, id, "");
        }

        ParsedRef(Source source, String repo, String id, String secret) {
            this.source = source;
            this.repo = normalizeRepo(repo);
            this.id = id == null ? "" : id.trim();
            this.secret = secret == null ? "" : secret.trim();
        }
    }

    static final class RemoteSchematic {
        final String manifestUrl;
        final String blobUrl;
        final Schematic schematic;
        final String rawText;
        final String remoteHash;

        RemoteSchematic(String manifestUrl, String blobUrl, Schematic schematic, String rawText, String remoteHash) {
            this.manifestUrl = manifestUrl == null ? "" : manifestUrl;
            this.blobUrl = blobUrl == null ? "" : blobUrl;
            this.schematic = schematic;
            this.rawText = rawText == null ? "" : rawText;
            this.remoteHash = remoteHash == null ? "" : remoteHash;
        }
    }
}
