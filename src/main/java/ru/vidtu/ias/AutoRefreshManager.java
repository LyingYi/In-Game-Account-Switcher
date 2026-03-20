package ru.vidtu.ias;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.account.Account;
import ru.vidtu.ias.account.MicrosoftAccount;
import ru.vidtu.ias.auth.LoginData;
import ru.vidtu.ias.auth.handlers.LoginHandler;
import ru.vidtu.ias.config.IASStorage;
import ru.vidtu.ias.utils.exceptions.FriendlyException;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles automatic token refresh and reconnect flow.
 */
public final class AutoRefreshManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS/AutoRefresh");
    public static final SystemToast.SystemToastId TOKEN_REFRESH = new SystemToast.SystemToastId(10001L);

    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    private static volatile String lastServerAddress;
    private static volatile String lastServerName;

    private AutoRefreshManager() {
        throw new AssertionError("No instances.");
    }

    public static void rememberServer(@NotNull ServerAddress address, @Nullable ServerData serverData) {
        lastServerAddress = address.toString();
        lastServerName = serverData != null ? serverData.name : address.toString();
    }

    public static void tryRefreshExpiredToken(@NotNull Minecraft minecraft, @NotNull Screen parent, @NotNull Component reason) {
        if (!isTokenExpiredMessage(reason) || !REFRESHING.compareAndSet(false, true)) return;

        MicrosoftAccount account = currentMicrosoftAccount(minecraft.getUser());
        if (account == null) {
            REFRESHING.set(false);
            return;
        }

        String address = lastServerAddress;
        if (address == null || address.isBlank()) {
            REFRESHING.set(false);
            return;
        }

        LOGGER.info("IAS: Token-expiry disconnect detected, trying silent token refresh for {}.", account.name());
        minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, TOKEN_REFRESH,
                Component.literal("In-Game Account Switcher"),
                Component.literal("Refreshing Microsoft token...")));

        loginSilently(account).thenCompose(result -> {
            if (result.changed) {
                IAS.disclaimersStorage();
                IAS.saveStorage();
            }

            return IASMinecraft.account(minecraft, result.data);
        }).whenComplete((ok, error) -> minecraft.execute(() -> {
            try {
                if (error != null) {
                    if (isSilentPasswordRequired(error)) {
                        LOGGER.info("IAS: Silent token refresh requires password UI for account {}. Falling back to manual login.", account.name());
                        minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, TOKEN_REFRESH,
                                Component.literal("In-Game Account Switcher"),
                                Component.literal("Token refresh needs password. Please login manually.")));
                        return;
                    }

                    LOGGER.error("IAS: Auto token refresh failed.", error);
                    minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, TOKEN_REFRESH,
                            Component.literal("In-Game Account Switcher"),
                            Component.literal("Token refresh failed.")));
                    return;
                }

                minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, TOKEN_REFRESH,
                        Component.literal("In-Game Account Switcher"),
                        Component.literal("Token refreshed. Reconnecting...")));
                reconnectToLastServer(minecraft, parent);
            } finally {
                REFRESHING.set(false);
            }
        }));
    }

    private static @Nullable MicrosoftAccount currentMicrosoftAccount(@Nullable User user) {
        if (user == null) return null;
        for (Account account : IASStorage.ACCOUNTS) {
            if (!(account instanceof MicrosoftAccount ms)) continue;
            if (Objects.equals(ms.skin(), user.getProfileId()) || Objects.equals(ms.name(), user.getName())) return ms;
        }
        return null;
    }

    private static boolean isTokenExpiredMessage(@NotNull Component reason) {
        String value = reason.getString().toLowerCase(Locale.ROOT);
        return value.contains("token") || value.contains("session") || value.contains("expired") || value.contains("invalid") || value.contains("401");
    }

    private static CompletableFuture<LoginResult> loginSilently(@NotNull MicrosoftAccount account) {
        if (!account.canLoginSilently()) {
            return CompletableFuture.failedFuture(new FriendlyException("Silent token refresh requires password.", "ias.error.password"));
        }

        CompletableFuture<LoginResult> out = new CompletableFuture<>();
        account.login(new LoginHandler() {
            @Override
            public boolean cancelled() {
                return false;
            }

            @Override
            public void stage(@NotNull String stage, Object @NotNull ... args) {
                // no-op
            }

            @Override
            public @NotNull CompletableFuture<String> password() {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new FriendlyException("No password UI for silent refresh.", "ias.error.password"));
                return future;
            }

            @Override
            public void success(@NotNull LoginData data, boolean changed) {
                out.complete(new LoginResult(data, changed));
            }

            @Override
            public void error(@NotNull Throwable error) {
                out.completeExceptionally(error);
            }
        });
        return out;
    }

    private static boolean isSilentPasswordRequired(@NotNull Throwable error) {
        FriendlyException friendly = FriendlyException.friendlyInChain(error);
        if (friendly != null && "ias.error.password".equals(friendly.key())) {
            return true;
        }

        Throwable current = error;
        while (current != null) {
            final Throwable cur = current;
            if (current instanceof IllegalStateException state && "No password UI for silent refresh.".equals(state.getMessage())) {
                return true;
            }
            current = switch (current) {
                case CompletionException completion when completion.getCause() != null -> completion.getCause();
                case RuntimeException runtime when runtime.getCause() != null && runtime.getCause() != cur -> runtime.getCause();
                default -> null;
            };
        }
        return false;
    }

    private static void reconnectToLastServer(@NotNull Minecraft minecraft, @NotNull Screen parent) {
        String address = lastServerAddress;
        if (address == null || address.isBlank()) return;

        try {
            ServerAddress parsed = ServerAddress.parseString(address);
            ServerData data = new ServerData(lastServerName != null ? lastServerName : address, address, ServerData.Type.OTHER);

            ConnectScreen.startConnecting(parent, minecraft, parsed, data, false, (TransferState) null);
        } catch (Throwable t) {
            LOGGER.error("IAS: Unable to reconnect to previous server {}.", address, t);
        }
    }

    private record LoginResult(@NotNull LoginData data, boolean changed) {
    }
}
