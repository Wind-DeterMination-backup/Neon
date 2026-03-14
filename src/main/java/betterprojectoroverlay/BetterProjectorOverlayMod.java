package betterprojectoroverlay;

import arc.Events;
import betterprojectoroverlay.features.BetterProjectorOverlayFeature;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;

import static mindustry.Vars.ui;

public class BetterProjectorOverlayMod extends Mod {

    public static boolean bekBundled = false;

    private static boolean settingsAdded;

    public static void bekBuildSettings(SettingsMenuDialog.SettingsTable table) {
        BetterProjectorOverlayFeature.buildSettings(table);
    }

    @Override
    public void init() {
        BetterProjectorOverlayFeature.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (settingsAdded) return;
            settingsAdded = true;

            GithubUpdateCheck.applyDefaults();

            if (ui != null && ui.settings != null && !bekBundled) {
                ui.settings.addCategory("@settings.bpo", Icon.map, BetterProjectorOverlayMod::bekBuildSettings);
            }
            GithubUpdateCheck.checkOnce();
        });
    }
}
