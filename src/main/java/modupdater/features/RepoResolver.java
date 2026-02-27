package modupdater.features;

import arc.Core;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Strings;
import mindustry.mod.Mods;

import java.util.Locale;

public final class RepoResolver{
    private static final ObjectMap<String, String> builtinMap = new ObjectMap<>();

    static{
        builtinMap.put("bek-tools", "DeterMination-Wind/Neon");
        builtinMap.put("betterminimap", "DeterMination-Wind/betterMiniMap");
        builtinMap.put("betterhotkey", "DeterMination-Wind/betterHotKey");
        builtinMap.put("powergrid-minimap", "DeterMination-Wind/Power-Grid-Minimap");
        builtinMap.put("stealth-path", "DeterMination-Wind/StealthPath");
        builtinMap.put("radial-build-menu", "DeterMination-Wind/Radial-Build-Menu-hud-");
        builtinMap.put("betterprojectoroverlay", "DeterMination-Wind/BetterProjectorOverlay");
        builtinMap.put("bettermapeditor", "DeterMination-Wind/BetterMapEditor");
        builtinMap.put("server-player-database", "DeterMination-Wind/ServerPlayerDataBase");
        builtinMap.put("updatescheme", "DeterMination-Wind/UpdateScheme");
    }

    private RepoResolver(){
    }

    public static ObjectMap<String, String> loadOverrides(String key){
        Seq<String> lines = Core.settings.getJson(key, Seq.class, String.class, Seq::new);
        ObjectMap<String, String> out = new ObjectMap<>();
        for(String line : lines){
            if(line == null) continue;
            int sep = line.indexOf('=');
            if(sep <= 0 || sep >= line.length() - 1) continue;
            String name = line.substring(0, sep).trim().toLowerCase(Locale.ROOT);
            String repo = sanitizeRepo(line.substring(sep + 1));
            if(!name.isEmpty() && !repo.isEmpty()){
                out.put(name, repo);
            }
        }
        return out;
    }

    public static void saveOverrides(String key, ObjectMap<String, String> map){
        Seq<String> lines = new Seq<>();
        for(ObjectMap.Entry<String, String> e : map){
            if(e == null || e.key == null || e.value == null) continue;
            String name = e.key.trim().toLowerCase(Locale.ROOT);
            String repo = sanitizeRepo(e.value);
            if(!name.isEmpty() && !repo.isEmpty()){
                lines.add(name + "=" + repo);
            }
        }
        lines.sort();
        Core.settings.putJson(key, String.class, lines);
    }

    public static String resolveRepo(Mods.LoadedMod mod, ObjectMap<String, String> overrides){
        if(mod == null) return "";
        String internalName = mod.name == null ? "" : mod.name.toLowerCase(Locale.ROOT);

        String override = overrides.get(internalName);
        String repo = sanitizeRepo(override);
        if(!repo.isEmpty()) return repo;

        repo = sanitizeRepo(mod.getRepo());
        if(!repo.isEmpty()) return repo;

        repo = sanitizeRepo(mod.meta == null ? null : mod.meta.repo);
        if(!repo.isEmpty()) return repo;

        repo = sanitizeRepo(builtinMap.get(internalName));
        return repo;
    }

    public static String sanitizeRepo(String repo){
        String s = Strings.stripColors(repo == null ? "" : repo).trim();
        if(s.isEmpty()) return "";

        if(s.startsWith("https://github.com/")){
            s = s.substring("https://github.com/".length());
        }else if(s.startsWith("http://github.com/")){
            s = s.substring("http://github.com/".length());
        }else if(s.startsWith("github.com/")){
            s = s.substring("github.com/".length());
        }

        while(s.startsWith("/")){
            s = s.substring(1);
        }

        if(s.endsWith(".git")){
            s = s.substring(0, s.length() - 4);
        }
        if(s.endsWith("/")){
            s = s.substring(0, s.length() - 1);
        }

        String[] seg = s.split("/");
        if(seg.length < 2) return "";
        String owner = seg[0].trim();
        String name = seg[1].trim();
        if(owner.isEmpty() || name.isEmpty()) return "";
        if(owner.contains(" ") || name.contains(" ")) return "";
        return owner + "/" + name;
    }
}
