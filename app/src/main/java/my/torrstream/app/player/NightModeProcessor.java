package my.torrstream.app.player;

import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;

/**
 * «Ночной режим» звука: громкое тише, чтобы тихое было слышно, а взрывы не будили дом.
 *
 * <p>Компрессор с ограничителем на выходе — как у эталонного плеера (ffmpeg acompressor +
 * alimiter=limit=0.95, константы оттуда же), арифметика — по af_sidechaincompress.c: общий для
 * каналов пиковый детектор, однополюсное сглаживание, колено по Эрмиту. Ограничитель без
 * заглядывания вперёд: усиление кадра считается от пика этого же кадра и держится ниже потолка,
 * так что ничего не клиппирует.
 *
 * <p>Два уровня: {@link NightMode#GENTLE} (−22 dBFS, 3:1, для ресивера с хорошими колонками) и
 * {@link NightMode#STRONG} (−32 dBFS, 10:1, для динамиков ТВ). Уровень читается на каждом буфере
 * из {@link NightMode#level}, так что переключается посреди фильма без пересборки плеера:
 * процессор в цепочке всегда, выключенный — пропускает как есть.
 *
 * <p>Только 16-битный PCM: битстрим на ресивер — дело ресивера, у него для этого свои метаданные.
 * Из Just+ Player (DynamicRangeProcessor, Unlicense).
 */
final class NightModeProcessor extends BaseAudioProcessor {

    /**
     * The compressor's arithmetic, kept free of Android so it can be run on its own. One instance per
     * configuration; {@link #gainFor} is called once per frame and carries the detector between them.
     */
    static final class Compressor {
        private final double threshold;
        private final double ratio;
        private final double kneeStart;
        private final double kneeStop;
        private final double linearKneeStart;
        private final double compressedKneeStop;
        private final float makeup;
        private final float limit;
        private final float attack;
        private final float release;
        /** The detector: the peak amplitude as it is followed, not as it arrives. */
        private float envelope;

        /**
         * @param threshold  where compression starts, as a fraction of full scale
         * @param ratio      how many decibels in for one decibel out, above the threshold
         * @param knee       how wide the corner at the threshold is, as a factor: 3 rounds it over
         *                   about five decibels either side, 2 over three
         * @param attackMs   how fast the detector follows a peak up
         * @param releaseMs  how slowly it follows one down
         * @param makeup     linear gain applied afterwards, since the loud has been taken away
         * @param limit      the ceiling the output is held under, as a fraction of full scale
         * @param sampleRate frames per second, which is what turns the times into coefficients
         */
        Compressor(float threshold, float ratio, float knee, float attackMs, float releaseMs,
                   float makeup, float limit, int sampleRate) {
            this.threshold = Math.log(threshold);
            this.ratio = ratio;
            this.linearKneeStart = threshold / Math.sqrt(knee);
            this.kneeStart = Math.log(linearKneeStart);
            this.kneeStop = Math.log(threshold * Math.sqrt(knee));
            this.compressedKneeStop = (kneeStop - this.threshold) / ratio + this.threshold;
            this.makeup = makeup;
            this.limit = limit;
            this.attack = coefficient(attackMs, sampleRate);
            this.release = coefficient(releaseMs, sampleRate);
        }

        /**
         * The fraction of the distance to the target covered in one frame. ffmpeg's own formula, and the
         * four thousand in it is why the reference's millisecond figures mean what they mean: a time
         * constant of a quarter of the number written.
         */
        private static float coefficient(float ms, int sampleRate) {
            return (float) Math.min(1.0, 4000.0 / (ms * (double) sampleRate));
        }

        /**
         * Hermite interpolation between two points with two slopes, as ffmpeg rounds the knee with.
         */
        private static double hermite(double x, double x0, double x1, double p0, double p1,
                                      double m0, double m1) {
            final double width = x1 - x0;
            final double t = (x - x0) / width;
            final double s0 = m0 * width;
            final double s1 = m1 * width;
            final double t2 = t * t;
            final double t3 = t2 * t;
            return (2 * p0 + s0 - 2 * p1 + s1) * t3
                    + (-3 * p0 - 2 * s0 + 3 * p1 - s1) * t2
                    + s0 * t + p0;
        }

