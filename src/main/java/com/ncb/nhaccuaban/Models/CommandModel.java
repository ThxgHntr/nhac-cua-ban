package com.ncb.nhaccuaban.Models;

import java.io.Serial;
import java.io.Serializable;

public record CommandModel(String request, String client, RoomModel room) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
