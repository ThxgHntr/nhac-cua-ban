package com.ncb.nhaccuaban;

import com.ncb.nhaccuaban.Controllers.LauncherController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Application extends javafx.application.Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(Application.class.getResource("launcher.fxml"));
        Scene scene = new Scene(fxmlLoader.load());
        LauncherController controller = fxmlLoader.getController();
        controller.setupInputCheck();
        stage.setTitle("Listen Together");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}