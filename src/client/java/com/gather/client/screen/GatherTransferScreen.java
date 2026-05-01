package com.gather.client.screen;

import com.gather.client.GatherList;
import com.gather.client.GatherState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class GatherTransferScreen extends Screen {

    private final Screen parent;

    private record WorldEntry(String worldId, List<GatherList> lists) {}
    private final List<WorldEntry> worlds = new ArrayList<>();
    private final List<int[]> worldLabelY = new ArrayList<>(); // y pos per world header

    public GatherTransferScreen(Screen parent) {
        super(Text.literal("Transfer List from Another World"));
        this.parent = parent;
        for (String wid : GatherState.listOtherWorldIds()) {
            List<GatherList> lists = GatherState.loadListsFromWorld(wid);
            if (!lists.isEmpty()) worlds.add(new WorldEntry(wid, lists));
        }
    }

    @Override
    protected void init() {
        worldLabelY.clear();
        int cx = width / 2;
        int y = 50;

        for (WorldEntry world : worlds) {
            worldLabelY.add(new int[]{y});
            y += 18;
            for (int li = 0; li < world.lists().size(); li++) {
                GatherList list = world.lists().get(li);
                final int listIndex = li;
                final String worldId = world.worldId();
                int roots = (int) list.nodes.stream().filter(n -> n.depth == 0).count();
                boolean atCap = GatherState.get().getListCount() >= 10;
                Text label = Text.literal(list.name + " (" + roots + " items)");
                ButtonWidget btn = ButtonWidget.builder(label, b -> {
                    GatherState.get().copyListFromWorld(worldId, listIndex);
                    close();
                }).dimensions(cx - 120, y, 210, 18).build();
                if (atCap) btn.active = false;
                addDrawableChild(btn);

                ButtonWidget copyBtn = ButtonWidget.builder(Text.literal("Copy"), b -> {
                    GatherState.get().copyListFromWorld(worldId, listIndex);
                    close();
                }).dimensions(cx + 95, y, 50, 18).build();
                if (atCap) copyBtn.active = false;
                addDrawableChild(copyBtn);

                y += 22;
            }
            y += 8;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("< Back"), btn -> close())
            .dimensions(cx - 50, height - 30, 100, 20)
            .build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0xCC111122);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFCCDDFF);

        if (worlds.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("No other worlds found."), width / 2, height / 2 - 10, 0xFFAAAAAA);
        } else {
            for (int wi = 0; wi < worlds.size(); wi++) {
                String worldId = worlds.get(wi).worldId();
                int labelY = worldLabelY.get(wi)[0];
                ctx.drawTextWithShadow(textRenderer,
                    Text.literal(worldId).withColor(0xFFCCDDFF),
                    width / 2 - 120, labelY + 4, 0xFFFFFF);
            }
        }

        if (GatherState.get().getListCount() >= 10) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("List limit reached (10 max)").withColor(0xFFFF6666),
                width / 2, height - 50, 0xFFFFFF);
        }

        super.render(ctx, mx, my, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
