package updatescheme.features;

import arc.Core;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.game.Schematic;

import static mindustry.Vars.schematics;

final class UpdateSchemeFetcher {

    private static final ObjectMap<String, PendingUpdate> pendingUpdates = new ObjectMap<>();
    private static final Seq<FetchJob> jobQueue = new Seq<>();
    private static final ObjectSet<String> queuedKeys = new ObjectSet<>();
    private static boolean jobRunning;

    private UpdateSchemeFetcher() {
    }

    static PendingUpdate pendingOf(Schematic schematic) {
        String key = subscriptionKeyOf(schematic);
        return key == null ? null : pendingUpdates.get(key);
    }

    static void tickQueue() {
        if (jobRunning || jobQueue.isEmpty()) return;
        FetchJob job = jobQueue.remove(0);
        jobRunning = true;
        fetchRemote(job.ref, remote -> {
            try {
                if (job.onSuccess != null) job.onSuccess.get(remote);
            } finally {
                finishJob(job);
            }
        }, err -> {
            try {
                if (job.onError != null) job.onError.get(err);
            } finally {
                finishJob(job);
            }
        });
    }

    static void enqueueAllChecks(boolean force) {
        if (!UpdateSchemeFeature.enabled || schematics == null) return;

        ObjectSet<String> seen = new ObjectSet<>();
        Seq<Schematic> all = schematics.all();
        for (int i = 0; i < all.size; i++) {
            Schematic s = all.get(i);
            UpdateSchemeDefs.ParsedRef ref = refOfSchematic(s);
            if (ref == null || ref.source != UpdateSchemeDefs.Source.manifest) continue;
            String key = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
            if (key == null || seen.contains(key)) continue;
            seen.add(key);
            if (force || shouldCheckNow(s)) enqueueCheck(ref, false);
        }
    }

    static void enqueueCheck(UpdateSchemeDefs.ParsedRef ref, boolean toastResult) {
        if (!UpdateSchemeFeature.enabled || ref == null || ref.source != UpdateSchemeDefs.Source.manifest) return;
        String key = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
        if (key == null) return;

        enqueueJob(new FetchJob("check:" + key, ref, remote -> {
            RemoteResult result = handleCheckResult(ref, remote);
            if (!toastResult) return;
            if (!result.ok) toast(Core.bundle.format("us.toast.failed", result.displayName));
            else if (result.updateAvailable) toast(Core.bundle.format("us.toast.update", result.displayName));
            else toast(Core.bundle.format("us.toast.latest", result.displayName));
        }, err -> {
            handleCheckFailure(ref, err);
            if (toastResult) toast(Core.bundle.format("us.toast.failed.detail", key, prettyError(err)));
        }));
    }

    static void subscribeAndImport(UpdateSchemeDefs.ParsedRef ref, boolean silent) {
        if (!UpdateSchemeFeature.enabled || schematics == null || ref == null || ref.source != UpdateSchemeDefs.Source.manifest) return;
        String key = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
        if (key == null) return;

        if (isAlreadySubscribed(ref)) {
            if (!silent) toast(Core.bundle.format("us.toast.already", ref.source.shortCode, ref.id));
            return;
        }

        enqueueJob(new FetchJob("sub:" + key, ref, remote -> {
            if (remote == null || remote.schematic == null) {
                if (!silent) toast(Core.bundle.format("us.toast.failed.detail", key, prettyError("invalid-content")));
                return;
            }
            importSchematic(ref, remote.schematic);
            if (!silent) toast(Core.bundle.format("us.toast.subscribed", ref.source.shortCode, ref.id));
        }, err -> {
            if (!silent) toast(Core.bundle.format("us.toast.failed.detail", key, prettyError(err)));
        }));
    }

    static void subscribeAndImportAuto(String raw, UpdateSchemeDefs.Source preferred, boolean silent) {
        if (!UpdateSchemeFeature.enabled || schematics == null) return;
        String input = Strings.stripColors(raw == null ? "" : raw).trim();
        if (input.isEmpty()) return;

        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(input);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(input);
        if (ref == null && preferred == UpdateSchemeDefs.Source.manifest) {
            if (!silent) toast(Core.bundle.format("us.toast.failed.detail", input, prettyError("invalid-id")));
            return;
        }

        subscribeAndImport(ref, silent);
    }

