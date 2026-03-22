package updatescheme.features;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Player;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog;

import java.util.Locale;

import static mindustry.Vars.player;
import static mindustry.Vars.schematics;
import static mindustry.Vars.ui;

final class UpdateSchemeUI {

    private UpdateSchemeUI() {
    }

    static void onChatMessage(Player sender, String message) {
        if (!UpdateSchemeFeature.enabled || message == null || message.isEmpty()) return;
        if (sender != null && player != null && sender.id == player.id) return;

        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(message);
        if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(message);
        if (ref == null || ref.source != UpdateSchemeDefs.Source.manifest) return;

        if (!UpdateSchemeFetcher.localsByKey(ref.source, ref.repo, ref.id).isEmpty()) {
            toast(Core.bundle.format("us.toast.already", ref.source.shortCode, ref.id));
            return;
        }

        if (UpdateSchemeFeature.chatAutoSub) {
            UpdateSchemeFetcher.subscribeAndImport(ref, true);
            toast(Core.bundle.format("us.toast.subscribed", ref.source.shortCode, ref.id));
            return;
        }

        showSubscribeDialog(ref);
    }

    static void openManager() {
        if (Vars.headless || ui == null || schematics == null) return;

        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.manager.title"));
        dialog.cont.margin(10f);

        Table tabs = new Table();
        dialog.cont.add(tabs).center().row();

        Table body = new Table();
        dialog.cont.add(body).grow().row();

        Runnable showSub = () -> {
            body.clearChildren();
            buildSubscribeTab(body);
        };
        Runnable showPub = () -> {
            body.clearChildren();
            buildPublishTab(body);
        };
        Runnable showDbg = () -> {
            body.clearChildren();
            buildDebugTab(body);
        };

        tabs.center();
        tabs.defaults().width(280f).height(46f).pad(4f);
        ButtonGroup<TextButton> tabGroup = new ButtonGroup<>();
        tabGroup.setMinCheckCount(1);
        tabGroup.setMaxCheckCount(1);
        TextButton b1 = tabs.button("@us.tab.subscribe", Styles.flatTogglet, showSub).get();
        TextButton b2 = tabs.button("@us.tab.publish", Styles.flatTogglet, showPub).get();
        TextButton b3 = tabs.button("@us.tab.debug", Styles.flatTogglet, showDbg).get();
        tabGroup.add(b1);
        tabGroup.add(b2);
        tabGroup.add(b3);
        styleButton(b1);
        styleButton(b2);
        styleButton(b3);
        b1.setChecked(true);
        showSub.run();

        dialog.addCloseButton();
        dialog.show();
    }

    static void toast(String message) {
        if (message == null || message.trim().isEmpty()) return;
        if (message.startsWith("@") && Core.bundle != null && Core.bundle.has(message.substring(1))) {
            UpdateSchemeFetcher.toast(Core.bundle.get(message.substring(1)));
            return;
        }
        UpdateSchemeFetcher.toast(message);
    }

    private static void showSubscribeDialog(UpdateSchemeDefs.ParsedRef ref) {
        if (ref == null) return;
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.dialog.subscribe.title"));
        dialog.cont.margin(10f);
        dialog.cont.add(Core.bundle.format("us.dialog.subscribe.text", Core.bundle.get("us.source.v2"), ref.id))
            .left()
            .wrap()
            .width(520f)
            .row();
        dialog.cont.add(Core.bundle.format("us.registry.repo.line", ref.repo)).left().color(Color.lightGray).row();
        dialog.buttons.defaults().size(200f, 54f).pad(6f);
        dialog.buttons.button("@us.subscribe", Icon.download, () -> {
            UpdateSchemeFetcher.subscribeAndImport(ref, false);
            dialog.hide();
        });
        dialog.addCloseButton();
        dialog.show();
    }

    private static void buildSubscribeTab(Table root) {
        root.top().left();
        root.defaults().left().growX().pad(4f);

        Table input = new Table(Styles.black6);
        input.margin(8f);
        TextField field = input.field("", t -> {
        }).growX().height(44f).get();
        field.setMessageText(Core.bundle.get("us.input.hint"));

        TextButton subscribe = input.button("@us.subscribe", Icon.download, () -> {
            String raw = Strings.stripColors(field.getText()).trim();
            if (raw.isEmpty()) return;
            UpdateSchemeFetcher.subscribeAndImportAuto(raw, UpdateSchemeDefs.Source.manifest, false);
        }).height(46f).minWidth(340f).padTop(8f).growX().get();
        styleButton(subscribe);
        input.row();
        root.add(input).growX().row();

        Seq<Schematic> managed = UpdateSchemeFetcher.listManagedSchematics();
        if (managed.isEmpty()) {
            root.add("@us.status.unbound").color(Color.lightGray).pad(8f).row();
            return;
        }

        root.pane(Styles.noBarPane, pane -> {
            pane.top().left().defaults().left().growX().pad(4f);
            for (int i = 0; i < managed.size; i++) {
                pane.add(buildManagedCard(managed.get(i))).growX().row();
            }
        }).grow().row();
    }

