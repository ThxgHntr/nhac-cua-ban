package com.ncb.nhaccuaban.Models;

import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;

public record RoomModel(InetAddress ip, int id) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String toString() {
        return ip.toString() + "#" + id;
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        RoomModel other = (RoomModel) obj;

        return ip.equals(other.ip) && id == other.id;
    }
}