    static void debugResolve(String raw, UpdateSchemeDefs.Source preferred, Cons<String> onDone) {
        String input = Strings.stripColors(raw == null ? "" : raw).trim();
        if (input.isEmpty()) {
            if (onDone != null) onDone.get("Input is empty.");
            return;
        }

        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(input);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(input);
        if (ref == null) {
            if (onDone != null) onDone.get("Cannot parse manifest ref.");
            return;
        }

        final UpdateSchemeDefs.ParsedRef resolvedRef = ref;
        fetchRemote(resolvedRef, remote -> {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("resolved = ").append(UpdateSchemeDefs.shareText(resolvedRef)).append('\n');
            sb.append("manifest.url = ").append(remote == null ? "" : remote.manifestUrl).append('\n');
            sb.append("blob.url = ").append(remote == null ? "" : remote.blobUrl).append('\n');
            sb.append("remote.hash = ").append(remote == null ? "" : remote.remoteHash).append('\n');

            if (remote != null && remote.schematic != null) {
                sb.append("schematic = ok").append('\n');
                sb.append("schematic.name = ").append(Strings.stripColors(remote.schematic.name())).append('\n');
                sb.append("schematic.size = ").append(remote.schematic.width).append('x').append(remote.schematic.height).append('\n');
            } else {
                sb.append("schematic = none").append('\n');
            }
            if (onDone != null) onDone.get(sb.toString());
        }, err -> {
            if (onDone != null) onDone.get("failed = " + prettyError(err));
        });
    }

    static void applyUpdate(Schematic target) {
        UpdateSchemeDefs.ParsedRef ref = refOfSchematic(target);
        if (ref == null) return;
        String key = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
        if (key == null) return;

        PendingUpdate pending = pendingUpdates.get(key);
        if (pending == null || pending.remote == null) {
            enqueueCheck(ref, true);
            return;
        }

        String localHash = ensureLocalHash(target);
        if (!pending.remoteHash.isEmpty() && pending.remoteHash.equals(localHash)) {
            pendingUpdates.remove(key);
            toast(Core.bundle.format("us.toast.latest", Strings.stripColors(target.name())));
            return;
        }

        try {
            bindRefTags(pending.remote, ref);
            setTag(pending.remote, UpdateSchemeDefs.tagHash, pending.remoteHash);
            setTag(pending.remote, UpdateSchemeDefs.tagRemoteHash, pending.remoteHash);
            setTag(pending.remote, UpdateSchemeDefs.tagLastUpdateMs, Long.toString(System.currentTimeMillis()));
            setTag(pending.remote, UpdateSchemeDefs.tagLastError, "");
            schematics.overwrite(target, pending.remote);
            pendingUpdates.remove(key);
            toast(Core.bundle.format("us.toast.latest", Strings.stripColors(target.name())));
        } catch (Throwable t) {
            setTag(target, UpdateSchemeDefs.tagLastError, t.getClass().getSimpleName());
            tryPersistTags(target);
            toast(Core.bundle.format("us.toast.failed", Strings.stripColors(target.name())));
            if (mindustry.Vars.ui != null) mindustry.Vars.ui.showException(t);
        }
    }

    static void bindSchematic(Schematic target, UpdateSchemeDefs.ParsedRef ref) {
        if (ref == null) return;
        bindSchematic(target, ref.source, ref.repo, ref.id, ref.secret);
    }

    static void bindSchematic(Schematic target, UpdateSchemeDefs.Source source, String repo, String id, String secret) {
        if (target == null || source == null) return;
        String cleanRepo = UpdateSchemeDefs.normalizeRepo(repo);
        String cleanId = id == null ? "" : id.trim();
        if (cleanRepo.isEmpty() || cleanId.isEmpty()) return;
        if (!canPersist(target)) {
            toast(Core.bundle.format("us.toast.failed", "read-only"));
            return;
        }

        bindRefTags(target, new UpdateSchemeDefs.ParsedRef(source, cleanRepo, cleanId, secret));
        String hash = UpdateSchemeUtil.computeContentHash(target);
        if (!hash.isEmpty()) setTag(target, UpdateSchemeDefs.tagHash, hash);
        setTag(target, UpdateSchemeDefs.tagLastError, "");
        tryPersistTags(target);
    }

