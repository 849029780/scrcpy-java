package com.jian.scrcpy.app;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.media.FfmpegVideoDecoder;
import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import com.jian.scrcpy.sdl.SdlD3d11VideoRenderer;
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
import com.jian.scrcpy.server.ServerLauncher;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Cursor;
import javafx.scene.input.Clipboard;
import javafx.scene.robot.Robot;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Modality;
import javafx.stage.FileChooser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.TextInputDialog;

import java.util.*;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScrcpyApp extends Application {
    private static final double TILE_WIDTH = 360;
    private static final double TILE_HEIGHT = 680;
    private static final double RELATIVE_MOUSE_EDGE_MARGIN = 2.0;
    private static final long RECONNECT_INTERVAL_MILLIS = 3_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 20;
    /** Experimental opt-in path: present D3D11 hardware frames in an SDL window. */
    private static final boolean USE_SDL_D3D11_RENDERER = Boolean.getBoolean("scrcpy.video.sdl-d3d11");

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
    /** Device whose absolute touch gesture is currently active. */
    private volatile DeviceSession activeMouseInput;
    private final Set<String> reconnecting = ConcurrentHashMap.newKeySet();
    /** Devices checked in the device manager; independent from ListView focus/selection. */
    private final Set<String> checkedDevices = ConcurrentHashMap.newKeySet();
    /** Retry state for connections that were previously established and then lost. */
    private final Map<String, ReconnectState> reconnectStates = new ConcurrentHashMap<>();
    private volatile String primarySerial;
    /** Preserve the user's primary-device choice across temporary ADB loss. */
    private volatile String desiredPrimarySerial;
    private final Robot mouseRobot = new Robot();
    private Adb adb;
    private volatile String lastDesktopClipboard = "";
    private final java.util.concurrent.atomic.AtomicBoolean applyingRemoteClipboard = new java.util.concurrent.atomic.AtomicBoolean();
    /** Last valid Android touch position, retained so an outside release can always emit ACTION_UP. */
    private final java.util.Map<DeviceSession, DeviceController.ScreenPosition> activeTouchPositions = new java.util.concurrent.ConcurrentHashMap<>();
    // ADB polling spends almost all of its time blocked in Process I/O, so a
    // virtual-thread scheduler avoids dedicating a platform thread to it.
    private final ScheduledExecutorService adbMonitor =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("scrcpy-adb-monitor").factory());

    @Override
    public void start(Stage stage) {
        // Adb resolves an explicit path, bundled platform-tools, Android SDK,
        // and finally PATH. The application therefore does not require a
        // system-wide adb installation.
        adb = new Adb(System.getProperty("scrcpy.adb"), null);

        devices.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
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

        Button deviceButton = new Button("设备");
        deviceButton.setOnAction(e -> showDeviceManager());

        Button sendFiles = new Button("传送文件");
        sendFiles.setOnAction(e -> showFileTransferDialog(stage));

        Button installApps = new Button("批量安装应用");
        installApps.setOnAction(e -> showAppInstallDialog(stage));

        HBox toolbar = new HBox(12, deviceButton, sendFiles, installApps, primaryStatus, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        BorderPane content = new BorderPane(screenArea);
        content.setTop(toolbar);

        // Device connection targets are controlled by per-row check boxes.
        // The ListView selection is now only visual focus and never requires Ctrl.

        stage.setTitle("多设备投屏");
        Scene scene = new Scene(content, 1400, 900);
        // A fast drag can cross from one VideoView to another before JavaFX
        // delivers MOUSE_EXITED/MOUSE_DRAGGED to the original view. Keep a
        // scene-level gesture guard so a touch is always cancelled as soon as
        // the pointer leaves the view that started it.
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            DeviceSession active = activeMouseInput;
            if (active != null && !isScreenPointInsideProjection(active.view(), e.getScreenX(), e.getScreenY())) {
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
        // Inspect the actual Prism backend only after the JavaFX pipeline has initialized.
        Platform.runLater(PrismPipelineDiagnostics::log);
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
            private final CheckBox check = new CheckBox();

            {
                check.setOnAction(e -> {
                    AdbDevice d = getItem();
                    if (d == null) return;
                    if (check.isSelected()) {
                        checkedDevices.add(d.serial());
                    } else {
                        checkedDevices.remove(d.serial());
                    }
                    updateButtons();
                });
            }

            @Override
            protected void updateItem(AdbDevice d, boolean empty) {
                super.updateItem(d, empty);
                setText(null);
                if (empty || d == null) {
                    setGraphic(null);
                    return;
                }
                boolean active = sessions.containsKey(d.serial());
                String state = active ? "投屏中" :
                        ("device".equals(d.state()) ? "已连接" : d.state());
                check.setText(d.displayName() + "  [" + state + "]");
                check.setSelected(checkedDevices.contains(d.serial()));
                check.setMaxWidth(Double.MAX_VALUE);
                setGraphic(check);
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

    private void showFileTransferDialog(Stage owner) {
        if (sessions.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "当前没有正在投屏的设备。", ButtonType.OK).showAndWait();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("传送文件");
        dialog.setHeaderText("批量选择文件和目标设备");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);

        ListView<File> files = new ListView<>();
        files.setPrefHeight(220);
        files.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getAbsolutePath());
            }
        });

        Button chooseFiles = new Button("选择文件...");
        chooseFiles.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择要传送的文件");
            List<File> selected = chooser.showOpenMultipleDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null && !selected.isEmpty()) {
                files.getItems().setAll(selected);
            }
        });

        VBox deviceBox = new VBox(6);
        Map<String, CheckBox> targetChecks = new LinkedHashMap<>();
        sessions.values().stream()
                .filter(s -> s.device != null)
                .sorted(Comparator.comparing(s -> s.device.displayName()))
                .forEach(s -> {
                    CheckBox check = new CheckBox(s.device.displayName());
                    check.setSelected(true);
                    targetChecks.put(s.device.serial(), check);
                    deviceBox.getChildren().add(check);
                });

        ScrollPane targetScroll = new ScrollPane(deviceBox);
        targetScroll.setFitToWidth(true);
        targetScroll.setPrefHeight(180);

        Label result = new Label();
        VBox progressBox = new VBox(4);
        ScrollPane progressScroll = new ScrollPane(progressBox);
        progressScroll.setFitToWidth(true);
        progressScroll.setPrefViewportHeight(160);
        progressScroll.setMaxHeight(220);
        progressScroll.setPannable(true);
        Map<String, Label> progressLabels = new LinkedHashMap<>();
        targetChecks.forEach((serial, check) -> {
            Label label = new Label(check.getText() + "：等待发送");
            progressLabels.put(serial, label);
            progressBox.getChildren().add(label);
        });
        Button send = new Button("发送");
        send.setDefaultButton(true);
        send.setOnAction(e -> {
            List<File> selectedFiles = List.copyOf(files.getItems());
            List<String> targets = targetChecks.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
            if (selectedFiles.isEmpty()) {
                result.setText("请先选择文件");
                return;
            }
            if (targets.isEmpty()) {
                result.setText("请至少选择一台设备");
                return;
            }

            send.setDisable(true);
            chooseFiles.setDisable(true);
            result.setText("正在发送...");
            progressLabels.forEach((serial, label) ->
                    label.setVisible(targets.contains(serial)));
            targets.forEach(serial ->
                    progressLabels.get(serial).setText(targetChecks.get(serial).getText()
                            + "：0 / " + selectedFiles.size()));
            Thread.startVirtualThread(() -> transferFiles(
                    selectedFiles, targets, result, progressLabels, send, chooseFiles));
        });

        VBox content = new VBox(10,
                new Label("文件"), chooseFiles, files,
                new Label("发送到已投屏设备"), targetScroll,
                send, result, new Label("各设备进度"), progressScroll);
        content.setPadding(new Insets(8));
        content.setPrefSize(680, 560);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private void transferFiles(List<File> files, List<String> serials, Label result,
                               Map<String, Label> progressLabels,
                               Button send, Button chooseFiles) {
        int total = files.size() * serials.size();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String serial : serials) {
                executor.submit(() -> {
                    DeviceSession session = sessions.get(serial);
                    String deviceName = session != null && session.device != null
                            ? session.device.displayName() : serial;
                    Adb deviceAdb = new Adb(System.getProperty("scrcpy.adb", "adb"), serial);
                    // One task per device: devices run in parallel, while files for
                    // the same device remain ordered to avoid competing ADB installs/pushes.
                    for (File file : files) {
                        try {
                            deviceAdb.push(file.toPath(), "/sdcard/Download/" + file.getName());
                            success.incrementAndGet();
                        } catch (Exception ex) {
                            failures.add(deviceName + " / " + file.getName());
                        }
                        int deviceDone = files.indexOf(file) + 1;
                        int currentDone = done.incrementAndGet();
                        int currentSuccess = success.get();
                        Platform.runLater(() -> {
                            Label label = progressLabels.get(serial);
                            if (label != null) {
                                label.setText(deviceName + "：" + deviceDone + " / " + files.size());
                            }
                            result.setText("总进度 " + currentDone + " / " + total
                                    + "，成功 " + currentSuccess);
                        });
                    }
                });
            }
        }

        int finalSuccess = success.get();
        Platform.runLater(() -> {
            String text = "发送完成：成功 " + finalSuccess + " / " + total;
            if (!failures.isEmpty()) {
                text += "，失败 " + failures.size() + "（" + String.join("、", failures) + "）";
            }
            result.setText(text);
            send.setDisable(false);
            chooseFiles.setDisable(false);
            status.setText("文件传送完成：成功 " + finalSuccess + " / " + total);
        });
    }

    private void showAppInstallDialog(Stage owner) {
        if (sessions.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "当前没有正在投屏的设备。", ButtonType.OK).showAndWait();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("批量安装应用");
        dialog.setHeaderText("选择 APK 安装包和目标设备");
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);

        ListView<File> apks = new ListView<>();
        apks.setPrefHeight(220);
        apks.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getAbsolutePath());
            }
        });

        Button chooseApks = new Button("选择 APK...");
        chooseApks.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择 APK 安装包");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Android APK (*.apk)", "*.apk"));
            List<File> selected = chooser.showOpenMultipleDialog(dialog.getDialogPane().getScene().getWindow());
            if (selected != null && !selected.isEmpty()) {
                apks.getItems().setAll(selected);
            }
        });

        VBox deviceBox = new VBox(6);
        Map<String, CheckBox> targetChecks = new LinkedHashMap<>();
        sessions.values().stream()
                .filter(s -> s.device != null)
                .sorted(Comparator.comparing(s -> s.device.displayName()))
                .forEach(s -> {
                    CheckBox check = new CheckBox(s.device.displayName());
                    check.setSelected(true);
                    targetChecks.put(s.device.serial(), check);
                    deviceBox.getChildren().add(check);
                });

        ScrollPane targetScroll = new ScrollPane(deviceBox);
        targetScroll.setFitToWidth(true);
        targetScroll.setPrefHeight(180);

        Label result = new Label();
        VBox progressBox = new VBox(4);
        ScrollPane progressScroll = new ScrollPane(progressBox);
        progressScroll.setFitToWidth(true);
        progressScroll.setPrefViewportHeight(160);
        progressScroll.setMaxHeight(220);
        progressScroll.setPannable(true);
        Map<String, Label> progressLabels = new LinkedHashMap<>();
        targetChecks.forEach((serial, check) -> {
            Label label = new Label(check.getText() + "：等待安装");
            progressLabels.put(serial, label);
            progressBox.getChildren().add(label);
        });
        Button install = new Button("安装");
        install.setDefaultButton(true);
        install.setOnAction(e -> {
            List<File> selectedApks = List.copyOf(apks.getItems());
            List<String> targets = targetChecks.entrySet().stream()
                    .filter(entry -> entry.getValue().isSelected())
                    .map(Map.Entry::getKey)
                    .toList();
            if (selectedApks.isEmpty()) {
                result.setText("请先选择 APK 安装包");
                return;
            }
            if (targets.isEmpty()) {
                result.setText("请至少选择一台设备");
                return;
            }

            install.setDisable(true);
            chooseApks.setDisable(true);
            result.setText("正在安装...");
            progressLabels.forEach((serial, label) ->
                    label.setVisible(targets.contains(serial)));
            targets.forEach(serial ->
                    progressLabels.get(serial).setText(targetChecks.get(serial).getText()
                            + "：0 / " + selectedApks.size()));
            Thread.startVirtualThread(() -> installApps(
                    selectedApks, targets, result, progressLabels, install, chooseApks));
        });

        VBox content = new VBox(10,
                new Label("APK 安装包"), chooseApks, apks,
                new Label("安装到已投屏设备"), targetScroll,
                install, result, new Label("各设备进度"), progressScroll);
        content.setPadding(new Insets(8));
        content.setPrefSize(680, 560);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private void installApps(List<File> apks, List<String> serials, Label result,
                             Map<String, Label> progressLabels,
                             Button install, Button chooseApks) {
        int total = apks.size() * serials.size();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String serial : serials) {
                executor.submit(() -> {
                    DeviceSession session = sessions.get(serial);
                    String deviceName = session != null && session.device != null
                            ? session.device.displayName() : serial;
                    Adb deviceAdb = new Adb(System.getProperty("scrcpy.adb", "adb"), serial);
                    // One task per device: devices install concurrently, but APKs on
                    // each individual device are deliberately installed sequentially.
                    for (File apk : apks) {
                        try {
                            deviceAdb.run("install", "-r", apk.getAbsolutePath());
                            success.incrementAndGet();
                        } catch (Exception ex) {
                            failures.add(deviceName + " / " + apk.getName());
                        }
                        int deviceDone = apks.indexOf(apk) + 1;
                        int currentDone = done.incrementAndGet();
                        int currentSuccess = success.get();
                        Platform.runLater(() -> {
                            Label label = progressLabels.get(serial);
                            if (label != null) {
                                label.setText(deviceName + "：" + deviceDone + " / " + apks.size());
                            }
                            result.setText("总进度 " + currentDone + " / " + total
                                    + "，成功 " + currentSuccess);
                        });
                    }
                });
            }
        }

        int finalSuccess = success.get();
        Platform.runLater(() -> {
            String text = "安装完成：成功 " + finalSuccess + " / " + total;
            if (!failures.isEmpty()) {
                text += "，失败 " + failures.size() + "（" + String.join("、", failures) + "）";
            }
            result.setText(text);
            install.setDisable(false);
            chooseApks.setDisable(false);
            status.setText("应用安装完成：成功 " + finalSuccess + " / " + total);
        });
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
                        status.setText("设备已离线: " + serial + "，3 秒后尝试自动重连");
                        disconnectForReconnect(serial);
                    }
                }));
            }
            long now = System.currentTimeMillis();
            desiredConnections.forEach((serial, settings) -> {
                if (sessions.containsKey(serial) || reconnecting.contains(serial)) return;

                ReconnectState retry = reconnectStates.get(serial);
                if (retry == null || now < retry.nextAttemptAtMillis()) return;
                if (retry.attempts() >= MAX_RECONNECT_ATTEMPTS) {
                    reconnectStates.remove(serial);
                    desiredConnections.remove(serial);
                    Platform.runLater(() -> {
                        status.setText("设备自动重连失败: " + serial + "，已达到 " + MAX_RECONNECT_ATTEMPTS + " 次");
                        devices.refresh();
                        updateButtons();
                    });
                    return;
                }

                AdbDevice d = current.get(serial);
                if (d == null || !d.ready()) {
                    // Do not consume an attempt while ADB cannot see the device;
                    // wait three seconds and try the availability check again.
                    reconnectStates.computeIfPresent(serial,
                            (key, state) -> state.waitUntil(now + RECONNECT_INTERVAL_MILLIS));
                    return;
                }

                ReconnectState next = retry.nextAttempt(now + RECONNECT_INTERVAL_MILLIS);
                reconnectStates.put(serial, next);
                if (reconnecting.add(serial)) {
                    Platform.runLater(() -> {
                        status.setText("正在自动重连 " + d.displayName() + "（"
                                + next.attempts() + "/" + MAX_RECONNECT_ATTEMPTS + "）");
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

    private Optional<ConnectionSettings> showConnectionSettings(
            Map<String, List<ServerLauncher.VideoEncoder>> encoderCapabilities) {
        Dialog<ConnectionSettings> dialog = new Dialog<>();
        dialog.setTitle("连接设置");
        dialog.setHeaderText("连接设备前设置本次连接参数");
        dialog.initModality(Modality.APPLICATION_MODAL);

        CheckBox video = new CheckBox("视频"); video.setSelected(true);
        CheckBox audio = new CheckBox("音频"); audio.setSelected(true);
        CheckBox control = new CheckBox("控制"); control.setSelected(true);
        CheckBox showTouches = new CheckBox("显示触摸点");
        CheckBox stayAwake = new CheckBox("保持唤醒");
        CheckBox turnScreenOff = new CheckBox("关闭设备屏幕");

        ComboBox<String> videoCodec = combo("H.264", "H.265", "AV1");
        ComboBox<String> videoEncoder = combo("自动（推荐）");
        ComboBox<String> videoDecoder = combo("硬件解码", "软件解码");
        TextField videoCodecOptions = new TextField();
        videoCodecOptions.setPromptText("例如 bitrate-mode:int=1,profile:int=1");
        ComboBox<String> audioCodec = combo("opus", "aac", "flac");
        ComboBox<String> videoBitRate = combo("2M", "4M", "6M", "8M", "10M", "12M", "16M", "20M");
        ComboBox<String> audioBitRate = combo("64K", "96K", "128K", "160K", "192K", "256K", "320K");
        ComboBox<String> maxSize = combo("不限制", "720", "1080", "1440", "1920", "2560", "3840");
        ComboBox<String> maxFps = combo("不限制", "30", "45", "60", "90", "120", "144");
        ComboBox<String> crop = combo("不裁剪", "1080:1920:0:0", "1920:1080:0:0", "1440:2560:0:0");
        TextField startApp = new TextField();
        startApp.setPromptText("可选：应用名称或包名，例如 抖音 / com.ss.android.ugc.aweme");

        videoCodec.setValue("H.264");
        videoDecoder.setValue("硬件解码");
        audioCodec.setValue("opus");
        videoBitRate.setValue("8M"); audioBitRate.setValue("128K");
        maxSize.setValue("1080"); maxFps.setValue("60"); crop.setValue("不裁剪");

        Runnable refreshEncoders = () -> {
            String codec = videoCodecName(videoCodec.getValue());
            List<String> common = commonVideoEncoderNames(codec, encoderCapabilities);
            String previous = videoEncoder.getValue();
            var values = new ArrayList<String>();
            values.add("自动（推荐）");
            values.addAll(common);
            videoEncoder.setItems(FXCollections.observableArrayList(values));
            if (previous != null && values.contains(previous)) {
                videoEncoder.setValue(previous);
            } else {
                videoEncoder.setValue("自动（推荐）");
            }
            videoEncoder.setDisable(!video.isSelected());
        };
        videoCodec.valueProperty().addListener((obs, oldValue, newValue) -> refreshEncoders.run());
        video.selectedProperty().addListener((obs, oldValue, selected) -> {
            videoCodec.setDisable(!selected);
            videoEncoder.setDisable(!selected);
            videoCodecOptions.setDisable(!selected);
        });
        refreshEncoders.run();

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8); grid.setPadding(new Insets(10));
        int row = 0;
        grid.add(video, 0, row); grid.add(audio, 1, row); grid.add(control, 2, row++);
        grid.add(new Label("视频编码"), 0, row); grid.add(videoCodec, 1, row++, 2, 1);
        grid.add(new Label("视频编码器"), 0, row); grid.add(videoEncoder, 1, row++, 2, 1);
        grid.add(new Label("视频解码器"), 0, row); grid.add(videoDecoder, 1, row++, 2, 1);
        grid.add(new Label("音频编码"), 0, row); grid.add(audioCodec, 1, row++, 2, 1);
        grid.add(new Label("视频码率"), 0, row); grid.add(videoBitRate, 1, row++, 2, 1);
        grid.add(new Label("音频码率"), 0, row); grid.add(audioBitRate, 1, row++, 2, 1);
        grid.add(new Label("最大分辨率"), 0, row); grid.add(maxSize, 1, row++, 2, 1);
        grid.add(new Label("最大帧率"), 0, row); grid.add(maxFps, 1, row++, 2, 1);
        grid.add(new Label("裁剪"), 0, row); grid.add(crop, 1, row++, 2, 1);
        grid.add(new Label("启动应用"), 0, row); grid.add(startApp, 1, row++, 2, 1);
        grid.add(showTouches, 0, row); grid.add(stayAwake, 1, row); grid.add(turnScreenOff, 2, row++);

        VBox advancedBox = new VBox(8,
                new Label("向 Android MediaFormat 传递自定义编码参数。留空使用默认参数。"),
                videoCodecOptions);
        advancedBox.setPadding(new Insets(8));
        TitledPane advanced = new TitledPane("高级编码设置", advancedBox);
        advanced.setExpanded(false);
        advanced.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(4, grid, advanced);
        content.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(content);
        ButtonType ok = new ButtonType("连接", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(bt -> {
            if (bt != ok) return null;
            String codec = videoCodecName(videoCodec.getValue());
            String encoder = "自动（推荐）".equals(videoEncoder.getValue())
                    ? null : blankToNull(videoEncoder.getValue());
            return new ConnectionSettings(video.isSelected(), audio.isSelected(), control.isSelected(),
                    codec, encoder, blankToNull(videoCodecOptions.getText()), videoDecoderMode(videoDecoder.getValue()), audioCodec.getValue(),
                    parseBitRate(videoBitRate.getValue()), parseBitRate(audioBitRate.getValue()),
                    parsePositiveInt(maxSize.getValue(), 0),
                    "不限制".equals(maxFps.getValue()) ? null : maxFps.getValue(),
                    showTouches.isSelected(), stayAwake.isSelected(), turnScreenOff.isSelected(),
                    "不裁剪".equals(crop.getValue()) ? null : crop.getValue(), normalizeStartApp(startApp.getText()));
        });
        return dialog.showAndWait();
    }

    /**
     * scrcpy START_APP treats a plain value as an exact package name.
     * Prefix human-readable app names with '?' so the server searches by label.
     */
    private static String normalizeStartApp(String value) {
        String name = blankToNull(value);
        if (name == null) return null;

        boolean forceStop = name.startsWith("+");
        String target = forceStop ? name.substring(1) : name;
        if (!target.startsWith("?") && !target.contains(".")) {
            target = "?" + target;
        }
        return forceStop ? "+" + target : target;
    }

    private static String videoDecoderMode(String label) {
        return switch (Objects.requireNonNullElse(label, "硬件解码")) {
            case "软件解码" -> "software";
            default -> "hardware";
        };
    }

    private static String videoCodecName(String label) {
        return switch (Objects.requireNonNullElse(label, "H.264")) {
            case "H.265" -> "h265";
            case "AV1" -> "av1";
            default -> "h264";
        };
    }

    private static List<String> commonVideoEncoderNames(
            String codec, Map<String, List<ServerLauncher.VideoEncoder>> capabilities) {
        if (capabilities.isEmpty()) return List.of();
        Set<String> common = null;
        List<String> order = List.of();
        for (List<ServerLauncher.VideoEncoder> encoders : capabilities.values()) {
            LinkedHashSet<String> names = encoders.stream()
                    .filter(e -> codec.equals(e.codec()))
                    .map(ServerLauncher.VideoEncoder::name)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (common == null) {
                common = new LinkedHashSet<>(names);
                order = new ArrayList<>(names);
            } else {
                common.retainAll(names);
            }
        }
        if (common == null) return List.of();
        return order.stream().filter(common::contains).toList();
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
        connectDevices(devices.getItems().stream()
                .filter(d -> checkedDevices.contains(d.serial()))
                .toList());
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

        status.setText("正在读取设备视频编码器...");
        Thread.startVirtualThread(() -> {
            Map<String, List<ServerLauncher.VideoEncoder>> capabilities = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();
            try {
                var jar = ScrcpyServerArtifact.resolve();
                for (AdbDevice device : targets) {
                    try {
                        var deviceAdb = new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial());
                        var launcher = new ServerLauncher(deviceAdb, ScrcpyOptions.defaults());
                        capabilities.put(device.serial(), launcher.listVideoEncoders(jar));
                    } catch (Throwable t) {
                        failures.add(device.displayName() + ": " + t.getMessage());
                        capabilities.put(device.serial(), List.of());
                    }
                }
            } catch (Throwable t) {
                failures.add("读取编码器失败: " + t.getMessage());
            }

            Platform.runLater(() -> {
                if (!failures.isEmpty()) {
                    status.setText("编码器查询部分失败，将仅提供自动选择");
                } else {
                    status.setText("正在连接 " + targets.size() + " 台设备...");
                }
                showConnectionSettings(capabilities).ifPresent(settings -> {
                    status.setText("正在连接 " + targets.size() + " 台设备...");
                    targets.forEach(device -> {
                        desiredConnections.put(device.serial(), settings);
                        reconnectStates.remove(device.serial());
                        reconnecting.remove(device.serial());
                        connectDevice(device, settings, false);
                    });
                });
            });
        });
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings) {
        connectDevice(device, settings, false);
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings, boolean reconnect) {
        Thread.startVirtualThread(() -> {
            JavaSoundPlayer audioPlayer = null;
            java.util.concurrent.atomic.AtomicReference<FfmpegVideoDecoder> videoDecoderRef = new java.util.concurrent.atomic.AtomicReference<>();
            ScrcpySession session = null;
            SdlD3d11VideoRenderer sdlRenderer = null;
            try {
                ScrcpyOptions d = settings.toOptions(device.serial(), ScrcpyOptions.defaults());
                ScrcpyOptions options = new ScrcpyOptions(
                        device.serial(), d.scid(), d.localPort(),
                        d.video(), d.audio(), d.control(),
                        d.videoCodec(), d.videoEncoder(), d.videoCodecOptions(), d.videoDecoder(), d.audioCodec(),
                        d.videoBitRate(), d.audioBitRate(),
                        d.maxSize(), d.maxFps(),
                        d.showTouches(), d.stayAwake(),
                        d.turnScreenOff(), d.crop(), d.startApp());

                VideoView view = new VideoView();
                if (USE_SDL_D3D11_RENDERER) {
                    try {
                        sdlRenderer = SdlD3d11VideoRenderer.create(
                                "scrcpy-java - " + device.displayName(), 360, 680);
                    } catch (Throwable rendererError) {
                        // SDL/D3D11 is an experimental presentation backend.
                        // A renderer initialization failure must not prevent
                        // the device session from connecting: the decoder will
                        // use FFmpeg's default hardware device and transfer
                        // frames to the existing JavaFX BGRA path.
                        System.err.printf(
                                "[scrcpy-java][%s] SDL D3D11 renderer unavailable; falling back to JavaFX: %s%n",
                                device.serial(), rendererError);
                        rendererError.printStackTrace(System.err);
                        sdlRenderer = null;
                    }
                }
                final FfmpegVideoDecoder videoDecoder = d.video()
                        ? new FfmpegVideoDecoder(
                                view::accept,
                                d.videoDecoder(),
                                sdlRenderer != null
                                        ? sdlRenderer.hardwareDeviceProvider()
                                        : VideoHardwareDeviceProvider.DEFAULT,
                                device.serial(),
                                sdlRenderer)
                        : null;
                videoDecoderRef.set(videoDecoder);
                session = new ScrcpySession(
                        new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial()),
                        options);
                audioPlayer = new JavaSoundPlayer(session.metrics());
                JavaSoundPlayer player = audioPlayer;


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
                        videoDecoder,
                        player::accept);

                // SdlD3d11VideoRenderer.create() already owns its SDL lifecycle
                // on a dedicated platform render thread. Do not start a second pump loop here.
                final SdlD3d11VideoRenderer sessionSdlRenderer = sdlRenderer;

                Platform.runLater(() -> {
                    reconnecting.remove(device.serial());
                    reconnectStates.remove(device.serial());
                    if (!desiredConnections.containsKey(device.serial())) {
                        runningSession.close();
                        if (videoDecoder != null) videoDecoder.close();
                        player.close();
                        return;
                    }
                    if (sessions.containsKey(device.serial())) {
                        runningSession.close();
                        if (videoDecoder != null) videoDecoder.close();
                        player.close();
                        return;
                    }

                    UhidController uhid = new UhidController(runningSession::sendControl);
                    ClipboardController clipboard = new ClipboardController(runningSession::sendControl);
                    runningSession.addDeviceMessageListener(uhid::handle);
                    runningSession.addDeviceMessageListener(clipboard::handle);
                    clipboard.onRemoteClipboard(this::applyDeviceClipboard);
                    DeviceSession handle = new DeviceSession(
                            runningSession, player, videoDecoder, view,
                            new DeviceController(runningSession::sendControl), uhid, clipboard, sessionSdlRenderer);
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
                if (sdlRenderer != null) {
                    try {
                        sdlRenderer.close();
                    } catch (Throwable closeError) {
                        e.addSuppressed(closeError);
                    }
                }
                if (session != null) session.close();
                FfmpegVideoDecoder videoDecoder = videoDecoderRef.getAndSet(null);
                if (videoDecoder != null) videoDecoder.close();
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
        VBox tile = new VBox(0);
        tile.setStyle("-fx-background-color: #202020; -fx-border-color: #444444; -fx-border-width: 1;");
        tile.setPrefSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMinSize(TILE_WIDTH, TILE_HEIGHT);
        tile.setMaxSize(TILE_WIDTH, TILE_HEIGHT);

        Label title = new Label(device.displayName());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: black; -fx-background-color: white; -fx-padding: 4 6 4 6;");

        StackPane viewport = new StackPane(handle.view());
        viewport.setStyle("-fx-background-color: transparent;");
        viewport.setAlignment(Pos.TOP_CENTER);

        VBox navigationBar = createDeviceNavigationBar(handle, device);
        HBox videoAndNavigation = new HBox(0, viewport, navigationBar);
        videoAndNavigation.setAlignment(Pos.TOP_LEFT);

        handle.view().setPreserveRatio(false);
        handle.view().setSmooth(true);

        installControls(handle);

        HBox header = new HBox(6, title);
        HBox.setHgrow(title, Priority.ALWAYS);
        // Do not force a guessed header height. The Label's CSS/insets determine
        // its real height; forcing 29 px can leave unused dark space above the
        // video when the rendered header is shorter.
        header.setMinHeight(Region.USE_PREF_SIZE);
        header.setMaxHeight(Region.USE_PREF_SIZE);

        tile.getChildren().addAll(header, videoAndNavigation);
        VBox.setVgrow(videoAndNavigation, Priority.ALWAYS);

        handle.tile = tile;
        handle.viewport = viewport;
        handle.device = device;

        // The tile is created before the first decoded frame, so frameWidth/frameHeight
        // are still 0 here. Recalculate as soon as JavaFX receives the real image
        // dimensions (and again after rotation/resolution changes). Otherwise the
        // initial fixed viewport keeps preserveRatio=true and produces black bars
        // on the left and right of portrait video.
        handle.view().imageProperty().addListener((obs, oldImage, newImage) ->
                resizeTile(handle, isPrimary(handle)));

        resizeTile(handle, false);
        secondaryGrid.getChildren().add(tile);
    }

    private ToggleButton createAdaptClickButton(DeviceSession handle) {
        ToggleButton button = new ToggleButton();
        button.setCursor(Cursor.DEFAULT);
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
        setButtonIcon(button, enabled ? "adapt-click-off.png" : "adapt-click-on.png");
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
        setButtonIcon(button, uhid ? "mouse-mode.png" : "touch-mode.png");
        button.setTooltip(new Tooltip(uhid ? "UHID 鼠标（点击切换为触控）" : "触控（点击切换为 UHID 鼠标）"));
    }

    private void setMouseModeFromSidebar(DeviceSession source, boolean uhid) {
        MouseMode mode = uhid ? MouseMode.UHID_RELATIVE : MouseMode.ABSOLUTE_TOUCH;
        for (DeviceSession target : inputTargets(source)) {
            setMouseMode(target, mode);
        }
    }

    private VBox createDeviceNavigationBar(DeviceSession handle, AdbDevice device) {
        Button close = deviceControlButton("disconnect.png", "断开设备", () -> disconnect(device.serial()));

        ToggleButton primaryScreen = new ToggleButton();
        primaryScreen.setCursor(Cursor.DEFAULT);
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
        Button back = deviceControlButton("back.png", "返回", () -> rightSidebarKey(handle, AndroidKeycodes.BACK));
        Button home = deviceControlButton("home.png", "Home", () -> rightSidebarKey(handle, AndroidKeycodes.HOME));
        Button recent = deviceControlButton("recent-apps.png", "多任务", () -> rightSidebarKey(handle, 187));
        Button taskbar = deviceControlButton("notification-panel.png", "打开通知栏", () -> rightSidebarTaskbar(handle));
        Button volumeUp = deviceControlButton("volume-up.png", "音量加", () -> rightSidebarKey(handle, AndroidKeycodes.VOLUME_UP));
        Button volumeDown = deviceControlButton("volume-down.png", "音量减", () -> rightSidebarKey(handle, AndroidKeycodes.VOLUME_DOWN));
        Button power = deviceControlButton("power.png", "电源", () -> rightSidebarKey(handle, AndroidKeycodes.POWER));
        ToggleButton displayPower = new ToggleButton();
        displayPower.setCursor(Cursor.DEFAULT);
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

        ToggleButton stayAwake = new ToggleButton();
        stayAwake.setCursor(Cursor.DEFAULT);
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

        VBox bar = new VBox(5, close, primaryScreen, back, home, recent, taskbar, volumeUp, volumeDown, power, displayPower, stayAwake, mouseModeButton, adaptClickButton);
        bar.setAlignment(Pos.TOP_CENTER);
        bar.setPadding(new Insets(0, 2, 3, 2));
        bar.setStyle("-fx-background-color: #181818;");
        return bar;
    }

    private void rightSidebarKey(DeviceSession source, int keyCode) {
        for (DeviceSession target : inputTargets(source)) {
            pressAndroidKey(target, keyCode);
        }
    }

    private void rightSidebarTaskbar(DeviceSession source) {
        for (DeviceSession target : inputTargets(source)) {
            target.controller.expandNotificationPanel();
        }
    }

    private void rightSidebarDisplayPower(DeviceSession source, boolean on) {
        for (DeviceSession target : inputTargets(source)) {
            target.controller.setDisplayPower(on);
            target.session.metrics().setDisplayOn(on);
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
        setButtonIcon(button, primary ? "unset-primary.png" : "set-primary.png");
        button.setTooltip(new Tooltip(primary ? "取消主屏幕" : "设为主屏幕"));
    }

    private static void updateDisplayPowerToggle(ToggleButton button, boolean on) {
        button.setSelected(on);
        setButtonIcon(button, on ? "screen-off.png" : "screen-on.png");
        button.setTooltip(new Tooltip(on ? "熄屏" : "亮屏"));
    }

    private static void updateStayAwakeToggle(ToggleButton button, boolean enabled) {
        setButtonIcon(button, enabled ? "stay-awake-off.png" : "stay-awake-on.png");
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

    private static void setButtonIcon(ButtonBase button, String iconName) {
        button.setText("");
        button.setGraphic(null);

        if (iconName == null || iconName.isBlank()) {
            return;
        }

        String resourcePath = "/icons/" + iconName;
        var resource = ScrcpyApp.class.getResource(resourcePath);
        if (resource == null) {
            System.err.println("[scrcpy-java] icon resource not found: " + resourcePath);
            return;
        }

        // Load the PNG synchronously from the classpath stream. This avoids
        // JavaFX URL/resource handling differences between IDE runs and the
        // packaged Maven application.
        try (var input = resource.openStream()) {
            Image image = new Image(input, 24, 24, true, true);
            if (image.isError()) {
                System.err.println("[scrcpy-java] failed to load icon: " + resourcePath
                        + " (" + image.getException() + ")");
                return;
            }

            ImageView view = new ImageView(image);
            view.setFitWidth(24);
            view.setFitHeight(24);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.setCache(false);
            view.setMouseTransparent(true);
            button.setGraphic(view);
        } catch (Exception e) {
            System.err.println("[scrcpy-java] failed to read icon: " + resourcePath);
            e.printStackTrace(System.err);
        }
    }

    private Button deviceControlButton(String iconName, String tooltip, Runnable action) {
        Button button = new Button();
        setButtonIcon(button, iconName);
        button.setCursor(Cursor.DEFAULT);
        Tooltip tip = new Tooltip(tooltip);
        tip.setShowDelay(javafx.util.Duration.millis(250));
        button.setTooltip(tip);
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
                activeTouchPositions.put(handle, p);
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
            if (p != null) {
                activeTouchPositions.put(handle, p);
                broadcastTouch(handle, DeviceController.ACTION_MOVE, p, 1f, 0);
            }
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

    /**
     * Checks the actual displayed projection, not merely the ImageView layout bounds.
     * This matters when the ImageView contains letterboxing or when the pointer
     * crosses directly into another device tile during a fast swipe.
     */
    private boolean isScreenPointInsideProjection(VideoView view, double screenX, double screenY) {
        if (view == null) return false;
        javafx.geometry.Point2D local = view.screenToLocal(screenX, screenY);
        if (local == null) return false;
        return InputMapper.position(view, local.getX(), local.getY()) != null;
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

        if (handle.mouseMode == MouseMode.ABSOLUTE_TOUCH
                && (e.isPrimaryButtonDown() || activeMouseInput == handle)) {
            DeviceController.ScreenPosition release = activeTouchPositions.remove(handle);
            if (release == null) {
                VideoView view = handle.view();
                release = InputMapper.position(view,
                        Math.max(0, Math.min(Math.max(0, view.getBoundsInLocal().getWidth() - 1), e.getX())),
                        Math.max(0, Math.min(Math.max(0, view.getBoundsInLocal().getHeight() - 1), e.getY())));
            }
            if (release != null) {
                broadcastTouch(handle, DeviceController.ACTION_UP, release, 0f, 1);
            }
        }

        handle.relativePointerValid = false;
        handle.recentering = false;
        if (activeMouseInput == handle) activeMouseInput = null;
        activeTouchPositions.remove(handle);
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
        activeTouchPositions.clear();
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

        double maxWidth = primary ? 760 : TILE_WIDTH;
        double maxVideoHeight = primary ? 960 : TILE_HEIGHT;
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

        // BorderPane/TilePane may place the tile on fractional pixels. Rounding
        // the outer height upward used to leave a visible 0..1 px dark strip at
        // the bottom. Keep all dimensions on the same integer pixel grid.
        videoWidth = Math.rint(videoWidth);
        videoHeight = Math.rint(videoHeight);
        double controlsWidth = 38;
        // Let VBox compute the outer height from the real header + video row.
        // A hard-coded header estimate caused either a top or bottom black strip
        // depending on JavaFX font/CSS metrics.
        double tileWidth = videoWidth + controlsWidth;

        handle.viewport.setPrefSize(videoWidth, videoHeight);
        handle.viewport.setMinSize(videoWidth, videoHeight);
        handle.viewport.setMaxSize(videoWidth, videoHeight);
        handle.view().setFitWidth(videoWidth);
        handle.view().setFitHeight(videoHeight);

        handle.tile.setMinWidth(tileWidth);
        handle.tile.setPrefWidth(tileWidth);
        handle.tile.setMaxWidth(tileWidth);
        handle.tile.setMinHeight(Region.USE_PREF_SIZE);
        handle.tile.setPrefHeight(Region.USE_COMPUTED_SIZE);
        handle.tile.setMaxHeight(Region.USE_PREF_SIZE);
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

        // The touch/mouse control is a single toggle button. Keep its selected
        // state and PNG in sync whenever the mode is changed, including when
        // the change is broadcast from the primary device to other devices.
        if (handle.mouseModeButton != null) {
            updateMouseModeButton(handle.mouseModeButton, handle.mouseMode);
        }

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
        devices.getItems().stream()
                .filter(d -> checkedDevices.contains(d.serial()))
                .map(AdbDevice::serial)
                .toList()
                .forEach(this::disconnect);
    }

    private void disconnect(String serial) {
        desiredConnections.remove(serial);
        reconnecting.remove(serial);
        reconnectStates.remove(serial);
        if (Objects.equals(desiredPrimarySerial, serial)) {
            desiredPrimarySerial = null;
        }
        disconnectSession(serial);
    }

    private void disconnectForReconnect(String serial) {
        reconnecting.remove(serial);
        reconnectStates.computeIfAbsent(serial,
                key -> new ReconnectState(0, System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS));
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
        boolean hasSelection = !checkedDevices.isEmpty();
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
        reconnectStates.clear();
        checkedDevices.clear();
        closeAllSessions();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private record ReconnectState(int attempts, long nextAttemptAtMillis) {
        ReconnectState nextAttempt(long nextAttemptAtMillis) {
            return new ReconnectState(attempts + 1, nextAttemptAtMillis);
        }

        ReconnectState waitUntil(long nextAttemptAtMillis) {
            return new ReconnectState(attempts, nextAttemptAtMillis);
        }
    }

    private record ConnectionSettings(
            boolean video, boolean audio, boolean control,
            String videoCodec, String videoEncoder, String videoCodecOptions, String videoDecoder, String audioCodec,
            long videoBitRate, long audioBitRate,
            int maxSize, String maxFps,
            boolean showTouches, boolean stayAwake,
            boolean turnScreenOff, String crop, String startApp) {

        ScrcpyOptions toOptions(String serial, ScrcpyOptions base) {
            return new ScrcpyOptions(serial, base.scid(), base.localPort(),
                    video, audio, control, videoCodec, videoEncoder, videoCodecOptions, videoDecoder, audioCodec,
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
        private final FfmpegVideoDecoder videoDecoder;
        private final VideoView view;
        private final DeviceController controller;
        private final UhidController uhid;
        private final ClipboardController clipboard;
        private final SdlD3d11VideoRenderer sdlRenderer;
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
                FfmpegVideoDecoder videoDecoder,
                VideoView view,
                DeviceController controller,
                UhidController uhid,
                ClipboardController clipboard,
                SdlD3d11VideoRenderer sdlRenderer) {
            this.session = session;
            this.audioPlayer = audioPlayer;
            this.videoDecoder = videoDecoder;
            this.view = view;
            this.controller = controller;
            this.uhid = uhid;
            this.clipboard = clipboard;
            this.sdlRenderer = sdlRenderer;
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
                if (videoDecoder != null) videoDecoder.close();
            } catch (Throwable t) {
                first = t;
            }

            if (sdlRenderer != null) {
                sdlRenderer.requestClose();
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
