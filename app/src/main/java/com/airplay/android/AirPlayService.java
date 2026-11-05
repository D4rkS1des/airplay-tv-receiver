package com.airplay.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.airplay.android.server.AirPlayServer;
import com.github.serezhka.jap2lib.AirPlay;
import com.github.serezhka.jap2lib.AirPlayBonjour;

import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the AirPlay receiver in the background, from boot onwards.
 *
 * <p>The receiver stays on the AirPlay list whether or not the app is on screen. Mirroring
 * still needs the Activity's Surface to decode into, so when a sender starts a session while
 * the UI is not visible, the service brings {@link MainActivity} up; frames that arrive before
 * the surface exists are held by {@link VideoHolder} from the last keyframe on. Running as a
 * foreground service keeps the multicast lock (mDNS), the Wi-Fi lock (power save would
 * otherwise stall the streams) and the network threads at foreground priority.
 */
public class AirPlayService extends Service {

    private static final String TAG = "AirPlayService";
    private static final String CHANNEL_ID = "airplay_channel";
    private static final int NOTIFICATION_ID = 1;

    /**
     * Start and stop are serialised on one thread that outlives the service instance, because
     * shutting the Netty groups down takes seconds. Leaving the app and coming straight back
     * would otherwise start a new instance while the old one is still tearing the server down,
     * and the new start would be dropped as a duplicate.
     */
    private static final ExecutorService LIFECYCLE = Executors.newSingleThreadExecutor();
    private static AirPlayServer sServer;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    /** Starts (or re-asserts) the receiver from any context: activity, boot receiver, etc. */
    public static void start(Context context) {
        Intent intent = new Intent(context, AirPlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    private static volatile Context sAppContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        createNotificationChannel();
    }

    /**
     * A sender has begun mirroring: make sure the screen that decodes it is in front. Called on a
     * network thread for every SPS/PPS, so it is a no-op while the UI is already visible.
     */
    static void bringUiForward() {
        Context context = sAppContext;
        if (context == null || MainActivity.isVisible()) return;
        try {
            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_AUTO_LAUNCHED, true);
            context.startActivity(intent);
            Log.i(TAG, "Mirroring started in the background, bringing the UI forward");
        } catch (Exception e) {
            Log.e(TAG, "Could not bring the mirroring screen forward", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String serverName = resolveServiceName(this);
        String deviceId = resolveDeviceId(this);
        startForeground(NOTIFICATION_ID, buildNotification(serverName + " · starting"));

        sWanted = true;
        acquireLocks();
        watchNetwork(serverName, deviceId);
        LIFECYCLE.submit(() -> startServer(serverName, deviceId));
        // Sticky: if the system ever kills the process for memory, it is brought back
        return START_STICKY;
    }

    /**
     * Builds the name broadcast over mDNS from the device's own name, so two receivers on the
     * same network stay apart. A fixed name made jmDNS fall back to "name (2)" as soon as the
     * app ran on a second device.
     *
     * <p>{@code global device_name} is what the user typed in Settings; Android TV boxes that
     * leave it empty usually still carry the Bluetooth name, and the model is the last resort.
     */
    static String resolveServiceName(Context context) {
        String deviceName = Settings.Global.getString(
                context.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = Settings.Secure.getString(context.getContentResolver(), "bluetooth_name");
        }
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = Build.MODEL;
        }
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = context.getString(R.string.airplay_fallback_name);
        }
        return deviceName.trim() + " " + context.getString(R.string.airplay_name_suffix);
    }

