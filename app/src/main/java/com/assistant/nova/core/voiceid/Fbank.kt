package com.assistant.nova.core.voiceid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Kaldi-style 80-band log-mel filterbank ("fbank"), the input the WeSpeaker voice-ID model
 * was trained on: 25 ms Hamming frames every 10 ms, DC removal, pre-emphasis 0.97,
 * 512-point FFT, mel filters from 20 Hz to 8 kHz. Pure Kotlin so it can be unit tested.
 */
object Fbank {
    const val SAMPLE_RATE = 16_000
    const val BINS = 80
    private const val FRAME = 400
    private const val SHIFT = 160
    private const val NFFT = 512
    private const val PREEMPH = 0.97
    private const val EPS = 1.1920929e-7

    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }

    private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

    /** [BINS] triangular filters over the first NFFT/2 FFT bins, built the way Kaldi does. */
    private val filters: Array<DoubleArray> = run {
        val numFft = NFFT / 2
        val binWidth = SAMPLE_RATE.toDouble() / NFFT
        val lo = mel(20.0)
        val hi = mel(SAMPLE_RATE / 2.0)
        val delta = (hi - lo) / (BINS + 1)
        Array(BINS) { b ->
            val left = lo + b * delta
            val center = left + delta
            val right = center + delta
            DoubleArray(numFft) { k ->
                val m = mel(binWidth * k)
                when {
                    m <= left || m >= right -> 0.0
                    m <= center -> (m - left) / (center - left)
                    else -> (right - m) / (right - center)
                }
            }
        }
    }

    /** [samples] at 16-bit scale (not normalised to ±1). Returns frames × [BINS]. */
    fun compute(samples: FloatArray, count: Int = samples.size): Array<FloatArray> {
        if (count < FRAME) return emptyArray()
        val frames = 1 + (count - FRAME) / SHIFT
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        return Array(frames) { f ->
            val start = f * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) mean += samples[start + i]
            mean /= FRAME
            for (i in 0 until FRAME) re[i] = samples[start + i] - mean
            for (i in FRAME - 1 downTo 1) re[i] -= PREEMPH * re[i - 1]
            re[0] -= PREEMPH * re[0]
            for (i in 0 until FRAME) re[i] *= window[i]
            for (i in FRAME until NFFT) re[i] = 0.0
            im.fill(0.0)
            fft(re, im)
            val out = FloatArray(BINS)
            for (b in 0 until BINS) {
                val w = filters[b]
                var e = 0.0
                for (k in w.indices) if (w[k] != 0.0) e += w[k] * (re[k] * re[k] + im[k] * im[k])
                out[b] = ln(max(e, EPS)).toFloat()
            }
            out
        }
    }

    /** Subtracts each band's average over the utterance (cepstral mean normalisation). */
    fun meanNormalize(feats: Array<FloatArray>): Array<FloatArray> {
        if (feats.isEmpty()) return feats
        val mean = FloatArray(BINS)
        for (f in feats) for (b in 0 until BINS) mean[b] += f[b]
        for (b in 0 until BINS) mean[b] /= feats.size
        return Array(feats.size) { i -> FloatArray(BINS) { b -> feats[i][b] - mean[b] } }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
