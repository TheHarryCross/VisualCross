package asg.jcodec.containers.mp4.muxer;

import static asg.jcodec.containers.mp4.TrackType.SOUND;
import static asg.jcodec.containers.mp4.TrackType.VIDEO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.sound.sampled.AudioFormat;

import asg.jcodec.common.NIOUtils;
import asg.jcodec.common.SeekableByteChannel;
import asg.jcodec.common.model.Size;
import asg.jcodec.containers.mp4.Brand;
import asg.jcodec.containers.mp4.MP4Util;
import asg.jcodec.containers.mp4.TrackType;
import asg.jcodec.containers.mp4.boxes.AudioSampleEntry;
import asg.jcodec.containers.mp4.boxes.Box;
import asg.jcodec.containers.mp4.boxes.EndianBox;
import asg.jcodec.containers.mp4.boxes.FileTypeBox;
import asg.jcodec.containers.mp4.boxes.FormatBox;
import asg.jcodec.containers.mp4.boxes.Header;
import asg.jcodec.containers.mp4.boxes.LeafBox;
import asg.jcodec.containers.mp4.boxes.MovieBox;
import asg.jcodec.containers.mp4.boxes.MovieHeaderBox;
import asg.jcodec.containers.mp4.boxes.NodeBox;
import asg.jcodec.containers.mp4.boxes.SampleEntry;
import asg.jcodec.containers.mp4.boxes.VideoSampleEntry;
import asg.jcodec.containers.mp4.boxes.EndianBox.Endian;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Creates MP4 file out of a set of samples
 * 
 * @author The JCodec project
 * 
 */
public class MP4Muxer {
    private List<AbstractMP4MuxerTrack> tracks = new ArrayList<AbstractMP4MuxerTrack>();
    private long mdatOffset;

    private int nextTrackId = 1;
    SeekableByteChannel out;

    public MP4Muxer(SeekableByteChannel output) throws IOException {
        this(output, Brand.MP4);
    }

    public MP4Muxer(SeekableByteChannel output, Brand brand) throws IOException {
        this(output, brand.getFileTypeBox());
    }

    public MP4Muxer(SeekableByteChannel output, FileTypeBox ftyp) throws IOException {
        this.out = output;

        ByteBuffer buf = ByteBuffer.allocate(1024);
        ftyp.write(buf);
        new Header("wide", 8).write(buf);
        new Header("mdat", 1).write(buf);
        mdatOffset = buf.position();
        buf.putLong(0);
        buf.flip();
        output.write(buf);
    }

    public FramesMP4MuxerTrack addVideoTrackWithTimecode(String fourcc, Size size, String encoderName, int timescale) {
        TimecodeMP4MuxerTrack timecode = addTimecodeTrack(timescale);

        FramesMP4MuxerTrack track = addTrackForCompressed(VIDEO, timescale);

        track.addSampleEntry(videoSampleEntry(fourcc, size, encoderName));
        track.setTimecode(timecode);

        return track;
    }

    public FramesMP4MuxerTrack addVideoTrack(String fourcc, Size size, String encoderName, int timescale) {
        FramesMP4MuxerTrack track = addTrackForCompressed(VIDEO, timescale);

        track.addSampleEntry(videoSampleEntry(fourcc, size, encoderName));
        return track;
    }

    public static VideoSampleEntry videoSampleEntry(String fourcc, Size size, String encoderName) {
        return new VideoSampleEntry(new Header(fourcc), (short) 0, (short) 0, "jcod", 0, 768, (short) size.getWidth(),
                (short) size.getHeight(), 72, 72, (short) 1, encoderName != null ? encoderName : "jcodec", (short) 24,
                (short) 1, (short) -1);
    }

    public static AudioSampleEntry audioSampleEntry(String fourcc, int drefId, int sampleSize, int channels,
            int sampleRate, Endian endian) {
        AudioSampleEntry ase = new AudioSampleEntry(new Header(fourcc, 0), (short) drefId, (short) channels,
                (short) 16, sampleRate, (short) 0, 0, 65535, 0, 1, sampleSize, channels * sampleSize, sampleSize,
                (short) 1);

        NodeBox wave = new NodeBox(new Header("wave"));
        ase.add(wave);

        wave.add(new FormatBox(fourcc));
        wave.add(new EndianBox(endian));
        wave.add(terminatorAtom());
        // ase.add(new ChannelBox(atom));

        return ase;
    }

    public static LeafBox terminatorAtom() {
        return new LeafBox(new Header(new String(new byte[4])), ByteBuffer.allocate(0));
    }

    public TimecodeMP4MuxerTrack addTimecodeTrack(int timescale) {
        TimecodeMP4MuxerTrack track = new TimecodeMP4MuxerTrack(out, nextTrackId++, timescale);
        tracks.add(track);
        return track;
    }