    static void unbindSchematic(Schematic target) {
        if (target == null || !canPersist(target)) return;
        if (target.tags != null) {
            target.tags.remove(UpdateSchemeDefs.tagRef);
            target.tags.remove(UpdateSchemeDefs.tagSource);
            target.tags.remove(UpdateSchemeDefs.tagRepo);
            target.tags.remove(UpdateSchemeDefs.tagId);
            target.tags.remove(UpdateSchemeDefs.tagKey);
            target.tags.remove(UpdateSchemeDefs.tagHash);
            target.tags.remove(UpdateSchemeDefs.tagRemoteHash);
            target.tags.remove(UpdateSchemeDefs.tagLastCheckMs);
            target.tags.remove(UpdateSchemeDefs.tagLastUpdateMs);
            target.tags.remove(UpdateSchemeDefs.tagLastError);
        }
        tryPersistTags(target);
    }

    static Seq<Schematic> listManagedSchematics() {
        Seq<Schematic> out = new Seq<>();
        if (schematics == null) return out;
        Seq<Schematic> all = schematics.all();
        for (int i = 0; i < all.size; i++) {
            if (refOfSchematic(all.get(i)) != null) out.add(all.get(i));
        }
        return out;
    }

    static UpdateSchemeDefs.ParsedRef refOfSchematic(Schematic schematic) {
        if (schematic == null || schematic.tags == null) return null;
        String raw = schematic.tags.get(UpdateSchemeDefs.tagRef, "");
        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(raw);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(raw);
        if (ref != null) return ref;

        String sourceName = schematic.tags.get(UpdateSchemeDefs.tagSource, "");
        String repo = schematic.tags.get(UpdateSchemeDefs.tagRepo, "");
        String id = schematic.tags.get(UpdateSchemeDefs.tagId, "");
        if (sourceName.isEmpty() || repo.isEmpty() || id.isEmpty()) return null;
        UpdateSchemeDefs.Source source = UpdateSchemeDefs.Source.fromInternal(sourceName);
        if (source == null) return null;
        return new UpdateSchemeDefs.ParsedRef(source, repo, id, schematic.tags.get(UpdateSchemeDefs.tagKey, ""));
    }

    static Seq<Schematic> localsByKey(UpdateSchemeDefs.Source source, String repo, String id) {
        Seq<Schematic> out = new Seq<>();
        if (schematics == null) return out;
        String key = UpdateSchemeDefs.keyOf(source, repo, id);
        if (key == null) return out;
        Seq<Schematic> all = schematics.all();
        for (int i = 0; i < all.size; i++) {
            UpdateSchemeDefs.ParsedRef ref = refOfSchematic(all.get(i));
            if (ref == null) continue;
            String current = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
            if (key.equals(current)) out.add(all.get(i));
        }
        return out;
    }

    static Seq<UpdateSchemeDefs.ParsedRef> knownRefsForSameContent(Schematic target) {
        Seq<UpdateSchemeDefs.ParsedRef> out = new Seq<>();
        if (target == null || schematics == null) return out;
        String hash = UpdateSchemeUtil.computeContentHash(target);
        if (hash.isEmpty()) return out;
        Seq<Schematic> all = schematics.all();
        for (int i = 0; i < all.size; i++) {
            Schematic schematic = all.get(i);
            if (schematic == target) continue;
            if (!hash.equals(ensureLocalHash(schematic))) continue;
            UpdateSchemeDefs.ParsedRef ref = refOfSchematic(schematic);
            if (ref != null) out.add(ref);
        }
        return out;
    }

    static String safeTag(Schematic schematic, String key) {
        if (schematic == null || schematic.tags == null || key == null) return "";
        return Strings.stripColors(schematic.tags.get(key, "")).trim();
    }

