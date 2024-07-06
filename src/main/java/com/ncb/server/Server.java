package com.ncb.server;


import com.ncb.nhaccuaban.Models.RoomModel;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private static final int PORT = 55555;
    private static final ConcurrentHashMap<String, RoomModel> rooms = new ConcurrentHashMap<>();
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            close();
        }));

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening on port " + PORT);

            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                System.out.println(rooms);
                threadPool.execute(new ClientHandler(socket, rooms));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void close() {
        try {
            threadPool.shutdown();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
