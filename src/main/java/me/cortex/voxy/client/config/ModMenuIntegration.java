package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            //Sodium is optional, keep all sodium class references inside SodiumScreen so this class always loads
            if (VoxyCommon.isAvailable() && FabricLoader.getInstance().isModLoaded("sodium")) {
                return SodiumScreen.create(parent);
            } else {
                return null;
            }
        };
    }

    private static final class SodiumScreen {
        static Screen create(Screen parent) {
            var page = (net.caffeinemc.mods.sodium.client.config.structure.OptionPage) net.caffeinemc.mods.sodium.client.config.ConfigManager.CONFIG.getModOptions().stream().filter(a -> a.configId().equals("voxy")).findFirst().get().pages().get(0);
            return net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen.createScreen(parent, page);
        }
    }
}
