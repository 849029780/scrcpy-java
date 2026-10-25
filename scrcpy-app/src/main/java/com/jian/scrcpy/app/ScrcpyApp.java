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

    // Device management is intentionally kept out of the main workspace and
    // shown from the "设备" menu as a modal popup.
    private final ListView<AdbDevice> devices = new ListView<>();
    private final Button connect = new Button("连接选中");
    private final Button disconnect = new Button("断开选中");
    private final Button connectAll = new Button("全部连接");
    private final Button refresh = new Button("刷新设备");
    private final Label status = new Label("正在扫描 ADB 设备...");
    private final Label primaryStatus = new Label("主设备：无");
    private final TilePane secondaryGrid = new TilePane();
    private final ScrollPane secondaryScroll = new ScrollPane(secondaryGrid);
    private final StackPane primaryHost = new StackPane();
    private final BorderPane screenArea = new BorderPane();
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private volatile String primarySerial;
    private final Robot mouseRobot = new Robot();
    private Adb adb;
    private volatile String lastDesktopClipboard = "";
    private final java.util.concurrent.atomic.AtomicBoolean applyingRemoteClipboard = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public void start(Stage stage) {
        String adbPath = System.getProperty("scrcpy.adb", "adb");
        adb = new Adb(adbPath, null);

        devices.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        devices.setCellFactory(v -> deviceCell());
        devices.setPlaceholder(new Label("未发现 ADB 设备"));
        devices.setPrefHeight(420);

        secondaryGrid.setHgap(10);
        secondaryGrid.setVgap(10);
        secondaryGrid.setPrefColumns(3);
        secondaryGrid.setPrefTileWidth(TILE_WIDTH);
        secondaryGrid.setPrefTileHeight(TILE_HEIGHT);
        secondaryGrid.setPadding(new Insets(10));
        secondaryGrid.setTileAlignment(Pos.TOP_LEFT);

        secondaryScroll.setFitToWidth(true);
        secondaryScroll.setFitToHeight(true);
        secondaryScroll.setPannable(true);

        primaryHost.setPadding(new Insets(10));
        primaryHost.setStyle("-fx-background-color: #1b1b1b;");
        primaryHost.setMinWidth(0);
        primaryHost.setPrefWidth(0);
        primaryHost.setManaged(false);
        primaryHost.setVisible(false);

        screenArea.setCenter(secondaryScroll);
        screenArea.setStyle("-fx-background-color: #181818;");

        MenuItem deviceManager = new MenuItem("设备列表 / 连接");
        deviceManager.setOnAction(e -> showDeviceManager());
        MenuItem refreshDevices = new MenuItem("刷新设备");
        refreshDevices.setOnAction(e -> scanDevices());
        MenuButton deviceMenu = new MenuButton("设备");
        deviceMenu.getItems().addAll(deviceManager, refreshDevices);

        HBox toolbar = new HBox(12, deviceMenu, primaryStatus, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        BorderPane content = new BorderPane(screenArea);
        content.setTop(toolbar);

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

    private void showDeviceManager() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("设备管理");
        dialog.setHeaderText("选择设备进行连接、断开或刷新");
        dialog.initModality(Modality.APPLICATION_MODAL);

        Button refreshButton = new Button("刷新");
        Button connectButton = new Button("连接选中");
        Button connectAllButton = new Button("全部连接");
        Button disconnectButton = new Button("断开选中");

        refreshButton.setOnAction(e -> scanDevices());
        connectButton.setOnAction(e -> connectSelected());
        connectAllButton.setOnAction(e -> connectAll());
        disconnectButton.setOnAction(e -> disconnectSelected());

        HBox actions = new HBox(8, refreshButton, connectButton, connectAllButton, disconnectButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, devices, actions);
        box.setPadding(new Insets(8));
        box.setPrefSize(620, 520);

        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
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

    private Optional<ConnectionSettings> showConnectionSettings() {
        Dialog<ConnectionSettings> dialog = new Dialog<>();
        dialog.setTitle("scrcpy 连接设置");
        dialog.setHeaderText("连接设备前设置本次连接参数");
        dialog.initModality(Modality.APPLICATION_MODAL);

        CheckBox video = new CheckBox("视频"); video.setSelected(true);
        CheckBox audio = new CheckBox("音频"); audio.setSelected(true);
        CheckBox control = new CheckBox("控制"); control.setSelected(true);
        CheckBox showTouches = new CheckBox("显示触摸点");
        CheckBox stayAwake = new CheckBox("保持唤醒");
        CheckBox turnScreenOff = new CheckBox("关闭设备屏幕");

        ComboBox<String> videoCodec = combo("h264", "h265", "av1");
        ComboBox<String> audioCodec = combo("opus", "aac", "flac");
        ComboBox<String> videoBitRate = combo("2M", "4M", "6M", "8M", "10M", "12M", "16M", "20M");
        ComboBox<String> audioBitRate = combo("64K", "96K", "128K", "160K", "192K", "256K", "320K");
        ComboBox<String> maxSize = combo("不限制", "720", "1080", "1440", "1920", "2560", "3840");
        ComboBox<String> maxFps = combo("不限制", "30", "45", "60", "90", "120", "144");
        ComboBox<String> crop = combo("不裁剪", "1080:1920:0:0", "1920:1080:0:0", "1440:2560:0:0");
        TextField startApp = new TextField();
        startApp.setPromptText("可选：应用包名");

        videoCodec.setValue("h264"); audioCodec.setValue("opus");
        videoBitRate.setValue("8M"); audioBitRate.setValue("128K");
        maxSize.setValue("1920"); maxFps.setValue("60"); crop.setValue("不裁剪");

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
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
        ButtonType ok = new ButtonType("连接", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ok) return null;
            return new ConnectionSettings(video.isSelected(), audio.isSelected(), control.isSelected(),
                    videoCodec.getValue(), audioCodec.getValue(),
                    parseBitRate(videoBitRate.getValue()), parseBitRate(audioBitRate.getValue()),
                    parsePositiveInt(maxSize.getValue(), 0),
                    "不限制".equals(maxFps.getValue()) ? null : maxFps.getValue(),
                    showTouches.isSelected(), stayAwake.isSelected(), turnScreenOff.isSelected(),
                    "不裁剪".equals(crop.getValue()) ? null : crop.getValue(), blankToNull(startApp.getText()));
        });
        return dialog.showAndWait();
    }

    @SafeVarargs
    private static <T> ComboBox<T> combo(T... values) {
        ComboBox<T> box = new ComboBox<>(FXCollections.observableArrayList(values));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
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

        showConnectionSettings().ifPresent(settings -> {
            status.setText("正在连接 " + targets.size() + " 台设备...");
            targets.forEach(device -> connectDevice(device, settings));
        });
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings) {
        Thread.startVirtualThread(() -> {
            JavaSoundPlayer audioPlayer = null;
            ScrcpySession session = null;
            try {
                ScrcpyOptions d = settings.toOptions(device.serial(), ScrcpyOptions.defaults());
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
        tile.setStyle("-fx-background-color: #202020; -fx-border-color: #444444; -fx-border-width: 1;");
        tile.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMaxSize(TILE_WIDTH, TILE_HEIGHT);

        Label title = new Label(device.displayName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setStyle("-fx-font-weight: bold; -fx-padding: 4 6 4 6;");

        StackPane viewport = new StackPane(handle.view());
        viewport.setStyle("-fx-background-color: #222222;");

        handle.view().setPreserveRatio(true);
        handle.view().setSmooth(true);

        installControls(handle);

        ComboBox<MouseMode> mouseMode = new ComboBox<>(FXCollections.observableArrayList(MouseMode.values()));
        mouseMode.setValue(MouseMode.ABSOLUTE_TOUCH);
        mouseMode.setPrefWidth(112);
        mouseMode.setOnAction(e -> setMouseMode(handle, mouseMode.getValue()));

        Button primary = new Button("设为主设备");
        primary.setOnAction(e -> togglePrimary(handle));

        Button close = new Button("断开");
        close.setOnAction(e -> disconnect(device.serial()));

        HBox header = new HBox(6, title, new Region(), mouseMode, primary, close);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        tile.getChildren().addAll(header, viewport);
        VBox.setVgrow(viewport, Priority.ALWAYS);

        handle.tile = tile;
        handle.viewport = viewport;
        handle.primaryButton = primary;
        handle.device = device;

        resizeTile(handle, false);
        secondaryGrid.getChildren().add(tile);
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
                double dx = relativeDeltaX(handle, e);
                double dy = relativeDeltaY(handle, e);
                if (dx != 0 || dy != 0) {
                    broadcastRelativeMove(handle, dx, dy);
                }
                return;
            }
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p != null) broadcastHover(handle, p);
        });
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            view.requestFocus();
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                int button = MouseInputMapper.uhidButton(e.getButton());
                if (button != 0) broadcastMouseButton(handle, button, true);
                handle.resetRelativePointer(e.getX(), e.getY());
                e.consume();
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p != null) broadcastTouch(handle, DeviceController.ACTION_DOWN, p, 1f, 1);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                double dx = relativeDeltaX(handle, e);
                double dy = relativeDeltaY(handle, e);
                if (dx != 0 || dy != 0) broadcastRelativeMove(handle, dx, dy);
                e.consume();
                return;
            }
            if (!e.isPrimaryButtonDown()) return;
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p != null) broadcastTouch(handle, DeviceController.ACTION_MOVE, p, 1f, 0);
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                int button = MouseInputMapper.uhidButton(e.getButton());
                if (button != 0) broadcastMouseButton(handle, button, false);
                e.consume();
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p != null) broadcastTouch(handle, DeviceController.ACTION_UP, p, 0f, 1);
            e.consume();
        });
        view.addEventHandler(ScrollEvent.SCROLL, e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                broadcastMouseScroll(handle, e.getDeltaX() / 40.0, e.getDeltaY() / 40.0);
                e.consume();
                return;
            }
            DeviceController.ScreenPosition p = InputMapper.position(view, e.getX(), e.getY());
            if (p == null) return;
            float h = (float) Math.max(-16, Math.min(16, e.getDeltaX() / 20.0));
            float v = (float) Math.max(-16, Math.min(16, e.getDeltaY() / 20.0));
            broadcastScroll(handle, p, h, v);
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            int usage = HidKeyMapper.usage(e.getCode());
            if (usage < 0) return;
            int modifiers = HidKeyMapper.modifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
            broadcastKeyboard(handle, usage, true, modifiers);
            e.consume();
        });
        view.addEventHandler(KeyEvent.KEY_RELEASED, e -> {
            int usage = HidKeyMapper.usage(e.getCode());
            if (usage < 0) return;
            int modifiers = HidKeyMapper.modifiers(e.isShiftDown(), e.isControlDown(), e.isAltDown(), e.isMetaDown());
            broadcastKeyboard(handle, usage, false, modifiers);
            e.consume();
        });
    }

    private boolean isPrimary(DeviceSession handle) {
        return primarySerial != null && handle.device != null && primarySerial.equals(handle.device.serial());
    }

    private List<DeviceSession> inputTargets(DeviceSession source) {
        if (!isPrimary(source)) return List.of(source);
        return new ArrayList<>(sessions.values());
    }

    private void broadcastKeyboard(DeviceSession source, int usage, boolean down, int modifiers) {
        for (DeviceSession target : inputTargets(source)) {
            target.uhid.keyboard().key(usage, down, modifiers);
        }
    }

    private void broadcastMouseButton(DeviceSession source, int button, boolean down) {
        for (DeviceSession target : inputTargets(source)) {
            target.uhid.mouse().button(button, down);
        }
    }

    private void broadcastMouseScroll(DeviceSession source, double horizontal, double vertical) {
        for (DeviceSession target : inputTargets(source)) {
            target.uhid.mouse().scroll(horizontal, vertical);
        }
    }

    private void broadcastRelativeMove(DeviceSession source, double dx, double dy) {
        for (DeviceSession target : inputTargets(source)) {
            target.uhid.mouse().move(dx, dy);
        }
        recenterRelativePointer(source);
    }

    private void broadcastHover(DeviceSession source, DeviceController.ScreenPosition position) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = scalePosition(position, target.view());
            if (p != null) target.controller.hover(p);
        }
    }

    private void broadcastTouch(DeviceSession source, int action, DeviceController.ScreenPosition position,
                                float pressure, int actionButton) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = scalePosition(position, target.view());
            if (p != null) {
                int buttons = action == DeviceController.ACTION_UP ? 0 : 1;
                target.controller.touch(action, DeviceController.POINTER_ID_MOUSE, p, pressure, actionButton, buttons);
            }
        }
    }

    private void broadcastScroll(DeviceSession source, DeviceController.ScreenPosition position,
                                 float horizontal, float vertical) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = scalePosition(position, target.view());
            if (p != null) target.controller.scroll(p, horizontal, vertical, 0);
        }
    }

    private static DeviceController.ScreenPosition scalePosition(DeviceController.ScreenPosition source,
                                                                  VideoView targetView) {
        int width = targetView.frameWidth();
        int height = targetView.frameHeight();
        if (width <= 0 || height <= 0) return null;
        int x = Math.min(width - 1, Math.max(0,
                (int) Math.round((source.x() / (double) Math.max(1, source.screenWidth() - 1)) * (width - 1))));
        int y = Math.min(height - 1, Math.max(0,
                (int) Math.round((source.y() / (double) Math.max(1, source.screenHeight() - 1)) * (height - 1))));
        return new DeviceController.ScreenPosition(x, y, width, height);
    }

    private double relativeDeltaX(DeviceSession handle, MouseEvent e) {
        if (handle.recentering || handle.mouseMode != MouseMode.UHID_RELATIVE || !handle.relativePointerValid) return 0;
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        javafx.geometry.Point2D screen = handle.view().localToScreen(e.getX(), e.getY());
        if (center == null || screen == null) return 0;
        return screen.getX() - center.getX();
    }

    private double relativeDeltaY(DeviceSession handle, MouseEvent e) {
        if (handle.recentering || handle.mouseMode != MouseMode.UHID_RELATIVE || !handle.relativePointerValid) return 0;
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        javafx.geometry.Point2D screen = handle.view().localToScreen(e.getX(), e.getY());
        if (center == null || screen == null) return 0;
        return screen.getY() - center.getY();
    }

    private void recenterRelativePointer(DeviceSession handle) {
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        if (center == null) return;
        handle.recentering = true;
        handle.relativePointerValid = true;
        mouseRobot.mouseMove(center.getX(), center.getY());
        handle.recentering = false;
    }

    private void togglePrimary(DeviceSession handle) {
        if (isPrimary(handle)) {
            clearPrimary();
        } else {
            setPrimary(handle);
        }
    }

    private void setPrimary(DeviceSession handle) {
        if (handle.device == null) return;
        if (primarySerial != null && primarySerial.equals(handle.device.serial())) return;

        DeviceSession old = primarySerial == null ? null : sessions.get(primarySerial);
        if (old != null) {
            old.primary = false;
            if (old.tile != null) {
                primaryHost.getChildren().remove(old.tile);
                secondaryGrid.getChildren().add(old.tile);
                resizeTile(old, false);
            }
        }

        primarySerial = handle.device.serial();
        handle.primary = true;
        if (handle.tile != null) {
            secondaryGrid.getChildren().remove(handle.tile);
            primaryHost.getChildren().setAll(handle.tile);
        }
        screenArea.setLeft(primaryHost);
        primaryHost.setManaged(true);
        primaryHost.setVisible(true);
        primaryHost.setPrefWidth(620);
        resizeTile(handle, true);
        refreshPrimaryUi();
    }

    private void clearPrimary() {
        DeviceSession old = primarySerial == null ? null : sessions.get(primarySerial);
        if (old == null) {
            primarySerial = null;
            screenArea.setLeft(null);
            primaryHost.getChildren().clear();
            primaryHost.setManaged(false);
            primaryHost.setVisible(false);
            refreshPrimaryUi();
            return;
        }

        primarySerial = null;
        old.primary = false;
        primaryHost.getChildren().remove(old.tile);
        secondaryGrid.getChildren().add(0, old.tile);
        resizeTile(old, false);
        primaryHost.setManaged(false);
        primaryHost.setVisible(false);
        primaryHost.setPrefWidth(0);
        screenArea.setLeft(null);
        refreshPrimaryUi();
    }

    private void resizeTile(DeviceSession handle, boolean primary) {
        if (handle.tile == null || handle.view() == null) return;
        double width = primary ? 620 : TILE_WIDTH;
        double height = primary ? 820 : TILE_HEIGHT;
        handle.tile.setPrefSize(width, height);
        handle.tile.setMinSize(width, height);
        handle.tile.setMaxSize(width, height);
        handle.viewport.setPrefSize(width, height - 34);
        handle.view().setFitWidth(width - 8);
        handle.view().setFitHeight(height - 42);
        if (handle.primaryButton != null) {
            handle.primaryButton.setText(primary ? "取消主设备" : "设为主设备");
        }
    }

    private void refreshPrimaryUi() {
        if (primarySerial == null) {
            primaryStatus.setText("主设备：无");
        } else {
            DeviceSession s = sessions.get(primarySerial);
            primaryStatus.setText("主设备：" + (s == null || s.device == null ? primarySerial : s.device.displayName()));
        }
        sessions.values().forEach(s -> {
            if (s.primaryButton != null) s.primaryButton.setText(isPrimary(s) ? "取消主设备" : "设为主设备");
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

        // Do not close Netty/audio resources on the JavaFX Application Thread.
        // In particular, closing the per-device EventLoopGroup can block while
        // channels are being torn down. The UI tile is removed immediately,
        // while the actual session shutdown happens in a virtual thread.
        if (handle.tile != null) {
            primaryHost.getChildren().remove(handle.tile);
            secondaryGrid.getChildren().remove(handle.tile);
        }
        if (isPrimary(handle)) {
            primarySerial = null;
            screenArea.setLeft(null);
            primaryHost.setManaged(false);
            primaryHost.setVisible(false);
            primaryHost.setPrefWidth(0);
            refreshPrimaryUi();
        }

        devices.refresh();
        status.setText("投屏中 " + sessions.size() + " 台设备");
        updateButtons();

        Thread.startVirtualThread(() -> {
            try {
                handle.close();
            } catch (Throwable t) {
                System.err.println("[scrcpy-java] disconnect failed for " + serial + ": " + t);
                t.printStackTrace(System.err);
            }
        });
    }

    private void updateButtons() {
        boolean hasReady = devices.getItems().stream()
                .anyMatch(d -> d.ready() && !sessions.containsKey(d.serial()));
        boolean hasSelection = !devices.getSelectionModel().getSelectedItems().isEmpty();
        connect.setDisable(!hasSelection);
        disconnect.setDisable(!hasSelection);
        connectAll.setDisable(!hasReady);
        refresh.setDisable(false);
        refreshPrimaryUi();
    }

    private void closeAllSessions() {
        new ArrayList<>(sessions.values()).forEach(handle -> {
            try {
                handle.close();
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            }
            if (handle.tile != null) {
                primaryHost.getChildren().remove(handle.tile);
                secondaryGrid.getChildren().remove(handle.tile);
            }
        });
        sessions.clear();
        primarySerial = null;
        primaryHost.getChildren().clear();
        primaryHost.setManaged(false);
        primaryHost.setVisible(false);
        primaryHost.setPrefWidth(0);
        screenArea.setLeft(null);
    }

    @Override
    public void stop() {
        closeAllSessions();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record ConnectionSettings(
            boolean video, boolean audio, boolean control,
            String videoCodec, String audioCodec,
            long videoBitRate, long audioBitRate,
            int maxSize, String maxFps,
            boolean showTouches, boolean stayAwake,
            boolean turnScreenOff, String crop, String startApp) {

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
        private StackPane viewport;
        private Button primaryButton;
        private AdbDevice device;
        private boolean primary;
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
            Throwable first = null;

            // Release virtual HID devices while the control channel is still
            // alive. Failure here must not prevent the actual scrcpy session
            // and audio resources from being closed.
            try {
                uhid.close();
            } catch (Throwable t) {
                first = t;
            }

            try {
                session.close();
            } catch (Throwable t) {
                if (first == null) first = t;
                else first.addSuppressed(t);
            }

            try {
                audioPlayer.close();
            } catch (Throwable t) {
                if (first == null) first = t;
                else first.addSuppressed(t);
            }

            if (first != null) {
                throw new IllegalStateException("Failed to close device session", first);
            }
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
