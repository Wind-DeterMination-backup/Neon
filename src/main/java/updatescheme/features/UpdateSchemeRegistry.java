package updatescheme.features;

import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import arc.util.serialization.Jval;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class UpdateSchemeRegistry {

    private static final String apiBase = "https://api.github.com";
    private static final String userAgent = "UpdateScheme/2.0.0";

    private UpdateSchemeRegistry() {
    }

    static void resolvePublisherRepo(Cons<RepoRef> onDone, Cons<String> onError) {
        new Thread(() -> {
            try {
                String token = UpdateSchemeUtil.getRegistryToken();
                if (token.isEmpty()) throw new RuntimeException("missing-token");

                String login = fetchLogin(token);
                if (login.isEmpty()) throw new RuntimeException("missing-login");

                String repoSpec = UpdateSchemeUtil.getRegistryRepo();
                if (repoSpec.isEmpty()) repoSpec = login + "/UpdateSchemeRegistry";
                repoSpec = UpdateSchemeDefs.normalizeRepo(repoSpec);
                if (repoSpec.isEmpty()) throw new RuntimeException("invalid-repo");

                RepoRef repo = ensureRepo(token, repoSpec);
                UpdateSchemeUtil.setRegistryRepo(repo.spec);
                if (onDone != null) onDone.get(repo);
            } catch (Throwable t) {
                Log.warn("UpdateScheme: resolve repo failed", t);
                if (onError != null) onError.get(errorName(t));
            }
        }, "UpdateScheme-registry-resolve").start();
    }

    static void createManifest(RepoRef repo, ManifestRecord record, Cons<UpdateSchemeDefs.ParsedRef> onDone, Cons<String> onError) {
        putJson(repo, UpdateSchemeDefs.Source.manifest.filePath(record.manifestId), record.toJson(), "Create manifest " + record.manifestId, null, sha -> {
            if (onDone != null) onDone.get(new UpdateSchemeDefs.ParsedRef(UpdateSchemeDefs.Source.manifest, repo.spec, record.manifestId));
        }, onError);
    }

    static void updateManifest(UpdateSchemeDefs.ParsedRef ref, ManifestRecord record, Cons<String> onDone, Cons<String> onError) {
        fetchFile(ref.repo, UpdateSchemeDefs.Source.manifest.filePath(ref.id), true, file -> {
            putJson(new RepoRef(ref.repo), UpdateSchemeDefs.Source.manifest.filePath(ref.id), record.toJson(), "Update manifest " + ref.id, file.sha, onDone, onError);
        }, onError);
    }

    static void fetchManifest(UpdateSchemeDefs.ParsedRef ref, Cons<ManifestRecord> onDone, Cons<String> onError) {
        fetchFile(ref.repo, UpdateSchemeDefs.Source.manifest.filePath(ref.id), false, file -> {
            try {
                ManifestRecord record = ManifestRecord.fromJson(file.content);
                if (record == null) throw new RuntimeException("manifest-invalid");
                if (record.manifestId.isEmpty()) record.manifestId = ref.id;
                if (record.repo.isEmpty()) record.repo = ref.repo;
                record.apiUrl = file.htmlUrl;
                if (onDone != null) onDone.get(record);
            } catch (Throwable t) {
                if (onError != null) onError.get(errorName(t));
            }
        }, onError);
    }

    static void ensureAuthorIndex(String author, Cons<UpdateSchemeDefs.ParsedRef> onDone, Cons<String> onError) {
        String cleanAuthor = Strings.stripColors(author == null ? "" : author).trim();
        if (cleanAuthor.isEmpty()) {
            if (onError != null) onError.get("missing-author");
            return;
        }

        UpdateSchemeDefs.ParsedRef stored = UpdateSchemeUtil.getStoredAuthorIndexRef();
        if (cleanAuthor.equals(UpdateSchemeUtil.getStoredAuthorName()) && stored != null) {
            if (onDone != null) onDone.get(stored);
            return;
        }

        resolvePublisherRepo(repo -> {
            AuthorIndexRecord record = new AuthorIndexRecord();
            record.author = cleanAuthor;
            record.indexId = "author-" + UpdateSchemeUtil.slugify(cleanAuthor) + "-" + UpdateSchemeUtil.shortHash(cleanAuthor + "-" + System.currentTimeMillis());
            record.repo = repo.spec;
            record.createdAt = UpdateSchemeUtil.formatNow();
            record.updatedAt = record.createdAt;
            putJson(repo, UpdateSchemeDefs.Source.authorIndex.filePath(record.indexId), record.toJson(), "Create author index " + record.indexId, null, sha -> {
                UpdateSchemeDefs.ParsedRef ref = new UpdateSchemeDefs.ParsedRef(UpdateSchemeDefs.Source.authorIndex, repo.spec, record.indexId);
                UpdateSchemeUtil.setStoredAuthorName(cleanAuthor);
                UpdateSchemeUtil.setStoredAuthorIndexRef(ref);
                if (onDone != null) onDone.get(ref);
            }, onError);
        }, onError);
    }

    static void fetchAuthorIndex(UpdateSchemeDefs.ParsedRef ref, Cons<AuthorIndexRecord> onDone, Cons<String> onError) {
        fetchFile(ref.repo, UpdateSchemeDefs.Source.authorIndex.filePath(ref.id), false, file -> {
            try {
                AuthorIndexRecord record = AuthorIndexRecord.fromJson(file.content);
                if (record == null) throw new RuntimeException("invalid-content");
                if (record.indexId.isEmpty()) record.indexId = ref.id;
                if (record.repo.isEmpty()) record.repo = ref.repo;
                if (onDone != null) onDone.get(record);
            } catch (Throwable t) {
                if (onError != null) onError.get(errorName(t));
            }
        }, onError);
    }

    static void appendAuthorIndex(UpdateSchemeDefs.ParsedRef indexRef, UpdateSchemeDefs.ParsedRef manifestRef, String author, Cons<String> onDone, Cons<String> onError) {
        fetchFile(indexRef.repo, UpdateSchemeDefs.Source.authorIndex.filePath(indexRef.id), true, file -> {
            try {
                AuthorIndexRecord record = AuthorIndexRecord.fromJson(file.content);
                if (record == null) record = new AuthorIndexRecord();
                record.author = Strings.stripColors(author == null ? record.author : author).trim();
                record.indexId = indexRef.id;
                record.repo = indexRef.repo;
                if (record.createdAt.isEmpty()) record.createdAt = UpdateSchemeUtil.formatNow();
                record.updatedAt = UpdateSchemeUtil.formatNow();
                String share = UpdateSchemeDefs.shareText(manifestRef);
                if (!record.entries.contains(share, false)) record.entries.add(share);
                putJson(new RepoRef(indexRef.repo), UpdateSchemeDefs.Source.authorIndex.filePath(indexRef.id), record.toJson(), "Update author index " + indexRef.id, file.sha, onDone, onError);
            } catch (Throwable t) {
                if (onError != null) onError.get(errorName(t));
            }
        }, onError);
    }

    static String viewUrl(UpdateSchemeDefs.ParsedRef ref) {
        if (ref == null || ref.source == null) return "";
        return "https://github.com/" + ref.repo + "/blob/main/" + ref.source.filePath(ref.id);
    }

    private static RepoRef ensureRepo(String token, String repoSpec) throws Exception {
        Response existing = request("GET", apiBase + "/repos/" + repoSpec, token, null);
        if (existing.code >= 200 && existing.code < 300) return new RepoRef(repoSpec);
        if (existing.code != 404) throw new RuntimeException("repo-http-" + existing.code);

        int slash = repoSpec.indexOf('/');
        String repoName = repoSpec.substring(slash + 1);
        Jval body = Jval.newObject();
        body.put("name", repoName);
        body.put("private", false);
        body.put("auto_init", true);
        body.put("description", "UpdateScheme public manifest registry");

        Response created = request("POST", apiBase + "/user/repos", token, body.toString(Jval.Jformat.plain));
        if (created.code < 200 || created.code >= 300) throw new RuntimeException("create-repo-http-" + created.code);
        return new RepoRef(repoSpec);
    }

    private static String fetchLogin(String token) throws Exception {
        Response response = request("GET", apiBase + "/user", token, null);
        if (response.code < 200 || response.code >= 300) throw new RuntimeException("user-http-" + response.code);
        Jval json = Jval.read(response.body == null ? "{}" : response.body);
        return Strings.stripColors(json.getString("login", "")).trim();
    }

    private static void fetchFile(String repo, String path, boolean auth, Cons<FileSnapshot> onDone, Cons<String> onError) {
        new Thread(() -> {
            try {
                String token = auth ? UpdateSchemeUtil.getRegistryToken() : "";
                Response response = request("GET", apiBase + "/repos/" + repo + "/contents/" + encodePath(path), token, null);
                if (response.code < 200 || response.code >= 300) throw new RuntimeException("file-http-" + response.code);

                Jval json = Jval.read(response.body == null ? "{}" : response.body);
                String content = decodeGithubContent(json.getString("content", ""));
                String sha = Strings.stripColors(json.getString("sha", "")).trim();
                String htmlUrl = Strings.stripColors(json.getString("html_url", "")).trim();
                if (onDone != null) onDone.get(new FileSnapshot(path, content, sha, htmlUrl));
            } catch (Throwable t) {
                if (onError != null) onError.get(errorName(t));
            }
        }, "UpdateScheme-registry-fetch").start();
    }

    private static void putJson(RepoRef repo, String path, Jval json, String message, String sha, Cons<String> onDone, Cons<String> onError) {
        new Thread(() -> {
            try {
                String token = UpdateSchemeUtil.getRegistryToken();
                if (token.isEmpty()) throw new RuntimeException("missing-token");

                Jval body = Jval.newObject();
                body.put("message", message);
                body.put("content", Base64.getEncoder().encodeToString(json.toString(Jval.Jformat.formatted).getBytes(StandardCharsets.UTF_8)));
                if (sha != null && !sha.trim().isEmpty()) body.put("sha", sha.trim());

                Response response = request("PUT", apiBase + "/repos/" + repo.spec + "/contents/" + encodePath(path), token, body.toString(Jval.Jformat.plain));
                if (response.code < 200 || response.code >= 300) throw new RuntimeException("put-http-" + response.code);

                Jval res = Jval.read(response.body == null ? "{}" : response.body);
                Jval content = res.get("content");
                String nextSha = content == null ? "" : Strings.stripColors(content.getString("sha", "")).trim();
                if (onDone != null) onDone.get(nextSha);
            } catch (Throwable t) {
                if (onError != null) onError.get(errorName(t));
            }
        }, "UpdateScheme-registry-put").start();
    }

    private static Response request(String method, String url, String token, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(25_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        if (token != null && !token.trim().isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token.trim());

        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = conn.getResponseCode();
        String text = readBody(code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream());
        return new Response(code, text);
    }

    private static String encodePath(String path) throws Exception {
        return URLEncoder.encode(path, "UTF-8").replace("+", "%20").replace("%2F", "/");
    }

    private static String decodeGithubContent(String content) {
        String clean = content == null ? "" : content.replace("\n", "").replace("\r", "").trim();
        if (clean.isEmpty()) return "";
        byte[] bytes = Base64.getDecoder().decode(clean);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readBody(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = stream.read(buffer)) >= 0) out.write(buffer, 0, len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String errorName(Throwable error) {
        if (error == null) return "error";
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message == null ? "error" : message.trim();
    }

    static final class RepoRef {
        final String spec;

        RepoRef(String spec) {
            this.spec = UpdateSchemeDefs.normalizeRepo(spec);
        }
    }

    static final class FileSnapshot {
        final String path;
        final String content;
        final String sha;
        final String htmlUrl;

        FileSnapshot(String path, String content, String sha, String htmlUrl) {
            this.path = path == null ? "" : path;
            this.content = content == null ? "" : content;
            this.sha = sha == null ? "" : sha;
            this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
        }
    }

    static final class ManifestRecord {
        String manifestId = "";
        String repo = "";
        String title = "";
        String author = "";
        String authorIndex = "";
        String createdAt = "";
        String updatedAt = "";
        String latestBlobUrl = "";
        String latestHash = "";
        String latestName = "";
        int revision = 1;
        String apiUrl = "";

        Jval toJson() {
            Jval root = Jval.newObject();
            root.put("protocol", "updatescheme-v2");
            root.put("kind", "manifest");
            root.put("manifestId", manifestId);
            root.put("title", title);
            root.put("author", author);
            root.put("authorIndex", authorIndex);
            root.put("createdAt", createdAt);
            root.put("updatedAt", updatedAt);
            root.put("latestBlobUrl", latestBlobUrl);
            root.put("latestHash", latestHash);
            root.put("latestName", latestName);
            root.put("revision", revision);
            return root;
        }

        static ManifestRecord fromJson(String text) {
            Jval json = Jval.read(text == null ? "{}" : text);
            ManifestRecord out = new ManifestRecord();
            out.manifestId = Strings.stripColors(json.getString("manifestId", "")).trim();
            out.title = Strings.stripColors(json.getString("title", "")).trim();
            out.author = Strings.stripColors(json.getString("author", "")).trim();
            out.authorIndex = Strings.stripColors(json.getString("authorIndex", "")).trim();
            out.createdAt = Strings.stripColors(json.getString("createdAt", "")).trim();
            out.updatedAt = Strings.stripColors(json.getString("updatedAt", "")).trim();
            out.latestBlobUrl = Strings.stripColors(json.getString("latestBlobUrl", "")).trim();
            out.latestHash = Strings.stripColors(json.getString("latestHash", "")).trim();
            out.latestName = Strings.stripColors(json.getString("latestName", "")).trim();
            out.revision = json.getInt("revision", 1);
            return out;
        }
    }

    static final class AuthorIndexRecord {
        String indexId = "";
        String repo = "";
        String author = "";
        String createdAt = "";
        String updatedAt = "";
        final Seq<String> entries = new Seq<>();

        Jval toJson() {
            Jval root = Jval.newObject();
            root.put("protocol", "updatescheme-v2");
            root.put("kind", "author-index");
            root.put("indexId", indexId);
            root.put("author", author);
            root.put("createdAt", createdAt);
            root.put("updatedAt", updatedAt);
            Jval arr = Jval.newArray();
            for (int i = 0; i < entries.size; i++) {
                arr.add(entries.get(i));
            }
            root.put("entries", arr);
            return root;
        }

        static AuthorIndexRecord fromJson(String text) {
            Jval json = Jval.read(text == null ? "{}" : text);
            AuthorIndexRecord out = new AuthorIndexRecord();
            out.indexId = Strings.stripColors(json.getString("indexId", "")).trim();
            out.author = Strings.stripColors(json.getString("author", "")).trim();
            out.createdAt = Strings.stripColors(json.getString("createdAt", "")).trim();
            out.updatedAt = Strings.stripColors(json.getString("updatedAt", "")).trim();
            Jval entries = json.get("entries");
            if (entries != null && entries.isArray()) {
                for (Jval item : entries.asArray()) {
                    String value = Strings.stripColors(item == null ? "" : item.asString()).trim();
                    if (!value.isEmpty()) out.entries.add(value);
                }
            }
            return out;
        }
    }

    private static final class Response {
        final int code;
        final String body;

        private Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
