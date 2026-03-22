package updatescheme.features;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Schematic;
import mindustry.gen.Call;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

final class UpdateSchemeUploadDialog {

    private static final class PublisherContext {
        final String author;
        final UpdateSchemeDefs.ParsedRef authorIndex;

        PublisherContext(String author, UpdateSchemeDefs.ParsedRef authorIndex) {
            this.author = author == null ? "" : author.trim();
            this.authorIndex = authorIndex;
        }
    }

    private UpdateSchemeUploadDialog() {
    }

    static void oneClickUpload(Schematic schematic) {
        if (Vars.headless || Vars.ui == null || schematic == null) return;
        withPublisherContext(schematic, ctx -> publishNew(schematic, ctx, true));
    }

    static void oneClickUpdateOrShow(Schematic schematic) {
        if (Vars.headless || Vars.ui == null || schematic == null) return;
        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeFetcher.refOfSchematic(schematic);
        if (ref == null) {
            Seq<UpdateSchemeDefs.ParsedRef> known = UpdateSchemeFetcher.knownRefsForSameContent(schematic);
            if (!known.isEmpty()) {
                showReuseOrCreateDialog(schematic, known);
                return;
            }
            oneClickUpload(schematic);
            return;
        }
        withPublisherContext(schematic, ctx -> publishUpdate(schematic, ref, ctx, true));
    }

