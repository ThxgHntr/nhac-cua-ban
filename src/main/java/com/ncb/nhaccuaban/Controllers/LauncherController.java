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
    private static final String SERVER_ADDRESS = "192.168.1.6";
    private InetAddress ip;
    private int id;
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
        tfName.textProperty().addListener((_, oldValue, newValue) -> {
            if (newValue != null && newValue.length() > 25) {
                tfName.setText(oldValue);
            }
        });

        UnaryOperator<TextFormatter.Change> filter = change -> {
            String text = change.getText();
            if (text.matches("[0-9]*")) {
                int newValue = Integer.parseInt(!change.getControlNewText().isBlank() ? change.getControlNewText() : "0");
                if (newValue >= 1 && newValue <= 16777214) {
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

            if (!tfRoomId.getText().trim().isBlank()) {
                id = Integer.parseInt(tfRoomId.getText().trim());

                String MULTICAST_ADDRESS = generateMulticastIP(Integer.parseInt(tfRoomId.getText().trim()));
//                String MULTICAST_ADDRESS = "230.0.0." + tfRoomId.getText().trim();
                ip = InetAddress.getByName(MULTICAST_ADDRESS);
            }

            if (ip.isMulticastAddress() && id > 0 && !name.isEmpty()) {
                room = new RoomModel(ip, id, InetAddress.getLocalHost());
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

    public String generateMulticastIP(int number) {
        // Địa chỉ IP multicast cơ sở và tối đa
        long multicastBase = (230L << 24) | 1; // 230.0.0.1
        long multicastMax = (239L << 24) | (255L << 16) | (255L << 8) | 255L; // 239.255.255.255

        // Kiểm tra phạm vi số nguyên hợp lệ
        if (number < 0 || number > (multicastMax - multicastBase)) {
            displayAlert();
            throw new IllegalArgumentException("Number is out of valid range. It must be between 0 and " + (multicastMax - multicastBase));
        }

        // Tính toán địa chỉ IP multicast từ số nguyên
        long ipNumber = multicastBase + number;

        // Chuyển đổi số nguyên thành địa chỉ IP
        return String.format("%d.%d.%d.%d",
                (ipNumber >> 24) & 0xFF,
                (ipNumber >> 16) & 0xFF,
                (ipNumber >> 8) & 0xFF,
                ipNumber & 0xFF);
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
                } else {
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