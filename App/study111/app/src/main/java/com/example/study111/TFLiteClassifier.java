package com.example.study111;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * TFLite classifier that:
 *  - Loads an INT8 (or FLOAT) Edge Impulse motion model
 *  - Expects 39-element feature vector (from metadata)
 *  - Can compute a placeholder feature vector from raw window [N][3]
 *    so the pipeline runs without crashing.
 *
 * Replace computeFeaturesFromWindow() with the real Edge Impulse DSP logic
 * once you share the processing block details.
 */
public class TFLiteClassifier {

    private static final String TAG = "TFLiteClassifier";

    private final Interpreter interpreter;

    // --- Model IO specs ---
    private final int[] inputShape;     // e.g. [1,39]
    private final DataType inputType;   // INT8 (per metadata)
    private final float inScale;
    private final int inZeroPoint;

    private final int[] outputShape;    // e.g. [1,2]
    private final DataType outputType;  // INT8 per metadata
    private final float outScale;
    private final int outZeroPoint;

    // Adjust label order to match your Edge Impulse project order if different.
    private final String[] labels = {"stationary", "pick_up"};

    public TFLiteClassifier(AssetManager assets, String modelAssetName) throws IOException {
        Interpreter.Options opts = new Interpreter.Options();
        interpreter = new Interpreter(loadModelFile(assets, modelAssetName), opts);

        // Inspect input tensor
        Tensor in = interpreter.getInputTensor(0);
        inputShape = in.shape();
        inputType  = in.dataType();
        inScale    = in.quantizationParams().getScale();
        inZeroPoint= in.quantizationParams().getZeroPoint();

        // Inspect output tensor
        Tensor out = interpreter.getOutputTensor(0);
        outputShape = out.shape();
        outputType  = out.dataType();
        outScale    = out.quantizationParams().getScale();
        outZeroPoint= out.quantizationParams().getZeroPoint();

        Log.d(TAG, "Input tensor shape: " + Arrays.toString(inputShape));
        Log.d(TAG, "Input tensor data type: " + inputType);
        Log.d(TAG, "Input quant params: scale=" + inScale + " zeroPoint=" + inZeroPoint);
        Log.d(TAG, "Output tensor shape: " + Arrays.toString(outputShape));
        Log.d(TAG, "Output tensor data type: " + outputType);
        Log.d(TAG, "Output quant params: scale=" + outScale + " zeroPoint=" + outZeroPoint);
    }

