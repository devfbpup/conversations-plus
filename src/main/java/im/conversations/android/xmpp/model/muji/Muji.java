package im.conversations.android.xmpp.model.muji;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

/**
 * Conversations+: XEP-0272 Muji (group calls). A group call is a MUC whose participants announce
 * {@code <muji>} in their room presence ({@code <preparing/>} first, then the media they send) and
 * connect pairwise with ordinary Jingle RTP sessions (a full mesh) that carry {@code <muji room>}.
 * Wire-compatible with monocles chat (same payload and tie-break), which this port is based on.
 */
@XmlElement(name = "muji")
public class Muji extends Extension {

    public static final String NAMESPACE = "urn:xmpp:jingle:muji:0";

    public enum State {
        PREPARING,
        READY
    }

    public Muji() {
        super(Muji.class);
    }

    /** Our presence payload: {@code <preparing/>} until ready, then the media we send. */
    public static Muji payload(final boolean ready, final boolean video) {
        final var muji = new Muji();
        if (!ready) {
            muji.addChild("preparing", NAMESPACE);
            return muji;
        }
        content(muji, "voice", "audio", "111", "opus", "48000", "2");
        if (video) {
            content(muji, "webcam", "video", "96", "VP8", "90000", null);
        }
        return muji;
    }

    private static void content(
            final Muji muji,
            final String name,
            final String media,
            final String id,
            final String codec,
            final String clockrate,
            final String channels) {
        final Element content = muji.addChild("content", NAMESPACE);
        content.setAttribute("creator", "initiator");
        content.setAttribute("name", name);
        final Element description = content.addChild("description", Namespace.JINGLE_APPS_RTP);
        description.setAttribute("media", media);
        final Element payloadType = description.addChild("payload-type", Namespace.JINGLE_APPS_RTP);
        payloadType.setAttribute("id", id);
        payloadType.setAttribute("name", codec);
        payloadType.setAttribute("clockrate", clockrate);
        if (channels != null) {
            payloadType.setAttribute("channels", channels);
        }
    }

    /** The {@code <muji>} state in an occupant's presence, or null when they are not in the call. */
    public static State stateOf(final Element presence) {
        final Element muji = presence.findChild("muji", NAMESPACE);
        if (muji == null) {
            return null;
        }
        if (muji.findChild("preparing", NAMESPACE) != null) {
            return State.PREPARING;
        }
        for (final Element child : muji.getChildren()) {
            if ("content".equals(child.getName())) {
                return State.READY;
            }
        }
        return State.PREPARING;
    }

    /** Whether the occupant's {@code <muji>} advertises video. */
    public static boolean hasVideo(final Element presence) {
        final Element muji = presence.findChild("muji", NAMESPACE);
        if (muji == null) {
            return false;
        }
        for (final Element child : muji.getChildren()) {
            if ("content".equals(child.getName())) {
                final Element description = child.findChild("description", Namespace.JINGLE_APPS_RTP);
                if (description != null && "video".equals(description.getAttribute("media"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The {@code room} of a {@code <muji>} child of a {@code <jingle>} element, or null. */
    public static String roomOf(final Element jingle) {
        final Element muji = jingle.findChild("muji", NAMESPACE);
        return muji == null ? null : muji.getAttribute("room");
    }

    /**
     * Glare tie-break: of two ready participants, the one whose nick is greater starts the session.
     * Only nicks are compared (clients may spell the room JID differently).
     */
    public static boolean shouldInitiate(final String ourNick, final String theirNick) {
        return ourNick.compareTo(theirNick) > 0;
    }
}