        /**
         * The gain for this frame, from the loudest sample in it. The detector rises at the attack rate
         * and falls at the release one, which is the difference between taming a peak and hearing the
         * compressor breathe; the curve is then read off the detector, not off the sample, so a single
         * spike does not duck a whole scene.
         */
        float gainFor(float peak) {
            envelope += (peak - envelope) * (peak > envelope ? attack : release);
            float gain = makeup;
            if (envelope > linearKneeStart) {
                final double level = Math.log(envelope);
                double compressed = (level - threshold) / ratio + threshold;
                if (level < kneeStop) {
                    // Inside the corner, where the curve bends from straight-through to compressed.
                    compressed = hermite(level, kneeStart, kneeStop, kneeStart, compressedKneeStop,
                            1.0, 1.0 / ratio);
                }
                gain = (float) (Math.exp(compressed - level) * makeup);
            }
            // The limiter. This frame's gain against this frame's own peak, so the result cannot pass
            // the ceiling and nothing downstream has to throw anything away.
            return peak > 0f ? Math.min(gain, limit / peak) : gain;
        }

        /** Only for the check beside this file: the detector's current value. */
        float envelope() {
            return envelope;
        }
    }

    /** Full scale for the 16-bit samples the sink hands over. */
    private static final float FULL_SCALE = 32767f;
    /** The ceiling the reference limits to, leaving the last fraction of a decibel as headroom. */
    private static final float LIMIT = 0.95f;

    private Compressor compressor;
    /** Уровень, под который собран {@link #compressor}; OFF — буфер шёл как есть. */
    private int compressorLevel = NightMode.OFF;

    private static Compressor newCompressor(int level, int sampleRate) {
        return level == NightMode.STRONG
                // Threshold -32 dBFS, ten to one, and a fast grip: the shape the reference uses when it
                // wants the night quiet rather than the mix intact.
                ? new Compressor(0.025f, 10f, 2f, 3f, 130f, 2.3f, LIMIT, sampleRate)
                // -22 dBFS, three to one, slower: audible on the loudest scenes and barely elsewhere.
                : new Compressor(0.08f, 3f, 3f, 10f, 200f, 1.6f, LIMIT, sampleRate);
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            // Anything else is passed on untouched, which for a bitstream is the only right answer.
            return AudioFormat.NOT_SET;
        }
        compressor = null;
        compressorLevel = NightMode.OFF;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return;
        }
        final int level = NightMode.level;
        if (level == NightMode.OFF) {
            compressorLevel = NightMode.OFF;
            replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip();
            return;
        }
        if (compressor == null || compressorLevel != level) {
            // A detector left where it was when the switch went off would duck the first moments for
            // a peak long gone.
            compressor = newCompressor(level, inputAudioFormat.sampleRate);
            compressorLevel = level;
            Log.i("NightMode", (level == NightMode.STRONG ? "strong" : "gentle") + " on "
                    + inputAudioFormat.channelCount + " channels at " + inputAudioFormat.sampleRate + " Hz");
        }
        final int channelCount = inputAudioFormat.channelCount;
        final int frameBytes = 2 * channelCount;
        final int start = inputBuffer.position();
        final int frames = inputBuffer.remaining() / frameBytes;
        // Whole frames only; the leftover of a partial one is passed through rather than held: a
        // processor that consumes less than it is given and produces nothing stalls the pipeline.
        final ByteBuffer outputBuffer = replaceOutputBuffer(inputBuffer.remaining());
        for (int frame = 0; frame < frames; frame++) {
            final int at = start + frame * frameBytes;
            // The frame's peak first: the gain has to be the same for every channel of it.
            float peak = 0f;
            for (int channel = 0; channel < channelCount; channel++) {
                final float sample = Math.abs(inputBuffer.getShort(at + channel * 2)) / FULL_SCALE;
                if (sample > peak) {
                    peak = sample;
                }
            }
            final float gain = compressor.gainFor(peak);
            for (int channel = 0; channel < channelCount; channel++) {
                final int scaled = Math.round(inputBuffer.getShort(at + channel * 2) * gain);
                outputBuffer.putShort((short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, scaled)));
            }
        }
        inputBuffer.position(start + frames * frameBytes);
        while (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer.get());
        }
        outputBuffer.flip();
    }

    @Override
    protected void onFlush() {
        // A seek lands somewhere else entirely; a detector carried across it would duck the first
        // second of the new place for a peak that is no longer coming.
        compressor = null;
    }

    @Override
    protected void onReset() {
        compressor = null;
        compressorLevel = NightMode.OFF;
    }
}
