package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.control.DeviceController;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ScrcpyServerArtifact;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrcpyApp extends Application {
    private static final double TILE_WIDTH = 360;
    private static final double TILE_HEIGHT = 680;

    private final ListView<AdbDevice> devices = new ListView<>();
    private final Button connect = new Button("连接选中");
    private final Button disconnect = new Button("断开选中");
    private final Button connectAll = new Button("全部连接");
    private final Label status = new Label("正在扫描 ADB 设备...");
    private final TilePane screenGrid = new TilePane();
    private final ScrollPane screenScroll = new ScrollPane(screenGrid);
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private Adb adb;

    @Override
    public void start(Stage stage) {
        String adbPath = System.getProperty("scrcpy.adb", "adb");
        adb = new Adb(adbPath, null);

        devices.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        devices.setCellFactory(v -> deviceCell());
        devices.setPlaceholder(new Label("未发现 ADB 设备"));

        screenGrid.setHgap(10);
        screenGrid.setVgap(10);
        screenGrid.setPrefColumns(3);
        screenGrid.setPrefTileWidth(TILE_WIDTH);
        screenGrid.setPrefTileHeight(TILE_HEIGHT);
        screenGrid.setPadding(new javafx.geometry.Insets(10));
        screenGrid.setTileAlignment(javafx.geometry.Pos.TOP_LEFT);

        screenScroll.setFitToWidth(true);
        screenScroll.setFitToHeight(false);
        screenScroll.setPannable(true);

        disconnect.setDisable(true);

        HBox toolbar = new HBox(8,
                new Label("设备:"),
                connect,
                disconnect,
                connectAll,
                status);

        SplitPane root = new SplitPane();
        root.setDividerPositions(0.22);
        root.getItems().addAll(devices, screenScroll);

        BorderPane content = new BorderPane(root);
        content.setTop(toolbar);
        BorderPane.setMargin(toolbar, new javafx.geometry.Insets(8));

        connect.setOnAction(e -> connectSelected());
        disconnect.setOnAction(e -> disconnectSelected());
        connectAll.setOnAction(e -> connectAll());

        devices.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<AdbDevice>) c -> updateButtons());

        stage.setTitle("scrcpy-java - 多设备投屏");
        stage.setScene(new Scene(content, 1400, 900));
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
                String state = active ? "投屏中" :
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
                            : "发现 " + list.size() + " 台设备，投屏中 " + sessions.size() + " 台");
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

        status.setText("正在连接 " + targets.size() + " 台设备...");
        targets.forEach(this::connectDevice);
    }

    private void connectDevice(AdbDevice device) {
        Thread.startVirtualThread(() -> {
            JavaSoundPlayer audioPlayer = null;
            ScrcpySession session = null;
            try {
                ScrcpyOptions d = ScrcpyOptions.defaults();
                ScrcpyOptions options = new ScrcpyOptions(
                        device.serial(), d.scid(), d.localPort(),
                        true, true, true,
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

                Platform.runLater(() -> {
                    if (sessions.containsKey(device.serial())) {
                        runningSession.close();
                        player.close();
                        return;
                    }

                    DeviceSession handle = new DeviceSession(runningSession, player, view,
                            new DeviceController(runningSession::sendControl));
                    sessions.put(device.serial(), handle);
                    addScreen(handle, device);
                    devices.refresh();
                    status.setText("投屏中 " + sessions.size() + " 台设备");
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

    private void addScreen(DeviceSession handle, AdbDevice device) {
        VBox tile = new VBox(4);
        tile.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMaxSize(TILE_WIDTH, TILE_HEIGHT);

        Label title = new Label(device.displayName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setStyle("-fx-font-weight: bold; -fx-padding: 4 6 4 6;");

        StackPane viewport = new StackPane(handle.view());
        viewport.setPrefSize(TILE_WIDTH, TILE_HEIGHT - 34);
        viewport.setStyle("-fx-background-color: #222222;");

        handle.view().setPreserveRatio(true);
        handle.view().setSmooth(true);
        handle.view().setFitWidth(TILE_WIDTH - 8);
        handle.view().setFitHeight(TILE_HEIGHT - 42);
        installControls(handle);

        Button close = new Button("断开");
        close.setOnAction(e -> disconnect(device.serial()));

        HBox header = new HBox(6, title, new Region(), close);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        tile.getChildren().addAll(header, viewport);
        handle.tile = tile;
        handle.device = device;

        screenGrid.getChildren().add(tile);
    }

    private void installControls(DeviceSession handle) {
        VideoView view = handle.view();
        view.setFocusTraversable(true);
        // Keep the normal pointer while dragging; do not expose JavaFX's move/drag cursor.
        view.setCursor(Cursor.DEFAULT);

        view.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p == null) return;
            handle.controller.hover(p);
        });
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            view.requestFocus();
            sendTouch(handle, e, 0, 1f, 1);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isPrimaryButtonDown()) return;
            sendTouch(handle, e, 2, 1f, 0);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            sendTouch(handle, e, 1, 0f, 1);
            e.consume();
        });
        view.addEventHandler(ScrollEvent.SCROLL, e -> {
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p == null) return;
            float h = (float) Math.max(-16, Math.min(16, e.getDeltaX() / 20.0));
            float v = (float) Math.max(-16, Math.min(16, e.getDeltaY() / 20.0));
            handle.controller.scroll(p, h, v, 0);
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            int keycode = InputMapper.androidKeyCode(e.getCode());
            if (keycode < 0) return;
            handle.controller.key(DeviceController.ACTION_DOWN, keycode, 0,
                    InputMapper.metaState(e.isShiftDown(), e.isControlDown(), e.isAltDown()));
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            int keycode = InputMapper.androidKeyCode(e.getCode());
            if (keycode < 0) return;
            handle.controller.key(DeviceController.ACTION_UP, keycode, 0,
                    InputMapper.metaState(e.isShiftDown(), e.isControlDown(), e.isAltDown()));
            e.consume();
        });
        view.setOnInputMethodTextChanged(e -> {
            String committed = e.getCommitted();
            if (committed != null && !committed.isEmpty()) {
                handle.controller.pasteText(committed);
            }
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            String text = e.getCharacter();
            if (text == null || text.isEmpty() || text.charAt(0) < 0x20) return;
            // SDK text injection is suitable for ASCII. Non-ASCII (for example
            // Chinese committed by the PC IME) is sent via clipboard+paste.
            if (text.codePoints().allMatch(cp -> cp < 0x80)) {
                handle.controller.text(text);
            } else {
                handle.controller.pasteText(text);
            }
            e.consume();
        });
    }

    private void sendTouch(DeviceSession handle, MouseEvent e, int action, float pressure, int actionButton) {
        DeviceController.ScreenPosition p = InputMapper.position(handle.view(), e.getX(), e.getY());
        if (p == null) return;
        handle.controller.touch(action, DeviceController.POINTER_ID_MOUSE, p, pressure, actionButton, e.isPrimaryButtonDown() ? 1 : 0);
    }

    private void disconnectSelected() {
        new ArrayList<>(devices.getSelectionModel().getSelectedItems())
                .forEach(d -> disconnect(d.serial()));
    }

    private void disconnect(String serial) {
        DeviceSession handle = sessions.remove(serial);
        if (handle == null) return;

        handle.close();
        if (handle.tile != null) {
            screenGrid.getChildren().remove(handle.tile);
        }

        devices.refresh();
        status.setText("投屏中 " + sessions.size() + " 台设备");
        updateButtons();
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
        new ArrayList<>(sessions.values()).forEach(handle -> {
            handle.close();
            if (handle.tile != null) {
                screenGrid.getChildren().remove(handle.tile);
            }
        });
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
        private final VideoView view;
        private final DeviceController controller;
        private VBox tile;
        private AdbDevice device;

        private DeviceSession(
                ScrcpySession session,
                JavaSoundPlayer audioPlayer,
                VideoView view,
                DeviceController controller) {
            this.session = session;
            this.audioPlayer = audioPlayer;
            this.view = view;
            this.controller = controller;
        }

        @Override
        public void close() {
            session.close();
            audioPlayer.close();
        }

        public VideoView view() {
            return view;
        }
    }
}
