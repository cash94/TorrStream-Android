package my.torrstream.app.player;

import android.media.MediaCodecInfo.CodecProfileLevel;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.DoviStrategy;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.Hdr10PlusStrategy;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.HevcFrameTransformer;
import com.suyashbelekar.exoplayerhdrutils.video.transformers.TransformStrategy;
import java.io.EOFException;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dolby Vision profile 7 (UHD BDRemux) — как Dolby Vision profile 8.1, а не как HDR10 базового слоя.
 *
 * <p>У profile 7 к HDR10-совместимому HEVC добавлены NAL-блоки 62 (RPU, метаданные DV) и 63 (слой
 * улучшения). Декодер устройства включает режим Dolby Vision на HDMI только для однослойных
 * профилей 5 и 8.1, так что profile 7 выходит как HDR10, даже когда открылся декодер
 * video/dolby-vision. Если переписать RPU в форму 8.1 и выбросить слой улучшения, получится
 * поток, который берёт декодер profile 8 — так делают Kodi, Vimu и Dune. Ничего не
 * перекодируется, меняются только метаданные (libdovi через ExoplayerHdrUtils, MIT).
 *
 * <p>В двухслойном MKV (обычный UHD BDRemux) слой улучшения и RPU лежат не в кадре, а в
 * BlockAdditional, который MatroskaExtractor выбрасывает, — его перехватывают
 * {@link Matroska} и {@link AssMatroska} через {@link BlockAdditionalHook}.
 * Строка codecs (dvhe.07 → dvhe.08), по которой Media3 выбирает декодер, меняется только после
 * того, как первый кадр действительно сконвертирован, и до этого ни один байт кадра плееру не
 * уходит. Любая неожиданность — кадр без RPU, нет libdovi для этого ABI, шифрование, странная
 * разбивка — и буфер отдаётся как был: дорожка идёт как раньше, дальше её может забрать
 * {@link DolbyVisionBaseLayer} (HDR10 базового слоя), который стоит снаружи.
 *
 * <p>Только там, где декодер устройства заявляет profile 8 (DvheSt). Из Just+ Player
 * (Dv7Converter, Unlicense); вместо ForwardingExtractorsFactory — обёртка одного разборщика,
 * потому что набор разборщиков у нас собирает AssSubtitles.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class Dv7Converter {

    private static final String TAG = "Dv7Converter";

    /** Matroska {@code BlockAddID} Media3 reads itself, for VP9 ITU-T T.35; left to the superclass. */
    private static final int BLOCK_ADDITIONAL_ID_VP9_ITU_T_35 = 4;
    /** HEVC NAL unit type carrying the Dolby Vision RPU. */
    private static final int NAL_UNIT_TYPE_RPU = 62;
    /** HEVC NAL unit type carrying the Dolby Vision enhancement layer picture. */
    private static final int NAL_UNIT_TYPE_ENHANCEMENT_LAYER = 63;
    /** Four-byte Annex-B start code, the framing Media3's extractors emit HEVC samples in. */
    private static final byte[] NAL_START_CODE = {0, 0, 0, 1};
    /** Sanity bound on one {@code BlockAdditional}, so a malformed file cannot size a buffer for us. */
    private static final int MAX_ENHANCEMENT_LAYER_BYTES = 8 * 1024 * 1024;
    /**
     * Sanity bound on one access unit. A UHD keyframe at Blu-ray bitrates is a few MB; past this the
     * accounting has gone wrong somewhere, and growing a direct buffer without limit on a 32-bit box —
     * which is what these files get played on — is how a player runs out of address space.
     */
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;


    /** Один на процесс: статус — для журнала и отчёта диагностики, решение HDR10+ — общее. */
    static final Dv7Converter INSTANCE = new Dv7Converter(/* dropHdr10Plus= */ true);

    /** Written on the loading thread by whichever track decided, read on the app thread for the dump. */
    @Nullable
    private volatile String status;

    /** The same verdict, short. */
    @Nullable
    private volatile String shortStatus;

    /** Cached answer to "does this device list a profile 8 decoder"; see deviceListsProfile8(). */
    @Nullable
    private static volatile Boolean profile8Listed;

    /**
     * Whether HDR10+ metadata is taken out along the way: a display handed both sets of dynamic
     * metadata has to choose between them, and some choose badly (так делает и эталонный плеер).
     */
    private final boolean dropHdr10Plus;

    private Dv7Converter(boolean dropHdr10Plus) {
        this.dropHdr10Plus = dropHdr10Plus;
    }

    /** Что стало с дорожкой Dolby Vision profile 7 последнего файла; null — такой не было. */
    @Nullable
    public static String status() {
        return INSTANCE.status;
    }

    /** То же коротко. */
    @Nullable
    public static String shortStatus() {
        return INSTANCE.shortStatus;
    }

    /** Новый файл — прежний вердикт к нему не относится. */
    public static void resetStatus() {
        INSTANCE.status = null;
        INSTANCE.shortStatus = null;
    }

    /**
     * Обёртка одного разборщика: у каждого своё состояние — Media3 держит набор разборщиков на
     * каждый период, и в плейлисте живых периодов больше одного. Для MKV разборщик должен быть
     * {@link Matroska} / {@link AssMatroska}, иначе двухслойный файл
     * останется как был (RPU лежит в BlockAdditional).
     */
    public static Extractor wrap(Extractor extractor) {
        return new Dv7Extractor(extractor, INSTANCE);
    }

    /** Owns the state of one read of one media item: the track outputs it created. */
    private static final class Dv7Extractor extends ForwardingExtractor {

        private final Dv7Converter owner;
        private final List<Dv7TrackOutput> outputs = new ArrayList<>();

        Dv7Extractor(Extractor delegate, Dv7Converter owner) {
            super(delegate);
            this.owner = owner;
        }

        @Override
        public void init(ExtractorOutput output) {
            super.init(new Dv7ExtractorOutput(output, this));
        }

        @Override
        public void seek(long position, long timeUs) {
            // Whatever of the current access unit was read belongs to a sample that will never be
            // committed, and its RPU to a frame that is no longer coming.
            for (int i = 0; i < outputs.size(); i++) {
                outputs.get(i).discardPartialSample();
            }
            super.seek(position, timeUs);
        }

        TrackOutput wrapVideoTrack(TrackOutput trackOutput) {
            final Dv7TrackOutput wrapped = new Dv7TrackOutput(trackOutput, owner);
            outputs.add(wrapped);
            return wrapped;
        }
    }

    /** Intercepts the video track output; every other track is left with the player's own. */
    private static final class Dv7ExtractorOutput extends ForwardingExtractorOutput {

        private final Dv7Extractor extractor;

        Dv7ExtractorOutput(ExtractorOutput output, Dv7Extractor extractor) {
            super(output);
            this.extractor = extractor;
        }

        @Override
        public TrackOutput track(int id, int type) {
            final TrackOutput output = super.track(id, type);
            return type == C.TRACK_TYPE_VIDEO ? extractor.wrapVideoTrack(output) : output;
        }
    }

    /**
     * Слой улучшения из Matroska BlockAdditional: из него нужен только RPU. Media3 читает Block
     * группы первым и отдаёт кадр в конце группы, так что RPU всегда на руках до кадра, к которому
     * относится. Уходит прямо в выход своей дорожки — дорожки видео не читают метаданные друг друга.
     * Зовут подклассы MatroskaExtractor из handleBlockAdditionalData с полями её Track (сам класс
     * Track доступен только подклассам).
     */
    public static final class BlockAdditionalHook {

        private byte[] enhancementLayer = new byte[0];
        private final int[] rpuRange = new int[2];

        /** @return false — не наш случай, звать реализацию родителя (байты не тронуты). */
        public boolean handle(@Nullable TrackOutput trackOutput, int nalUnitLengthFieldLength,
                              int blockAdditionalId, ExtractorInput input, int contentSize)
                throws IOException {
            final Dv7TrackOutput output = trackOutput instanceof Dv7TrackOutput
                    ? (Dv7TrackOutput) trackOutput
                    : null;
            if (output == null || !output.isConverting()
                    || blockAdditionalId == BLOCK_ADDITIONAL_ID_VP9_ITU_T_35
                    || contentSize <= 0 || contentSize > MAX_ENHANCEMENT_LAYER_BYTES) {
                return false;
            }
            if (enhancementLayer.length < contentSize) {
                enhancementLayer = new byte[contentSize];
            }
            input.readFully(enhancementLayer, 0, contentSize);
            // Only the RPU is wanted; any block addition is tried rather than one hardcoded id, since
            // findRpu only accepts a payload that tiles exactly as length-prefixed NAL units and really
            // carries type 62.
            if (findRpu(enhancementLayer, contentSize, nalUnitLengthFieldLength, rpuRange)) {
                output.setPendingRpu(enhancementLayer, rpuRange[0], rpuRange[1]);
            }
            return true;
        }
    }

    /**
     * Buffers each access unit, splices in the enhancement layer's RPU, and hands the frame to libdovi to
     * be rewritten as profile 8.1.
     *
     * <p>Implements {@link TrackOutput} directly rather than extending {@code ForwardingTrackOutput}:
     * that class forwards the two convenience overloads straight to its delegate instead of to itself,
     * and those are the ones Media3's extractors mostly call for sample data — a subclass of it would
     * never see most of a frame. Implementing the interface makes its default methods dispatch here.
     *
     * <p>Sample data arrives in parts: the picture itself, and — for a Matroska block that has any block
     * additions at all — a supplemental length prefix alongside it. Main bytes go into the direct buffer
     * libdovi rewrites in place, everything else goes aside, and the arrival order is recorded so a
     * bail-out can replay the sample byte for byte.
     *
     * <p>All of it runs on the loading thread, one sample at a time.
     */
    private static final class Dv7TrackOutput implements TrackOutput {

        /** Big enough for a UHD access unit without regrowing on every early frame. */
        private static final int INITIAL_FRAME_CAPACITY = 512 * 1024;
        /** Slack past the access unit: the spliced RPU and libdovi's in-place rewrite both need room. */
        private static final int FRAME_HEADROOM = 64 * 1024;
        /** A real sample is one to three runs; past this the framing is not something to guess at. */
        private static final int MAX_RUNS = 64;

        private final TrackOutput delegate;
        private final Dv7Converter owner;
        private final ParsableByteArray view = new ParsableByteArray();

        private boolean decided;
        private boolean converting;
        private boolean codecsRewritten;
        /** True once an RPU has come from a block addition, i.e. this is a dual-layer file. */
        private boolean dualLayer;
        @Nullable
        private HevcFrameTransformer transformer;
        @Nullable
        private Format publishedFormat;

        /** The picture's own bytes. Direct, because libdovi rewrites them in place through JNI. */
        @Nullable
        private ByteBuffer frame;
        /** Bytes of every other sample data part, in arrival order. */
        private byte[] side = new byte[0];
        private int sideLength;
        private int[] runPart = new int[4];
        private int[] runLength = new int[4];
        private int runCount;

        private byte[] transfer = new byte[0];
        /** True while {@link #transfer} holds this sample's main bytes as they arrived, before the rewrite. */
        private boolean mainSnapshot;
        private byte[] pendingRpu = new byte[0];
        private int pendingRpuLength;

        Dv7TrackOutput(TrackOutput delegate, Dv7Converter owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        boolean isConverting() {
            return converting;
        }

        /** Takes the RPU found in the enhancement layer of the block being read, framed as a NAL unit. */
        void setPendingRpu(byte[] data, int offset, int length) {
            final int framed = NAL_START_CODE.length + length;
            if (pendingRpu.length < framed) {
                pendingRpu = new byte[framed];
            }
            System.arraycopy(NAL_START_CODE, 0, pendingRpu, 0, NAL_START_CODE.length);
            System.arraycopy(data, offset, pendingRpu, NAL_START_CODE.length, length);
            pendingRpuLength = framed;
            dualLayer = true;
        }

        /** Drops whatever of the current access unit was read; its sample will never be committed. */
        void discardPartialSample() {
            pendingRpuLength = 0;
            resetBuffers();
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public void format(Format format) {
            if (!decided) {
                decided = true;
                // Encrypted tracks are excluded outright: their samples carry encryption parts and
                // subsample maps that a bitstream rewrite would invalidate.
                converting = isDolbyVisionProfile7(format)
                        && format.drmInitData == null
                        && deviceListsProfile8()
                        && (transformer = newTransformer(owner.dropHdr10Plus)) != null;
                if (isDolbyVisionProfile7(format) && !converting) {
                    owner.status = "profile 7, unchanged (no profile 8 decoder or no libdovi here)";
                    owner.shortStatus = "DV 7 → HDR10 · no P8 decoder";
                    Log.i(TAG, owner.status);
                }
            }
            publishedFormat = format;
            // The profile lives in the codec string and that is what the decoder is chosen by, so it is
            // rewritten only once a frame has really converted: a track that never converts keeps
            // advertising exactly what the container said. Re-applied to later formats so a mid-stream
            // change cannot put profile 7 back, but only to a format that is still profile 7 — such a
            // change may be to something else entirely, and rewriting that codec string would invent a
            // codec nothing can decode.
            delegate.format(codecsRewritten && isDolbyVisionProfile7(format)
                    ? format.buildUpon().setCodecs(toProfile8(format.codecs)).build()
                    : format);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            if (!converting) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            // Room is claimed before the read, since on refusal the bytes have to go straight to the
            // player and the staging array is the one replayBuffered() writes through.
            if (!claim(sampleDataPart, length)) {
                replayBuffered();
                giveUp("profile 7, unchanged (sample larger than this can rewrite)");
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            if (transfer.length < length) {
                transfer = new byte[length];
            }
            final int read = input.read(transfer, 0, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) {
                    return C.RESULT_END_OF_INPUT;
                }
                throw new EOFException();
            }
            store(sampleDataPart, transfer, 0, read);
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            if (!converting) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            if (!claim(sampleDataPart, length)) {
                replayBuffered();
                giveUp("profile 7, unchanged (sample larger than this can rewrite)");
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            if (sampleDataPart == SAMPLE_DATA_PART_MAIN) {
                data.readBytes(frame, length);
            } else {
                data.readBytes(side, sideLength, length);
                sideLength += length;
            }
            addRun(sampleDataPart, length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            final int rpuLength = pendingRpuLength;
            pendingRpuLength = 0;
            if (!converting) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            final int mainLength = frame == null ? 0 : frame.position();
            if (cryptoData != null || offset != 0 || mainLength == 0 || mainLength + sideLength != size) {
                // Either the sample is encrypted, or its bytes did not all come through this path (offset
                // counts bytes written after the sample). Rewriting on top of either would put the sample
                // queue out of step with itself, so the track goes back to being forwarded — one sample's
                // worth of doubt is not worth a corrupted stream.
                replayBuffered();
                giveUp("profile 7, unchanged (sample framing not rewritable)");
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            if (dualLayer && rpuLength == 0) {
                // A dual-layer file whose frame arrived without an enhancement layer. Converting the rest
                // would leave a profile 8 stream with holes in its metadata, which is worse for the
                // display than a stream that was never converted at all.
                replayBuffered();
                giveUp("profile 7, unchanged (enhancement layer missing on a frame)");
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            if (rpuLength > 0) {
                // The RPU belongs at the end of the access unit, after the picture's own NAL units. It is
                // appended without recording a run: these bytes are this class's own, not the sample's, so
                // a bail-out still replays exactly what the extractor wrote.
                if (!claim(SAMPLE_DATA_PART_MAIN, rpuLength)) {
                    replayBuffered();
                    giveUp("profile 7, unchanged (sample larger than this can rewrite)");
                    delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                    return;
                }
                frame.put(pendingRpu, 0, rpuLength);
            }
            final ByteBuffer buffer = frame;
            final int length = buffer.position();
            // Dropping the enhancement layer and libdovi both rewrite the buffer in place, so the sample is
            // taken out of it verbatim first and every bail-out below replays it from that copy.
            ((Buffer) buffer).position(0);
            copyFrameOut(length);
            mainSnapshot = true;
            ((Buffer) buffer).clear();
            final int base = copyWithoutEnhancementLayer(transfer, length, buffer);
            if (base < 0) {
                // No RPU anywhere in the access unit, so there is no profile 7 metadata here to rewrite.
                // libdovi would not have said so: with no RPU to parse it settles on "no transform needed"
                // and hands the frame back at its original size, which on its own is indistinguishable from
                // a conversion.
                replayBuffered();
                giveUp("profile 7, unchanged (no Dolby Vision RPU in the stream)");
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            final int converted;
            try {
                ((Buffer) buffer).limit(base).position(0);
                converted = transformer.transformFrame(buffer, base);
                ((Buffer) buffer).limit(buffer.capacity());
                if (converted <= 0 || converted > buffer.capacity()) {
                    throw new IllegalStateException("dolby vision rewrite returned " + converted);
                }
            } catch (RuntimeException | LinkageError e) {
                // libdovi could not make sense of the frame. The sample is replayed as it came in and the
                // track goes back to what it was.
                ((Buffer) buffer).limit(buffer.capacity());
                replayBuffered();
                giveUp("profile 7, unchanged (the Dolby Vision rewrite refused the frame)");
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            if (!codecsRewritten) {
                // The first frame that really converted. Only now is it honest to advertise profile 8, and
                // no sample byte has reached the player yet, so the decoder is opened on the final format.
                codecsRewritten = true;
                if (publishedFormat != null && isDolbyVisionProfile7(publishedFormat)) {
                    delegate.format(publishedFormat.buildUpon()
                            .setCodecs(toProfile8(publishedFormat.codecs)).build());
                }
                owner.status = "profile 7 → 8.1 ("
                        + (dualLayer ? "RPU from the enhancement layer" : "in-band RPU")
                        + (length > base ? ", enhancement layer dropped" : "") + ")";
                owner.shortStatus = "DV 7 → 8.1 · " + (dualLayer ? "RPU from EL" : "in-band RPU");
                Log.i(TAG, owner.status);
            }
            ((Buffer) buffer).position(0);
            copyFrameOut(converted);
            view.reset(transfer, converted);
            delegate.sampleData(view, converted, SAMPLE_DATA_PART_MAIN);
            // The whole sample was just written and nothing follows it, so its offset is zero whatever the
            // extractor's own accounting was. The supplemental flag goes with the bytes it described.
            delegate.sampleMetadata(timeUs, flags & ~C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, converted,
                    /* offset= */ 0, cryptoData);
            resetBuffers();
        }

        /** Reserves room for {@code length} more bytes of {@code part}; false once the sample is absurd. */
        private boolean claim(int part, int length) {
            if (length < 0) {
                return false;
            }
            if (runCount >= MAX_RUNS && runPart[runCount - 1] != part) {
                return false;
            }
            if (part != SAMPLE_DATA_PART_MAIN) {
                if ((long) sideLength + length > MAX_ENHANCEMENT_LAYER_BYTES) {
                    return false;
                }
                if (side.length < sideLength + length) {
                    side = Arrays.copyOf(side, Math.max(sideLength + length, 1024));
                }
                return true;
            }
            final int used = frame == null ? 0 : frame.position();
            if ((long) used + length > MAX_FRAME_BYTES) {
                return false;
            }
            if (frame == null || frame.capacity() < used + length) {
                final ByteBuffer grown = ByteBuffer.allocateDirect(
                        Math.max(used + length + FRAME_HEADROOM, INITIAL_FRAME_CAPACITY));
                if (frame != null) {
                    ((Buffer) frame).position(0).limit(used);
                    grown.put(frame);
                }
                frame = grown;
            }
            ((Buffer) frame).limit(frame.capacity());
            return true;
        }

        /** Appends bytes whose room {@link #claim} already reserved. */
        private void store(int part, byte[] data, int offset, int length) {
            if (part == SAMPLE_DATA_PART_MAIN) {
                frame.put(data, offset, length);
            } else {
                System.arraycopy(data, offset, side, sideLength, length);
                sideLength += length;
            }
            addRun(part, length);
        }

        private void addRun(int part, int length) {
            if (length <= 0) {
                return;
            }
            if (runCount > 0 && runPart[runCount - 1] == part) {
                runLength[runCount - 1] += length;
                return;
            }
            if (runCount == runPart.length) {
                runPart = Arrays.copyOf(runPart, runCount * 2);
                runLength = Arrays.copyOf(runLength, runCount * 2);
            }
            runPart[runCount] = part;
            runLength[runCount] = length;
            runCount++;
        }

        /** Hands the player everything buffered, in the parts and the order it arrived in. */
        private void replayBuffered() {
            if (runCount == 0) {
                resetBuffers();
                return;
            }
            // transfer already holds the sample's main bytes when the rewrite took its copy of them.
            final int mainLength = mainSnapshot || frame == null ? 0 : frame.position();
            if (mainLength > 0) {
                ((Buffer) frame).limit(frame.capacity()).position(0);
                copyFrameOut(mainLength);
            }
            int mainPosition = 0;
            int sidePosition = 0;
            for (int i = 0; i < runCount; i++) {
                final int length = runLength[i];
                if (runPart[i] == SAMPLE_DATA_PART_MAIN) {
                    view.reset(transfer, mainPosition + length);
                    view.setPosition(mainPosition);
                    mainPosition += length;
                } else {
                    view.reset(side, sidePosition + length);
                    view.setPosition(sidePosition);
                    sidePosition += length;
                }
                delegate.sampleData(view, length, runPart[i]);
            }
            resetBuffers();
        }

        /** Copies out of the direct buffer, which has no array to hand over in place. */
        private void copyFrameOut(int length) {
            if (transfer.length < length) {
                transfer = new byte[length];
            }
            frame.get(transfer, 0, length);
        }

        private void resetBuffers() {
            if (frame != null) {
                ((Buffer) frame).clear();
            }
            sideLength = 0;
            runCount = 0;
            mainSnapshot = false;
        }

        /** Stops converting for good and says why, unless a conversion has already been advertised. */
        private void giveUp(String reason) {
            converting = false;
            transformer = null;
            frame = null;
            side = new byte[0];
            sideLength = 0;
            runCount = 0;
            if (!codecsRewritten) {
                owner.status = reason;
                // Every reason to stop is one line in the panel: which frame it choked on is a question
                // for the dump, and what the viewer sees is the same picture either way.
                owner.shortStatus = "DV 7 → HDR10 · rewrite refused";
                Log.i(TAG, reason);
            }
        }
    }

    /**
     * @param dropHdr10Plus leave HDR10+ out of the rewritten frame, which is what the reference player
     *     does while it is playing Dolby Vision: a display handed both sets of dynamic metadata has to
     *     choose between them, and some choose badly. Off, the file's own metadata is passed through and
     *     the display decides — see the viewer asking for exactly that choice in issue #166.
     */
    @Nullable
    private static HevcFrameTransformer newTransformer(boolean dropHdr10Plus) {
        try {
            // FEL and MEL alike become profile 8.1.
            return new HevcFrameTransformer(new TransformStrategy(
                    DoviStrategy.CONVERT_TO_P8, DoviStrategy.CONVERT_TO_P8,
                    dropHdr10Plus ? Hdr10PlusStrategy.DISCARD : Hdr10PlusStrategy.KEEP));
        } catch (LinkageError e) {
            // No libdovi for this ABI (it ships for arm64-v8a, armeabi-v7a and x86_64 only). Playback
            // carries on exactly as it did before this class existed.
            return null;
        }
    }

    /**
     * Whether a decoder on this device lists Dolby Vision profile 8, which is what a rewritten track is
     * offered as. A device that lists only profile 5, only profile 7, or no profiles at all counts as a
     * no: it plays these files today as HDR10, and a codec string nothing matches would leave it with no
     * video at all.
     */
    private static boolean deviceListsProfile8() {
        final Boolean cached = profile8Listed;
        if (cached != null) {
            return cached;
        }
        boolean supported = false;
        try {
            for (MediaCodecInfo info : MediaCodecUtil.getDecoderInfos(
                    MimeTypes.VIDEO_DOLBY_VISION, /* secure= */ false, /* tunneling= */ false)) {
                for (CodecProfileLevel profileLevel : info.getProfileLevels()) {
                    supported |= profileLevel.profile == CodecProfileLevel.DolbyVisionProfileDvheSt;
                }
            }
        } catch (MediaCodecUtil.DecoderQueryException | RuntimeException e) {
            supported = false;
        }
        profile8Listed = supported;
        return supported;
    }

    static boolean isDolbyVisionProfile7(Format format) {
        return MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)
                && format.codecs != null
                && (format.codecs.startsWith("dvhe.07") || format.codecs.startsWith("dvh1.07"));
    }

    /** {@code dvhe.07.06} → {@code dvhe.08.06}, leaving the level and the sample entry prefix alone. */
    private static String toProfile8(String codecs) {
        return codecs.substring(0, 5) + "08" + codecs.substring(7);
    }

    /**
     * Copies an Annex-B access unit into {@code target} with the Dolby Vision enhancement layer left out.
     *
     * <p>libdovi rewrites the RPU and nothing else, so an enhancement layer that arrived in band — which is
     * how a remux carries it when it is not in a Matroska block addition — would survive into a stream the
     * codec string calls profile 8.1. That profile is single-layer by definition, and a device handed a
     * dual-layer stream under its name keeps it out of the Dolby Vision pipeline and shows HDR10: the very
     * symptom this class exists to fix. Losing what the enhancement layer carried is what a profile 7 → 8.1
     * conversion is; the base layer and the rewritten RPU are the picture.
     *
     * @return the number of bytes written, or -1 if this is not Annex-B framing it can walk, or the access
     *     unit carries no RPU at all — nothing here to convert, so the caller must leave the track alone.
     */
    static int copyWithoutEnhancementLayer(byte[] source, int length, ByteBuffer target) {
        int unit = startCodeAt(source, 0, length);
        if (unit != 0) {
            return -1;
        }
        boolean rpuSeen = false;
        int written = 0;
        while (unit < length) {
            final int payload = unit + (source[unit + 2] == 1 ? 3 : 4);
            if (payload >= length) {
                // A start code with no unit behind it. Not something to interpret; kept as it came.
                target.put(source, unit, length - unit);
                written += length - unit;
                break;
            }
            final int next = startCodeAt(source, payload, length);
            final int type = (source[payload] >> 1) & 0x3F;
            if (type != NAL_UNIT_TYPE_ENHANCEMENT_LAYER) {
                target.put(source, unit, next - unit);
                written += next - unit;
                rpuSeen |= type == NAL_UNIT_TYPE_RPU;
            }
            unit = next;
        }
        return rpuSeen ? written : -1;
    }

    /**
     * Offset of the Annex-B start code at or after {@code from}, or {@code limit} if there is none. A fourth
     * leading zero byte counts as part of the start code, which is the form Media3's extractors emit;
     * three-byte codes are walked all the same.
     */
    private static int startCodeAt(byte[] data, int from, int limit) {
        for (int i = from; i + 2 < limit; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                return i > from && data[i - 1] == 0 ? i - 1 : i;
            }
        }
        return limit;
    }

    /**
     * Finds the RPU in an enhancement layer block, reporting its offset and length through {@code range}.
     * The block holds NAL units prefixed with their length, in the same field width the track uses; a run
     * that does not tile the block exactly is not one, and is given up on rather than guessed at.
     */
    static boolean findRpu(byte[] data, int limit, int nalUnitLengthFieldLength, int[] range) {
        if (nalUnitLengthFieldLength <= 0 || nalUnitLengthFieldLength > 4 || limit < 0
                || limit > data.length) {
            return false;
        }
        int rpuStart = -1;
        int rpuLength = 0;
        int position = 0;
        while (position + nalUnitLengthFieldLength <= limit) {
            long length = 0;
            for (int i = 0; i < nalUnitLengthFieldLength; i++) {
                length = (length << 8) | (data[position + i] & 0xFF);
            }
            position += nalUnitLengthFieldLength;
            // A NAL unit is at least its two header bytes, and a four-byte length field can hold a number
            // far larger than the block — read as a long so this check cannot itself overflow.
            if (length < 2 || position + length > limit) {
                return false;
            }
            if (((data[position] >> 1) & 0x3F) == NAL_UNIT_TYPE_RPU) {
                rpuStart = position;
                rpuLength = (int) length;
            }
            position += (int) length;
        }
        if (position != limit || rpuStart < 0) {
            return false;
        }
        range[0] = rpuStart;
        range[1] = rpuLength;
        return true;
    }

    /**
     * MatroskaExtractor, который отдаёт RPU из слоя улучшения (BlockAdditional) конвертеру —
     * стоковый эти байты выбрасывает. На Java, а не Kotlin: track бывает null (блок дорожки,
     * которую Media3 не добавила), а Kotlin на такой параметр поставил бы проверку и упал.
     */
    public static final class Matroska extends MatroskaExtractor {

        private final BlockAdditionalHook hook = new BlockAdditionalHook();

        public Matroska(SubtitleParser.Factory subtitleParserFactory) {
            super(subtitleParserFactory, /* flags= */ 0);
        }

        @Override
        protected void handleBlockAdditionalData(@Nullable Track track, int blockAdditionalId,
                                                 ExtractorInput input, int contentSize) throws IOException {
            if (track == null || !hook.handle(track.output, track.nalUnitLengthFieldLength,
                    blockAdditionalId, input, contentSize)) {
                super.handleBlockAdditionalData(track, blockAdditionalId, input, contentSize);
            }
        }
    }

    /** То же для разборщика с ASS-субтитрами (libass). */
    public static final class AssMatroska extends AssMatroskaExtractor {

        private final BlockAdditionalHook hook = new BlockAdditionalHook();

        public AssMatroska(SubtitleParser.Factory subtitleParserFactory, AssHandler assHandler) {
            super(subtitleParserFactory, assHandler, /* flags= */ 0);
        }

        @Override
        protected void handleBlockAdditionalData(@Nullable Track track, int blockAdditionalId,
                                                 ExtractorInput input, int contentSize) throws IOException {
            if (track == null || !hook.handle(track.output, track.nalUnitLengthFieldLength,
                    blockAdditionalId, input, contentSize)) {
                super.handleBlockAdditionalData(track, blockAdditionalId, input, contentSize);
            }
        }
    }
}
