package asg.jcodec.movtool.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import asg.jcodec.common.NIOUtils;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Retrieves chunks for the range concurrently allowing for concurrent
 * transcode-on-the-fly
 * 
 * @author The JCodec project
 * 
 */
public class ConcurrentMovieRangeService {
    private ExecutorService exec;
    private VirtualMovie movie;

    public ConcurrentMovieRangeService(VirtualMovie movie, int nThreads) {
        this.exec = Executors.newFixedThreadPool(nThreads, new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                thread.setDaemon(true);
                return thread;
            }
        });
        this.movie = movie;
    }

    public void shutdown() {
        exec.shutdown();
    }

    public InputStream getRange(long from, long to) throws IOException {
        return new ConcurrentMovieRange(from, to);
    }

    static class GetCallable implements Callable<ByteBuffer> {
        private MovieSegment segment;

        public GetCallable(MovieSegment segment) {
            this.segment = segment;
        }

        public ByteBuffer call() throws Exception {
            ByteBuffer bb = segment.getData().duplicate();
            checkDataLen(bb, segment.getDataLen());
            return bb;
        }
    }

    public class ConcurrentMovieRange extends InputStream {
        private static final int READ_AHEAD_SEGMENTS = 10;
        private List<Future<ByteBuffer>> segments = new ArrayList<Future<ByteBuffer>>();
        private int nextReadAheadNo;
        private long remaining;
        private long to;

        public ConcurrentMovieRange(long from, long to) throws IOException {
            if (to < from)
                throw new IllegalArgumentException("from < to");

            this.remaining = to - from + 1;
            this.to = to;

            MovieSegment segment = movie.getPacketAt(from);
            if (segment != null) {

                nextReadAheadNo = segment.getNo();
                scheduleSegmentRetrieve(segment);

                for (int i = 0; i < READ_AHEAD_SEGMENTS; i++)
                    tryReadAhead();

                ByteBuffer data = segmentData();
                NIOUtils.skip(data, (int) (from - segment.getPos()));
            }
        }

        @Override
        public int read(byte[] b, int from, int len) throws IOException {
            if (segments.size() == 0 || remaining == 0)
                return -1;

            len = (int) Math.min(len, remaining);
            int totalRead = 0;
            while (len > 0 && segments.size() > 0) {
                ByteBuffer segmentData = segmentData();
                int toRead = Math.min(segmentData.remaining(), len);

                segmentData.get(b, from, toRead);
                totalRead += toRead;
                len -= toRead;
                from += toRead;

                disposeReadAhead(segmentData);
            }
            remaining -= totalRead;

            return totalRead;
        }

        private void disposeReadAhead(ByteBuffer segmentData) {
            if (!segmentData.hasRemaining()) {
                segments.remove(0);
                tryReadAhead();
            }
        }

        private void tryReadAhead() {
            MovieSegment segment = movie.getPacketByNo(nextReadAheadNo);
            if (segment != null && segment.getPos() < to) {
                scheduleSegmentRetrieve(segment);
            }
        }

        private void scheduleSegmentRetrieve(MovieSegment segment) {
            Future<ByteBuffer> submit = exec.submit(new GetCallable(segment));
            segments.add(submit);
            nextReadAheadNo++;
        }

        private ByteBuffer segmentData() throws IOException {
            ByteBuffer segmentData;
            try {
                segmentData = segments.get(0).get();
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
            return segmentData;
        }

        @Override
        public void close() throws IOException {
            for (Future<ByteBuffer> future : segments) {
                future.cancel(false);
            }
        }

        @Override
        public int read() throws IOException {
            if (segments.size() == 0 || remaining == 0)
                return -1;

            ByteBuffer segmentData = segmentData();
            int ret = segmentData.get() & 0xff;

            disposeReadAhead(segmentData);

            --remaining;

            return ret;
        }
    }

    private static void checkDataLen(ByteBuffer chunkData, int chunkDataLen) throws IOException {
        if (chunkData.remaining() != chunkDataLen) {
            System.err.println("WARN: packet expected data len != actual data len " + chunkDataLen + " != "
                    + chunkData.remaining());
        }
    }
}