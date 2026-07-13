package dev.chungjungsoo.gptmobile.data.memory.vector;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Memory16KbReleaseCompatibilityInstrumentedTest extends Instrumentation {
    private static final String PHASE_ARGUMENT = "phase";
    private static final String PHASE_ONE = "phase1";
    private static final String PHASE_TWO = "phase2";
    private static final String PHASE_ONE_CHECKPOINT_LOG = "MEMORY_16KB_PHASE1_READY";
    private static final String PHASE_TWO_CHECKPOINT_LOG = "MEMORY_16KB_PHASE2_OK";
    private static final long EXPECTED_PAGE_SIZE_BYTES = 16_384L;
    private static final long EXPECTED_MODEL_BYTES = 24_010_842L;
    private static final String EXPECTED_MODEL_SHA256 =
            "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc";
    private static final String MODEL_ASSET_PATH = "memory-model/bge-small-zh-v1.5/model.onnx";
    private static final String HARNESS_DIRECTORY_NAME = "memory_16kb_release_compatibility";
    private static final String STORE_DIRECTORY_NAME = "objectbox";
    private static final String MODEL_FILE_NAME = "bge-small-zh-v1.5-int8.onnx";
    private static final String PHASE_ONE_MARKER_NAME = "phase-one-ready";
    private static final String PHASE_ONE_MARKER_CONTENT = EXPECTED_MODEL_SHA256 + "\n";
    private static final String CHUNK_ID = "memory-16kb-release-canary";
    private static final String LOG_TAG = "Memory16KbGate";
    private static final int MEMORY_VECTOR_DIMENSION = 512;
    private static final float EMBEDDING_TOLERANCE = 1e-6f;
    private static final double NORMALIZATION_TOLERANCE = 1e-4;
    private static final long PROCESS_DEATH_TIMEOUT_MILLIS = 30_000L;

    private String phase;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        phase = arguments == null ? "" : arguments.getString(PHASE_ARGUMENT, "");
        start();
    }

    @Override
    public void onStart() {
        try {
            if (PHASE_ONE.equals(phase)) {
                runPhaseOne();
            } else if (PHASE_TWO.equals(phase)) {
                runPhaseTwo();
            } else {
                throw new AssertionError("Unknown 16 KB compatibility phase: " + phase);
            }
        } catch (Throwable throwable) {
            Log.e(LOG_TAG, phase + " failed", throwable);
            Bundle result = new Bundle();
            result.putString("shortMsg", throwable.toString());
            result.putString(REPORT_KEY_STREAMRESULT, "\n" + phase + " failed: " + throwable + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runPhaseOne() throws Exception {
        Context target = getTargetContext();
        assertRelease16KbTarget(target);
        resetHarness(target);

        File model = provisionVerifiedModel(target);
        float[] firstEmbedding = createEmbedding(model);
        MemoryVectorReleaseCompatibilityProbe.writeQueryAndClose(
                target,
                storeDirectory(target),
                CHUNK_ID,
                firstEmbedding
        );

        float[] reopenedEmbedding = createEmbedding(model);
        assertFloatArraysEqual(firstEmbedding, reopenedEmbedding);
        MemoryVectorReleaseCompatibilityProbe.queryAndClose(
                target,
                storeDirectory(target),
                CHUNK_ID,
                reopenedEmbedding
        );
        assertApkMappingsPresent(getContext(), target);
        writePhaseOneMarker(target);
        Log.i(LOG_TAG, PHASE_ONE_CHECKPOINT_LOG);

        Process.killProcess(Process.myPid());
        Thread.sleep(PROCESS_DEATH_TIMEOUT_MILLIS);
        throw new AssertionError("Process survived the 16 KB compatibility process-death checkpoint");
    }

    private void runPhaseTwo() throws Exception {
        Context target = getTargetContext();
        assertRelease16KbTarget(target);
        assertEquals(
                PHASE_ONE_MARKER_CONTENT,
                new String(Files.readAllBytes(markerFile(target).toPath()), StandardCharsets.UTF_8)
        );

        File model = modelFile(target);
        assertTrue("Provisioned model is missing after process restart", model.isFile());
        assertEquals(EXPECTED_MODEL_BYTES, model.length());
        assertEquals(EXPECTED_MODEL_SHA256, sha256(model));
        float[] restartedEmbedding = createEmbedding(model);
        MemoryVectorReleaseCompatibilityProbe.queryAndClose(
                target,
                storeDirectory(target),
                CHUNK_ID,
                restartedEmbedding
        );
        assertApkMappingsPresent(getContext(), target);
        resetHarness(target);
        Log.i(LOG_TAG, PHASE_TWO_CHECKPOINT_LOG);
        Bundle result = new Bundle();
        result.putString(REPORT_KEY_STREAMRESULT, "\n" + PHASE_TWO_CHECKPOINT_LOG + "\n");
        finish(Activity.RESULT_OK, result);
    }

    private static void assertRelease16KbTarget(Context context) {
        assertEquals(EXPECTED_PAGE_SIZE_BYTES, Os.sysconf(OsConstants._SC_PAGESIZE));
        assertTrue(
                "The target APK must be a non-debuggable release build",
                (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0
        );
    }

    private static File provisionVerifiedModel(Context targetContext) throws Exception {
        File destination = modelFile(targetContext);
        File temporary = new File(destination.getParentFile(), destination.getName() + ".download");
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new AssertionError("Unable to create ONNX canary directory");
        }
        if (temporary.exists() && !temporary.delete()) {
            throw new AssertionError("Unable to remove stale ONNX canary download");
        }

        try (InputStream input = targetContext.getAssets().open(MODEL_ASSET_PATH);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (Throwable throwable) {
            throw new AssertionError(
                    "Missing verified production ONNX asset; run " +
                            "tools/memory-model/provision-bge-small-zh-v1.5-production.ps1",
                    throwable
            );
        }

        assertEquals(EXPECTED_MODEL_BYTES, temporary.length());
        assertEquals(EXPECTED_MODEL_SHA256, sha256(temporary));
        if (destination.exists() && !destination.delete()) {
            throw new AssertionError("Unable to replace stale production ONNX model");
        }
        assertTrue("Unable to publish verified production ONNX model", temporary.renameTo(destination));
        assertEquals(EXPECTED_MODEL_SHA256, sha256(destination));
        return destination;
    }

    private static float[] createEmbedding(File model) throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
        try {
            OrtSession session = environment.createSession(model.getAbsolutePath(), sessionOptions);
            try {
                long[] inputIds = new long[]{101, 872, 1962, 102};
                long[] attentionMask = new long[inputIds.length];
                for (int index = 0; index < attentionMask.length; index++) {
                    attentionMask[index] = 1L;
                }
                long[] tokenTypeIds = new long[inputIds.length];
                long[] shape = new long[]{1L, inputIds.length};
                OnnxTensor inputIdTensor = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(inputIds),
                        shape
                );
                OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(attentionMask),
                        shape
                );
                OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(
                        environment,
                        LongBuffer.wrap(tokenTypeIds),
                        shape
                );
                try {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("input_ids", inputIdTensor);
                    inputs.put("attention_mask", attentionMaskTensor);
                    inputs.put("token_type_ids", tokenTypeTensor);
                    OrtSession.Result output = session.run(inputs);
                    try {
                        float[][][] hiddenState = (float[][][]) output.get(0).getValue();
                        float[] embedding = normalize(hiddenState[0][0]);
                        assertEquals(MEMORY_VECTOR_DIMENSION, embedding.length);
                        double norm = 0.0;
                        for (float value : embedding) {
                            assertTrue("Embedding contains a non-finite value", Float.isFinite(value));
                            norm += value * value;
                        }
                        assertTrue("Embedding is not normalized", Math.abs(norm - 1.0) < NORMALIZATION_TOLERANCE);
                        return embedding;
                    } finally {
                        output.close();
                    }
                } finally {
                    tokenTypeTensor.close();
                    attentionMaskTensor.close();
                    inputIdTensor.close();
                }
            } finally {
                session.close();
            }
        } finally {
            sessionOptions.close();
        }
    }

    private static void assertFloatArraysEqual(float[] expected, float[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            if (Math.abs(expected[index] - actual[index]) > EMBEDDING_TOLERANCE) {
                throw new AssertionError("Embedding differs at index " + index);
            }
        }
    }

    private static void assertApkMappingsPresent(Context testContext, Context targetContext) throws Exception {
        List<String> maps = Files.readAllLines(new File("/proc/self/maps").toPath(), StandardCharsets.UTF_8);
        String targetMapping = firstOwnedMapping(maps, targetContext.getApplicationInfo());
        String testMapping = firstOwnedMapping(maps, testContext.getApplicationInfo());
        Log.i(LOG_TAG, "Release target APK mapping: " + targetMapping);
        Log.i(LOG_TAG, "Instrumentation companion APK mapping: " + testMapping);
    }

    private static String firstOwnedMapping(List<String> lines, ApplicationInfo applicationInfo) {
        for (String line : lines) {
            if (belongsTo(line, applicationInfo)) {
                return line;
            }
        }
        throw new AssertionError("APK is not mapped in the compatibility process: " + applicationInfo.sourceDir);
    }

    private static boolean belongsTo(String line, ApplicationInfo applicationInfo) {
        File source = new File(applicationInfo.sourceDir);
        File parent = source.getParentFile();
        return line.contains(applicationInfo.sourceDir)
                || line.contains(applicationInfo.nativeLibraryDir)
                || (parent != null && line.contains(parent.getAbsolutePath()));
    }

    private static void writePhaseOneMarker(Context context) throws Exception {
        File marker = markerFile(context);
        File parent = marker.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new AssertionError("Unable to create process-death marker directory");
        }
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(PHASE_ONE_MARKER_CONTENT.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static void resetHarness(Context context) {
        deleteRecursively(harnessRoot(context));
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new AssertionError("Unable to delete compatibility harness path: " + file.getAbsolutePath());
        }
    }

    private static float[] normalize(float[] values) {
        double squaredNorm = 0.0;
        for (float value : values) {
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (norm <= 0.0) {
            throw new AssertionError("Embedding norm is zero");
        }
        float[] normalized = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            normalized[index] = (float) (values[index] / norm);
        }
        return normalized;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) {
            output.append(String.format("%02x", value & 0xff));
        }
        return output.toString();
    }

    private static File harnessRoot(Context context) {
        return new File(context.getNoBackupFilesDir(), HARNESS_DIRECTORY_NAME);
    }

    private static File storeDirectory(Context context) {
        return new File(harnessRoot(context), STORE_DIRECTORY_NAME);
    }

    private static File modelFile(Context context) {
        return new File(harnessRoot(context), MODEL_FILE_NAME);
    }

    private static File markerFile(Context context) {
        return new File(harnessRoot(context), PHASE_ONE_MARKER_NAME);
    }

    private static void assertTrue(String message, boolean condition) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
