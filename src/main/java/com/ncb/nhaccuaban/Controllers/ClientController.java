package com.ncb.nhaccuaban.Controllers;

import com.ncb.nhaccuaban.Models.RoomModel;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

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
    public VBox userListContainer;
    public Label lblRoomId;

    private MulticastSocket ms;
    private DatagramPacket dp;
    private String me;
    private RoomModel room;
    private final ArrayList<String> clientList = new ArrayList<>();
    private boolean isHost;
    private Media media;
    private MediaPlayer mediaPlayer;
    private ArrayList<File> songs;
    private BooleanProperty isPlaying;
    private IntegerProperty currentSong;
    private Timer timer;
    private TimerTask task;
    private boolean isRunning = false;
    private static final String SERVER_ADDRESS = "192.168.1.6";
    private static final int MULTICAST_PORT = 4444;
    private static final int SERVER_PORT = 12345;
    private static ServerSocket serverSocket;
    private static Socket socket;
    private InputStream is;
    private FileOutputStream fos;
    private FileInputStream fis;
    private OutputStream os;

    public void initialize() {
        songs = new ArrayList<>();
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sendRequestCode("L", me, "");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));
    }

    public void initData(boolean isHost, String name, RoomModel room) throws IOException, InterruptedException {
        this.isHost = isHost;
        this.me = name;
        this.room = room;
        this.ms = new MulticastSocket(MULTICAST_PORT);
        this.dp = new DatagramPacket(new byte[1000], 1000);

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
    public void sendRequestCode(String code, String client, String msg) throws IOException {
        byte[] buffer = (code + ":" + client + ":" + msg).getBytes();
        dp = new DatagramPacket(buffer, buffer.length, room.ip(), MULTICAST_PORT);
        ms.send(dp);
    }

    // Receive chat message
    public void receiveMessage() {
        new Thread(() -> {
            while (true) {
                try {
                    byte[] buffer = new byte[1024 * 1024 * 5];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    ms.receive(packet);

                    String receivedMsg = new String(packet.getData(), 0, packet.getLength());
                    String[] msgSplit = receivedMsg.split(":", 3);
                    String newCl = msgSplit[1];

                    switch (msgSplit[0]) {
                        // Leave chat
                        case "L":
                            clientList.removeIf(cl -> cl.equals(newCl));
                            displayJoinedLeftUser(newCl, false);
                            loadClients();
                            break;

                        // Update client list
                        case "U":
                            if (!clientList.contains(newCl)) {
                                clientList.add(newCl);
                                if (isHost) {
                                    if (isRunning) {
                                        sendRequestCode("PLAY", me, String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
                                        System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());
                                    }

                                    for (String client : clientList) {
                                        sendRequestCode("U", client, "");
                                    }
                                }
                                displayJoinedLeftUser(newCl, true);
                                loadClients();
                            }
                            break;

                        case "PLAY":
                            if (!isHost && mediaPlayer != null) {
                                double receivedTimeInSeconds = Double.parseDouble(msgSplit[2]);
                                // Tạo một đối tượng Duration từ số giây
                                Duration receivedDuration = Duration.seconds(receivedTimeInSeconds);
                                Platform.runLater(this::play);
                                mediaPlayer.seek(receivedDuration);
                                beginTimer();
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

        try {
            msgToSend = "NORM:" + me + ": " + chatField.getText().trim();
            byte[] data = msgToSend.getBytes();
            dp = new DatagramPacket(data, data.length, room.ip(), MULTICAST_PORT);
            ms.send(dp);
            chatField.clear();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadClients() {
        Platform.runLater(() -> {
            // clear except first client
            if (userListContainer.getChildren().size() > 1) {
                userListContainer.getChildren().remove(1, userListContainer.getChildren().size());
            }
            for (String client : clientList) {
                Label label = new Label(client);
                label.getStyleClass().add("user-label");
                userListContainer.getChildren().add(label);
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
            songs.add(selectedFile);
            if (currentSong.get() < 0) {
                currentSong.set(0);
            }
        }
    }

    public void play() {
        if (isRunning) {
            pause();
        } else {
            if (mediaPlayer == null) {
                currentSong.set(0);
                media = new Media(songs.getFirst().toURI().toString());

                mediaPlayer = new MediaPlayer(media);

                if (isHost) {
                    mediaPlayer.setOnEndOfMedia(this::playNext);
                    btnPlay.setText("Pause");
                }
                mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);
            }
            beginTimer();
            mediaPlayer.play();
            lblNowPlaying.textProperty().setValue(
                    songs.get(currentSong.get()).getName()
                            .substring(0, songs.get(currentSong.get()).getName().lastIndexOf('.')));
            System.out.println("playing " + songs.get(currentSong.get()));
            isPlaying.set(true);
            if (isHost) {
                btnPlay.setText("Pause");
                try {
                    sendRequestCode("PLAY", me, String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
                    System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public void pause() {
        cancelTimer();
        mediaPlayer.pause();
        isPlaying.set(false);
        if (isHost) {
            try {
                sendRequestCode("PAUSE", me, "");
                btnPlay.setText("Play");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // Only use this method to play previous or next song
    public void playSong() {
        mediaPlayer.stop();

        if (isRunning) {
            cancelTimer();
        }

        media = new Media(songs.get(currentSong.get()).toURI().toString());

        mediaPlayer = new MediaPlayer(media);

        isPlaying.set(true);
        mediaPlayer.setOnEndOfMedia(this::playNext);
        mediaPlayer.setVolume(volumeSlider.getValue() / 100.0);
        beginTimer();
        mediaPlayer.play();

        btnPlay.setText("Pause");

        try {
            sendRequestCode("PLAY", me, String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
            System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void playPrevious() {
        if (currentSong.get() > 0) {
            currentSong.set(currentSong.get() - 1);
            playSong();
        } else {
            currentSong.set(songs.size() - 1);
            playSong();
        }
    }

    public void playNext() {
        if (currentSong.get() < songs.size() - 1) {
            currentSong.set(currentSong.get() + 1);
            playSong();
        } else {
            currentSong.set(0);
            playSong();
        }
    }

    public void beginTimer() {
        if (isHost) {
            try {
                sendRequestCode("PLAY", me, String.valueOf(mediaPlayer.getCurrentTime().toSeconds()));
                System.out.println("play at " + mediaPlayer.getCurrentTime().toSeconds());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

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
                if (current == total && isHost) {
                    cancelTimer();
                    playNext();
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

    private Thread audioSender() {
        return new Thread(() -> {
            try {
                serverSocket = new ServerSocket(SERVER_PORT);
                while (true) {
                    socket = serverSocket.accept();
                    if (currentSong.get() >= 0) {
                        File file = songs.get(currentSong.get());

                        byte[] fileBytes = new byte[(int) file.length()];
                        fis = new FileInputStream(file);
                        os = socket.getOutputStream();
                        int bytesRead = fis.read(fileBytes);
                        if (bytesRead == -1) {
                            fis.close();
                        } else {
                            os.write(fileBytes);
                            os.flush();
                            os.close();
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // Phát audio từ server
    private Thread audioReceiver() {
        return new Thread(() -> {
            try {
                socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
                is = socket.getInputStream();
                File file = new File("received_song.mp3");
                fos = new FileOutputStream(file);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    fos.flush();
                }
                fos.close();
                songs.add(file);
                Platform.runLater(this::play);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}

