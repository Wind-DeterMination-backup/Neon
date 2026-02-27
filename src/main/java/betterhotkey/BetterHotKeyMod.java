package betterhotkey;

import arc.Events;
import betterhotkey.features.BetterHotKeyFeature;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;

import static mindustry.Vars.ui;

public class BetterHotKeyMod extends Mod {
    public static boolean bekBundled = false;

    private static boolean settingsAdded;

    @Override
    public void init() {
        BetterHotKeyFeature.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (settingsAdded || ui == null || ui.settings == null) return;
            settingsAdded = true;

            if (!bekBundled) {
                ui.settings.addCategory("@settings.betterhotkey", Icon.settingsSmall, this::bekBuildSettings);
            }
        });
    }

    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table) {
        BetterHotKeyFeature.buildSettings(table);
    }
}
