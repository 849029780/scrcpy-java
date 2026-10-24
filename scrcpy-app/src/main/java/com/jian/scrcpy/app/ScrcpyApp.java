package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ScrcpyServerArtifact;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.util.List;

public final class ScrcpyApp extends Application {
    private ScrcpySession session;
    private final ComboBox<AdbDevice> devices = new ComboBox<>();
    private final Button connect = new Button("连接");
    private final Label status = new Label("正在扫描 ADB 设备...");
    private Adb adb;

    @Override
    public void start(Stage stage) {
        String adbPath = System.getProperty("scrcpy.adb", "adb");
        adb = new Adb(adbPath, null);

        VideoView view = new VideoView();
        view.fitWidthProperty().bind(((BorderPane) new BorderPane()).widthProperty());

        devices.setPromptText("选择 Android 设备");
        devices.setCellFactory(v -> deviceCell());
        devices.setButtonCell(deviceCell());
        connect.setDisable(true);

        HBox toolbar = new HBox(8, new Label("设备:"), devices, connect, status);
        BorderPane root = new BorderPane(view);
        root.setTop(toolbar);
        BorderPane.setMargin(toolbar, new javafx.geometry.Insets(8));
        view.fitWidthProperty().bind(root.widthProperty());
        view.fitHeightProperty().bind(root.heightProperty().subtract(toolbar.heightProperty()));

        connect.setOnAction(e -> connectSelected(stage, view));

        stage.setTitle("scrcpy-java");
        stage.setScene(new Scene(root, 480, 800));
        stage.setOnCloseRequest(e -> {
            if (session != null) session.close();
            Platform.exit();
        });
        stage.show();

        scanDevices();
    }

    private ListCell<AdbDevice> deviceCell() {
        return new ListCell<>() {
            @Override protected void updateItem(AdbDevice d, boolean empty) {
                super.updateItem(d, empty);
                setText(empty || d == null ? null :
                        d.displayName() + "  " + ("device".equals(d.state()) ? "已连接" : d.state()));
            }
        };
    }

    private void scanDevices() {
        connect.setDisable(true);
        status.setText("正在扫描...");
        Thread.startVirtualThread(() -> {
            try {
                List<AdbDevice> list = adb.devices();
                Platform.runLater(() -> {
                    devices.setItems(FXCollections.observableArrayList(list));
                    if (list.size() == 1 && list.getFirst().ready()) devices.getSelectionModel().selectFirst();
                    connect.setDisable(list.stream().noneMatch(AdbDevice::ready));
                    status.setText(list.isEmpty() ? "未发现设备" : "发现 " + list.size() + " 台设备");
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("ADB 扫描失败: " + e.getMessage()));
            }
        });
    }

    private void connectSelected(Stage stage, VideoView view) {
        AdbDevice device = devices.getValue();
        if (device == null || !device.ready()) {
            status.setText("请选择已授权设备");
            return;
        }

        connect.setDisable(true);
        devices.setDisable(true);
        status.setText("正在连接 " + device.displayName());

        Thread.startVirtualThread(() -> {
            try {
                ScrcpyOptions d = ScrcpyOptions.defaults();
                ScrcpyOptions options = new ScrcpyOptions(
                        device.serial(), d.scid(), d.localPort(),
                        true, false, false,
                        d.videoCodec(), d.audioCodec(),
                        d.videoBitRate(), d.audioBitRate(),
                        d.maxSize(), d.maxFps(),
                        d.showTouches(), d.stayAwake(),
                        d.turnScreenOff(), d.crop(), d.startApp());

                session = new ScrcpySession(new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial()), options);
                session.start(ScrcpyServerArtifact.resolve(), view::accept);

                Platform.runLater(() -> {
                    status.setText("已连接");
                    stage.setTitle("scrcpy-java - " + device.displayName());
                });
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    status.setText("连接失败: " + e.getMessage());
                    connect.setDisable(false);
                    devices.setDisable(false);
                });
                e.printStackTrace();
            }
        });
    }

    @Override public void stop() {
        if (session != null) session.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
