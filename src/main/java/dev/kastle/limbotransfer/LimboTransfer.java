package dev.kastle.limbotransfer;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.commands.TabCompletor;
import com.loohp.limbo.events.EventHandler;
import com.loohp.limbo.events.Listener;
import com.loohp.limbo.events.player.PlayerJoinEvent;
import com.loohp.limbo.events.player.PlayerQuitEvent;
import com.loohp.limbo.network.ClientConnection;
import com.loohp.limbo.network.protocol.packets.PacketOut;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.plugins.LimboPlugin;
import com.loohp.limbo.scheduler.LimboTask;
import com.loohp.limbo.utils.DataTypeIO;
import net.md_5.bungee.api.ChatColor;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LimboTransfer extends LimboPlugin implements Listener {

    public static int PLAY_TRANSFER_ID = -1;
    public static int CONFIG_TRANSFER_ID = -1;

    private final Map<UUID, TransferInfo> pendingTransfers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        loadTransferPacketIds();

        Limbo.getInstance().getEventsManager().registerEvents(this, this);

        if (PLAY_TRANSFER_ID != -1) {
            Limbo.getInstance().getConsole().sendMessage(
                    "§a[LimboTransfer] Loaded PLAY transfer ID: " + String.format("0x%02X", PLAY_TRANSFER_ID));
        } else {
            Limbo.getInstance().getConsole()
                    .sendMessage("§c[LimboTransfer] Failed to load PLAY transfer ID. Using fallback.");
            throw new RuntimeException("Failed to load PLAY transfer ID from packets.json");
        }

        if (CONFIG_TRANSFER_ID != -1) {
            Limbo.getInstance().getConsole().sendMessage("§a[LimboTransfer] Loaded CONFIGURATION transfer ID: "
                    + String.format("0x%02X", CONFIG_TRANSFER_ID));
        } else {
            Limbo.getInstance().getConsole()
                    .sendMessage("§c[LimboTransfer] Failed to load CONFIGURATION transfer ID. Using fallback.");
            throw new RuntimeException("Failed to load CONFIGURATION transfer ID from packets.json");
        }

        Limbo.getInstance().getPluginManager().registerCommands(this, new Commands());
    }

    private void loadTransferPacketIds() {
        try (InputStream inputStream = Limbo.class.getClassLoader().getResourceAsStream("reports/packets.json")) {
            if (inputStream == null)
                return;

            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                JSONObject json = (JSONObject) new JSONParser().parse(reader);

                // Path: play -> clientbound -> minecraft:transfer -> protocol_id
                PLAY_TRANSFER_ID = extractId(json, "play", "minecraft:transfer");

                // Path: configuration -> clientbound -> minecraft:transfer -> protocol_id
                CONFIG_TRANSFER_ID = extractId(json, "configuration", "minecraft:transfer");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int extractId(JSONObject root, String phase, String packetName) {
        try {
            JSONObject phaseObj = (JSONObject) root.get(phase);
            if (phaseObj == null)
                return -1;

            JSONObject clientbound = (JSONObject) phaseObj.get("clientbound");
            if (clientbound == null)
                return -1;

            JSONObject packet = (JSONObject) clientbound.get(packetName);
            if (packet == null)
                return -1;

            return ((Number) packet.get("protocol_id")).intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    public void transfer(Player player, String host, int port) {
        ClientConnection.ClientState state = player.clientConnection.getClientState();

        if (state == ClientConnection.ClientState.LOGIN || state == ClientConnection.ClientState.HANDSHAKE) {
            pendingTransfers.put(player.getUniqueId(), new TransferInfo(host, port));
            Limbo.getInstance().getConsole().sendMessage(
                    "§e[LimboTransfer] Scheduled transfer for " + player.getName() + " (State: " + state + ")");
            return;
        }

        try {
            int packetId;

            if (state == ClientConnection.ClientState.PLAY) {
                packetId = PLAY_TRANSFER_ID;
            } else if (state == ClientConnection.ClientState.CONFIGURATION) {
                packetId = CONFIG_TRANSFER_ID;
            } else {
                return;
            }

            ClientboundTransferPacket transferPacket = new ClientboundTransferPacket(host, port, packetId, state);
            player.clientConnection.sendPacket(transferPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        TransferInfo info = pendingTransfers.remove(player.getUniqueId());

        if (info != null) {
            Limbo.getInstance().getConsole()
                    .sendMessage("§a[LimboTransfer] Executing scheduled transfer for " + player.getName());
            transfer(player, info.host, info.port);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingTransfers.remove(event.getPlayer().getUniqueId());
    }

    public void transferAll(String host, int port, int batchDelayTicks, int batchSize) {
        if (batchDelayTicks <= 0 || batchSize <= 0) {
            for (Player player : Limbo.getInstance().getPlayers()) {
                transfer(player, host, port);
            }
        } else {
            long totalDelay = 0;
            Set<Player> currentBatch = new HashSet<>();
            List<Player> players = new ArrayList<>(Limbo.getInstance().getPlayers());

            for (int i = 0; i < players.size(); i++) {
                currentBatch.add(players.get(i));
                if (currentBatch.size() < batchSize && i != players.size() - 1) {
                    continue;
                }
                Set<Player> batch = new HashSet<>(currentBatch);
                LimboTask task = () -> {
                    for (Player p : batch) {
                        transfer(p, host, port);
                    }
                };
                Limbo.getInstance().getScheduler().runTaskLater(this, task, totalDelay);
                totalDelay += batchDelayTicks;
                currentBatch.clear();
            }
        }
    }

    class Commands implements CommandExecutor, TabCompletor {
        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args[0].equalsIgnoreCase("transfer")) {
                if (sender.hasPermission("limbotransfer.transfer")) {
                    if (args.length == 4) {
                        Player player = Limbo.getInstance().getPlayer(args[1]);
                        if (player != null) {
                            String host = args[2];
                            int port;
                            try {
                                port = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                sender.sendMessage(ChatColor.RED + "Invalid port number!");
                                return;
                            }
                            transfer(player, host, port);
                            sender.sendMessage(
                                    ChatColor.GOLD + "Transferred " + player.getName() + " to " + host + ":" + port);
                        } else {
                            sender.sendMessage(ChatColor.RED + "Player is not online!");
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Invalid usage! Use: /transfer <player> <host> <port>");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use that command!");
                }
                return;
            }

            if (args[0].equalsIgnoreCase("transferall")) {
                if (sender.hasPermission("limbotransfer.transfer")) {
                    if (args.length >= 3 && args.length <= 5) {
                        String host = args[1];
                        int port;
                        int batchDelay;
                        int batchSize;
                        try {
                            port = Integer.parseInt(args[2]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "Invalid port!");
                            return;
                        }
                        if (args.length == 4) {
                            try {
                                batchDelay = Integer.parseInt(args[3]);
                            } catch (NumberFormatException e) {
                                sender.sendMessage(ChatColor.RED + "Invalid batch delay ticks!");
                                return;
                            }
                            batchSize = 1;
                        } else if (args.length == 5) {
                            try {
                                batchDelay = Integer.parseInt(args[3]);
                                batchSize = Integer.parseInt(args[4]);
                            } catch (NumberFormatException e) {
                                sender.sendMessage(ChatColor.RED + "Invalid batch delay ticks or size!");
                                return;
                            }
                        } else {
                            batchDelay = 1;
                            batchSize = 10;
                        }
                        transferAll(host, port, batchDelay, batchSize);
                        sender.sendMessage(ChatColor.GOLD + "Transferred all players to " + host + ":" + port);
                    } else {
                        sender.sendMessage(ChatColor.RED
                                + "Invalid usage! Use: /transferall <host> <port> [<batchDelayTicks> <batchSize>]");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to use that command!");
                }
                return;
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String[] args) {
            if (!sender.hasPermission("limbotransfer.transfer")) {
                return Collections.emptyList();
            }

            List<String> tab = new ArrayList<>();

            if (args.length == 0) {
                tab.add("transfer");
                tab.add("transferall");
            }

            if (args.length == 1) {
                if ("transfer".startsWith(args[0].toLowerCase())) {
                    tab.add("transfer");
                }
                if ("transferall".startsWith(args[0].toLowerCase())) {
                    tab.add("transferall");
                }
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("transfer")) {
                for (Player player : Limbo.getInstance().getPlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        tab.add(player.getName());
                    }
                }
            }

            return tab;
        }
    }

    class ClientboundTransferPacket extends PacketOut {
        private final String host;
        private final int port;
        private final int packetId;
        private final ClientConnection.ClientState state;

        public ClientboundTransferPacket(String host, int port, int packetId, ClientConnection.ClientState state) {
            this.host = host;
            this.port = port;
            this.packetId = packetId;
            this.state = state;
        }

        @Override
        public byte[] serializePacket() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            DataOutputStream output = new DataOutputStream(buffer);
            output.writeByte(packetId); // Use the ID specific to the current phase
            DataTypeIO.writeString(output, host, StandardCharsets.UTF_8);
            DataTypeIO.writeVarInt(output, port);

            return buffer.toByteArray();
        }

        @Override
        public ClientConnection.ClientState getPacketState() {
            return state; // Correctly report the state this packet was intended for
        }
    }

    private static class TransferInfo {
        final String host;
        final int port;

        public TransferInfo(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
