package app.getknit.knit.mesh.protocol

import java.security.MessageDigest

/**
 * A canonical frame id ([FrameId.admits]) derived from a readable [label], so a test can still name its frames `m1` and
 * `tick1` and have them pass the inbound doors, which refuse any other shape (#178). The same label is the same id.
 */
internal fun frameIdOf(label: String): String =
    FrameId.fromBytes(MessageDigest.getInstance("SHA-256").digest(label.encodeToByteArray()).copyOf(FrameId.ID_BYTES))
