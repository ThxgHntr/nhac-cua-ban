package com.ncb.nhaccuaban.Controllers;

import com.ncb.nhaccuaban.Models.CommandModel;
import com.ncb.nhaccuaban.Models.RoomModel;
import com.ncb.nhaccuaban.Application;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.stage.Stage;
import javafx.util.converter.IntegerStringConverter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.function.UnaryOperator;

public class LauncherController {
    private static final String SERVER_ADDRESS = "localhost";
    private static final String MULTICAST_GROUP_IP = "230.0.0.0";
    private static final int SERVER_PORT = 55555;
    private Socket socket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    private String name;
    private RoomModel room;

    public Button btnCreate;
    public Button btnJoin;
    public TextField tfRoomId;
    public TextField tfName;

    public void setupInputCheck() {
        tfName.textProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue.length() > 25) {
                tfName.setText(oldValue);
            }
        });

        UnaryOperator<TextFormatter.Change> filter = change -> {
            String text = change.getText();
            if (text.matches("[0-9]*")) {
                int newValue = Integer.parseInt(!change.getControlNewText().isBlank() ? change.getControlNewText() : "0");
                if (newValue >= 0 && newValue <= 65535) {
                    return change;
                }
            }
            return null;
        };

        TextFormatter<Integer> formatter = new TextFormatter<>(new IntegerStringConverter(), null, filter);

        tfRoomId.setTextFormatter(formatter);
    }

    private void sendRequestAndOpen(String request, boolean isHost) {
        try {
            name = tfName.getText().trim();

            InetAddress ip = InetAddress.getByName(MULTICAST_GROUP_IP);
            int port = Integer.parseInt(tfRoomId.getText().trim().isBlank() ? "0" : tfRoomId.getText().trim());

            if (port > 0) {
                room = new RoomModel(port, ip);
            } else {
                displayAlert();
            }

            CommandModel command = new CommandModel(request, name, room);

            socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(command);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Thread clientThread = clientThread(isHost);
        clientThread.start();
    }

    public Thread clientThread(boolean isHost) {
        return new Thread(() -> {
            boolean response;
            try {
                ois = new ObjectInputStream(socket.getInputStream());
                response = ois.readBoolean();
                System.out.println(response);
                if (response) {
                    runClient(isHost);
                }
                else {
                    displayAlert();
                }
            } catch (IOException e) {
                displayAlert();
            }
        });
    }

    public void displayAlert() {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);

            alert.setTitle("Error");
            alert.setHeaderText(null);
            alert.setContentText("Room or username is not valid");

            // Hiển thị dialog và chờ người dùng đóng
            alert.showAndWait();
        });
    }

    public synchronized void runClient(boolean isHost) {
        Platform.runLater(() -> {
            //Get the current Stage
            Stage currentStage = (Stage) btnCreate.getScene().getWindow();
            currentStage.close();

            Stage stage = new Stage();
            FXMLLoader fxmlLoader = new FXMLLoader(Application.class.getResource("client.fxml"));
            try {
                Scene scene = new Scene(fxmlLoader.load());
                ClientController controller = fxmlLoader.getController();
                controller.initData(isHost, name, room);
                stage.setTitle("Listen Together");
                stage.setScene(scene);
                stage.setOnCloseRequest(_ -> leaveRoom());
                stage.setMaximized(true);
                stage.show();
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private synchronized void leaveRoom() {
        CommandModel command = new CommandModel("L", name, room);
        try {
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.writeObject(command);
            oos.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Platform.exit();
        System.exit(0);
    }

    @FXML
    protected void createRoom() {
        sendRequestAndOpen("C", true);
    }

    @FXML
    protected void joinRoom() {
        sendRequestAndOpen("U", false);
    }
}