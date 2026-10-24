package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ScrcpyServerArtifact;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.scene.input.KeyCode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrcpyApp extends Application {
    private final ListView<AdbDevice> devices = new ListView<>();
    private final Button connect = new Button("连接选中");
    private final Button disconnect = new Button("断开选中");
    private final Button connectAll = new Button("全部连接");
    private final Label status = new Label("正在扫描 ADB 设备...");
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private Adb adb;

    @Override
    public void start(Stage stage) {
        String adbPath = System.getProperty("scrcpy.adb", "adb");
        adb = new Adb(adbPath, null);

        devices.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        devices.setCellFactory(v -> deviceCell());
        devices.setPlaceholder(new Label("未发现 ADB 设备"));

        disconnect.setDisable(true);

        HBox toolbar = new HBox(8,
                new Label("设备:"),
                connect,
                disconnect,
                connectAll,
                status);
        BorderPane root = new BorderPane(devices);
        root.setTop(toolbar);
        BorderPane.setMargin(toolbar, new javafx.geometry.Insets(8));

        connect.setOnAction(e -> connectSelected());
        disconnect.setOnAction(e -> disconnectSelected());
        connectAll.setOnAction(e -> connectAll());

        devices.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<AdbDevice>) c -> updateButtons());

        stage.setTitle("scrcpy-java - 多设备");
        stage.setScene(new Scene(root, 720, 520));
        stage.setOnCloseRequest(e -> {
            closeAllSessions();
            Platform.exit();
        });
        stage.show();

        scanDevices();
    }

    private ListCell<AdbDevice> deviceCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AdbDevice d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setText(null);
                    return;
                }
                boolean active = sessions.containsKey(d.serial());
                String state = active ? "运行中" :
                        ("device".equals(d.state()) ? "已连接" : d.state());
                setText(d.displayName() + "  [" + state + "]");
            }
        };
    }

    private void scanDevices() {
        status.setText("正在扫描...");
        Thread.startVirtualThread(() -> {
            try {
                List<AdbDevice> list = adb.devices();
                Platform.runLater(() -> {
                    devices.setItems(FXCollections.observableArrayList(list));
                    status.setText(list.isEmpty()
                            ? "未发现设备"
                            : "发现 " + list.size() + " 台设备，已连接 " + sessions.size() + " 台");
                    updateButtons();
                });
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("ADB 扫描失败: " + e.getMessage()));
            }
        });
    }

    private void connectSelected() {
        connectDevices(new ArrayList<>(devices.getSelectionModel().getSelectedItems()));
    }

    private void connectAll() {
        connectDevices(devices.getItems().stream()
                .filter(AdbDevice::ready)
                .filter(d -> !sessions.containsKey(d.serial()))
                .toList());
    }

    private void connectDevices(List<AdbDevice> selected) {
        List<AdbDevice> targets = selected.stream()
                .filter(AdbDevice::ready)
                .filter(d -> !sessions.containsKey(d.serial()))
                .toList();

        if (targets.isEmpty()) {
            status.setText("没有可连接的新设备");
            return;
        }

        updateButtons();
        targets.forEach(this::connectDevice);
    }

    private void connectDevice(AdbDevice device) {
        status.setText("正在连接 " + device.displayName() + " ...");

        Thread.startVirtualThread(() -> {
            JavaSoundPlayer audioPlayer = null;
            ScrcpySession session = null;
            try {
                ScrcpyOptions d = ScrcpyOptions.defaults();
                ScrcpyOptions options = new ScrcpyOptions(
                        device.serial(), d.scid(), d.localPort(),
                        true, true, false,
                        d.videoCodec(), d.audioCodec(),
                        d.videoBitRate(), d.audioBitRate(),
                        d.maxSize(), d.maxFps(),
                        d.showTouches(), d.stayAwake(),
                        d.turnScreenOff(), d.crop(), d.startApp());

                VideoView view = new VideoView();
                audioPlayer = new JavaSoundPlayer();
                JavaSoundPlayer player = audioPlayer;

                session = new ScrcpySession(
                        new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial()),
                        options);

                ScrcpySession runningSession = session;
                runningSession.start(
                        ScrcpyServerArtifact.resolve(),
                        view::accept,
                        player::accept);

                Stage deviceStage = new Stage();
                BorderPane root = new BorderPane(view);
                view.fitWidthProperty().bind(root.widthProperty());
                view.fitHeightProperty().bind(root.heightProperty());
                deviceStage.setTitle("scrcpy-java - " + device.displayName());
                deviceStage.setScene(new Scene(root, 480, 800));
                deviceStage.setOnCloseRequest(e -> disconnect(device.serial()));
                deviceStage.show();

                DeviceSession handle = new DeviceSession(runningSession, player, deviceStage);
                DeviceSession previous = sessions.putIfAbsent(device.serial(), handle);
                if (previous != null) {
                    handle.close();
                    return;
                }

                Platform.runLater(() -> {
                    devices.refresh();
                    status.setText("已连接 " + sessions.size() + " 台设备");
                    updateButtons();
                });
            } catch (Throwable e) {
                if (session != null) session.close();
                if (audioPlayer != null) audioPlayer.close();
                Platform.runLater(() -> {
                    status.setText("连接 " + device.displayName() + " 失败: " + e.getMessage());
                    devices.refresh();
                    updateButtons();
                });
                e.printStackTrace();
            }
        });
    }

    private void disconnectSelected() {
        new ArrayList<>(devices.getSelectionModel().getSelectedItems())
                .forEach(d -> disconnect(d.serial()));
    }

    private void disconnect(String serial) {
        DeviceSession handle = sessions.remove(serial);
        if (handle == null) return;
        handle.close();
        Platform.runLater(() -> {
            devices.refresh();
            status.setText("已连接 " + sessions.size() + " 台设备");
            updateButtons();
        });
    }

    private void updateButtons() {
        boolean hasReady = devices.getItems().stream()
                .anyMatch(d -> d.ready() && !sessions.containsKey(d.serial()));
        boolean hasSelection = !devices.getSelectionModel().getSelectedItems().isEmpty();
        connect.setDisable(!hasSelection);
        disconnect.setDisable(!hasSelection);
        connectAll.setDisable(!hasReady);
    }

    private void closeAllSessions() {
        new ArrayList<>(sessions.values()).forEach(DeviceSession::close);
        sessions.clear();
    }

    @Override
    public void stop() {
        closeAllSessions();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static final class DeviceSession implements AutoCloseable {
        private final ScrcpySession session;
        private final JavaSoundPlayer audioPlayer;
        private final Stage stage;

        private DeviceSession(ScrcpySession session, JavaSoundPlayer audioPlayer, Stage stage) {
            this.session = session;
            this.audioPlayer = audioPlayer;
            this.stage = stage;
        }

        @Override
        public void close() {
            Platform.runLater(stage::close);
            session.close();
            audioPlayer.close();
        }
    }
}
