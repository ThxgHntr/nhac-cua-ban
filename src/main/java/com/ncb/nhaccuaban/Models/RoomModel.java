package com.ncb.nhaccuaban.Models;

import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;

public record RoomModel(int port, InetAddress ip) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String toString() {
        return ip.toString() + ":" + port;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        RoomModel other = (RoomModel) obj;

        return ip.equals(other.ip) && port == other.port;
    }
}