    static void fetchRemote(UpdateSchemeDefs.ParsedRef ref, Cons<UpdateSchemeDefs.RemoteSchematic> onDone, Cons<String> onError) {
        if (ref == null || ref.source != UpdateSchemeDefs.Source.manifest) {
            if (onError != null) onError.get("invalid-id");
            return;
        }

        UpdateSchemeRegistry.fetchManifest(ref, manifest -> {
            if (manifest.latestBlobUrl.isEmpty()) {
                if (onError != null) onError.get("manifest-invalid");
                return;
            }
            UpdateSchemeBlobStore.downloadText(manifest.latestBlobUrl, text -> {
                UpdateSchemeUtil.PublishedPayload payload = UpdateSchemeUtil.parsePublishedPayload(text);
                if (payload == null || payload.schematic == null) {
                    if (onError != null) onError.get("invalid-content");
                    return;
                }
                String hash = manifest.latestHash.isEmpty() ? UpdateSchemeUtil.computeContentHash(payload.schematic) : manifest.latestHash;
                if (onDone != null) onDone.get(new UpdateSchemeDefs.RemoteSchematic(manifest.apiUrl, manifest.latestBlobUrl, payload.schematic, text, hash));
            }, onError);
        }, onError);
    }

    private static void enqueueJob(FetchJob job) {
        if (job == null || job.queueKey == null || job.queueKey.isEmpty()) return;
        if (queuedKeys.contains(job.queueKey)) return;
        queuedKeys.add(job.queueKey);
        jobQueue.add(job);
    }

    private static void finishJob(FetchJob job) {
        if (job != null && job.queueKey != null) queuedKeys.remove(job.queueKey);
        jobRunning = false;
    }

    private static boolean isAlreadySubscribed(UpdateSchemeDefs.ParsedRef ref) {
        return !localsByKey(ref.source, ref.repo, ref.id).isEmpty();
    }

    private static void importSchematic(UpdateSchemeDefs.ParsedRef ref, Schematic remote) {
        if (remote == null || schematics == null) return;
        bindRefTags(remote, ref);
        String hash = UpdateSchemeUtil.computeContentHash(remote);
        if (!hash.isEmpty()) {
            setTag(remote, UpdateSchemeDefs.tagHash, hash);
            setTag(remote, UpdateSchemeDefs.tagRemoteHash, hash);
        }
        setTag(remote, UpdateSchemeDefs.tagLastCheckMs, Long.toString(System.currentTimeMillis()));
        setTag(remote, UpdateSchemeDefs.tagLastUpdateMs, Long.toString(System.currentTimeMillis()));
        schematics.add(remote);
    }

    private static RemoteResult handleCheckResult(UpdateSchemeDefs.ParsedRef ref, UpdateSchemeDefs.RemoteSchematic remote) {
        String display = ref.repo + ":" + ref.id;
        RemoteResult result = new RemoteResult(display);
        if (remote == null || remote.schematic == null) return result;

        String remoteHash = remote.remoteHash.isEmpty() ? UpdateSchemeUtil.computeContentHash(remote.schematic) : remote.remoteHash;
        String key = UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
        Seq<Schematic> locals = localsByKey(ref.source, ref.repo, ref.id);
        if (locals.isEmpty()) {
            pendingUpdates.remove(key);
            result.ok = true;
            return result;
        }

        boolean updateAvailable = false;
        for (int i = 0; i < locals.size; i++) {
            Schematic local = locals.get(i);
            String localHash = ensureLocalHash(local);
            setTag(local, UpdateSchemeDefs.tagRemoteHash, remoteHash);
            setTag(local, UpdateSchemeDefs.tagLastCheckMs, Long.toString(System.currentTimeMillis()));
            setTag(local, UpdateSchemeDefs.tagLastError, "");
            tryPersistTags(local);
            if (!remoteHash.equals(localHash)) updateAvailable = true;
        }

        if (updateAvailable) pendingUpdates.put(key, new PendingUpdate(remoteHash, remote.schematic));
        else pendingUpdates.remove(key);

        result.ok = true;
        result.updateAvailable = updateAvailable;
        return result;
    }