    static void showUpload(Schematic schematic) {
        if (Vars.headless || Vars.ui == null || schematic == null) return;

        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.uc.dialog.upload"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        TextField shareField = dialog.cont.field("", t -> {
        }).height(44f).get();
        shareField.setMessageText(Core.bundle.get("us.input.hint"));

        TextField authorField = dialog.cont.field(UpdateSchemeUtil.getStoredAuthorName(), t -> {
        }).height(44f).get();
        authorField.setMessageText(Core.bundle.get("us.author.hint"));

        TextField tokenField = dialog.cont.field(UpdateSchemeUtil.getRegistryToken(), t -> {
        }).height(44f).get();
        tokenField.setMessageText(Core.bundle.get("us.registry.token.hint"));

        TextField repoField = dialog.cont.field(UpdateSchemeUtil.getRegistryRepo(), t -> {
        }).height(44f).get();
        repoField.setMessageText(Core.bundle.get("us.registry.repo.hint"));

        dialog.cont.add("@us.author.note").color(Color.lightGray).wrap().left().width(560f).row();

        dialog.cont.table(actions -> {
            actions.left().defaults().height(46f).growX().pad(4f);

            styleButton(actions.button("@us.uc.copy-base64", Icon.copy, () -> {
                String base64 = UpdateSchemeUtil.exportBase64ForPublish(schematic);
                if (!base64.isEmpty()) Core.app.setClipboardText(base64);
            }).minWidth(220f).get());

            styleButton(actions.button("@us.uc.upload", Icon.upload, () -> {
                savePublisherFields(authorField, tokenField, repoField);
                dialog.hide();
                oneClickUpload(schematic);
            }).minWidth(220f).get());
            actions.row();

            styleButton(actions.button("@us.uc.bind", Icon.ok, () -> {
                String raw = Strings.stripColors(shareField.getText()).trim();
                UpdateSchemeDefs.ParsedRef parsed = UpdateSchemeDefs.parseRefFromText(raw);
                if (parsed == null) parsed = UpdateSchemeDefs.parseRefFromUrl(raw);
                if (parsed == null || parsed.source != UpdateSchemeDefs.Source.manifest) {
                    UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "bind", Core.bundle.get("us.error.invalid-id")));
                    return;
                }
                UpdateSchemeFetcher.bindSchematic(schematic, parsed);
                dialog.hide();
            }).minWidth(220f).get());

            styleButton(actions.button("@us.uc.copy-share", Icon.copy, () -> {
                UpdateSchemeDefs.ParsedRef ref = UpdateSchemeFetcher.refOfSchematic(schematic);
                if (ref == null) return;
                Core.app.setClipboardText(UpdateSchemeDefs.shareText(ref));
                showCopiedDialog(UpdateSchemeDefs.shareText(ref), false);
            }).minWidth(220f).get());
        }).growX().row();

        dialog.addCloseButton();
        dialog.show();
    }

    static void showUpdate(Schematic schematic) {
        if (Vars.headless || Vars.ui == null || schematic == null) return;
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.uc.dialog.update"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        UpdateSchemeDefs.ParsedRef ref = UpdateSchemeFetcher.refOfSchematic(schematic);
        if (ref == null) {
            dialog.cont.add("@us.uc.not-bound").color(Color.lightGray).wrap().width(520f).row();
            dialog.buttons.defaults().size(220f, 54f).pad(6f);
            dialog.buttons.button(Core.bundle.get("us.uc.upload"), Icon.upload, () -> {
                dialog.hide();
                showUpload(schematic);
            });
            dialog.addCloseButton();
            dialog.show();
            return;
        }

        dialog.cont.add(UpdateSchemeDefs.shareText(ref)).color(Color.lightGray).wrap().width(560f).row();
        dialog.cont.add(Core.bundle.format("us.registry.repo.line", ref.repo)).color(Color.lightGray).left().row();

        dialog.buttons.defaults().size(220f, 54f).pad(6f);
        dialog.buttons.button("@us.uc.update", Icon.refresh, () -> {
            dialog.hide();
            oneClickUpdateOrShow(schematic);
        });
        dialog.buttons.button("@us.uc.copy-share", Icon.copy, () -> {
            Core.app.setClipboardText(UpdateSchemeDefs.shareText(ref));
            showCopiedDialog(UpdateSchemeDefs.shareText(ref), false);
        });
        dialog.buttons.button("@us.uc.open-page", Icon.link, () -> Core.app.openURI(UpdateSchemeRegistry.viewUrl(ref)));
        dialog.addCloseButton();
        dialog.show();
    }

    private static void publishNew(Schematic schematic, PublisherContext ctx, boolean attemptChat) {
        String text = UpdateSchemeUtil.buildRootUploadText(schematic, ctx.author, ctx.authorIndex);
        if (text.isEmpty()) return;

        UpdateSchemeBlobStore.uploadText(text, blobUrl -> UpdateSchemeRegistry.resolvePublisherRepo(repo -> {
            UpdateSchemeRegistry.ManifestRecord manifest = new UpdateSchemeRegistry.ManifestRecord();
            manifest.manifestId = UpdateSchemeUtil.randomId("mf");
            manifest.repo = repo.spec;
            manifest.title = Strings.stripColors(schematic.name()).trim();
            manifest.author = ctx.author;
            manifest.authorIndex = ctx.authorIndex == null ? "" : UpdateSchemeDefs.shareText(ctx.authorIndex);
            manifest.createdAt = UpdateSchemeUtil.formatNow();
            manifest.updatedAt = manifest.createdAt;
            manifest.latestBlobUrl = blobUrl;
            manifest.latestHash = UpdateSchemeUtil.computeContentHash(schematic);
            manifest.latestName = Strings.stripColors(schematic.name()).trim();
            manifest.revision = 1;

            UpdateSchemeRegistry.createManifest(repo, manifest, manifestRef -> {
                onShareReady(schematic, manifestRef, attemptChat, ctx, text);
            }, err -> UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "manifest", err)));
        }, err -> UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "repo", err))), err -> {
            Log.warn("UpdateScheme: blob upload failed: @", err);
            UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "blob", err));
        });
    }

    private static void publishUpdate(Schematic schematic, UpdateSchemeDefs.ParsedRef ref, PublisherContext ctx, boolean attemptChat) {
        String text = UpdateSchemeUtil.buildUpdateUploadText(schematic, ctx.author, ctx.authorIndex);
        if (text.isEmpty()) return;

        UpdateSchemeBlobStore.uploadText(text, blobUrl -> UpdateSchemeRegistry.fetchManifest(ref, manifest -> {
            manifest.title = Strings.stripColors(schematic.name()).trim();
            manifest.author = ctx.author;
            manifest.authorIndex = ctx.authorIndex == null ? "" : UpdateSchemeDefs.shareText(ctx.authorIndex);
            if (manifest.createdAt.isEmpty()) manifest.createdAt = UpdateSchemeUtil.formatNow();
            manifest.updatedAt = UpdateSchemeUtil.formatNow();
            manifest.latestBlobUrl = blobUrl;
            manifest.latestHash = UpdateSchemeUtil.computeContentHash(schematic);
            manifest.latestName = Strings.stripColors(schematic.name()).trim();
            manifest.revision = Math.max(1, manifest.revision) + 1;

            UpdateSchemeRegistry.updateManifest(ref, manifest, sha -> {
                onShareReady(schematic, ref, attemptChat, ctx, text);
            }, err -> UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "manifest", err)));
        }, err -> UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "manifest", err))), err -> {
            Log.warn("UpdateScheme: blob upload failed: @", err);
            UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "blob", err));
        });
    }

    private static void onShareReady(Schematic schematic, UpdateSchemeDefs.ParsedRef manifestRef, boolean attemptChat, PublisherContext ctx, String publishedText) {
        UpdateSchemeUtil.applyMetadataTags(schematic, UpdateSchemeUtil.parseMetadata(publishedText));
        UpdateSchemeFetcher.bindSchematic(schematic, manifestRef);

        if (schematic.tags == null) schematic.tags = new arc.struct.StringMap();
        if (ctx != null && ctx.authorIndex != null) {
            schematic.tags.put(UpdateSchemeDefs.tagAuthorIndexRef, UpdateSchemeDefs.shareText(ctx.authorIndex));
            UpdateSchemeRegistry.appendAuthorIndex(ctx.authorIndex, manifestRef, ctx.author, reply -> {
            }, err -> Log.warn("UpdateScheme: author index update failed: @", err));
        }

        String share = UpdateSchemeDefs.shareText(manifestRef);
        Core.app.setClipboardText(share);

        boolean sent = attemptChat && trySendChat(share);
        if (sent) Log.info("UpdateScheme: sent share to chat: @", share);
        else Log.info("UpdateScheme: copied share to clipboard: @", share);

        showCopiedDialog(share, sent);
    }

    private static void withPublisherContext(Schematic schematic, Cons<PublisherContext> onReady) {
        String author = UpdateSchemeFetcher.safeTag(schematic, UpdateSchemeDefs.tagAuthor);
        if (author.isEmpty()) author = UpdateSchemeUtil.getStoredAuthorName();
        String token = UpdateSchemeUtil.getRegistryToken();
        if (author.isEmpty() || token.isEmpty()) {
            showPublisherPrompt(author, token, UpdateSchemeUtil.getRegistryRepo(), result -> continueWithPublisher(result.author, result.token, result.repo, onReady));
            return;
        }
        continueWithPublisher(author, token, UpdateSchemeUtil.getRegistryRepo(), onReady);
    }

    private static void continueWithPublisher(String author, String token, String repo, Cons<PublisherContext> onReady) {
        String cleanAuthor = Strings.stripColors(author == null ? "" : author).trim();
        String cleanToken = token == null ? "" : token.trim();
        if (cleanAuthor.isEmpty()) {
            UpdateSchemeUI.toast("@us.author.required");
            return;
        }
        if (cleanToken.isEmpty()) {
            UpdateSchemeUI.toast("@us.registry.token.required");
            return;
        }

        UpdateSchemeUtil.setStoredAuthorName(cleanAuthor);
        UpdateSchemeUtil.setRegistryToken(cleanToken);
        UpdateSchemeUtil.setRegistryRepo(repo);
        UpdateSchemeAuthorIndex.ensureIndex(cleanAuthor, ref -> {
            if (onReady != null) onReady.get(new PublisherContext(cleanAuthor, ref));
        }, err -> UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "author-index", err)));
    }

    private static void showReuseOrCreateDialog(Schematic schematic, Seq<UpdateSchemeDefs.ParsedRef> knownRefs) {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.uc.dialog.reuse.title"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);
        dialog.cont.add(Core.bundle.get("us.uc.dialog.reuse.text")).left().wrap().width(560f).row();

        Table list = new Table(Styles.black6);
        list.margin(8f);
        list.defaults().growX().left().pad(2f);
        for (int i = 0; i < knownRefs.size; i++) {
            list.add(UpdateSchemeDefs.shareText(knownRefs.get(i))).color(Color.lightGray).row();
        }
        dialog.cont.add(list).growX().row();

        dialog.buttons.defaults().size(240f, 54f).pad(6f);
        dialog.buttons.button("@us.uc.use-existing", Icon.edit, () -> {
            dialog.hide();
            showFillExistingCodeDialog(schematic, knownRefs);
        });
        dialog.buttons.button("@us.uc.create-new", Icon.upload, () -> {
            dialog.hide();
            oneClickUpload(schematic);
        });
        dialog.addCloseButton();
        dialog.show();
    }

    private static void showFillExistingCodeDialog(Schematic schematic, Seq<UpdateSchemeDefs.ParsedRef> knownRefs) {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.uc.dialog.fill-existing"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        TextField field = dialog.cont.field(UpdateSchemeDefs.shareText(knownRefs.first()), t -> {
        }).height(44f).get();
        field.setMessageText(Core.bundle.get("us.input.hint"));

        dialog.cont.pane(pane -> {
            pane.top().left().defaults().growX().height(42f).pad(3f);
            for (int i = 0; i < knownRefs.size; i++) {
                UpdateSchemeDefs.ParsedRef ref = knownRefs.get(i);
                String share = UpdateSchemeDefs.shareText(ref);
                TextButton btn = pane.button(share, Styles.flatt, () -> field.setText(share)).minWidth(520f).get();
                styleButton(btn);
                pane.row();
            }
        }).maxHeight(220f).growX().row();

        dialog.buttons.defaults().size(220f, 54f).pad(6f);
        dialog.buttons.button("@us.uc.bind-existing", Icon.ok, () -> {
            String raw = Strings.stripColors(field.getText()).trim();
            UpdateSchemeDefs.ParsedRef parsed = UpdateSchemeDefs.parseRefFromText(raw);
            if (parsed == null) parsed = UpdateSchemeDefs.parseRefFromUrl(raw);
            if (parsed == null || parsed.source != UpdateSchemeDefs.Source.manifest) {
                UpdateSchemeUI.toast(Core.bundle.format("us.toast.failed.detail", "bind", Core.bundle.get("us.error.invalid-id")));
                return;
            }
            UpdateSchemeFetcher.bindSchematic(schematic, parsed);
            dialog.hide();
        });
        dialog.addCloseButton();
        dialog.show();
    }

    private static void showCopiedDialog(String shareCode, boolean sentToChat) {
        if (Vars.headless || Vars.ui == null || shareCode == null || shareCode.trim().isEmpty()) return;
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.dialog.copied.title"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);
        dialog.cont.add(Core.bundle.format("us.dialog.copied.text", shareCode)).left().wrap().width(560f).row();
        if (sentToChat) dialog.cont.add("@us.toast.sent").color(Color.lightGray).left().row();
        dialog.addCloseButton();
        dialog.show();
    }

    private static boolean trySendChat(String message) {
        if (message == null || message.trim().isEmpty()) return false;
        try {
            if (Vars.net == null || !Vars.net.active()) return false;
            Call.sendChatMessage(message);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void savePublisherFields(TextField authorField, TextField tokenField, TextField repoField) {
        UpdateSchemeUtil.setStoredAuthorName(Strings.stripColors(authorField.getText()).trim());
        UpdateSchemeUtil.setRegistryToken(tokenField.getText());
        UpdateSchemeUtil.setRegistryRepo(repoField.getText());
    }

    private static void showPublisherPrompt(String author, String token, String repo, Cons<PublisherPromptResult> onDone) {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("us.registry.setup.title"));
        dialog.cont.margin(10f);
        dialog.cont.defaults().growX().pad(4f);

        TextField authorField = dialog.cont.field(author == null ? "" : author, t -> {
        }).height(44f).get();
        authorField.setMessageText(Core.bundle.get("us.author.hint"));

        TextField tokenField = dialog.cont.field(token == null ? "" : token, t -> {
        }).height(44f).get();
        tokenField.setMessageText(Core.bundle.get("us.registry.token.hint"));

        TextField repoField = dialog.cont.field(repo == null ? "" : repo, t -> {
        }).height(44f).get();
        repoField.setMessageText(Core.bundle.get("us.registry.repo.hint"));

        dialog.cont.add("@us.registry.note").color(Color.lightGray).wrap().left().width(560f).row();

        dialog.buttons.defaults().size(220f, 54f).pad(6f);
        dialog.buttons.button("@ok", Icon.ok, () -> {
            String cleanAuthor = Strings.stripColors(authorField.getText()).trim();
            String cleanToken = tokenField.getText() == null ? "" : tokenField.getText().trim();
            if (cleanAuthor.isEmpty()) {
                UpdateSchemeUI.toast("@us.author.required");
                return;
            }
            if (cleanToken.isEmpty()) {
                UpdateSchemeUI.toast("@us.registry.token.required");
                return;
            }
            dialog.hide();
            if (onDone != null) onDone.get(new PublisherPromptResult(cleanAuthor, cleanToken, repoField.getText()));
        });
        dialog.addCloseButton();
        dialog.show();
    }

    private static void styleButton(TextButton button) {
        if (button == null || button.getLabel() == null) return;
        button.getLabel().setWrap(false);
        button.getLabel().setEllipsis(true);
    }

    private static final class PublisherPromptResult {
        final String author;
        final String token;
        final String repo;

        private PublisherPromptResult(String author, String token, String repo) {
            this.author = author;
            this.token = token;
            this.repo = repo == null ? "" : repo.trim();
        }
    }
}
