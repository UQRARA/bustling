package dev.bustling;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class WorldClient {
    private static boolean busRegistered;
    private WorldClient() {}

    public static void registerBus(IEventBus bus) {
        if (!busRegistered) {
            busRegistered = true;
            bus.addListener(ToolModels::register);
            bus.addListener(ToolModels::wrapDeviceCarrier);
            bus.addListener(ToolModels::bakingCompleted);
        }
    }

    public static void install() {
        WorldSystem.load();
        WorldHooks.openPanel = WorldClient::openWorldPanel;
        WorldHooks.openDiffuser = DiffuserScreen::open;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, ClientToolInput::use);
        NeoForge.EVENT_BUS.addListener(WorldClient::onLogin);
        NeoForge.EVENT_BUS.addListener(WorldClient::onLogout);
        NeoForge.EVENT_BUS.addListener(DiffuserClient::draw);
        NeoForge.EVENT_BUS.addListener(WandLaser::draw);
        NeoForge.EVENT_BUS.addListener(PasteEffects::draw);
        NeoForge.EVENT_BUS.addListener(ToolParticles::tick);
        NeoForge.EVENT_BUS.addListener(YsmClientModels::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, YsmClientModels::hideNameTag);
        NeoForge.EVENT_BUS.addListener(ToolMotion::advance);
        NeoForge.EVENT_BUS.addListener(ToolAim::beginFrame);
        WorldSystem.onRuntimeReset = WorldClient::clear;
    }

    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientCrowdStore.resetSession();
        ClientSkinPacks.reload();
    }
    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PasteEffects.clear();
        ClientSkinPacks.clearRemote();
        YsmClientModels.clear();
        ClientCrowdStore.resetSession();
    }

    private static void openWorldPanel() {
        ToolSounds.gear();
        ToolParticles.startWorldRing();
        WorldScreen.open();
    }

    public static void clear() {
        CrowdPresence.clear();
        WandLaser.clear();
        PasteEffects.clear();
        ToolParticles.clear();
        CrowdNavigation.clear();
        CrowdFootsteps.clear();
        MobMotion.clear();
        YsmClientModels.clearProxies();
        RenderPolicy.clear();
        CachedShadowCaster.clear();
        CrowdRenderer.clearRuntime();
    }
}
