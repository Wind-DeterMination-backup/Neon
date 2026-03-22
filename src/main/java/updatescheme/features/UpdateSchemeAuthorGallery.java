package updatescheme.features;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog;

final class UpdateSchemeAuthorGallery {

    private UpdateSchemeAuthorGallery() {
    }

    static void showFor(Schematic schematic) {
        if (Vars.headless || Vars.ui == null || schematic == null) return;

        String author = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagAuthor);
        UpdateSchemeDefs.ParsedRef indexRef = UpdateSchemeAuthorIndex.refOfSchematic(schematic);
        if (author.isEmpty() || indexRef == null) {
            UpdateSchemeUI.toast("@us.author.gallery.unavailable");
            return;
        }

        BaseDialog dialog = new BaseDialog(Core.bundle.format("us.author.gallery.title", author));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        Label status = dialog.cont.add("@loading").left().color(Color.lightGray).get();
        dialog.cont.row();

        Table list = new Table();
        list.top().left().defaults().left().growX().pad(4f);
        dialog.cont.pane(Styles.noBarPane, list).grow().row();
        dialog.addCloseButton();
        dialog.show();

        UpdateSchemeAuthorIndex.fetchShares(indexRef, refs -> {
            list.clearChildren();
            status.setText(Core.bundle.format("us.author.gallery.count", Integer.toString(refs.size)));
            if (refs.isEmpty()) {
                list.add("@us.author.gallery.empty").color(Color.lightGray).left().row();
                return;
            }
            for (int i = refs.size - 1; i >= 0; i--) {
                list.add(buildLoadingCard(refs.get(i))).growX().row();
            }
        }, err -> status.setText(Core.bundle.format("us.toast.failed.detail", "author-gallery", err)));
    }

    private static Table buildLoadingCard(UpdateSchemeDefs.ParsedRef ref) {
        Table card = new Table(Styles.black6);
        card.margin(8f);
        card.left().defaults().left();
        card.add(ref == null ? "" : UpdateSchemeDefs.shareText(ref)).color(Color.lightGray).wrap().growX().row();
        card.add("@loading").color(Color.lightGray).left().row();

        if (ref != null) {
            UpdateSchemeFetcher.fetchRemote(ref, remote -> {
                if (remote == null || remote.schematic == null) return;

                card.clearChildren();
                card.table(top -> {
                    top.left().defaults().left().padRight(10f);
                    top.add(new SchematicsDialog.SchematicImage(remote.schematic)).size(88f).top();
                    top.table(meta -> {
                        meta.left().defaults().left().growX().padBottom(2f);
                        meta.add(Strings.stripColors(remote.schematic.name())).color(Pal.accent).row();
                        meta.add(UpdateSchemeDefs.shareText(ref)).color(Color.lightGray).wrap().width(420f).row();
                        String createTime = UpdateSchemeFetcher.safeTag(remote.schematic, UpdateSchemeDefs.tagCreateTime);
                        String updateTime = UpdateSchemeFetcher.safeTag(remote.schematic, UpdateSchemeDefs.tagUpdateTime);
                        if (!createTime.isEmpty()) meta.add(Core.bundle.format("us.author.gallery.create", createTime)).color(Color.lightGray).row();
                        if (!updateTime.isEmpty()) meta.add(Core.bundle.format("us.author.gallery.update", updateTime)).color(Color.lightGray).row();
                    }).growX();
                }).growX().row();

                card.button("@us.action.copy-share", Icon.copy, () -> {
                    Core.app.setClipboardText(UpdateSchemeDefs.shareText(ref));
                    UpdateSchemeUI.toast("@us.toast.copied");
                }).height(42f).growX().padTop(4f).row();
            }, err -> {
            });
        }

        return card;
    }
}
