package com.ncb.nhaccuaban.Controllers;

import com.ncb.nhaccuaban.Models.RoomModel;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ClientController {
    public TextField chatField;
    public TextFlow chatFlow;
    public Button btnSend;
    public Button btnChooseSong;
    public Button btnPrevious;
    public Button btnPlay;
    public Button btnNext;
    public Slider volumeSlider;
    public Label lblCurrentTime;
    public Label lblTotalTime;
    public ProgressBar songProgressBar;
    public Label lblNowPlaying;
    public VBox usersListContainer;
    public ListView<File> songsListView;
    public Label lblRoomId;

    public ArrayList<String> clientList = new ArrayList<>();
    public ObservableList<File> songsList;
    private MulticastSocket ms;
    private DatagramPacket dp;
    public String me;
    private RoomModel room;
    private boolean isHost;
    private Media media;
    private MediaPlayer mediaPlayer;
    private BooleanProperty isPlaying;
    public static IntegerProperty currentSong;
    private Timer timer;
    private TimerTask task;
    private boolean isRunning = false;
    private static final int MULTICAST_PORT = 4444;
    private static final int SERVER_PORT = 12345;
    private static ServerSocket serverSocket;
    private static Socket socket;

    public void initialize() {
    }

    public void initData(boolean isHost, String name, RoomModel room) throws IOException, InterruptedException {
        this.isHost = isHost;
        this.me = name;
        this.room = room;
        this.ms = new MulticastSocket(MULTICAST_PORT);
        this.dp = new DatagramPacket(new byte[1000], 1000);
        songsList = FXCollections.observableArrayList();
        songsListView.setItems(songsList);
        songsListView.setCellFactory(_ -> getFileCell());
        isPlaying = new SimpleBooleanProperty(false);
        currentSong = new SimpleIntegerProperty(-1);

        volumeSlider.setMin(0);
        volumeSlider.setMax(100);
        volumeSlider.setValue(50);
        volumeSlider.valueProperty().addListener((_, _, _) -> {
            if (mediaPlayer != null) {
                mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);
            }
        });
        // Thêm shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> sendRequestCode("L", me, "")));
        lblRoomId.setText("Room ID: " + room.id());

        btnSend.disableProperty().bind(chatField.textProperty().isEmpty());

        if (isHost) {
            audioSender().start();
            btnPlay.disableProperty().bind(currentSong.lessThan(0));
            btnNext.disableProperty().bind(currentSong.lessThan(0));
            btnPrevious.disableProperty().bind(currentSong.lessThan(0));
        } else {
            audioReceiver().start();
            btnChooseSong.setDisable(true);
            btnPlay.setDisable(true);
            btnNext.setDisable(true);
            btnPrevious.setDisable(true);
        }

        InetSocketAddress inetSA = new InetSocketAddress(room.ip(), MULTICAST_PORT);
        NetworkInterface netInt = NetworkInterface.getByInetAddress(room.ip());
        ms.joinGroup(inetSA, netInt);

        sendRequestCode("U", me, "");
        receiveMessage();
    }

    public void onChatAreaKeyPressed(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) { // Send message on enter
            sendMessage();
        }
    }

    // Send request code to other clients
    public void sendRequestCode(String code, String client, String msg) {
        byte[] buffer = (code + ":" + client + ":" + msg).getBytes();
        dp = new DatagramPacket(buffer, buffer.length, room.ip(), MULTICAST_PORT);
        try {
            ms.send(dp);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Receive chat message
    public void receiveMessage() {
        new Thread(() -> {
            while (true) {
                try {
                    byte[] buffer = new byte[1024 * 1024 * 5];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    ms.receive(packet);

                    String receivedMsg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    String[] msgSplit = receivedMsg.split(":", 3);
                    String newCl = msgSplit[1];

                    switch (msgSplit[0]) {
                        // Leave chat
                        case "L":
                            clientList.removeIf(cl -> cl.equals(newCl));
                            displayJoinedLeftUser(newCl, false);
                            loadUsersList();
                            break;

                        // Update client list
                        case "U":
                            if (!clientList.contains(newCl)) {
                                clientList.add(newCl);
                                if (isHost) {
                                    if (!songsList.isEmpty()) {
                                        sendSongsList(me, songsList);
                                    }
                                    if (isRunning) {
                                        sendRequestCode("PLAY", String.valueOf(currentSong.get()), String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
                                        System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());
                                    }

                                    for (String client : clientList) {
                                        sendRequestCode("U", client, "");
                                    }
                                }
                                displayJoinedLeftUser(newCl, true);
                                loadUsersList();
                            }
                            break;

                        case "LOADSONGS":
                            if (!isHost) {
                                String encodedSongsList = msgSplit[2];
                                byte[] songsListBytes = Base64.getDecoder().decode(encodedSongsList);
                                try (ByteArrayInputStream bais = new ByteArrayInputStream(songsListBytes);
                                     ObjectInputStream ois = new ObjectInputStream(bais)) {
                                    ArrayList<File> receivedSongsList = (ArrayList<File>) ois.readObject();

                                    // Convert ArrayList back to ObservableList
                                    songsList = FXCollections.observableArrayList(receivedSongsList);
                                    Platform.runLater(() -> songsListView.setItems(songsList));
                                } catch (ClassNotFoundException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                            break;

                        case "PLAY":
                            if (!isHost) {
                                Platform.runLater(() -> {
                                    currentSong.set(Integer.parseInt(msgSplit[1]));
                                    play();
                                    double receivedTimeInSeconds = Double.parseDouble(msgSplit[2]);
                                    Duration receivedDuration = Duration.seconds(receivedTimeInSeconds);
                                    mediaPlayer.seek(receivedDuration);
                                });
                            }
                            break;

                        case "PLAYSONG":
                            if (!isHost) {
                                Platform.runLater(() -> {
                                    currentSong.set(Integer.parseInt(msgSplit[1]));
                                    playSong();
                                });
                            }
                            break;

                        case "PAUSE":
                            if (!isHost && mediaPlayer != null) {
                                Platform.runLater(this::pause);
                            }
                            break;

                        // Normal message
                        case "NORM":
                            addReceiveMessage(newCl, msgSplit[2]);
                            break;

                        default:
                            break;
                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }).start();
    }

    // Send message to chat
    public void sendMessage() {

        String msgToSend = chatField.getText();

        if (msgToSend.isBlank()) {
            return;
        }

        msgToSend = "NORM:" + me + ": " + chatField.getText().trim();
        byte[] data = msgToSend.getBytes();
        sendDatagramPacket(data);
        chatField.clear();
    }

    private void loadUsersList() {
        Platform.runLater(() -> {
            // Xóa các client hiện tại
            usersListContainer.getChildren().clear();

            for (String client : clientList) {
                // Tạo HBox chứa tên người dùng và nút "X"
                HBox hbox = new HBox();
                hbox.getStyleClass().add("full-width-hbox");

                // Tạo Label cho tên người dùng
                Label userLabel = new Label(client);
                userLabel.getStyleClass().add("user-label");

                // Thêm Label vào HBox
                hbox.getChildren().add(userLabel);

//                if (isHost) {
//                    // Tạo nút "X" nếu người dùng là host
//                    Button removeButton = new Button("X");
//                    removeButton.getStyleClass().add("remove-button");
//
//                    // Thiết lập hành động khi nút "X" được nhấn
//                    removeButton.setOnAction(_ -> {
//                        // Xử lý khi nút "X" được nhấn, ví dụ: xóa người dùng khỏi danh sách
//                        clientList.remove(client);
//                        kickUser(client);
//                        displayJoinedLeftUser(client, false);
//                        loadUsersList(); // Cập nhật danh sách
//                    });
//
//                    // Thêm Label và Button vào HBox
//                    hbox.getChildren().add(removeButton);
//                }

                // Thêm HBox vào usersListContainer
                usersListContainer.getChildren().add(hbox);
            }
        });
    }

    private void addReceiveMessage(String sender, String message) {
        Platform.runLater(() -> {
            // Sender Styling
            Text username = new Text(sender);
            username.getStyleClass().add("sender");

            // Time styling
            Text time = new Text(" at " + getTime());
            time.getStyleClass().add("time");

            // Message styling
            Text msg = new Text(message);
            msg.getStyleClass().add("message");

            // Thêm Text vào TextFlow
            chatFlow.getChildren().addAll(username, time, msg, new Text("\n"));
        });
    }

    private void displayJoinedLeftUser(String username, boolean joined) {
        Platform.runLater(() -> {
            String textToDisplay;
            if (joined) {
                textToDisplay = username + " has joined";
            } else {
                textToDisplay = username + " has left";
            }
            Text text = new Text(textToDisplay);
            text.getStyleClass().add("user-joined-left");

            chatFlow.getChildren().addAll(text, new Text("\n"));
        });
    }

    private String getTime() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public void chooseSong() {
        // Choose media file from user directory
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Choose a song");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav"));
        File selectedFile = fileChooser.showOpenDialog(null);
        if (selectedFile != null) {
            songsList.add(selectedFile);
            if (currentSong.get() < 0) {
                currentSong.set(0);
            }
            sendSongsList(me, songsList);
        }
    }

    public void sendSongsList(String client, ObservableList<File> songsList) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {

            ArrayList<File> serializableList = new ArrayList<>();

            for (File file : songsList) {
                serializableList.add(new File(file.getName()));
            }

            oos.writeObject(serializableList);
            oos.flush();

            // Convert the serialized object to a byte array
            byte[] songsListBytes = baos.toByteArray();

            // Encode the data (simple approach: using Base64 encoding)
            String encodedSongsList = Base64.getEncoder().encodeToString(songsListBytes);

            // Create a message with code, client and encoded object
            String message = "LOADSONGS:" + client + ":" + encodedSongsList;

            // Convert the message to bytes
            byte[] buffer = message.getBytes(StandardCharsets.UTF_8);

            sendDatagramPacket(buffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendDatagramPacket(byte[] buffer) {
        dp = new DatagramPacket(buffer, buffer.length, room.ip(), MULTICAST_PORT);
        try {
            ms.send(dp);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Thread audioSender() {
        return new Thread(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                while (true) {
                    socket = serverSocket.accept();
                    TCPSendFile();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void TCPSendFile() {
        if (currentSong.get() >= 0) {
            int index = currentSong.get();
            File file = songsList.get(index);

            System.out.println("current song: " + index);

            // Tạo header
            String header = String.format("%04d", index);
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            byte[] fileBytes = new byte[(int) file.length()];

            try {
                OutputStream os = socket.getOutputStream();

                // Gửi header
                os.write(headerBytes);
                os.flush();

                FileInputStream fis = new FileInputStream(file);

                // Gửi file
                int bytesRead = fis.read(fileBytes);
                if (bytesRead != -1) {
                    os.write(fileBytes);
                    os.flush();
                }
                fis.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // receive audio and play
    private Thread audioReceiver() {
        return new Thread(() -> {
            try {
                Socket receiveSocket = new Socket(room.hostIp(), SERVER_PORT);
                InputStream is = receiveSocket.getInputStream();
                // Đọc header
                byte[] headerBuffer = new byte[4];
                int headerBytesRead = is.read(headerBuffer);
                String header = new String(headerBuffer, 0, headerBytesRead, StandardCharsets.UTF_8);

                int index = Integer.parseInt(header.trim());

                currentSong.set(index);

                System.out.println("current song: " + index);

                File file = new File(songsList.get(index).getName());

                // Kiểm tra xem tệp có tồn tại không
                if (!file.exists()) {
                    FileOutputStream fos = new FileOutputStream(file);

                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        fos.flush();
                    }
                    fos.close();
                }
                is.close();
                receiveSocket.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void play() {
        if (isRunning) {
            pause();
        } else {
            if (mediaPlayer == null) {
                currentSong.set(0);
                media = new Media(songsList.getFirst().toURI().toString());

                mediaPlayer = new MediaPlayer(media);

                if (isHost) {
                    mediaPlayer.setOnEndOfMedia(this::playNext);
                }
                mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);
                if (isHost && socket != null) {
                    TCPSendFile();
                }
            }
            beginTimer();
            mediaPlayer.play();
            isPlaying.set(true);
            btnPlay.setText("Pause");

            if (isHost) {
                sendRequestCode("PLAY", String.valueOf(currentSong.get()), String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
            }
        }
    }

    public void pause() {
        cancelTimer();
        mediaPlayer.pause();
        isPlaying.set(false);
        btnPlay.setText("Play");
        if (isHost) {
            sendRequestCode("PAUSE", me, "");
        }
    }

    // Only use this method to play a specific song
    public void playSong() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }

        if (isRunning) {
            cancelTimer();
        }

        if (!isHost) {
            audioReceiver().start();
        }

        media = new Media(songsList.get(currentSong.get()).toURI().toString());

        mediaPlayer = new MediaPlayer(media);

        if (isHost) {
            mediaPlayer.setOnEndOfMedia(this::playNext);
        }
        mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);

        beginTimer();
        mediaPlayer.play();
        isPlaying.set(true);
        btnPlay.setText("Pause");

        if (isHost && socket != null) {
            TCPSendFile();
            sendRequestCode("PLAYSONG", String.valueOf(currentSong.get()), String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
        }
    }

    public void playPrevious() {
        if (currentSong.get() > 0) {
            currentSong.set(currentSong.get() - 1);
            playSong();
        } else {
            if (isHost) {
                notice();
            }
        }
    }

    public void playNext() {
        if (currentSong.get() < songsList.size() - 1) {
            currentSong.set(currentSong.get() + 1);
            playSong();
        } else {
            if (isHost) {
                notice();
            }
        }
    }

    public void notice() {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);

            alert.setTitle("Notice");
            alert.setHeaderText(null);
            alert.setContentText("End off the list!");
            alert.showAndWait();
        });
    }

    public void beginTimer() {
        lblNowPlaying.textProperty().setValue(
                songsList.get(currentSong.get()).getName());
        System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());

        isRunning = true;

        timer = new Timer();

        task = new TimerTask() {
            @Override
            public void run() {
                double current = mediaPlayer.getCurrentTime().toSeconds();
                double total = mediaPlayer.getTotalDuration().toSeconds();

                Platform.runLater(() -> {
                    lblCurrentTime.textProperty().setValue(String.format("%02d:%02d", (int) current / 60, (int) current % 60));
                    lblTotalTime.textProperty().setValue(String.format("%02d:%02d", (int) total / 60, (int) total % 60));
                });

                songProgressBar.setProgress(current / total);
                if (current == total) {
                    cancelTimer();
                }
            }
        };

        timer.scheduleAtFixedRate(task, 0, 1000);
    }

    public void cancelTimer() {
        isRunning = false;
        if (task != null) {
            task.cancel();
        }
        if (timer != null) {
            timer.cancel();
        }
    }

    public ListCell<File> getFileCell() {
        return new FileCell() {
            @Override
            public Node getStyleableNode() {
                return super.getStyleableNode();
            }

            @Override
            protected void playChosenSong(File file) {
                if (isHost) {
                    currentSong.set(songsList.indexOf(file));
                    playSong();
                }
            }

            @Override
            protected void deleteChosenSong(File file) {
                if (isHost) {
                    currentSong.set(songsList.indexOf(file));
                    songsList.remove(file);
                    sendSongsList("", songsList);
                }
            }
        };
    }
}

abstract class FileCell extends ListCell<File> {
    private final Label songLabel;

    public FileCell() {
        songLabel = new Label();
        songLabel.getStyleClass().add("song-label");
        setGraphic(songLabel);

        // Context menu for right click
        ContextMenu contextMenu = new ContextMenu();

        MenuItem playItem = new MenuItem("Play");
        playItem.setOnAction(_ -> playChosenSong(getItem()));

        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(_ -> deleteChosenSong(getItem()));

        contextMenu.getItems().addAll(playItem, deleteItem);

        // context menu for cell
        setContextMenu(contextMenu);

        // Drag and Drop event
        setOnDragDetected(event -> {
            if (getItem() == null) {
                return;
            }
            Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(getItem().getAbsolutePath());
            dragboard.setContent(content);
            event.consume();
        });

        setOnDragOver(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasString()) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        setOnDragEntered(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasString()) {
                setOpacity(0.3);
            }
        });

        setOnDragExited(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasString()) {
                setOpacity(1);
            }
        });

        setOnDragDropped(event -> {
            if (getItem() == null) {
                return;
            }
            Dragboard dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasString()) {
                ObservableList<File> items = getListView().getItems();
                int draggedIdx = items.indexOf(new File(dragboard.getString()));
                int thisIdx = items.indexOf(getItem());

                if (draggedIdx != thisIdx) {
                    File temp = items.get(draggedIdx);
                    items.set(draggedIdx, items.get(thisIdx));
                    items.set(thisIdx, temp);

                    ListView<File> listView = getListView();
                    listView.getSelectionModel().clearSelection();
                    listView.getSelectionModel().select(thisIdx);
                }

                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        setOnDragDone(DragEvent::consume);
    }

    @Override
    protected void updateItem(File item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
            setText(null);
            setGraphic(null);
        } else {
            songLabel.setText(item.getName());
            setGraphic(songLabel);
        }
    }

    protected abstract void playChosenSong(File file);

    protected abstract void deleteChosenSong(File file);
}