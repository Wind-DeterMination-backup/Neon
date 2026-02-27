package betterlogisticsspeed.features;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Image;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Scaling;
import arc.util.Strings;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.ui.Displayable;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.world.Block;
import mindustry.world.meta.StatUnit;
import mindustry.world.modules.ItemModule;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

import static mindustry.Vars.content;
import static mindustry.Vars.control;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

public class LongWindowFlowFeature {

    private static final String keyEnabled = "bls-enabled";
    private static final String keyWindowSeconds = "bls-window-seconds";
    private static final String keyShowTotal = "bls-show-total";
    private static final String keyDecimals = "bls-decimals";

    private static final String extraTableName = "bls-flow-extra";
    private static final long sampleIntervalMs = 250L;

    private static boolean inited;
    private static boolean reflectReady;

    private static boolean enabled;
    private static boolean showTotal;
    private static int windowSeconds;
    private static int decimals;

    private static Field fieldTopTable;
    private static Field fieldMenuHoverBlock;
    private static Field fieldHover;
    private static Field fieldNextFlowBuild;

    private static final ObjectSet<TextureRegion> itemIconRegions = new ObjectSet<>();
    private static boolean itemIconCacheReady;

    private static Building trackedBuild;
    private static BuildFlowTracker trackedTracker;

