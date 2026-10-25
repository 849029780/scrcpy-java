package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.control.DeviceController;
import com.jian.scrcpy.control.UhidController;
import com.jian.scrcpy.control.ClipboardController;
import com.jian.scrcpy.input.AndroidKeycodes;
import com.jian.scrcpy.protocol.ControlMessages;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCode;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScrcpyApp extends Application {
    private static final double TILE_WIDTH = 360;
    private static final double TILE_HEIGHT = 680;
    private static final double RELATIVE_MOUSE_EDGE_MARGIN = 2.0;

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
    /** User-selected connections survive temporary ADB disconnects. */
    private final Map<String, ConnectionSettings> desiredConnections = new ConcurrentHashMap<>();
    /** Map primary-device absolute coordinates to each target when enabled. */
    private boolean adaptScreenClick = false;
    private final Set<String> reconnecting = ConcurrentHashMap.newKeySet();
    private volatile String primarySerial;
    /** Preserve the user's primary-device choice across temporary ADB loss. */
    private volatile String desiredPrimarySerial;
    private final Robot mouseRobot = new Robot();
    private Adb adb;
    private volatile String lastDesktopClipboard = "";
    private final java.util.concurrent.atomic.AtomicBoolean applyingRemoteClipboard = new java.util.concurrent.atomic.AtomicBoolean();
    // ADB polling spends almost all of its time blocked in Process I/O, so a
    // virtual-thread scheduler avoids dedicating a platform thread to it.
    private final ScheduledExecutorService adbMonitor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("scrcpy-adb-monitor").factory());

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
        Scene scene = new Scene(content, 1400, 900);
        // A fast drag can cross from one VideoView to another before JavaFX
        // delivers MOUSE_EXITED/MOUSE_DRAGGED to the original view. Keep a
        // scene-level gesture guard so a touch is always cancelled as soon as
        // the pointer leaves the view that started it.
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            DeviceSession active = activeMouseInput;
            if (active != null && !isScreenPointInsideView(active.view(), e.getScreenX(), e.getScreenY())) {
                endMouseInput(active, e);
                activeMouseInput = null;
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            DeviceSession active = activeMouseInput;
            if (active != null) {
                endMouseInput(active, e);
                activeMouseInput = null;
                e.consume();
            }
        });
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            closeAllSessions();
            Platform.exit();
        });
        stage.show();
        // A desktop focus change must never leave a remote keyboard key or
        // mouse button logically pressed. JavaFX can stop delivering release
        // events when another window becomes active.
        stage.focusedProperty().addListener((obs, oldFocused, focused) -> {
            if (!focused) releaseAllTransientInput();
        });

        scanDevices();
        startClipboardMonitor();
        startAdbMonitor();
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

    private void startAdbMonitor() {
        adbMonitor.scheduleWithFixedDelay(this::pollAdbState, 1500, 1500, TimeUnit.MILLISECONDS);
    }

    private void pollAdbState() {
        try {
            Map<String, AdbDevice> current = new HashMap<>();
            adb.devices().forEach(d -> current.put(d.serial(), d));
            List<String> lost = new ArrayList<>();
            sessions.forEach((serial, handle) -> {
                AdbDevice d = current.get(serial);
                if (d == null || !d.ready()) lost.add(serial);
            });
            if (!lost.isEmpty()) {
                Platform.runLater(() -> lost.forEach(serial -> {
                    if (sessions.containsKey(serial)) {
                        status.setText("设备已离线: " + serial + "，等待自动重连");
                        disconnectForReconnect(serial);
                    }
                }));
            }
            current.values().stream()
                    .filter(AdbDevice::ready)
                    .filter(d -> desiredConnections.containsKey(d.serial()))
                    .filter(d -> !sessions.containsKey(d.serial()))
                    .forEach(d -> {
                        if (reconnecting.add(d.serial())) {
                            ConnectionSettings settings = desiredConnections.get(d.serial());
                            Platform.runLater(() -> {
                                status.setText("设备已恢复，正在重连: " + d.displayName());
                                connectDevice(d, settings, true);
                            });
                        }
                    });
        } catch (Throwable ignored) {
            // A transient ADB restart must not tear down active sessions.
        }
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
            targets.forEach(device -> {
                desiredConnections.put(device.serial(), settings);
                connectDevice(device, settings, false);
            });
        });
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings) {
        connectDevice(device, settings, false);
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings, boolean reconnect) {
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

                java.util.concurrent.atomic.AtomicBoolean firstFrameLayout = new java.util.concurrent.atomic.AtomicBoolean();

                ScrcpySession runningSession = session;
                runningSession.onTransportFailure(cause -> Platform.runLater(() -> {
                    DeviceSession active = sessions.get(device.serial());
                    if (active == null || active.session != runningSession) {
                        return;
                    }
                    status.setText("设备连接异常: " + device.displayName() + "，正在隔离并重连");
                    disconnectForReconnect(device.serial());
                }));
                runningSession.start(
                        ScrcpyServerArtifact.resolve(),
                        frame -> {
                            view.accept(frame);
                            if (firstFrameLayout.compareAndSet(false, true)) {
                                Platform.runLater(() -> resizeTileForCurrentRole(device.serial()));
                            }
                        },
                        player::accept);

                Platform.runLater(() -> {
                    reconnecting.remove(device.serial());
                    if (!desiredConnections.containsKey(device.serial())) {
                        runningSession.close();
                        player.close();
                        return;
                    }
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
                    if (Objects.equals(desiredPrimarySerial, device.serial())) {
                        setPrimary(handle);
                    }
                    devices.refresh();
                    status.setText("投屏中 " + sessions.size() + " 台设备");
                    updateButtons();
                });
            } catch (Throwable e) {
                reconnecting.remove(device.serial());
                if (session != null) session.close();
                if (audioPlayer != null) audioPlayer.close();
                Platform.runLater(() -> {
                    status.setText((reconnect ? "自动重连 " : "连接 ") + device.displayName() + " 失败: " + e.getMessage());
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
        viewport.setStyle("-fx-background-color: transparent;");
        viewport.setAlignment(Pos.TOP_CENTER);

        VBox navigationBar = createDeviceNavigationBar(handle);
        HBox videoAndNavigation = new HBox(3, viewport, navigationBar);
        videoAndNavigation.setAlignment(Pos.TOP_LEFT);

        handle.view().setPreserveRatio(true);
        handle.view().setSmooth(true);

        installControls(handle);

        Button close = new Button("断开");
        close.setOnAction(e -> disconnect(device.serial()));

        HBox header = new HBox(6, title, new Region(), close);
        HBox.setHgrow(header.getChildren().get(1), Priority.ALWAYS);

        tile.getChildren().addAll(header, videoAndNavigation);
        VBox.setVgrow(videoAndNavigation, Priority.ALWAYS);

        handle.tile = tile;
        handle.viewport = viewport;
        handle.device = device;

        resizeTile(handle, false);
        secondaryGrid.getChildren().add(tile);
    }

    private ToggleButton createAdaptClickButton(DeviceSession handle) {
        ToggleButton button = new ToggleButton();
        button.setFocusTraversable(false);
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        updateAdaptClickButton(button, adaptScreenClick);
        button.setOnAction(e -> setAdaptScreenClick(button.isSelected()));
        return button;
    }

    private static void updateAdaptClickButton(ToggleButton button, boolean enabled) {
        button.setSelected(enabled);
        button.setText(enabled ? "↔" : "↕");
        button.setTooltip(new Tooltip(enabled
                ? "关闭主设备点击坐标适配"
                : "开启主设备点击坐标适配"));
    }

    private void setAdaptScreenClick(boolean enabled) {
        adaptScreenClick = enabled;
        refreshAdaptClickButtons();
    }

    private ToggleButton createMouseModeButton(DeviceSession handle) {
        ToggleButton button = new ToggleButton();
        button.setFocusTraversable(false);
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        updateMouseModeButton(button, handle.mouseMode);
        button.setOnAction(e -> setMouseModeFromSidebar(handle, button.isSelected()));
        return button;
    }

    private static void updateMouseModeButton(ToggleButton button, MouseMode mode) {
        boolean uhid = mode == MouseMode.UHID_RELATIVE;
        button.setSelected(uhid);
        button.setText(uhid ? "🖱" : "☝");
        button.setTooltip(new Tooltip(uhid ? "UHID 鼠标（点击切换为触控）" : "触控（点击切换为 UHID 鼠标）"));
    }

    private void setMouseModeFromSidebar(DeviceSession source, boolean uhid) {
        MouseMode mode = uhid ? MouseMode.UHID_RELATIVE : MouseMode.ABSOLUTE_TOUCH;
        for (DeviceSession target : inputTargets(source)) {
            setMouseMode(target, mode);
        }
    }

    private VBox createDeviceNavigationBar(DeviceSession handle) {
        ToggleButton primaryScreen = new ToggleButton("★");
        primaryScreen.setTooltip(new Tooltip("设为主屏幕"));
        primaryScreen.setFocusTraversable(false);
        primaryScreen.setMinSize(34, 34);
        primaryScreen.setPrefSize(34, 34);
        primaryScreen.setMaxSize(34, 34);
        handle.primaryScreenButton = primaryScreen;
        updatePrimaryScreenToggle(primaryScreen, isPrimary(handle));
        primaryScreen.setOnAction(e -> togglePrimary(handle));

        ToggleButton adaptClickButton = createAdaptClickButton(handle);
        handle.adaptClickButton = adaptClickButton;

        ToggleButton mouseModeButton = createMouseModeButton(handle);
        handle.mouseModeButton = mouseModeButton;
        Button back = deviceControlButton("←", "返回", () -> rightSidebarKey(handle, AndroidKeycodes.BACK));
        Button home = deviceControlButton("⌂", "Home", () -> rightSidebarKey(handle, AndroidKeycodes.HOME));
        Button recent = deviceControlButton("▣", "多任务", () -> rightSidebarKey(handle, 187));
        Button power = deviceControlButton("⏻", "电源", () -> rightSidebarKey(handle, AndroidKeycodes.POWER));
        ToggleButton displayPower = new ToggleButton("◐");
        displayPower.setTooltip(new Tooltip("熄屏 / 亮屏"));
        displayPower.setFocusTraversable(false);
        displayPower.setMinSize(34, 34);
        displayPower.setPrefSize(34, 34);
        displayPower.setMaxSize(34, 34);
        handle.displayPowerButton = displayPower;
        displayPower.setSelected(handle.displayPower);
        updateDisplayPowerToggle(displayPower, handle.displayPower);
        displayPower.setOnAction(e -> {
            boolean on = displayPower.isSelected();
            rightSidebarDisplayPower(handle, on);
        });

        ToggleButton stayAwake = new ToggleButton("☀");
        stayAwake.setTooltip(new Tooltip("保持屏幕常亮"));
        stayAwake.setFocusTraversable(false);
        stayAwake.setMinSize(34, 34);
        stayAwake.setPrefSize(34, 34);
        stayAwake.setMaxSize(34, 34);
        handle.stayAwakeButton = stayAwake;
        stayAwake.setSelected(handle.stayAwake);
        updateStayAwakeToggle(stayAwake, handle.stayAwake);
        stayAwake.setOnAction(e -> {
            boolean enabled = stayAwake.isSelected();
            rightSidebarStayAwake(handle, enabled);
        });

        VBox bar = new VBox(5, primaryScreen, back, home, recent, power, displayPower, stayAwake, mouseModeButton, adaptClickButton);
        bar.setAlignment(Pos.TOP_CENTER);
        bar.setPadding(new Insets(3, 2, 3, 2));
        bar.setStyle("-fx-background-color: #181818;");
        return bar;
    }

    private void rightSidebarKey(DeviceSession source, int keyCode) {
        for (DeviceSession target : inputTargets(source)) {
            pressAndroidKey(target, keyCode);
        }
    }

    private void rightSidebarDisplayPower(DeviceSession source, boolean on) {
        for (DeviceSession target : inputTargets(source)) {
            target.controller.setDisplayPower(on);
            target.displayPower = on;
            if (target.displayPowerButton != null) {
                updateDisplayPowerToggle(target.displayPowerButton, on);
            }
        }
    }

    private void rightSidebarStayAwake(DeviceSession source, boolean enabled) {
        for (DeviceSession target : inputTargets(source)) {
            target.stayAwake = enabled;
            if (target.stayAwakeButton != null) {
                updateStayAwakeToggle(target.stayAwakeButton, enabled);
            }
            setStayAwake(target, enabled);
        }
    }

    private static void updatePrimaryScreenToggle(ToggleButton button, boolean primary) {
        button.setSelected(primary);
        button.setText(primary ? "★" : "☆");
        button.setTooltip(new Tooltip(primary ? "取消主屏幕" : "设为主屏幕"));
    }

    private static void updateDisplayPowerToggle(ToggleButton button, boolean on) {
        button.setSelected(on);
        button.setText(on ? "◐" : "○");
        button.setTooltip(new Tooltip(on ? "熄屏" : "亮屏"));
    }

    private static void updateStayAwakeToggle(ToggleButton button, boolean enabled) {
        button.setText(enabled ? "☀" : "☾");
        button.setTooltip(new Tooltip(enabled ? "关闭屏幕常亮" : "开启屏幕常亮"));
    }

    private void setStayAwake(DeviceSession handle, boolean enabled) {
        if (handle.device == null) return;
        handle.stayAwake = enabled;
        String serial = handle.device.serial();
        Thread.startVirtualThread(() -> {
            try {
                // scrcpy's stay_awake is a server start option, not a runtime control
                // message. For a live per-device switch use Android's stay_on_while_plugged_in
                // setting; ADB/scrcpy connections are normally USB or charging-capable.
                String value = enabled ? "7" : "0";
                new Adb(System.getProperty("scrcpy.adb", "adb"), serial)
                        .run("shell", "settings", "put", "global", "stay_on_while_plugged_in", value);
            } catch (Throwable t) {
                Platform.runLater(() -> status.setText("设置屏幕常亮失败: " + t.getMessage()));
            }
        });
    }

    private Button deviceControlButton(String text, String tooltip, Runnable action) {
        Button button = new Button(text);
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setMinSize(34, 34);
        button.setPrefSize(34, 34);
        button.setMaxSize(34, 34);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void pressAndroidKey(DeviceSession handle, int keyCode) {
        handle.controller.key(DeviceController.ACTION_DOWN, keyCode, 0, 0);
        handle.controller.key(DeviceController.ACTION_UP, keyCode, 0, 0);
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
        view.addEventHandler(MouseEvent.MOUSE_EXITED, e -> endMouseInput(handle, e));
        view.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            // Do not rely only on MOUSE_EXITED. On Windows/JavaFX the event may
            // be missed when crossing the left/top edge quickly. Treat an edge
            // crossing as leaving the mapped screen before forwarding another
            // hover event.
            if (isPointerOutsideOrAtEdge(view, e)) {
                endMouseInput(handle, e);
                e.consume();
                return;
            }
            if (handle.mouseMode == MouseMode.UHID_RELATIVE && isRelativeMouseAtEdge(handle.view(), e)) {
                clearMouseInputAtEdge(handle);
                e.consume();
                return;
            }
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
            if (p != null) {
                activeMouseInput = handle;
                broadcastTouch(handle, DeviceController.ACTION_DOWN, p, 1f, 1);
            }
            e.consume();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (isPointerOutsideOrAtEdge(view, e)) {
                endMouseInput(handle, e);
                e.consume();
                return;
            }
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
            if (activeMouseInput == handle) activeMouseInput = null;
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
            if (handle.mouseMode == MouseMode.UHID_RELATIVE
                    && e.getCode() == KeyCode.ESCAPE
                    && e.isControlDown()) {
                releaseRelativeMouseCapture(handle);
                e.consume();
                return;
            }
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

    private boolean isScreenPointInsideView(VideoView view, double screenX, double screenY) {
        if (view == null) return false;
        double width = view.getBoundsInLocal().getWidth();
        double height = view.getBoundsInLocal().getHeight();
        if (width <= 0 || height <= 0) return false;
        javafx.geometry.Point2D topLeft = view.localToScreen(0, 0);
        javafx.geometry.Point2D bottomRight = view.localToScreen(width, height);
        return topLeft != null && bottomRight != null
                && screenX >= topLeft.getX() && screenX < bottomRight.getX()
                && screenY >= topLeft.getY() && screenY < bottomRight.getY();
    }

    private boolean isPointerOutsideOrAtEdge(VideoView view, MouseEvent e) {
        double width = view.getBoundsInLocal().getWidth();
        double height = view.getBoundsInLocal().getHeight();
        if (width <= 0 || height <= 0) return true;

        // Use screen coordinates for boundary detection. JavaFX local X may
        // stop at a small positive value when the native Windows pointer exits
        // through the left edge, so checking only e.getX() is asymmetric.
        javafx.geometry.Point2D topLeft = view.localToScreen(0, 0);
        javafx.geometry.Point2D bottomRight = view.localToScreen(width, height);
        if (topLeft != null && bottomRight != null) {
            double margin = 2.0;
            double screenX = e.getScreenX();
            double screenY = e.getScreenY();
            return screenX <= topLeft.getX() + margin
                    || screenX >= bottomRight.getX() - margin
                    || screenY <= topLeft.getY() + margin
                    || screenY >= bottomRight.getY() - margin;
        }

        double margin = 2.0;
        return e.getX() <= margin || e.getY() <= margin
                || e.getX() >= width - margin || e.getY() >= height - margin;
    }

    private void endMouseInput(DeviceSession handle, MouseEvent e) {
        if (handle.recentering) return;

        for (DeviceSession target : inputTargets(handle)) {
            target.session.clearPendingControlEvents();
            try {
                target.uhid.mouse().clearTransientState();
            } catch (Throwable ignored) {
            }
        }

        if (handle.mouseMode == MouseMode.ABSOLUTE_TOUCH && e.isPrimaryButtonDown()) {
            VideoView view = handle.view();
            DeviceController.ScreenPosition exit = InputMapper.position(
                    view,
                    Math.max(0, Math.min(Math.max(0, view.getBoundsInLocal().getWidth() - 1), e.getX())),
                    Math.max(0, Math.min(Math.max(0, view.getBoundsInLocal().getHeight() - 1), e.getY())));
            if (exit != null) {
                broadcastTouch(handle, DeviceController.ACTION_UP, exit, 0f, 1);
            }
        }

        handle.relativePointerValid = false;
        handle.recentering = false;
        if (activeMouseInput == handle) activeMouseInput = null;
    }

    private boolean isPrimary(DeviceSession handle) {
        return primarySerial != null && handle.device != null && primarySerial.equals(handle.device.serial());
    }

    private List<DeviceSession> inputTargets(DeviceSession source) {
        if (!isPrimary(source)) return List.of(source);
        return new ArrayList<>(sessions.values());
    }

    /**
     * Route a control operation according to the primary-device policy.
     * Input is broadcast from the primary device; device-management commands
     * stay local unless explicitly added to this policy.
     */
    private void routeControl(String sourceSerial, ControlMessages.Message message) {
        DeviceSession source = sessions.get(sourceSerial);
        if (source == null) return;
        for (DeviceSession target : inputTargets(source)) {
            target.session.sendControl(message);
        }
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
            DeviceController.ScreenPosition p = mapAbsolutePosition(source, target, position);
            if (p != null) target.controller.hover(p);
        }
    }

    private void broadcastTouch(DeviceSession source, int action, DeviceController.ScreenPosition position,
                                float pressure, int actionButton) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = mapAbsolutePosition(source, target, position);
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

    private DeviceController.ScreenPosition mapAbsolutePosition(DeviceSession source,
                                                                  DeviceSession target,
                                                                  DeviceController.ScreenPosition position) {
        if (source == target) return position;
        if (adaptScreenClick) return scalePosition(position, target.view());
        int width = target.view().frameWidth();
        int height = target.view().frameHeight();
        if (width <= 0 || height <= 0) return null;

        // Raw-coordinate mode preserves the primary device's coordinates, but
        // the target screen may be smaller (different resolution/orientation).
        // Scrcpy positions must always satisfy 0 <= x < width, 0 <= y < height.
        int x = Math.min(width - 1, Math.max(0, position.x()));
        int y = Math.min(height - 1, Math.max(0, position.y()));
        return new DeviceController.ScreenPosition(x, y, width, height);
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

    private boolean isRelativeMouseAtEdge(VideoView view, MouseEvent e) {
        double width = view.getBoundsInLocal().getWidth();
        double height = view.getBoundsInLocal().getHeight();
        if (width <= 0 || height <= 0) return false;
        double margin = RELATIVE_MOUSE_EDGE_MARGIN;
        return e.getX() <= margin || e.getY() <= margin
                || e.getX() >= width - margin || e.getY() >= height - margin;
    }

    private void clearMouseInputAtEdge(DeviceSession handle) {
        // Drop stale queued input first, then emit one clean button-release state.
        handle.session.clearPendingControlEvents();
        handle.uhid.mouse().clearTransientState();
        handle.relativePointerValid = false;
        handle.recentering = false;
        recenterRelativePointer(handle);
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

    private void releaseAllTransientInput() {
        for (DeviceSession session : sessions.values()) {
            try {
                session.uhid.releaseInputState();
            } catch (Throwable t) {
                System.err.println("[scrcpy-java] failed to release input state: " + t);
            }
            session.relativePointerValid = false;
            session.recentering = false;
        }
        activeMouseInput = null;
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
        // Do not carry pressed state from the old input topology into the new
        // one. Primary switching changes which devices receive future events.
        releaseAllTransientInput();
        if (primarySerial != null && primarySerial.equals(handle.device.serial())) return;

        DeviceSession old = primarySerial == null ? null : sessions.get(primarySerial);
        if (old != null) {
            old.primary = false;
            if (old.tile != null) {
                primaryHost.getChildren().remove(old.tile);
                int index = old.secondaryIndex;
                if (index < 0 || index > secondaryGrid.getChildren().size()) {
                    index = secondaryGrid.getChildren().size();
                }
                secondaryGrid.getChildren().add(index, old.tile);
                old.secondaryIndex = -1;
                resizeTile(old, false);
            }
        }

        primarySerial = handle.device.serial();
        desiredPrimarySerial = primarySerial;
        handle.primary = true;
        if (handle.tile != null) {
            handle.secondaryIndex = secondaryGrid.getChildren().indexOf(handle.tile);
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
        releaseAllTransientInput();
        desiredPrimarySerial = null;
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
        int index = old.secondaryIndex;
        if (index < 0 || index > secondaryGrid.getChildren().size()) {
            index = secondaryGrid.getChildren().size();
        }
        secondaryGrid.getChildren().add(index, old.tile);
        old.secondaryIndex = -1;
        resizeTile(old, false);
        primaryHost.setManaged(false);
        primaryHost.setVisible(false);
        primaryHost.setPrefWidth(0);
        screenArea.setLeft(null);
        refreshPrimaryUi();
    }

    private void resizeTileForCurrentRole(String serial) {
        DeviceSession handle = sessions.get(serial);
        if (handle != null) resizeTile(handle, isPrimary(handle));
    }

    private void resizeTile(DeviceSession handle, boolean primary) {
        if (handle.tile == null || handle.view() == null) return;

        double maxWidth = primary ? 620 : TILE_WIDTH;
        double maxVideoHeight = (primary ? 820 : TILE_HEIGHT) - 42;
        int frameWidth = handle.view().frameWidth();
        int frameHeight = handle.view().frameHeight();

        // Size the mapped viewport to the actual video aspect ratio. Previously
        // the viewport was always 360/620 px wide while ImageView preserved the
        // phone aspect ratio, which left large black columns on both sides.
        double videoWidth = maxWidth;
        double videoHeight = maxVideoHeight;
        if (frameWidth > 0 && frameHeight > 0) {
            double aspect = frameWidth / (double) frameHeight;
            videoHeight = Math.min(maxVideoHeight, maxWidth / aspect);
            videoWidth = videoHeight * aspect;
        }

        double controlsWidth = 39;
        double tileWidth = videoWidth + controlsWidth;
        double tileHeight = videoHeight + 42;
        handle.tile.setPrefSize(tileWidth, tileHeight);
        handle.tile.setMinSize(tileWidth, tileHeight);
        handle.tile.setMaxSize(tileWidth, tileHeight);
        handle.viewport.setPrefSize(videoWidth, videoHeight);
        handle.viewport.setMinSize(videoWidth, videoHeight);
        handle.viewport.setMaxSize(videoWidth, videoHeight);
        handle.view().setFitWidth(videoWidth);
        handle.view().setFitHeight(videoHeight);
    }

    private void refreshAdaptClickButtons() {
        sessions.values().forEach(session -> {
            if (session.adaptClickButton != null) {
                updateAdaptClickButton(session.adaptClickButton, adaptScreenClick);
            }
        });
    }

    private void refreshPrimaryUi() {
        if (primarySerial == null) {
            primaryStatus.setText("主设备：无");
        } else {
            DeviceSession s = sessions.get(primarySerial);
            primaryStatus.setText("主设备：" + (s == null || s.device == null ? primarySerial : s.device.displayName()));
        }
        sessions.values().forEach(s -> {
            if (s.primaryScreenButton != null) {
                updatePrimaryScreenToggle(s.primaryScreenButton, isPrimary(s));
            }
        });
        refreshAdaptClickButtons();
    }

    private void setMouseMode(DeviceSession handle, MouseMode mode) {
        // Switching input modes must release state generated by the previous mode.
        handle.uhid.releaseInputState();
        handle.mouseMode = mode == null ? MouseMode.ABSOLUTE_TOUCH : mode;
        handle.relativePointerValid = false;
        handle.view().setCursor(Cursor.DEFAULT);
        handle.relativeCaptured = false;
        if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
            handle.uhid.openMouse();
            handle.view().requestFocus();
            Platform.runLater(() -> captureRelativeMouse(handle));
        } else {
            handle.uhid.mouse().buttons(0);
        }
    }

    private void captureRelativeMouse(DeviceSession handle) {
        if (handle.mouseMode != MouseMode.UHID_RELATIVE
                || !handle.view().isVisible()
                || handle.relativeCaptured) return;
        javafx.geometry.Point2D center = handle.view().localToScreen(
                handle.view().getBoundsInLocal().getWidth() / 2.0,
                handle.view().getBoundsInLocal().getHeight() / 2.0);
        if (center == null) return;
        handle.recentering = true;
        handle.relativePointerValid = true;
        mouseRobot.mouseMove(center.getX(), center.getY());
        handle.recentering = false;
        handle.relativeCaptured = true;
    }

    private void releaseRelativeMouseCapture(DeviceSession handle) {
        if (handle.mouseMode != MouseMode.UHID_RELATIVE) return;
        handle.relativeCaptured = false;
        handle.relativePointerValid = false;
        handle.recentering = false;
        try {
            handle.uhid.mouse().clearTransientState();
        } catch (Throwable ignored) {
        }
        handle.view().setCursor(Cursor.DEFAULT);
    }

    private void sendRelativeMove(DeviceSession handle, MouseEvent e) {
        if (handle.recentering || handle.mouseMode != MouseMode.UHID_RELATIVE
                || !handle.relativeCaptured) return;
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
        desiredConnections.remove(serial);
        reconnecting.remove(serial);
        if (Objects.equals(desiredPrimarySerial, serial)) {
            desiredPrimarySerial = null;
        }
        disconnectSession(serial);
    }

    private void disconnectForReconnect(String serial) {
        reconnecting.remove(serial);
        // A temporary ADB loss must not forget which device was primary.
        if (isPrimarySerial(serial)) {
            desiredPrimarySerial = serial;
        }
        disconnectSession(serial);
    }

    private boolean isPrimarySerial(String serial) {
        return primarySerial != null && primarySerial.equals(serial);
    }

    private void disconnectSession(String serial) {
        DeviceSession handle = sessions.remove(serial);
        if (handle == null) return;

        if (handle.tile != null) {
            primaryHost.getChildren().remove(handle.tile);
            secondaryGrid.getChildren().remove(handle.tile);
        }
        try {
            handle.uhid.releaseInputState();
        } catch (Throwable ignored) {
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
        desiredPrimarySerial = null;
        primaryHost.getChildren().clear();
        primaryHost.setManaged(false);
        primaryHost.setVisible(false);
        primaryHost.setPrefWidth(0);
        screenArea.setLeft(null);
    }

    @Override
    public void stop() {
        adbMonitor.shutdownNow();
        desiredConnections.clear();
        reconnecting.clear();
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
        private ToggleButton adaptClickButton;
        private AdbDevice device;
        private boolean primary;
        private MouseMode mouseMode = MouseMode.ABSOLUTE_TOUCH;
        private boolean relativePointerValid;
        private boolean recentering;
        private boolean stayAwake;
        private boolean displayPower = true;
        private ToggleButton displayPowerButton;
        private ToggleButton stayAwakeButton;
        private ToggleButton mouseModeButton;
        private ToggleButton primaryScreenButton;
        private boolean relativeCaptured;
        /** Original secondary-grid position while this device is primary. */
        private int secondaryIndex = -1;

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
