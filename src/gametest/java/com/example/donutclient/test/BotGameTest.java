package com.example.donutclient.test;

import com.example.donutclient.bot.Bot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Launches a real singleplayer world and lets the bot play for a while.
 * Scenario options are read from bot-test.properties in the run directory:
 *   seed, ticks, setup (server commands separated by ';'), shotEvery, tickRate, start (false to skip the bot)
 */
public class BotGameTest implements FabricClientGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("bot-test");

    @Override
    public void runTest(ClientGameTestContext context) {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(System.getProperty("bot.test.props", "../../bot-test.properties"))) {
            props.load(in);
        } catch (Exception e) {
            LOG.info("no bot-test.properties, using defaults");
        }
        String seed = props.getProperty("seed", "donut");
        int ticks = Integer.parseInt(props.getProperty("ticks", "1200"));
        int shotEvery = Integer.parseInt(props.getProperty("shotEvery", "400"));
        String setup = props.getProperty("setup", "");
        String tickRate = props.getProperty("tickRate", "20");
        boolean start = Boolean.parseBoolean(props.getProperty("start", "true"));

        context.runOnClient(mc -> {
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(8);
            mc.options.framerateLimit().set(260);
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
            mc.options.entityDistanceScaling().set(0.5);
            mc.options.enableVsync().set(false);
            mc.options.simulationDistance().set(6);
        });
        context.getInput().resizeWindow(427, 240);
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(s -> {
                    s.setSeed(seed);
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                    s.setAllowCommands(true);
                })
                .create()) {
            context.waitTicks(40);
            for (String cmd : setup.split(";")) {
                if (!cmd.isBlank()) sp.getServer().runCommand(cmd.trim());
            }
            if (!tickRate.equals("20")) sp.getServer().runCommand("tick rate " + tickRate);
            context.waitTicks(20);
            context.takeScreenshot("start");
            if (start) context.runOnClient(mc -> {
                String err = Bot.I.start();
                if (err != null) LOG.error("bot refused to start: {}", err);
            });
            int elapsed = 0;
            while (elapsed < ticks) {
                int step = Math.min(shotEvery, ticks - elapsed);
                context.waitTicks(step);
                elapsed += step;
                final int at = elapsed;
                boolean active = context.computeOnClient(mc -> {
                    var p = mc.player;
                    TreeMap<String, Integer> items = new TreeMap<>();
                    for (int i = 0; i < 36; i++) {
                        ItemStack st = p.getInventory().getItem(i);
                        if (!st.isEmpty()) items.merge(st.getItem().toString().replace("minecraft:", ""), st.getCount(), Integer::sum);
                    }
                    LOG.info("t={} deaths={} pos={} dim={} hp={} food={} status=[{}] inv={}", at, Bot.I.deaths, p.blockPosition().toShortString(),
                            mc.level.dimension().identifier().getPath(), (int) p.getHealth(),
                            p.getFoodData().getFoodLevel(), Bot.I.status(), items);
                    return Bot.I.isActive();
                });
                context.takeScreenshot(String.format("t%06d", elapsed));
                if (start && !active) break;
            }
            context.runOnClient(mc -> Bot.I.stop());
        }
    }
}
