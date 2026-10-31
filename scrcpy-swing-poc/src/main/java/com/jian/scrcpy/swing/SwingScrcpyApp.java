package com.jian.scrcpy.swing;

import com.jian.scrcpy.ScrcpySession;
import com.jian.scrcpy.adb.Adb;
import com.jian.scrcpy.adb.AdbDevice;
import com.jian.scrcpy.control.ClipboardController;
import com.jian.scrcpy.control.DeviceController;
import com.jian.scrcpy.control.UhidController;
import com.jian.scrcpy.control.UhidMouse;
import com.jian.scrcpy.input.AndroidKeycodes;
import com.jian.scrcpy.media.FfmpegVideoDecoder;
import com.jian.scrcpy.media.HardwareVideoFrame;
import com.jian.scrcpy.media.JavaSoundPlayer;
import com.jian.scrcpy.media.VideoFrame;
import com.jian.scrcpy.media.VideoHardwareDeviceProvider;
import com.jian.scrcpy.media.VideoRenderer;
import com.jian.scrcpy.protocol.ControlMessages;
import com.jian.scrcpy.server.ScrcpyOptions;
import com.jian.scrcpy.server.ScrcpyServerArtifact;
import com.jian.scrcpy.server.ServerLauncher;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Independent Swing frontend for scrcpy-java.
 *
 * This class deliberately lives in scrcpy-swing-poc. It reuses scrcpy-core
 * services but has no dependency on JavaFX and does not modify scrcpy-app.
 */
public final class SwingScrcpyApp {
    private static final int TILE_WIDTH = 360;
    private static final int TILE_HEIGHT = 680;
    private static final int NAV_WIDTH = 40;
    private static final long RECONNECT_INTERVAL_MILLIS = 3_000;
    private static final int MAX_RECONNECT_ATTEMPTS = 20;

    private final JFrame frame = new JFrame("scrcpy-java - 多设备投屏（Swing）");
    private final JLabel status = new JLabel("正在扫描 ADB 设备...");
    private final JLabel primaryStatus = new JLabel("主设备：无");
    private final JPanel primaryHost = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel secondaryGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
    private final JScrollPane secondaryScroll = new JScrollPane(secondaryGrid);
    private final JPanel workspace = new JPanel(new BorderLayout(4, 0));
    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ConnectionSettings> desiredConnections = new ConcurrentHashMap<>();
    private final Set<String> reconnecting = ConcurrentHashMap.newKeySet();
    private final Set<String> checkedDevices = ConcurrentHashMap.newKeySet();
    private final Map<String, ReconnectState> reconnectStates = new ConcurrentHashMap<>();
    private final Map<String, JCheckBox> deviceChecks = new LinkedHashMap<>();
    private final Map<String, JLabel> deviceStates = new LinkedHashMap<>();
    private volatile List<AdbDevice> knownDevices = List.of();
    private final Map<DeviceSession, DeviceController.ScreenPosition> activeTouchPositions = new ConcurrentHashMap<>();
    private final AtomicBoolean applyingRemoteClipboard = new AtomicBoolean();
    private final ScheduledExecutorService background = Executors.newScheduledThreadPool(2,
            Thread.ofVirtual().name("scrcpy-swing-background-", 0).factory());
    private final Robot mouseRobot;
    private final Adb adb;
    private volatile String primarySerial;
    private volatile String desiredPrimarySerial;
    private volatile DeviceSession activeMouseInput;
    private volatile boolean adaptScreenClick;
    private volatile String lastDesktopClipboard = "";
    private volatile boolean closing;
    private JDialog deviceDialog;

