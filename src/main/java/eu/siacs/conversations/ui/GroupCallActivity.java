package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import eu.siacs.conversations.xmpp.manager.MujiManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;

/**
 * Conversations+: the group call screen (XEP-0272 Muji). One tile per participant (their video, or
 * their name), our own camera in the first tile, and Join / Mute / Camera / Speaker / Leave.
 */
public class GroupCallActivity extends XmppActivity {

    private static final Executor SINK_REMOVER = Executors.newSingleThreadExecutor();
    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_ROOM = "room";
    private static final String EXTRA_JOIN = "join";
    private static final String EXTRA_VIDEO = "video";
    private static final int REQUEST_PERMISSIONS = 0x4d55;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Tile> tiles = new ArrayList<>();
    private String tileSignature = "";

    private Jid room;
    private String accountJid;
    private boolean pendingVideo = false;
    private boolean autoJoin = false;

    private TextView title;
    private TextView status;
    private GridLayout grid;
    private LinearLayout controls;

    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    refresh();
                    handler.postDelayed(this, 1000);
                }
            };

    public static Intent intent(
            final Context context, final Account account, final Jid room, final boolean join) {
        return intent(context, account, room, join, false);
    }

    public static Intent intent(
            final Context context,
            final Account account,
            final Jid room,
            final boolean join,
            final boolean video) {
        final Intent intent = new Intent(context, GroupCallActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account.getJid().asBareJid().toString());
        intent.putExtra(EXTRA_ROOM, room.asBareJid().toString());
        intent.putExtra(EXTRA_JOIN, join);
        intent.putExtra(EXTRA_VIDEO, video);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        readIntent(getIntent());

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 18));
        root.setPadding(dp(12), dp(28), dp(12), dp(16));

        title = text(20, Color.WHITE);
        root.addView(title);
        status = text(14, Color.LTGRAY);
        root.addView(status);

        grid = new GridLayout(this);
        grid.setColumnCount(2);
        final LinearLayout.LayoutParams gridParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridParams.topMargin = dp(12);
        root.addView(grid, gridParams);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        root.addView(
                controls,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        readIntent(intent);
        if (xmppConnectionServiceBound) {
            onBackendConnected();
        }
    }

    private void readIntent(final Intent intent) {
        this.accountJid = intent.getStringExtra(EXTRA_ACCOUNT);
        this.room = Jid.of(Objects.requireNonNull(intent.getStringExtra(EXTRA_ROOM)));
        this.autoJoin = intent.getBooleanExtra(EXTRA_JOIN, false);
        this.pendingVideo = intent.getBooleanExtra(EXTRA_VIDEO, false);
    }

    @Override
    protected void onBackendConnected() {
        final Conversation conversation = conversation();
        if (conversation == null) {
            finish();
            return;
        }
        title.setText(conversation.getName());
        if (autoJoin) {
            autoJoin = false;
            join(pendingVideo);
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void refreshUiReal() {
        refresh();
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(tick);
        releaseTiles();
        tileSignature = "";
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (xmppConnectionServiceBound) {
            handler.post(tick);
        }
    }

    // ------------------------------------------------------------------------------ actions

    private void join(final boolean video) {
        final List<String> missing = new ArrayList<>();
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (video && !granted(Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (!missing.isEmpty()) {
            pendingVideo = video;
            ActivityCompat.requestPermissions(
                    this, missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        final Conversation conversation = conversation();
        final MujiManager muji = muji();
        if (conversation == null || muji == null) {
            return;
        }
        if (!muji.join(conversation, video)) {
            Toast.makeText(this, R.string.plus_group_call_not_possible, Toast.LENGTH_LONG).show();
        }
        tileSignature = "";
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            join(pendingVideo && granted(Manifest.permission.CAMERA));
        } else {
            Toast.makeText(this, R.string.no_microphone_permission, Toast.LENGTH_LONG).show();
        }
    }

    private boolean granted(final String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void leave() {
        releaseTiles(); // detach the views while the legs are still alive
        final MujiManager muji = muji();
        if (muji != null) {
            muji.leave(room);
        }
        finish();
    }

    // ------------------------------------------------------------------------------- drawing

    private void refresh() {
        final MujiManager muji = muji();
        if (muji == null || title == null) {
            return;
        }
        final boolean inCall = muji.isInCall(room);
        final List<MujiManager.Participant> participants = muji.getParticipants(room);
        int connected = 0;
        for (final var participant : participants) {
            if (participant.leg() != null
                    && participant.leg().getEndUserState() == RtpEndUserState.CONNECTED) {
                connected++;
            }
        }
        if (!inCall) {
            status.setText(
                    muji.hasRunningCall(room)
                            ? getString(R.string.plus_group_call_running)
                            : getString(R.string.plus_group_call_start_hint));
        } else if (participants.isEmpty()) {
            status.setText(R.string.plus_group_call_waiting);
        } else {
            status.setText(
                    getResources()
                            .getQuantityString(
                                    R.plurals.plus_group_call_connected,
                                    participants.size(),
                                    connected,
                                    participants.size()));
        }
        updateTiles(muji, inCall, participants);
        updateControls(muji, inCall);
    }

    private void updateTiles(
            final MujiManager muji,
            final boolean inCall,
            final List<MujiManager.Participant> participants) {
        final StringBuilder signature = new StringBuilder(inCall ? "in;" : "out;");
        for (final var participant : participants) {
            final JingleRtpConnection leg = participant.leg();
            final boolean video = leg != null && leg.getRemoteVideoTrack().isPresent();
            signature
                    .append(participant.nick())
                    .append('|')
                    .append(leg == null ? "-" : leg.getId().sessionId)
                    .append('|')
                    .append(video)
                    .append(';');
        }
        final boolean ourVideo = inCall && muji.isVideo(room) && localTrack(participants) != null;
        signature.append("me:").append(ourVideo);
        if (signature.toString().equals(tileSignature)) {
            for (final Tile tile : tiles) {
                tile.updateLabel();
            }
            return;
        }
        tileSignature = signature.toString();
        releaseTiles();
        grid.removeAllViews();
        final int count = participants.size() + (inCall ? 1 : 0);
        grid.setColumnCount(count <= 1 ? 1 : 2);
        if (inCall) {
            final VideoTrack local = ourVideo ? localTrack(participants) : null;
            addTile(getString(R.string.plus_you), null, local, eglOf(participants), true);
        }
        for (final var participant : participants) {
            final JingleRtpConnection leg = participant.leg();
            final VideoTrack remote =
                    leg == null ? null : leg.getRemoteVideoTrack().orNull();
            addTile(participant.nick(), leg, remote, leg == null ? null : leg.getEglBaseContext(), false);
        }
    }

    private VideoTrack localTrack(final List<MujiManager.Participant> participants) {
        for (final var participant : participants) {
            final JingleRtpConnection leg = participant.leg();
            if (leg != null) {
                try {
                    final var track = leg.getLocalVideoTrack();
                    if (track.isPresent()) {
                        return track.get();
                    }
                } catch (final RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private org.webrtc.EglBase.Context eglOf(final List<MujiManager.Participant> participants) {
        for (final var participant : participants) {
            final JingleRtpConnection leg = participant.leg();
            if (leg != null) {
                try {
                    return leg.getEglBaseContext();
                } catch (final RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private void addTile(
            final String name,
            final JingleRtpConnection leg,
            final VideoTrack track,
            final org.webrtc.EglBase.Context egl,
            final boolean mirror) {
        final FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(40, 40, 40));
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f));
        params.width = 0;
        params.height = 0;
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        final Tile tile = new Tile(name, leg);
        if (track != null && egl != null) {
            final SurfaceViewRenderer renderer = new SurfaceViewRenderer(this);
            try {
                renderer.init(egl, null);
                renderer.setMirror(mirror);
                renderer.setZOrderMediaOverlay(mirror);
                track.addSink(renderer);
                tile.renderer = renderer;
                tile.track = track;
                frame.addView(
                        renderer,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (final RuntimeException e) {
                renderer.release();
            }
        } else {
            final TextView initial = text(48, Color.WHITE);
            initial.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
            initial.setGravity(Gravity.CENTER);
            frame.addView(
                    initial,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        tile.label = text(13, Color.WHITE);
        tile.label.setBackgroundColor(Color.argb(140, 0, 0, 0));
        tile.label.setPadding(dp(6), dp(2), dp(6), dp(2));
        final FrameLayout.LayoutParams labelParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.START);
        labelParams.setMargins(dp(6), dp(6), dp(6), dp(6));
        frame.addView(tile.label, labelParams);
        tile.updateLabel();
        tiles.add(tile);
        grid.addView(frame, params);
    }

    private void releaseTiles() {
        for (final Tile tile : tiles) {
            if (tile.renderer != null) {
                // a released renderer drops late frames. removeSink blocks on WebRTC's worker
                // thread, which hangs while a leg's peer connection is closing, so keep it off
                // the UI thread
                tile.renderer.release();
                final VideoTrack track = tile.track;
                final SurfaceViewRenderer renderer = tile.renderer;
                SINK_REMOVER.execute(
                        () -> {
                            try {
                                track.removeSink(renderer);
                            } catch (final RuntimeException ignored) {
                                // the track was already disposed with its leg
                            }
                        });
            }
        }
        tiles.clear();
    }

    private void updateControls(final MujiManager muji, final boolean inCall) {
        controls.removeAllViews();
        if (!inCall) {
            button(R.string.plus_join_voice, v -> join(false));
            if (isCameraFeatureAvailable()) {
                button(R.string.plus_join_video, v -> join(true));
            }
            return;
        }
        final boolean mic = muji.isMicrophoneEnabled(room);
        button(mic ? R.string.plus_mute : R.string.plus_unmute, v -> {
            muji.setMicrophoneEnabled(room, !mic);
            refresh();
        });
        if (muji.isVideo(room)) {
            final boolean camera = muji.isCameraEnabled(room);
            button(camera ? R.string.plus_camera_off : R.string.plus_camera_on, v -> {
                muji.setCameraEnabled(room, !camera);
                refresh();
            });
        }
        final boolean speaker =
                muji.getAudioDevice(room) == CallIntegration.AudioDevice.SPEAKER_PHONE;
        button(speaker ? R.string.plus_earpiece : R.string.plus_speaker, v -> {
            muji.setAudioDevice(
                    room,
                    speaker
                            ? CallIntegration.AudioDevice.EARPIECE
                            : CallIntegration.AudioDevice.SPEAKER_PHONE);
            handler.postDelayed(this::refresh, 300);
        });
        final Button leave = button(R.string.plus_leave, v -> leave());
        leave.setBackgroundColor(Color.rgb(198, 40, 40));
        leave.setTextColor(Color.WHITE);
    }

    private Button button(final int label, final View.OnClickListener listener) {
        final Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), dp(8), dp(3), 0);
        controls.addView(button, params);
        return button;
    }

    // ------------------------------------------------------------------------------- helpers

    private Conversation conversation() {
        if (xmppConnectionService == null || accountJid == null) {
            return null;
        }
        final Account account = xmppConnectionService.findAccountByJid(Jid.of(accountJid));
        return account == null ? null : xmppConnectionService.find(account, room);
    }

    private MujiManager muji() {
        final Conversation conversation = conversation();
        if (conversation == null) {
            return null;
        }
        return conversation.getAccount().getXmppConnection().getManager(MujiManager.class);
    }

    private TextView text(final int sp, final int color) {
        final TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(final int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private final class Tile {
        private final String name;
        private final JingleRtpConnection leg;
        private SurfaceViewRenderer renderer;
        private VideoTrack track;
        private TextView label;

        private Tile(final String name, final JingleRtpConnection leg) {
            this.name = name;
            this.leg = leg;
        }

        private void updateLabel() {
            if (label == null) {
                return;
            }
            if (leg == null) {
                label.setText(
                        name.equals(getString(R.string.plus_you))
                                ? name
                                : getString(R.string.plus_tile_connecting, name));
                return;
            }
            final RtpEndUserState state = leg.getEndUserState();
            label.setText(
                    state == RtpEndUserState.CONNECTED
                            ? name
                            : getString(R.string.plus_tile_connecting, name));
        }
    }
}
