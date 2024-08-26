package PlayerVsPlayer.net;

import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

import PlayerVsPlayer.lobby.Lobby;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import arc.Events;
import arc.func.Cons;

public class NetClientLobby implements Runnable {
    public static Map<String, NetClientLobby> uuidClientHandles = new HashMap<>();
    public static Map<String, Cons<String>> methodsClient = new HashMap<>();
    public static Lobby lobby = null;

    private Socket socket;
    private BufferedReader reader; // Receive messages
    private BufferedWriter writer; // Read messages
    private String uuid = null;

    private Integer idlePlayerCount = 0;
    private boolean hasHeartbeat = false;
    private int hbTimeoutSeconds = 0;
    private Thread hbThread = null;

    private Phaser phaser = null;

    private Thread wcThread = null;

    public NetClientLobby(Lobby _lobby) {
        lobby = _lobby;
        this.addMethodClient("uuid", (c) -> {
            uuid = c;
        });
    }

    public NetClientLobby(Socket socket) {
        // Check correct Init
        if (lobby == null || methodsClient.get("uuid") == null) {
            System.out.println("NetClientLobby is not yet initialized, revert");
            return;
        }

        this.phaser = new Phaser(0);

        // Try connection setup
        try {
            this.socket = socket;
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Timeout and revert if uuid is not present
            Thread uuidReadThread = new Thread(() -> {
                try {
                    TimeUnit.SECONDS.sleep(10);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    this.closeSocket();
                    return;
                }

                if (uuid != null) {
                    return;
                }

                System.out.println("uuid timed out");
                this.closeSocket();
                return;
            });
            uuidReadThread.start();

            // Wait for starting uuid message
            String waitUuidMessage = null;
            while (waitUuidMessage == null) {
                waitUuidMessage = this.reader.readLine();
                this.handleSocketMessage(waitUuidMessage);
            }

            // Kick if uuid doesn't match
            if (!lobby.isPlayerInTheLobby(uuid)) {
                throw new Exception("uuid not valid, disconnecting the client");
            }

            // Add socket, initialize and start heartbeat timeout
            uuidClientHandles.put(uuid, this);
            this.hbTimeoutSeconds = 30; // 30 seconds
            this.idlePlayerCount = 0;

            this.hbThread = new Thread(() -> {
                while (!this.socket.isClosed() && this.hbThread != null) {
                    try {
                        if (this.phaser.getUnarrivedParties() != 0) { // no registration
                            this.phaser.awaitAdvance(this.phaser.getPhase());
                        }

                        if (this.hbThread != null) {
                            Thread.sleep(this.hbTimeoutSeconds * 1000);

                            this.checkHeartbeat();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        this.closeSocket();
                        break;
                    }
                }

                return;
            });
            this.hbThread.start();

            System.out.println("Client init completed, uuid: " + this.uuid);
            Events.fire(new NewPlayerSocket(this.uuid));
            this.hasHeartbeat = true;
        } catch (Exception e) {
            e.printStackTrace();
            this.closeSocket();
        }
    }

    /*
     * NewPlayerSocket is an event fired as soon as a new clinet socket has been
     * initialized correctly
     */
    public class NewPlayerSocket {
        final String uuid;

        public NewPlayerSocket(String _uuid) {
            this.uuid = _uuid;
        }
    }

    public void waitConnection(String uuid) {
        this.wcThread = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                ie.getStackTrace();
            }

            if (uuidClientHandles.get(uuid) == null) {
                lobby.deletePlayer(uuid);
                return;
            }
        });

        this.wcThread.start();
    }

    /**
     * checkHeartBeat periodically checks based on hbTimeout if the player has
     * heartbeeted or if he is idle (has not betted in 5 minutes), if not it kicks
     * him
     */
    private void checkHeartbeat() {
        System.out.println("User heartbeat check");

        if ((!this.hasHeartbeat || this.idlePlayerCount > 10) && !lobby.hasPlayerConfirmedTheBet(uuid)) {
            if (!this.hasHeartbeat) {
                this.sendPacket("HeartbeatKick", "heartbeat");
            }

            if (this.idlePlayerCount > 10) {
                this.sendPacket("Idle", "idle");
            }

            System.out.println("Invalid heartbeat");

            lobby.deletePlayerIfNoBet(this.uuid);
            this.closeSocket();
            return;
        }

        System.out.println("Valid heartbeat");
        this.hasHeartbeat = false;
        this.idlePlayerCount += 1;
    }

    public void closeSocket() {
        closeSocket(this.uuid);
    }

    /*
     * closeSocket closes a defined socket
     */
    public void closeSocket(String uuid) {
        NetClientLobby nclInstance = null;

        if (uuid != null) {
            nclInstance = uuidClientHandles.get(uuid);
            uuidClientHandles.put(uuid, null);
        }

        if (nclInstance == null) {
            return;
        }

        System.out.println("Closing a socket, uuid: " + uuid + " IP: " + nclInstance.socket.getInetAddress());
        nclInstance.phaser.register();

        try {
            if (nclInstance.reader != null) {
                nclInstance.reader.close();
                nclInstance.reader = null;
            }
            if (nclInstance.writer != null) {
                nclInstance.writer.close();
                nclInstance.writer = null;
            }
            if (nclInstance.socket != null) {
                nclInstance.socket.close();
            }
            if (nclInstance.hbThread != null) {
                nclInstance.hbThread = null;
                nclInstance.idlePlayerCount = 0;
                nclInstance.hasHeartbeat = false;
                nclInstance.hbTimeoutSeconds = 0;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        nclInstance.phaser.arrive();
    }

    /*
     * handleSocketMessage takes a message and handles it with three standard
     * methods: uuid, CloseSocket and Heartbeat or a dinamically defined method.
     */
    private void handleSocketMessage(String sm) {
        if (sm == null) {
            return;
        }

        System.out.println("new socket message: " + sm);

        String[] sections = sm.split("(!type:|!content:)");

        if ("Heartbeat".equals(sections[1])) {
            System.out.println("User heartbeated");
            this.hasHeartbeat = true;
        }

        if ("CloseSocket".equals(sections[1])) {
            lobby.deletePlayerIfNoBet(this.uuid);
            this.closeSocket();
            return;
        }

        if ("uuid".equals(sections[1])) {
            this.uuid = sections[2];
            return;
        }

        Cons<String> method = methodsClient.get(sections[1]);

        if (method != null) {
            method.get(sections[2]);
        }
    }

    /*
     * sendPacket takes the packet type and content, creates the message and sends
     * it.
     */
    public void sendPacket(String type, String content) {
        if (this.socket.isClosed() || this.writer == null) {
            System.out.println("Can't send a message, type: " + type);
            return;
        }

        String message = "!type:" + type + "!content:" + content;

        try {
            this.writer.write(message);
            this.writer.newLine();
            this.writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
            this.closeSocket();
        }
    }

    /*
     * addMethodClient adds runnable methods that clients can call through the
     * socket
     */
    public void addMethodClient(String type, Cons<String> method) {
        methodsClient.put(type, method);
    }

    @Override
    public void run() {
        while (!this.socket.isClosed() && this.reader != null) {
            try {
                // Might be a broken synching solution but it works great
                if (this.phaser.getUnarrivedParties() != 0) { // no registration
                    this.phaser.awaitAdvance(this.phaser.getPhase());
                }

                if (this.reader == null) {
                    break;
                }

                handleSocketMessage(this.reader.readLine());
            } catch (Exception ioe) {
                ioe.printStackTrace();
                lobby.deletePlayerIfNoBet(this.uuid);
                this.closeSocket();
                break;
            }
        }
    }
}