    /**
     * A stand-in MAC address, derived so it stays the same across restarts but differs between
     * installs. iOS pairs the {@code _airplay} and {@code _raop} records of one receiver by this
     * id, so the value that used to be hardcoded made two devices look like a single one.
     */
    private static String resolveDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String seed = TextUtils.isEmpty(androidId) ? Build.FINGERPRINT : androidId;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed to be present", e);
        }
        byte[] mac = Arrays.copyOf(digest, 6);
        // Mark it locally administered and unicast, the shape a made-up MAC is meant to have
        mac[0] = (byte) ((mac[0] & 0xFE) | 0x02);
        StringBuilder hex = new StringBuilder(12);
        for (byte b : mac) {
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    /** Runs on {@link #LIFECYCLE}. */
    private void startServer(String serverName, String deviceId) {
        if (sServer != null) {
            Log.i(TAG, "Server already running, ignoring duplicate start");
            return;
        }
        if (!sWanted) return;
        AirPlayServer server = null;
        try {
            // Build the Ed25519 identity keypair off to one side while mDNS comes up and the
            // sender is still finding us, so the ~0.5s it costs the first time is not spent
            // mid-handshake. The generator is shared and guarded, so a connection arriving before
            // this finishes simply waits on the same one-time build rather than starting a second.
            new Thread(AirPlay::prewarmCrypto, "airplay-crypto-prewarm").start();

            Inet4Address address = defaultNetworkAddress();
            if (address == null) {
                throw new IllegalStateException("No default network with an IPv4 address yet");
            }
            AirPlayBonjour.setPreferredAddress(address);
            lastAddress = address.getHostAddress();
            server = new AirPlayServer(serverName, deviceId, 7000, 5000, new VideoCallback());
            server.start();
            sServer = server;
            Log.i(TAG, "AirPlay server started as '" + serverName + "' (id " + deviceId + ")");
            updateNotification(serverName + " · ready");
        } catch (Exception e) {
            // Typically no IPv4 address yet: at boot Wi-Fi comes up after BOOT_COMPLETED. Keep
            // retrying while the service is alive instead of giving up until the app is opened.
            Log.e(TAG, "Failed to start AirPlay server, retrying in " + RETRY_DELAY_MS + "ms", e);
            // Undo a half start (mDNS registered, RTSP port failed) so the retry starts clean
            if (server != null) {
                try { server.stop(); } catch (Exception ignored) { /* best effort */ }
            }
            updateNotification(serverName + " · waiting for network");
            scheduleRetry(serverName, deviceId);
        }
    }

    private static final long RETRY_DELAY_MS = 5000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** True between onStartCommand and onDestroy: the receiver is supposed to be up. */
    private static volatile boolean sWanted;

    private void scheduleRetry(String serverName, String deviceId) {
        MAIN.postDelayed(() -> {
            if (sWanted) LIFECYCLE.submit(() -> startServer(serverName, deviceId));
        }, RETRY_DELAY_MS);
    }

    private ConnectivityManager.NetworkCallback networkCallback;
    /** IPv4 address of the default network last time we looked, to spot real changes. */
    private volatile String lastAddress;

    /** First IPv4 address on these link properties, or null. */
    private static Inet4Address ipv4Of(LinkProperties props) {
        if (props == null) return null;
        for (LinkAddress la : props.getLinkAddresses()) {
            if (la.getAddress() instanceof Inet4Address) return (Inet4Address) la.getAddress();
        }
        return null;
    }

    /** IPv4 address of the system's default (active) network: the one the LAN reaches us on. */
    private Inet4Address defaultNetworkAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network active = cm.getActiveNetwork();
        return active == null ? null : ipv4Of(cm.getLinkProperties(active));
    }

    /**
     * mDNS is bound to one address, so after a Wi-Fi reconnect with a new IP (or a switch
     * between Ethernet and Wi-Fi) the receiver would keep advertising the old one and vanish from
     * the AirPlay list. Restart it whenever the default network's IPv4 address changes.
     */
    private void watchNetwork(String serverName, String deviceId) {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null || networkCallback != null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                Inet4Address ipv4 = ipv4Of(props);
                if (ipv4 == null) return;
                String address = ipv4.getHostAddress();
                String previous = lastAddress;
                lastAddress = address;
                if (previous != null && !previous.equals(address)) {
                    Log.i(TAG, "Network address changed " + previous + " -> " + address
                            + ", restarting the receiver");
                    LIFECYCLE.submit(() -> {
                        stopServer();
                        if (sWanted) startServer(serverName, deviceId);
                    });
                }
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "Could not watch the network", e);
            networkCallback = null;
        }
    }

    /** Runs on {@link #LIFECYCLE}. */
    private static void stopServer() {
        if (sServer == null) return;
        sServer.stop();
        sServer = null;
        Log.i(TAG, "AirPlay server stopped");
    }

    private void acquireLocks() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifi != null) {
            // mDNS is multicast: without this lock the Wi-Fi stack drops the packets
            multicastLock = wifi.createMulticastLock("airplay_mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();

            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airplay_wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:receiver");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        Log.i(TAG, "Locks acquired (multicast/wifi/wake)");
    }

    private void releaseLocks() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        multicastLock = null;
        wifiLock = null;
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sWanted = false;
        MAIN.removeCallbacksAndMessages(null);
        if (networkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            try { if (cm != null) cm.unregisterNetworkCallback(networkCallback); } catch (Exception e) { /* ignore */ }
            networkCallback = null;
        }
        LIFECYCLE.submit(AirPlayService::stopServer);
        releaseLocks();
        Log.i(TAG, "AirPlay service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    private static class VideoCallback implements VideoCallbackInterface {
        @Override
        public void onVideo(byte[] video, long ptsUs) {
            VideoHolder.onVideoData(video, ptsUs);
        }

        @Override
        public void onVideoFormat(int width, int height) {
            Log.i("AirPlayService", "Video format: " + width + "x" + height);
            VideoHolder.onVideoFormat(width, height);
        }

        @Override
        public void onAudio(byte[] audio, long rtpTime) {
            VideoHolder.onAudioData(audio, rtpTime);
        }

        @Override
        public void onAudioFormat(int sampleRate, int channels) {
            Log.i("AirPlayService", "Audio format: " + sampleRate + "Hz " + channels + "ch");
        }

        @Override
        public void onSpsPps(byte[] spsPps) {
            VideoHolder.onSpsPpsData(spsPps);
            bringUiForward();
        }
    }
}
