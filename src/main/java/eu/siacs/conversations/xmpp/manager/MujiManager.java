package eu.siacs.conversations.xmpp.manager;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.services.AppRTCAudioManager;
import eu.siacs.conversations.services.CallIntegration;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.GroupCallActivity;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.jingle.AbstractJingleConnection;
import eu.siacs.conversations.xmpp.jingle.JingleRtpConnection;
import eu.siacs.conversations.xmpp.jingle.Media;
import eu.siacs.conversations.xmpp.jingle.RtpEndUserState;
import im.conversations.android.xmpp.model.muc.user.MucUser;
import im.conversations.android.xmpp.model.muji.Muji;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Presence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Conversations+: XEP-0272 Muji group calls for one account (ported from monocles chat, GPLv3).
 *
 * <p>A group call lives in a private, non-anonymous MUC. Everyone in it announces {@code <muji>} in
 * their room presence; every pair of participants is connected by an ordinary Jingle RTP session
 * (full mesh) tagged with {@code <muji room>}. Of each pair the participant with the greater nick
 * starts the session; the other auto-accepts it (no ringing) once it checked that the caller is a
 * participant whose real JID the room vouched for.
 */
public class MujiManager extends AbstractManager {

    public static final int MAX_PEERS = 8;
    private static final long STUCK_LEG_MILLIS = 12_000L;
    private static final String INVITE_CHANNEL = "group_calls";

    private final XmppConnectionService service;

    /** Rooms whose call we are in, keyed by bare room JID. */
    private final Map<Jid, Conference> conferences = new ConcurrentHashMap<>();

    /** Who announced {@code <muji>}, per room: occupant JID -> peer. Kept while we're not in the call. */
    private final Map<Jid, Map<Jid, Peer>> seen = new ConcurrentHashMap<>();

    /** Rooms for which a "join the group call" notification is showing. */
    private final Set<Jid> invited = ConcurrentHashMap.newKeySet();