    public static void init() {
        if (inited) return;
        inited = true;

        Core.settings.defaults(keyEnabled, true);
        Core.settings.defaults(keyWindowSeconds, 10);
        Core.settings.defaults(keyShowTotal, true);
        Core.settings.defaults(keyDecimals, 1);
        refreshSettings();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            refreshSettings();
            tryInitReflection();
            rebuildItemIconCache();
            clearTracking();
        });

        Events.on(EventType.WorldLoadEvent.class, e -> clearTracking());
        Events.on(EventType.ResetEvent.class, e -> clearTracking());

        Events.run(EventType.Trigger.update, () -> {
            refreshSettings();
            updateTracking();
        });

        Events.run(EventType.Trigger.uiDrawEnd, LongWindowFlowFeature::updateDisplay);
    }

    public static void buildSettings(SettingsMenuDialog.SettingsTable table) {
        table.checkPref(keyEnabled, true);
        table.sliderPref(keyWindowSeconds, 10, 2, 60, 1, i -> i + "s");
        table.checkPref(keyShowTotal, true);
        table.sliderPref(keyDecimals, 1, 1, 2, 1, String::valueOf);
        refreshSettings();
    }

    private static void refreshSettings() {
        enabled = Core.settings.getBool(keyEnabled, true);
        showTotal = Core.settings.getBool(keyShowTotal, true);
        windowSeconds = clamp(Core.settings.getInt(keyWindowSeconds, 10), 2, 60);
        decimals = clamp(Core.settings.getInt(keyDecimals, 1), 1, 2);

        if (trackedTracker != null) {
            trackedTracker.setWindowSeconds(windowSeconds);
        }
    }

    private static void updateTracking() {
        if (!enabled) {
            clearTracking();
            return;
        }

        if (ui == null || ui.hudfrag == null || ui.hudfrag.blockfrag == null || state == null || !state.isGame()) {
            clearTracking();
            return;
        }

        tryInitReflection();
        if (!reflectReady) {
            clearTracking();
            return;
        }

        try {
            Object pf = ui.hudfrag.blockfrag;
            Building next = (Building) fieldNextFlowBuild.get(pf);
            if (next == null || next.flowItems() == null) {
                clearTracking();
                return;
            }

            if (trackedBuild != next || trackedTracker == null) {
                trackedBuild = next;
                trackedTracker = new BuildFlowTracker(windowSeconds);
            }

            trackedTracker.sample(next, Time.millis(), sampleIntervalMs);
        } catch (Throwable ignored) {
        }
    }

    private static void updateDisplay() {
        if (ui == null || ui.hudfrag == null || ui.hudfrag.blockfrag == null || state == null || !state.isGame()) return;

        tryInitReflection();
        if (!reflectReady) return;

        try {
            Object pf = ui.hudfrag.blockfrag;
            Table topTable = (Table) fieldTopTable.get(pf);
            if (topTable == null) return;

            if (!enabled || trackedTracker == null || trackedBuild == null) {
                hideInjectedRows(topTable);
                return;
            }

            Block menuHoverBlock = (Block) fieldMenuHoverBlock.get(pf);
            Displayable hover = (Displayable) fieldHover.get(pf);

            if (menuHoverBlock != null || control.input.block != null || !(hover instanceof Building)) {
                hideInjectedRows(topTable);
                return;
            }

            Building displayBuild = (Building) hover;
            if (displayBuild != trackedBuild || displayBuild.flowItems() == null) {
                hideInjectedRows(topTable);
                return;
            }

            Table flowTable = findItemFlowTable(topTable);
            if (flowTable == null) {
                hideInjectedRows(topTable);
                return;
            }

            Table extra = (Table) flowTable.find(extraTableName);
            if (extra == null) {
                flowTable.row();
                extra = flowTable.table(t -> t.left().marginTop(1f)).left().padTop(1f).colspan(2).get();
                extra.name = extraTableName;
            }
            extra.visible = true;

            rebuildExtraRows(extra, displayBuild.flowItems());
        } catch (Throwable ignored) {
        }
    }

    private static void rebuildExtraRows(Table extra, ItemModule flowItems) {
        if (extra == null || flowItems == null || trackedTracker == null) return;

        extra.clearChildren();
        extra.left();

        long now = Time.millis();
        String speedLabel = Core.bundle.format("bls.flow.avg.label", windowSeconds);
        String perSecond = " " + StatUnit.perSecond.localized();

        float total = 0f;
        int totalCount = 0;
        int shown = 0;

        for (Item item : content.items()) {
            if (!flowItems.hasFlowItem(item)) continue;

            float avg = trackedTracker.average(item.id, now);
            String valueText = avg < 0f
                ? Core.bundle.get("bls.flow.na")
                : Strings.fixed(avg, decimals) + perSecond;

            extra.image(item.uiIcon).scaling(Scaling.fit).padRight(3f);
            Label line = extra.add(speedLabel + ": " + valueText).left().color(Color.lightGray).get();
            line.setWrap(false);
            extra.row();

            if (avg >= 0f) {
                total += avg;
                totalCount++;
            }
            shown++;
        }

        if (showTotal && shown > 0) {
            String valueText = totalCount == 0
                ? Core.bundle.get("bls.flow.na")
                : Strings.fixed(total, decimals) + perSecond;
            extra.add(Core.bundle.get("bls.flow.total.label") + ": " + valueText)
                .left()
                .color(Color.lightGray)
                .colspan(2);
            extra.row();
        }
    }

    private static void hideInjectedRows(Table topTable) {
        if (topTable == null) return;

        try {
            Element extra = topTable.find(extraTableName);
            if (extra instanceof Table) {
                Table table = (Table) extra;
                table.clearChildren();
                table.visible = false;
            }
        } catch (Throwable ignored) {
        }
    }

    private static Table findItemFlowTable(Table root) {
        if (root == null) return null;

        String perSecond = StatUnit.perSecond.localized();
        Seq<Element> stack = new Seq<>();
        stack.add(root);

        while (stack.size > 0) {
            Element element = stack.pop();

            if (element instanceof Table) {
                Table table = (Table) element;
                if (isItemFlowTableCandidate(table, perSecond)) {
                    return table;
                }
            }

            if (element instanceof Group) {
                Group group = (Group) element;
                for (Element child : group.getChildren()) {
                    if (child != null) {
                        stack.add(child);
                    }
                }
            }
        }

        return null;
    }

    private static boolean isItemFlowTableCandidate(Table table, String perSecondLocalized) {
        if (table == null) return false;
        if (extraTableName.equals(table.name)) return false;

        int itemIconCount = 0;
        int speedLabelCount = 0;

        for (Element child : table.getChildren()) {
            if (child instanceof Image) {
                if (isItemIcon((Image) child)) {
                    itemIconCount++;
                }
            } else if (child instanceof Label) {
                String text;
                try {
                    text = ((Label) child).getText() == null ? "" : ((Label) child).getText().toString();
                } catch (Throwable ignored) {
                    text = "";
                }

                String stripped = Strings.stripColors(text);
                if (stripped != null && stripped.contains(perSecondLocalized)) {
                    speedLabelCount++;
                }
            }
        }

        return itemIconCount > 0 && speedLabelCount > 0;
    }

    private static boolean isItemIcon(Image image) {
        if (image == null) return false;
        if (!(image.getDrawable() instanceof TextureRegionDrawable)) return false;

        TextureRegion region = ((TextureRegionDrawable) image.getDrawable()).getRegion();
        if (region == null) return false;
        if (!itemIconCacheReady) rebuildItemIconCache();

        return itemIconRegions.contains(region);
    }

    private static void rebuildItemIconCache() {
        itemIconRegions.clear();
        for (Item item : content.items()) {
            if (item != null && item.uiIcon != null) {
                itemIconRegions.add(item.uiIcon);
            }
        }
        itemIconCacheReady = true;
    }

    private static void tryInitReflection() {
        if (reflectReady) return;

        try {
            Class<?> cls = Class.forName("mindustry.ui.fragments.PlacementFragment");

            fieldTopTable = cls.getDeclaredField("topTable");
            fieldMenuHoverBlock = cls.getDeclaredField("menuHoverBlock");
            fieldHover = cls.getDeclaredField("hover");
            fieldNextFlowBuild = cls.getDeclaredField("nextFlowBuild");

            fieldTopTable.setAccessible(true);
            fieldMenuHoverBlock.setAccessible(true);
            fieldHover.setAccessible(true);
            fieldNextFlowBuild.setAccessible(true);

            reflectReady = true;
        } catch (Throwable ignored) {
            reflectReady = false;
        }
    }

    private static void clearTracking() {
        trackedBuild = null;
        trackedTracker = null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class BuildFlowTracker {
        private final ObjectMap<Integer, ItemRateWindow> windows = new ObjectMap<>();
        private int windowSeconds;
        private long lastSampleMs;

        BuildFlowTracker(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        void setWindowSeconds(int windowSeconds) {
            if (this.windowSeconds == windowSeconds) return;
            this.windowSeconds = windowSeconds;
            windows.clear();
            lastSampleMs = 0L;
        }

        void sample(Building build, long nowMs, long sampleIntervalMs) {
            if (build == null || build.flowItems() == null) return;
            if (nowMs - lastSampleMs < sampleIntervalMs) return;

            lastSampleMs = nowMs;
            long windowMs = windowSeconds * 1000L;
            ItemModule flow = build.flowItems();

            for (Item item : content.items()) {
                if (!flow.hasFlowItem(item)) continue;

                float rate = flow.getFlowRate(item);
                if (rate < 0f) continue;

                int itemId = item.id;
                ItemRateWindow window = windows.get(itemId);
                if (window == null) {
                    window = new ItemRateWindow();
                    windows.put(itemId, window);
                }
                window.add(nowMs, rate, windowMs);
            }
        }

        float average(int itemId, long nowMs) {
            ItemRateWindow window = windows.get(itemId);
            if (window == null) return -1f;
            return window.average(nowMs, windowSeconds * 1000L);
        }
    }

    private static class ItemRateWindow {
        private final ArrayDeque<SamplePoint> samples = new ArrayDeque<>();
        private float sum;

        void add(long timeMs, float value, long windowMs) {
            samples.addLast(new SamplePoint(timeMs, value));
            sum += value;
            trim(timeMs, windowMs);
        }

        float average(long nowMs, long windowMs) {
            trim(nowMs, windowMs);
            if (samples.size() < 2) return -1f;
            return Math.max(sum / samples.size(), 0f);
        }

        private void trim(long nowMs, long windowMs) {
            while (!samples.isEmpty()) {
                SamplePoint first = samples.peekFirst();
                if (first == null || nowMs - first.timeMs <= windowMs) break;

                SamplePoint removed = samples.removeFirst();
                sum -= removed.value;
            }

            if (samples.isEmpty()) {
                sum = 0f;
            }
        }
    }

    private static class SamplePoint {
        final long timeMs;
        final float value;

        SamplePoint(long timeMs, float value) {
            this.timeMs = timeMs;
            this.value = value;
        }
    }
}
