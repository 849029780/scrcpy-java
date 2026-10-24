package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ScrcpyServerArtifact;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public final class ScrcpyApp extends Application {
    private ScrcpySession session;

    @Override
    public void start(Stage stage) {
        VideoView view = new VideoView();
        StackPane root = new StackPane(view);
        view.fitWidthProperty().bind(root.widthProperty());
        view.fitHeightProperty().bind(root.heightProperty());

        Label status = new Label("正在连接 Android 设备...");
        root.getChildren().add(status);

        stage.setTitle("scrcpy-java");
        stage.setScene(new Scene(root, 480, 800));
        stage.setOnCloseRequest(e -> {
            if (session != null) session.close();
            Platform.exit();
        });
        stage.show();

        String serial = null;
        String adbPath = System.getProperty("scrcpy.adb", "adb");
        for (String arg : getParameters().getRaw()) {
            if (arg.startsWith("--serial=")) serial = arg.substring("--serial=".length());
            else if (arg.startsWith("--adb=")) adbPath = arg.substring("--adb=".length());
        }

        final String selectedSerial = serial;
        final String selectedAdb = adbPath;

        Thread.startVirtualThread(() -> {
            try {
                ScrcpyOptions d = ScrcpyOptions.defaults();
                ScrcpyOptions options = new ScrcpyOptions(
                        selectedSerial, d.scid(), d.localPort(),
                        true, false, false,
                        d.videoCodec(), d.audioCodec(),
                        d.videoBitRate(), d.audioBitRate(),
                        d.maxSize(), d.maxFps(),
                        d.showTouches(), d.stayAwake(),
                        d.turnScreenOff(), d.crop(), d.startApp());

                session = new ScrcpySession(new Adb(selectedAdb, selectedSerial), options);
                var serverJar = ScrcpyServerArtifact.resolve();
                session.start(serverJar, view::accept);

                Platform.runLater(() -> {
                    status.setVisible(false);
                    stage.setTitle("scrcpy-java" +
                            (session.deviceName() == null ? "" : " - " + session.deviceName()));
                });
            } catch (Throwable e) {
                Platform.runLater(() -> status.setText("连接失败: " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
