package asg.jcodec.codecs.h264.io.model;

import java.util.Comparator;

import asg.jcodec.common.model.ColorSpace;
import asg.jcodec.common.model.Picture;
import asg.jcodec.common.model.Rect;

/**
 * This class is part of JCodec ( www.jcodec.org ) This software is distributed
 * under FreeBSD License
 * 
 * Picture extension with frame number, makes it easier to debug reordering
 * 
 * @author The JCodec project
 * 
 */
public class Frame extends Picture {
    private int frameNo;
    private int[][][][] mvs;
    private Frame[][][] refsUsed; 
    private boolean shortTerm;
    private int poc;

    public Frame(int width, int height, int[][] data, ColorSpace color, Rect crop, int frameNo, int[][][][] mvs, Frame[][][] refsUsed, int poc) {
        super(width, height, data, color, crop);
        this.frameNo = frameNo;
        this.mvs = mvs;
        this.refsUsed = refsUsed;
        this.poc = poc;
        shortTerm = true;
    }

    public static Frame createFrame(Frame pic) {
        Picture comp = pic.createCompatible();
        return new Frame(comp.getWidth(), comp.getHeight(), comp.getData(), comp.getColor(), pic.getCrop(),
                pic.frameNo, pic.mvs, pic.refsUsed, pic.poc);
    }

    public Frame cropped() {
        Picture cropped = super.cropped();
        return new Frame(cropped.getWidth(), cropped.getHeight(), cropped.getData(), cropped.getColor(), null, frameNo, mvs, refsUsed, poc);
    }

    public void copyFrom(Frame src) {
        super.copyFrom(src);
        this.frameNo = src.frameNo;
        this.mvs = src.mvs;
        this.shortTerm = src.shortTerm;
        this.refsUsed = src.refsUsed;
        this.poc = src.poc;
    }

    public int getFrameNo() {
        return frameNo;
    }

    public int[][][][] getMvs() {
        return mvs;
    }

    public boolean isShortTerm() {
        return shortTerm;
    }

    public void setShortTerm(boolean shortTerm) {
        this.shortTerm = shortTerm;
    }

    public int getPOC() {
        return poc;
    }

    public static Comparator<Frame> POCAsc = new Comparator<Frame>() {
        public int compare(Frame o1, Frame o2) {
            if (o1 == null && o2 == null)
                return 0;
            else if (o1 == null)
                return 1;
            else if (o2 == null)
                return -1;
            else
                return o1.poc > o2.poc ? 1 : (o1.poc == o2.poc ? 0 : -1);
        }
    };

    public static Comparator<Frame> POCDesc = new Comparator<Frame>() {
        public int compare(Frame o1, Frame o2) {
            if (o1 == null && o2 == null)
                return 0;
            else if (o1 == null)
                return 1;
            else if (o2 == null)
                return -1;
            else
                return o1.poc < o2.poc ? 1 : (o1.poc == o2.poc ? 0 : -1);
        }
    };

    public Frame[][][] getRefsUsed() {
        return refsUsed;
    }
}