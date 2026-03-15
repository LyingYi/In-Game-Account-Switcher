package ru.vidtu.ias.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.vidtu.ias.AutoRefreshManager;

/**
 * Tracks the last attempted server connection.
 */
@Mixin(ConnectScreen.class)
public final class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void ias$startConnecting$head(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data, boolean quickPlay, @Nullable TransferState transferState, CallbackInfo ci) {
        AutoRefreshManager.rememberServer(address, data);
    }
}
