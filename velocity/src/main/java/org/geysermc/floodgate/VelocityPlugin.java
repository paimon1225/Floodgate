package org.geysermc.floodgate;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import lombok.Getter;
import net.kyori.text.TextComponent;
import org.geysermc.floodgate.HandshakeHandler.HandshakeResult;
import org.geysermc.floodgate.command.LinkAccountCommand;
import org.geysermc.floodgate.command.UnlinkAccountCommand;
import org.geysermc.floodgate.injector.VelocityInjector;
import org.geysermc.floodgate.util.CommandUtil;
import org.geysermc.floodgate.util.ReflectionUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.geysermc.floodgate.util.ReflectionUtil.*;

public class VelocityPlugin {
    @Getter private VelocityFloodgateConfig config;
    @Getter private PlayerLink playerLink;
    private HandshakeHandler handshakeHandler;

    private Set<InboundConnection> workingSet;
    private Cache<InboundConnection, FloodgatePlayer> playerCache;
    private Cache<InboundConnection, String> playersToKick;

    private final ProxyServer server;
    private final Logger logger;
    private boolean injectSucceed;

    @Inject
    public VelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        // we're too late if we would do this in the init event
        injectSucceed = false;
        try {
            injectSucceed = VelocityInjector.inject(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.logger = logger;
        if (!injectSucceed) return;

        this.workingSet = new HashSet<>();
        this.playersToKick = CacheBuilder.newBuilder()
                .maximumSize(3000)
                .expireAfterWrite(50, TimeUnit.SECONDS)
                .build();
        this.playerCache = CacheBuilder.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(50, TimeUnit.SECONDS)
                .build();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        if (injectSucceed) {
            logger.info("Floodgate injection process succeeded!");
        } else {
            logger.severe("Failed to inject! Floodgate won't do anything.");
            return;
        }

        File dataFolder = new File("plugins/floodgate/");
        if (!dataFolder.exists()) {
            dataFolder.mkdir();
        }

        config = FloodgateConfig.load(logger, dataFolder.toPath().resolve("config.yml"), VelocityFloodgateConfig.class);
        playerLink = PlayerLink.initialize(logger, dataFolder.toPath(), config);
        handshakeHandler = new HandshakeHandler(config.getPrivateKey(), true, config.getUsernamePrefix(), config.isReplaceSpaces());

        CommandUtil commandUtil = new CommandUtil();
        server.getCommandManager().register(CommandUtil.LINK_ACCOUNT_COMMAND, new LinkAccountCommand(playerLink, commandUtil));
        server.getCommandManager().register(CommandUtil.UNLINK_ACCOUNT_COMMAND, new UnlinkAccountCommand(playerLink, commandUtil));
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onConnectionHandshake(ConnectionHandshakeEvent event) {
        if (!injectSucceed) return;
        workingSet.add(event.getConnection());

        try {
            Object cachedHandshake = ReflectionUtil.getValue(event.getConnection(), handshakeField);
            String handshakeData = ReflectionUtil.getCastedValue(cachedHandshake, handshakeAddressField, String.class);

            HandshakeResult result = handshakeHandler.handle(handshakeData);
            switch (result.getResultType()) {
                case SUCCESS:
                    break;
                case EXCEPTION:
                    playersToKick.put(event.getConnection(), config.getMessages().getInvalidKey());
                    return;
                case INVALID_DATA_LENGTH:
                    playersToKick.put(event.getConnection(), config.getMessages().getInvalidArgumentsLength());
                    return;
                default:
                    return;
            }

            FloodgatePlayer player = result.getFloodgatePlayer();
            FloodgateAPI.addEncryptedData(player.getCorrectUniqueId(), result.getHandshakeData()[2] + '\0' + result.getHandshakeData()[3]);
            playerCache.put(event.getConnection(), player);
            logger.info("Added " + player.getCorrectUsername() + " " + player.getCorrectUniqueId());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            workingSet.remove(event.getConnection());
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPreLogin(PreLoginEvent event) {
        if (!injectSucceed) return;

        if (workingSet.contains(event.getConnection())) {
            int count = 70;
            // 70 * 70 / 1000 = 4.9 seconds to get removed from working cache
            while (count-- != 0 && workingSet.contains(event.getConnection())) {
                // should be 'just fine' because the event system is multithreaded
                try {
                    Thread.sleep(70);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (workingSet.contains(event.getConnection())) {
                String message = "Took more then 4.9 seconds (after PreLoginEvent) to finish Handshake data for "+event.getUsername();
                logger.warning(message);
                event.setResult(PreLoginComponentResult.denied(TextComponent.of(message)));
                return;
            }
        }

        FloodgatePlayer player = playerCache.getIfPresent(event.getConnection());
        if (player != null) {
            System.out.println(event.getUsername());
            event.setResult(PreLoginComponentResult.forceOfflineMode());

            // we can't rely on Velocity when it comes to kicking the old players, so with this system we only
            // have to check if the connection (which is already closed at that time) has a FloodgatePlayer as attribute
            try {
                Object mcConnection = initialMinecraftConnectionField.get(event.getConnection());
                Channel channel = (Channel) channelField.get(mcConnection);
                channel.attr(playerAttribute).set(player);
                return;
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to add FloodgatePlayer to player", e);
                playersToKick.put(event.getConnection(), "Failed to add FloodgatePlayer to player");
            }
        }

        String message = playersToKick.getIfPresent(event.getConnection());
        if (message != null) {
            playersToKick.invalidate(event.getConnection());
            event.setResult(PreLoginComponentResult.denied(TextComponent.of(message)));
        }
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        if (!injectSucceed) return;

        FloodgatePlayer player = playerCache.getIfPresent(event.getConnection());
        if (player != null) {
            playerCache.invalidate(event.getConnection());
            event.setGameProfile(new GameProfile(player.getCorrectUniqueId(), player.getCorrectUsername(), new ArrayList<>()));
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();

        try {
            Object minecraftConnection = minecraftConnectionField.get(player);
            Channel channel = (Channel) channelField.get(minecraftConnection);
            FloodgatePlayer fPlayer = channel.attr(playerAttribute).get();
            if (fPlayer != null) {
                FloodgateAPI.removePlayer(fPlayer);
                FloodgateAPI.removeEncryptedData(event.getPlayer().getUniqueId());
                logger.info("Removed Bedrock player who was logged in as " + player.getUsername() + " " + player.getUniqueId());
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to remove the player", e);
        }
    }

    private Field handshakeField;
    private Field handshakeAddressField;

    private final AttributeKey<FloodgatePlayer> playerAttribute = AttributeKey.newInstance("floodgate-player");
    private Field initialMinecraftConnectionField;
    private Field minecraftConnectionField;
    private Field channelField;

    public void initReflection() {
        ReflectionUtil.setPrefix("com.velocitypowered.proxy");
        Class<?> IIC = getPrefixedClass("connection.client.InitialInboundConnection");
        Class<?> HandshakePacket = getPrefixedClass("protocol.packet.Handshake");

        handshakeField = getField(IIC, "handshake");
        handshakeAddressField = getField(HandshakePacket, "serverAddress");

        Class<?> minecraftConnection = getPrefixedClass("connection.MinecraftConnection");
        initialMinecraftConnectionField = getFieldOfType(IIC, minecraftConnection, true);
        Class<?> connectedPlayer = getPrefixedClass("connection.client.ConnectedPlayer");
        minecraftConnectionField = getFieldOfType(connectedPlayer, minecraftConnection, true);
        channelField = getFieldOfType(minecraftConnection, Channel.class, true);
    }
}
