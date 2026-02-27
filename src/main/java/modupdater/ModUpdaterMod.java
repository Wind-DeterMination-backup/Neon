package modupdater;

import arc.Events;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.SettingsMenuDialog;
import modupdater.features.ModUpdateCenter;

import static mindustry.Vars.ui;

public class ModUpdaterMod extends Mod{
    public static boolean bekBundled = false;
    private static boolean settingsAdded;

    @Override
    public void init(){
        ModUpdateCenter.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if(!settingsAdded && !bekBundled && ui != null && ui.settings != null){
                settingsAdded = true;
                ui.settings.addCategory("@settings.modupdater", Icon.refresh, this::bekBuildSettings);
            }

            ModUpdateCenter.checkOnceAtStartup();
        });
    }

    public void bekBuildSettings(SettingsMenuDialog.SettingsTable table){
        ModUpdateCenter.buildSettings(table);
    }
}