    public SwingScrcpyApp() {
        adb = new Adb(System.getProperty("scrcpy.adb"), null);
        Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            robot = null;
            System.err.println("[scrcpy-swing] Robot unavailable: " + e);
        }
        mouseRobot = robot;
        buildWindow();
        installGlobalInputGuards();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SwingScrcpyApp().show());
    }

    private void show() {
        frame.setVisible(true);
        scanDevices();
        startMonitors();
    }

    private void buildWindow() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1050, 700));
        frame.setSize(1500, 950);
        frame.setLocationByPlatform(true);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 7));
        JButton devices = new JButton("设备");
        JButton files = new JButton("传送文件");
        JButton install = new JButton("批量安装应用");
        JButton toggleAllOrientation = new JButton("全部切换横竖屏");
        toggleAllOrientation.setToolTipText("将所有正在投屏的设备统一切换为横屏或竖屏");
        devices.addActionListener(e -> showDeviceManager());
        files.addActionListener(e -> showBatchDialog(false));
        install.addActionListener(e -> showBatchDialog(true));
        toggleAllOrientation.addActionListener(e -> toggleAllOrientations());
        toolbar.add(devices);
        toolbar.add(files);
        toolbar.add(install);
        toolbar.add(toggleAllOrientation);
        toolbar.add(primaryStatus);
        toolbar.add(status);
        toolbar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(215, 215, 215)));

        primaryHost.setOpaque(true);
        primaryHost.setBackground(Color.WHITE);
        primaryHost.setDoubleBuffered(false);
        workspace.setOpaque(true);
        workspace.setDoubleBuffered(false);
        primaryHost.setPreferredSize(new Dimension(0, 0));
        primaryHost.setVisible(false);
        secondaryGrid.setBackground(Color.WHITE);
        secondaryGrid.setBorder(new EmptyBorder(10, 10, 10, 10));
        secondaryScroll.setBorder(BorderFactory.createEmptyBorder());
        secondaryScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        secondaryScroll.getViewport().setBackground(Color.WHITE);
        secondaryScroll.getVerticalScrollBar().setUnitIncrement(24);
        workspace.setBackground(Color.WHITE);
        workspace.add(secondaryScroll, BorderLayout.CENTER);
        workspace.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                DeviceSession primary = primarySerial == null ? null : sessions.get(primarySerial);
                if (primary != null) resizeTile(primary, true);
            }
        });

        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(workspace, BorderLayout.CENTER);
        frame.add(statusBar(), BorderLayout.SOUTH);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) {
                releaseAllTransientInput();
            }
        });
    }

    private JComponent statusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(3, 8, 3, 8));
        panel.add(status, BorderLayout.WEST);
        return panel;
    }

    private void installGlobalInputGuards() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent e)) return;
            DeviceSession active = activeMouseInput;
            if (active == null || active.view == null) return;
            // Native heavyweight Canvas events originate from the Canvas child,
            // not from SwingVideoSurface itself. Treat every descendant as part
            // of the active video view so the global drag/release guard does
            // not consume events before SwingVideoSurface forwards them.
            Object source = e.getSource();
            if (source instanceof Component component
                    && (component == active.view || SwingUtilities.isDescendingFrom(component, active.view))) {
                return;
            }
            if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
                if (!containsScreenPoint(active.view, e.getXOnScreen(), e.getYOnScreen())) {
                    endMouseInput(active, e);
                    e.consume();
                }
            } else if (e.getID() == MouseEvent.MOUSE_RELEASED && e.getButton() == MouseEvent.BUTTON1) {
                endMouseInput(active, e);
                e.consume();
            }
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    private void showDeviceManager() {
        if (deviceDialog != null && deviceDialog.isShowing()) {
            deviceDialog.toFront();
            return;
        }
        deviceDialog = new JDialog(frame, "设备管理", Dialog.ModalityType.MODELESS);
        deviceDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(UIManager.getColor("List.background"));
        rebuildDeviceRows(list);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(620, 440));

        JButton refresh = new JButton("刷新");
        JButton connect = new JButton("连接选中");
        JButton connectAll = new JButton("全部连接");
        JButton disconnect = new JButton("断开选中");
        refresh.addActionListener(e -> scanDevices());
        connect.addActionListener(e -> connectSelected());
        connectAll.addActionListener(e -> connectAll());
        disconnect.addActionListener(e -> disconnectSelected());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(refresh); actions.add(connect); actions.add(connectAll); actions.add(disconnect);

        deviceDialog.add(scroll, BorderLayout.CENTER);
        deviceDialog.add(actions, BorderLayout.SOUTH);
        deviceDialog.pack();
        deviceDialog.setLocationRelativeTo(frame);
        deviceDialog.setVisible(true);
    }

    private void rebuildDeviceRows(JPanel list) {
        list.removeAll();
        deviceChecks.clear();
        deviceStates.clear();
        try {
            for (AdbDevice d : knownDevices) {
                JCheckBox check = new JCheckBox(d.displayName() + "  [" + (sessions.containsKey(d.serial()) ? "投屏中" : d.state()) + "]");
                check.setSelected(checkedDevices.contains(d.serial()));
                check.addActionListener(e -> {
                    if (check.isSelected()) checkedDevices.add(d.serial());
                    else checkedDevices.remove(d.serial());
                });
                JLabel state = new JLabel(sessions.containsKey(d.serial()) ? "投屏中" : d.state());
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(true);
                row.setBackground(UIManager.getColor("List.background"));
                row.setBorder(new EmptyBorder(2, 6, 2, 6));
                row.add(check, BorderLayout.CENTER);
                row.add(state, BorderLayout.EAST);
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                Dimension preferred = row.getPreferredSize();
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
                list.add(row);
                deviceChecks.put(d.serial(), check);
                deviceStates.put(d.serial(), state);
            }
        } catch (Exception e) {
            list.add(new JLabel("读取 ADB 设备失败：" + e.getMessage()));
        }
        list.revalidate();
        list.repaint();
    }

    private void showBatchDialog(boolean apkMode) {
        if (sessions.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "当前没有正在投屏的设备。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String title = apkMode ? "批量安装应用" : "传送文件";
        JDialog dialog = new JDialog(frame, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        DefaultListModel<File> fileModel = new DefaultListModel<>();
        JList<File> fileList = new JList<>(fileModel);
        fileList.setVisibleRowCount(7);
        fileList.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "" : value.getAbsolutePath());
            label.setOpaque(true);
            label.setBorder(new EmptyBorder(3, 5, 3, 5));
            if (selected) { label.setBackground(list.getSelectionBackground()); label.setForeground(list.getSelectionForeground()); }
            else { label.setBackground(list.getBackground()); label.setForeground(list.getForeground()); }
            return label;
        });
        JButton choose = new JButton(apkMode ? "选择 APK..." : "选择文件...");
        JButton execute = new JButton(apkMode ? "安装" : "发送");
        JLabel result = new JLabel(" ");
        JPanel targets = new JPanel();
        targets.setLayout(new BoxLayout(targets, BoxLayout.Y_AXIS));
        Map<String, JCheckBox> targetChecks = new LinkedHashMap<>();
        sessions.values().stream().filter(s -> s.device != null)
                .sorted(Comparator.comparing(s -> s.device.displayName()))
                .forEach(s -> {
                    JCheckBox check = new JCheckBox(s.device.displayName(), true);
                    targetChecks.put(s.device.serial(), check);
                    targets.add(check);
                });
        JScrollPane targetScroll = new JScrollPane(targets);
        targetScroll.setPreferredSize(new Dimension(400, 120));
        JPanel progress = new JPanel();
        progress.setLayout(new BoxLayout(progress, BoxLayout.Y_AXIS));
        Map<String, JLabel> progressLabels = new LinkedHashMap<>();
        targetChecks.forEach((serial, check) -> {
            JLabel label = new JLabel(check.getText() + "：等待" + (apkMode ? "安装" : "发送"));
            progressLabels.put(serial, label);
            progress.add(label);
        });
        JScrollPane progressScroll = new JScrollPane(progress);
        progressScroll.setPreferredSize(new Dimension(400, 100));

        choose.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            if (apkMode) chooser.setFileFilter(new FileNameExtensionFilter("Android APK (*.apk)", "apk"));
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                fileModel.clear();
                for (File file : chooser.getSelectedFiles()) fileModel.addElement(file);
                if (fileModel.isEmpty() && chooser.getSelectedFile() != null) fileModel.addElement(chooser.getSelectedFile());
            }
        });
        execute.addActionListener(e -> {
            List<File> selectedFiles = Collections.list(fileModel.elements());
            List<String> serials = targetChecks.entrySet().stream().filter(x -> x.getValue().isSelected())
                    .map(Map.Entry::getKey).toList();
            if (selectedFiles.isEmpty()) { result.setText("请先选择" + (apkMode ? " APK 安装包" : "文件")); return; }
            if (serials.isEmpty()) { result.setText("请至少选择一台设备"); return; }
            execute.setEnabled(false); choose.setEnabled(false);
            serials.forEach(serial -> progressLabels.get(serial).setText(targetChecks.get(serial).getText() + "：0 / " + selectedFiles.size()));
            Thread.startVirtualThread(() -> runBatch(apkMode, selectedFiles, serials, result, progressLabels, execute, choose));
        });

        JPanel top = new JPanel(new BorderLayout(8, 8));
        top.add(choose, BorderLayout.NORTH);
        top.add(new JScrollPane(fileList), BorderLayout.CENTER);
        JPanel middle = new JPanel(new BorderLayout(8, 8));
        middle.setBorder(new EmptyBorder(6, 0, 6, 0));
        middle.add(new JLabel("目标设备"), BorderLayout.NORTH);
        middle.add(targetScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(execute, BorderLayout.NORTH);
        bottom.add(result, BorderLayout.CENTER);
        bottom.add(progressScroll, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        content.add(top, BorderLayout.NORTH);
        content.add(middle, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.add(content);
        dialog.setSize(700, 620);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
    }

    private void runBatch(boolean apkMode, List<File> files, List<String> serials,
                          JLabel result, Map<String, JLabel> progressLabels,
                          JButton execute, JButton choose) {
        int total = files.size() * serials.size();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        List<String> failures = new CopyOnWriteArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String serial : serials) {
                executor.submit(() -> {
                    DeviceSession session = sessions.get(serial);
                    String name = session != null && session.device != null ? session.device.displayName() : serial;
                    Adb target = new Adb(System.getProperty("scrcpy.adb", "adb"), serial);
                    for (int i = 0; i < files.size(); i++) {
                        File file = files.get(i);
                        try {
                            if (apkMode) target.run("install", "-r", file.getAbsolutePath());
                            else target.push(file.toPath(), "/sdcard/Download/" + file.getName());
                            success.incrementAndGet();
                        } catch (Exception ex) {
                            failures.add(name + " / " + file.getName() + ": " + ex.getMessage());
                        }
                        int deviceDone = i + 1;
                        int allDone = done.incrementAndGet();
                        int allSuccess = success.get();
                        SwingUtilities.invokeLater(() -> {
                            JLabel label = progressLabels.get(serial);
                            if (label != null) label.setText(name + "：" + deviceDone + " / " + files.size());
                            result.setText("总进度 " + allDone + " / " + total + "，成功 " + allSuccess);
                        });
                    }
                });
            }
        }
        int finalSuccess = success.get();
        SwingUtilities.invokeLater(() -> {
            String message = (apkMode ? "安装" : "发送") + "完成：成功 " + finalSuccess + " / " + total;
            if (!failures.isEmpty()) message += "，失败 " + failures.size() + "（" + String.join("；", failures) + "）";
            result.setText(message);
            execute.setEnabled(true);
            choose.setEnabled(true);
            status.setText(message);
        });
    }

    private void startMonitors() {
        background.scheduleWithFixedDelay(this::pollAdbState, 1500, 1500, TimeUnit.MILLISECONDS);
        background.scheduleWithFixedDelay(this::syncDesktopClipboard, 250, 250, TimeUnit.MILLISECONDS);
    }

    private void scanDevices() {
        Thread.startVirtualThread(() -> {
            try {
                List<AdbDevice> found = adb.devices();
                knownDevices = List.copyOf(found);
                SwingUtilities.invokeLater(() -> {
                    status.setText("发现 " + found.size() + " 台 ADB 设备");
                    if (deviceDialog != null && deviceDialog.isShowing()) {
                        JPanel list = (JPanel) ((JScrollPane) deviceDialog.getContentPane().getComponent(0)).getViewport().getView();
                        rebuildDeviceRows(list);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> status.setText("ADB 扫描失败：" + e.getMessage()));
            }
        });
    }

    private void pollAdbState() {
        if (closing) return;
        try {
            List<AdbDevice> snapshot = adb.devices();
            knownDevices = List.copyOf(snapshot);
            Map<String, AdbDevice> current = snapshot.stream().collect(Collectors.toMap(AdbDevice::serial, d -> d, (a, b) -> a));
            List<String> lost = sessions.keySet().stream().filter(serial -> {
                AdbDevice d = current.get(serial);
                return d == null || !d.ready();
            }).toList();
            if (!lost.isEmpty()) SwingUtilities.invokeLater(() -> lost.forEach(serial -> {
                if (sessions.containsKey(serial)) {
                    status.setText("设备已离线：" + serial + "，3 秒后尝试自动重连");
                    disconnectForReconnect(serial);
                }
            }));
            long now = System.currentTimeMillis();
            desiredConnections.forEach((serial, settings) -> {
                if (sessions.containsKey(serial) || reconnecting.contains(serial)) return;
                ReconnectState retry = reconnectStates.get(serial);
                if (retry == null || now < retry.nextAttemptAtMillis) return;
                if (retry.attempts >= MAX_RECONNECT_ATTEMPTS) {
                    reconnectStates.remove(serial);
                    desiredConnections.remove(serial);
                    SwingUtilities.invokeLater(() -> status.setText("设备自动重连失败：" + serial + "，已达到 " + MAX_RECONNECT_ATTEMPTS + " 次"));
                    return;
                }
                AdbDevice d = current.get(serial);
                if (d == null || !d.ready()) {
                    reconnectStates.computeIfPresent(serial, (k, v) -> new ReconnectState(v.attempts, now + RECONNECT_INTERVAL_MILLIS));
                    return;
                }
                ReconnectState next = new ReconnectState(retry.attempts + 1, now + RECONNECT_INTERVAL_MILLIS);
                reconnectStates.put(serial, next);
                if (reconnecting.add(serial)) SwingUtilities.invokeLater(() -> {
                    status.setText("正在自动重连 " + d.displayName() + "（" + next.attempts + "/" + MAX_RECONNECT_ATTEMPTS + "）");
                    connectDevice(d, settings, true);
                });
            });
        } catch (Throwable ignored) {
            // A transient ADB restart must not tear down active sessions.
        }
    }

    private void syncDesktopClipboard() {
        if (applyingRemoteClipboard.get()) return;
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (!(value instanceof String text) || text.isEmpty() || Objects.equals(text, lastDesktopClipboard)) return;
            lastDesktopClipboard = text;
            sessions.values().forEach(s -> s.clipboard.setRemoteClipboard(text));
        } catch (IllegalStateException | java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException ignored) {
        }
    }

    private void applyDeviceClipboard(String text) {
        if (text == null || Objects.equals(text, lastDesktopClipboard)) return;
        SwingUtilities.invokeLater(() -> {
            applyingRemoteClipboard.set(true);
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                lastDesktopClipboard = text;
            } catch (IllegalStateException ignored) {
            } finally {
                applyingRemoteClipboard.set(false);
            }
        });
    }

    private void connectSelected() {
        List<AdbDevice> selected = knownDevices.stream()
                .filter(d -> checkedDevices.contains(d.serial())).toList();
        connectDevices(selected);
    }

    private void connectAll() {
        connectDevices(knownDevices.stream().filter(AdbDevice::ready)
                .filter(d -> !sessions.containsKey(d.serial())).toList());
    }

    private void connectDevices(List<AdbDevice> selected) {
        List<AdbDevice> targets = selected.stream().filter(AdbDevice::ready)
                .filter(d -> !sessions.containsKey(d.serial())).toList();
        if (targets.isEmpty()) { status.setText("没有可连接的新设备"); return; }
        status.setText("正在读取设备视频编码器...");
        Thread.startVirtualThread(() -> {
            Map<String, List<ServerLauncher.VideoEncoder>> capabilities = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();
            try {
                Path jar = ScrcpyServerArtifact.resolve();
                for (AdbDevice device : targets) {
                    try {
                        ServerLauncher launcher = new ServerLauncher(new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial()), ScrcpyOptions.defaults());
                        capabilities.put(device.serial(), launcher.listVideoEncoders(jar));
                    } catch (Throwable t) {
                        failures.add(device.displayName() + ": " + t.getMessage());
                        capabilities.put(device.serial(), List.of());
                    }
                }
            } catch (Throwable t) { failures.add("读取编码器失败：" + t.getMessage()); }
            SwingUtilities.invokeLater(() -> {
                status.setText(failures.isEmpty() ? "正在连接 " + targets.size() + " 台设备..." : "编码器查询部分失败，将仅提供自动选择");
                ConnectionSettings settings = showConnectionSettings(capabilities);
                if (settings == null) return;
                for (AdbDevice d : targets) {
                    desiredConnections.put(d.serial(), settings);
                    reconnectStates.remove(d.serial());
                    reconnecting.remove(d.serial());
                    connectDevice(d, settings, false);
                }
            });
        });
    }

    private ConnectionSettings showConnectionSettings(Map<String, List<ServerLauncher.VideoEncoder>> capabilities) {
        JCheckBox video = new JCheckBox("视频", true);
        JCheckBox audio = new JCheckBox("音频", true);
        JCheckBox control = new JCheckBox("控制", true);
        JCheckBox showTouches = new JCheckBox("显示触摸点");
        JCheckBox stayAwake = new JCheckBox("保持唤醒");
        JCheckBox turnScreenOff = new JCheckBox("关闭设备屏幕");
        JComboBox<String> codec = combo("H.264", "H.265", "AV1");
        JComboBox<String> encoder = combo("自动（推荐）");
        JComboBox<String> decoder = combo("硬件解码", "软件解码");
        JTextField codecOptions = new JTextField();
        codecOptions.setToolTipText("例如 bitrate-mode:int=1,profile:int=1");
        JComboBox<String> audioCodec = combo("opus", "aac", "flac");
        JComboBox<String> videoBitrate = combo("2M", "4M", "6M", "8M", "10M", "12M", "16M", "20M");
        JComboBox<String> audioBitrate = combo("64K", "96K", "128K", "160K", "192K", "256K", "320K");
        JComboBox<String> maxSize = combo("不限制", "720", "1080", "1440", "1920", "2560", "3840");
        JComboBox<String> maxFps = combo("不限制", "30", "45", "60", "90", "120", "144");
        JComboBox<String> crop = combo("不裁剪", "1080:1920:0:0", "1920:1080:0:0", "1440:2560:0:0");
        JTextField startApp = new JTextField();
        codec.setSelectedItem("H.264"); decoder.setSelectedItem("硬件解码");
        audioCodec.setSelectedItem("opus"); videoBitrate.setSelectedItem("8M"); audioBitrate.setSelectedItem("128K");
        maxSize.setSelectedItem("1080"); maxFps.setSelectedItem("60"); crop.setSelectedItem("不裁剪");

        Runnable refreshEncoders = () -> {
            String selectedCodec = codecName((String) codec.getSelectedItem());
            List<String> common = commonVideoEncoderNames(selectedCodec, capabilities);
            Object previous = encoder.getSelectedItem();
            encoder.removeAllItems(); encoder.addItem("自动（推荐）");
            common.forEach(encoder::addItem);
            if (previous != null && containsCombo(encoder, previous.toString())) encoder.setSelectedItem(previous);
            else encoder.setSelectedIndex(0);
            encoder.setEnabled(video.isSelected());
        };
        codec.addActionListener(e -> refreshEncoders.run());
        video.addActionListener(e -> {
            codec.setEnabled(video.isSelected()); encoder.setEnabled(video.isSelected()); codecOptions.setEnabled(video.isSelected());
        });
        refreshEncoders.run();

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6); g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        int row = 0;
        addSettingsRow(panel, g, row++, video, audio, control);
        addSettingsRow(panel, g, row++, new JLabel("视频编码"), codec, null);
        addSettingsRow(panel, g, row++, new JLabel("视频编码器"), encoder, null);
        addSettingsRow(panel, g, row++, new JLabel("视频解码器"), decoder, null);
        addSettingsRow(panel, g, row++, new JLabel("音频编码"), audioCodec, null);
        addSettingsRow(panel, g, row++, new JLabel("视频码率"), videoBitrate, null);
        addSettingsRow(panel, g, row++, new JLabel("音频码率"), audioBitrate, null);
        addSettingsRow(panel, g, row++, new JLabel("最大分辨率"), maxSize, null);
        addSettingsRow(panel, g, row++, new JLabel("最大帧率"), maxFps, null);
        addSettingsRow(panel, g, row++, new JLabel("裁剪"), crop, null);
        addSettingsRow(panel, g, row++, new JLabel("启动应用"), startApp, null);
        addSettingsRow(panel, g, row++, showTouches, stayAwake, turnScreenOff);
        JPanel advanced = new JPanel(new BorderLayout(4, 4));
        advanced.setBorder(BorderFactory.createTitledBorder("高级编码设置"));
        advanced.add(new JLabel("向 Android MediaFormat 传递自定义编码参数。留空使用默认参数。"), BorderLayout.NORTH);
        advanced.add(codecOptions, BorderLayout.CENTER);
        JPanel content = new JPanel(new BorderLayout(4, 4));
        content.add(panel, BorderLayout.CENTER); content.add(advanced, BorderLayout.SOUTH);
        int answer = JOptionPane.showConfirmDialog(frame, new JScrollPane(content), "scrcpy 连接设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return null;
        try {
            String selectedEncoder = Objects.toString(encoder.getSelectedItem(), "自动（推荐）");
            return new ConnectionSettings(video.isSelected(), audio.isSelected(), control.isSelected(),
                    codecName((String) codec.getSelectedItem()),
                    "自动（推荐）".equals(selectedEncoder) ? null : blankToNull(selectedEncoder),
                    blankToNull(codecOptions.getText()),
                    "软件解码".equals(decoder.getSelectedItem()) ? "software" : "hardware",
                    Objects.toString(audioCodec.getSelectedItem(), "opus"),
                    parseBitRate(Objects.toString(videoBitrate.getSelectedItem(), "8M")),
                    parseBitRate(Objects.toString(audioBitrate.getSelectedItem(), "128K")),
                    parsePositiveInt(Objects.toString(maxSize.getSelectedItem(), "1080"), 1080),
                    "不限制".equals(maxFps.getSelectedItem()) ? null : Objects.toString(maxFps.getSelectedItem(), null),
                    showTouches.isSelected(), stayAwake.isSelected(), turnScreenOff.isSelected(),
                    "不裁剪".equals(crop.getSelectedItem()) ? null : Objects.toString(crop.getSelectedItem(), null),
                    normalizeStartApp(startApp.getText()));
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "参数错误", JOptionPane.ERROR_MESSAGE);
            return showConnectionSettings(capabilities);
        }
    }

    private static void addSettingsRow(JPanel panel, GridBagConstraints base, int row, Component first, Component second, Component third) {
        GridBagConstraints g = (GridBagConstraints) base.clone();
        g.gridy = row; g.gridx = 0; g.weightx = 1;
        if (second == null) {
            g.gridwidth = 1; panel.add(first, g);
            g.gridx = 1; g.gridwidth = 2; panel.add((Component) first, g);
            return;
        }
        if (third == null) {
            g.gridx = 0; g.gridwidth = 1; panel.add(first, g);
            g.gridx = 1; g.gridwidth = 2; panel.add(second, g);
        } else {
            g.gridx = 0; g.gridwidth = 1; panel.add(first, g);
            g.gridx = 1; panel.add(second, g);
            g.gridx = 2; panel.add(third, g);
        }
    }

    private static <T> JComboBox<T> combo(T... values) {
        JComboBox<T> box = new JComboBox<>(values);
        box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        return box;
    }

    private static boolean containsCombo(JComboBox<?> box, String value) {
        for (int i = 0; i < box.getItemCount(); i++) if (Objects.equals(box.getItemAt(i), value)) return true;
        return false;
    }

    private static List<String> commonVideoEncoderNames(String codec, Map<String, List<ServerLauncher.VideoEncoder>> capabilities) {
        if (capabilities.isEmpty()) return List.of();
        Set<String> common = null;
        List<String> order = List.of();
        for (List<ServerLauncher.VideoEncoder> encoders : capabilities.values()) {
            LinkedHashSet<String> names = encoders.stream().filter(e -> codec.equals(e.codec()))
                    .map(ServerLauncher.VideoEncoder::name).collect(Collectors.toCollection(LinkedHashSet::new));
            if (common == null) { common = new LinkedHashSet<>(names); order = new ArrayList<>(names); }
            else common.retainAll(names);
        }
        if (common == null) return List.of();
        return order.stream().filter(common::contains).toList();
    }

    private static String codecName(String label) {
        return switch (Objects.requireNonNullElse(label, "H.264")) {
            case "H.265" -> "h265"; case "AV1" -> "av1"; default -> "h264";
        };
    }

    private static String normalizeStartApp(String value) {
        String name = blankToNull(value);
        if (name == null) return null;
        boolean forceStop = name.startsWith("+");
        String target = forceStop ? name.substring(1) : name;
        if (!target.startsWith("?") && !target.contains(".")) target = "?" + target;
        return forceStop ? "+" + target : target;
    }

    private static String blankToNull(String value) {
        String s = Objects.requireNonNullElse(value, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static long parseBitRate(String value) {
        String s = Objects.requireNonNullElse(value, "").trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return 0;
        long multiplier = 1;
        if (s.endsWith("M")) { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("K")) { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        long n = Long.parseLong(s.trim());
        if (n < 0 || n > Long.MAX_VALUE / multiplier) throw new IllegalArgumentException("码率超出范围");
        return n * multiplier;
    }

    private static int parsePositiveInt(String value, int fallback) {
        String s = Objects.requireNonNullElse(value, "").trim();
        if (s.isEmpty()) return fallback;
        int n = Integer.parseInt(s);
        if (n < 0) throw new IllegalArgumentException("数值必须是非负整数");
        return n;
    }

    private void connectDevice(AdbDevice device, ConnectionSettings settings, boolean reconnect) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> connectDevice(device, settings, reconnect));
            return;
        }

        // Swing components and their heavyweight Canvas peers are created on
        // the EDT. The decoder/network work below remains on a virtual thread.
        SwingVideoSurface view = new SwingVideoSurface();
        SwingHardwareVideoRenderer nativeRenderer = null;
        if (settings.video() && "hardware".equalsIgnoreCase(settings.videoDecoder())) {
            try {
                nativeRenderer = SwingHardwareRendererFactory.create(view);
                if (nativeRenderer != null) {
                    System.out.println("[scrcpy-swing] native hardware renderer selected: " + nativeRenderer.backend());
                }
            } catch (Throwable nativeError) {
                System.err.println("[scrcpy-swing] native hardware renderer unavailable; using Java2D: " + nativeError);
            }
        }
        SwingHardwareVideoRenderer renderer = nativeRenderer;

        Thread.startVirtualThread(() -> {
            JavaSoundPlayer player = null;
            FfmpegVideoDecoder decoder = null;
            ScrcpySession session = null;
            try {
                ScrcpyOptions options = settings.toOptions(device.serial(), ScrcpyOptions.defaults());
                player = new JavaSoundPlayer();
                VideoHardwareDeviceProvider hardwareProvider = renderer != null
                        ? renderer : VideoHardwareDeviceProvider.DEFAULT;
                VideoRenderer videoRenderer = renderer;
                decoder = options.video()
                        ? new FfmpegVideoDecoder(view::accept, options.videoDecoder(),
                        hardwareProvider, device.serial(), videoRenderer)
                        : null;
                session = new ScrcpySession(new Adb(System.getProperty("scrcpy.adb", "adb"), device.serial()), options);
                ScrcpySession running = session;
                running.onTransportFailure(cause -> SwingUtilities.invokeLater(() -> {
                    DeviceSession active = sessions.get(device.serial());
                    if (active == null || active.session != running) return;
                    status.setText("设备连接异常：" + device.displayName() + "，正在隔离并重连");
                    disconnectForReconnect(device.serial());
                }));
                running.start(ScrcpyServerArtifact.resolve(), decoder, player::accept);
                JavaSoundPlayer audio = player;
                FfmpegVideoDecoder video = decoder;
                SwingUtilities.invokeLater(() -> {
                    reconnecting.remove(device.serial());
                    reconnectStates.remove(device.serial());
                    if (!desiredConnections.containsKey(device.serial()) || sessions.containsKey(device.serial())) {
                        running.close();
                        if (video != null) video.close();
                        if (renderer != null) renderer.close();
                        audio.close();
                        return;
                    }
                    UhidController uhid = new UhidController(running::sendControl);
                    ClipboardController clipboard = new ClipboardController(running::sendControl);
                    running.addDeviceMessageListener(uhid::handle);
                    running.addDeviceMessageListener(clipboard::handle);
                    clipboard.onRemoteClipboard(this::applyDeviceClipboard);
                    DeviceSession handle = new DeviceSession(running, audio, video, renderer, view,
                            new DeviceController(running::sendControl), uhid, clipboard);
                    sessions.put(device.serial(), handle);
                    addScreen(handle, device);
                    if (Objects.equals(desiredPrimarySerial, device.serial())) setPrimary(handle);
                    status.setText("投屏中 " + sessions.size() + " 台设备");
                    refreshDeviceRows();
                });
            } catch (Throwable e) {
                reconnecting.remove(device.serial());
                if (decoder != null) decoder.close();
                if (renderer != null) renderer.close();
                if (session != null) session.close();
                if (player != null) player.close();
                view.close();
                SwingUtilities.invokeLater(() -> {
                    status.setText((reconnect ? "自动重连 " : "连接 ") + device.displayName() + " 失败：" + e.getMessage());
                    refreshDeviceRows();
                });
                e.printStackTrace(System.err);
            }
        });
    }

    private void addScreen(DeviceSession handle, AdbDevice device) {
        // A session must own exactly one tile. This also protects against a
        // late/repeated EDT callback after reconnect or a rapid connect action.
        if (sessions.get(device.serial()) != handle || handle.tile != null) return;
        JPanel tile = new JPanel(new BorderLayout(0, 0));
        tile.setOpaque(true);
        tile.setBackground(Color.WHITE);
        tile.setDoubleBuffered(false);
        tile.setBorder(BorderFactory.createLineBorder(new Color(68, 68, 68)));
        JLabel title = new JLabel(device.displayName());
        title.setOpaque(true); title.setBackground(Color.WHITE); title.setForeground(Color.BLACK);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(new EmptyBorder(4, 6, 4, 6));
        JPanel videoAndNavigation = new JPanel(new BorderLayout(0, 0));
        videoAndNavigation.setOpaque(true);
        videoAndNavigation.setBackground(Color.WHITE);
        videoAndNavigation.setDoubleBuffered(false);
        JPanel viewport = new JPanel(new BorderLayout());
        viewport.setOpaque(true);
        viewport.setBackground(Color.WHITE);
        viewport.setDoubleBuffered(false);
        viewport.add(handle.view, BorderLayout.CENTER);
        JPanel navigation = createNavigationBar(handle, device);
        videoAndNavigation.add(viewport, BorderLayout.CENTER);
        videoAndNavigation.add(navigation, BorderLayout.EAST);
        tile.add(title, BorderLayout.NORTH);
        tile.add(videoAndNavigation, BorderLayout.CENTER);
        handle.tile = tile;
        handle.viewport = viewport;
        handle.device = device;
        Runnable updateFrameGeometry = () -> {
            int frameWidth = handle.view.frameWidth();
            int frameHeight = handle.view.frameHeight();
            if (frameWidth > 0 && frameHeight > 0) {
                // A device may already be in landscape when it connects. Do not
                // assume portrait until the first decoded frame tells us its
                // actual orientation; otherwise the secondary tile is capped
                // at TILE_WIDTH (360px) and appears half-sized.
                boolean actualLandscape = frameWidth > frameHeight;
                if (handle.landscape != actualLandscape) {
                    handle.landscape = actualLandscape;
                    updateOrientationButton(handle);
                }
            }
            resizeTile(handle, isPrimary(handle));
        };
        handle.view.setFrameSizeListener(updateFrameGeometry);
        installControls(handle);
        // The first frame can arrive before the listener is installed.
        // Synchronize once now as well as on every subsequent size change.
        updateFrameGeometry.run();
        secondaryGrid.add(tile);
        secondaryGrid.revalidate(); secondaryGrid.repaint();
    }

    private JPanel createNavigationBar(DeviceSession handle, AdbDevice device) {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        bar.setBackground(new Color(24, 24, 24));
        bar.setBorder(new EmptyBorder(2, 2, 2, 2));
        JButton close = controlButton("disconnect.png", "断开设备", () -> disconnect(device.serial()));
        JToggleButton primary = toggleButton("set-primary.png", "设为主屏幕");
        handle.primaryButton = primary;
        primary.addActionListener(e -> togglePrimary(handle));
        JToggleButton adapt = toggleButton(null, "开启主设备点击坐标适配");
        handle.adaptButton = adapt;
        updateAdaptButton(adapt);
        adapt.addActionListener(e -> setAdaptScreenClick(adapt.isSelected()));
        JToggleButton mouse = toggleButton("touch-mode.png", "触控（点击切换为 UHID 鼠标）");
        handle.mouseModeButton = mouse;
        mouse.addActionListener(e -> setMouseModeFromSidebar(handle, mouse.isSelected()));
        JButton back = controlButton("back.png", "返回", () -> sidebarKey(handle, AndroidKeycodes.BACK));
        JButton home = controlButton("home.png", "Home", () -> sidebarKey(handle, AndroidKeycodes.HOME));
        JButton recent = controlButton("recent-apps.png", "多任务", () -> sidebarKey(handle, 187));
        JButton taskbar = controlButton("notification-panel.png", "打开通知栏", () -> sidebarTaskbar(handle));
        JButton volumeUp = controlButton("volume-up.png", "音量加", () -> sidebarKey(handle, AndroidKeycodes.VOLUME_UP));
        JButton volumeDown = controlButton("volume-down.png", "音量减", () -> sidebarKey(handle, AndroidKeycodes.VOLUME_DOWN));
        JButton power = controlButton("power.png", "电源", () -> sidebarKey(handle, AndroidKeycodes.POWER));
        JToggleButton display = toggleButton("screen-off.png", "熄屏");
        display.setSelected(true);
        handle.displayButton = display;
        display.addActionListener(e -> sidebarDisplayPower(handle, display.isSelected()));
        JToggleButton awake = toggleButton("stay-awake-on.png", "开启屏幕常亮");
        handle.awakeButton = awake;
        awake.addActionListener(e -> sidebarStayAwake(handle, awake.isSelected()));
        JToggleButton orientation = toggleButton("portrait.png", "切换为横屏");
        handle.orientationButton = orientation;
        orientation.addActionListener(e -> sidebarOrientation(handle));
        for (JComponent c : List.of(close, primary, back, home, recent, taskbar, volumeUp, volumeDown, power, display, awake, orientation, mouse, adapt)) {
            c.setAlignmentX(Component.CENTER_ALIGNMENT);
            bar.add(c);
            bar.add(Box.createVerticalStrut(4));
        }
        return bar;
    }

    private JButton controlButton(String icon, String tooltip, Runnable action) {
        JButton button = new JButton();
        setIcon(button, icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 2, 2, 2));
        button.setPreferredSize(new Dimension(34, 34));
        button.setMinimumSize(new Dimension(34, 34));
        button.setMaximumSize(new Dimension(34, 34));
        button.addActionListener(e -> action.run());
        return button;
    }

    private JToggleButton toggleButton(String icon, String tooltip) {
        JToggleButton button = new JToggleButton();
        setIcon(button, icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(2, 2, 2, 2));
        button.setPreferredSize(new Dimension(34, 34));
        button.setMinimumSize(new Dimension(34, 34));
        button.setMaximumSize(new Dimension(34, 34));
        return button;
    }

    private void setIcon(AbstractButton button, String name) {
        if (name == null) { button.setText("↔"); return; }
        try {
            var url = SwingScrcpyApp.class.getResource("/icons/" + name);
            if (url == null) { button.setText("?"); return; }
            ImageIcon icon = new ImageIcon(url);
            Image scaled = icon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH);
            button.setIcon(new ImageIcon(scaled));
            button.setText("");
        } catch (Exception e) {
            button.setText("?");
        }
    }

    private void updateAdaptButton(JToggleButton button) {
        button.setSelected(adaptScreenClick);
        setIcon(button, adaptScreenClick ? "adapt-click-off.png" : "adapt-click-on.png");
        button.setToolTipText(adaptScreenClick ? "关闭主设备点击坐标适配" : "开启主设备点击坐标适配");
    }

    private void refreshAdaptButtons() {
        sessions.values().forEach(s -> { if (s.adaptButton != null) updateAdaptButton(s.adaptButton); });
    }

    private void setAdaptScreenClick(boolean enabled) {
        adaptScreenClick = enabled;
        refreshAdaptButtons();
    }

    private void setMouseModeFromSidebar(DeviceSession source, boolean uhid) {
        MouseMode mode = uhid ? MouseMode.UHID_RELATIVE : MouseMode.ABSOLUTE_TOUCH;
        for (DeviceSession target : inputTargets(source)) setMouseMode(target, mode);
    }

    private void setMouseMode(DeviceSession handle, MouseMode mode) {
        handle.uhid.releaseInputState();
        handle.mouseMode = mode;
        handle.relativePointerValid = false;
        handle.relativeCaptured = false;
        if (handle.mouseModeButton != null) {
            setIcon(handle.mouseModeButton, mode == MouseMode.UHID_RELATIVE ? "mouse-mode.png" : "touch-mode.png");
            handle.mouseModeButton.setSelected(mode == MouseMode.UHID_RELATIVE);
            handle.mouseModeButton.setToolTipText(mode == MouseMode.UHID_RELATIVE ? "UHID 鼠标（点击切换为触控）" : "触控（点击切换为 UHID 鼠标）");
        }
        if (mode == MouseMode.UHID_RELATIVE) {
            handle.uhid.openMouse();
            handle.view.requestFocusInWindow();
            SwingUtilities.invokeLater(() -> captureRelativeMouse(handle));
        } else {
            handle.uhid.mouse().buttons(0);
            handle.view.setCursor(Cursor.getDefaultCursor());
        }
    }

    private void sidebarKey(DeviceSession source, int keyCode) {
        for (DeviceSession target : inputTargets(source)) pressAndroidKey(target, keyCode);
    }

    private void sidebarTaskbar(DeviceSession source) {
        for (DeviceSession target : inputTargets(source)) target.controller.expandNotificationPanel();
    }

    private void sidebarDisplayPower(DeviceSession source, boolean on) {
        for (DeviceSession target : inputTargets(source)) {
            target.controller.setDisplayPower(on);
            target.displayPower = on;
            if (target.displayButton != null) {
                setIcon(target.displayButton, on ? "screen-off.png" : "screen-on.png");
                target.displayButton.setSelected(on);
                target.displayButton.setToolTipText(on ? "熄屏" : "亮屏");
            }
        }
    }

    /**
     * Set an explicit Android display rotation instead of sending scrcpy's
     * ROTATE_DEVICE toggle. The latter can be reverted immediately when
     * accelerometer rotation is enabled, making the UI appear to do nothing.
     *
     * Confirm the result from the incoming video frame dimensions before
     * changing the button state, so a rejected rotation is never shown as
     * successful.
     */
    private void sidebarOrientation(DeviceSession source) {
        applyOrientation(inputTargets(source), !source.landscape);
    }

    /**
     * One-click global orientation switch. When devices are currently mixed,
     * use the primary device (or the first connected device) as the reference
     * and bring every connected projection to the opposite orientation.
     */
    private void toggleAllOrientations() {
        List<DeviceSession> targets = sessions.values().stream()
                .filter(s -> s.device != null)
                .sorted(Comparator.comparing(s -> s.device.serial()))
                .toList();
        if (targets.isEmpty()) {
            status.setText("当前没有正在投屏的设备");
            return;
        }
        DeviceSession reference = primarySerial == null ? null : sessions.get(primarySerial);
        if (reference == null || !targets.contains(reference)) reference = targets.get(0);
        int width = reference.view.frameWidth();
        int height = reference.view.frameHeight();
        boolean currentLandscape = width > 0 && height > 0 ? width > height : reference.landscape;
        applyOrientation(targets, !currentLandscape);
    }

    private void applyOrientation(List<DeviceSession> targets, boolean landscape) {
        for (DeviceSession target : targets) {
            if (target.orientationPending) continue;
            target.orientationPending = true;
            if (target.orientationButton != null) target.orientationButton.setEnabled(false);

            Thread.startVirtualThread(() -> {
                boolean changed = false;
                String failure = null;
                try {
                    if (target.device == null) {
                        throw new IllegalStateException("设备已断开");
                    }
                    Adb targetAdb = new Adb(System.getProperty("scrcpy.adb", "adb"), target.device.serial());
                    targetAdb.run("shell", "wm", "user-rotation", "lock", landscape ? "1" : "0");
                    changed = awaitFrameOrientation(target, landscape);
                    if (!changed) {
                        failure = "系统已接收方向设置，但视频画面未切换；当前应用可能锁定了屏幕方向";
                    }
                } catch (Exception e) {
                    failure = e.getMessage() == null ? e.toString() : e.getMessage();
                }

                final boolean succeeded = changed;
                final String error = failure;
                final String targetName = target.device == null ? "设备" : target.device.displayName();
                SwingUtilities.invokeLater(() -> {
                    if (succeeded) {
                        target.landscape = landscape;
                        updateOrientationButton(target);
                        resizeTile(target, isPrimary(target));
                        status.setText(targetName + " 已切换为" + (landscape ? "横屏" : "竖屏"));
                    } else {
                        updateOrientationButton(target);
                        status.setText("方向切换失败：" + targetName + " - " + error);
                    }
                    target.orientationPending = false;
                    if (target.orientationButton != null) target.orientationButton.setEnabled(true);
                    secondaryGrid.revalidate();
                    secondaryGrid.repaint();
                    workspace.revalidate();
                    workspace.repaint();
                });
            });
        }
    }

    private boolean awaitFrameOrientation(DeviceSession target, boolean landscape) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            int width = target.view.frameWidth();
            int height = target.view.frameHeight();
            if (width > 0 && height > 0 && (width > height) == landscape) return true;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private void updateOrientationButton(DeviceSession handle) {
        if (handle.orientationButton == null) return;
        setIcon(handle.orientationButton, handle.landscape ? "landscape.png" : "portrait.png");
        handle.orientationButton.setSelected(handle.landscape);
        handle.orientationButton.setToolTipText(handle.landscape ? "切换为竖屏" : "切换为横屏");
    }

    private void sidebarStayAwake(DeviceSession source, boolean enabled) {
        for (DeviceSession target : inputTargets(source)) {
            target.stayAwake = enabled;
            if (target.awakeButton != null) {
                setIcon(target.awakeButton, enabled ? "stay-awake-off.png" : "stay-awake-on.png");
                target.awakeButton.setSelected(enabled);
                target.awakeButton.setToolTipText(enabled ? "关闭屏幕常亮" : "开启屏幕常亮");
            }
            setStayAwake(target, enabled);
        }
    }

    private void setStayAwake(DeviceSession handle, boolean enabled) {
        if (handle.device == null) return;
        String serial = handle.device.serial();
        Thread.startVirtualThread(() -> {
            try {
                new Adb(System.getProperty("scrcpy.adb", "adb"), serial)
                        .run("shell", "settings", "put", "global", "stay_on_while_plugged_in", enabled ? "7" : "0");
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> status.setText("设置屏幕常亮失败：" + t.getMessage()));
            }
        });
    }

    private void pressAndroidKey(DeviceSession handle, int keyCode) {
        handle.controller.key(DeviceController.ACTION_DOWN, keyCode, 0, 0);
        handle.controller.key(DeviceController.ACTION_UP, keyCode, 0, 0);
    }

    private void installControls(DeviceSession handle) {
        SwingVideoSurface view = handle.view;
        view.setFocusable(true);
        view.setCursor(Cursor.getDefaultCursor());
        view.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (handle.mouseMode == MouseMode.UHID_RELATIVE) captureRelativeMouse(handle);
            }
            @Override public void mouseExited(MouseEvent e) { endMouseInput(handle, e); }
            @Override public void mousePressed(MouseEvent e) {
                view.requestFocusInWindow();
                if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                    int button = uhidButton(e.getButton());
                    if (button != 0) for (DeviceSession t : inputTargets(handle)) t.uhid.mouse().button(button, true);
                    handle.relativePointerValid = true;
                    e.consume();
                    return;
                }
                if (e.getButton() != MouseEvent.BUTTON1) return;
                DeviceController.ScreenPosition p = view.position(e.getX(), e.getY());
                if (p != null) {
                    activeMouseInput = handle;
                    activeTouchPositions.put(handle, p);
                    broadcastTouch(handle, DeviceController.ACTION_DOWN, p, 1f, 1);
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                    int button = uhidButton(e.getButton());
                    if (button != 0) for (DeviceSession t : inputTargets(handle)) t.uhid.mouse().button(button, false);
                    e.consume();
                    return;
                }
                if (e.getButton() != MouseEvent.BUTTON1) return;
                DeviceController.ScreenPosition p = view.position(e.getX(), e.getY());
                if (p == null) p = activeTouchPositions.get(handle);
                if (p != null) broadcastTouch(handle, DeviceController.ACTION_UP, p, 0f, 1);
                activeTouchPositions.remove(handle);
                if (activeMouseInput == handle) activeMouseInput = null;
            }
        });
        view.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                if (outsideOrEdge(view, e)) { endMouseInput(handle, e); return; }
                if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                    relativeMove(handle, e);
                } else {
                    DeviceController.ScreenPosition p = view.position(e.getX(), e.getY());
                    if (p != null) broadcastHover(handle, p);
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (outsideOrEdge(view, e)) { endMouseInput(handle, e); return; }
                if (handle.mouseMode == MouseMode.UHID_RELATIVE) { relativeMove(handle, e); return; }
                if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) return;
                DeviceController.ScreenPosition p = view.position(e.getX(), e.getY());
                if (p != null) {
                    activeTouchPositions.put(handle, p);
                    broadcastTouch(handle, DeviceController.ACTION_MOVE, p, 1f, 0);
                }
            }
        });
        view.addMouseWheelListener(e -> {
            if (handle.mouseMode == MouseMode.UHID_RELATIVE) {
                for (DeviceSession t : inputTargets(handle)) t.uhid.mouse().scroll(0, -e.getPreciseWheelRotation());
            } else {
                DeviceController.ScreenPosition p = view.position(e.getX(), e.getY());
                if (p != null) {
                    float v = (float) Math.max(-16, Math.min(16, -e.getPreciseWheelRotation()));
                    broadcastScroll(handle, p, 0, v);
                }
            }
        });
        view.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (handle.mouseMode == MouseMode.UHID_RELATIVE && e.getKeyCode() == KeyEvent.VK_ESCAPE
                        && (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                    releaseRelativeMouseCapture(handle);
                    e.consume(); return;
                }
                int usage = hidUsage(e.getKeyCode());
                if (usage < 0) return;
                int modifiers = hidModifiers(e);
                for (DeviceSession t : inputTargets(handle)) t.uhid.keyboard().key(usage, true, modifiers);
                e.consume();
            }
            @Override public void keyReleased(KeyEvent e) {
                int usage = hidUsage(e.getKeyCode());
                if (usage < 0) return;
                int modifiers = hidModifiers(e);
                for (DeviceSession t : inputTargets(handle)) t.uhid.keyboard().key(usage, false, modifiers);
                e.consume();
            }
        });
    }

    private static int uhidButton(int button) {
        return switch (button) {
            case MouseEvent.BUTTON1 -> UhidMouse.BUTTON_LEFT;
            case MouseEvent.BUTTON3 -> UhidMouse.BUTTON_RIGHT;
            case MouseEvent.BUTTON2 -> UhidMouse.BUTTON_MIDDLE;
            case 4 -> UhidMouse.BUTTON_BACK;
            case 5 -> UhidMouse.BUTTON_FORWARD;
            default -> 0;
        };
    }

    private static int hidModifiers(KeyEvent e) {
        int value = 0;
        if (e.isControlDown()) value |= 0x01;
        if (e.isShiftDown()) value |= 0x02;
        if (e.isAltDown()) value |= 0x04;
        if (e.isMetaDown()) value |= 0x08;
        return value;
    }

    private static int hidUsage(int key) {
        if (key >= KeyEvent.VK_A && key <= KeyEvent.VK_Z) return 0x04 + key - KeyEvent.VK_A;
        if (key >= KeyEvent.VK_1 && key <= KeyEvent.VK_9) return 0x1e + key - KeyEvent.VK_1;
        if (key == KeyEvent.VK_0) return 0x27;
        if (key >= KeyEvent.VK_F1 && key <= KeyEvent.VK_F12) return 0x3a + key - KeyEvent.VK_F1;
        return switch (key) {
            case KeyEvent.VK_ENTER -> 0x28; case KeyEvent.VK_ESCAPE -> 0x29; case KeyEvent.VK_BACK_SPACE -> 0x2a;
            case KeyEvent.VK_TAB -> 0x2b; case KeyEvent.VK_SPACE -> 0x2c; case KeyEvent.VK_MINUS -> 0x2d;
            case KeyEvent.VK_EQUALS -> 0x2e; case KeyEvent.VK_OPEN_BRACKET -> 0x2f; case KeyEvent.VK_CLOSE_BRACKET -> 0x30;
            case KeyEvent.VK_BACK_SLASH -> 0x31; case KeyEvent.VK_SEMICOLON -> 0x33; case KeyEvent.VK_QUOTE -> 0x34;
            case KeyEvent.VK_BACK_QUOTE -> 0x35; case KeyEvent.VK_COMMA -> 0x36; case KeyEvent.VK_PERIOD -> 0x37;
            case KeyEvent.VK_SLASH -> 0x38; case KeyEvent.VK_CAPS_LOCK -> 0x39; case KeyEvent.VK_PRINTSCREEN -> 0x46;
            case KeyEvent.VK_SCROLL_LOCK -> 0x47; case KeyEvent.VK_PAUSE -> 0x48; case KeyEvent.VK_INSERT -> 0x49;
            case KeyEvent.VK_HOME -> 0x4a; case KeyEvent.VK_PAGE_UP -> 0x4b; case KeyEvent.VK_DELETE -> 0x4c;
            case KeyEvent.VK_END -> 0x4d; case KeyEvent.VK_PAGE_DOWN -> 0x4e; case KeyEvent.VK_RIGHT -> 0x4f;
            case KeyEvent.VK_LEFT -> 0x50; case KeyEvent.VK_DOWN -> 0x51; case KeyEvent.VK_UP -> 0x52;
            case KeyEvent.VK_NUM_LOCK -> 0x53; case KeyEvent.VK_DIVIDE -> 0x54; case KeyEvent.VK_MULTIPLY -> 0x55;
            case KeyEvent.VK_SUBTRACT -> 0x56; case KeyEvent.VK_ADD -> 0x57; case KeyEvent.VK_NUMPAD1 -> 0x59;
            case KeyEvent.VK_NUMPAD2 -> 0x5a; case KeyEvent.VK_NUMPAD3 -> 0x5b; case KeyEvent.VK_NUMPAD4 -> 0x5c;
            case KeyEvent.VK_NUMPAD5 -> 0x5d; case KeyEvent.VK_NUMPAD6 -> 0x5e; case KeyEvent.VK_NUMPAD7 -> 0x5f;
            case KeyEvent.VK_NUMPAD8 -> 0x60; case KeyEvent.VK_NUMPAD9 -> 0x61; case KeyEvent.VK_NUMPAD0 -> 0x62;
            case KeyEvent.VK_DECIMAL -> 0x63;
            default -> -1;
        };
    }

    private boolean outsideOrEdge(SwingVideoSurface view, MouseEvent e) {
        // Use the visible phone-image bounds. The Swing component itself may
        // contain letterbox space, especially in the enlarged primary layout.
        return view.isAtImageEdge(e.getX(), e.getY(), 2);
    }

    private boolean containsScreenPoint(SwingVideoSurface view, int sx, int sy) {
        try {
            Point p = view.getLocationOnScreen();
            return sx > p.x && sy > p.y && sx < p.x + view.getWidth() && sy < p.y + view.getHeight()
                    && view.position(sx - p.x, sy - p.y) != null;
        } catch (IllegalComponentStateException e) { return false; }
    }

    private void endMouseInput(DeviceSession handle, MouseEvent e) {
        if (handle.recentering) return;
        for (DeviceSession target : inputTargets(handle)) {
            target.session.clearPendingControlEvents();
            try { target.uhid.mouse().clearTransientState(); } catch (Throwable ignored) {}
        }
        if (handle.mouseMode == MouseMode.ABSOLUTE_TOUCH
                && ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0 || activeMouseInput == handle)) {
            DeviceController.ScreenPosition release = activeTouchPositions.remove(handle);
            if (release == null) release = handle.view.position(
                    Math.max(0, Math.min(handle.view.getWidth() - 1, e.getX())),
                    Math.max(0, Math.min(handle.view.getHeight() - 1, e.getY())));
            if (release != null) broadcastTouch(handle, DeviceController.ACTION_UP, release, 0f, 1);
        }
        handle.relativePointerValid = false;
        handle.recentering = false;
        if (activeMouseInput == handle) activeMouseInput = null;
        activeTouchPositions.remove(handle);
    }

    private void relativeMove(DeviceSession handle, MouseEvent e) {
        if (handle.recentering || !handle.relativeCaptured || mouseRobot == null) return;
        Point location;
        try { location = handle.view.getLocationOnScreen(); } catch (IllegalComponentStateException ex) { return; }
        int cx = location.x + handle.view.getWidth() / 2;
        int cy = location.y + handle.view.getHeight() / 2;
        double dx = e.getXOnScreen() - cx;
        double dy = e.getYOnScreen() - cy;
        if (dx == 0 && dy == 0) return;
        for (DeviceSession target : inputTargets(handle)) target.uhid.mouse().move(dx, dy);
        recenterRelativePointer(handle);
    }

    private void captureRelativeMouse(DeviceSession handle) {
        if (mouseRobot == null || handle.mouseMode != MouseMode.UHID_RELATIVE || !handle.view.isShowing()
                || handle.relativeCaptured) return;
        recenterRelativePointer(handle);
        handle.relativeCaptured = true;
    }

    private void recenterRelativePointer(DeviceSession handle) {
        if (mouseRobot == null || !handle.view.isShowing()) return;
        Point location = handle.view.getLocationOnScreen();
        handle.recentering = true;
        handle.relativePointerValid = true;
        mouseRobot.mouseMove(location.x + handle.view.getWidth() / 2, location.y + handle.view.getHeight() / 2);
        handle.recentering = false;
    }

    private void releaseRelativeMouseCapture(DeviceSession handle) {
        handle.relativeCaptured = false; handle.relativePointerValid = false; handle.recentering = false;
        try { handle.uhid.mouse().clearTransientState(); } catch (Throwable ignored) {}
        handle.view.setCursor(Cursor.getDefaultCursor());
    }

    private boolean isPrimary(DeviceSession handle) {
        return primarySerial != null && handle.device != null && primarySerial.equals(handle.device.serial());
    }

    private List<DeviceSession> inputTargets(DeviceSession source) {
        return isPrimary(source) ? new ArrayList<>(sessions.values()) : List.of(source);
    }

    private void broadcastKeyboard(DeviceSession source, int usage, boolean down, int modifiers) {
        for (DeviceSession target : inputTargets(source)) target.uhid.keyboard().key(usage, down, modifiers);
    }

    private void broadcastMouseButton(DeviceSession source, int button, boolean down) {
        for (DeviceSession target : inputTargets(source)) target.uhid.mouse().button(button, down);
    }

    private void broadcastHover(DeviceSession source, DeviceController.ScreenPosition position) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = mapAbsolutePosition(source, target, position);
            if (p != null) target.controller.hover(p);
        }
    }

    private void broadcastTouch(DeviceSession source, int action, DeviceController.ScreenPosition position, float pressure, int actionButton) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = mapAbsolutePosition(source, target, position);
            if (p != null) target.controller.touch(action, DeviceController.POINTER_ID_MOUSE, p, pressure, actionButton,
                    action == DeviceController.ACTION_UP ? 0 : 1);
        }
    }

    private void broadcastScroll(DeviceSession source, DeviceController.ScreenPosition position, float h, float v) {
        for (DeviceSession target : inputTargets(source)) {
            DeviceController.ScreenPosition p = scalePosition(position, target.view);
            if (p != null) target.controller.scroll(p, h, v, 0);
        }
    }

    private DeviceController.ScreenPosition mapAbsolutePosition(DeviceSession source, DeviceSession target, DeviceController.ScreenPosition p) {
        if (source == target) return p;
        if (adaptScreenClick) return scalePosition(p, target.view);
        int w = target.view.frameWidth(), h = target.view.frameHeight();
        if (w <= 0 || h <= 0) return null;
        return new DeviceController.ScreenPosition(Math.min(w - 1, p.x()), Math.min(h - 1, p.y()), w, h);
    }

    private static DeviceController.ScreenPosition scalePosition(DeviceController.ScreenPosition p, SwingVideoSurface target) {
        int w = target.frameWidth(), h = target.frameHeight();
        if (w <= 0 || h <= 0) return null;
        int x = Math.min(w - 1, Math.max(0, (int) Math.round((p.x() / (double) Math.max(1, p.screenWidth() - 1)) * (w - 1))));
        int y = Math.min(h - 1, Math.max(0, (int) Math.round((p.y() / (double) Math.max(1, p.screenHeight() - 1)) * (h - 1))));
        return new DeviceController.ScreenPosition(x, y, w, h);
    }

    private void releaseAllTransientInput() {
        for (DeviceSession s : sessions.values()) {
            try { s.uhid.releaseInputState(); } catch (Throwable ignored) {}
            s.relativePointerValid = false; s.recentering = false;
        }
        activeMouseInput = null;
        activeTouchPositions.clear();
    }

    private void togglePrimary(DeviceSession handle) {
        if (isPrimary(handle)) clearPrimary();
        else setPrimary(handle);
    }

    private void setPrimary(DeviceSession handle) {
        if (handle.device == null || isPrimary(handle)) return;
        releaseAllTransientInput();
        DeviceSession old = primarySerial == null ? null : sessions.get(primarySerial);
        if (old != null) {
            old.primary = false;
            detachTile(old.tile);
            int index = old.secondaryIndex;
            secondaryGrid.add(old.tile, Math.max(0, Math.min(index < 0 ? secondaryGrid.getComponentCount() : index, secondaryGrid.getComponentCount())));
            old.secondaryIndex = -1;
            resizeTile(old, false);
        }
        primarySerial = handle.device.serial();
        desiredPrimarySerial = primarySerial;
        handle.primary = true;
        if (handle.tile != null) {
            handle.secondaryIndex = indexOfComponent(secondaryGrid, handle.tile);
            // Remove from its actual parent, not just the expected container.
            // AWT reparenting can otherwise leave stale layout state during a
            // rapid sequence of primary-device switches.
            detachTile(handle.tile);
        }
        // Clear the old host completely before installing the new device tile.
        // Each DeviceSession owns its own SwingVideoSurface and frame buffer.
        primaryHost.removeAll();
        primaryHost.revalidate();
        primaryHost.repaint();
        primaryHost.add(handle.tile, BorderLayout.CENTER);
        primaryHost.setOpaque(true);
        primaryHost.setBackground(Color.WHITE);
        primaryHost.setVisible(true);
        resizeTile(handle, true);
        primaryHost.setPreferredSize(new Dimension(handle.tile.getPreferredSize().width, 0));
        if (primaryHost.getParent() != workspace) workspace.add(primaryHost, BorderLayout.WEST);
        workspace.setComponentZOrder(primaryHost, 0);
        primaryHost.revalidate();
        primaryHost.repaint();
        refreshPrimaryUi();
        workspace.revalidate(); workspace.repaint();
    }

    private void clearPrimary() {
        releaseAllTransientInput();
        desiredPrimarySerial = null;
        DeviceSession old = primarySerial == null ? null : sessions.get(primarySerial);
        primarySerial = null;
        if (old != null && old.tile != null) {
            detachTile(old.tile);
            int index = old.secondaryIndex;
            secondaryGrid.add(old.tile, Math.max(0, Math.min(index < 0 ? secondaryGrid.getComponentCount() : index, secondaryGrid.getComponentCount())));
            old.secondaryIndex = -1;
            old.primary = false;
            resizeTile(old, false);
        }
        primaryHost.removeAll();
        primaryHost.setVisible(false);
        primaryHost.setPreferredSize(new Dimension(0, 0));
        workspace.remove(primaryHost);
        refreshPrimaryUi();
        workspace.revalidate(); workspace.repaint();
    }

    private static void detachTile(Component tile) {
        if (tile == null) return;
        Container parent = tile.getParent();
        if (parent != null) {
            parent.remove(tile);
            parent.revalidate();
            parent.repaint();
        }
    }

    private static int indexOfComponent(Container parent, Component child) {
        Component[] components = parent.getComponents();
        for (int i = 0; i < components.length; i++) if (components[i] == child) return i;
        return -1;
    }

    private void resizeTile(DeviceSession handle, boolean primary) {
        if (handle.tile == null) return;
        int availableWidth = Math.max(1, workspace.getWidth() - 24);
        int maxW;
        int maxH;
        if (primary) {
            // Calculate the portrait-sized display bounds first. In landscape,
            // swap those bounds instead of fitting a wide frame into the narrow
            // portrait width (which would make the landscape preview tiny).
            boolean hasSecondary = secondaryGrid.getComponentCount() > 0;
            int portraitWidth = Math.max(1,
                    (int) (availableWidth * (hasSecondary ? 0.58 : 1.0)) - NAV_WIDTH);
            int portraitHeight = Math.max(1,
                    workspace.getHeight() > 30 ? workspace.getHeight() - 30 : 940);
            maxW = handle.landscape ? portraitHeight : portraitWidth;
            maxH = handle.landscape ? portraitWidth : portraitHeight;
        } else {
            // Secondary tiles use the same portrait footprint in both
            // orientations; landscape simply swaps width and height.
            maxW = handle.landscape ? TILE_HEIGHT : TILE_WIDTH;
            maxH = handle.landscape ? TILE_WIDTH : TILE_HEIGHT;
        }
        // Keep every tile of the same orientation exactly the same size.
        // The decoded frame is fitted inside this fixed viewport by the
        // renderer; it must not determine the outer tile dimensions.
        int vw = maxW;
        int vh = maxH;
        if (!handle.landscape) {
            handle.portraitViewportWidth = vw;
            handle.portraitViewportHeight = vh;
        }
        // The tile's video viewport must have exactly the calculated video
        // dimensions. A fixed "+30" header allowance is not reliable: the
        // title's actual preferred height changes with font metrics and DPI.
        // Any extra height is then given to the BorderLayout.CENTER viewport,
        // making the native D3D11 swap chain taller than the phone frame and
        // producing black letterbox strips at the top and bottom.
        BorderLayout tileLayout = (BorderLayout) handle.tile.getLayout();
        Component title = tileLayout.getLayoutComponent(BorderLayout.NORTH);
        int titleHeight = title == null ? 0 : title.getPreferredSize().height;
        Insets tileInsets = handle.tile.getInsets();

        Component videoAndNavigation = tileLayout.getLayoutComponent(BorderLayout.CENTER);
        int navigationWidth = NAV_WIDTH;
        if (videoAndNavigation instanceof Container container
                && container.getLayout() instanceof BorderLayout contentLayout) {
            Component navigation = contentLayout.getLayoutComponent(BorderLayout.EAST);
            if (navigation != null) {
                navigationWidth = navigation.getPreferredSize().width;
            }
        }

        Dimension preferred = new Dimension(
                vw + navigationWidth + tileInsets.left + tileInsets.right,
                vh + titleHeight + tileInsets.top + tileInsets.bottom);
        handle.tile.setPreferredSize(preferred);
        handle.tile.setMinimumSize(preferred);
        handle.viewport.setPreferredSize(new Dimension(vw, vh));
        handle.view.setPreferredSize(new Dimension(vw, vh));
        handle.tile.revalidate();
        if (primary && Objects.equals(primarySerial, handle.device == null ? null : handle.device.serial())) {
            primaryHost.setPreferredSize(new Dimension(preferred.width, 0));
            primaryHost.revalidate();
            workspace.revalidate();
        }
    }

    private void refreshPrimaryUi() {
        DeviceSession primary = primarySerial == null ? null : sessions.get(primarySerial);
        primaryStatus.setText("主设备：" + (primary == null || primary.device == null ? "无" : primary.device.displayName()));
        for (DeviceSession s : sessions.values()) {
            if (s.primaryButton != null) {
                boolean selected = isPrimary(s);
                s.primaryButton.setSelected(selected);
                setIcon(s.primaryButton, selected ? "unset-primary.png" : "set-primary.png");
                s.primaryButton.setToolTipText(selected ? "取消主屏幕" : "设为主屏幕");
            }
        }
        refreshAdaptButtons();
    }

    private void disconnectSelected() {
        checkedDevices.forEach(this::disconnect);
    }

    private void disconnect(String serial) {
        desiredConnections.remove(serial);
        reconnecting.remove(serial);
        reconnectStates.remove(serial);
        if (Objects.equals(desiredPrimarySerial, serial)) desiredPrimarySerial = null;
        disconnectSession(serial);
    }

    private void disconnectForReconnect(String serial) {
        reconnecting.remove(serial);
        reconnectStates.computeIfAbsent(serial, k -> new ReconnectState(0, System.currentTimeMillis() + RECONNECT_INTERVAL_MILLIS));
        if (Objects.equals(primarySerial, serial)) desiredPrimarySerial = serial;
        disconnectSession(serial);
    }

    private void disconnectSession(String serial) {
        DeviceSession handle = sessions.remove(serial);
        if (handle == null) return;
        if (handle.tile != null) {
            primaryHost.remove(handle.tile);
            secondaryGrid.remove(handle.tile);
        }
        try { handle.uhid.releaseInputState(); } catch (Throwable ignored) {}
        if (isPrimary(handle)) {
            primarySerial = null;
            primaryHost.setVisible(false);
            workspace.remove(primaryHost);
        }
        Thread.startVirtualThread(() -> {
            try { handle.close(); }
            catch (Throwable t) { System.err.println("[scrcpy-swing] disconnect failed: " + t); }
        });
        status.setText("投屏中 " + sessions.size() + " 台设备");
        refreshPrimaryUi();
        refreshDeviceRows();
        workspace.revalidate(); workspace.repaint();
    }

    private void refreshDeviceRows() {
        if (deviceDialog != null && deviceDialog.isShowing()) {
            JPanel list = (JPanel) ((JScrollPane) deviceDialog.getContentPane().getComponent(0)).getViewport().getView();
            rebuildDeviceRows(list);
        }
    }

    private void shutdown() {
        if (closing) return;
        closing = true;
        background.shutdownNow();
        releaseAllTransientInput();
        for (DeviceSession handle : new ArrayList<>(sessions.values())) {
            try { handle.close(); } catch (Throwable t) { t.printStackTrace(System.err); }
        }
        sessions.clear();
        desiredConnections.clear();
        reconnecting.clear();
        reconnectStates.clear();
        frame.dispose();
    }

    private static int indexClamp(int index, int size) {
        return Math.max(0, Math.min(index < 0 ? size : index, size));
    }

    private static final class DeviceSession implements AutoCloseable {
        final ScrcpySession session;
        final JavaSoundPlayer audioPlayer;
        final FfmpegVideoDecoder decoder;
        final SwingHardwareVideoRenderer nativeRenderer;
        final SwingVideoSurface view;
        final DeviceController controller;
        final UhidController uhid;
        final ClipboardController clipboard;
        AdbDevice device;
        JPanel tile;
        JPanel viewport;
        JToggleButton primaryButton, adaptButton, mouseModeButton, displayButton, awakeButton, orientationButton;
        boolean primary, stayAwake, displayPower = true, landscape, orientationPending;
        boolean relativePointerValid, recentering, relativeCaptured;
        int secondaryIndex = -1;
        int portraitViewportWidth, portraitViewportHeight;
        MouseMode mouseMode = MouseMode.ABSOLUTE_TOUCH;

        DeviceSession(ScrcpySession session, JavaSoundPlayer audioPlayer, FfmpegVideoDecoder decoder,
                      SwingHardwareVideoRenderer nativeRenderer, SwingVideoSurface view,
                      DeviceController controller, UhidController uhid, ClipboardController clipboard) {
            this.session = session; this.audioPlayer = audioPlayer; this.decoder = decoder;
            this.nativeRenderer = nativeRenderer;
            this.view = view; this.controller = controller; this.uhid = uhid; this.clipboard = clipboard;
        }

        @Override public void close() {
            Throwable first = null;
            try { uhid.close(); } catch (Throwable t) { first = t; }
            try { if (decoder != null) decoder.close(); } catch (Throwable t) { if (first == null) first = t; else first.addSuppressed(t); }
            try { if (nativeRenderer != null) nativeRenderer.close(); } catch (Throwable t) { if (first == null) first = t; else first.addSuppressed(t); }
            try { session.close(); } catch (Throwable t) { if (first == null) first = t; else first.addSuppressed(t); }
            try { view.close(); } catch (Throwable t) { if (first == null) first = t; else first.addSuppressed(t); }
            try { audioPlayer.close(); } catch (Throwable t) { if (first == null) first = t; else first.addSuppressed(t); }
            if (first != null) throw new IllegalStateException("Failed to close device session", first);
        }
    }

    private record ReconnectState(int attempts, long nextAttemptAtMillis) {}
    private record ConnectionSettings(boolean video, boolean audio, boolean control,
                                      String videoCodec, String videoEncoder, String videoCodecOptions,
                                      String videoDecoder, String audioCodec, long videoBitRate, long audioBitRate,
                                      int maxSize, String maxFps, boolean showTouches, boolean stayAwake,
                                      boolean turnScreenOff, String crop, String startApp) {
        ScrcpyOptions toOptions(String serial, ScrcpyOptions base) {
            return new ScrcpyOptions(serial, base.scid(), base.localPort(), video, audio, control,
                    videoCodec, videoEncoder, videoCodecOptions, videoDecoder, audioCodec,
                    videoBitRate, audioBitRate, maxSize, maxFps, showTouches, stayAwake,
                    turnScreenOff, crop, startApp);
        }
    }
    private enum MouseMode { ABSOLUTE_TOUCH, UHID_RELATIVE }
}
