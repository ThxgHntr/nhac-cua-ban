package com.ncb.server;


import com.ncb.nhaccuaban.Models.CommandModel;
import com.ncb.nhaccuaban.Models.RoomModel;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler extends Thread {
    private final Socket socket;
    private ConcurrentHashMap<String, RoomModel> rooms;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;
    private CommandModel command;
    private String request;
    private String client;
    private RoomModel room;

    public ClientHandler(Socket socket, ConcurrentHashMap<String, RoomModel> rooms) {
        this.socket = socket;
        this.rooms = rooms;
    }

    @Override
    public void run() {
        try {
            while (true) {
                oos = new ObjectOutputStream(socket.getOutputStream());
                ois = new ObjectInputStream(socket.getInputStream());

                command = (CommandModel) ois.readObject();
                request = command.request();
                client = command.client();
                room = command.room();

                switch (request) {

                    // Create room
                    case "C":
                        // Check if client name or room exists
                        if (rooms.containsKey(client) || rooms.contains(room)) {
                            oos.writeBoolean(false);
                        } else {
                            rooms.put(client, room);
                            oos.writeBoolean(true);
                        }
                        oos.flush();
                        break;

                    // Join room
                    case "U":
                        // Check if client name exist or room not exists
                        if (rooms.containsKey(client) || !rooms.contains(room)) {
                            oos.writeBoolean(false);
                        } else {
                            rooms.put(client, room);
                            oos.writeBoolean(true);
                        }
                        oos.flush();
                        break;

                    // Remove client if left
                    case "L":
                        if (rooms.containsKey(client) && rooms.contains(room)) {
                            rooms.remove(client, room);
                        }
                        break;

                    default:
                        System.out.println("Unknown request: " + request);
                        break;
                }
            }
        } catch (Exception e) {
            System.out.println(e.getMessage());
            System.out.println(rooms);
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        try {
            if (ois != null) ois.close();
            if (oos != null) oos.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("Error closing resources: " + e.getMessage());
        }
    }
}