    private static void handleCheckFailure(UpdateSchemeDefs.ParsedRef ref, String err) {
        Seq<Schematic> locals = localsByKey(ref.source, ref.repo, ref.id);
        for (int i = 0; i < locals.size; i++) {
            setTag(locals.get(i), UpdateSchemeDefs.tagLastError, err);
            setTag(locals.get(i), UpdateSchemeDefs.tagLastCheckMs, Long.toString(System.currentTimeMillis()));
            tryPersistTags(locals.get(i));
        }
    }

    private static void bindRefTags(Schematic schematic, UpdateSchemeDefs.ParsedRef ref) {
        if (schematic == null || ref == null) return;
        if (schematic.tags == null) schematic.tags = new arc.struct.StringMap();
        setTag(schematic, UpdateSchemeDefs.tagRef, UpdateSchemeDefs.shareText(ref));
        setTag(schematic, UpdateSchemeDefs.tagSource, ref.source.internalName);
        setTag(schematic, UpdateSchemeDefs.tagRepo, ref.repo);
        setTag(schematic, UpdateSchemeDefs.tagId, ref.id);
        setTag(schematic, UpdateSchemeDefs.tagKey, ref.secret);
    }

    private static String subscriptionKeyOf(Schematic schematic) {
        UpdateSchemeDefs.ParsedRef ref = refOfSchematic(schematic);
        return ref == null ? null : UpdateSchemeDefs.keyOf(ref.source, ref.repo, ref.id);
    }

    private static boolean shouldCheckNow(Schematic schematic) {
        long last = safeLongTag(schematic, UpdateSchemeDefs.tagLastCheckMs);
        if (last <= 0L) return true;
        long now = System.currentTimeMillis();
        return now - last >= UpdateSchemeFeature.intervalMin * 60_000L;
    }

    private static String ensureLocalHash(Schematic schematic) {
        String hash = safeTag(schematic, UpdateSchemeDefs.tagHash);
        if (!hash.isEmpty()) return hash;
        hash = UpdateSchemeUtil.computeContentHash(schematic);
        if (!hash.isEmpty()) {
            setTag(schematic, UpdateSchemeDefs.tagHash, hash);
            tryPersistTags(schematic);
        }
        return hash;
    }

    private static long safeLongTag(Schematic schematic, String key) {
        String value = safeTag(schematic, key);
        if (value.isEmpty()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static boolean canPersist(Schematic schematic) {
        return schematic != null && schematics != null;
    }

    private static void tryPersistTags(Schematic schematic) {
        if (!canPersist(schematic)) return;
    }

    private static void setTag(Schematic schematic, String key, String value) {
        if (schematic == null || key == null) return;
        if (schematic.tags == null) schematic.tags = new arc.struct.StringMap();
        if (value == null || value.trim().isEmpty()) schematic.tags.remove(key);
        else schematic.tags.put(key, value.trim());
    }

    static void toast(String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (mindustry.Vars.ui != null) mindustry.Vars.ui.showInfoFade(message.trim());
    }

    static String prettyError(String code) {
        if (code == null || code.isEmpty()) return "unknown";
        if (Core.bundle != null && Core.bundle.has("us.error." + code)) return Core.bundle.get("us.error." + code);
        return code;
    }

    static final class PendingUpdate {
        final String remoteHash;
        final Schematic remote;

        PendingUpdate(String remoteHash, Schematic remote) {
            this.remoteHash = remoteHash == null ? "" : remoteHash;
            this.remote = remote;
        }
    }

    private static final class FetchJob {
        final String queueKey;
        final UpdateSchemeDefs.ParsedRef ref;
        final Cons<UpdateSchemeDefs.RemoteSchematic> onSuccess;
        final Cons<String> onError;

        FetchJob(String queueKey, UpdateSchemeDefs.ParsedRef ref, Cons<UpdateSchemeDefs.RemoteSchematic> onSuccess, Cons<String> onError) {
            this.queueKey = queueKey;
            this.ref = ref;
            this.onSuccess = onSuccess;
            this.onError = onError;
        }
    }

    private static final class RemoteResult {
        final String displayName;
        boolean ok;
        boolean updateAvailable;

        RemoteResult(String displayName) {
            this.displayName = displayName == null ? "" : displayName;
        }
    }
}
