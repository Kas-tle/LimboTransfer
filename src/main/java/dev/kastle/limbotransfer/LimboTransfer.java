package dev.kastle.limbotransfer;

import com.loohp.limbo.Limbo;
import com.loohp.limbo.commands.CommandExecutor;
import com.loohp.limbo.commands.CommandSender;
import com.loohp.limbo.commands.TabCompletor;
import com.loohp.limbo.network.ClientConnection;
import com.loohp.limbo.network.protocol.packets.PacketOut;
import com.loohp.limbo.player.Player;
import com.loohp.limbo.plugins.LimboPlugin;
import com.loohp.limbo.scheduler.LimboTask;
import com.loohp.limbo.utils.DataTypeIO;
import net.md_5.bungee.api.ChatColor;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LimboTransfer extends LimboPlugin {
    @Override
    public void onEnable() {
        Limbo.getInstance().getPluginManager().registerCommands(this, new Commands());
    }

    public void transfer(Player player, String host, int port) {
        try {
            ClientboundTransferPacket transferPacket = new ClientboundTransferPacket(host, port);
            player.clientConnection.sendPacket(transferPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
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

        public ClientboundTransferPacket(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public byte[] serializePacket() throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            DataOutputStream output = new DataOutputStream(buffer);
            output.writeByte(0x7A); // Transfer packet ID in Play state
            DataTypeIO.writeString(output, host, StandardCharsets.UTF_8);
            DataTypeIO.writeVarInt(output, port);

            return buffer.toByteArray();
        }

        @Override
        public ClientConnection.ClientState getPacketState() {
            return ClientConnection.ClientState.PLAY;
        }
    }
}
