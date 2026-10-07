package com.takekazex.hypertweak.dock

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

internal data class DockMotion(val sequence: Long, val x: Double, val y: Double, val scale: Double,
    val originY: Double, val alpha: Double, val kind: Int = 0, val screenScale: Double = 1.0, val sourceEpoch: Long = 0L)

internal class DockMotionLedger {
    @Volatile var latest: DockMotion? = null
        private set
    private var closed = false
    private var sequence = 0L
    private val versions = LongArray(8)
    private var sourceEpoch = 0L
    private val retiredSources = LinkedHashSet<Long>()
    private fun resetProducerState() {
        versions.fill(0L)
        controllerScale = 1.0; controllerAlpha = 1.0
        scaleRevision = 0L; alphaRevision = 0L
        inner = null; screen = null; editingVisible = 1.0
        desktopInteraction = false; lastVisibleRowAlpha = 1.0
        rowVisible = 1.0; localRevision = 0L; visibilityRevision = 0L
    }
    private var controllerScale = 1.0
    private var controllerAlpha = 1.0
    private var scaleRevision = 0L
    private var alphaRevision = 0L
    private var inner: DockMotion? = null
    private var screen: DockMotion? = null
    private var editingVisible = 1.0
    private var desktopInteraction = false
    private var lastVisibleRowAlpha = 1.0
    private var rowVisible = 1.0
    private var localRevision = 0L
    private var visibilityRevision = 0L
    @Synchronized fun offer(frame: DockMotion): Boolean {
        if (closed || frame.kind !in 0..7 || frame.sourceEpoch in retiredSources) return false
        if (frame.sourceEpoch != sourceEpoch) {
            if (sourceEpoch != 0L) retiredSources.add(sourceEpoch)
            while (retiredSources.size > 64) retiredSources.remove(retiredSources.first())
            resetProducerState()
            sourceEpoch = frame.sourceEpoch
        }
        if (frame.sequence <= versions[frame.kind]) return false
        versions[frame.kind] = frame.sequence
        sequence++
        when (frame.kind) {
            0 -> {
                // Legacy empty-row packets had no distinct kind and no valid pose.
                val legacyEmpty = frame.sourceEpoch <= 0 && frame.alpha == 0.0 && frame.x == 0.0 && frame.y == 0.0 && frame.scale == 1.0 && frame.originY == 0.0
                if (frame.sequence > visibilityRevision) { rowVisible = frame.alpha; visibilityRevision = frame.sequence }
                if (!legacyEmpty && frame.sequence > localRevision) { inner = frame; localRevision = frame.sequence }
                if (frame.alpha > 0) lastVisibleRowAlpha = frame.alpha
            }
            6 -> if (frame.sequence > localRevision) {
                val previous = inner ?: DockMotion(sequence, 0.0, 0.0, 1.0, 0.0, 1.0)
                inner = previous.copy(y = frame.y, scale = frame.scale)
                localRevision = frame.sequence
            }
            7 -> if (frame.sequence > visibilityRevision) { rowVisible = 0.0; visibilityRevision = frame.sequence }
            1 -> screen = frame
            2 -> {
                editingVisible = frame.alpha
                val interacting = frame.alpha > 0 && frame.originY == 1.0
                if (desktopInteraction && !interacting && frame.alpha > 0 && rowVisible == 0.0) rowVisible = lastVisibleRowAlpha
                desktopInteraction = interacting
            }
            3, 4, 5 -> {
                if (frame.kind != 4 && frame.sequence > scaleRevision) {
                    controllerScale = frame.scale; scaleRevision = frame.sequence
                }
                if (frame.kind != 3 && frame.sequence > alphaRevision) {
                    controllerAlpha = frame.alpha; alphaRevision = frame.sequence
                }
            }

        }
        // The inner row can remain cached while the root animates or editing changes.
        val row = inner ?: DockMotion(sequence, 0.0, 0.0, 1.0, 0.0, 1.0)
        latest = row.copy(sequence = sequence, alpha = (if (desktopInteraction) lastVisibleRowAlpha else rowVisible) * (screen?.alpha ?: 1.0) * controllerAlpha * editingVisible,
            screenScale = (screen?.scale ?: 1.0) * controllerScale, sourceEpoch = sourceEpoch)
        return true
    }
    @Synchronized fun snapshot(): Array<Any> = arrayOf(versions.copyOf(), sequence,
        doubleArrayOf(inner?.x ?: 0.0, inner?.y ?: 0.0, inner?.scale ?: 1.0, inner?.originY ?: 0.0, inner?.alpha ?: 1.0,
            screen?.scale ?: 1.0, screen?.alpha ?: 1.0, editingVisible, controllerScale, controllerAlpha, if (desktopInteraction) 1.0 else 0.0, lastVisibleRowAlpha, rowVisible), sourceEpoch, retiredSources.toLongArray())
    @Synchronized fun restore(state: Any?) {
        val data = state as? Array<*> ?: return
        val savedVersions = data.getOrNull(0) as? LongArray ?: return
        val fields = data.getOrNull(2) as? DoubleArray ?: return
        if (closed || versions.any { it != 0L } || savedVersions.size !in listOf(6, 8) || fields.size !in listOf(10, 12, 13) || fields.any { !it.isFinite() }) return
        sourceEpoch = data.getOrNull(3) as? Long ?: 0L
        (data.getOrNull(4) as? LongArray)?.takeLast(64)?.let { retiredSources.addAll(it) }
        savedVersions.copyInto(versions)
        sequence = data.getOrNull(1) as? Long ?: 0L
        inner = DockMotion(sequence, fields[0], fields[1], fields[2], fields[3], fields[4])
        screen = DockMotion(sequence, 0.0, 0.0, fields[5], 0.0, fields[6])
        editingVisible = fields[7]; controllerScale = fields[8]; controllerAlpha = fields[9]
        scaleRevision = maxOf(versions[3], versions[5]); alphaRevision = maxOf(versions[4], versions[5])
        rowVisible = fields.getOrNull(12) ?: fields[4]
        localRevision = maxOf(versions[0], versions[6])
        visibilityRevision = maxOf(versions[0], versions[7])
        desktopInteraction = fields.getOrNull(10) == 1.0
        lastVisibleRowAlpha = fields.getOrNull(11) ?: fields[4].takeIf { it > 0 } ?: 1.0
        if (savedVersions.any { it != 0L }) latest = requireNotNull(inner).copy(alpha = (if (desktopInteraction) lastVisibleRowAlpha else rowVisible) * fields[6] * fields[7] * fields[9], screenScale = fields[5] * fields[8], sourceEpoch = sourceEpoch)
    }
    @Synchronized fun close() { closed = true; latest = null; inner = null; screen = null }
}

