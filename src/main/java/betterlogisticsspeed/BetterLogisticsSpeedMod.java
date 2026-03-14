package betterlogisticsspeed;

import arc.Events;
import betterlogisticsspeed.features.LongWindowFlowFeature;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;

import static mindustry.Vars.ui;

public class BetterLogisticsSpeedMod extends Mod {

    public static boolean bekBundled = false;

    private static boolean settingsAdded;

    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table) {
        LongWindowFlowFeature.buildSettings(table);
    }

    @Override
    public void init() {
        LongWindowFlowFeature.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (settingsAdded) return;
            settingsAdded = true;
            if (ui != null && ui.settings != null && !bekBundled) {
                ui.settings.addCategory("@settings.betterlogisticsspeed", Icon.settingsSmall, this::bekBuildSettings);
            }
        });
    }
}
