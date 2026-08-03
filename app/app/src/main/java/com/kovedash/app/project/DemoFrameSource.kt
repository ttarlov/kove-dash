package com.kovedash.app.project

/**
 * Placeholder for V0/V1 demo frame sourcing. ProjectionSession currently reads the
 * named asset directly; this object exists so V1 can swap in:
 *   - cycling through multiple frames at N fps
 *   - decoding an MP4 to Annex-B at runtime
 *   - rendering a Compose UI to a Canvas, encoding via MediaCodec
 *
 * V2 will replace this entirely with the Presentation+VirtualDisplay+MediaCodec
 * pipeline described in docs/ARCHITECTURE.md.
 */
object DemoFrameSource {
    const val DEFAULT_ASSET = "motion.h264"
    const val STATIC_FALLBACK = "motion.h264"
}