    public MujiManager(final XmppConnectionService service, final XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    // ------------------------------------------------------------------------------ public API

    /** Start or join the group call of a joined, private, non-anonymous room. */
    public synchronized boolean join(final Conversation muc, final boolean video) {
        final Jid room = muc.getAddress().asBareJid();
        final MucOptions mucOptions = getManager(MultiUserChatManager.class).getState(room);
        if (mucOptions == null || !mucOptions.online() || mucOptions.getSelf().getFullJid() == null) {
            Log.w(Config.LOGTAG, "muji: not joined to " + room);
            return false;
        }
        if (!mucOptions.isPrivateAndNonAnonymous()) {
            Log.w(Config.LOGTAG, "muji: " + room + " is public or anonymous");
            return false;
        }
        if (conferences.containsKey(room)) {
            return true;
        }
        final Map<Jid, Peer> peers = seen.get(room);
        final boolean joiningRunningCall = peers != null && !peers.isEmpty();
        final Conference conference =
                new Conference(room, mucOptions.getSelf().getFullJid(), video, joiningRunningCall);
        conferences.put(room, conference);
        cancelInvite(room);
        startAudio(conference);
        conference.ready = false;
        announce(muc);
        conference.ready = true;
        announce(muc);
        // XEP-0272: wait for the room to reflect our <muji> before starting sessions (our IQs go
        // directly and could overtake the presence). The reflection normally starts the mesh; this
        // is the safety net.
        JingleManager.schedule(() -> markAnnounced(room), 5, TimeUnit.SECONDS);
        conference.remesh =
                JingleManager.SCHEDULED_EXECUTOR_SERVICE.scheduleWithFixedDelay(
                        () -> {
                            try {
                                remesh(room);
                            } catch (final RuntimeException e) {
                                Log.w(Config.LOGTAG, "muji: remesh failed", e);
                            }
                        },
                        8,
                        8,
                        TimeUnit.SECONDS);
        service.updateConversationUi();
        Log.d(Config.LOGTAG, getAccount().getJid().asBareJid() + ": joined group call in " + room);
        return true;
    }

    public synchronized void leave(final Jid room) {
        final Conference conference = conferences.remove(room.asBareJid());
        if (conference == null) {
            return;
        }
        teardown(conference, true);
        // others are still in the call: don't ring us again for it
        final Map<Jid, Peer> peers = seen.get(room.asBareJid());
        if (peers != null && !peers.isEmpty()) {
            invited.add(room.asBareJid());
        }
    }

    public boolean isInCall(final Jid room) {
        return conferences.containsKey(room.asBareJid());
    }

    public boolean isInAnyCall() {
        return !conferences.isEmpty();
    }

    /** Whether others are in this room's group call (whether or not we are). */
    public boolean hasRunningCall(final Jid room) {
        final Map<Jid, Peer> peers = seen.get(room.asBareJid());
        return peers != null && !peers.isEmpty();
    }

    /** The {@code <muji>} our room presence must carry, or null when we're not in its call. */
    public Muji currentPayload(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        if (conference == null) {
            return null;
        }
        return Muji.payload(conference.ready, conference.video);
    }

    public List<Participant> getParticipants(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        final Map<Jid, Peer> peers = seen.get(room.asBareJid());
        if (conference == null || peers == null) {
            return ImmutableList.of();
        }
        final var builder = new ImmutableList.Builder<Participant>();
        for (final Map.Entry<Jid, Peer> entry : peers.entrySet()) {
            final JingleRtpConnection leg = conference.legs.get(entry.getValue().realJid);
            builder.add(new Participant(entry.getKey().getResource(), entry.getValue().video, leg));
        }
        return builder.build();
    }

    public boolean isVideo(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        return conference != null && conference.video;
    }

    public boolean isMicrophoneEnabled(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        return conference == null || conference.microphone;
    }

    public void setMicrophoneEnabled(final Jid room, final boolean enabled) {
        final Conference conference = conferences.get(room.asBareJid());
        if (conference == null) {
            return;
        }
        conference.microphone = enabled;
        for (final JingleRtpConnection leg : conference.legs.values()) {
            applyMicrophone(leg, enabled);
        }
    }

    public boolean isCameraEnabled(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        return conference != null && conference.video && conference.camera;
    }

    public void setCameraEnabled(final Jid room, final boolean enabled) {
        final Conference conference = conferences.get(room.asBareJid());
        if (conference == null) {
            return;
        }
        conference.camera = enabled;
        for (final JingleRtpConnection leg : conference.legs.values()) {
            applyCamera(leg, enabled);
        }
    }

    public CallIntegration.AudioDevice getAudioDevice(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        if (conference == null) {
            return CallIntegration.AudioDevice.NONE;
        }
        final var device = conference.audio.getSelectedAudioDevice();
        return device == null ? CallIntegration.AudioDevice.NONE : device;
    }

    public Set<CallIntegration.AudioDevice> getAudioDevices(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        return conference == null ? ImmutableSet.of() : conference.audio.getAudioDevices();
    }

    public void setAudioDevice(final Jid room, final CallIntegration.AudioDevice device) {
        final Conference conference = conferences.get(room.asBareJid());
        if (conference == null) {
            return;
        }
        conference.audio.executeOnMain(() -> conference.audio.setDefaultAudioDevice(device));
    }

    public AppRTCAudioManager getSharedAudio(final Jid room) {
        final Conference conference = conferences.get(room.asBareJid());
        return conference == null ? null : conference.audio;
    }

    // ------------------------------------------------------------- called by the MUC manager

    /** Every room presence passes through here (after Conversations handled it). */
    public synchronized void onPresence(final Presence presence) {
        final Jid occupant = presence.getFrom();
        if (occupant == null || occupant.getResource() == null) {
            return;
        }
        final Jid room = occupant.asBareJid();
        final MucUser mucUser = presence.getExtension(MucUser.class);
        final boolean unavailable = presence.getType() == Presence.Type.UNAVAILABLE;
        final boolean self =
                mucUser != null && mucUser.getStatus().contains(MucUser.STATUS_CODE_SELF_PRESENCE);
        final boolean nickChange =
                mucUser != null && mucUser.getStatus().contains(MucUser.STATUS_CODE_CHANGED_NICK);
        final Muji.State state = unavailable ? null : Muji.stateOf(presence);
        if (self) {
            onSelfPresence(room, occupant, state, unavailable, nickChange);
            return;
        }
        final Jid realJid =
                mucUser == null || mucUser.getItem() == null ? null : mucUser.getItem().getJid();
        final Map<Jid, Peer> peers = seen.computeIfAbsent(room, r -> new ConcurrentHashMap<>());
        final Peer previous = peers.remove(occupant);
        final Peer current =
                state == null ? null : acceptPeer(realJid, state, Muji.hasVideo(presence));
        if (current != null) {
            peers.put(occupant, current);
        }
        final Conference conference = conferences.get(room);
        if (conference == null) {
            updateInvite(room, occupant.getResource(), !peers.isEmpty());
            return;
        }
        if (previous != null
                && !(unavailable && nickChange)
                && (current == null || !previous.realJid.equals(current.realJid))) {
            endLeg(conference, previous.realJid);
        }
        if (current != null && current.state == Muji.State.READY) {
            maybeInitiate(conference, occupant, current);
        }
        if (peers.isEmpty() && conference.legs.isEmpty() && conference.connectedSince.get() > 0) {
            // the last other participant left: the call is over
            conferences.remove(room);
            teardown(conference, true);
        }
    }

    private void onSelfPresence(
            final Jid room,
            final Jid occupant,
            final Muji.State state,
            final boolean unavailable,
            final boolean nickChange) {
        final Conference conference = conferences.get(room);
        if (unavailable) {
            if (nickChange) {
                return;
            }
            onRoomLeft(room);
            return;
        }
        if (conference == null) {
            return;
        }
        conference.ourOccupant = occupant;
        if (state == null) {
            // a presence of ours went out without <muji> (others now think we left): re-announce
            final Conversation conversation = service.find(getAccount(), room);
            if (conversation != null) {
                announce(conversation);
            }
        } else if (state == Muji.State.READY && !conference.announced) {
            markAnnounced(room);
        }
    }

    /** We're (re)joining the room: it replays every occupant's presence. */
    public synchronized void onRoomJoining(final Jid room) {
        seen.remove(room.asBareJid());
        cancelInvite(room.asBareJid());
    }

    /** We left the room (or were removed): leave its call and forget its participants. */
    public synchronized void onRoomLeft(final Jid room) {
        final Conference conference = conferences.remove(room.asBareJid());
        if (conference != null) {
            teardown(conference, false);
        }
        seen.remove(room.asBareJid());
        cancelInvite(room.asBareJid());
    }

    // ------------------------------------------------------------ called by the Jingle manager

    /** A session-initiate tagged {@code <muji room>}: accept it if it's a leg of our call. */
    public synchronized void onSessionInitiate(
            final Iq packet, final AbstractJingleConnection.Id id, final String roomAttribute) {
        final JingleManager jingle = getManager(JingleManager.class);
        final Jid room;
        try {
            room = Jid.of(roomAttribute).asBareJid();
        } catch (final IllegalArgumentException e) {
            jingle.terminateMuji(packet, id, "invalid-room");
            return;
        }
        final Conference conference = conferences.get(room);
        if (conference == null) {
            jingle.terminateMuji(packet, id, "not-in-conference");
            return;
        }
        final Jid from = id.with;
        // only someone the room says is a participant (real JID vouched for by the room) may connect
        Peer peer = null;
        final Map<Jid, Peer> peers = seen.get(room);
        if (peers != null) {
            for (final Peer candidate : peers.values()) {
                if (candidate.realJid.equals(from)) {
                    peer = candidate;
                    break;
                }
            }
            if (peer == null) {
                // the room may list another session of the same account
                Map.Entry<Jid, Peer> sameAccount = null;
                int matches = 0;
                for (final Map.Entry<Jid, Peer> candidate : peers.entrySet()) {
                    if (candidate.getValue().realJid.asBareJid().equals(from.asBareJid())) {
                        sameAccount = candidate;
                        matches++;
                    }
                }
                if (matches == 1) {
                    final Peer stale = sameAccount.getValue();
                    peer = new Peer(from, stale.state, stale.video);
                    peers.put(sameAccount.getKey(), peer);
                }
            }
        }
        if (peer == null) {
            Log.w(Config.LOGTAG, "muji: rejecting session-initiate from non-participant " + from);
            jingle.terminateMuji(packet, id, "not-a-participant");
            return;
        }
        final JingleRtpConnection existing = conference.legs.get(from);
        if (existing != null) {
            final RtpEndUserState st = existing.getEndUserState();
            final boolean up =
                    st == RtpEndUserState.CONNECTED || st == RtpEndUserState.RECONNECTING;
            // crossed initiates: both sides keep the session with the smaller id
            if (up || existing.getId().sessionId.compareTo(id.sessionId) < 0) {
                jingle.terminateMuji(packet, id, "duplicate");
                return;
            }
            conference.legs.remove(from);
            conference.legStarted.remove(from);
            endSession(existing);
        } else if (conference.legs.size() >= MAX_PEERS) {
            jingle.terminateMuji(packet, id, "limit");
            return;
        }
        final JingleRtpConnection leg =
                jingle.createMujiResponder(id, room.toString(), conference.audio);
        leg.setProposedMedia(conference.media());
        conference.legs.put(from, leg);
        conference.legStarted.put(from, System.currentTimeMillis());
        leg.deliverPacket(packet); // auto-accepts: the leg knows it belongs to a group call
    }

    /** A leg ended (peer left, hang-up or error). Called asynchronously from the leg. */
    public synchronized void onLegEnded(final String roomString, final JingleRtpConnection leg) {
        final Jid room = Jid.of(roomString).asBareJid();
        final Conference conference = conferences.get(room);
        if (conference == null) {
            return;
        }
        final Jid peer = leg.getId().with;
        if (conference.legs.get(peer) == leg) {
            conference.legs.remove(peer, leg);
            conference.legStarted.remove(peer);
        }
        final Map<Jid, Peer> peers = seen.get(room);
        if (conference.legs.isEmpty()
                && (peers == null || peers.isEmpty())
                && conference.connectedSince.get() > 0) {
            conferences.remove(room);
            teardown(conference, true);
        }
        service.updateConversationUi();
    }

    /** A leg reached CONNECTED. */
    public void onLegConnected(final String roomString, final JingleRtpConnection leg) {
        final Conference conference = conferences.get(Jid.of(roomString).asBareJid());
        if (conference == null) {
            return;
        }
        conference.connectedSince.compareAndSet(0, System.currentTimeMillis());
        applyMicrophone(leg, conference.microphone);
        if (conference.video) {
            applyCamera(leg, conference.camera);
        }
        refreshOngoingCall(roomString);
    }

    /** One ongoing-call notification (and foreground service) for the whole group call. */
    public void refreshOngoingCall(final String roomString) {
        final Jid room = Jid.of(roomString).asBareJid();
        final Conference conference = conferences.get(room);
        JingleRtpConnection live = null;
        if (conference != null) {
            for (final JingleRtpConnection leg : conference.legs.values()) {
                if (leg.getState() == AbstractJingleConnection.State.SESSION_ACCEPTED) {
                    live = leg;
                    break;
                }
                if (live == null
                        && JingleRtpConnection.STATES_SHOWING_ONGOING_CALL.contains(
                                leg.getState())) {
                    live = leg;
                }
            }
        }
        if (live != null) {
            service.setOngoingGroupCall(live.getId(), conference.media(), room);
        } else if (service.isOngoingGroupCall(room)) {
            service.removeOngoingCall();
        }
    }

    // ------------------------------------------------------------------------------ internals

    private synchronized void markAnnounced(final Jid room) {
        final Conference conference = conferences.get(room);
        if (conference == null || conference.announced) {
            return;
        }
        conference.announced = true;
        final Map<Jid, Peer> peers = seen.get(room);
        if (peers != null) {
            for (final Map.Entry<Jid, Peer> entry : peers.entrySet()) {
                if (entry.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private synchronized void remesh(final Jid room) {
        final Conference conference = conferences.get(room);
        if (conference == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final List<Jid> drop = new ArrayList<>();
        for (final Map.Entry<Jid, JingleRtpConnection> entry : conference.legs.entrySet()) {
            final RtpEndUserState st = entry.getValue().getEndUserState();
            final boolean up =
                    st == RtpEndUserState.CONNECTED || st == RtpEndUserState.RECONNECTING;
            final Long since = conference.legStarted.get(entry.getKey());
            final boolean stuck = !up && since != null && now - since > STUCK_LEG_MILLIS;
            if (st == RtpEndUserState.ENDED || st == RtpEndUserState.CONNECTIVITY_ERROR || stuck) {
                drop.add(entry.getKey());
            }
        }
        for (final Jid peer : drop) {
            Log.d(Config.LOGTAG, "muji: retrying leg with " + peer);
            endLeg(conference, peer);
        }
        final Map<Jid, Peer> peers = seen.get(room);
        if (peers != null) {
            for (final Map.Entry<Jid, Peer> entry : peers.entrySet()) {
                if (entry.getValue().state == Muji.State.READY) {
                    maybeInitiate(conference, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void maybeInitiate(final Conference conference, final Jid occupant, final Peer peer) {
        if (!conference.announced || conference.legs.containsKey(peer.realJid)) {
            return;
        }
        if (!Muji.shouldInitiate(
                conference.ourOccupant.getResource(), occupant.getResource())) {
            return; // they call us
        }
        if (conference.legs.size() >= MAX_PEERS) {
            return;
        }
        try {
            final JingleRtpConnection leg =
                    getManager(JingleManager.class)
                            .initializeMujiSession(
                                    peer.realJid,
                                    conference.room.toString(),
                                    conference.media(),
                                    conference.audio);
            conference.legs.put(peer.realJid, leg);
            conference.legStarted.put(peer.realJid, System.currentTimeMillis());
            Log.d(Config.LOGTAG, "muji: calling " + occupant + " (" + peer.realJid + ")");
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "muji: could not call " + occupant, e);
        }
    }

    private Peer acceptPeer(final Jid realJid, final Muji.State state, final boolean video) {
        if (realJid == null || realJid.isBareJid()) {
            return null; // anonymous occupant: we can't address or check them
        }
        if (realJid.equals(getAccount().getJid())) {
            return null;
        }
        if (getManager(BlockingManager.class).isBlocked(realJid)) {
            return null;
        }
        return new Peer(realJid, state, video);
    }

    private void endLeg(final Conference conference, final Jid peer) {
        final JingleRtpConnection leg = conference.legs.remove(peer);
        conference.legStarted.remove(peer);
        if (leg != null) {
            endSession(leg);
        }
    }

    private void teardown(final Conference conference, final boolean retractPresence) {
        if (conference.remesh != null) {
            conference.remesh.cancel(false);
            conference.remesh = null;
        }
        if (retractPresence) {
            final Conversation conversation = service.find(getAccount(), conference.room);
            if (conversation != null) {
                announce(conversation); // no longer in conferences: goes out without <muji>
            }
        }
        for (final JingleRtpConnection leg : conference.legs.values()) {
            endSession(leg);
        }
        conference.legs.clear();
        conference.legStarted.clear();
        conference.audio.executeOnMain(conference.audio::stop);
        if (service.isOngoingGroupCall(conference.room)) {
            service.removeOngoingCall();
        }
        writeCallLog(conference);
        service.updateConversationUi();
    }

    private void announce(final Conversation conversation) {
        getManager(MultiUserChatManager.class).resendPresence(conversation);
    }

    private static void endSession(final JingleRtpConnection leg) {
        try {
            leg.endCall();
        } catch (final RuntimeException e) {
            Log.w(Config.LOGTAG, "muji: could not end leg", e);
        }
    }

    private static void applyMicrophone(final JingleRtpConnection leg, final boolean enabled) {
        try {
            leg.setMicrophoneEnabled(enabled);
        } catch (final RuntimeException e) {
            // the leg's WebRTC isn't set up yet; applied again when it connects
        }
    }

    private static void applyCamera(final JingleRtpConnection leg, final boolean enabled) {
        try {
            leg.setVideoEnabled(enabled);
        } catch (final RuntimeException e) {
            // no video track on this leg (yet)
        }
    }

    private void startAudio(final Conference conference) {
        final AppRTCAudioManager audio = conference.audio;
        audio.setAudioManagerEvents(
                (selected, available) ->
                        service.notifyJingleRtpConnectionUpdate(selected, available));
        audio.executeOnMain(
                () -> {
                    audio.start();
                    final var initial = CallIntegration.initialAudioDevice(conference.media());
                    final var available = audio.getAudioDevices();
                    if (available.contains(initial)
                            && !available.contains(CallIntegration.AudioDevice.BLUETOOTH)
                            && !available.contains(CallIntegration.AudioDevice.WIRED_HEADSET)) {
                        audio.setDefaultAudioDevice(initial);
                    }
                });
    }

    private void writeCallLog(final Conference conference) {
        final Conversation conversation = service.find(getAccount(), conference.room);
        if (conversation == null) {
            return;
        }
        final long since = conference.connectedSince.get();
        final boolean connected = since > 0;
        final Message message =
                new Message(
                        conversation,
                        connected && conference.joinedRunningCall
                                ? Message.STATUS_RECEIVED
                                : Message.STATUS_SEND,
                        Message.TYPE_RTP_SESSION,
                        UUID.randomUUID().toString());
        message.setTime(conference.startedAt);
        message.setBody(
                new RtpSessionStatus(connected, connected ? System.currentTimeMillis() - since : 0)
                        .toString());
        conversation.add(message);
        getDatabase().createMessage(message);
    }

    // ------------------------------------------------------------------ "join group call" ring

    private void updateInvite(final Jid room, final String who, final boolean anyone) {
        if (anyone && invited.add(room)) {
            final Conversation conversation = service.find(getAccount(), room);
            if (conversation != null && !conversation.isMuted()) {
                showInvite(conversation, who);
            }
        } else if (!anyone) {
            cancelInvite(room);
        }
    }

    private void cancelInvite(final Jid room) {
        if (invited.remove(room)) {
            NotificationManagerCompat.from(context).cancel(room.toString(), R.id.plus_group_call);
        }
    }

    private void showInvite(final Conversation conversation, final String who) {
        final Jid room = conversation.getAddress().asBareJid();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final var channel =
                    new NotificationChannel(
                            INVITE_CHANNEL,
                            context.getString(R.string.plus_group_calls),
                            NotificationManager.IMPORTANCE_HIGH);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
        final Intent intent = GroupCallActivity.intent(context, getAccount(), room, false);
        final PendingIntent pending =
                PendingIntent.getActivity(
                        context,
                        room.hashCode(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final var notification =
                new NotificationCompat.Builder(context, INVITE_CHANNEL)
                        .setSmallIcon(R.drawable.ic_call_24dp)
                        .setContentTitle(conversation.getName())
                        .setContentText(context.getString(R.string.plus_group_call_started, who))
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(pending)
                        .addAction(
                                R.drawable.ic_call_24dp,
                                context.getString(R.string.plus_join),
                                pending)
                        .build();
        try {
            NotificationManagerCompat.from(context)
                    .notify(room.toString(), R.id.plus_group_call, notification);
        } catch (final SecurityException e) {
            Log.w(Config.LOGTAG, "muji: no permission to notify", e);
        }
    }

    // ------------------------------------------------------------------------------- types

    private static final class Peer {
        private final Jid realJid;
        private final Muji.State state;
        private final boolean video;

        private Peer(final Jid realJid, final Muji.State state, final boolean video) {
            this.realJid = realJid;
            this.state = state;
            this.video = video;
        }
    }

    /** One other participant, for the call screen. {@code leg} is null while not connected. */
    public record Participant(String nick, boolean video, JingleRtpConnection leg) {}

    private final class Conference {
        private final Jid room;
        private volatile Jid ourOccupant;
        private final boolean video;
        private final boolean joinedRunningCall;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicLong connectedSince = new AtomicLong(0);
        private final AppRTCAudioManager audio = new AppRTCAudioManager(context);
        private final Map<Jid, JingleRtpConnection> legs = new ConcurrentHashMap<>();
        private final Map<Jid, Long> legStarted = new ConcurrentHashMap<>();
        private volatile boolean ready = false;
        private volatile boolean announced = false;
        private volatile boolean microphone = true;
        private volatile boolean camera = true;
        private ScheduledFuture<?> remesh;

        private Conference(
                final Jid room,
                final Jid ourOccupant,
                final boolean video,
                final boolean joinedRunningCall) {
            this.room = room;
            this.ourOccupant = ourOccupant;
            this.video = video;
            this.joinedRunningCall = joinedRunningCall;
        }

        private Set<Media> media() {
            return video ? ImmutableSet.of(Media.AUDIO, Media.VIDEO) : ImmutableSet.of(Media.AUDIO);
        }
    }
}