    public FramesMP4MuxerTrack addTrackForCompressed(TrackType type, int timescale) {
        FramesMP4MuxerTrack track = new FramesMP4MuxerTrack(out, nextTrackId++, type, timescale);
        tracks.add(track);
        return track;
    }

    public PCMMP4MuxerTrack addTrackForUncompressed(TrackType type, int timescale, int sampleDuration, int sampleSize,
            SampleEntry se) {
        PCMMP4MuxerTrack track = new PCMMP4MuxerTrack(out, nextTrackId++, type, timescale, sampleDuration, sampleSize, se);
        tracks.add(track);
        return track;
    }

    public List<AbstractMP4MuxerTrack> getTracks() {
        return tracks;
    }

    public void writeHeader() throws IOException {
        MovieBox movie = finalizeHeader();

        storeHeader(movie);
    }

    public void storeHeader(MovieBox movie) throws IOException {
        long mdatSize = out.position() - mdatOffset + 8;
        MP4Util.writeMovie(out, movie);

        out.position(mdatOffset);
        NIOUtils.writeLong(out, mdatSize);
    }

    public MovieBox finalizeHeader() throws IOException {
        MovieBox movie = new MovieBox();
        MovieHeaderBox mvhd = movieHeader(movie);
        movie.addFirst(mvhd);

        for (AbstractMP4MuxerTrack track : tracks) {
            Box trak = track.finish(mvhd);
            if (trak != null)
                movie.add(trak);
        }
        return movie;
    }

    public AbstractMP4MuxerTrack getVideoTrack() {
        for (AbstractMP4MuxerTrack frameMuxer : tracks) {
            if (frameMuxer.isVideo()) {
                return frameMuxer;
            }
        }
        return null;
    }

    public AbstractMP4MuxerTrack getTimecodeTrack() {
        for (AbstractMP4MuxerTrack frameMuxer : tracks) {
            if (frameMuxer.isTimecode()) {
                return frameMuxer;
            }
        }
        return null;
    }

    public List<AbstractMP4MuxerTrack> getAudioTracks() {
        ArrayList<AbstractMP4MuxerTrack> result = new ArrayList<AbstractMP4MuxerTrack>();
        for (AbstractMP4MuxerTrack frameMuxer : tracks) {
            if (frameMuxer.isAudio()) {
                result.add(frameMuxer);
            }
        }
        return result;
    }

    private MovieHeaderBox movieHeader(NodeBox movie) {
        int timescale = tracks.get(0).getTimescale();
        long duration = tracks.get(0).getTrackTotalDuration();
        AbstractMP4MuxerTrack videoTrack = getVideoTrack();
        if (videoTrack != null) {
            timescale = videoTrack.getTimescale();
            duration = videoTrack.getTrackTotalDuration();
        }

        return new MovieHeaderBox(timescale, duration, 1.0f, 1.0f, new Date().getTime(), new Date().getTime(),
                new int[] { 0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000 }, nextTrackId);
    }

    public static String lookupFourcc(AudioFormat format) {
        if (format.getSampleSizeInBits() == 16 && !format.isBigEndian())
            return "sowt";
        else if (format.getSampleSizeInBits() == 24)
            return "in24";
        else
            throw new IllegalArgumentException("Audio format " + format + " is not supported.");
    }

    public PCMMP4MuxerTrack addUncompressedAudioTrack(AudioFormat format) {
        return addTrackForUncompressed(SOUND, (int) format.getSampleRate(), 1, (format.getSampleSizeInBits() >> 3)
                * format.getChannels(), MP4Muxer.audioSampleEntry(lookupFourcc(format), 1,
                format.getSampleSizeInBits() >> 3, format.getChannels(), (int) format.getSampleRate(),
                format.isBigEndian() ? Endian.BIG_ENDIAN : Endian.LITTLE_ENDIAN));
    }

    public FramesMP4MuxerTrack addCompressedAudioTrack(String fourcc, int timescale, int channels, int sampleRate,
            int samplesPerPkt, Box... extra) {
        FramesMP4MuxerTrack track = addTrackForCompressed(SOUND, timescale);

        AudioSampleEntry ase = new AudioSampleEntry(new Header(fourcc, 0), (short) 1, (short) channels, (short) 16,
                sampleRate, (short) 0, 0, 65534, 0, samplesPerPkt, 0, 0, 2, (short) 1);

        NodeBox wave = new NodeBox(new Header("wave"));
        ase.add(wave);

        wave.add(new FormatBox(fourcc));
        for (Box box : extra)
            wave.add(box);

        wave.add(terminatorAtom());

        track.addSampleEntry(ase);

        return track;
    }
}