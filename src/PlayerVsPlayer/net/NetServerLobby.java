package PlayerVsPlayer.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class NetServerLobby {
    private ServerSocket ss = null;
    private Thread socketThread;

    public NetServerLobby(int port) {
        try {
            this.ss = new ServerSocket(port);
        } catch (IOException ioe) {

        }
    }

    public void startServer() {
        this.socketThread = new Thread(() -> {
            System.out.println("Thread server start");
            try {
                while (!ss.isClosed()) {
                    System.out.println("Waiting connection");
                    Socket socket = this.ss.accept();
                    System.out.println("New connection");

                    NetClientLobby ncl = new NetClientLobby(socket);

                    Thread thread = new Thread(ncl);
                    thread.start();
                }
            } catch (IOException e) {
                closeServerSocket();
                return;
            }
            return;
        });

        socketThread.start();
    }

    public void closeServerSocket() {
        try {
            if (this.ss != null) { // && !this.ss.isClosed()
                System.out.println("Closing Server Socket");
                this.ss.close();
                this.ss = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
