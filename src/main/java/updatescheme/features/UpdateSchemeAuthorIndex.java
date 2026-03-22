package updatescheme.features;

import arc.func.Cons;
import arc.struct.Seq;
import mindustry.game.Schematic;

final class UpdateSchemeAuthorIndex {

    private UpdateSchemeAuthorIndex() {
    }

    static UpdateSchemeDefs.ParsedRef refOfSchematic(Schematic schematic) {
        if (schematic == null || schematic.tags == null) return null;
        String raw = schematic.tags.get(UpdateSchemeDefs.tagAuthorIndexRef, "");
        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(raw);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(raw);
        return ref;
    }

    static void ensureIndex(String author, Cons<UpdateSchemeDefs.ParsedRef> onReady, Cons<String> onError) {
        UpdateSchemeRegistry.ensureAuthorIndex(author, onReady, onError);
    }

    static void appendShare(UpdateSchemeDefs.ParsedRef indexRef, UpdateSchemeDefs.ParsedRef shareRef, String author, Cons<String> onDone, Cons<String> onError) {
        UpdateSchemeRegistry.appendAuthorIndex(indexRef, shareRef, author, onDone, onError);
    }

    static void fetchShares(UpdateSchemeDefs.ParsedRef indexRef, Cons<Seq<UpdateSchemeDefs.ParsedRef>> onDone, Cons<String> onError) {
        UpdateSchemeRegistry.fetchAuthorIndex(indexRef, record -> {
            Seq<UpdateSchemeDefs.ParsedRef> refs = new Seq<>();
            for (int i = 0; i < record.entries.size; i++) {
                String text = record.entries.get(i);
                UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(text);
                if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(text);
                if (ref != null) refs.add(ref);
            }
            if (onDone != null) onDone.get(refs);
        }, onError);
    }
}
