package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.control.DeviceController;
import com.jian.scrcpy.control.UhidController;
import com.jian.scrcpy.control.ClipboardController;
import javafx.scene.input.KeyEvent;
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
import javafx.scene.input.Clipboard;
import javafx.scene.robot.Robot;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.TextInputDialog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ScrcpyApp extends Application {
    private static final double TILE_WIDTH = 360;
    private static final double TILE_HEIGHT = 680;

    private final ListView<AdbDevice> devices = new ListView<>();
    private final Button connect = new Button("连接选中");
    private final Button disconnect = new Button("断开选中");
    private final Button connectAll = new Button("全部连接");
    private final Button refresh = new Button("刷新设备");
    private final Button connectionSettings = new Button("连接设置");
    private final Label status = new Label("正在扫描 ADB 设备...");
    private final TilePane screenGrid = new TilePane();
    private final ScrollPane screenScroll = new ScrollPane(screenGrid);
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Robot mouseRobot = new Robot();
    private Adb adb;
    private volatile String lastDesktopClipboard = "";
    private ConnectionSettings connectionSettingsValue = ConnectionSettings.defaults();
    private final java.util.concurrent.atomic.AtomicBoolean applyingRemoteClipboard = new java.util.concurrent.atomic.AtomicBoolean();

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
                refresh,
                connectionSettings,
                connect,
                disconnect,
                connectAll,
                status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        SplitPane root = new SplitPane();
        root.setDividerPositions(0.22);
        root.getItems().addAll(devices, screenScroll);

        BorderPane content = new BorderPane(root);
        content.setTop(toolbar);
        BorderPane.setMargin(toolbar, new javafx.geometry.Insets(8));

        connect.setOnAction(e -> connectSelected());
        disconnect.setOnAction(e -> disconnectSelected());
        connectAll.setOnAction(e -> connectAll());
        refresh.setOnAction(e -> scanDevices());
        connectionSettings.setOnAction(e -> showConnectionSettings());

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
        startClipboardMonitor();
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

    private void startClipboardMonitor() {
        javafx.animation.Timeline timer = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(250), e -> syncDesktopClipboard()));
        timer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        timer.play();
    }

    private void syncDesktopClipboard() {
        if (applyingRemoteClipboard.get()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasString()) return;
        String text = clipboard.getString();
        if (text == null || Objects.equals(text, lastDesktopClipboard)) return;
        lastDesktopClipboard = text;
        // PC -> Android: synchronize to every active device, without injecting PASTE.
        sessions.values().forEach(session -> session.clipboard.setRemoteClipboard(text));
    }

    private void applyDeviceClipboard(String text) {
        if (text == null) return;
        Platform.runLater(() -> {
            if (Objects.equals(text, lastDesktopClipboard)) return;
            applyingRemoteClipboard.set(true);
            try {
                javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                content.putString(text);
                Clipboard.getSystemClipboard().setContent(content);
                lastDesktopClipboard = text;
            } finally {
                applyingRemoteClipboard.set(false);
            }
        });
    }

    private void scanDevices() {
        status.setText("正在扫描...");
        Thread.startVirtualThread(() -> {
            try {
                List<AdbDevice> list = adb.devices();
                Platform.runLater(() -> {
                    Set<String> selectedSerials = new HashSet<>();
                    devices.getSelectionModel().getSelectedItems().forEach(d -> selectedSerials.add(d.serial()));
                    devices.setItems(FXCollections.observableArrayList(list));
                    for (int i = 0; i < list.size(); i++) {
                        if (selectedSerials.contains(list.get(i).serial())) {
                            devices.getSelectionModel().select(i);
                        }
                    }
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

    private void showConnectionSettings() {
        Dialog<ConnectionSettings> dialog = new Dialog<>();
        dialog.setTitle("scrcpy 连接设置");
        dialog.setHeaderText("设置下一次设备连接使用的参数");
        dialog.initModality(Modality.APPLICATION_MODAL);

        ConnectionSettings s = connectionSettingsValue;

        CheckBox video = new CheckBox("视频");
        video.setSelected(s.video());
        CheckBox audio = new CheckBox("音频");
        audio.setSelected(s.audio());
        CheckBox control = new CheckBox("控制");
        control.setSelected(s.control());
        CheckBox showTouches = new CheckBox("显示触摸点");
        showTouches.setSelected(s.showTouches());
        CheckBox stayAwake = new CheckBox("保持唤醒");
        stayAwake.setSelected(s.stayAwake());
        CheckBox turnScreenOff = new CheckBox("关闭设备屏幕");
        turnScreenOff.setSelected(s.turnScreenOff());

        ComboBox<String> videoCodec = new ComboBox<>(FXCollections.observableArrayList("h264", "h265", "av1"));
        videoCodec.setValue(s.videoCodec());
        ComboBox<String> audioCodec = new ComboBox<>(FXCollections.observableArrayList("opus", "aac", "flac"));
        audioCodec.setValue(s.audioCodec());

        TextField videoBitRate = new TextField(formatBitRate(s.videoBitRate()));
        videoBitRate.setPromptText("例如 8M");
        TextField audioBitRate = new TextField(formatBitRate(s.audioBitRate()));
        audioBitRate.setPromptText("例如 128K");
        TextField maxSize = new TextField(s.maxSize() <= 0 ? "" : Integer.toString(s.maxSize()));
        maxSize.setPromptText("例如 1920");
        TextField maxFps = new TextField(s.maxFps() == null ? "" : s.maxFps());
        maxFps.setPromptText("例如 60");
        TextField crop = new TextField(Objects.requireNonNullElse(s.crop(), ""));
        crop.setPromptText("例如 1080:1920:0:0");
        TextField startApp = new TextField(Objects.requireNonNullElse(s.startApp(), ""));
        startApp.setPromptText("可选：启动应用包名");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(video, 0, row); grid.add(audio, 1, row); grid.add(control, 2, row++);
        grid.add(new Label("视频编码"), 0, row); grid.add(videoCodec, 1, row++, 2, 1);
        grid.add(new Label("音频编码"), 0, row); grid.add(audioCodec, 1, row++, 2, 1);
        grid.add(new Label("视频码率"), 0, row); grid.add(videoBitRate, 1, row++, 2, 1);
        grid.add(new Label("音频码率"), 0, row); grid.add(audioBitRate, 1, row++, 2, 1);
        grid.add(new Label("最大分辨率"), 0, row); grid.add(maxSize, 1, row++, 2, 1);
        grid.add(new Label("最大帧率"), 0, row); grid.add(maxFps, 1, row++, 2, 1);
        grid.add(new Label("裁剪"), 0, row); grid.add(crop, 1, row++, 2, 1);
        grid.add(new Label("启动应用"), 0, row); grid.add(startApp, 1, row++, 2, 1);
        grid.add(showTouches, 0, row); grid.add(stayAwake, 1, row); grid.add(turnScreenOff, 2, row);

        dialog.getDialogPane().setContent(grid);
        ButtonType ok = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ok) return null;
            try {
                return new ConnectionSettings(
                        video.isSelected(), audio.isSelected(), control.isSelected(),
                        videoCodec.getValue(), audioCodec.getValue(),
                        parseBitRate(videoBitRate.getText()),
                        parseBitRate(audioBitRate.getText()),
                        parsePositiveInt(maxSize.getText(), 0),
                        blankToNull(maxFps.getText()),
                        showTouches.isSelected(), stayAwake.isSelected(),
                        turnScreenOff.isSelected(), blankToNull(crop.getText()),
                        blankToNull(startApp.getText()));
            } catch (IllegalArgumentException ex) {
                new Alert(Alert.AlertType.ERROR, "连接设置无效", ex.getMessage(), ButtonType.OK).showAndWait();
                return null;
            }
        });
        dialog.showAndWait().ifPresent(value -> {
            connectionSettingsValue = value;
            status.setText("连接设置已保存");
        });
    }

    private static String formatBitRate(long value) {
        if (value <= 0) return "";
        if (value % 1_000_000 == 0) return (value / 1_000_000) + "M";
        if (value % 1_000 == 0) return (value / 1_000) + "K";
        return Long.toString(value);
    }

    private static long parseBitRate(String value) {
        String s = Objects.requireNonNullElse(value, "").trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return 0;
        long multiplier = 1;
        if (s.endsWith("M")) { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("K")) { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        long number;
        try { number = Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("码率必须是数字，可使用 K/M 后缀"); }
        if (number < 0 || number > Long.MAX_VALUE / multiplier) throw new IllegalArgumentException("码率超出范围");
        return number * multiplier;
    }

    private static int parsePositiveInt(String value, int defaultValue) {
        String s = Objects.requireNonNullElse(value, "").trim();
        if (s.isEmpty()) return defaultValue;
        try {
            int n = Integer.parseInt(s);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数值必须是非负整数");
        }
    }

    private static String blankToNull(String value) {
        String s = Objects.requireNonNullElse(value, "").trim();
        return s.isEmpty() ? null : s;
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
                ScrcpyOptions d = connectionSettingsValue.toOptions(device.serial(), ScrcpyOptions.defaults());
                ScrcpyOptions options = new ScrcpyOptions(
                        device.serial(), d.scid(), d.localPort(),
                        d.video(), d.audio(), d.control(),
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

                    UhidController uhid = new UhidController(runningSession::sendControl);
                    ClipboardController clipboard = new ClipboardController(runningSession::sendControl);
                    runningSession.addDeviceMessageListener(uhid::handle);
                    runningSession.addDeviceMessageListener(clipboard::handle);
                    clipboard.onRemoteClipboard(this::applyDeviceClipboard);
                    DeviceSession handle = new DeviceSession(runningSession, player, view,
                            new DeviceController(runningSession::sendControl), uhid, clipboard);
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

        ComboBox<MouseMode> mouseMode = new ComboBox<>(FXCollections.observableArrayList(MouseMode.values()));
        mouseMode.setValue(MouseMode.ABSOLUTE_TOUCH);
        mouseMode.setPrefWidth(112);
        mouseMode.setOnAction(e -> setMouseMode(handle, mouseMode.getValue()));

        Button close = new Button("断开");
        close.setOnAction(e -> disconnect(device.serial()));

        HBox header = new HBox(6, title, new Region(), mouseMode, close);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        tile.getChildren().addAll(header, viewport);
        handle.tile = tile;
        handle.device = device;

        screenGrid.getChildren().add(tile);
    }

    private void installControls(DeviceSession handle) {
        VideoView view = handle.view();
        view.setFocusTraversable(true);
        view.setCursor(Cursor.DEFAULT);

        view.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                captureRelativeMouse(handle);
            }
        });
        view.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (!handle.recentering) handle.relativePointerValid = false;
        });
        view.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                sendRelativeMove(handle, e);
                return;
            }
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p != null) handle.controller.hover(p);
        });
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            view.requestFocus();
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                int button = MouseInputMapper.uhidButton(e.getButton());
                if (button != 0) handle.uhid.mouse().button(button, true);
                handle.resetRelativePointer(e.getX(), e.getY());
                e.consume();
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;
            sendTouch(handle, e, 0, 1f, 1);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                sendRelativeMove(handle, e);
                e.consume();
                return;
            }
            if (!e.isPrimaryButtonDown()) return;
            sendTouch(handle, e, 2, 1f, 0);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                int button = MouseInputMapper.uhidButton(e.getButton());
                if (button != 0) handle.uhid.mouse().button(button, false);
                e.consume();
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;
            sendTouch(handle, e, 1, 0f, 1);
            e.consume();
        });
        view.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                // JavaFX deltas are pixels; convert to HID wheel detents while
                // preserving small deltas in UhidMouse's residual accumulator.
                handle.uhid.mouse().scroll(e.getDeltaX() / 40.0, e.getDeltaY() / 40.0);
                e.consume();
                return;
            }
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p == null) return;
            float h = (float) Math.max(-16, Math.min(16, e.getDeltaX() / 20.0));
            float v = (float) Math.max(-16, Math.min(16, e.getDeltaY() / 20.0));
            handle.controller.scroll(p, h, v, 0);
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            int usage = HidKeyMapper.usage(e.getCode());
            if (usage < 0) return;
            handle.uhid.keyboard().key(usage, true,
                    HidKeyMapper.modifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown()));
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            int usage = HidKeyMapper.usage(e.getCode());
            if (usage < 0) return;
            handle.uhid.keyboard().key(usage, false,
                    HidKeyMapper.modifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown()));
            e.consume();
        });
    }

    private void setMouseMode(DeviceSession handle, MouseMode mode) {
        handle.mouseMode = mode == null ? MouseMode.ABSOLUTE_TOUCH : mode;
        handle.relativePointerValid = false;
        handle.view().setCursor(Cursor.DEFAULT);
        if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
            handle.uhid.openMouse();
            handle.view().requestFocus();
            Platform.runLater(() -> captureRelativeMouse(handle));
        } else {
            handle.uhid.mouse().buttons(0);
        }
    }

    private void captureRelativeMouse(DeviceSession handle) {
        if (handle.mouseMode != MouseMode.UHID_RELATIVE || !handle.view().isVisible()) return;
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        if (center == null) return;
        handle.recentering = true;
        handle.relativePointerValid = true;
        mouseRobot.mouseMove(center.getX(), center.getY());
        handle.recentering = false;
    }

    private void sendRelativeMove(DeviceSession handle, MouseEvent e) {
        if (handle.recentering || handle.mouseMode != MouseMode.UHID_RELATIVE) return;
        if (!handle.relativePointerValid) {
            captureRelativeMouse(handle);
            return;
        }
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        if (center == null) return;

        javafx.geometry.Point2D screen = handle.view().localToScreen(e.getX(), e.getY());
        if (screen == null) return;

        double dx = screen.getX() - center.getX();
        double dy = screen.getY() - center.getY();
        if (dx == 0 && dy == 0) return;

        handle.uhid.mouse().move(dx, dy);
        handle.recentering = true;
        mouseRobot.mouseMove(center.getX(), center.getY());
        handle.recentering = false;
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
        refresh.setDisable(false);
        connectionSettings.setDisable(false);
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

    private record ConnectionSettings(
            boolean video,
            boolean audio,
            boolean control,
            String videoCodec,
            String audioCodec,
            long videoBitRate,
            long audioBitRate,
            int maxSize,
            String maxFps,
            boolean showTouches,
            boolean stayAwake,
            boolean turnScreenOff,
            String crop,
            String startApp) {

        static ConnectionSettings defaults() {
            ScrcpyOptions d = ScrcpyOptions.defaults();
            return new ConnectionSettings(d.video(), d.audio(), d.control(),
                    d.videoCodec(), d.audioCodec(), d.videoBitRate(), d.audioBitRate(),
                    d.maxSize(), d.maxFps(), d.showTouches(), d.stayAwake(),
                    d.turnScreenOff(), d.crop(), d.startApp());
        }

        ScrcpyOptions toOptions(String serial, ScrcpyOptions base) {
            return new ScrcpyOptions(serial, base.scid(), base.localPort(),
                    video, audio, control, videoCodec, audioCodec,
                    videoBitRate, audioBitRate, maxSize, maxFps,
                    showTouches, stayAwake, turnScreenOff, crop, startApp);
        }
    }

    private enum MouseMode {
        ABSOLUTE_TOUCH("触控"),
        UHID_RELATIVE("UHID 鼠标");

        private final String label;
        MouseMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final class DeviceSession implements AutoCloseable {
        private final ScrcpySession session;
        private final JavaSoundPlayer audioPlayer;
        private final VideoView view;
        private final DeviceController controller;
        private final UhidController uhid;
        private final ClipboardController clipboard;
        private VBox tile;
        private AdbDevice device;
        private MouseMode mouseMode = MouseMode.ABSOLUTE_TOUCH;
        private boolean relativePointerValid;
        private boolean recentering;

        private DeviceSession(
                ScrcpySession session,
                JavaSoundPlayer audioPlayer,
                VideoView view,
                DeviceController controller,
                UhidController uhid,
                ClipboardController clipboard) {
            this.session = session;
            this.audioPlayer = audioPlayer;
            this.view = view;
            this.controller = controller;
            this.uhid = uhid;
            this.clipboard = clipboard;
        }

        @Override
        public void close() {
            uhid.close();
            session.close();
            audioPlayer.close();
        }

        private void resetRelativePointer(double x, double y) {
            relativePointerValid = true;
            recentering = false;
        }

        public VideoView view() {
            return view;
        }
    }
}
