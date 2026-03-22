package updatescheme;

import mindustry.gen.Icon;
import mindustry.mod.Mod;
import updatescheme.features.UpdateSchemeFeature;

import static mindustry.Vars.ui;

public final class UpdateSchemeMod extends Mod {

    public static boolean bekBundled = false;

    public UpdateSchemeMod() {
        UpdateSchemeFeature.init();
    }

    public void bekBuildSettings(mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable table) {
        UpdateSchemeFeature.buildSettings(table);
    }

    @Override
    public void init() {
        if (bekBundled) return;
        if (ui != null) {
            ui.settings.addCategory("@settings.updatescheme", Icon.download, this::bekBuildSettings);
        }
    }
}