    private MappedByteBuffer loadModelFile(AssetManager am, String assetName) throws IOException {
        AssetFileDescriptor fd = am.openFd(assetName);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel fc = fis.getChannel();
        return fc.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    /* ------------------------------------------------------------------
     * Public API #1: supply raw accelerometer window [N][3]; we compute 39 features.
     * ------------------------------------------------------------------ */
    public Result predictFromWindow(float[][] windowXYZ) {
        float[] features = computeFeaturesFromWindow(windowXYZ); // length 39
        return predictFeatures(features);
    }

    /* ------------------------------------------------------------------
     * Public API #2: supply features directly (length must match model).
     * ------------------------------------------------------------------ */
    public Result predictFeatures(float[] features) {
        int featureCountExpected = inputShape[inputShape.length - 1]; // [1,39] => 39
        if (features.length != featureCountExpected) {
            throw new IllegalArgumentException(
                    "Expected " + featureCountExpected + " features, got " + features.length);
        }

        ByteBuffer inBuf = buildInputBuffer(features);

        // Run inference & return scores
        float[] scores = runInference(inBuf);

        // Pick top class
        int topIdx = 0;
        float topVal = scores[0];
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > topVal) { topVal = scores[i]; topIdx = i; }
        }
        String lbl = (topIdx < labels.length) ? labels[topIdx] : ("class_" + topIdx);
        return new Result(lbl, topVal, scores);
    }

    /* ------------------------------------------------------------------
     * Placeholder feature extraction (TEMP) — 39 features.
     * Replace with exact Edge Impulse DSP once you give config.
     *
     * Features used here (index order shown):
     *  0-2 mean(X,Y,Z)
     *  3-5 std(X,Y,Z)
     *  6-8 min(X,Y,Z)
     *  9-11 max(X,Y,Z)
     *  12-14 rms(X,Y,Z)
     *  15-17 skew(X,Y,Z)
     *  18-20 kurt(X,Y,Z)
     *  21 mean(|g| magnitude)
     *  22 std(|g|)
     *  23 min(|g|)
     *  24 max(|g|)
     *  25 rms(|g|)
     *  26 skew(|g|)
     *  27 kurt(|g|)
     *  28 corr(X,Y)
     *  29 corr(Y,Z)
     *  30 corr(X,Z)
     *  31 energy(X)
     *  32 energy(Y)
     *  33 energy(Z)
     *  34 energy(|g|)
     *  35 rangeX = max-min
     *  36 rangeY
     *  37 rangeZ
     *  38 rangeMag
     * ------------------------------------------------------------------ */
    private float[] computeFeaturesFromWindow(float[][] w) {
        if (w == null || w.length == 0) return new float[39];
        final int n = w.length;

        // accumulate
        double sumX=0,sumY=0,sumZ=0;
        double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,minZ=Double.MAX_VALUE;
        double maxX=-Double.MAX_VALUE,maxY=-Double.MAX_VALUE,maxZ=-Double.MAX_VALUE;

        double sumMag=0;
        double minMag=Double.MAX_VALUE,maxMag=-Double.MAX_VALUE;

        for (float[] s : w) {
            float x=s[0], y=s[1], z=s[2];
            sumX+=x; sumY+=y; sumZ+=z;
            if (x<minX)minX=x; if (y<minY)minY=y; if (z<minZ)minZ=z;
            if (x>maxX)maxX=x; if (y>maxY)maxY=y; if (z>maxZ)maxZ=z;
            double m=Math.sqrt(x*x+y*y+z*z);
            sumMag+=m;
            if (m<minMag)minMag=m;
            if (m>maxMag)maxMag=m;
        }
        double meanX=sumX/n, meanY=sumY/n, meanZ=sumZ/n;
        double meanMag=sumMag/n;

        // var, skew, kurt, energy
        double varX=0,varY=0,varZ=0,varMag=0;
        double skewX=0,skewY=0,skewZ=0,skewMag=0;
        double kurtX=0,kurtY=0,kurtZ=0,kurtMag=0;
        double energyX=0,energyY=0,energyZ=0,energyMag=0;
        double sumXY=0,sumYZ=0,sumXZ=0; // for correlation

        for (float[] s : w) {
            float x=s[0], y=s[1], z=s[2];
            double dx=x-meanX, dy=y-meanY, dz=z-meanZ;
            varX+=dx*dx; varY+=dy*dy; varZ+=dz*dz;
            skewX+=dx*dx*dx; skewY+=dy*dy*dy; skewZ+=dz*dz*dz;
            kurtX+=dx*dx*dx*dx; kurtY+=dy*dy*dy*dy; kurtZ+=dz*dz*dz*dz;
            energyX+=x*x; energyY+=y*y; energyZ+=z*z;

            double m=Math.sqrt(x*x+y*y+z*z);
            double dm=m-meanMag;
            varMag+=dm*dm;
            skewMag+=dm*dm*dm;
            kurtMag+=dm*dm*dm*dm;
            energyMag+=m*m;

            sumXY+=dx*dy;
            sumYZ+=dy*dz;
            sumXZ+=dx*dz;
        }
        varX/=n; varY/=n; varZ/=n; varMag/=n;
        double stdX=Math.sqrt(varX), stdY=Math.sqrt(varY), stdZ=Math.sqrt(varZ), stdMag=Math.sqrt(varMag);

        // Normalize skew/kurt
        if (stdX>0) { skewX/= (n*stdX*stdX*stdX); kurtX/= (n*varX*varX); }
        if (stdY>0) { skewY/= (n*stdY*stdY*stdY); kurtY/= (n*varY*varY); }
        if (stdZ>0) { skewZ/= (n*stdZ*stdZ*stdZ); kurtZ/= (n*varZ*varZ); }
        if (stdMag>0){ skewMag/=(n*stdMag*stdMag*stdMag); kurtMag/=(n*varMag*varMag); }

        // Correlations
        double corrXY = (stdX>0 && stdY>0) ? (sumXY/n)/(stdX*stdY) : 0;
        double corrYZ = (stdY>0 && stdZ>0) ? (sumYZ/n)/(stdY*stdZ) : 0;
        double corrXZ = (stdX>0 && stdZ>0) ? (sumXZ/n)/(stdX*stdZ) : 0;

        // RMS
        double rmsX=Math.sqrt(energyX/n);
        double rmsY=Math.sqrt(energyY/n);
        double rmsZ=Math.sqrt(energyZ/n);
        double rmsMag=Math.sqrt(energyMag/n);

        float[] f = new float[39];
        int i=0;
        f[i++]=(float)meanX; f[i++]=(float)meanY; f[i++]=(float)meanZ;
        f[i++]=(float)stdX;  f[i++]=(float)stdY;  f[i++]=(float)stdZ;
        f[i++]=(float)minX;  f[i++]=(float)minY;  f[i++]=(float)minZ;
        f[i++]=(float)maxX;  f[i++]=(float)maxY;  f[i++]=(float)maxZ;
        f[i++]=(float)rmsX;  f[i++]=(float)rmsY;  f[i++]=(float)rmsZ;
        f[i++]=(float)skewX; f[i++]=(float)skewY; f[i++]=(float)skewZ;
        f[i++]=(float)kurtX; f[i++]=(float)kurtY; f[i++]=(float)kurtZ;
        f[i++]=(float)meanMag;
        f[i++]=(float)stdMag;
        f[i++]=(float)minMag;
        f[i++]=(float)maxMag;
        f[i++]=(float)rmsMag;
        f[i++]=(float)skewMag;
        f[i++]=(float)kurtMag;
        f[i++]=(float)corrXY;
        f[i++]=(float)corrYZ;
        f[i++]=(float)corrXZ;
        f[i++]=(float)energyX;
        f[i++]=(float)energyY;
        f[i++]=(float)energyZ;
        f[i++]=(float)energyMag;
        f[i++]=(float)(maxX-minX);
        f[i++]=(float)(maxY-minY);
        f[i++]=(float)(maxZ-minZ);
        f[i++]=(float)(maxMag-minMag);
        // 39 filled.

        return f;
    }

    // Build input buffer for interpreter (handles INT8 vs float)
    private ByteBuffer buildInputBuffer(float[] features) {
        int numElements = 1;
        for (int s : inputShape) numElements *= s; // [1,39] => 39
        boolean quant = (inputType == DataType.UINT8 || inputType == DataType.INT8);
        int bytesPer = quant ? 1 : 4;
        ByteBuffer buf = ByteBuffer.allocateDirect(numElements * bytesPer);
        buf.order(ByteOrder.nativeOrder());

        if (quant) {
            int min = (inputType == DataType.INT8) ? -128 : 0;
            int max = (inputType == DataType.INT8) ? 127  : 255;
            for (int i=0;i<numElements;i++) {
                float v = (i<features.length) ? features[i] : 0f;
                int q = Math.round(v / inScale + inZeroPoint);
                if (q < min) q = min;
                if (q > max) q = max;
                buf.put((byte)q);
            }
        } else {
            for (int i=0;i<numElements;i++) {
                float v = (i<features.length) ? features[i] : 0f;
                buf.putFloat(v);
            }
        }
        buf.rewind();
        return buf;
    }

    // Run interpreter; always return float[] scores (dequantized if needed)
    private float[] runInference(ByteBuffer inBuf) {
        int numClasses = outputShape[outputShape.length - 1];
        float[] scores = new float[numClasses];

        if (outputType == DataType.FLOAT32) {
            float[][] out = new float[1][numClasses];
            interpreter.run(inBuf, out);
            System.arraycopy(out[0], 0, scores, 0, numClasses);
        } else {
            byte[][] outQ = new byte[1][numClasses];
            interpreter.run(inBuf, outQ);
            for (int i=0;i<numClasses;i++) {
                // outQ[][] is signed bytes for INT8
                int q = outQ[0][i];
                scores[i] = (q - outZeroPoint) * outScale;
            }
        }
        return scores;
    }

    public void close() { interpreter.close(); }

    // Result container
    public static class Result {
        public final String label;
        public final float confidence;
        public final float[] scores;
        Result(String l, float c, float[] s) { label=l; confidence=c; scores=s; }
    }
}