    private static void buildPublishTab(Table root) {
        root.top().left();
        root.defaults().left().growX().pad(4f);

        Table head = new Table(Styles.black6);
        head.margin(8f);

        Table list = new Table();
        list.top().left();
        list.defaults().left().growX().pad(4f);

        TextField filter = head.field("", t -> rebuildPublishList(list, t)).growX().height(44f).get();
        filter.setMessageText(Core.bundle.get("us.input.hint"));

        root.add(head).growX().row();
        rebuildPublishList(list, filter.getText());
        root.pane(Styles.noBarPane, pane -> pane.add(list).growX().row()).grow().row();
    }

    private static void buildDebugTab(Table root) {
        root.top().left();
        root.defaults().left().growX().pad(4f);

        Table box = new Table(Styles.black6);
        box.margin(8f);
        box.defaults().left().growX().pad(4f);

        TextField input = box.field("", t -> {
        }).height(44f).growX().get();
        input.setMessageText(Core.bundle.get("us.debug.input.hint"));

        Label output = new Label("");
        output.setWrap(true);
        output.setColor(Color.lightGray);

        styleButton(box.button("@us.debug.read", Icon.refresh, () -> {
            String raw = Strings.stripColors(input.getText()).trim();
            if (raw.isEmpty()) return;
            output.setText(Core.bundle.get("us.debug.reading"));
            UpdateSchemeFetcher.debugResolve(raw, UpdateSchemeDefs.Source.manifest, output::setText);
        }).height(46f).minWidth(340f).growX().get());
        box.row();

        box.pane(Styles.noBarPane, pane -> pane.add(output).left().top().width(620f).row())
            .maxHeight(320f)
            .growX()
            .row();

        root.add(box).growX().row();
    }