internal object DockMotionCodec {
    const val SIZE = 72
    fun decode(bytes: ByteArray, length: Int, token0: Long, token1: Long): DockMotion? {
        if (length != SIZE || bytes.size < length || token0 == 0L || token1 == 0L) return null
        val b = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val header = b.long
        val kind = ((header ushr 16) and 0xffffL).toInt()
        val epoch = header ushr 32
        if (header and 0xffffL != 0x444bL || kind !in 0..7 || b.long != token0 || b.long != token1) return null
        val value = DockMotion(b.long, b.double, b.double, b.double, b.double, b.double, kind, sourceEpoch = epoch)
        if (value.sequence <= 0 || listOf(value.x, value.y, value.scale, value.originY, value.alpha).any { !it.isFinite() } ||
            kotlin.math.abs(value.x) > 20_000 || kotlin.math.abs(value.y) > 20_000 ||
            kotlin.math.abs(value.originY) > 20_000 || value.scale !in 0.0..4.0 || value.alpha !in -4.0..4.0) return null
        return value.copy(alpha = value.alpha.coerceIn(0.0, 1.0))
    }
}

/** One authenticated setup broadcast; changed render frames use a bounded nonblocking loopback channel. */
internal class DockFrameChannel(private val restored: Any? = null, private val changed: (DockMotion) -> Unit) : AutoCloseable {
    companion object {
        const val OFFER = "com.takekazex.hypertweak.action.DOCK_CHANNEL"
        const val REQUEST = "com.takekazex.hypertweak.action.DOCK_CHANNEL_REQUEST"
    }
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    val port: Int = socket.localPort
    private val random = SecureRandom()
    val token0: Long = generateSequence { random.nextLong() }.first { it != 0L }
    val token1: Long = generateSequence { random.nextLong() }.first { it != 0L }
    private val ledger = DockMotionLedger().apply { restore(restored) }
    fun snapshot(): Array<Any> = ledger.snapshot()
    val latest: DockMotion? get() = ledger.latest
    init {
        Thread({
            val bytes = ByteArray(DockMotionCodec.SIZE + 1)
            val packet = DatagramPacket(bytes, bytes.size)
            val observed = BooleanArray(8)
            var producer = 0L
            while (!socket.isClosed) {
                try {
                    packet.length = bytes.size
                    socket.receive(packet)
                    if (!packet.address.isLoopbackAddress) continue
                    val decoded = DockMotionCodec.decode(bytes, packet.length, token0, token1) ?: continue
                    // Previous native payloads have no epoch. Their connected socket keeps its
                    // source port for process life, including endpoint rebinds after Java reload.
                    val frame = if (decoded.sourceEpoch == 0L) decoded.copy(sourceEpoch = -packet.port.toLong()) else decoded
                    if (!ledger.offer(frame)) continue
                    if (producer != frame.sourceEpoch) {
                        if (producer != 0L) com.takekazex.hypertweak.util.DebugLog.i("DockMotion", "native producer changed; motion state rebound")
                        producer = frame.sourceEpoch
                        observed.fill(false)
                    }
                    if (!observed[frame.kind]) {
                        observed[frame.kind] = true
                        com.takekazex.hypertweak.util.DebugLog.i("DockMotion", "native source active: ${arrayOf("hotseat", "legacy-screen", "editing", "controller-scale", "controller-alpha", "controller-set", "hotseat-tick", "hotseat-empty")[frame.kind]}")
                    }
                    changed(frame)
                } catch (error: Exception) {
                    if (!socket.isClosed) com.takekazex.hypertweak.util.DebugLog.w("DockMotion", "frame channel failed", error)
                    break
                }
            }
        }, "HT-DockMotion").apply { isDaemon = true }.start()
    }
    override fun close() { ledger.close(); socket.close() }
}