    private static void rebuildPublishList(Table list, String rawNeedle) {
        if (list == null || schematics == null) return;
        list.clearChildren();

        String needle = Strings.stripColors(rawNeedle == null ? "" : rawNeedle).trim().toLowerCase(Locale.ROOT);
        Seq<Schematic> all = schematics.all();
        for (int i = 0; i < all.size; i++) {
            Schematic s = all.get(i);
            String name = Strings.stripColors(s.name()).toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !name.contains(needle)) continue;
            list.add(buildPublishCard(s)).growX().row();
        }
    }

    private static Table buildManagedCard(Schematic schematic) {
        Table card = new Table(Styles.black6);
        card.margin(8f);
        card.left().defaults().left();

        UpdateSchemeFetcher.PendingUpdate pending = UpdateSchemeFetcher.pendingOf(schematic);
        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeFetcher.refOfSchematic(schematic);

        card.table(top -> {
            top.left().defaults().left().padRight(10f);
            top.add(new SchematicsDialog.SchematicImage(schematic)).size(88f).top();
            top.table(meta -> {
                meta.left().defaults().left().growX().padBottom(2f);
                meta.add(Strings.stripColors(schematic.name())).color(Pal.accent).row();
                if (ref != null) meta.add(UpdateSchemeDefs.shareText(ref)).color(Color.lightGray).wrap().width(420f).row();
                meta.add(Core.bundle.format("us.registry.repo.line", ref == null ? "" : ref.repo)).color(Color.lightGray).wrap().width(420f).row();
                appendSchematicMetadata(meta, schematic);
                String statusKey = pending != null ? "@us.status.update" : "@us.status.latest";
                String err = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagLastError);
                if (!err.isEmpty()) statusKey = "@us.status.error";
                meta.add(statusKey).color(statusColor(pending, err)).row();
                if (!err.isEmpty()) meta.add(err).color(Color.scarlet).wrap().width(420f).row();
            }).growX();
        }).growX().row();

        card.table(actions -> {
            actions.left().defaults().growX().height(42f).pad(3f);
            if (ref != null) {
                styleButton(actions.button("@us.action.check", Icon.refresh, () -> UpdateSchemeFetcher.enqueueCheck(ref, true)).minWidth(210f).get());
                styleButton(actions.button("@us.action.apply", Icon.download, () -> UpdateSchemeFetcher.applyUpdate(schematic)).minWidth(210f).get());
                actions.row();
                styleButton(actions.button("@us.action.copy-share", Icon.copy, () -> {
                    Core.app.setClipboardText(UpdateSchemeDefs.shareText(ref));
                    toast("@us.toast.copied");
                }).minWidth(210f).get());
                styleButton(actions.button("@us.action.unbind", Icon.cancel, () -> {
                    UpdateSchemeFetcher.unbindSchematic(schematic);
                    openManager();
                }).minWidth(210f).get());
                String author = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagAuthor);
                if (!author.isEmpty()) {
                    actions.row();
                    styleButton(actions.button("@us.author.gallery.open", Icon.book, () -> UpdateSchemeAuthorGallery.showFor(schematic)).colspan(2).minWidth(420f).get());
                }
            }
        }).growX();

        return card;
    }

    private static Table buildPublishCard(Schematic schematic) {
        Table card = new Table(Styles.black6);
        card.margin(8f);
        card.left().defaults().left();

        UpdateSchemeDefs.ParsedRef existing = UpdateSchemeFetcher.refOfSchematic(schematic);

        card.table(top -> {
            top.left().defaults().left().padRight(10f);
            top.add(new SchematicsDialog.SchematicImage(schematic)).size(88f).top();
            top.table(meta -> {
                meta.left().defaults().left().growX().padBottom(2f);
                meta.add(Strings.stripColors(schematic.name())).color(Pal.accent).row();
                if (existing != null) meta.add(UpdateSchemeDefs.shareText(existing)).color(Color.lightGray).wrap().width(420f).row();
                appendSchematicMetadata(meta, schematic);
            }).growX();
        }).growX().row();

        card.table(actions -> {
            actions.left().defaults().growX().height(42f).pad(3f);

            styleButton(actions.button("@us.action.copy-base64", Icon.copy, () -> {
                String base64 = UpdateSchemeUtil.exportBase64ForPublish(schematic);
                if (!base64.isEmpty()) Core.app.setClipboardText(base64);
                toast("@us.toast.copied");
            }).minWidth(210f).get());

            styleButton(actions.button("@us.action.bind", Icon.edit, () -> showBindDialog(schematic)).minWidth(210f).get());
            actions.row();

            styleButton(actions.button(existing == null ? "@us.uc.upload" : "@us.uc.update", existing == null ? Icon.upload : Icon.refresh, () -> {
                if (existing == null) UpdateSchemeUploadDialog.showUpload(schematic);
                else UpdateSchemeUploadDialog.showUpdate(schematic);
            }).colspan(2).minWidth(420f).get());

            if (existing != null) {
                actions.row();
                styleButton(actions.button("@us.uc.copy-share", Icon.link, () -> {
                    Core.app.setClipboardText(UpdateSchemeDefs.shareText(existing));
                    toast("@us.toast.copied");
                }).colspan(2).minWidth(420f).get());
            }

            String author = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagAuthor);
            if (!author.isEmpty()) {
                actions.row();
                styleButton(actions.button("@us.author.gallery.open", Icon.book, () -> UpdateSchemeAuthorGallery.showFor(schematic)).colspan(2).minWidth(420f).get());
            }
        }).growX();

        return card;
    }

    private static void showBindDialog(Schematic target) {
        if (target == null || ui == null) return;
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.action.bind"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        TextField field = dialog.cont.field("", t -> {
        }).height(44f).get();
        field.setMessageText(Core.bundle.get("us.input.hint"));

        dialog.buttons.defaults().size(200f, 54f).pad(6f);
        dialog.buttons.button("@us.action.bind", Icon.ok, () -> {
            String raw = Strings.stripColors(field.getText()).trim();
            UpdateSchemeDefs.ParsedRef ref = UpdateSchemeDefs.parseRefFromText(raw);
            if (ref == null) ref = UpdateSchemeDefs.parseRefFromUrl(raw);
            if (ref == null || ref.source != UpdateSchemeDefs.Source.manifest) {
                toast(Core.bundle.format("us.toast.failed.detail", "bind", Core.bundle.get("us.error.invalid-id")));
                return;
            }
            UpdateSchemeFetcher.bindSchematic(target, ref);
            dialog.hide();
        });
        dialog.addCloseButton();
        dialog.show();
    }

    private static void appendSchematicMetadata(Table meta, Schematic schematic) {
        String author = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagAuthor);
        String createTime = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagCreateTime);
        String updateTime = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagUpdateTime);
        if (!author.isEmpty()) meta.add(Core.bundle.format("us.author.line", author)).color(Color.lightGray).row();
        if (!createTime.isEmpty()) meta.add(Core.bundle.format("us.author.create", createTime)).color(Color.lightGray).row();
        if (!updateTime.isEmpty()) meta.add(Core.bundle.format("us.author.update", updateTime)).color(Color.lightGray).row();
    }

    private static Color statusColor(UpdateSchemeFetcher.PendingUpdate pending, String err) {
        if (err != null && !err.isEmpty()) return Color.scarlet;
        return pending != null ? Pal.accent : Color.lightGray;
    }

    private static void styleButton(TextButton button) {
        if (button == null || button.getLabel() == null) return;
        button.getLabel().setWrap(false);
        button.getLabel().setEllipsis(true);
    }
}
